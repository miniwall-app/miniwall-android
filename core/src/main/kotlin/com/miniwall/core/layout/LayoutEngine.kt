package com.miniwall.core.layout

import com.miniwall.core.model.Align
import com.miniwall.core.model.Component
import com.miniwall.core.model.Dimension
import com.miniwall.core.model.Distribution
import com.miniwall.core.model.Size
import com.miniwall.core.model.Spacing
import kotlin.math.max
import kotlin.math.min

/**
 * Content measured by the platform (text, images): sizes of the content box in pt.
 * Leaves without content (an empty stack) have no Measurable.
 */
interface Measurable {
    fun minContentWidth(): Double
    fun maxContentWidth(): Double
    fun heightForWidth(width: Double): Double
}

/** One node of the layout tree. Built from the model by [LayoutNode.from]; renderers read frames back by [id]. */
class LayoutNode(
    val id: String?,
    val width: Size,
    val height: Size,
    val dimension: Dimension,
    val spacing: Double = 0.0,
    val padding: Spacing = Spacing.ZERO,
    val margin: Spacing = Spacing.ZERO,
    val border: Double = 0.0,
    val children: List<LayoutNode> = emptyList(),
    val measurable: Measurable? = null,
    /** Key the frame is reported under; defaults to [id]. Render trees use unique paths. */
    val key: String? = id,
    /** False for `flex-shrink: 0` items (fixed-width images and videos, icons). */
    val shrink: Boolean = true,
    /** Images and videos: height = round(width x ratio) unless the height is fixed. */
    val aspectRatio: Double? = null,
    /** A stack's badge, laid out after the stack (`mapBadge` in stack-utils.js). */
    val badge: Badge? = null,
) {
    /** `overlay` straddles the top or bottom edge; `nested` sits inside it. */
    class Badge(val node: LayoutNode, val overlay: Boolean, val top: Boolean, val horizontal: Align)

    /** Border-box frame in screen coordinates, set by [LayoutEngine]. */
    var frame: Rect? = null
        internal set

    // Memoized measurements: intrinsic widths never change; auto heights depend only on the width.
    // Without them nested stacks re-measure their subtrees at every level.
    internal var maxContentCache: Double? = null
    internal var minContentCache: Double? = null
    internal val autoHeightCache = HashMap<Double, Double>()

    /** Padding + border, per axis. A box is never smaller than this. */
    internal val pbH get() = padding.horizontal + 2 * border
    internal val pbV get() = padding.vertical + 2 * border

    companion object {
        /**
         * Layout tree for a component; hidden nodes are dropped, unsupported ones are replaced by
         * their fallback or dropped (same as the web's Node.svelte). Returns null when nothing renders.
         */
        fun from(component: Component, measure: (Component) -> Measurable? = { null }): LayoutNode? {
            if (!component.visible) return null
            return when (component) {
                is Component.Stack -> LayoutNode(
                    id = component.id,
                    width = component.width,
                    height = component.height,
                    dimension = component.dimension,
                    spacing = component.spacing,
                    padding = component.padding,
                    margin = component.margin,
                    border = component.borderWidth,
                    children = component.components.mapNotNull { from(it, measure) },
                )
                is Component.Unsupported -> component.fallback?.let { from(it, measure) }
            }
        }
    }
}

data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Computes the frame of every node the way the web renderer's CSS does
 * (the web renderer). Pure and deterministic: the same algorithm
 * exists in Swift (sdks/ios) and both are checked against goldens from the web
 * (the shared conformance suite). All sizes are border-box, in pt (= CSS px = dp).
 */
object LayoutEngine {
    /**
     * Lays out a screen's root stack inside a [screenWidth] x [screenHeight] area. The root
     * always takes the full height (it grows and shrinks, like `.paywall-content-scroll`).
     * Returns frames by node id, in screen coordinates.
     */
    fun layoutScreen(root: LayoutNode, screenWidth: Double, screenHeight: Double): Map<String, Rect> {
        val frames = LinkedHashMap<String, Rect>()
        val width = stretchWidth(root, screenWidth)
        val height = max(screenHeight - root.margin.vertical, root.pbV)
        layout(root, root.margin.leading, root.margin.top, width, height, frames)
        return frames
    }

