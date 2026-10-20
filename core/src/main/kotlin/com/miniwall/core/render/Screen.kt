package com.miniwall.core.render

import com.miniwall.core.layout.LayoutEngine
import com.miniwall.core.layout.LayoutNode
import com.miniwall.core.layout.Rect
import com.miniwall.core.logic.Flow
import com.miniwall.core.logic.PackageInfo
import com.miniwall.core.model.Align
import com.miniwall.core.model.Dimension
import com.miniwall.core.model.Distribution
import com.miniwall.core.model.Size
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

/**
 * One laid-out screen, ready to paint: the header pinned at the top, the root stack
 * (which scrolls inside [rootFrame] when [contentHeight] is taller), the footer pinned at
 * the bottom, and an open sheet over a dimmed backdrop. Mirrors the web renderer.
 */
class ScreenRender(
    val width: Double,
    val height: Double,
    val background: Fill?,
    val header: RenderNode?,
    val root: RenderNode,
    /** Height of the root's scrollable content (>= its frame height). */
    val contentHeight: Double,
    val footer: RenderNode?,
    val sheet: RenderNode?,
) {
    val rootFrame: Rect get() = root.layout.frame!!
}

/** Locale choice (the Web SDK's resolveLocale): exact, then same language, then the default. */
object Localization {
    fun resolve(available: Collection<String>, requested: String?, defaultLocale: String): String {
        if (!requested.isNullOrEmpty()) {
            val normalized = requested.replaceFirst('-', '_')
            // Sorted, like Swift (whose JSON objects have no order): the same screen picks the same language on both.
            val sorted = available.sorted()
            sorted.firstOrNull { it == normalized }?.let { return it }
            sorted.firstOrNull { it.substringBefore('_') == normalized.substringBefore('_') }?.let { return it }
        }
        return defaultLocale
    }
}

object ScreenLayout {
    /** Default package: the first marked `is_selected_by_default`, else the first one (root stack, then sticky footer). */
    fun defaultPackageId(base: JsonObject): String? {
        fun first(root: JsonElement?, predicate: (JsonObject) -> Boolean): String? {
            var found: String? = null
            fun visit(e: JsonElement?) {
                if (found != null) return
                when (e) {
                    is JsonObject -> {
                        if (e.str("type") == "package" && predicate(e)) { found = e.str("package_id"); return }
                        packageSearchChildren(e).forEach(::visit)
                    }
                    is JsonArray -> e.forEach(::visit)
                    else -> {}
                }
            }
            visit(root)
            return found
        }
        val stack = base["stack"]
        val footer = base["sticky_footer"]
        return first(stack) { it.bool("is_selected_by_default") == true }
            ?: first(stack) { true }
            ?: first(footer) { it.bool("is_selected_by_default") == true }
            ?: first(footer) { true }
    }

    /** Children the web's package search walks (getPaywallComponentChildNodes in style-utils.js). */
    private fun packageSearchChildren(node: JsonObject): List<JsonElement?> = when (node.str("type")) {
        "stack" -> listOf(node["components"], node.obj("badge")?.get("stack"))
        "button", "purchase_button", "redemption_button", "footer", "input_multiple_choice", "input_single_choice", "input_option", "tab", "tab_control_button", "package" -> listOf(node["stack"])
        "carousel" -> listOf(node["pages"])
        "countdown" -> listOf(node["countdown_stack"], node["end_stack"])
        "tabs" -> listOf(node.obj("control")?.get("stack"), node["tabs"])
        "timeline" -> (node["items"] as? JsonArray)?.flatMap { (it as? JsonObject)?.let { i -> listOf(i["icon"], i["title"], i["description"]) } ?: emptyList() } ?: emptyList()
        "express_purchase_button" -> listOf(node["wallet_unknown_stack"], node["wallet_available_stack"], node["wallet_unavailable_stack"])
        else -> emptyList()
    }

    /** A centered row the width of the screen holding [stack] (the header and footer wrappers). */
    private fun pinnedRow(stack: RenderNode, key: String, width: Double): Pair<LayoutNode, Double> {
        val row = LayoutNode(
            id = null, key = key, width = Size.Fixed(width), height = Size.Fit,
            dimension = Dimension.Horizontal(Align.START, Distribution.CENTER), children = listOf(stack.layout),
        )
        return row to LayoutEngine.autoHeight(row, width)
    }

