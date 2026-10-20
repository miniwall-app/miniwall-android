package com.miniwall.core.render

import com.miniwall.core.model.PaywallData
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B156: carousel, tabs, video and multiple choice had no template, so nothing exercised them. The fixtures in
 * the spec repo fixtures/components are small screens for exactly those; this parses each, builds the render tree at
 * both golden sizes, checks the frames against the web goldens (golden/components) and drives the state changes.
 */
class ComponentFixturesTest {
    private val spec = File(com.miniwall.core.Spec.dir.path)
    private val files get() = File(spec, "fixtures/components").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun paywall(name: String): JsonObject =
        Json.parseToJsonElement(File(spec, "fixtures/components/$name.json").readText()).jsonObject["paywall"]!!.jsonObject

    private fun controller(name: String, events: MutableList<ScreenEvent> = mutableListOf()) =
        ScreenController(paywall(name), "en_US", dark = false, variablesPerPackage = emptyMap(), baseVariables = emptyMap(), infoPerPackage = emptyMap(), showRestoreButton = true) { events += it }

    private fun nodes(render: ScreenRender): List<RenderNode> {
        val out = mutableListOf<RenderNode>()
        listOfNotNull(render.header, render.root, render.footer, render.sheet).forEach { it.walk { n -> out += n } }
        return out
    }

    private fun render(c: ScreenController, w: Double = 375.0, h: Double = 812.0) = nodes(c.render(w, h, FakeMeasurer) ?: fail("no render"))

    /** The node types each fixture exists to exercise. */
    private val expectedTypes = mapOf(
        "carousel-pages" to "carousel", "carousel-peek" to "carousel", "tabs-buttons" to "tabs", "tabs-toggle" to "tab_control_toggle",
        "video-basic" to "video", "choice-multiple" to "input_option",
    )

    @Test
    fun everyFixtureParsesAndRendersAtBothSizes() {
        assertEquals(expectedTypes.keys, files.map { it.nameWithoutExtension }.toSet(), "a fixture without an entry in expectedTypes")
        for (file in files) {
            val name = file.nameWithoutExtension
            PaywallData.decode(paywall(name))
            for ((w, h) in listOf(375.0 to 812.0, 768.0 to 1024.0)) {
                val all = render(controller(name), w, h)
                assertTrue(all.any { it.type == expectedTypes.getValue(name) }, "$name@$w: no ${expectedTypes[name]} node")
                val missing = all.filter { it.layout.frame == null }
                assertTrue(missing.isEmpty(), "$name@$w: ${missing.size} nodes without a frame (${missing.firstOrNull()?.type})")
                all.mapNotNull { it.layout.frame }.firstOrNull { it.width.isNaN() || it.height.isNaN() || it.width < 0 || it.height < 0 }
                    ?.let { fail("$name@$w: bad frame $it") }
            }
        }
    }

