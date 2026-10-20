package com.miniwall.core.session

import com.miniwall.core.logic.Flow
import com.miniwall.core.render.Content
import com.miniwall.core.render.FakeMeasurer
import com.miniwall.core.render.RenderNode
import com.miniwall.core.render.ScreenController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screens in the spec's mock-api/ui are what the XCUITest, Compose, Detox and Flutter UI tests drive. This runs the same
 * scenario against the shared core (the one part of the UI suites that runs at cloud), so a screen that stops behaving
 * as the UI tests expect fails here first. The texts below are the labels the UI tests look for.
 */
class UiFixturesTest {
    private val dir = com.miniwall.core.Spec.ui
    private fun load(name: String) = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject

    private class Host(val screens: Map<String, JsonObject>) : SessionHost {
        var buy = true
        var restored = true
        val shown = mutableListOf<ScreenController>()
        var result: PurchaseResult? = null
        var purchasedPackage: String? = null
        override fun purchase(packageId: String, answers: Map<String, Flow.Answer>, done: (Result<Boolean>) -> Unit) { purchasedPackage = packageId; done(Result.success(buy)) }
        override fun restore(done: (Result<Boolean>) -> Unit) = done(Result.success(restored))
        override fun action(actionId: String, answers: Map<String, Flow.Answer>, done: (Boolean) -> Unit) = done(true)
        override fun openUrl(url: String) {}
        override fun answer(fieldId: String, value: Flow.Answer?, answers: Map<String, Flow.Answer>) {}
        override fun error(error: Throwable) = throw error
        override fun show(controller: ScreenController, animated: Boolean) { shown += controller }
        override fun finish(result: PurchaseResult) { this.result = result }
        override fun busy(isBusy: Boolean) {}
    }

    private fun start(first: String, host: Host): PaywallSession = PaywallSession(
        first = Resolved(load(first), EventContext(first, 1, "ui")),
        memory = DeviceMemory(MemoryStore()),
        enqueue = {},
        loadScreen = { id, done -> done(Result.success(Resolved(host.screens.getValue(id), EventContext(id, 1)))) },
        controllerFactory = { json, answers, onEvent ->
            val vars = mapOf("monthly" to mapOf("product.price" to "\$9.99"), "annual" to mapOf("product.price" to "\$79.99"))
            ScreenController(json, "en_US", false, vars, emptyMap(), emptyMap(), true, answers, onEvent)
        },
        host = host,
    )

    private fun nodes(c: ScreenController): List<RenderNode> {
        val out = mutableListOf<RenderNode>()
        c.render(375.0, 812.0, FakeMeasurer)!!.root.walk { out += it }
        return out
    }

    /** What a screen reader (and a UI test) sees: a button's merged texts or its name, a text's own words. */
    private fun texts(node: RenderNode): List<String> {
        val own = (node.content as? Content.Text)?.plain
        val inner = ArrayList<String>()
        node.children.forEach { inner += texts(it) }
        return listOfNotNull(own) + inner
    }

    private fun button(c: ScreenController, label: String): RenderNode =
        nodes(c).first { n -> n.semantics.isButton && (n.semantics.label == label || texts(n).any { it.contains(label) }) }

    private fun tap(c: ScreenController, label: String) = c.handle(button(c, label).action!!)

    @Test
    fun paywallSelectsAPackageBuysAndRestores() {
        val host = Host(emptyMap())
        start("paywall", host)
        val c = host.shown.last()
        assertTrue(button(c, "Annual").semantics.selected == true, "annual is selected by default")
        assertFalse(button(c, "Monthly").semantics.selected == true)
        tap(c, "Monthly")
        assertTrue(button(c, "Monthly").semantics.selected == true)
        assertFalse(button(c, "Annual").semantics.selected == true)
        assertTrue(texts(button(c, "Monthly")).single().startsWith("Monthly"), "price variable is filled")
        tap(c, "Subscribe")
        assertEquals("monthly", host.purchasedPackage)
        assertEquals(PurchaseResult.Purchased("monthly", emptyMap()), host.result)
    }

    @Test
    fun restoreEndsRestoredOnlyWhenSomethingWasRestored() {
        val none = Host(emptyMap()).apply { restored = false }
        start("paywall", none)
        tap(none.shown.last(), "Restore")
        assertNull(none.result, "nothing restored: the screen stays")
        val some = Host(emptyMap())
        start("paywall", some)
        tap(some.shown.last(), "Restore")
        assertIs<PurchaseResult.Restored>(some.result)
    }

    @Test
    fun cancelledPurchaseKeepsTheScreenAndCloseButtonCloses() {
        val host = Host(emptyMap()).apply { buy = false }
        start("paywall", host)
        tap(host.shown.last(), "Subscribe")
        assertNull(host.result)
        tap(host.shown.last(), "Close")
        assertIs<PurchaseResult.Closed>(host.result)
    }

    @Test
    fun flowGoesForwardRequiresAnAnswerAndGoesBack() {
        val host = Host(mapOf("flow_question" to load("flow-question"), "flow_done" to load("flow-done")))
        val session = start("flow-welcome", host)
        assertTrue(texts(nodes(host.shown.last()).first { it.type == "text" }).single() == "Welcome aboard")
        tap(host.shown.last(), "Next")
        val question = host.shown.last()
        assertEquals(2, host.shown.size)
        val cont = button(question, "Continue")
        assertTrue(cont.disabled && cont.semantics.disabled, "required question: Continue is disabled until answered")
        tap(question, "Lose weight")
        assertTrue(button(host.shown.last(), "Lose weight").semantics.selected == true)
        assertFalse(button(host.shown.last(), "Continue").disabled)
        tap(host.shown.last(), "Continue")
        assertEquals(3, host.shown.size)
        tap(host.shown.last(), "Back") // screen:back from the last screen: the question is shown again, answer kept
        assertEquals(4, host.shown.size)
        assertTrue(button(host.shown.last(), "Lose weight").semantics.selected == true, "the answer survives going back")
        tap(host.shown.last(), "Back") // back to the welcome screen
        assertEquals(5, host.shown.size)
        assertNull(host.result)
        tap(host.shown.last(), "Close")
        assertEquals(PurchaseResult.Closed(mapOf("goal" to Flow.Answer.One("lose_weight"))), host.result)
        session.close()
    }

    @Test
    fun finishingTheFlowReportsTheAnswersWithTheAction() {
        val host = Host(mapOf("flow_question" to load("flow-question"), "flow_done" to load("flow-done")))
        start("flow-welcome", host)
        tap(host.shown.last(), "Next")
        tap(host.shown.last(), "Build strength")
        tap(host.shown.last(), "Continue")
        tap(host.shown.last(), "Finish")
        assertEquals(PurchaseResult.ActionTaken("finish", mapOf("goal" to Flow.Answer.One("strength"))), host.result)
    }

    @Test
    fun deliveryBodiesCarryTheSameScreens() {
        val placements = Json.parseToJsonElement(File(dir, "placements.json").readText()).jsonObject
        assertEquals(setOf("onboarding", "flow", "capped", "holdout", "toast"), placements.keys)
        for ((_, body) in placements) assertTrue(Delivery.asPaywall((body as JsonObject)["paywall"]) != null)
        val paywalls = Json.parseToJsonElement(File(dir, "paywalls.json").readText()).jsonObject
        assertEquals(setOf("flow_question", "flow_done"), paywalls.keys)
    }
}
