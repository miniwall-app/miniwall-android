package com.miniwall.core.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A path step in absolute coordinates; every SVG path command reduces to these four. */
sealed interface PathOp {
    data class Move(val x: Double, val y: Double) : PathOp
    data class Line(val x: Double, val y: Double) : PathOp
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathOp
    data object Close : PathOp
}

/** One drawable element of an icon: its path and how it is painted (always in the icon's one color). */
data class SvgShape(
    val ops: List<PathOp>,
    val fill: Boolean,
    val stroke: Boolean,
    val strokeWidth: Double,
    val roundCap: Boolean,
    val roundJoin: Boolean,
    /** `fill-rule="evenodd"`: overlapping parts of the path are holes. Default is non-zero. */
    val evenOdd: Boolean = false,
)

/** [viewBox] origin and size: shapes are drawn in these units and scaled to the icon's frame. */
data class SvgIcon(val minX: Double, val minY: Double, val width: Double, val height: Double, val shapes: List<SvgShape>)

/**
 * The subset of SVG that icon sets use (what api.iconify.design serves for Lucide and Material
 * Design Icons): `path`, `circle`, `ellipse`, `rect`, `line`, `polyline`, `polygon`, inside `g`
 * and `svg`, with fill/stroke/stroke-width/linecap/linejoin inherited as in SVG. Colors are
 * ignored (an icon is tinted with one color), `none` is honored. Platforms only replay [PathOp]s.
 */
object Svg {
    private data class Style(
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: Double? = null,
        val cap: String? = null,
        val join: String? = null,
        val fillRule: String? = null,
    )

    private val TAG = Regex("""<(/?)([a-zA-Z][\w:-]*)([^>]*?)(/?)>""")
    private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val SHAPES = setOf("path", "circle", "ellipse", "rect", "line", "polyline", "polygon")

