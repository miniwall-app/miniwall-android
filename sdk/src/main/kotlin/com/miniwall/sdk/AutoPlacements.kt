package com.miniwall.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.miniwall.core.session.AutoTriggers
import com.miniwall.core.session.PurchaseResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Placements that show by themselves in an app (D-120): when the app opens, when it reports an
 * event with [Miniwall.track], or a few seconds after it opens. The rules live in `AutoTriggers`
 * (core); this runs them against the app's lifecycle and shows one screen at a time.
 */
internal object AutoPlacements {
    private lateinit var triggers: AutoTriggers
    private var provider: (String) -> PresentOptions = { PresentOptions() }
    private var onResult: ((String, PurchaseResult) -> Unit)? = null
    private var started = false
    /** After [stop] and until the next [start]: events are not kept. */
    private var stopped = false
    private var loaded = false
    private var pendingOpen = false
    private val pendingEvents = ArrayList<String>()
    @Volatile private var showing = false
    /** Events tracked while a screen is up: replayed in order when it finishes (at most [MAX_QUEUED], the rest are dropped). */
    private val queuedEvents = ArrayDeque<String>()
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    /** Bumped by [stop]: work started before it (a screen, a delayed placement) must not touch the new state. */
    @Volatile private var generation = 0
    private const val MAX_QUEUED = 5
    private var current: WeakReference<Activity>? = null
    private var startedActivities = 0
    private var wentToBackground = false

    /** [visible]: the Activity on screen when this is called late (from JavaScript or Dart, after the app is up). */
    fun start(options: (String) -> PresentOptions, onResult: ((String, PurchaseResult) -> Unit)?, visible: Activity? = null) {
        provider = options
        this.onResult = onResult
        if (visible != null) {
            // The lifecycle callbacks below only see activities that start from now on: seed the one already up.
            current = WeakReference(visible)
            startedActivities = 1
        }
        stopped = false
        if (started) return
        started = true
        triggers = AutoTriggers(PrefsStore(Miniwall.appContext))
        // Starting the SDK at launch is itself an open: it plays once the first activity is visible.
        pendingOpen = true
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // Away from the app and back (the first started activity again) is a new open.
                if (startedActivities++ == 0 && wentToBackground && loaded) pendingOpen = true
                wentToBackground = false
                current = WeakReference(activity)
                Miniwall.scope.launch { refresh() }
            }
            override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // A rotation stops and restarts the activity: that is not the app going to the background.
                if (startedActivities == 0 && !activity.isChangingConfigurations) wentToBackground = true
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        lifecycleCallbacks = callbacks
        (Miniwall.appContext as Application).registerActivityLifecycleCallbacks(callbacks)
        if (visible != null) Miniwall.scope.launch { refresh() }
    }

    /**
     * Ends auto placements: the lifecycle callbacks are removed and every setting and queued event is cleared, so a
     * later [start] begins fresh. A screen already showing is left to finish; its result is not reported.
     */
    fun stop() {
        stopped = true
        if (!started) return
        started = false
        generation++
        lifecycleCallbacks?.let { (Miniwall.appContext as Application).unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
        provider = { PresentOptions() }
        onResult = null
        loaded = false
        pendingOpen = false
        showing = false
        current = null
        startedActivities = 0
        wentToBackground = false
        synchronized(pendingEvents) { pendingEvents.clear() }
        synchronized(queuedEvents) { queuedEvents.clear() }
    }

    /** Before the list loaded events wait in [pendingEvents]; while a screen shows they wait in [queuedEvents]. */
    fun track(event: String) {
        if (stopped) return
        if (!started || !loaded) {
            synchronized(pendingEvents) { pendingEvents.add(event); if (pendingEvents.size > 20) pendingEvents.removeAt(0) }
            return
        }
        handle(event)
    }

    private fun handle(event: String) {
        if (showing) {
            synchronized(queuedEvents) { if (queuedEvents.size < MAX_QUEUED) queuedEvents.addLast(event) }
            return
        }
        fire(triggers.event(event))
    }

    /** After a screen: plays the queued events in order, each under the one-at-a-time rule (one that shows holds back the rest). */
    private fun replayQueued() {
        while (!showing) {
            val event = synchronized(queuedEvents) { queuedEvents.removeFirstOrNull() } ?: return
            fire(triggers.event(event))
        }
    }

    /** Fetches the list (a 304 when unchanged), then plays what is waiting. */
    private suspend fun refresh() {
        val gen = generation
        val list = runCatching { Miniwall.client.fetch("/auto", "&platform=android") }.getOrNull()
        if (gen != generation || !started) return
        list?.let { triggers.load(it); loaded = true }
        if (!loaded) return
        val events = synchronized(pendingEvents) { pendingEvents.toList().also { pendingEvents.clear() } }
        if (pendingOpen && current?.get() != null) {
            pendingOpen = false
            val opened = triggers.appOpened()
            fire(opened.now)
            for (item in opened.delayed) Miniwall.scope.launch {
                delay(item.seconds.coerceAtLeast(0) * 1000L)
                if (gen == generation) fire(listOf(item.placement))
            }
        }
        // Through the same rule as a live event: the first may show a screen, the next ones wait their turn.
        for (event in events) handle(event)
    }

    /** Shows the first placement that does not skip; one screen at a time, events tracked meanwhile wait (up to 5) and play afterwards. */
    private fun fire(placements: List<String>) {
        val activity = current?.get()
        // Nothing shows once the app is known to be in the background (Android blocks the start of an activity from there).
        if (placements.isEmpty() || showing || Miniwall.presenting.get() > 0 || wentToBackground || activity == null || activity.isFinishing) return
        showing = true
        val gen = generation
        Miniwall.scope.launch {
            try {
                for (placement in placements) {
                    val options = provider(placement)
                    val result = try {
                        Miniwall.present(activity, ScreenSource.Placement(placement), options)
                    } catch (e: Exception) {
                        options.onError?.invoke(e)
                        continue
                    }
                    if (gen == generation) onResult?.invoke(placement, result)
                    if (result is PurchaseResult.Skipped) continue
                    return@launch
                }
            } finally {
                if (gen == generation) {
                    showing = false
                    replayQueued()
                }
            }
        }
    }
}