    fun build(context: ScreenContext, state: ScreenState, width: Double, height: Double): ScreenRender? {
        val base = context.componentsConfig.obj("base") ?: return null
        val rootJson = base.obj("stack") ?: return null
        val headerJson = base.obj("header")?.obj("stack")
        val footerJson = base.obj("sticky_footer")?.obj("stack")
        val sheetJson = state.sheet
        val resolver = Resolver(context, state)
        val roots = listOfNotNull(
            rootJson to "root",
            headerJson?.let { it to "header" },
            footerJson?.let { it to "footer" },
            sheetJson?.obj("stack")?.let { it to "sheet" },
        )
        val resolved: Map<String, RenderNode?> = roots.map { it.second }.zip(resolver.resolveAll(roots)).toMap()
        val root = resolved["root"] ?: return null
        val header = resolved["header"]
        val footer = resolved["footer"]

        var headerHeight = 0.0
        header?.let {
            val (row, h) = pinnedRow(it, "header-row", width)
            headerHeight = h
            LayoutEngine.layoutAt(row, 0.0, 0.0, width, h)
        }
        var footerHeight = 0.0
        footer?.let {
            val (row, h) = pinnedRow(it, "footer-row", width)
            footerHeight = h
            LayoutEngine.layoutAt(row, 0.0, height - h, width, h)
        }
        // A screen that starts with a full-width image shows it under the header.
        val first = (rootJson["components"] as? JsonArray)?.firstOrNull() as? JsonObject
        val pullUnderHeader = header != null && first?.str("type") == "image" &&
            (first.obj("size")?.obj("width")?.str("type") == "fill")
        val top = if (pullUnderHeader) 0.0 else headerHeight
        val areaHeight = max(height - top - footerHeight, 0.0)
        val rl = root.layout
        val rootWidth = LayoutEngine.widthIn(rl, width)
        val rootHeight = max(areaHeight - rl.margin.vertical, rl.padding.vertical + 2 * rl.border)
        LayoutEngine.layoutAt(rl, rl.margin.leading, top + rl.margin.top, rootWidth, rootHeight)

        val rootFrame = rl.frame!!
        val contentBottom = rl.children.maxOfOrNull { c -> c.frame!!.let { it.y + it.height } + c.margin.bottom } ?: rootFrame.y
        val contentHeight = max(rootFrame.height, contentBottom - rootFrame.y + rl.padding.bottom + rl.border)

        val sheet = sheetJson?.let { json -> resolved["sheet"]?.let { sheetNode(context, resolver, json, it, width, height) } }
        val background = (base["background"] as? JsonObject)?.let { bg ->
            when (bg.str("type")) {
                "color" -> resolver.fill(bg["value"])
                "image" -> ((if (context.dark) bg.obj("value")?.obj("dark") else null) ?: bg.obj("value")?.obj("light"))
                    ?.let { f -> (f.str("webp") ?: f.str("original"))?.let { Fill.Image(it, bg.str("fit_mode") == "fill", resolver.fill(bg["color_overlay"])) } }
                else -> null
            }
        }
        return ScreenRender(width, height, background, header, root, contentHeight, footer, sheet)
    }

    /**
     * Height the screen needs at [width] when nothing forces it (banners, toasts, slide-ins, inline):
     * header + the root stack's content + footer.
     */
    fun intrinsicHeight(context: ScreenContext, state: ScreenState, width: Double): Double {
        val base = context.componentsConfig.obj("base") ?: return 0.0
        val rootJson = base.obj("stack") ?: return 0.0
        val roots = listOfNotNull(
            rootJson to "root",
            base.obj("header")?.obj("stack")?.let { it to "header" },
            base.obj("sticky_footer")?.obj("stack")?.let { it to "footer" },
        )
        val resolved = Resolver(context, state).resolveAll(roots)
        var total = 0.0
        for ((node, root) in resolved.zip(roots)) {
            node ?: continue
            total += if (root.second == "root") {
                LayoutEngine.autoHeight(node.layout, LayoutEngine.widthIn(node.layout, width)) + node.layout.margin.vertical
            } else {
                pinnedRow(node, "${root.second}-row", width).second
            }
        }
        return total
    }

    /** The sheet: full width at the bottom, its stack inside as in a block. */
    private fun sheetNode(context: ScreenContext, resolver: Resolver, json: JsonObject, stack: RenderNode, width: Double, height: Double): RenderNode {
        val sizeHeight = Size.decode(json.obj("size")?.get("height"))
        val box = LayoutNode(
            id = json.str("id"), key = "sheet-box", width = Size.Fixed(width), height = Size.Fit,
            dimension = Dimension.ZLayer(Align.START, Align.START), children = listOf(stack.layout),
        )
        val h = when (sizeHeight) {
            is Size.Fixed -> sizeHeight.value
            is Size.Relative -> sizeHeight.fraction * height
            else -> LayoutEngine.autoHeight(box, width)
        }
        LayoutEngine.layoutAt(box, 0.0, height - h, width, h)
        val bg = json.obj("background")
        val fill = when (bg?.str("type")) {
            "color" -> resolver.fill(bg["value"])
            else -> null
        }
        return RenderNode(key = "sheet-box", id = json.str("id"), type = "sheet", layout = box, paint = Paint(background = fill), children = listOf(stack))
    }
}

