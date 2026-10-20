package com.miniwall.sdk

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.miniwall.core.logic.Flow
import com.miniwall.core.logic.ProductInfo
import com.miniwall.core.session.Delivery
import com.miniwall.core.session.DeviceMemory
import com.miniwall.core.session.EventClient
import com.miniwall.core.session.EventContext
import com.miniwall.core.session.Events
import com.miniwall.core.session.PaywallSession
import com.miniwall.core.session.PurchaseResult
import com.miniwall.core.session.Resolved
import com.miniwall.core.session.SkipReason
import com.miniwall.core.session.AutoAttributes
import com.miniwall.core.session.TargetingInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.net.URLEncoder
import java.util.Locale

enum class ColorMode { LIGHT, DARK, SYSTEM }

/** Where a screen comes from. */
sealed interface ScreenSource {
    /** A placement set up in the dashboard (targeting, A/B test, display, schedule). */
    data class Placement(val name: String) : ScreenSource
    /** A published screen by its id. */
    data class PaywallId(val id: String) : ScreenSource
    /** A screen JSON you hold (e.g. bundled for offline use). No analytics. */
    data class Json(val json: JsonObject) : ScreenSource
}

/** How a screen shows. Default: the placement's display from the dashboard, else fullscreen. */
sealed interface PresentationStyle {
    data object Fullscreen : PresentationStyle
    /** Full-height sheet; back or a tap outside closes it. */
    data object Modal : PresentationStyle
    /** Bottom sheet as tall as the screen's content. */
    data object Sheet : PresentationStyle
    /** Pinned to the top or bottom; the app stays usable around it. */
    data class Banner(val top: Boolean) : PresentationStyle
    /** A small card at the bottom (or top) that closes itself after [seconds]. */
    data class Toast(val seconds: Double, val top: Boolean = false) : PresentationStyle

    companion object {
        /** The placement's `display` mapped to Android. */
        fun from(display: JsonObject?): PresentationStyle {
            fun s(key: String) = (display?.get(key) as? JsonPrimitive)?.content
            return when (s("format")) {
                "modal", "drawer", "launcher" -> Modal
                "slide_in" -> Sheet
                "banner" -> Banner(top = s("position") != "bottom")
                "toast" -> Toast(((display?.get("duration") as? JsonPrimitive)?.doubleOrNull ?: 6.0).coerceIn(3.0, 60.0), top = s("position") == "top")
                else -> Fullscreen
            }
        }
    }
}

/** Width of a presented screen on wide screens (modal, sheet, toast), like the Web SDK's `size`. Phones are narrower than all of them. */
enum class PresentationSize(val wire: String) { SMALL("small"), MEDIUM("medium"), LARGE("large") }

/** Where a banner or toast sits. */
enum class PresentationPosition { TOP, BOTTOM }

/** What your app gets when a purchase button is tapped. */
data class PurchaseRequest(val packageId: String, val product: ProductInfo?, val answers: Map<String, Flow.Answer>)

/** Per-call options of [Miniwall.present]. */
class PresentOptions(
    /** Prices per package id, to fill `{{ product.* }}` texts. */
    val products: Map<String, ProductInfo> = emptyMap(),
    /** Extra `{{ product.* }}` values per package, over the computed ones. */
    val variablesPerPackage: Map<String, Map<String, String>> = emptyMap(),
    /** Values for `{{ custom.name }}`. */
    val customVariables: Map<String, Any> = emptyMap(),
    /** Audience attributes for this call, over [Miniwall.setAttributes]. */
    val attributes: Map<String, Any?> = emptyMap(),
    val locale: String? = null,
    val colorMode: ColorMode? = null,
    /** Overrides the placement's display. */
    val style: PresentationStyle? = null,
    /** Screens a "Go to screen" button may open, by paywall id (for JSON sources). */
    val screens: Map<String, JsonObject> = emptyMap(),
    /**
     * Run your purchase (Play Billing or your billing library). Return true when bought, false when
     * cancelled; throw on failure. A spinner shows meanwhile. Required when the screen has a buy button.
     */
    val onPurchase: (suspend (PurchaseRequest) -> Boolean)? = null,
    /** "Restore purchases"; return true when something was restored. Without it the button is hidden. */
    val onRestore: (suspend () -> Boolean)? = null,
    /** A "Run your code" button. Return false to keep the screen open. */
    val onAction: (suspend (actionId: String, answers: Map<String, Flow.Answer>) -> Boolean)? = null,
    /** A question's answer changed. */
    val onAnswer: ((fieldId: String, value: Flow.Answer?, answers: Map<String, Flow.Answer>) -> Unit)? = null,
    /** Links in the screen. Default: open in the browser. */
    val onOpenUrl: ((String) -> Unit)? = null,
    val onError: ((Throwable) -> Unit)? = null,
    /** The user closed the screen (close button, Back, tap outside): the outcome is [PurchaseResult.Closed]. Not called for a purchase, restore or action. */
    val onClose: (() -> Unit)? = null,
    /** The placement's targeting chose to show nothing (frequency cap, audience, holdout): no screen was opened. */
    val onSkip: ((SkipReason) -> Unit)? = null,
    /** Widest the content gets, in dp; centered on wide screens (tablets, landscape). Wins over [size]. Default: no limit. */
    val maxContentWidth: Double? = null,
    /** Close a [PresentationStyle.Modal] or [PresentationStyle.Sheet] when the dimmed area around it is tapped. Back still closes. Default true. */
    val closeOnBackdrop: Boolean = true,
    /** Width of a modal, sheet or toast on wide screens (small 360/320/280, medium 440/380/360, large 560/440/440 dp). Overrides the placement's display. */
    val size: PresentationSize? = null,
    /** Where a banner or toast sits. Overrides the placement's display. */
    val position: PresentationPosition? = null,
)

