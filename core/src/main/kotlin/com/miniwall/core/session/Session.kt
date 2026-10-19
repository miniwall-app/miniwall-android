package com.miniwall.core.session

import com.miniwall.core.logic.Flow
import com.miniwall.core.render.ScreenController
import com.miniwall.core.render.ScreenEvent
import kotlinx.serialization.json.JsonObject
import com.miniwall.core.logic.IsoTime

/** How a presented screen ended (PurchaseResult on the web). */
sealed interface PurchaseResult {
    val answers: Map<String, Flow.Answer>

    data class Purchased(val packageId: String, override val answers: Map<String, Flow.Answer>) : PurchaseResult
    data class Restored(override val answers: Map<String, Flow.Answer>) : PurchaseResult
    data class Closed(override val answers: Map<String, Flow.Answer>) : PurchaseResult
    /** A "Run your code" button was pressed and `onAction` did not keep the screen open. */
    data class ActionTaken(val actionId: String, override val answers: Map<String, Flow.Answer>) : PurchaseResult
    data class Skipped(val reason: SkipReason) : PurchaseResult {
        override val answers: Map<String, Flow.Answer> get() = emptyMap()
    }
}

/** The app's side of a shown screen. Every callback runs on the platform's main thread. */
interface SessionHost {
    /** Run the purchase; call [done] with true (bought), false (cancelled) or an error. */
    fun purchase(packageId: String, answers: Map<String, Flow.Answer>, done: (Result<Boolean>) -> Unit)
    /** Restore purchases; null [done] value means "no restore handler" (button hidden). */
    fun restore(done: (Result<Boolean>) -> Unit)
    /** A "Run your code" button. Call [done] with false to keep the screen open. */
    fun action(actionId: String, answers: Map<String, Flow.Answer>, done: (Boolean) -> Unit)
    fun openUrl(url: String)
    fun answer(fieldId: String, value: Flow.Answer?, answers: Map<String, Flow.Answer>)
    fun error(error: Throwable)
    /** Shows a new screen (flow step) built from [controller]. */
    fun show(controller: ScreenController, animated: Boolean)
    /** The screen is done: dismiss it (presented) and report [result]. */
    fun finish(result: PurchaseResult)
    /** True while a purchase/restore/action runs: the platform shows a spinner and ignores taps. */
    fun busy(isBusy: Boolean)
}

/**
 * One presentation of a screen and the flow behind it (sdks/web/src/render.ts):
 * "Go to screen" swaps in the next screen, answers carry across, and events reach the API.
 */
