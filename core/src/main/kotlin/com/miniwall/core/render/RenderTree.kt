package com.miniwall.core.render

import com.miniwall.core.layout.LayoutNode
import com.miniwall.core.logic.TextLogic

/** sRGB color, components 0..1. */
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    companion object {
        val CLEAR = Rgba(0.0, 0.0, 0.0, 0.0)
        val BLACK = Rgba(0.0, 0.0, 0.0)

        /** `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` (CSS order, alpha last), or `transparent`. */
        fun parse(value: String?): Rgba? {
            val s = value?.trim()?.removePrefix("#") ?: return null
            if (value.trim().equals("transparent", ignoreCase = true)) return CLEAR
            val full = when (s.length) {
                3, 4 -> s.map { "$it$it" }.joinToString("")
                6, 8 -> s
                else -> return null
            }
            val v = full.toLongOrNull(16) ?: return null
            val hasAlpha = full.length == 8
            fun byte(shift: Int) = ((v shr shift) and 0xFF) / 255.0
            return if (hasAlpha) Rgba(byte(24), byte(16), byte(8), byte(0)) else Rgba(byte(16), byte(8), byte(0))
        }
    }
}

data class GradientStop(val color: Rgba, val percent: Double)

/** What fills an area: a color, a CSS gradient, or an image. */
sealed interface Fill {
    data class Solid(val color: Rgba) : Fill
    /** CSS `linear-gradient(<degrees>deg, ...)`: 0deg points up, 90deg points right. */
    data class Linear(val degrees: Double, val stops: List<GradientStop>) : Fill
    /** CSS `radial-gradient(circle, ...)`: centered, to the farthest corner. */
    data class Radial(val stops: List<GradientStop>) : Fill
    /** Background image; [cover] = `fit_mode: fill` (center), else contain (top center). */
    data class Image(val url: String, val cover: Boolean, val overlay: Fill?) : Fill

    /** A single color standing in for this fill where a platform cannot paint it (first stop). */
    val approximateColor: Rgba?
        get() = when (this) {
            is Solid -> color
            is Linear -> stops.firstOrNull()?.color
            is Radial -> stops.firstOrNull()?.color
            is Image -> null
        }
}

data class Shadow(val x: Double, val y: Double, val radius: Double, val color: Rgba)

/** Corner radii in pt. [pill] = fully rounded (CSS 9999px): min(width, height) / 2. */
data class Corners(
    val topLeading: Double = 0.0,
    val topTrailing: Double = 0.0,
    val bottomTrailing: Double = 0.0,
    val bottomLeading: Double = 0.0,
    val pill: Boolean = false,
) {
    val isZero get() = !pill && topLeading == 0.0 && topTrailing == 0.0 && bottomTrailing == 0.0 && bottomLeading == 0.0

    /** Radii for a box of this size, clamped like CSS (no radius larger than half a side). */
    fun resolved(width: Double, height: Double): List<Double> {
        if (pill) return List(4) { minOf(width, height) / 2 }
        val max = minOf(width, height) / 2
        return listOf(topLeading, topTrailing, bottomTrailing, bottomLeading).map { minOf(it, max) }
    }

    companion object {
        val NONE = Corners()
    }
}

/** How a box is painted (behind its children). */
data class Paint(
    val background: Fill? = null,
    val borderWidth: Double = 0.0,
    val borderFill: Fill? = null,
    val corners: Corners = Corners.NONE,
    val shadow: Shadow? = null,
    /** 0.4 for disabled buttons and options. */
    val opacity: Double = 1.0,
) {
    companion object {
        val NONE = Paint()
    }
}

enum class TextAlign { START, CENTER, END }

data class FontSpec(
    /** `components_config.fonts` key (e.g. `Fraunces@700`), or null for the system font. */
    val name: String?,
    /** Family parsed from [name], when it has the `Family@weight` form. */
    val family: String?,
    val size: Double,
    val weight: Int,
    /** CSS line height of the paywall: 1.5 x size. */
    val lineHeight: Double = size * 1.5,
)

/** Shape that clips an image or video (`mask_shape`). */
sealed interface Mask {
    data class Rect(val corners: Corners) : Mask
    data object Circle : Mask
    /** Bottom edge curves up 54pt in the middle. */
    data object Concave : Mask
    /** Bottom edge curves down: sides end 27pt above the bottom. */
    data object Convex : Mask
}

