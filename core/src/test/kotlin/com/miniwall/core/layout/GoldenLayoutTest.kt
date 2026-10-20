package com.miniwall.core.layout

import com.miniwall.core.model.PaywallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every layout fixture in the Miniwall spec must produce the same frames as the web
 * renderer (golden/layout/<name>.json), within [TOLERANCE] pt, at every golden viewport.
 */
class GoldenLayoutTest {
    private val spec = File(com.miniwall.core.Spec.dir.path)

    @Test
    fun layoutMatchesWebGoldens() {
        val fixtures = File(spec, "fixtures/layout").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
            ?: fail("no fixtures in $spec")
        assertTrue(fixtures.isNotEmpty())
        val failures = mutableListOf<String>()
        for (fixtureFile in fixtures) {
            val name = fixtureFile.nameWithoutExtension
            val goldenFile = File(spec, "golden/layout/$name.json")
            if (!goldenFile.exists()) { failures += "$name: no golden (run pnpm golden:render)"; continue }
            val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
            val paywall = PaywallData.decode(fixture["paywall"]!!.jsonObject)
            val root = LayoutNode.from(paywall.base.stack) ?: fail("$name: root not rendered")
            val viewports = Json.parseToJsonElement(goldenFile.readText()).jsonObject["viewports"]!!.jsonObject
            for ((viewport, data) in viewports) {
                val (w, h) = viewport.split("x").map { it.toDouble() }
                val frames = LayoutEngine.layoutScreen(root, w, h)
                for ((id, expectedJson) in (data.jsonObject["nodes"] as JsonObject)) {
                    val e = expectedJson.jsonObject.let { o -> listOf("x", "y", "w", "h").map { o[it]!!.jsonPrimitive.double } }
                    val actual = frames[id]
                    if (actual == null) {
                        // display: none on the web (visible: false) reports an empty rect; native drops the node.
                        if (e.all { it == 0.0 }) continue
                        failures += "$name@$viewport $id: missing"
                        continue
                    }
                    val a = listOf(actual.x, actual.y, actual.width, actual.height)
                    if (a.indices.any { abs(a[it] - e[it]) > TOLERANCE }) {
                        failures += "$name@$viewport $id: expected ${fmt(e)} got ${fmt(a)}"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} layout mismatches:\n" + failures.joinToString("\n"))
    }

    private fun fmt(v: List<Double>) = v.joinToString(", ", "(", ")") { "%.2f".format(it) }

    companion object {
        /** Layout-only fixtures have no text, so frames must match closely (Chrome rounds to 1/64 px). */
        const val TOLERANCE = 0.1
    }
}