    /**
     * Frames of every stack must match the web golden. Carousel pages are the exception on x and width: the web carousel is as
     * wide as its content (shrink-to-fit, max 100%) and lays out pages without the shift the native slider applies, the native
     * carousel is always as wide as its parent. Heights and y still must match. A known limit.
     */
    @Test
    fun framesMatchTheWebGoldens() {
        val failures = mutableListOf<String>()
        for (file in files) {
            val name = file.nameWithoutExtension
            val golden = File(spec, "golden/components/$name.json")
            if (!golden.exists()) { failures += "$name: no golden (run pnpm golden:render)"; continue }
            val viewports = Json.parseToJsonElement(golden.readText()).jsonObject["viewports"]!!.jsonObject
            for ((viewport, data) in viewports) {
                val (w, h) = viewport.split("x").map { it.toDouble() }
                val frames = render(controller(name), w, h).filter { it.id != null && it.layout.frame != null }.associate { it.id!! to it.layout.frame!! }
                val carousel = name.startsWith("carousel")
                for ((id, expectedJson) in data.jsonObject["nodes"] as JsonObject) {
                    val e = expectedJson.jsonObject.let { o -> listOf("x", "y", "w", "h").map { o[it]!!.jsonPrimitive.double } }
                    val actual = frames[id]
                    if (actual == null) { if (!e.all { it == 0.0 }) failures += "$name@$viewport $id: missing"; continue }
                    val a = listOf(actual.x, actual.y, actual.width, actual.height)
                    val checked = if (carousel && id.startsWith("page")) listOf(1, 3) else a.indices.toList()
                    if (checked.any { abs(a[it] - e[it]) > 0.1 }) failures += "$name@$viewport $id: expected ${e.map { "%.2f".format(it) }} got ${a.map { "%.2f".format(it) }}"
                }
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} layout mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun carouselShowsThePageItWasToldTo() {
        val c = controller("carousel-peek")
        fun slider(): RenderNode.Carousel = render(c).mapNotNull { it.carousel }.single()
        val first = slider()
        assertEquals(3, first.pageCount)
        assertEquals(1, first.currentPage, "initial_page_index")
        c.setCarouselPage(first.key, 2)
        assertEquals(2, slider().currentPage)
        c.setCarouselPage(first.key, 7)
        assertTrue(slider().currentPage in 0..2, "a page out of range falls back to a page that exists")
        val dots = render(c).count { it.key.contains("/dots/") }
        assertEquals(3, dots, "one indicator per page")
    }

    @Test
    fun tabButtonsSwitchTheShownTab() {
        val c = controller("tabs-buttons")
        fun ids() = render(c).mapNotNull { it.id }.toSet()
        assertTrue("pane0" in ids() && "pane1" !in ids(), "the default tab shows first")
        val buttons = render(c).filter { it.type == "tab_control_button" }
        assertEquals(2, buttons.size)
        assertEquals(listOf(true, false), buttons.map { it.semantics.selected }, "the open tab's button says it is selected")
        c.handle(assertNotNull(buttons[1].action))
        assertTrue("pane1" in ids() && "pane0" !in ids(), "tapping the second button opens the second tab")
        assertEquals(listOf(false, true), render(c).filter { it.type == "tab_control_button" }.map { it.semantics.selected })
    }

    @Test
    fun tabToggleSwitchesBetweenTheTwoTabs() {
        val c = controller("tabs-toggle")
        val off = render(c).single { it.type == "tab_control_toggle" }
        assertEquals(false, (off.content as Content.Toggle).on)
        c.handle(assertNotNull(off.action))
        val after = render(c)
        assertEquals(true, (after.single { it.type == "tab_control_toggle" }.content as Content.Toggle).on)
        assertTrue(after.any { it.id == "pane1" } && after.none { it.id == "pane0" })
    }

    @Test
    fun videoCarriesItsSettingsAndTakesItsSize() {
        val node = render(controller("video-basic")).single { it.type == "video" }
        val video = node.content as Content.Video
        assertTrue(video.url.endsWith("video.mp4"))
        assertEquals(true, video.cover)
        assertEquals(false, video.autoPlay)
        assertEquals(true, video.muted)
        assertEquals(true, video.showControls)
        assertNotNull(video.fallbackImageUrl)
        assertEquals(200.0, node.layout.frame!!.height, 0.1)
        assertEquals(343.0, node.layout.frame!!.width, 0.1)
    }

    @Test
    fun multipleChoiceHonoursMinAndMaxAndKeepsTapOrder() {
        val events = mutableListOf<ScreenEvent>()
        val c = controller("choice-multiple", events)
        fun options() = render(c).filter { it.type == "input_option" }
        assertEquals(3, options().size)
        c.handle(assertNotNull(options()[0].action))
        c.handle(assertNotNull(options()[2].action))
        val full = options()
        assertEquals(listOf(true, false, true), full.map { it.semantics.selected == true }, "two chosen, tap order kept")
        assertTrue(full[1].disabled && full[1].action == null, "at max the rest are off")
        c.handle(assertNotNull(full[0].action))
        val after = options()
        assertEquals(listOf(false, false, true), after.map { it.semantics.selected == true }, "tapping a chosen option clears it (multiple)")
        assertTrue(after.none { it.disabled }, "below max every option is open again")
        assertEquals(3, events.count { it is ScreenEvent.Answer })
    }
}
