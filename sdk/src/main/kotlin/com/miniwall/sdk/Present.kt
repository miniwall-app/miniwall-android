package com.miniwall.sdk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.miniwall.core.logic.DisplayWidth
import com.miniwall.core.logic.Flow
import com.miniwall.core.render.Fill
import com.miniwall.core.logic.PackageInfo
import com.miniwall.core.logic.Pricing
import com.miniwall.core.render.ScreenController
import com.miniwall.core.session.PaywallSession
import com.miniwall.core.session.PurchaseResult
import com.miniwall.core.session.Resolved
import com.miniwall.core.session.SessionHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Runs one presentation: the session, the host callbacks and the window it shows in. */
internal class PresentationCoordinator(private val activity: Activity?, private val options: PresentOptions) : SessionHost {
    var controller by mutableStateOf<ScreenController?>(null)
    var busy by mutableStateOf(false)
    var style: PresentationStyle = PresentationStyle.Fullscreen
    /** Widest the content gets (dp) on this presentation, from `maxContentWidth` or `size`; null = no limit. */
    var widthCap: Double? = null
        private set
    val closeOnBackdrop: Boolean get() = options.closeOnBackdrop
    private var session: PaywallSession? = null
    private var onFinish: ((PurchaseResult) -> Unit)? = null
    private var overlay: ComposeView? = null
    var hostActivity: Activity? = null
    private var activeId = -1
    /** Whether this presentation draws dark (its page background when the screen has none). */
    val dark: Boolean get() = Miniwall.isDark(options)
    private var lifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null

    /** True once the session ended (or never started): an activity opened for it has nothing to show. */
    val isFinished: Boolean get() = session == null