class PaywallSession(
    first: Resolved,
    private val memory: DeviceMemory,
    /** Queues analytics events (no-op when analytics are off or there is no API key). */
    private val enqueue: (JsonObject) -> Unit,
    /** Loads a linked screen by paywall id (the app's `screens`, else the published version). */
    private val loadScreen: (String, (Result<Resolved>) -> Unit) -> Unit,
    /** Builds the controller of one screen, wired to [onScreenEvent]. */
    private val controllerFactory: (paywall: JsonObject, answers: Map<String, Flow.Answer>, onEvent: (ScreenEvent) -> Unit) -> ScreenController,
    private val host: SessionHost,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Added to every event (platform, SDK and app version). */
    private val client: EventClient? = null,
) {
    private data class Screen(val paywall: JsonObject, val context: EventContext?, val controller: ScreenController)

    private val placement = first.context?.placement
    private val history = ArrayList<Screen>()
    private lateinit var current: Screen
    var answers: Map<String, Flow.Answer> = emptyMap()
        private set
    private val reported = HashSet<String>()
    private var isBusy = false
    private var finished = false
    /** The visitor closed the screen while a purchase, restore or action was running: decided when it answers. */
    private var closeRequested = false

    init {
        show(first.paywall!!, first.context, animated = false)
    }

    private fun track(type: String, context: EventContext? = current.context, extra: Map<String, String?> = emptyMap()) {
        context ?: return
        if (type == "view" || type == "holdout") {
            if (context.placement != null) {
                if (type == "view") memory.recordShown(context.placement)
                memory.rememberExposure(context, heldOut = type == "holdout")
            }
        }
        if (type == "purchase_click") extra["packageId"]?.let { memory.rememberPendingPurchase(context, it) }
        if (type == "purchase" || type == "purchase_cancel") memory.forgetPendingPurchase()
        enqueue(Events.event(type, context, memory.visitorId, IsoTime.format(clock()), extra, client))
    }

    /** Chosen options of the current screen, once each, when the visitor moves on. */
    private fun reportAnswers() {
        for ((fieldId, optionId) in Flow.chosenOptions(answers, current.controller.fields)) {
            val key = "${current.context?.paywallId}|$fieldId|$optionId"
            if (!reported.add(key)) continue
            track("answer", extra = mapOf("fieldId" to fieldId, "optionId" to optionId))
        }
    }

    private fun emit(type: String, extra: Map<String, String?> = emptyMap()) {
        if (type == "close" || type == "purchase" || type == "action" || type == "restore") reportAnswers()
        track(type, extra = extra)
    }

    private fun show(paywall: JsonObject, context: EventContext?, animated: Boolean) {
        val controller = controllerFactory(paywall, answers) { onScreenEvent(it) }
        current = Screen(paywall, context, controller)
        host.show(controller, animated)
        track("view")
        // Load linked screens now so "Continue" opens them at once.
        Flow.linkedScreens(paywall["components_config"]).forEach { id -> loadScreen(id) {} }
    }

    private fun runBusy(task: (done: () -> Unit) -> Unit) {
        if (isBusy || finished) return
        isBusy = true
        host.busy(true)
        task {
            isBusy = false
            host.busy(false)
        }
    }

    /** Applies a close that was asked for while busy, once the running call has been handled. */
    private fun settle() {
        if (closeRequested && !finished) {
            closeRequested = false
            close()
        }
    }

    private fun finish(result: PurchaseResult) {
        if (finished) return
        finished = true
        host.finish(result)
    }

    /** The visitor closed the screen another way (swipe down, system back, backdrop). */
    fun close() {
        if (finished) return
        // A purchase may still go through: its answer decides the result, then the close is applied.
        if (isBusy) { closeRequested = true; return }
        emit("close")
        finish(PurchaseResult.Closed(answers))
    }

    /** The system Back (Android): inside a flow it is "Back to previous screen"; on the first screen it closes. */
    fun back() {
        if (finished) return
        if (isBusy) close() else customAction("screen:back")
    }

    private fun onScreenEvent(event: ScreenEvent) {
        if (finished) return
        when (event) {
            is ScreenEvent.Purchase -> runBusy { done ->
                emit("purchase_click", mapOf("packageId" to event.packageId))
                host.purchase(event.packageId, answers) { result ->
                    done()
                    result.fold(
                        onSuccess = { bought ->
                            emit(if (bought) "purchase" else "purchase_cancel", mapOf("packageId" to event.packageId))
                            if (bought) finish(PurchaseResult.Purchased(event.packageId, answers))
                        },
                        onFailure = {
                            emit("purchase_error", mapOf("packageId" to event.packageId))
                            host.error(it)
                        },
                    )
                    settle()
                }
            }
            ScreenEvent.Restore -> runBusy { done ->
                host.restore { result ->
                    done()
                    result.fold(
                        onSuccess = { restored ->
                            if (restored) {
                                emit("restore")
                                finish(PurchaseResult.Restored(answers))
                            }
                        },
                        onFailure = host::error,
                    )
                    settle()
                }
            }
            ScreenEvent.Close -> close()
            is ScreenEvent.CustomAction -> customAction(event.actionId)
            is ScreenEvent.OpenUrl -> openUrl(event.url)
            // The Web SDK never wires `onCompleteWorkflowNavigate`, so the press does nothing there; same here.
            is ScreenEvent.CompleteWorkflow -> {}
            ScreenEvent.CustomerCenter -> {}
            is ScreenEvent.Answer -> {
                answers = Flow.applyAnswer(answers, current.controller.fields[event.fieldId], event.fieldId, event.value)
                host.answer(event.fieldId, answers[event.fieldId], answers)
            }
            is ScreenEvent.PackageSelected -> {}
        }
    }

    private fun openUrl(url: String) {
        if (!com.miniwall.core.logic.TextLogic.isOpenableUrl(url)) {
            host.error(IllegalArgumentException("Miniwall: not opening \"${url.take(40)}\": only http(s), mailto and tel links open"))
            return
        }
        host.openUrl(url)
    }

    private fun customAction(actionId: String) {
        when (val link = Flow.screenLink(actionId)) {
            Flow.Link.Back -> {
                // The first screen has nowhere to go back to: it closes.
                val previous = history.removeLastOrNull()
                if (previous == null) close() else {
                    current = previous.copy(controller = controllerFactory(previous.paywall, answers) { onScreenEvent(it) })
                    host.show(current.controller, true)
                    track("view")
                }
            }
            is Flow.Link.GoTo -> runBusy { done ->
                loadScreen(link.screenId) { result ->
                    done()
                    result.fold(
                        onSuccess = { next ->
                            val paywall = next.paywall ?: return@fold host.error(IllegalStateException("Miniwall: screen ${link.screenId} could not be loaded"))
                            reportAnswers()
                            history += current
                            // Every screen of a flow counts under the flow's placement.
                            show(paywall, next.context?.copy(placement = placement, experimentId = null, variant = null), animated = true)
                        },
                        onFailure = host::error,
                    )
                    settle()
                }
            }
            null -> runBusy { done ->
                emit("action", mapOf("actionId" to actionId))
                host.action(actionId, answers) { proceed ->
                    done()
                    if (proceed) finish(PurchaseResult.ActionTaken(actionId, answers))
                    settle()
                }
            }
        }
    }

    companion object {
        /** A placement that chose to show nothing: count the holdout and report the skip. */
        fun skipped(resolved: Resolved, memory: DeviceMemory, enqueue: (JsonObject) -> Unit, clock: () -> Long = System::currentTimeMillis, client: EventClient? = null): PurchaseResult.Skipped {
            val reason = resolved.skip!!
            if (reason == SkipReason.HOLDOUT) resolved.context?.let { ctx ->
                memory.rememberExposure(ctx, heldOut = true)
                enqueue(Events.event("holdout", ctx, memory.visitorId, IsoTime.format(clock()), client = client))
            }
            return PurchaseResult.Skipped(reason)
        }

        /**
         * A purchase that finished outside the screen (a later store transaction): attributes it to
         * the screen where buy was tapped, else to the last placement met (shown or held out).
         */
        fun reportPurchase(memory: DeviceMemory, enqueue: (JsonObject) -> Unit, packageId: String?, paywallId: String?, clock: () -> Long = System::currentTimeMillis, client: EventClient? = null) {
            val now = IsoTime.format(clock())
            val pending = memory.pendingPurchase()
            if (pending == null && paywallId == null) {
                val (ctx, heldOut) = memory.lastExposure() ?: return
                enqueue(Events.event(if (heldOut) "holdout_purchase" else "purchase", ctx, memory.visitorId, now, mapOf("packageId" to packageId), client))
                return
            }
            fun s(key: String) = (pending?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val id = paywallId ?: s("paywallId") ?: return
            memory.forgetPendingPurchase()
            val ctx = EventContext(id, s("version")?.toIntOrNull(), s("placement"), s("experimentId"), s("variant"))
            enqueue(Events.event("purchase", ctx, memory.visitorId, now, mapOf("packageId" to (packageId ?: s("packageId"))), client))
        }
    }
}