/**
 * Entry point of the Miniwall Android SDK. Same concepts as the Web SDK (`configure`, `present`,
 * `preload`, `reportPurchase`). Miniwall renders screens; your app sells (D-109).
 */
object Miniwall {
    const val VERSION = "0.1.0"
    internal const val DEFAULT_API_URL = "https://api.miniwall.app"

    data class Options(
        /** Your API's base URL; the default is Miniwall's. */
        val apiUrl: String? = null,
        /** Locale for texts, e.g. `fr_FR`; default: the device's. */
        val locale: String? = null,
        val colorMode: ColorMode = ColorMode.SYSTEM,
        /** Send views, purchases and answers to Miniwall analytics. Default true. */
        val analytics: Boolean = true,
        /** Audience attributes (`user.<key>`), merged with [setAttributes]. */
        val attributes: Map<String, Any?> = emptyMap(),
        /** Milliseconds before a screen load gives up (the last cached copy is used when there is one). */
        val timeoutMs: Int = 8000,
        /** Largest text size multiplier honored from the system font scale (1 turns it off). Default 1.6. */
        val maxFontScale: Double = 1.6,
    )

    @Volatile internal var options = Options()
    internal lateinit var client: Client
    internal lateinit var memory: DeviceMemory
    internal lateinit var appContext: Context
    internal val scope: CoroutineScope = MainScope()
    private val attributes = HashMap<String, Any>()

    /** Screens open now (yours and automatic ones): a trigger that fires meanwhile is dropped. */
    internal val presenting = java.util.concurrent.atomic.AtomicInteger()