/** Something the host app handles: purchases, restores, links, custom actions, closing. */
sealed interface ScreenEvent {
    data class Purchase(val packageId: String, val actionId: String?) : ScreenEvent
    data object Restore : ScreenEvent
    data object Close : ScreenEvent
    /** A "Run your code" button, or `screen:<id>` / `screen:back` (handled by the session). */
    data class CustomAction(val actionId: String) : ScreenEvent
    data class OpenUrl(val url: String) : ScreenEvent
    data class CompleteWorkflow(val url: String, val method: String) : ScreenEvent
    data object CustomerCenter : ScreenEvent
    /** A question's answer changed: [value] is the tapped option id or the typed text. */
    data class Answer(val fieldId: String, val value: String) : ScreenEvent
    data class PackageSelected(val packageId: String) : ScreenEvent
}

/**
 * State and actions of one shown screen (the web renderer's handlers). Platforms call [render]
 * when the size or [state] changes, and [handle] when a node with an action is tapped.
 */
class ScreenController(
    private val paywall: JsonObject,
    locale: String?,
    private val dark: Boolean,
    private val variablesPerPackage: Map<String, Map<String, String>>,
    private val baseVariables: Map<String, String>,
    private val infoPerPackage: Map<String, PackageInfo>,
    private val showRestoreButton: Boolean,
    initialAnswers: Map<String, Flow.Answer> = emptyMap(),
    private val onEvent: (ScreenEvent) -> Unit,
) {
    private val config = paywall.obj("components_config") ?: JsonObject(emptyMap())
    private val localizations: Map<String, Map<String, String>> =
        (paywall["components_localizations"] as? JsonObject)?.mapValues { (_, t) ->
            (t as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap() ?: emptyMap()
        } ?: emptyMap()
    private val defaultLocale = paywall.str("default_locale") ?: "en_US"
    val locale: String = Localization.resolve(localizations.keys, locale, defaultLocale)
    val fields: Map<String, Flow.FieldKind> = Flow.questionFields(config)

    var state = ScreenState(
        selectedPackageId = config.obj("base")?.let { ScreenLayout.defaultPackageId(it) },
        choices = initialAnswers.mapNotNull { (k, v) ->
            if (fields[k] == Flow.FieldKind.TEXT) null else k to when (v) { is Flow.Answer.One -> listOf(v.value); is Flow.Answer.Many -> v.values }
        }.toMap(),
        texts = initialAnswers.mapNotNull { (k, v) -> if (fields[k] == Flow.FieldKind.TEXT && v is Flow.Answer.One) k to v.value else null }.toMap(),
    )
        private set

    /** The user's text size setting as a multiplier (the platform sets it before each render). */
    var fontScale: Double = 1.0

    /** Called after every state change, so the platform re-renders. */
    var onChange: () -> Unit = {}

    private fun update(next: ScreenState) {
        state = next
        onChange()
    }

    fun context(measurer: TextMeasurer) = ScreenContext(
        componentsConfig = config,
        localizations = localizations,
        defaultLocale = defaultLocale,
        locale = locale,
        dark = dark,
        variablesPerPackage = variablesPerPackage,
        baseVariables = baseVariables,
        infoPerPackage = infoPerPackage,
        showRestoreButton = showRestoreButton,
        fontScale = fontScale,
        measurer = measurer,
    )

    fun render(width: Double, height: Double, measurer: TextMeasurer, nowMs: Long = System.currentTimeMillis()): ScreenRender? {
        state = state.copy(nowMs = nowMs)
        return ScreenLayout.build(context(measurer), state, width, height)
    }

    fun handle(action: Action) {
        when (action) {
            is Action.SelectPackage -> if (state.selectedPackageId != action.packageId) {
                update(state.copy(selectedPackageId = action.packageId))
                onEvent(ScreenEvent.PackageSelected(action.packageId))
            }
            is Action.Purchase -> state.selectedPackageId?.let { onEvent(ScreenEvent.Purchase(it, action.actionId)) }
            is Action.SelectTab -> if (state.tabs[action.tabsKey] != action.index) update(state.copy(tabs = state.tabs + (action.tabsKey to action.index)))
            is Action.SelectOption -> {
                val current = state.choices[action.fieldId] ?: emptyList()
                val next = when {
                    !action.multiple -> listOf(action.optionId)
                    action.optionId in current -> current - action.optionId
                    else -> current + action.optionId
                }
                update(state.copy(choices = state.choices + (action.fieldId to next)))
                if (action.fieldId.isNotEmpty()) onEvent(ScreenEvent.Answer(action.fieldId, action.optionId))
                action.actionId?.let { onEvent(ScreenEvent.CustomAction(it)) }
            }
            is Action.OpenUrl -> onEvent(ScreenEvent.OpenUrl(action.url))
            is Action.Button -> button(action)
        }
    }

    private fun localized(id: String?): String? =
        if (id.isNullOrEmpty()) null else localizations[locale]?.get(id) ?: localizations[defaultLocale]?.get(id)

    private fun button(action: Action.Button) {
        val a = action.action
        when (a.str("type")) {
            "workflow" -> action.actionId?.takeIf { it.isNotEmpty() }?.let { onEvent(ScreenEvent.CustomAction(it)) }
            "complete_workflow" -> {
                val url = localized(a.obj("url")?.str("url_lid"))?.takeIf { it.isNotEmpty() } ?: return
                onEvent(ScreenEvent.CompleteWorkflow(url, a.obj("url")?.str("method") ?: "external_browser"))
            }
            "navigate_back" -> onEvent(ScreenEvent.Close)
            "restore_purchases" -> onEvent(ScreenEvent.Restore)
            "navigate_to" -> when (a.str("destination")) {
                "customer_center" -> onEvent(ScreenEvent.CustomerCenter)
                "screen_redirect" -> onEvent(ScreenEvent.CustomAction("screen_redirect"))
                "privacy_policy", "terms", "url" -> localized(a.obj("url")?.str("url_lid"))?.takeIf { it.isNotEmpty() }?.let { onEvent(ScreenEvent.OpenUrl(it)) }
                "sheet" -> {
                    val sheet = a.obj("sheet")
                    if (sheet != null) {
                        update(state.copy(sheet = sheet, sheetRootPackageId = state.selectedPackageId))
                    } else {
                        update(state.copy(sheet = null, selectedPackageId = state.sheetRootPackageId))
                    }
                }
                else -> {}
            }
        }
    }

    /** Closes an open sheet (backdrop tap, back gesture), restoring the package selected when it opened. */
    fun closeSheet() {
        if (state.sheet != null) update(state.copy(sheet = null, selectedPackageId = state.sheetRootPackageId))
    }

    fun setCarouselPage(key: String, page: Int) {
        if (state.carouselPages[key] != page) update(state.copy(carouselPages = state.carouselPages + (key to page)))
    }

    /** The visitor typed in a text field. [valid] is the keyboard type's own check (email, url, number). */
    fun setText(fieldId: String, value: String, valid: Boolean) {
        val errors = if (fieldId in state.textErrors && valid) state.textErrors - fieldId else state.textErrors
        update(state.copy(texts = state.texts + (fieldId to value), textErrors = errors))
        if (valid) onEvent(ScreenEvent.Answer(fieldId, value.trim()))
    }

    fun focus(fieldId: String?) = update(state.copy(focusedField = fieldId))

    /** Leaving a field shows its error state when the value is invalid. */
    fun blur(fieldId: String, valid: Boolean) {
        update(state.copy(focusedField = null, textErrors = if (valid) state.textErrors - fieldId else state.textErrors + fieldId))
    }

    /** Height the screen needs at [width] (banners, toasts, slide-ins, inline placement). */
    fun intrinsicHeight(width: Double, measurer: TextMeasurer): Double = ScreenLayout.intrinsicHeight(context(measurer), state, width)

    /**
     * The page behind the screen, for the area outside the layout (system bars): the paywall's
     * background, else the root stack's.
     */
    fun pageBackground(): Fill? {
        val resolver = Resolver(context(object : TextMeasurer {
            override fun measure(runs: List<com.miniwall.core.logic.TextLogic.Run>, font: FontSpec, maxWidth: Double?) = 0.0 to 0.0
        }), state)
        val base = config.obj("base")
        base?.obj("background")?.takeIf { it.str("type") == "color" }?.let { bg -> resolver.fill(bg["value"])?.let { return it } }
        val root = base?.obj("stack")
        return resolver.backgroundFill(root?.get("background_color"), root?.get("background"))
    }

    /** Whether the screen shows a countdown, so the platform ticks [render] every second. */
    val hasCountdown: Boolean by lazy {
        var found = false
        Flow.walk(config) { if (it.str("type") == "countdown") found = true }
        found
    }
}