    fun parse(source: String): SvgIcon? {
        var box: List<Double>? = null
        val styles = ArrayList<Style>().apply { add(Style()) }
        val open = ArrayList<String>()
        val shapes = ArrayList<SvgShape>()
        for (match in TAG.findAll(source)) {
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val selfClosing = match.groupValues[4] == "/"
            if (closing) {
                if (open.isNotEmpty() && open.last() == name) {
                    open.removeAt(open.size - 1)
                    if (name == "g" || name == "svg") styles.removeAt(styles.size - 1)
                }
                continue
            }
            val attributes = ATTRIBUTE.findAll(match.groupValues[3]).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groups[3]?.value ?: "") }
            val style = merge(styles.last(), attributes)
            when {
                name == "svg" -> {
                    if (box == null) box = attributes["viewBox"]?.let(::numbers)?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                    if (box == null) {
                        val w = attributes["width"]?.let(::numbers)?.firstOrNull()
                        val h = attributes["height"]?.let(::numbers)?.firstOrNull()
                        if (w != null && h != null && w > 0 && h > 0) box = listOf(0.0, 0.0, w, h)
                    }
                    if (!selfClosing) { styles.add(style); open.add(name) }
                }
                name == "g" -> if (!selfClosing) { styles.add(style); open.add(name) }
                name in SHAPES -> {
                    val d = pathData(name, attributes) ?: continue
                    val ops = parsePath(d)
                    if (ops.isEmpty()) continue
                    val fill = (style.fill ?: "black") != "none"
                    val stroke = (style.stroke ?: "none") != "none"
                    if (!fill && !stroke) continue
                    shapes.add(SvgShape(ops, fill, stroke, style.strokeWidth ?: 1.0, style.cap == "round", style.join == "round", style.fillRule == "evenodd"))
                }
                else -> if (!selfClosing) open.add(name)
            }
        }
        val b = box ?: return null
        if (shapes.isEmpty()) return null
        return SvgIcon(b[0], b[1], b[2], b[3], shapes)
    }

    private fun merge(parent: Style, a: Map<String, String>) = Style(
        fill = a["fill"] ?: parent.fill,
        stroke = a["stroke"] ?: parent.stroke,
        strokeWidth = a["stroke-width"]?.let(::numbers)?.firstOrNull() ?: parent.strokeWidth,
        cap = a["stroke-linecap"] ?: parent.cap,
        join = a["stroke-linejoin"] ?: parent.join,
        fillRule = a["fill-rule"] ?: parent.fillRule,
    )

    private fun numbers(text: String): List<Double> = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()

    private fun num(a: Map<String, String>, key: String): Double = a[key]?.let(::numbers)?.firstOrNull() ?: 0.0

    /** The element as path data (circles, rectangles... become arcs and lines). */
    private fun pathData(name: String, a: Map<String, String>): String? = when (name) {
        "path" -> a["d"]
        "circle" -> {
            val cx = num(a, "cx"); val cy = num(a, "cy"); val r = num(a, "r")
            if (r <= 0) null else ellipse(cx, cy, r, r)
        }
        "ellipse" -> {
            val rx = num(a, "rx"); val ry = num(a, "ry")
            if (rx <= 0 || ry <= 0) null else ellipse(num(a, "cx"), num(a, "cy"), rx, ry)
        }
        "rect" -> {
            val x = num(a, "x"); val y = num(a, "y"); val w = num(a, "width"); val h = num(a, "height")
            var rx = a["rx"]?.let(::numbers)?.firstOrNull()
            var ry = a["ry"]?.let(::numbers)?.firstOrNull()
            if (rx == null) rx = ry
            if (ry == null) ry = rx
            if (w <= 0 || h <= 0) null
            else if (rx == null || ry == null || rx <= 0 || ry <= 0) "M$x $y H${x + w} V${y + h} H$x Z"
            else {
                val ax = min(rx, w / 2); val ay = min(ry, h / 2)
                "M${x + ax} $y H${x + w - ax} A$ax $ay 0 0 1 ${x + w} ${y + ay} V${y + h - ay} A$ax $ay 0 0 1 ${x + w - ax} ${y + h} " +
                    "H${x + ax} A$ax $ay 0 0 1 $x ${y + h - ay} V${y + ay} A$ax $ay 0 0 1 ${x + ax} $y Z"
            }
        }
        "line" -> "M${num(a, "x1")} ${num(a, "y1")} L${num(a, "x2")} ${num(a, "y2")}"
        "polyline", "polygon" -> {
            val points = numbers(a["points"] ?: "")
            if (points.size < 4) null
            else buildString {
                for (i in 0 until points.size / 2) append(if (i == 0) "M" else "L").append(points[2 * i]).append(' ').append(points[2 * i + 1]).append(' ')
                if (name == "polygon") append('Z')
            }
        }
        else -> null
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) =
        "M${cx - rx} $cy A$rx $ry 0 1 0 ${cx + rx} $cy A$rx $ry 0 1 0 ${cx - rx} $cy Z"

    /**
     * Path data -> absolute [PathOp]s. Relative, horizontal/vertical, smooth, quadratic and arc
     * commands are resolved here so platforms only draw moves, lines and cubics. Malformed data
     * keeps what was read before the error, like browsers.
     */
    fun parsePath(d: String): List<PathOp> {
        val ops = ArrayList<PathOp>()
        val s = Scanner(d)
        var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
        var lastCubic: Pair<Double, Double>? = null
        var lastQuad: Pair<Double, Double>? = null
        var command = ' '
        var needMove = false

        fun start() { if (needMove) { ops.add(PathOp.Move(cx, cy)); needMove = false } }

        while (true) {
            s.skipSeparators()
            if (s.atEnd) break
            val c = s.peek()
            if (c.isLetter()) { command = c; s.next() } else if (command == ' ' || command == 'Z' || command == 'z') break
            val relative = command.isLowerCase()
            val ox = if (relative) cx else 0.0
            val oy = if (relative) cy else 0.0
            var cubic: Pair<Double, Double>? = null
            var quad: Pair<Double, Double>? = null
            when (command.uppercaseChar()) {
                'M' -> {
                    val x = s.number() ?: break; val y = s.number() ?: break
                    cx = ox + x; cy = oy + y; sx = cx; sy = cy
                    ops.add(PathOp.Move(cx, cy)); needMove = false
                    command = if (relative) 'l' else 'L'
                }
                'L' -> {
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start(); cx = ox + x; cy = oy + y; ops.add(PathOp.Line(cx, cy))
                }
                'H' -> { val x = s.number() ?: break; start(); cx = ox + x; ops.add(PathOp.Line(cx, cy)) }
                'V' -> { val y = s.number() ?: break; start(); cy = oy + y; ops.add(PathOp.Line(cx, cy)) }
                'C' -> {
                    val x1 = s.number() ?: break; val y1 = s.number() ?: break
                    val x2 = s.number() ?: break; val y2 = s.number() ?: break
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start()
                    ops.add(PathOp.Cubic(ox + x1, oy + y1, ox + x2, oy + y2, ox + x, oy + y))
                    cubic = Pair(ox + x2, oy + y2); cx = ox + x; cy = oy + y
                }
                'S' -> {
                    val x2 = s.number() ?: break; val y2 = s.number() ?: break
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start()
                    val x1 = lastCubic?.let { 2 * cx - it.first } ?: cx
                    val y1 = lastCubic?.let { 2 * cy - it.second } ?: cy
                    ops.add(PathOp.Cubic(x1, y1, ox + x2, oy + y2, ox + x, oy + y))
                    cubic = Pair(ox + x2, oy + y2); cx = ox + x; cy = oy + y
                }
                'Q' -> {
                    val qx = s.number() ?: break; val qy = s.number() ?: break
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start()
                    quadratic(ops, cx, cy, ox + qx, oy + qy, ox + x, oy + y)
                    quad = Pair(ox + qx, oy + qy); cx = ox + x; cy = oy + y
                }
                'T' -> {
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start()
                    val qx = lastQuad?.let { 2 * cx - it.first } ?: cx
                    val qy = lastQuad?.let { 2 * cy - it.second } ?: cy
                    quadratic(ops, cx, cy, qx, qy, ox + x, oy + y)
                    quad = Pair(qx, qy); cx = ox + x; cy = oy + y
                }
                'A' -> {
                    val rx = s.number() ?: break; val ry = s.number() ?: break
                    val rotation = s.number() ?: break
                    val large = s.flag() ?: break; val sweep = s.flag() ?: break
                    val x = s.number() ?: break; val y = s.number() ?: break
                    start()
                    arc(ops, cx, cy, rx, ry, rotation, large, sweep, ox + x, oy + y)
                    cx = ox + x; cy = oy + y
                }
                'Z' -> {
                    ops.add(PathOp.Close); cx = sx; cy = sy; needMove = true
                }
                else -> break
            }
            lastCubic = cubic
            lastQuad = quad
        }
        return ops
    }

    private fun quadratic(ops: MutableList<PathOp>, x0: Double, y0: Double, qx: Double, qy: Double, x: Double, y: Double) {
        ops.add(PathOp.Cubic(x0 + 2.0 / 3 * (qx - x0), y0 + 2.0 / 3 * (qy - y0), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y))
    }

    /** SVG 1.1 implementation notes F.6.5/F.6.6: endpoint arc -> center arc -> cubic segments of at most 90 degrees. */
    private fun arc(ops: MutableList<PathOp>, x0: Double, y0: Double, rxIn: Double, ryIn: Double, rotation: Double, large: Boolean, sweep: Boolean, x: Double, y: Double) {
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (x0 == x && y0 == y) return
        if (rx == 0.0 || ry == 0.0) { ops.add(PathOp.Line(x, y)); return }
        val phi = rotation * PI / 180
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx = (x0 - x) / 2
        val dy = (y0 - y) / 2
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
        val numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val sign = if (large == sweep) -1.0 else 1.0
        val coefficient = if (denominator == 0.0) 0.0 else sign * sqrt(max(0.0, numerator / denominator))
        val cxp = coefficient * (rx * y1p / ry)
        val cyp = coefficient * (-ry * x1p / rx)
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val theta = atan2(uy, ux)
        var delta = angle(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val rawSegments = ceil(abs(delta) / (PI / 2) - 1e-9)
        val segments = if (rawSegments.isFinite()) max(1, min(rawSegments, 64.0).toInt()) else 1
        val step = delta / segments
        val t = 4.0 / 3 * tan(step / 4)
        fun point(unitX: Double, unitY: Double) = Pair(cx + rx * unitX * cosPhi - ry * unitY * sinPhi, cy + rx * unitX * sinPhi + ry * unitY * cosPhi)
        for (i in 0 until segments) {
            val a = theta + i * step
            val b = a + step
            val p1 = point(cos(a) - t * sin(a), sin(a) + t * cos(a))
            val p2 = point(cos(b) + t * sin(b), sin(b) - t * cos(b))
            // The last segment ends exactly on the requested point, free of rounding drift.
            val end = if (i == segments - 1) Pair(x, y) else point(cos(b), sin(b))
            ops.add(PathOp.Cubic(p1.first, p1.second, p2.first, p2.second, end.first, end.second))
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val length = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        val a = acos((dot / length).coerceIn(-1.0, 1.0))
        return if (ux * vy - uy * vx < 0) -a else a
    }

    private class Scanner(private val text: String) {
        private var i = 0
        val atEnd get() = i >= text.length
        fun peek() = text[i]
        fun next() = text[i++]

        fun skipSeparators() { while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++ }

        /** Arc flags are single characters and may be written without a separator ("a1 1 0 011 1"). */
        fun flag(): Boolean? {
            skipSeparators()
            if (i >= text.length) return null
            return when (text[i]) { '0' -> { i++; false }; '1' -> { i++; true }; else -> null }
        }

        fun number(): Double? {
            skipSeparators()
            val start = i
            if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
            var digits = 0
            while (i < text.length && text[i].isDigit()) { i++; digits++ }
            if (i < text.length && text[i] == '.') {
                i++
                while (i < text.length && text[i].isDigit()) { i++; digits++ }
            }
            if (digits == 0) { i = start; return null }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                val mark = i
                i++
                if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
                var exponent = 0
                while (i < text.length && text[i].isDigit()) { i++; exponent++ }
                if (exponent == 0) i = mark
            }
            return text.substring(start, i).toDoubleOrNull()
        }
    }
}