    /** Call once at startup (Application.onCreate): `Miniwall.configure(this, "pk_...")`. */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, apiKey: String, options: Options = Options()) {
        appContext = context.applicationContext
        if (!::client.isInitialized) client = Client(File(appContext.cacheDir, "miniwall"))
        if (!::memory.isInitialized) memory = DeviceMemory(PrefsStore(appContext))
        this.options = options
        client.apiKey = apiKey
        options.apiUrl?.let { client.apiUrl = it }
        client.timeoutMs = options.timeoutMs
        client.analytics = options.analytics
        synchronized(attributes) { options.attributes.forEach { (k, v) -> if (v == null) attributes.remove(k) else attributes[k] = v } }
    }

    /** Adds or changes audience attributes, e.g. after sign-in: `setAttributes(mapOf("plan" to "pro"))`. `null` removes one. */
    @JvmStatic
    fun setAttributes(values: Map<String, Any?>) {
        synchronized(attributes) { values.forEach { (k, v) -> if (v == null) attributes.remove(k) else attributes[k] = v } }
    }

    private fun requireConfigured() {
        check(::client.isInitialized) { "Miniwall: call Miniwall.configure(context, apiKey) first" }
    }

    /**
     * Shows a screen and suspends until it closes. A placement may decide to show nothing
     * ([PurchaseResult.Skipped]: frequency cap, audience, holdout). Throws when the screen cannot be loaded.
     */
    suspend fun present(activity: Activity, source: ScreenSource, options: PresentOptions = PresentOptions()): PurchaseResult {
        requireConfigured()
        val resolved = resolve(source, options)
        if (resolved.skip != null) return PaywallSession.skipped(resolved, memory, client::enqueue, client = eventClient).also { notify(options, it) }
        presenting.incrementAndGet()
        try {
            FontFiles.load(resolved.paywall)
            val style = (options.style ?: PresentationStyle.from(resolved.display)).positioned(options.position)
            // Views and the session belong on the main thread, whatever thread the caller is on.
            return withContext(Dispatchers.Main.immediate) { PresentationCoordinator(activity, options).run(resolved, style) }
                .also { notify(options, it) }
        } finally {
            presenting.decrementAndGet()
        }
    }

    /** [present] for Java and callback code. */
    @JvmStatic
    @JvmOverloads
    fun present(activity: Activity, source: ScreenSource, options: PresentOptions = PresentOptions(), callback: (Result<PurchaseResult>) -> Unit) {
        scope.launch { callback(runCatching { present(activity, source, options) }) }
    }

    /** Loads placements' screens and images ahead of time, so [present] opens at once. */
    suspend fun preload(placements: List<String>) {
        requireConfigured()
        coroutineScope {
            placements.map { name ->
                async(Dispatchers.IO) {
                    runCatching {
                        val body = client.fetch("/placements/${enc(name)}")
                        Delivery.pickSide(body, memory.visitorId).paywall.also { FontFiles.load(it); preloadImages(it) }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Starts the placements that show by themselves (set in the dashboard under "Show it": when the
     * app opens, after a delay, or on an event you report with [track]). Call once after [configure].
     * [options] gives each placement's prices and purchase handlers.
     */
    @JvmStatic
    @JvmOverloads
    fun startAutoPlacements(
        onResult: ((placement: String, result: PurchaseResult) -> Unit)? = null,
        options: (placement: String) -> PresentOptions = { PresentOptions() },
        /** Pass the Activity on screen when you call this after the app is up (not from Application.onCreate). */
        activity: Activity? = null,
    ) {
        requireConfigured()
        AutoPlacements.start(options, onResult, activity)
    }

    /**
     * Stops auto placements (e.g. on sign-out): no placement shows by itself and queued events are forgotten.
     * [startAutoPlacements] can be called again later and starts from scratch.
     */
    @JvmStatic
    fun stopAutoPlacements() = AutoPlacements.stop()

    /** Tells Miniwall something happened in the app, e.g. `Miniwall.track("level_completed")`. */
    @JvmStatic
    fun track(event: String) = AutoPlacements.track(event)

    /** The package ids a screen's cards use: the keys your app passes in `products`. */
    @JvmStatic
    fun packageIds(paywall: JsonObject): List<String> {
        val ids = LinkedHashSet<String>()
        Flow.walk((paywall["components_config"] as? JsonObject)?.get("base")) { node ->
            if ((node["type"] as? JsonPrimitive)?.content == "package") (node["package_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        return ids.toList()
    }

    /**
     * Records a purchase that finished outside the screen (e.g. a later Play Billing update).
     * Credited to the screen where buy was tapped, else to the last placement shown or held out.
     */
    @JvmStatic
    @JvmOverloads
    fun reportPurchase(packageId: String? = null, paywallId: String? = null) {
        requireConfigured()
        PaywallSession.reportPurchase(memory, client::enqueue, packageId, paywallId, client = eventClient)
        client.flush()
    }

    /** Forgets this device's visitor id, frequency counters and exposures (e.g. on sign-out). */
    @JvmStatic
    @JvmOverloads
    fun reset(placements: List<String> = emptyList()) {
        requireConfigured()
        memory.reset(placements.map { DeviceMemory.SHOWN_PREFIX + it })
    }

    // ------------------------------------------------------------------ internal

    /** The user's text size setting as the multiplier the core applies, within [Options.maxFontScale]. */
    internal fun fontScale(density: androidx.compose.ui.unit.Density): Double =
        density.fontScale.toDouble().coerceIn(0.85, maxOf(1.0, options.maxFontScale))

    /** `onClose` / `onSkip` as the Web SDK fires them: on a close by the user, and when targeting showed nothing. */
    internal fun notify(options: PresentOptions, result: PurchaseResult) {
        when (result) {
            is PurchaseResult.Closed -> options.onClose?.invoke()
            is PurchaseResult.Skipped -> options.onSkip?.invoke(result.reason)
            else -> {}
        }
    }

    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal fun isDark(options: PresentOptions): Boolean = when (options.colorMode ?: this.options.colorMode) {
        ColorMode.DARK -> true
        ColorMode.LIGHT -> false
        ColorMode.SYSTEM -> (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    internal fun locale(options: PresentOptions): String =
        options.locale ?: this.options.locale ?: Locale.getDefault().toString()

    private fun primitive(v: Any): JsonPrimitive = when (v) {
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    /** The app's `versionName` (`2.3.1`), the `app_version` audience fact; null when the package manager cannot say. */
    private val appVersionName: String? by lazy {
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
    }

    /** Sent on every event: platform, SDK version, the app's version name. */
    internal val eventClient: EventClient get() = EventClient("android", VERSION, AutoAttributes.appVersion(appVersionName))

    /** `X-Miniwall-App` for the server's Allowed apps check; null when the package name is unknown. */
    internal val appHeader: String? get() = Events.appHeader("android", runCatching { appContext.packageName }.getOrNull())

    internal fun targeting(options: PresentOptions): TargetingInput {
        val merged = synchronized(attributes) { HashMap(attributes) }
        options.attributes.forEach { (k, v) -> if (v == null) merged.remove(k) else merged[k] = v }
        val tablet = appContext.resources.configuration.smallestScreenWidthDp >= 600
        return TargetingInput(
            merged.mapValues { primitive(it.value) }, locale(options), if (tablet) "tablet" else "mobile", "android",
            osVersion = AutoAttributes.osVersion(android.os.Build.VERSION.RELEASE),
            appVersion = AutoAttributes.appVersion(appVersionName),
        )
    }

    internal suspend fun resolve(source: ScreenSource, options: PresentOptions): Resolved = when (source) {
        is ScreenSource.Placement -> Delivery.decide(client.fetch("/placements/${enc(source.name)}"), targeting(options), memory)
        is ScreenSource.PaywallId -> loadScreen(source.id, emptyMap())
        is ScreenSource.Json -> Resolved(
            Delivery.asPaywall(source.json) ?: throw MiniwallException("Miniwall: not a screen JSON (expected components_config.base.stack)"),
            null,
        )
    }

    /** The screen a "Go to screen" button opens: the app's own JSON when given, else the published one. */
    internal suspend fun loadScreen(id: String, screens: Map<String, JsonObject>): Resolved {
        screens[id]?.let { local ->
            return Resolved(Delivery.asPaywall(local) ?: throw MiniwallException("Miniwall: screens[$id] is not a screen JSON"), null)
        }
        val body = client.fetch("/paywalls/${enc(id)}")
        val paywall = Delivery.asPaywall(body["paywall"]) ?: throw MiniwallException("Miniwall: screen $id is not a screen JSON")
        val context = (body["paywallId"] as? JsonPrimitive)?.content?.let { EventContext(it, (body["version"] as? JsonPrimitive)?.content?.toIntOrNull()) }
        return Resolved(paywall, context)
    }

    /** Images of a screen, fetched ahead (preload). */
    internal suspend fun preloadImages(paywall: JsonObject?) {
        val urls = mutableListOf<String>()
        val icons = mutableListOf<String>()
        Flow.walk(paywall?.get("components_config")) { node ->
            for (key in listOf("source", "value", "fallback_source")) {
                val files = ((node[key] as? JsonObject)?.get("light") as? JsonObject) ?: continue
                ((files["original"] ?: files["webp"]) as? JsonPrimitive)?.content?.let(urls::add)
            }
            if ((node["type"] as? JsonPrimitive)?.content == "icon") {
                val base = (node["base_url"] as? JsonPrimitive)?.content
                val file = ((node["formats"] as? JsonObject)?.get("webp") as? JsonPrimitive)?.content
                if (base != null && file != null) (if (file.substringBefore('?').endsWith(".svg", ignoreCase = true)) icons else urls).add("$base/$file")
            }
        }
        coroutineScope {
            urls.map { async { Images.load(it) } }.awaitAll()
            icons.toSet().map { async { Icons.load(it) } }.awaitAll()
        }
    }
}

/** This style at [position]; styles without a position (fullscreen, modal, sheet) stay as they are. */
internal fun PresentationStyle.positioned(position: PresentationPosition?): PresentationStyle = when {
    position == null -> this
    this is PresentationStyle.Banner -> PresentationStyle.Banner(top = position == PresentationPosition.TOP)
    this is PresentationStyle.Toast -> copy(top = position == PresentationPosition.TOP)
    else -> this
}