sealed interface Content {
    data class Text(
        val runs: List<TextLogic.Run>,
        val font: FontSpec,
        val color: Fill,
        val align: TextAlign,
    ) : Content {
        val plain: String get() = runs.joinToString("") { it.text }
    }

    data class Image(
        val url: String,
        val cover: Boolean,
        val overlay: Fill?,
        val mask: Mask,
        val borderWidth: Double,
        val borderFill: Fill?,
        val shadow: Shadow?,
    ) : Content

    /** A monochrome icon: the image at [url] used as a mask, filled with [color]. */
    data class Icon(val url: String, val color: Fill?) : Content

    data class Video(
        val url: String,
        val fallbackImageUrl: String?,
        val cover: Boolean,
        val autoPlay: Boolean,
        val loop: Boolean,
        val muted: Boolean,
        val showControls: Boolean,
        val mask: Mask,
        val overlay: Fill?,
    ) : Content

    /** The tab switch (51 x 31). */
    data class Toggle(val on: Boolean, val track: Fill?, val thumb: Fill?) : Content

    data class TextField(
        val fieldId: String,
        val placeholder: String,
        val keyboard: String,
        val capitalize: String,
        val required: Boolean,
        val value: String,
        val font: FontSpec,
        val color: Fill,
        val align: TextAlign,
        val placeholderFont: FontSpec,
        val placeholderColor: Fill,
        val placeholderAlign: TextAlign,
    ) : Content
}

/** What a tap does. The controller ([ScreenController]) turns it into state changes or host callbacks. */
sealed interface Action {
    data class SelectPackage(val packageId: String) : Action
    /** Buy the selected package; [actionId] is the button's `triggers.on_purchase_press`, if any. */
    data class Purchase(val actionId: String?) : Action
    /** A button's `action` (workflow, complete_workflow, navigate_back, restore_purchases, navigate_to). */
    data class Button(val action: kotlinx.serialization.json.JsonObject, val actionId: String?) : Action
    data class SelectTab(val tabsKey: String, val index: Int) : Action
    data class SelectOption(val fieldId: String, val optionId: String, val multiple: Boolean, val actionId: String?) : Action
    data class OpenUrl(val url: String) : Action
}

/** Accessibility of a node: role and label for VoiceOver / TalkBack. */
data class Semantics(
    val isButton: Boolean = false,
    val label: String? = null,
    val selected: Boolean? = null,
    val hidden: Boolean = false,
    /** Not available now (a continue button waiting for a required answer): announced as dimmed. */
    val disabled: Boolean = false,
)

/**
 * One painted node. [layout] holds its frame after [com.miniwall.core.layout.LayoutEngine]
 * runs. Children paint in order on top of the parent; [badge] paints above everything in it.
 */
class RenderNode(
    /** Unique within a screen, stable across re-renders (path of indices). */
    val key: String,
    val id: String?,
    val type: String,
    val layout: LayoutNode,
    val paint: Paint = Paint.NONE,
    val content: Content? = null,
    val action: Action? = null,
    val disabled: Boolean = false,
    val semantics: Semantics = Semantics(),
    val children: List<RenderNode> = emptyList(),
    val badge: RenderNode? = null,
    /** Carousel: clip children to this box and shift them by [contentOffsetX]. */
    val clipsChildren: Boolean = false,
    val contentOffsetX: Double = 0.0,
    /** Carousel slider: the node a horizontal swipe moves (see [Carousel]). */
    val carousel: Carousel? = null,
    /** Timeline row: the bar joining this item's icon to the next, painted under the icon. */
    val connector: Connector? = null,
) {
    /**
     * Vertical bar, [width] wide, centered [centerX] from the row's left edge, from [top] below
     * the row's top to [bottomInset] above its bottom (negative: it runs past the row).
     */
    data class Connector(val width: Double, val fill: Fill?, val centerX: Double, val top: Double, val bottomInset: Double)

    /** Paging info for a carousel's slider row. */
    data class Carousel(
        val key: String,
        val pageCount: Int,
        val currentPage: Int,
        val loop: Boolean,
        /** Distance between two pages' starts, known after layout (page width + spacing). */
        val pageSpacing: Double,
        val autoAdvanceMs: Long?,
        val transitionMs: Long,
    )

    fun walk(visit: (RenderNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
        badge?.walk(visit)
    }
}