    /** Lays out [node] with its border box at ([x], [y], [w], [h]); frames go on the nodes and into [out]. */
    fun layoutAt(node: LayoutNode, x: Double, y: Double, w: Double, h: Double, out: MutableMap<String, Rect> = LinkedHashMap()): Map<String, Rect> {
        layout(node, x, y, w, h, out)
        return out
    }

    /** Border-box width of [node] inside a containing content box [containerWidth] wide (block flow). */
    fun widthIn(node: LayoutNode, containerWidth: Double): Double = stretchWidth(node, containerWidth)

    // ---------------------------------------------------------------- intrinsic widths

    /** max-content width (border-box): the width with no line breaks and nothing shrunk. */
    fun maxContent(n: LayoutNode): Double = n.maxContentCache ?: computeMaxContent(n).also { n.maxContentCache = it }

    private fun computeMaxContent(n: LayoutNode): Double = n.pbH + when {
        n.measurable != null -> n.measurable.maxContentWidth()
        n.children.isEmpty() -> 0.0
        else -> when (n.dimension) {
            is Dimension.Horizontal -> n.children.sumOf { maxContribution(it) } + n.spacing * (n.children.size - 1)
            is Dimension.Vertical -> n.children.maxOf { maxContribution(it) }
            is Dimension.ZLayer -> maxContribution(n.children.first())
        }
    }

    /** min-content width (border-box): the narrowest the content can get. A row's items sit side by side. */
    fun minContent(n: LayoutNode): Double = n.minContentCache ?: computeMinContent(n).also { n.minContentCache = it }

    private fun computeMinContent(n: LayoutNode): Double = n.pbH + when {
        n.measurable != null -> n.measurable.minContentWidth()
        n.children.isEmpty() -> 0.0
        else -> when (n.dimension) {
            is Dimension.Horizontal -> n.children.sumOf { minContribution(it) } + n.spacing * (n.children.size - 1)
            is Dimension.Vertical -> n.children.maxOf { minContribution(it) }
            is Dimension.ZLayer -> minContribution(n.children.first())
        }
    }

    // A fixed width contributes itself; fit, fill and relative contribute their content (CSS treats them as auto here).
    private fun maxContribution(c: LayoutNode) = c.margin.horizontal + ((c.width as? Size.Fixed)?.let { max(it.value, c.pbH) } ?: maxContent(c))
    private fun minContribution(c: LayoutNode) = c.margin.horizontal + ((c.width as? Size.Fixed)?.let { max(it.value, c.pbH) } ?: minContent(c))

    /** `fit-content`: shrink-to-fit inside [available], never below min-content. */
    private fun fitContent(n: LayoutNode, available: Double) = min(maxContent(n), max(minContent(n), available))

    /**
     * Width of a child whose containing content box is [cw] wide, before any flex shrinking:
     * fixed, relative (% of cw), fill (cw minus margins, `-webkit-fill-available`), fit (fit-content).
     * Used for a vertical stack's children, a row's flex basis, z-layers and the root.
     */
    private fun stretchWidth(c: LayoutNode, cw: Double): Double = max(c.pbH, when (val w = c.width) {
        is Size.Fixed -> w.value
        is Size.Relative -> w.fraction * cw
        Size.Fill -> cw - c.margin.horizontal
        Size.Fit -> fitContent(c, cw - c.margin.horizontal)
    })

    /** Content-based minimum width of a row item (`min-width: auto`). */
    private fun minMainWidth(c: LayoutNode, cw: Double): Double = max(c.pbH, when (val w = c.width) {
        is Size.Fixed -> min(w.value, minContent(c))
        is Size.Relative -> min(w.fraction * cw, minContent(c))
        else -> minContent(c)
    })

