package com.miniwall.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvgTest {
    private val dir = File(com.miniwall.core.Spec.dir.path)
    private fun load(path: String) = Json.parseToJsonElement(File(dir, path).readText()).jsonObject

    /** Control points of every curve, sampled, give the geometry's bounding box (like SVG getBBox). */
    private fun bounds(icon: SvgIcon): DoubleArray {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        fun add(x: Double, y: Double) { minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y) }
        for (shape in icon.shapes) {
            var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
            for (op in shape.ops) when (op) {
                is PathOp.Move -> { x = op.x; y = op.y; sx = x; sy = y; add(x, y) }
                is PathOp.Line -> { x = op.x; y = op.y; add(x, y) }
                is PathOp.Cubic -> {
                    for (k in 1..200) {
                        val t = k / 200.0; val u = 1 - t
                        add(u * u * u * x + 3 * u * u * t * op.x1 + 3 * u * t * t * op.x2 + t * t * t * op.x, u * u * u * y + 3 * u * u * t * op.y1 + 3 * u * t * t * op.y2 + t * t * t * op.y)
                    }
                    x = op.x; y = op.y
                }
                PathOp.Close -> { x = sx; y = sy }
            }
        }
        return doubleArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    @Test
    fun everyTemplateIconMatchesTheBrowsersGeometry() {
        val icons = load("fixtures/icons/icons.json")
        val golden = load("golden/icons/icons.json")
        assertTrue(icons.size >= 80)
        for ((name, source) in icons) {
            val icon = assertNotNull(Svg.parse(source.jsonPrimitive.content), name)
            assertEquals(24.0, icon.width, name)
            val expected = golden[name]!!.jsonObject
            val actual = bounds(icon)
            for ((i, key) in listOf("x", "y", "w", "h").withIndex()) {
                val want = expected[key]!!.jsonPrimitive.double
                assertTrue(abs(actual[i] - want) < 0.02, "$name $key: ${actual[i]} vs $want")
            }
        }
    }

    @Test
    fun lucideIconsStrokeAndMdiFills() {
        val icons = load("fixtures/icons/icons.json")
        val check = Svg.parse(icons["lucide/check"]!!.jsonPrimitive.content)!!
        assertTrue(check.shapes.all { it.stroke && !it.fill && it.strokeWidth == 2.0 && it.roundCap && it.roundJoin })
        val star = Svg.parse(icons["mdi/star"]!!.jsonPrimitive.content)!!
        assertTrue(star.shapes.all { it.fill && !it.stroke })
    }

    @Test
    fun relativeSmoothQuadraticAndArcCommands() {
        assertEquals(listOf(PathOp.Move(1.0, 1.0), PathOp.Line(3.0, 1.0), PathOp.Line(3.0, 4.0), PathOp.Close), Svg.parsePath("m1 1h2v3z"))
        // Implicit lineto after moveto, relative.
        assertEquals(listOf(PathOp.Move(1.0, 1.0), PathOp.Line(2.0, 3.0)), Svg.parsePath("m1 1 1 2"))
        val s = Svg.parsePath("M0 0C1 1 2 1 3 0S5 -1 6 0")
        assertEquals(PathOp.Cubic(4.0, -1.0, 5.0, -1.0, 6.0, 0.0), s[2])
        val q = Svg.parsePath("M0 0Q3 3 6 0")
        assertEquals(PathOp.Cubic(2.0, 2.0, 4.0, 2.0, 6.0, 0.0), q[1])
        // Arc flags without separators: a half circle of radius 1 splits in two quarter arcs.
        val arc = Svg.parsePath("M0 0a1 1 0 012 0")
        assertEquals(3, arc.size)
        assertEquals(PathOp.Cubic::class, arc.last()::class)
        assertEquals(2.0, (arc.last() as PathOp.Cubic).x, 0.0)
        // A drawing command after Z starts from the subpath start.
        assertEquals(PathOp.Move(0.0, 0.0), Svg.parsePath("M0 0L1 0ZL0 1")[3])
    }

    @Test
    fun shapesBecomePaths() {
        val svg = """<svg viewBox="0 0 10 10"><g fill="none" stroke="currentColor" stroke-width="3"><rect x="1" y="1" width="8" height="8" rx="2"/><line x1="0" y1="0" x2="5" y2="5"/><polygon points="0,0 4,0 4,4"/></g></svg>"""
        val icon = Svg.parse(svg)!!
        assertEquals(3, icon.shapes.size)
        assertTrue(icon.shapes.all { it.stroke && !it.fill && it.strokeWidth == 3.0 })
        val rounded = bounds(Svg.parse("""<svg viewBox="0 0 10 10"><rect x="1" y="1" width="8" height="8" rx="2" fill="red"/></svg>""")!!)
        listOf(1.0, 1.0, 8.0, 8.0).forEachIndexed { i, want -> assertEquals(want, rounded[i], 0.01) }
    }

    @Test
    fun fillRuleIsInheritedFromTheGroup() {
        val icon = Svg.parse("""<svg viewBox="0 0 10 10"><g fill-rule="evenodd"><path d="M0 0H10V10H0Z"/></g><path d="M0 0H5V5H0Z"/></svg>""")!!
        assertEquals(listOf(true, false), icon.shapes.map { it.evenOdd })
    }

    @Test
    fun unusableInputIsNull() {
        assertNull(Svg.parse("not svg"))
        assertNull(Svg.parse("""<svg viewBox="0 0 24 24"></svg>"""))
        assertNull(Svg.parse("""<svg viewBox="0 0 24 24"><path d="M0 0L1 1" fill="none"/></svg>"""))
        assertEquals(listOf(PathOp.Move(0.0, 0.0), PathOp.Line(1.0, 1.0)), Svg.parsePath("M0 0L1 1L"))
    }
}
