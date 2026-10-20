package com.miniwall.core.render

import com.miniwall.core.logic.TextLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ScreenTest {
    private val spec = File(com.miniwall.core.Spec.dir.path)

    private fun paywall(name: String): JsonObject =
        Json.parseToJsonElement(File(spec, "fixtures/templates/$name.json").readText()).jsonObject["paywall"]!!.jsonObject

    private fun controller(json: JsonObject, events: MutableList<ScreenEvent> = mutableListOf(), vars: Map<String, Map<String, String>> = emptyMap()) =
        ScreenController(json, "en_US", dark = false, variablesPerPackage = vars, baseVariables = emptyMap(), infoPerPackage = emptyMap(), showRestoreButton = true) { events += it }

    private fun nodes(render: ScreenRender): List<RenderNode> {
        val out = mutableListOf<RenderNode>()
        listOfNotNull(render.header, render.root, render.footer, render.sheet).forEach { it.walk { n -> out += n } }
        return out
    }

    @Test
    fun fontScaleGrowsTextAndTheScreenFollows() {
        val json = paywall("two-plans")
        fun texts(scale: Double): Pair<List<Double>, Double> {
            val c = controller(json).also { it.fontScale = scale }
            val render = c.render(375.0, 812.0, FakeMeasurer)!!
            val sizes = nodes(render).mapNotNull { (it.content as? Content.Text)?.font?.size }
            val bottom = nodes(render).mapNotNull { it.layout.frame }.maxOf { it.y + it.height }
            return sizes to bottom
        }
        val (normal, normalBottom) = texts(1.0)
        val (large, largeBottom) = texts(1.5)
        assertTrue(normal.isNotEmpty())
        assertEquals(normal.map { it * 1.5 }, large)
        assertTrue(largeBottom >= normalBottom, "content does not shrink with bigger text")
    }

    @Test
    fun everyTemplateRendersAtPhoneAndTabletSizes() {
        val files = File(spec, "fixtures/templates").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 5, "the spec has templates (the open spec holds a small set; the full library is checked privately)")
        val failures = mutableListOf<String>()
        for (file in files) {
            for ((w, h) in listOf(375.0 to 812.0, 768.0 to 1024.0)) {
                try {
                    val render = controller(paywall(file.nameWithoutExtension)).render(w, h, FakeMeasurer) ?: error("no render")
                    val all = nodes(render)
                    val missing = all.filter { it.layout.frame == null }
                    if (missing.isNotEmpty()) failures += "${file.name}@$w: ${missing.size} nodes without a frame (${missing.first().type})"
                    if (render.rootFrame.width > w + 0.01) failures += "${file.name}@$w: root wider than the screen"
                    all.mapNotNull { it.layout.frame }.firstOrNull { it.width.isNaN() || it.height.isNaN() || it.width < 0 || it.height < 0 }
                        ?.let { failures += "${file.name}@$w: bad frame $it" }
                } catch (e: Exception) {
                    failures += "${file.name}@$w: ${e::class.simpleName}: ${e.message}"
                }
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} problems:\n" + failures.joinToString("\n"))
    }

    @Test
    fun selectingAPackageMovesTheSelectedStyleAndTheBuyButtonUsesIt() {
        val name = File(spec, "fixtures/templates").listFiles()!!.map { it.nameWithoutExtension }.first { n ->
            val text = File(spec, "fixtures/templates/$n.json").readText()
            text.split("\"type\":\"package\"").size > 2 && text.contains("purchase_button")
        }
        val events = mutableListOf<ScreenEvent>()
        val c = controller(paywall(name), events)
        val first = c.render(375.0, 812.0, FakeMeasurer)!!
        val packages = nodes(first).filter { it.type == "package" }
        assertTrue(packages.size >= 2, "$name has packages")
        val selectedBefore = packages.single { it.semantics.selected == true }
        val other = packages.first { it.semantics.selected != true }
        c.handle(other.action!!)
        val after = nodes(c.render(375.0, 812.0, FakeMeasurer)!!).filter { it.type == "package" }
        assertEquals(other.key, after.single { it.semantics.selected == true }.key)
        assertFalse(after.single { it.key == selectedBefore.key }.semantics.selected == true)
        val buy = nodes(c.render(375.0, 812.0, FakeMeasurer)!!).first { it.action is Action.Purchase }
        c.handle(buy.action!!)
        val purchase = events.filterIsInstance<ScreenEvent.Purchase>().single()
        assertEquals((other.action as Action.SelectPackage).packageId, purchase.packageId)
    }

    @Test
    fun productVariablesFillTexts() {
        val name = File(spec, "fixtures/templates").listFiles()!!.map { it.nameWithoutExtension }.first { n ->
            File(spec, "fixtures/templates/$n.json").readText().contains("product.price")
        }
        val json = paywall(name)
        val ids = mutableListOf<String>()
        com.miniwall.core.logic.Flow.walk(json) { if (it.str("type") == "package") it.str("package_id")?.let(ids::add) }
        val vars = ids.associateWith { mapOf("product.price" to "\$1,234.56", "product.price_per_period" to "\$1,234.56/month", "product.period" to "month") }
        val texts = nodes(controller(json, vars = vars).render(375.0, 812.0, FakeMeasurer)!!).mapNotNull { (it.content as? Content.Text)?.plain }
        assertTrue(texts.any { it.contains("\$1,234.56") }, "a price shows in $name: $texts")
        assertTrue(texts.none { it.contains("{{") }, "no raw template left")
    }

    @Test
    fun requiredQuestionHoldsContinueUntilAnswered() {
        val name = File(spec, "fixtures/templates").listFiles()!!.map { it.nameWithoutExtension }.firstOrNull { n ->
            val t = File(spec, "fixtures/templates/$n.json").readText()
            t.contains("\"required\":true") && t.contains("input_single_choice")
        } ?: return
        val events = mutableListOf<ScreenEvent>()
        val c = controller(paywall(name), events)
        val before = nodes(c.render(375.0, 812.0, FakeMeasurer)!!)
        assertTrue(before.any { it.type == "button" && it.disabled && it.semantics.disabled }, "$name: continue waits and says so to screen readers")
        val option = before.first { it.action is Action.SelectOption }
        c.handle(option.action!!)
        val after = nodes(c.render(375.0, 812.0, FakeMeasurer)!!)
        assertTrue(after.single { it.key == option.key }.semantics.selected == true)
        assertTrue(events.any { it is ScreenEvent.Answer })
    }

    @Test
    fun sheetOpensAndClosingRestoresThePackage() {
        val json = Json.parseToJsonElement(
            """
            {"id":"s","default_locale":"en_US","components_localizations":{"en_US":{"t":"Open"}},
             "components_config":{"base":{"stack":{"type":"stack","id":"root","size":{"width":{"type":"fill"},"height":{"type":"fill"}},
               "dimension":{"type":"vertical","alignment":"center","distribution":"start"},"spacing":0,"margin":{},"padding":{},"components":[
                 {"type":"package","id":"p1","package_id":"a","is_selected_by_default":true,"stack":{"type":"stack","id":"pa","components":[],"size":{"width":{"type":"fixed","value":10},"height":{"type":"fixed","value":10}},"dimension":{"type":"vertical"}}},
                 {"type":"button","id":"b","action":{"type":"navigate_to","destination":"sheet","sheet":{"type":"sheet","id":"sh","size":{"width":{"type":"fill"},"height":{"type":"fixed","value":300}},"background_blur":true,
                    "stack":{"type":"stack","id":"sheet-stack","size":{"width":{"type":"fill"},"height":{"type":"fit"}},"dimension":{"type":"vertical"},"components":[
                      {"type":"package","id":"p2","package_id":"b","stack":{"type":"stack","id":"pb","components":[],"size":{"width":{"type":"fixed","value":10},"height":{"type":"fixed","value":10}},"dimension":{"type":"vertical"}}}]}}},
                  "stack":{"type":"stack","id":"bs","size":{"width":{"type":"fit"},"height":{"type":"fit"}},"dimension":{"type":"vertical"},"components":[
                    {"type":"text","id":"t","text_lid":"t","font_size":16,"size":{"width":{"type":"fit"},"height":{"type":"fit"}}}]}}
               ]}}}}
            """,
        ).jsonObject
        val c = controller(json)
        val first = c.render(375.0, 812.0, FakeMeasurer)!!
        assertEquals(null, first.sheet)
        c.handle(nodes(first).first { it.type == "button" }.action!!)
        val open = c.render(375.0, 812.0, FakeMeasurer)!!
        val sheet = assertNotNull(open.sheet)
        assertEquals(812.0 - 300.0, sheet.layout.frame!!.y)
        c.handle(nodes(open).first { (it.action as? Action.SelectPackage)?.packageId == "b" }.action!!)
        assertEquals("b", c.state.selectedPackageId)
        c.closeSheet()
        assertEquals("a", c.state.selectedPackageId)
        assertEquals(null, c.render(375.0, 812.0, FakeMeasurer)!!.sheet)
    }
}