    // ---------------------------------------------------------------- auto heights

    /**
     * Height (border-box) of [n] at border-box width [w] when its height is auto: what the content needs.
     * Relative and fill heights of children count as their content here (CSS: percentages of an
     * indefinite height behave as auto); they resolve against this height in [layout].
     */
    fun autoHeight(n: LayoutNode, w: Double): Double = n.autoHeightCache.getOrPut(w) { computeAutoHeight(n, w) }

    private fun computeAutoHeight(n: LayoutNode, w: Double): Double {
        val cw = max(w - n.pbH, 0.0)
        val content = when {
            n.measurable != null -> n.measurable.heightForWidth(cw)
            n.children.isEmpty() -> 0.0
            else -> when (n.dimension) {
                is Dimension.Vertical ->
                    n.children.sumOf { it.margin.vertical + contentHeight(it, stretchWidth(it, cw)) } + n.spacing * (n.children.size - 1)
                is Dimension.Horizontal -> {
                    val widths = rowWidths(n, cw)
                    n.children.indices.maxOf { i -> n.children[i].margin.vertical + contentHeight(n.children[i], widths[i]) }
                }
                is Dimension.ZLayer -> {
                    val first = n.children.first()
                    first.margin.vertical + contentHeight(first, stretchWidth(first, cw))
                }
            }
        }
        return n.pbV + content
    }

    /** A child's height when the parent's height is not known yet: fixed stays, the rest is content. */
    private fun contentHeight(c: LayoutNode, w: Double) = specifiedHeight(c, w) ?: autoHeight(c, w)

    /** A height known without the parent: fixed, or from the aspect ratio (images, videos). */
    private fun specifiedHeight(c: LayoutNode, w: Double): Double? = when {
        c.height is Size.Fixed -> max((c.height as Size.Fixed).value, c.pbV)
        c.aspectRatio != null -> max(Math.round(w * c.aspectRatio).toDouble(), c.pbV)
        else -> null
    }

    // ---------------------------------------------------------------- layout

    private fun layout(n: LayoutNode, x: Double, y: Double, w: Double, h: Double, out: MutableMap<String, Rect>) {
        val frame = Rect(x, y, w, h)
        n.frame = frame
        n.key?.let { out[it] = frame }
        n.badge?.let { layoutBadge(n, it, frame, out) }
        if (n.children.isEmpty()) return
        val cx = x + n.border + n.padding.leading
        val cy = y + n.border + n.padding.top
        val cw = max(w - n.pbH, 0.0)
        val ch = max(h - n.pbV, 0.0)
        when (val d = n.dimension) {
            is Dimension.Vertical -> layoutColumn(n, d, cx, cy, cw, ch, out)
            is Dimension.Horizontal -> layoutRow(n, d, cx, cy, cw, ch, out)
            is Dimension.ZLayer -> layoutLayers(n, d, cx, cy, cw, ch, out)
        }
    }

    private fun layoutColumn(n: LayoutNode, d: Dimension.Vertical, cx: Double, cy: Double, cw: Double, ch: Double, out: MutableMap<String, Rect>) {
        val items = n.children
        val widths = items.map { stretchWidth(it, cw) }
        val contentHeights = items.indices.map { autoHeight(items[it], widths[it]) }
        val bases = items.indices.map { i ->
            val c = items[i]
            specifiedHeight(c, widths[i]) ?: when (val s = c.height) {
                is Size.Relative -> max(s.fraction * ch, c.pbV)
                // fill on a column's main axis is auto in Chrome (-webkit-fill-available does not apply to flex main sizes).
                else -> contentHeights[i]
            }
        }
        val mins = items.indices.map { i ->
            val c = items[i]
            max(c.pbV, when (val s = c.height) {
                is Size.Fixed -> min(s.value, contentHeights[i])
                // An image's height comes from its width: its content is that tall, so it does not shrink.
                else -> if (c.aspectRatio != null) bases[i] else when (s) {
                    is Size.Relative -> min(s.fraction * ch, contentHeights[i])
                    else -> contentHeights[i]
                }
            })
        }
        val heights = flexShrink(bases, mins, items.indices.map { if (items[it].shrink) bases[it] - items[it].pbV else 0.0 }, ch - n.spacing * (items.size - 1) - items.sumOf { it.margin.vertical })
        val offsets = distribute(d.distribution, ch, items.indices.map { heights[it] + items[it].margin.vertical }, n.spacing)
        for (i in items.indices) {
            val c = items[i]
            val itemX = cx + c.margin.leading + (cw - widths[i] - c.margin.horizontal) * d.alignment.factor
            layout(c, itemX, cy + offsets[i] + c.margin.top, widths[i], heights[i], out)
        }
    }