    fun start(resolved: Resolved, finish: (PurchaseResult) -> Unit) {
        onFinish = finish
        val locale = Miniwall.locale(options)
        val computed = Pricing.productVariables(options.products, locale)
        val variables = computed.toMutableMap()
        options.variablesPerPackage.forEach { (id, vars) -> variables[id] = (variables[id] ?: emptyMap()) + vars }
        val custom = options.customVariables.mapValues { (_, v) ->
            when (v) {
                is Double, is Float -> (v as Number).toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString() }
                else -> v.toString()
            }
        }
        val info = options.products.mapValues { (_, p) -> PackageInfo(hasIntroOffer = (p.offer?.price ?: 0.0) > 0, hasTrial = p.offer?.price == 0.0) }
        val dark = Miniwall.isDark(options)
        val showRestore = options.onRestore != null
        session = PaywallSession(
            first = resolved,
            memory = Miniwall.memory,
            enqueue = Miniwall.client::enqueue,
            loadScreen = { id, done -> Miniwall.scope.launch { done(runCatching { Miniwall.loadScreen(id, options.screens) }) } },
            controllerFactory = { json, answers, onEvent ->
                ScreenController(json, locale, dark, variables, custom, info, showRestore, answers, onEvent)
            },
            host = this,
            client = Miniwall.eventClient,
        )
    }

    suspend fun run(resolved: Resolved, style: PresentationStyle): PurchaseResult = suspendCancellableCoroutine { continuation ->
        val host = activity ?: return@suspendCancellableCoroutine continuation.resumeWith(Result.failure(MiniwallException("Miniwall: present needs an Activity")))
        // A banner or toast is a view inside the Activity: it needs the lifecycle owners only a ComponentActivity provides.
        // Any other Activity gets a sheet instead of a crash.
        val needsComponentActivity = (style is PresentationStyle.Banner || style is PresentationStyle.Toast) && host !is androidx.activity.ComponentActivity
        if (needsComponentActivity) {
            android.util.Log.w("Miniwall", "A banner or toast needs a ComponentActivity host (AppCompatActivity, FlutterFragmentActivity, ReactActivity...); ${host.javaClass.simpleName} is not one, so the screen opens as a sheet and does not close by itself.")
        }
        this.style = if (needsComponentActivity) PresentationStyle.Sheet else style
        widthCap = DisplayWidth.cap(
            when (this.style) {
                PresentationStyle.Modal -> "modal"
                PresentationStyle.Sheet -> "sheet"
                is PresentationStyle.Toast -> "toast"
                is PresentationStyle.Banner -> "banner"
                else -> "fullscreen"
            },
            DisplayWidth.resolveSize(options.size?.wire, resolved.display, options.style != null), options.maxContentWidth,
        )
        start(resolved) { if (continuation.isActive) continuation.resume(it) }
        when (this.style) {
            is PresentationStyle.Banner, is PresentationStyle.Toast -> showOverlay(host, this.style)
            else -> {
                val id = nextId.incrementAndGet()
                activeId = id
                active[id] = this
                host.startActivity(Intent(host, MiniwallActivity::class.java).putExtra(MiniwallActivity.EXTRA_ID, id))
            }
        }
        // The caller's coroutine can be cancelled from any thread; views and sessions are closed on the main one.
        continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { close() } }
    }

    fun close() {
        session?.close()
    }

    /** The system Back: previous screen of a flow, else close. */
    fun back() {
        session?.back()
    }

    /** Banners and toasts sit on top of the app without blocking it. */
    private fun showOverlay(activity: Activity, style: PresentationStyle) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val toast = style is PresentationStyle.Toast
        val top = (style as? PresentationStyle.Banner)?.top == true || (style as? PresentationStyle.Toast)?.top == true
        val view = ComposeView(activity).apply {
            setContent { OverlayContent(this@PresentationCoordinator, toast) }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (top) Gravity.TOP else Gravity.BOTTOM)
        content.addView(view, params)
        overlay = view
        // The overlay lives in this Activity: when the Activity goes away for good, so does the presentation.
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) { if (a === activity && !a.isChangingConfigurations) close() }
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
        }
        lifecycleCallbacks = callbacks
        activity.application.registerActivityLifecycleCallbacks(callbacks)
        if (style is PresentationStyle.Toast) {
            view.postDelayed({ close() }, (style.seconds * 1000).toLong())
        }
    }

    // ---------------------------------------------------------------- SessionHost

    override fun purchase(packageId: String, answers: Map<String, Flow.Answer>, done: (Result<Boolean>) -> Unit) {
        val onPurchase = options.onPurchase ?: return done(Result.failure(MiniwallException("Miniwall: pass onPurchase in PresentOptions to handle purchase buttons")))
        Miniwall.scope.launch { done(runCatching { onPurchase(PurchaseRequest(packageId, options.products[packageId], answers)) }) }
    }

    override fun restore(done: (Result<Boolean>) -> Unit) {
        val onRestore = options.onRestore ?: return done(Result.success(false))
        Miniwall.scope.launch { done(runCatching { onRestore() }) }
    }

    override fun action(actionId: String, answers: Map<String, Flow.Answer>, done: (Boolean) -> Unit) {
        val onAction = options.onAction ?: return done(true)
        Miniwall.scope.launch { done(onAction(actionId, answers)) }
    }

    override fun openUrl(url: String) {
        options.onOpenUrl?.invoke(url) ?: runCatching {
            val context = hostActivity ?: activity ?: Miniwall.appContext
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun answer(fieldId: String, value: Flow.Answer?, answers: Map<String, Flow.Answer>) {
        options.onAnswer?.invoke(fieldId, value, answers)
    }

    override fun error(error: Throwable) {
        options.onError?.invoke(error)
    }

    override fun show(controller: ScreenController, animated: Boolean) {
        this.controller = controller
    }

    override fun finish(result: PurchaseResult) {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        lifecycleCallbacks?.let { Miniwall.appContext.let { c -> (c as android.app.Application).unregisterActivityLifecycleCallbacks(it) } }
        lifecycleCallbacks = null
        active.remove(activeId)
        hostActivity?.finish()
        hostActivity = null
        onFinish?.invoke(result)
        onFinish = null
        session = null
        Miniwall.client.flush()
    }

    override fun busy(isBusy: Boolean) {
        busy = isBusy
    }

    companion object {
        val active = ConcurrentHashMap<Int, PresentationCoordinator>()
        private val nextId = AtomicInteger()
    }
}

/** The current screen of a presentation (flow steps replace it). */
@Composable
internal fun PresentedContent(coordinator: PresentationCoordinator, modifier: Modifier = Modifier) {
    val controller = coordinator.controller ?: return
    ScreenContent(controller, { coordinator.openUrl(it) }, coordinator.busy, modifier)
}

/**
 * Keeps a banner or toast clear of the system bars, the cutout and the keyboard. Unlike `safeDrawingPadding()` it reads the window's
 * insets itself: a host that consumes the insets it is given (React Native's root view) would otherwise leave the banner under the
 * status bar. Only the part of an inset that the view does not already clear by its position counts, so a host that lays out
 * below the bars gets no extra padding.
 */
@Composable
private fun Modifier.overlaySafePadding(): Modifier {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var padding by remember { mutableStateOf(androidx.compose.foundation.layout.PaddingValues(0.dp)) }
    DisposableEffect(view) {
        val update = {
            val root = if (view.isAttachedToWindow) ViewCompat.getRootWindowInsets(view) else null
            if (root != null) {
                val bars = root.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                val at = IntArray(2).also { view.getLocationInWindow(it) }
                val window = view.rootView
                val left = maxOf(0, bars.left - at[0])
                val top = maxOf(0, bars.top - at[1])
                val right = maxOf(0, bars.right - (window.width - (at[0] + view.width)))
                val bottom = maxOf(0, bars.bottom - (window.height - (at[1] + view.height)))
                val next = with(density) { androidx.compose.foundation.layout.PaddingValues(left.toDp(), top.toDp(), right.toDp(), bottom.toDp()) }
                if (next != padding) padding = next
            }
        }
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return this.padding(padding)
}

@Composable
private fun OverlayContent(coordinator: PresentationCoordinator, toast: Boolean) {
    val controller = coordinator.controller ?: return
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val width = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - if (toast) 32 else 0).toDouble()
        .let { w -> coordinator.widthCap?.let { minOf(w, it) } ?: w }
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val fontScale = Miniwall.fontScale(density)
    val height = remember(controller, width, fontScale) {
        controller.fontScale = fontScale // measured at the size it will be drawn
        controller.intrinsicHeight(width, ComposeMeasurer(measurer, density)).coerceAtMost(screenHeight * 0.4)
    }
    Box(Modifier.fillMaxWidth().overlaySafePadding().then(if (toast) Modifier.padding(horizontal = 16.dp) else Modifier), contentAlignment = Alignment.Center) {
        PresentedContent(
            coordinator,
            Modifier.capWidth(coordinator.widthCap).fillMaxWidth().height(height.dp).then(if (toast) Modifier.clip(RoundedCornerShape(16.dp)) else Modifier),
        )
    }
}

/**
 * Hosts fullscreen, modal and sheet presentations. Translucent, so a sheet shows the app behind
 * its scrim. Back closes the screen (`closed`); in a flow the screen's own back button goes back.
 */
class MiniwallActivity : ComponentActivity() {
    private var coordinator: PresentationCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // `get`, not `remove`: the system can recreate this Activity (font size, locale, dark mode...) and it must find the presentation again.
        val c = PresentationCoordinator.active[intent.getIntExtra(EXTRA_ID, -1)]
        if (c == null || c.isFinished) {
            // Recreated after the process died, or the presentation ended before this Activity opened: nothing to show.
            finish()
            return
        }
        coordinator = c
        c.hostActivity = this
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val controller = c.controller
                if (controller?.state?.sheet != null) controller.closeSheet() else c.back()
            }
        })
        setContent {
            when (c.style) {
                PresentationStyle.Sheet, PresentationStyle.Modal -> SheetHost(this, c, fullHeight = c.style == PresentationStyle.Modal)
                else -> {
                    // The screen's own page color goes edge to edge, behind the system bars, and the bar icons follow it.
                    val page = rememberPageBackground(c)
                    SystemBarIcons(this, page, c.dark, statusFromPage = true)
                    PageBackground(page, c.dark, Modifier.fillMaxSize()) {
                        PresentedContent(c, Modifier.fillMaxSize().safeDrawingPadding().capWidthCentered(c.widthCap))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closed by the system (not by the screen): report it as closed.
        if (isFinishing) coordinator?.takeIf { it.hostActivity === this }?.close()
    }

    companion object {
        const val EXTRA_ID = "com.miniwall.sdk.presentation"
    }
}

/** The page background of the presentation's current screen (a flow step replaces the controller, and with it this). */
@Composable
private fun rememberPageBackground(coordinator: PresentationCoordinator): Fill? {
    val controller = coordinator.controller
    return remember(controller) { controller?.pageBackground() }
}

/**
 * Light or dark status and navigation bar icons, chosen from the page behind them (a dark paywall gets light
 * icons). [statusFromPage] false for sheets: the status bar sits over the app behind the scrim, which the scrim
 * darkens, so it keeps light icons. Bars stay transparent; `enableEdgeToEdge` can be called again to restyle them.
 */
@Composable
private fun SystemBarIcons(activity: ComponentActivity, page: Fill?, dark: Boolean, statusFromPage: Boolean) {
    val light = page.isLight(dark)
    SideEffect {
        val transparent = android.graphics.Color.TRANSPARENT
        // `light` styles are for light backgrounds (dark icons); `dark` styles are for dark backgrounds (light icons).
        val pageStyle = if (light) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent)
        activity.enableEdgeToEdge(statusBarStyle = if (statusFromPage) pageStyle else SystemBarStyle.dark(transparent), navigationBarStyle = pageStyle)
    }
}

/** A bottom sheet over a scrim: full height (modal) or as tall as the content (slide-in). */
@Composable
private fun SheetHost(activity: ComponentActivity, coordinator: PresentationCoordinator, fullHeight: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val controller = coordinator.controller
    val page = rememberPageBackground(coordinator)
    SystemBarIcons(activity, page, coordinator.dark, statusFromPage = false)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    Box(Modifier.fillMaxSize()) {
        // The scrim is its own button (a sibling of the sheet, so it does not swallow the sheet's semantics).
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (visible) 0.4f else 0f))
                .then(
                    if (!coordinator.closeOnBackdrop) Modifier
                    else Modifier.semantics { contentDescription = "Close" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", role = Role.Button) { coordinator.close() },
                ),
        )
        // The sheet itself rises above the keyboard (the scrim stays full screen); the screen scrolls inside when it no longer fits.
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        val maxHeight = configuration.screenHeightDp * 0.92
        val fontScale = Miniwall.fontScale(density)
        val width = configuration.screenWidthDp.toDouble().let { w -> coordinator.widthCap?.let { minOf(w, it) } ?: w }
        val height = if (fullHeight || controller == null) maxHeight else remember(controller, fontScale, width) {
            controller.fontScale = fontScale // measured at the size it will be drawn
            controller.intrinsicHeight(width, ComposeMeasurer(measurer, density)).coerceAtMost(maxHeight)
        }
        // Taps on the sheet must not reach the scrim below it: swallow them without making the sheet one big unlabeled button.
        PageBackground(
            page, coordinator.dark,
            Modifier.capWidth(coordinator.widthCap).fillMaxWidth().height(height.dp).wrapContentHeight()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Edge to edge: keep buttons above the gesture bar (the keyboard is handled by the Box around the sheet).
            PresentedContent(coordinator, Modifier.fillMaxSize().navigationBarsPadding())
        }
        }
    }
}

