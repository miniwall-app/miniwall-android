package com.miniwall.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The screen document the dashboard publishes (`PaywallData`, described by `schema/paywall.schema.json` in miniwall-spec).
 * Decoding is lenient by design: an unknown field is ignored, an unknown node type
 * becomes [Component.Unsupported] (rendered through its `fallback`, else skipped),
 * a malformed value falls back to the web renderer's default. A screen never
 * fails to decode because of one bad node.
 */
class PaywallData(
    val id: String,
    val defaultLocale: String,
    val colors: Map<String, String>,
    val base: RootPaywall,
    /** locale -> (key -> string). */
    val localizations: Map<String, Map<String, String>>,
    /** The source document, for fields the typed model does not cover yet. */
    val raw: JsonObject,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Throws [PaywallDecodingException] only when the document has no root stack. */
        fun decode(text: String): PaywallData = decode(json.parseToJsonElement(text).jsonObject)

        fun decode(obj: JsonObject): PaywallData {
            val config = obj.obj("components_config") ?: throw PaywallDecodingException("components_config missing")
            val base = config.obj("base") ?: throw PaywallDecodingException("components_config.base missing")
            val stack = base.obj("stack")?.let { Component.decode(it) } as? Component.Stack
                ?: throw PaywallDecodingException("components_config.base.stack missing or not a stack")
            return PaywallData(
                id = obj.string("id") ?: "",
                defaultLocale = obj.string("default_locale") ?: "en_US",
                colors = config.obj("colors")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNullSafe()?.let { k to it } }?.toMap() ?: emptyMap(),
                base = RootPaywall(
                    stack = stack,
                    header = base.obj("header")?.let { Component.decode(it) },
                    stickyFooter = base.obj("sticky_footer")?.let { Component.decode(it) },
                    background = base.obj("background"),
                ),
                localizations = obj.obj("components_localizations")?.mapValues { (_, table) ->
                    (table as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNullSafe()?.let { k to it } }?.toMap() ?: emptyMap()
                } ?: emptyMap(),
                raw = obj,
            )
        }
    }
}

class PaywallDecodingException(message: String) : Exception(message)

class RootPaywall(
    val stack: Component.Stack,
    val header: Component?,
    val stickyFooter: Component?,
    val background: JsonObject?,
)

/** Width or height of a node (`SizeType` on the web). */
sealed interface Size {
    data class Fixed(val value: Double) : Size
    /** Fraction of the parent's content box (`0.5` = 50%). */
    data class Relative(val fraction: Double) : Size
    data object Fit : Size
    data object Fill : Size

    companion object {
        fun decode(e: JsonElement?): Size {
            val o = e as? JsonObject ?: return Fit
            return when (o.string("type")) {
                "fixed" -> Fixed(o.double("value") ?: 0.0)
                "relative" -> Relative(o.double("value") ?: 1.0)
                "fill" -> Fill
                else -> Fit
            }
        }
    }
}

data class Spacing(val top: Double, val bottom: Double, val leading: Double, val trailing: Double) {
    val horizontal get() = leading + trailing
    val vertical get() = top + bottom

    companion object {
        val ZERO = Spacing(0.0, 0.0, 0.0, 0.0)
        fun decode(e: JsonElement?): Spacing {
            val o = e as? JsonObject ?: return ZERO
            return Spacing(o.double("top") ?: 0.0, o.double("bottom") ?: 0.0, o.double("leading") ?: 0.0, o.double("trailing") ?: 0.0)
        }
    }
}

/** Main-axis distribution (`justify-content` on the web). Unknown values map to center, like the web. */
enum class Distribution {
    START, CENTER, END, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY;

    companion object {
        fun decode(value: String?) = when (value) {
            "start" -> START
            "end" -> END
            "space_between" -> SPACE_BETWEEN
            "space_around" -> SPACE_AROUND
            "space_evenly" -> SPACE_EVENLY
            else -> CENTER
        }
    }
}

/** Position on one axis: leading/top, center, trailing/bottom. */
enum class Align {
    START, CENTER, END;

    val factor: Double get() = when (this) { START -> 0.0; CENTER -> 0.5; END -> 1.0 }

    companion object {
        /** Cross-axis alignment of a vertical/horizontal stack. Unknown values map to center, like the web. */
        fun decode(value: String?) = when (value) {
            "leading", "top" -> START
            "trailing", "bottom" -> END
            else -> CENTER
        }
    }
}

sealed interface Dimension {
    data class Vertical(val alignment: Align, val distribution: Distribution) : Dimension
    data class Horizontal(val alignment: Align, val distribution: Distribution) : Dimension
    /** Layers on top of each other; [horizontal]/[vertical] place layers 2..n inside the first. */
    data class ZLayer(val horizontal: Align, val vertical: Align) : Dimension

    companion object {
        fun decode(e: JsonElement?): Dimension {
            val o = e as? JsonObject ?: return Vertical(Align.CENTER, Distribution.CENTER)
            val alignment = o.string("alignment")
            return when (o.string("type")) {
                "horizontal" -> Horizontal(Align.decode(alignment), Distribution.decode(o.string("distribution")))
                "zlayer" -> {
                    // top_leading ... bottom_trailing; unknown -> center.
                    val a = alignment ?: "center"
                    val h = when { a.endsWith("leading") -> Align.START; a.endsWith("trailing") -> Align.END; else -> Align.CENTER }
                    val v = when { a.startsWith("top") -> Align.START; a.startsWith("bottom") -> Align.END; else -> Align.CENTER }
                    ZLayer(h, v)
                }
                "vertical" -> Vertical(Align.decode(alignment), Distribution.decode(o.string("distribution")))
                // The web renders any other type as a row (stack-utils.js mapDimension).
                else -> Horizontal(Align.decode(alignment), Distribution.decode(o.string("distribution")))
            }
        }
    }
}

sealed class Component {
    abstract val type: String
    abstract val id: String?
    abstract val raw: JsonObject
    /** `visible: false` hides the node (takes no space, no spacing). */
    val visible: Boolean get() = raw.bool("visible") != false

    class Stack(
        override val id: String?,
        override val raw: JsonObject,
        val components: List<Component>,
        val width: Size,
        val height: Size,
        val dimension: Dimension,
        val spacing: Double,
        val margin: Spacing,
        val padding: Spacing,
        /** Border width in pt; the border takes layout space (also for gradient borders). */
        val borderWidth: Double,
    ) : Component() {
        override val type get() = "stack"
    }

    /** A node type this SDK version does not render yet. Rendered as [fallback] when present. */
    class Unsupported(
        override val type: String,
        override val id: String?,
        override val raw: JsonObject,
        val fallback: Component?,
    ) : Component()

    companion object {
        fun decode(o: JsonObject): Component {
            val type = o.string("type") ?: ""
            val id = o.string("id")
            return when (type) {
                "stack" -> Stack(
                    id = id,
                    raw = o,
                    components = (o["components"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decode) } ?: emptyList(),
                    width = Size.decode(o.obj("size")?.get("width")),
                    height = Size.decode(o.obj("size")?.get("height")),
                    dimension = Dimension.decode(o["dimension"]),
                    spacing = o.double("spacing") ?: 0.0,
                    margin = Spacing.decode(o["margin"]),
                    padding = Spacing.decode(o["padding"]),
                    borderWidth = o.obj("border")?.double("width") ?: 0.0,
                )
                else -> Unsupported(type, id, o, o.obj("fallback")?.let(::decode))
            }
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