    private fun layoutRow(n: LayoutNode, d: Dimension.Horizontal, cx: Double, cy: Double, cw: Double, ch: Double, out: MutableMap<String, Rect>) {
        val items = n.children
        val widths = rowWidths(n, cw)
        val heights = items.indices.map { i -> crossHeight(items[i], widths[i], ch) }
        val offsets = distribute(d.distribution, cw, items.indices.map { widths[it] + items[it].margin.horizontal }, n.spacing)
        for (i in items.indices) {
            val c = items[i]
            val itemY = cy + c.margin.top + (ch - heights[i] - c.margin.vertical) * d.alignment.factor
            layout(c, cx + offsets[i] + c.margin.leading, itemY, widths[i], heights[i], out)
        }
    }

    /** Item widths of a row with content width [cw]: flex basis, then shrink to fit. */
    private fun rowWidths(n: LayoutNode, cw: Double): List<Double> {
        val items = n.children
        val bases = items.map { stretchWidth(it, cw) }
        val mins = items.map { minMainWidth(it, cw) }
        return flexShrink(bases, mins, items.indices.map { if (items[it].shrink) bases[it] - items[it].pbH else 0.0 }, cw - n.spacing * (items.size - 1) - items.sumOf { it.margin.horizontal })
    }

    /**
     * Height of an item on a row's cross axis (also a z-layer's upper layers) whose line is [ch] tall:
     * fill stretches to the line, relative is a fraction of it, fit is the content.
     */
    private fun crossHeight(c: LayoutNode, w: Double, ch: Double): Double = specifiedHeight(c, w) ?: max(c.pbV, when (val s = c.height) {
        is Size.Fixed -> s.value
        is Size.Relative -> s.fraction * ch
        Size.Fill -> ch - c.margin.vertical
        Size.Fit -> autoHeight(c, w)
    })

    /**
     * Z-layer: the first layer is in normal flow (it sizes a fit z-layer); every other layer is
     * placed over the content box by the stack's alignment, like a one-item row.
     */
    private fun layoutLayers(n: LayoutNode, d: Dimension.ZLayer, cx: Double, cy: Double, cw: Double, ch: Double, out: MutableMap<String, Rect>) {
        n.children.forEachIndexed { index, c ->
            if (index == 0) {
                val w = stretchWidth(c, cw)
                val h = specifiedHeight(c, w) ?: when (val s = c.height) {
                    is Size.Relative -> max(s.fraction * ch, c.pbV)
                    // fill in block flow is auto in Chrome.
                    else -> autoHeight(c, w)
                }
                layout(c, cx + c.margin.leading, cy + c.margin.top, w, h, out)
            } else {
                val basis = stretchWidth(c, cw)
                val w = flexShrink(listOf(basis), listOf(minMainWidth(c, cw)), listOf(if (c.shrink) basis - c.pbH else 0.0), cw - c.margin.horizontal)[0]
                val h = crossHeight(c, w, ch)
                val x = cx + c.margin.leading + (cw - w - c.margin.horizontal) * d.horizontal.factor
                val y = cy + c.margin.top + (ch - h - c.margin.vertical) * d.vertical.factor
                layout(c, x, y, w, h, out)
            }
        }
    }