/**
 * A screen inside your own Compose UI (onboarding step, pricing section), like the Web SDK's
 * `mountPaywall`. [onFinish] reports how it ended.
 *
 * Size: with a bounded height (a Box, a Column weight, a fixed `Modifier.height`) the screen fills it and scrolls
 * inside. In a scrolling parent (`verticalScroll`, `LazyColumn`) the height is unbounded, so the screen is as tall
 * as its content and does not scroll by itself.
 */
@Composable
fun MiniwallScreen(source: ScreenSource, options: PresentOptions = PresentOptions(), modifier: Modifier = Modifier, onFinish: (PurchaseResult) -> Unit = {}) {
    val coordinator = remember(source) { PresentationCoordinator(null, options) }
    var failed by remember(source) { mutableStateOf(false) }
    val finished by androidx.compose.runtime.rememberUpdatedState(onFinish)
    // Leaving the composition ends the session (it would otherwise keep listening for taps and results); the app
    // that removed the screen is not told it "closed".
    val disposed = remember(coordinator) { java.util.concurrent.atomic.AtomicBoolean(false) }
    androidx.compose.runtime.DisposableEffect(coordinator) { onDispose { disposed.set(true); coordinator.close() } }
    LaunchedEffect(source) {
        runCatching { Miniwall.resolve(source, options) }
            .onSuccess { resolved ->
                if (resolved.skip != null) {
                    failed = true
                    PaywallSession.skipped(resolved, Miniwall.memory, Miniwall.client::enqueue, client = Miniwall.eventClient).let { Miniwall.notify(options, it); finished(it) }
                } else {
                    FontFiles.load(resolved.paywall)
                    coordinator.start(resolved) { if (!disposed.get()) { Miniwall.notify(options, it); finished(it) } }
                }
            }
            .onFailure {
                failed = true
                options.onError?.invoke(it)
            }
    }
    if (!failed) PresentedContent(coordinator, modifier.capWidthCentered(options.maxContentWidth))
}

/** At most [cap] dp wide (no limit when null); for a child of a Box or Column that centers it. */
private fun Modifier.capWidth(cap: Double?): Modifier = if (cap == null) this else this.widthIn(max = cap.dp)

/** Fills its parent's height, at most [cap] dp wide and centered horizontally (no limit when null). Put after `fillMaxSize()`. */
private fun Modifier.capWidthCentered(cap: Double?): Modifier =
    if (cap == null) this else this.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = cap.dp).fillMaxWidth()
