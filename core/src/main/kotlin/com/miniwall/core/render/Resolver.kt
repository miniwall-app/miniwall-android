package com.miniwall.core.render

import com.miniwall.core.layout.LayoutNode
import com.miniwall.core.layout.Measurable
import com.miniwall.core.logic.Countdown
import com.miniwall.core.logic.Overrides
import com.miniwall.core.logic.PackageInfo
import com.miniwall.core.logic.TextLogic
import com.miniwall.core.model.Align
import com.miniwall.core.model.Dimension
import com.miniwall.core.model.Distribution
import com.miniwall.core.model.Size
import com.miniwall.core.model.Spacing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import com.miniwall.core.logic.IsoTime
import kotlin.math.max

/** Measures text with the platform's text engine (CoreText, Android text layout). */
interface TextMeasurer {
    /** Width and height of [runs] set in [font], wrapped at [maxWidth] (null: no wrapping, one line per `\n`). */
    fun measure(runs: List<TextLogic.Run>, font: FontSpec, maxWidth: Double?): Pair<Double, Double>
}

/** Everything a screen renders from that does not change while it is shown. */
class ScreenContext(
    /** `components_config` of the paywall, palette aliases still in place (resolved here). */
    val componentsConfig: JsonObject,
    val localizations: Map<String, Map<String, String>>,
    val defaultLocale: String,
    /** Locale key of [localizations] to show (see [com.miniwall.core.logic.Localization.resolve]). */
    val locale: String,
    val dark: Boolean,
    val variablesPerPackage: Map<String, Map<String, String>> = emptyMap(),
    /** Global and `custom.*` variables, under every package's own. */
    val baseVariables: Map<String, String> = emptyMap(),
    val infoPerPackage: Map<String, PackageInfo> = emptyMap(),
    /** App stores require a Restore button on mobile; shown when the host handles restore. */
    val showRestoreButton: Boolean = true,
    val hideBackButtons: Boolean = false,
    /** Hold "continue" buttons until required questions are answered (the web default). */
    val enforceRequiredInputs: Boolean = true,
    /** The user's text size setting (Dynamic Type / font scale) as a multiplier on every font size; 1 = as designed. */
    val fontScale: Double = 1.0,
    val measurer: TextMeasurer,
) {
    val colors: Map<String, String> =
        (componentsConfig["colors"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap() ?: emptyMap()
    val fonts: JsonObject? = componentsConfig["fonts"] as? JsonObject

    /** `getLocalizedString`: the selected locale, else the default locale; a present empty string stays empty. */
    fun localized(id: String?): String? {
        if (id.isNullOrEmpty()) return ""
        return localizations[locale]?.get(id) ?: localizations[defaultLocale]?.get(id)
    }
}

/** What changes while a screen is shown: the visitor's choices. Owned by [ScreenController]. */
data class ScreenState(
    val selectedPackageId: String? = null,
    /** Tabs node key -> selected tab index. */
    val tabs: Map<String, Int> = emptyMap(),
    /** Carousel node key -> current page. */
    val carouselPages: Map<String, Int> = emptyMap(),
    /** Choice question field id -> chosen option ids, in tap order. */
    val choices: Map<String, List<String>> = emptyMap(),
    /** Text question field id -> current text. */
    val texts: Map<String, String> = emptyMap(),
    /** Text fields whose value is invalid for their keyboard type (shown after leaving the field). */
    val textErrors: Set<String> = emptySet(),
    val focusedField: String? = null,
    /** The open sheet (`navigate_to` -> `sheet`), with the package selected when it opened. */
    val sheet: JsonObject? = null,
    val sheetRootPackageId: String? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

/**
 * Turns a screen's JSON and state into a [RenderNode] tree, the way the web components do:
 * overrides, visibility, variables, localization, package scope, tabs, questions, carousels.
 * Layout comes after ([com.miniwall.core.layout.LayoutEngine]); painting is the platform's.
 */
class Resolver(private val ctx: ScreenContext, private val state: ScreenState) {
    /** Field id -> satisfied, for required questions on screen (filled while resolving). */
    val fieldSatisfaction = LinkedHashMap<String, Boolean>()
    private var inputsSatisfied = true

    /** Scope inherited down the tree (Svelte contexts on the web). */
    private data class Scope(
        val variables: Map<String, String>,
        val packageInfo: PackageInfo?,
        val selected: Boolean = false,
        val choice: Choice? = null,
        val tabs: Tabs? = null,
    )

    private data class Choice(val fieldId: String, val multiple: Boolean, val atMax: Boolean)
    private data class Tabs(val key: String, val control: JsonObject?, val selectedIndex: Int, val tabIds: List<String?>)

    private fun rootScope(): Scope {
        val pkg = state.selectedPackageId
        return Scope(
            variables = ctx.baseVariables + (ctx.variablesPerPackage[pkg ?: ""] ?: emptyMap()),
            packageInfo = ctx.infoPerPackage[pkg ?: ""],
        )
    }

    /** Resolves [stack] (root, header, footer or sheet stack). Two passes: buttons wait for required answers. */
    fun resolveRoot(stack: JsonObject, key: String): RenderNode? {
        fieldSatisfaction.clear()
        inputsSatisfied = true
        val first = resolve(stack, key, rootScope())
        val satisfied = fieldSatisfaction.values.all { it }
        if (satisfied || !ctx.enforceRequiredInputs) return first
        inputsSatisfied = false
        return resolve(stack, key, rootScope())
    }

    /** Required-answer state across several roots (root stack + footer + sheet). */
    fun resolveAll(roots: List<Pair<JsonObject, String>>): List<RenderNode?> {
        fieldSatisfaction.clear()
        inputsSatisfied = true
        val first = roots.map { (json, key) -> resolve(json, key, rootScope()) }
        if (fieldSatisfaction.values.all { it } || !ctx.enforceRequiredInputs) return first
        inputsSatisfied = false
        return roots.map { (json, key) -> resolve(json, key, rootScope()) }
    }

    // ------------------------------------------------------------------ dispatch

    private fun resolve(json: JsonObject, key: String, scope: Scope): RenderNode? = when (json.str("type")) {
        "stack" -> stack(json, key, scope)
        "text" -> text(json, key, scope)
        "image" -> image(json, key, scope)
        "icon" -> icon(json, key, scope)
        "video" -> video(json, key, scope)
        "button" -> button(json, key, scope)
        "purchase_button" -> wrapped(json, key, scope, Action.Purchase((json["triggers"] as? JsonObject)?.str("on_purchase_press")), "purchase_button")
        "redemption_button" -> redemption(json, key, scope)
        "package" -> pkg(json, key, scope)
        "input_single_choice", "input_multiple_choice" -> choice(json, key, scope)
        "input_option" -> option(json, key, scope)
        "input_text" -> textField(json, key, scope)
        "carousel" -> carousel(json, key, scope)
        "tabs" -> tabs(json, key, scope)
        "tab_control" -> scope.tabs?.control?.obj("stack")?.let { stack(it, "$key/c", scope) }
        "tab_control_button" -> tabButton(json, key, scope)
        "tab_control_toggle" -> toggle(json, key, scope)
        "timeline" -> timeline(json, key, scope)
        "countdown" -> countdown(json, key, scope)
        "header", "footer" -> json.obj("stack")?.let { stack(it, "$key/s", scope) }
        // Web payment buttons (Apple Pay / Google Pay on the web) never show in an app.
        "wallet_button", "express_purchase_button", "skeleton_loader" -> null
        else -> json.obj("fallback")?.let { resolve(it, "$key/f", scope) }
    }

    private fun children(list: JsonElement?, key: String, scope: Scope): List<RenderNode> =
        (list as? JsonArray)?.mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { resolve(it, "$key/$i", scope) } } ?: emptyList()

    // ------------------------------------------------------------------ stack

    private fun stack(
        raw: JsonObject,
        key: String,
        scope: Scope,
        action: Action? = null,
        disabled: Boolean = false,
        semantics: Semantics = Semantics(),
    ): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        if (json.bool("visible") == false) return null
        val kids = children(json["components"], key, scope)
        val badgeJson = json.obj("badge")
        val badgeNode = badgeJson?.obj("stack")?.let { stack(it, "$key/badge", scope) }
        val badgeStyle = badgeJson?.str("style")
        val badgeAlignment = badgeJson?.str("alignment") ?: "top"
        val badge = if (badgeNode != null && (badgeStyle == "overlay" || badgeStyle == "nested")) badgeNode else null
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(json.obj("size")?.get("width")),
            height = Size.decode(json.obj("size")?.get("height")),
            dimension = Dimension.decode(json["dimension"]),
            spacing = json.num("spacing") ?: 0.0,
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            border = json.obj("border")?.num("width") ?: 0.0,
            children = kids.map { it.layout },
            badge = badge?.let {
                LayoutNode.Badge(
                    it.layout,
                    overlay = badgeStyle == "overlay",
                    top = badgeAlignment.startsWith("top"),
                    horizontal = when { badgeAlignment.endsWith("leading") -> Align.START; badgeAlignment.endsWith("trailing") -> Align.END; else -> Align.CENTER },
                )
            },
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "stack",
            layout = layout,
            paint = boxPaint(json, json["background_color"], json["background"], disabled),
            action = action,
            disabled = disabled,
            semantics = semantics.copy(isButton = semantics.isButton || action != null),
            children = kids,
            badge = badge,
        )
    }

    // ------------------------------------------------------------------ text

    private fun fontSize(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: return 14.0
        val n = p.content.toDoubleOrNull()
        if (n != null && n == Math.floor(n) && !n.isInfinite()) return n
        return FONT_SIZES[p.content] ?: 14.0
    }

    private fun font(json: JsonObject, nameKey: String = "font_name", weightIntKey: String = "font_weight_int", sizeKey: String = "font_size", weightKey: String? = "font_weight"): FontSpec {
        val name = json.str(nameKey)
        val declared = name != null && ctx.fonts?.containsKey(name) == true
        val family = if (declared) TextLogic.parseFontName(name!!)?.first else null
        val weight = json.num(weightIntKey)?.toInt() ?: weightKey?.let { FONT_WEIGHTS[json.str(it)] } ?: 400
        return FontSpec(name = if (declared) name else null, family = family, size = fontSize(json[sizeKey]) * ctx.fontScale, weight = weight)
    }

    private fun align(value: String?) = when (value) { "center" -> TextAlign.CENTER; "trailing" -> TextAlign.END; else -> TextAlign.START }

    private fun text(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val info = scope.packageInfo
        val json = Overrides.apply(
            raw,
            Overrides.State(
                selected = scope.selected,
                introOffer = info?.hasIntroOffer == true || info?.hasTrial == true,
                promoOffer = info?.hasPromoOffer == true,
            ),
        )
        val visible = Overrides.isVisible(raw["overrides"], raw.bool("visible"), state.selectedPackageId, info, scope.variables)
        if (!visible) return null
        val label = TextLogic.replaceVariables(ctx.localized(json.str("text_lid")), scope.variables)
        val runs = TextLogic.runs(label)
        val font = font(json)
        val content = Content.Text(
            runs = runs,
            font = font,
            color = fill(json["color"]) ?: Fill.Solid(Rgba.BLACK),
            align = align(json.str("horizontal_alignment")),
        )
        val padding = Spacing.decode(json["padding"])
        val margin = Spacing.decode(json["margin"])
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(json.obj("size")?.get("width")),
            height = Size.decode(json.obj("size")?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            // The web wraps the text in a block whose margin sits inside the sized wrapper.
            padding = Spacing(padding.top + margin.top, padding.bottom + margin.bottom, padding.leading + margin.leading, padding.trailing + margin.trailing),
            measurable = TextMeasurable(runs, font, ctx.measurer),
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "text",
            layout = layout,
            paint = Paint(background = fill(json["background_color"])),
            content = content,
            semantics = Semantics(label = content.plain),
        )
    }

    // ------------------------------------------------------------------ media

    private fun imageFiles(info: JsonObject?): JsonObject? {
        if (info == null) return null
        return (if (ctx.dark) info.obj("dark") else null) ?: info.obj("light")
    }

    private fun mask(json: JsonObject?): Mask = when (json?.str("type")) {
        "circle" -> Mask.Circle
        "concave" -> Mask.Concave
        "convex" -> Mask.Convex
        "rectangle" -> Mask.Rect(corners(json.obj("corners")))
        else -> Mask.Rect(Corners.NONE)
    }

    private fun image(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val files = imageFiles(json.obj("source")) ?: return null
        val url = files.str("original") ?: files.str("webp") ?: return null
        val w = files.num("width") ?: 0.0
        val h = files.num("height") ?: 0.0
        val size = json.obj("size")
        val width = Size.decode(size?.get("width"))
        val border = json.obj("border")
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = width,
            height = Size.decode(size?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            measurable = MediaMeasurable(w),
            shrink = width !is Size.Fixed,
            aspectRatio = if (w > 0 && h > 0) h / w else 0.0,
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "image",
            layout = layout,
            content = Content.Image(
                url = url,
                cover = json.str("fit_mode") == "fill",
                overlay = fill(json["color_overlay"]),
                mask = mask(json.obj("mask_shape")),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                shadow = shadow(json.obj("shadow")),
            ),
            semantics = Semantics(hidden = true),
        )
    }

    private fun video(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val files = imageFiles(json.obj("source"))
        val fallback = imageFiles(json.obj("fallback_source"))
        val url = files?.str("url") ?: return null
        val w = files.num("width") ?: fallback?.num("width") ?: 0.0
        val h = files.num("height") ?: fallback?.num("height") ?: 0.0
        val size = json.obj("size")
        val width = Size.decode(size?.get("width"))
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = width,
            height = Size.decode(size?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            measurable = MediaMeasurable(w),
            shrink = width !is Size.Fixed,
            aspectRatio = if (w > 0 && h > 0) h / w else 0.0,
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "video",
            layout = layout,
            content = Content.Video(
                url = url,
                fallbackImageUrl = fallback?.str("original"),
                cover = json.str("fit_mode") == "fill",
                autoPlay = json.bool("auto_play") ?: true,
                loop = json.bool("loop") ?: true,
                muted = json.bool("mute_audio") ?: true,
                showControls = json.bool("show_controls") ?: false,
                mask = mask(json.obj("mask_shape")),
                overlay = fill(json["color_overlay"]),
            ),
            semantics = Semantics(hidden = true),
        )
    }

    private fun icon(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val base = json.str("base_url") ?: return null
        val file = json.obj("formats")?.let { it.str("webp") ?: it.str("png") ?: it.str("svg") } ?: return null
        val background = json.obj("icon_background")
        val border = background?.obj("border")
        val size = json.obj("size")
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(size?.get("width")),
            height = Size.decode(size?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            border = border?.num("width") ?: 0.0,
            shrink = false,
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "icon",
            layout = layout,
            paint = Paint(
                background = fill(background?.get("color")),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                corners = shapeCorners(background?.obj("shape")),
                shadow = shadow(background?.obj("shadow")),
            ),
            content = Content.Icon(url = "${base.trimEnd('/')}/${file.trimStart('/')}", color = fill(json["color"])),
            semantics = Semantics(hidden = true),
        )
    }

    // ------------------------------------------------------------------ buttons and packages

    private fun hasText(e: JsonElement?): Boolean = when (e) {
        is JsonObject -> e.str("type") == "text" || hasText(e["components"]) || hasText(e["stack"])
        is JsonArray -> e.any(::hasText)
        else -> false
    }

    private fun wrapped(json: JsonObject, key: String, scope: Scope, action: Action, type: String, disabled: Boolean = false): RenderNode? {
        val stackJson = json.obj("stack") ?: return null
        val label = if (hasText(stackJson)) null else json.str("name")
        return stack(stackJson, key, scope, action = if (disabled) null else action, disabled = disabled, semantics = Semantics(isButton = true, label = label, disabled = disabled))
            ?.let { RenderNode(it.key, it.id, type, it.layout, it.paint, it.content, action.takeUnless { disabled }, disabled, it.semantics, it.children, it.badge) }
    }

    private fun button(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val action = json.obj("action") ?: return null
        val type = action.str("type")
        val destination = action.str("destination")
        val visible = when (type) {
            "restore_purchases" -> ctx.showRestoreButton
            "navigate_back" -> !ctx.hideBackButtons
            "navigate_to" -> destination != "web_paywall_link"
            else -> true
        }
        if (!visible) return null
        val actionId = (raw["triggers"] as? JsonObject)?.str("on_press")
        val disabled = (type == "workflow" || type == "complete_workflow") && actionId != "screen:back" && !inputsSatisfied
        return wrapped(raw, key, scope, Action.Button(action, actionId), "button", disabled)
    }

    private fun redemption(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val url = scope.variables["success.redemption_url"]?.takeIf { it.isNotEmpty() } ?: return null
        return wrapped(json, key, scope, Action.OpenUrl(url), "redemption_button")
    }

    private fun pkg(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val id = json.str("package_id") ?: return null
        val own = ctx.variablesPerPackage[id]
        val inner = scope.copy(
            variables = if (own == null) scope.variables else ctx.baseVariables + own,
            packageInfo = ctx.infoPerPackage[id] ?: scope.packageInfo,
            selected = state.selectedPackageId == id,
        )
        val stackJson = json.obj("stack") ?: return null
        val node = stack(stackJson, key, inner, action = Action.SelectPackage(id), semantics = Semantics(isButton = true, selected = inner.selected)) ?: return null
        return RenderNode(node.key, node.id, "package", node.layout, node.paint, null, node.action, false, node.semantics, node.children, node.badge)
    }

    // ------------------------------------------------------------------ questions

    private fun choice(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val fieldId = json.str("field_id") ?: ""
        val multiple = json.str("type") == "input_multiple_choice"
        val required = json.bool("required") ?: false
        val range = json.obj("selection_range")
        val min = range?.num("min")?.toInt()
        val max = range?.num("max")?.toInt()
        val count = state.choices[fieldId]?.size ?: 0
        val satisfied = when {
            !required && range == null -> true
            required && range == null -> count >= 1
            required && range != null -> if (min == max) count == min else count >= (min ?: 0) && count <= (max ?: Int.MAX_VALUE)
            else -> true
        }
        if (fieldId.isNotEmpty()) fieldSatisfaction[fieldId] = satisfied
        val inner = scope.copy(choice = Choice(fieldId, multiple, atMax = max != null && count >= max))
        return json.obj("stack")?.let { resolve(it, "$key/s", inner) }
    }

    private fun option(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val optionId = json.str("option_id") ?: return null
        val choice = scope.choice
        val chosen = choice != null && state.choices[choice.fieldId]?.contains(optionId) == true
        val disabled = choice != null && !chosen && choice.atMax
        val actionId = (json["triggers"] as? JsonObject)?.str("on_press")
        val inner = scope.copy(selected = chosen)
        val stackJson = json.obj("stack") ?: return null
        val action = choice?.let { Action.SelectOption(it.fieldId, optionId, it.multiple, actionId) }
        val node = stack(
            stackJson, key, inner,
            action = if (disabled) null else action,
            disabled = disabled,
            semantics = Semantics(isButton = true, selected = chosen),
        ) ?: return null
        return RenderNode(node.key, node.id, "input_option", node.layout, node.paint, null, node.action, disabled, node.semantics, node.children, node.badge)
    }

    private fun textField(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val fieldId = raw.str("field_id") ?: ""
        val json = Overrides.apply(raw, Overrides.State(focus = state.focusedField == fieldId, error = fieldId in state.textErrors))
        if (raw.bool("visible") == false) return null
        val required = json.bool("required") ?: false
        val value = state.texts[fieldId] ?: ""
        val valid = fieldId !in state.textErrors
        if (fieldId.isNotEmpty()) fieldSatisfaction[fieldId] = !required || (valid && value.trim().isNotEmpty())
        val font = font(json, weightKey = null)
        val placeholderFont = font(json, "placeholder_font_name", "placeholder_font_weight_int", "placeholder_font_size", null)
        val border = json.obj("border")
        val size = json.obj("size")
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(size?.get("width")),
            height = Size.decode(size?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            // The input's own padding sits inside the bordered wrapper.
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            border = border?.num("width") ?: 0.0,
            measurable = FieldMeasurable(font),
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "input_text",
            layout = layout,
            paint = Paint(
                background = fill(json["background_color"]),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                corners = shapeCorners(json.obj("shape")),
                shadow = shadow(json.obj("shadow")),
            ),
            content = Content.TextField(
                fieldId = fieldId,
                placeholder = TextLogic.replaceVariables(ctx.localized(json.str("placeholder_lid")), scope.variables),
                keyboard = json.str("keyboard_type") ?: "text",
                capitalize = json.str("capitalize") ?: "none",
                required = required,
                value = value,
                font = font,
                color = fill(json["color"]) ?: Fill.Solid(Rgba.BLACK),
                align = align(json.str("horizontal_alignment")),
                placeholderFont = placeholderFont,
                placeholderColor = fill(json["placeholder_color"]) ?: Fill.Solid(Rgba(0.0, 0.0, 0.0, 0.5)),
                placeholderAlign = align(json.str("placeholder_horizontal_alignment")),
            ),
        )
    }

    // ------------------------------------------------------------------ carousel

    private fun carousel(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val pagesJson = (json["pages"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        if (pagesJson.isEmpty()) return null
        val initial = json.num("initial_page_index")?.toInt()?.takeIf { it in pagesJson.indices } ?: 0
        val current = state.carouselPages[key]?.takeIf { it in pagesJson.indices } ?: initial
        val peek = json.num("page_peek") ?: 0.0
        val pageSpacing = json.num("page_spacing") ?: 0.0
        val pages = pagesJson.mapIndexedNotNull { i, page ->
            val node = stack(page, "$key/p$i", scope) ?: return@mapIndexedNotNull null
            // Each page is exactly one slider width (flex: 0 0 100%).
            val l = node.layout
            val sized = LayoutNode(
                id = l.id, key = l.key, width = Size.Relative(1.0), height = l.height, dimension = l.dimension, spacing = l.spacing,
                padding = l.padding, margin = l.margin, border = l.border, children = l.children, shrink = false, badge = l.badge,
            )
            RenderNode(node.key, node.id, node.type, sized, node.paint, node.content, node.action, node.disabled, node.semantics, node.children, node.badge)
        }
        val pageAlign = Align.decode(json.str("page_alignment"))
        val slider = LayoutNode(
            id = null,
            key = "$key/slider",
            width = Size.Fill,
            height = Size.Fit,
            dimension = Dimension.Horizontal(pageAlign, Distribution.START),
            spacing = pageSpacing,
            padding = Spacing(0.0, 0.0, peek + pageSpacing, peek + pageSpacing),
            children = pages.map { it.layout },
        )
        val auto = json.obj("auto_advance")
        val sliderNode = RenderNode(
            key = "$key/slider",
            id = null,
            type = "carousel_slider",
            layout = slider,
            children = pages,
            carousel = RenderNode.Carousel(
                key = key,
                pageCount = pages.size,
                currentPage = current,
                loop = json.bool("loop") ?: false,
                pageSpacing = pageSpacing,
                autoAdvanceMs = auto?.num("ms_time_per_page")?.toLong(),
                transitionMs = auto?.num("ms_transition_time")?.toLong() ?: 500,
            ),
        )
        val control = json.obj("page_control")?.let { pageControl(it, "$key/dots", pages.size, current) }
        val top = json.obj("page_control")?.str("position") == "top"
        val parts = listOfNotNull(if (top) control else null, sliderNode, if (top) null else control)
        val border = json.obj("border")
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.Fill,
            height = Size.Fit,
            dimension = Dimension.Vertical(Align.CENTER, Distribution.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            border = border?.num("width") ?: 0.0,
            children = parts.map { it.layout },
            shrink = false,
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "carousel",
            layout = layout,
            paint = Paint(
                background = backgroundFill(null, json["background"]),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                corners = shapeCorners(json.obj("shape")),
                shadow = shadow(json.obj("shadow")),
            ),
            children = parts,
            clipsChildren = true,
        )
    }

    private fun pageControl(json: JsonObject, key: String, count: Int, current: Int): RenderNode {
        val dots = (0 until count).map { i ->
            val indicator = json.obj(if (i == current) "active" else "default")
            val strokeWidth = indicator?.num("stroke_width")
            val strokeColor = indicator?.get("stroke_color")
            val hasStroke = strokeWidth != null && strokeColor != null && strokeColor !is JsonNull
            RenderNode(
                key = "$key/$i",
                id = null,
                type = "page_indicator",
                layout = LayoutNode(
                    id = null, key = "$key/$i",
                    width = Size.Fixed(indicator?.num("width") ?: 8.0),
                    height = Size.Fixed(indicator?.num("height") ?: 8.0),
                    dimension = Dimension.Vertical(Align.START, Distribution.START),
                    border = if (hasStroke) strokeWidth!! else 0.0,
                ),
                paint = Paint(
                    background = fill(indicator?.get("color")),
                    borderWidth = if (hasStroke) strokeWidth!! else 0.0,
                    borderFill = if (hasStroke) fill(strokeColor) else null,
                    corners = Corners(pill = true),
                ),
                semantics = Semantics(hidden = true),
            )
        }
        val border = json.obj("border")
        return RenderNode(
            key = key,
            id = null,
            type = "page_control",
            layout = LayoutNode(
                id = null, key = key, width = Size.Fit, height = Size.Fit,
                dimension = Dimension.Horizontal(Align.CENTER, Distribution.CENTER),
                spacing = json.num("spacing") ?: 0.0,
                padding = Spacing.decode(json["padding"]),
                margin = Spacing.decode(json["margin"]),
                border = border?.num("width") ?: 0.0,
                children = dots.map { it.layout },
            ),
            paint = Paint(
                background = fill(json["background_color"]),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                corners = shapeCorners(json.obj("shape")),
                shadow = shadow(json.obj("shadow")),
            ),
            children = dots,
            semantics = Semantics(label = "Page ${current + 1} of $count"),
        )
    }

    // ------------------------------------------------------------------ tabs

    private fun tabs(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val tabs = (json["tabs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val defaultIndex = max(0, tabs.indexOfFirst { it.str("id") == json.str("default_tab_id") })
        val index = state.tabs[key]?.takeIf { it in tabs.indices } ?: defaultIndex
        val inner = scope.copy(tabs = Tabs(key, json.obj("control"), index, tabs.map { it.str("id") }))
        val active = tabs.getOrNull(index)?.let { tab ->
            val tabJson = Overrides.apply(tab, Overrides.State(selected = scope.selected))
            tabJson.obj("stack")?.let { stack(it, "$key/t$index", inner) }
        }
        val border = json.obj("border")
        val size = json.obj("size")
        val kids = listOfNotNull(active)
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(size?.get("width")),
            height = Size.decode(size?.get("height")),
            // A block holding the active tab's stack: the same rules as a z-layer's first layer.
            dimension = Dimension.ZLayer(Align.START, Align.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            border = border?.num("width") ?: 0.0,
            children = kids.map { it.layout },
        )
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "tabs",
            layout = layout,
            paint = Paint(
                background = backgroundFill(null, json["background"]),
                borderWidth = border?.num("width") ?: 0.0,
                borderFill = fill(border?.get("color")),
                corners = shapeCorners(json.obj("shape")),
                shadow = shadow(json.obj("shadow")),
            ),
            children = kids,
        )
    }

    private fun tabButton(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val tabs = scope.tabs ?: return json.obj("stack")?.let { stack(it, key, scope) }
        val tabId = json.str("tab_id")
        val found = tabs.tabIds.indexOf(tabId)
        val index = if (found >= 0) found else json.num("tab_index")?.toInt() ?: -1
        val selected = tabs.tabIds.getOrNull(tabs.selectedIndex) == tabId
        val inner = scope.copy(selected = selected)
        val stackJson = json.obj("stack") ?: return null
        val action = if (index in tabs.tabIds.indices) Action.SelectTab(tabs.key, index) else null
        val node = stack(stackJson, key, inner, action = action, semantics = Semantics(isButton = true, selected = selected)) ?: return null
        return RenderNode(node.key, node.id, "tab_control_button", node.layout, node.paint, null, node.action, false, node.semantics, node.children, node.badge)
    }

    private fun toggle(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val tabs = scope.tabs
        val on = tabs?.selectedIndex == 1
        val json = Overrides.apply(raw, Overrides.State(selected = on))
        return RenderNode(
            key = key,
            id = json.str("id"),
            type = "tab_control_toggle",
            layout = LayoutNode(id = json.str("id"), key = key, width = Size.Fixed(51.0), height = Size.Fixed(31.0), dimension = Dimension.Vertical(Align.START, Distribution.START)),
            content = Content.Toggle(on, fill(json[if (on) "track_color_on" else "track_color_off"]), fill(json[if (on) "thumb_color_on" else "thumb_color_off"])),
            action = tabs?.let { Action.SelectTab(it.key, if (on) 0 else 1) },
            semantics = Semantics(isButton = true, label = "Toggle", selected = on),
        )
    }

    // ------------------------------------------------------------------ timeline

    private fun timeline(raw: JsonObject, key: String, scope: Scope): RenderNode? {
        val json = Overrides.apply(raw, Overrides.State(selected = scope.selected))
        val items = (json["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        if (items.isEmpty()) return null
        val gutter = json.num("column_gutter") ?: 0.0
        val textSpacing = json.num("text_spacing") ?: 0.0
        val itemSpacing = json.num("item_spacing") ?: 0.0
        // Grid "auto 1fr": the icon column is as wide as the widest icon.
        val icons = items.mapIndexed { i, item -> item.obj("icon")?.let { icon(it, "$key/r$i/icon", scope) } }
        val iconColumn = icons.maxOfOrNull { it?.let { n -> com.miniwall.core.layout.LayoutEngine.maxContent(n.layout).let { w -> (n.layout.width as? Size.Fixed)?.value?.plus(n.layout.margin.horizontal) ?: w } } ?: 0.0 } ?: 0.0
        val rows = items.mapIndexed { i, item ->
            val rowKey = "$key/r$i"
            val icon = icons[i]
            val connector = item.obj("connector")
            val texts = listOfNotNull(
                item.obj("title")?.let { text(it, "$rowKey/title", scope) },
                item.obj("description")?.let { text(it, "$rowKey/desc", scope) },
            )
            val content = RenderNode(
                key = "$rowKey/content", id = null, type = "timeline_content",
                layout = LayoutNode(
                    id = null, key = "$rowKey/content", width = Size.Fill, height = Size.Fit,
                    dimension = Dimension.Vertical(Align.START, Distribution.START), spacing = textSpacing,
                    margin = Spacing(0.0, itemSpacing, 0.0, 0.0), children = texts.map { it.layout },
                ),
                children = texts,
            )
            val iconSize = (icon?.layout?.width as? Size.Fixed)?.value ?: 24.0
            val margin = connector?.obj("margin")
            val iconCell = RenderNode(
                key = "$rowKey/iconcell", id = null, type = "timeline_icon",
                layout = LayoutNode(
                    id = null, key = "$rowKey/iconcell", width = Size.Fixed(iconColumn), height = Size.Fill,
                    dimension = Dimension.ZLayer(Align.START, Align.START), children = listOfNotNull(icon?.layout), shrink = false,
                ),
                children = listOfNotNull(icon),
            )
            val row = RenderNode(
                key = rowKey, id = item.str("id"), type = "timeline_item",
                layout = LayoutNode(
                    id = item.str("id"), key = rowKey, width = Size.Fill, height = Size.Fit,
                    dimension = Dimension.Horizontal(Align.START, Distribution.START), spacing = gutter,
                    children = listOf(iconCell.layout, content.layout),
                ),
                children = listOf(iconCell, content),
                connector = connector?.let {
                    RenderNode.Connector(
                        width = it.num("width") ?: 0.0,
                        fill = fill(it["color"]),
                        centerX = iconColumn / 2,
                        top = iconSize / 2 + (margin?.num("top") ?: 0.0),
                        bottomInset = (margin?.num("bottom") ?: 0.0) - iconSize / 2,
                    )
                },
            )
            row
        }
        val size = json.obj("size")
        val layout = LayoutNode(
            id = json.str("id"),
            key = key,
            width = Size.decode(size?.get("width")),
            height = Size.decode(size?.get("height")),
            dimension = Dimension.Vertical(Align.START, Distribution.START),
            padding = Spacing.decode(json["padding"]),
            margin = Spacing.decode(json["margin"]),
            children = rows.map { it.layout },
        )
        return RenderNode(key = key, id = json.str("id"), type = "timeline", layout = layout, children = rows)
    }

    // ------------------------------------------------------------------ countdown

    private fun countdown(json: JsonObject, key: String, scope: Scope): RenderNode? {
        val date = json.obj("style")?.str("date")
        val target = date?.let { IsoTime.parse(it) }
        val ended = target != null && Countdown.hasEnded(target, state.nowMs)
        val values = target?.let { Countdown.values(it, state.nowMs) } ?: Countdown.values(0, 1)
        val inner = scope.copy(variables = scope.variables + values)
        val stackJson = (if (ended) json.obj("end_stack") else null) ?: json.obj("countdown_stack") ?: return null
        return stack(stackJson, "$key/${if (ended) "end" else "run"}", inner)
    }

    // ------------------------------------------------------------------ paint helpers

    private fun color(info: JsonObject?): Rgba? {
        val type = info?.str("type") ?: return null
        val value = info.str("value") ?: return null
        return when (type) {
            "hex" -> Rgba.parse(value)
            "alias" -> Rgba.parse(ctx.colors[value] ?: value)
            else -> null
        }
    }

    /** A `ColorGradientScheme` (`{ light, dark }`) for the current color mode. */
    fun fill(scheme: JsonElement?): Fill? {
        val o = scheme as? JsonObject ?: return null
        val info = (if (ctx.dark) o["dark"] as? JsonObject else null) ?: o["light"] as? JsonObject ?: return null
        return when (info.str("type")) {
            "hex", "alias" -> color(info)?.let { Fill.Solid(it) }
            "linear" -> Fill.Linear(info.num("degrees") ?: 180.0, stops(info))
            "radial" -> Fill.Radial(stops(info))
            else -> null
        }
    }

    private fun stops(info: JsonObject): List<GradientStop> =
        (info["points"] as? JsonArray)?.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val c = o.str("color")?.let { Rgba.parse(ctx.colors[it] ?: it) } ?: return@mapNotNull null
            GradientStop(c, o.num("percent") ?: 0.0)
        } ?: emptyList()

    /** `mapBackground`: a `background` object wins, with `background_color` overriding its color. */
    fun backgroundFill(backgroundColor: JsonElement?, background: JsonElement?): Fill? {
        val bg = background as? JsonObject
        return when (bg?.str("type")) {
            null -> fill(backgroundColor)
            "color" -> fill(backgroundColor?.takeUnless { it is JsonNull } ?: bg["value"])
            "image" -> imageFiles(bg.obj("value"))?.let { files ->
                (files.str("webp") ?: files.str("original"))?.let { Fill.Image(it, cover = bg.str("fit_mode") == "fill", overlay = fill(bg["color_overlay"])) }
            }
            else -> null
        }
    }

    private fun corners(json: JsonObject?) = Corners(
        topLeading = json?.num("top_leading") ?: 0.0,
        topTrailing = json?.num("top_trailing") ?: 0.0,
        bottomTrailing = json?.num("bottom_trailing") ?: 0.0,
        bottomLeading = json?.num("bottom_leading") ?: 0.0,
    )

    private fun shapeCorners(shape: JsonObject?): Corners = when (shape?.str("type")) {
        "rectangle" -> corners(shape.obj("corners"))
        "circle", "pill" -> Corners(pill = true)
        else -> Corners.NONE
    }

    private fun shadow(json: JsonObject?): Shadow? {
        json ?: return null
        val c = fill(json["color"])?.approximateColor ?: return null
        return Shadow(json.num("x") ?: 0.0, json.num("y") ?: 0.0, json.num("radius") ?: 0.0, c)
    }

    private fun boxPaint(json: JsonObject, backgroundColor: JsonElement?, background: JsonElement?, disabled: Boolean): Paint {
        val border = json.obj("border")
        return Paint(
            background = backgroundFill(backgroundColor, background),
            borderWidth = border?.num("width") ?: 0.0,
            borderFill = fill(border?.get("color")),
            corners = shapeCorners(json.obj("shape")),
            shadow = shadow(json.obj("shadow")),
            opacity = if (disabled) 0.4 else 1.0,
        )
    }

    companion object {
        val FONT_SIZES = mapOf(
            "body_s" to 13.0, "body_m" to 14.0, "body_l" to 15.0, "body_xl" to 16.0,
            "heading_xs" to 14.0, "heading_s" to 16.0, "heading_m" to 18.0, "heading_l" to 20.0, "heading_xl" to 26.0, "heading_xxl" to 32.0,
        )
        val FONT_WEIGHTS = mapOf(
            "extra_light" to 100, "thin" to 200, "light" to 300, "regular" to 400, "medium" to 500,
            "semibold" to 600, "bold" to 700, "extra_bold" to 800, "black" to 900,
        )
    }
}

/** Text content measured by the platform; min-content is the widest word (no breaking inside words). */
class TextMeasurable(private val runs: List<TextLogic.Run>, private val font: FontSpec, private val measurer: TextMeasurer) : Measurable {
    private val maxWidth by lazy { if (runs.isEmpty()) 0.0 else measurer.measure(runs, font, null).first }
    private val minWidth by lazy {
        if (runs.isEmpty()) return@lazy 0.0
        runs.flatMap { run -> run.text.split(' ', '\n').filter { it.isNotEmpty() }.map { listOf(run.copy(text = it)) } }
            .maxOfOrNull { measurer.measure(it, font, null).first } ?: 0.0
    }
    private val heights = HashMap<Double, Double>()

    override fun minContentWidth() = minWidth
    override fun maxContentWidth() = maxWidth
    override fun heightForWidth(width: Double): Double =
        if (runs.isEmpty()) 0.0 else heights.getOrPut(width) { measurer.measure(runs, font, maxOf(width, minWidth)).second }
}

/** Images and videos: as wide as their file at most, any narrower width allowed. */
class MediaMeasurable(private val naturalWidth: Double) : Measurable {
    override fun minContentWidth() = 0.0
    override fun maxContentWidth() = naturalWidth
    override fun heightForWidth(width: Double) = 0.0
}

/** A one-line text input: its line is 1.5 x the font size, like the paywall's text. */
class FieldMeasurable(private val font: FontSpec) : Measurable {
    override fun minContentWidth() = 0.0
    // Chrome sizes an input with no width to about 20 characters.
    override fun maxContentWidth() = font.size * 0.55 * 20
    override fun heightForWidth(width: Double) = font.lineHeight
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