    /**
     * A badge is absolutely positioned against the stack's padding box at its max-content size.
     * `overlay` centers it on the top or bottom edge; `nested` puts it inside, one border width in.
     */
    private fun layoutBadge(n: LayoutNode, badge: LayoutNode.Badge, f: Rect, out: MutableMap<String, Rect>) {
        val b = n.border
        val node = badge.node
        val w = (node.width as? Size.Fixed)?.value?.let { max(it, node.pbH) } ?: maxContent(node)
        val h = specifiedHeight(node, w) ?: autoHeight(node, w)
        val x: Double
        val y: Double
        if (badge.overlay) {
            x = when (badge.horizontal) { Align.START -> f.x; Align.CENTER -> f.x + f.width / 2 - w / 2; Align.END -> f.x + f.width - w }
            y = if (badge.top) f.y + b / 2 - h / 2 else f.y + f.height - b / 2 - h / 2
        } else {
            x = when (badge.horizontal) { Align.START -> f.x + 2 * b; Align.CENTER -> f.x + f.width / 2 - w / 2; Align.END -> f.x + f.width - 2 * b - w }
            y = if (badge.top) f.y + 2 * b else f.y + f.height - 2 * b - h
        }
        layout(node, x + node.margin.leading, y + node.margin.top, w, h, out)
    }

    // ---------------------------------------------------------------- flex

    /**
     * CSS flexbox "resolve flexible lengths" for items with flex-grow 0 and flex-shrink 1
     * (every stack child on the web). Items only shrink, in proportion to their inner
     * (content-box) base size, never below their minimum.
     */
    internal fun flexShrink(bases: List<Double>, mins: List<Double>, inners: List<Double>, available: Double): List<Double> {
        val hypothetical = bases.indices.map { max(bases[it], mins[it]) }
        if (hypothetical.sum() <= available) return hypothetical
        val target = hypothetical.toMutableList()
        val frozen = BooleanArray(bases.size) { bases[it] < mins[it] || inners[it] <= 0.0 }
        while (frozen.any { !it }) {
            val free = available - bases.indices.sumOf { if (frozen[it]) target[it] else bases[it] }
            val scaled = bases.indices.filter { !frozen[it] }.sumOf { inners[it] }
            var totalViolation = 0.0
            val violation = DoubleArray(bases.size)
            for (i in bases.indices) {
                if (frozen[i]) continue
                val raw = if (scaled > 0) bases[i] + free * inners[i] / scaled else bases[i]
                val clamped = max(raw, mins[i])
                violation[i] = clamped - raw
                totalViolation += violation[i]
                target[i] = clamped
            }
            if (totalViolation <= 0.0) break
            for (i in bases.indices) if (!frozen[i] && violation[i] > 0.0) frozen[i] = true
        }
        return target
    }

    /** Main-axis start offset of each item (outer sizes [sizes]) for a distribution, inside [space]. */
    internal fun distribute(distribution: Distribution, space: Double, sizes: List<Double>, gap: Double): List<Double> {
        val n = sizes.size
        if (n == 0) return emptyList()
        val free = space - sizes.sum() - gap * (n - 1)
        // Negative free space: space-between falls back to start, space-around/evenly to center (CSS Box Alignment).
        val (lead, between) = when (distribution) {
            Distribution.START -> 0.0 to 0.0
            Distribution.CENTER -> free / 2 to 0.0
            Distribution.END -> free to 0.0
            Distribution.SPACE_BETWEEN -> if (free > 0 && n > 1) 0.0 to free / (n - 1) else 0.0 to 0.0
            Distribution.SPACE_AROUND -> if (free > 0) free / (2 * n) to free / n else free / 2 to 0.0
            Distribution.SPACE_EVENLY -> if (free > 0) free / (n + 1) to free / (n + 1) else free / 2 to 0.0
        }
        var position = lead
        return sizes.map { size -> position.also { position += size + gap + between } }
    }
}
