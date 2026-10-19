package com.miniwall.core.session

import com.miniwall.core.logic.Flow
import com.miniwall.core.render.Action
import com.miniwall.core.render.FakeMeasurer
import com.miniwall.core.render.RenderNode
import com.miniwall.core.render.ScreenController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private fun screen(id: String, buttons: String) = Json.parseToJsonElement(
        """{"id":"$id","default_locale":"en_US","components_localizations":{"en_US":{}},
        "components_config":{"base":{"stack":{"type":"stack","id":"root","size":{"width":{"type":"fill"},"height":{"type":"fill"}},
        "dimension":{"type":"vertical","alignment":"center","distribution":"start"},"components":[$buttons]}}}}""",
    ).jsonObject

    private fun button(id: String, actionJson: String, onPress: String? = null) =
        """{"type":"button","id":"$id","name":"$id","action":$actionJson,${onPress?.let { "\"triggers\":{\"on_press\":\"$it\"}," } ?: ""}
        "stack":{"type":"stack","id":"${id}s","components":[],"size":{"width":{"type":"fixed","value":40},"height":{"type":"fixed","value":40}},"dimension":{"type":"vertical"}}}"""

    private fun pkg(id: String) =
        """{"type":"package","id":"pk$id","package_id":"$id","stack":{"type":"stack","id":"pks$id","components":[],"size":{"width":{"type":"fixed","value":40},"height":{"type":"fixed","value":40}},"dimension":{"type":"vertical"}}}"""

    private val buy = """{"type":"purchase_button","id":"buy","stack":{"type":"stack","id":"buys","components":[],"size":{"width":{"type":"fixed","value":40},"height":{"type":"fixed","value":40}},"dimension":{"type":"vertical"}}}"""

    private class Host : SessionHost {
        var purchaseResult: Result<Boolean> = Result.success(true)
        var actionProceeds = true
        /** When set, `purchase` waits: the test answers later through [answerPurchase]. */
        var deferPurchase = false
        private var waiting: ((Result<Boolean>) -> Unit)? = null
        fun answerPurchase(result: Result<Boolean>) { waiting?.invoke(result); waiting = null }
        val shown = mutableListOf<ScreenController>()
        var result: PurchaseResult? = null
        val errors = mutableListOf<Throwable>()
        override fun purchase(packageId: String, answers: Map<String, Flow.Answer>, done: (Result<Boolean>) -> Unit) { if (deferPurchase) waiting = done else done(purchaseResult) }
        override fun restore(done: (Result<Boolean>) -> Unit) = done(Result.success(true))
        override fun action(actionId: String, answers: Map<String, Flow.Answer>, done: (Boolean) -> Unit) = done(actionProceeds)
        val opened = mutableListOf<String>()
        override fun openUrl(url: String) { opened += url }
        override fun answer(fieldId: String, value: Flow.Answer?, answers: Map<String, Flow.Answer>) {}
        override fun error(error: Throwable) { errors += error }
        override fun show(controller: ScreenController, animated: Boolean) { shown += controller }
        override fun finish(result: PurchaseResult) { this.result = result }
        override fun busy(isBusy: Boolean) {}
    }

    private fun session(first: JsonObject, screens: Map<String, JsonObject>, host: Host, events: MutableList<JsonObject>, memory: DeviceMemory = DeviceMemory(MemoryStore())) =
        PaywallSession(
            first = Resolved(first, EventContext("p1", 3, "onboarding")),
            memory = memory,
            enqueue = { events += it },
            loadScreen = { id, done -> done(screens[id]?.let { Result.success(Resolved(it, EventContext(id, 1))) } ?: Result.failure(IllegalStateException("missing"))) },
            controllerFactory = { json, answers, onEvent ->
                ScreenController(json, "en_US", false, emptyMap(), emptyMap(), emptyMap(), true, answers, onEvent)
            },
            host = host,
        )

    private fun tap(controller: ScreenController, predicate: (RenderNode) -> Boolean) {
        val render = controller.render(375.0, 812.0, FakeMeasurer)!!
        val nodes = mutableListOf<RenderNode>()
        render.root.walk { nodes += it }
        controller.handle(nodes.first(predicate).action!!)
    }

    private fun types(events: List<JsonObject>) = events.map { (it["type"] as JsonPrimitive).content }

    @Test
    fun purchaseReportsClickThenPurchaseAndFinishes() {
        val host = Host()
        val events = mutableListOf<JsonObject>()
        session(screen("p1", "${pkg("monthly")},$buy"), emptyMap(), host, events)
        tap(host.shown.last()) { it.action is Action.Purchase }
        assertEquals(listOf("view", "purchase_click", "purchase"), types(events))
        assertEquals("onboarding", (events[0]["placement"] as JsonPrimitive).content)
        assertEquals(PurchaseResult.Purchased("monthly", emptyMap()), host.result)
    }

    @Test
    fun closeWhilePurchasingWaitsForTheAnswer() {
        // A purchase that goes through after the visitor tried to close is still a purchase.
        val bought = Host().apply { deferPurchase = true }
        val boughtEvents = mutableListOf<JsonObject>()
        val a = session(screen("s", pkg("m") + "," + buy), emptyMap(), bought, boughtEvents)
        tap(bought.shown.last()) { it.type == "purchase_button" }
        a.close()
        assertNull(bought.result, "still waiting for the store")
        bought.answerPurchase(Result.success(true))
        assertIs<PurchaseResult.Purchased>(bought.result)

        // Cancelled: the close the visitor asked for is applied after the answer.
        val cancelled = Host().apply { deferPurchase = true }
        val cancelledEvents = mutableListOf<JsonObject>()
        val b = session(screen("s", pkg("m") + "," + buy), emptyMap(), cancelled, cancelledEvents)
        tap(cancelled.shown.last()) { it.type == "purchase_button" }
        b.close()
        cancelled.answerPurchase(Result.success(false))
        assertIs<PurchaseResult.Closed>(cancelled.result)
        assertEquals(listOf("view", "purchase_click", "purchase_cancel", "close"), types(cancelledEvents))
    }

    @Test
    fun cancelledPurchaseStaysOpen() {
        val host = Host().apply { purchaseResult = Result.success(false) }
        val events = mutableListOf<JsonObject>()
        session(screen("p1", "${pkg("monthly")},$buy"), emptyMap(), host, events)
        tap(host.shown.last()) { it.action is Action.Purchase }
        assertEquals(listOf("view", "purchase_click", "purchase_cancel"), types(events))
        assertNull(host.result)
    }

    @Test
    fun goToScreenAndBackThenCloseFromFirst() {
        val host = Host()
        val events = mutableListOf<JsonObject>()
        val second = screen("p2", button("back", """{"type":"workflow"}""", "screen:back"))
        session(screen("p1", button("next", """{"type":"workflow"}""", "screen:p2")), mapOf("p2" to second), host, events)
        tap(host.shown.last()) { it.type == "button" }
        assertEquals(2, host.shown.size)
        assertEquals("p2", (events.last()["paywallId"] as JsonPrimitive).content)
        assertEquals("onboarding", (events.last()["placement"] as JsonPrimitive).content, "flow screens count under the placement")
        tap(host.shown.last()) { it.type == "button" }
        assertEquals(3, host.shown.size)
        tap(host.shown.last()) { it.type == "button" }
        tap(host.shown.last()) { it.type == "button" }
        tap(host.shown.last()) { it.type == "button" }
        assertNull(host.result, "still in the flow")
    }

    @Test
    fun customActionFinishesUnlessKeptOpen() {
        val host = Host().apply { actionProceeds = false }
        val events = mutableListOf<JsonObject>()
        session(screen("p1", button("go", """{"type":"workflow"}""", "get_started")), emptyMap(), host, events)
        tap(host.shown.last()) { it.type == "button" }
        assertNull(host.result)
        host.actionProceeds = true
        tap(host.shown.last()) { it.type == "button" }
        assertIs<PurchaseResult.ActionTaken>(host.result)
        assertEquals(listOf("view", "action", "action"), types(events))
    }

    /**
     * Web parity: `complete_workflow` is pressable but the Web SDK never wires `onCompleteWorkflowNavigate`
     * (sdks/web/src/render.ts), so the press does nothing: no URL opened, no event, no result, no close, no onAction.
     */
    @Test
    fun completeWorkflowDoesNothingLikeTheWebSdk() {
        val host = Host()
        val events = mutableListOf<JsonObject>()
        val json = Json.parseToJsonElement(
            """{"id":"p1","default_locale":"en_US","components_localizations":{"en_US":{"u":"https://example.com/done"}},
            "components_config":{"base":{"stack":{"type":"stack","id":"root","size":{"width":{"type":"fill"},"height":{"type":"fill"}},
            "dimension":{"type":"vertical"},"components":[${button("done", """{"type":"complete_workflow","url":{"url_lid":"u","method":"external_browser"}}""")}]}}}}""",
        ).jsonObject
        session(json, emptyMap(), host, events)
        tap(host.shown.last()) { it.type == "button" }
        assertTrue(host.opened.isEmpty())
        assertTrue(host.errors.isEmpty())
        assertNull(host.result)
        assertEquals(listOf("view"), types(events))
        assertEquals(1, host.shown.size)
    }

    @Test
    fun frequencyCapAndHoldoutDecideBeforeShowing() {
        val memory = DeviceMemory(MemoryStore())
        val body = Json.parseToJsonElement(
            """{"paywall":${screen("p1", "")},"paywallId":"p1","version":1,"placement":"home","frequency":{"max":1,"per":"ever"}}""",
        ).jsonObject
        val input = TargetingInput(emptyMap(), "en_US", "mobile", "ios")
        assertNull(Delivery.decide(body, input, memory).skip)
        memory.recordShown("home")
        assertEquals(SkipReason.FREQUENCY_CAP, Delivery.decide(body, input, memory).skip)
        val held = Json.parseToJsonElement("""{"paywall":${screen("p1", "")},"paywallId":"p1","placement":"x","holdout":{"id":"h","share":100}}""").jsonObject
        assertEquals(SkipReason.HOLDOUT, Delivery.decide(held, input, memory).skip)
    }

    @Test
    fun audienceRuleSendsTheMatchingScreenOrNothing() {
        val memory = DeviceMemory(MemoryStore())
        val body = Json.parseToJsonElement(
            """{"paywall":${screen("p1", "")},"paywallId":"p1","placement":"home","rules":[
              {"id":"r1","conditions":[{"field":"user.plan","op":"eq","value":"pro"}],"paywall":null},
              {"id":"r2","conditions":[{"field":"platform","op":"eq","value":"ios"}],"paywall":{"paywall":${screen("p9", "")},"paywallId":"p9"}}]}""",
        ).jsonObject
        val pro = TargetingInput(mapOf("plan" to JsonPrimitive("Pro")), null, "mobile", "ios")
        assertEquals(SkipReason.AUDIENCE, Delivery.decide(body, pro, memory).skip)
        val free = TargetingInput(mapOf("plan" to JsonPrimitive("free")), null, "mobile", "ios")
        assertEquals("p9", Delivery.decide(body, free, memory).context?.paywallId)
        assertEquals("p1", Delivery.decide(body, free.copy(platform = "android"), memory).context?.paywallId)
    }

    @Test
    fun laterPurchaseIsCreditedToTheScreenWhereBuyWasTapped() {
        val memory = DeviceMemory(MemoryStore())
        val host = Host().apply { purchaseResult = Result.failure(IllegalStateException("store busy")) }
        val events = mutableListOf<JsonObject>()
        session(screen("p1", "${pkg("annual")},$buy"), emptyMap(), host, events, memory)
        tap(host.shown.last()) { it.action is Action.Purchase }
        assertTrue(host.errors.isNotEmpty())
        PaywallSession.reportPurchase(memory, { events += it }, null, null)
        val last = events.last()
        assertEquals("purchase", (last["type"] as JsonPrimitive).content)
        assertEquals("annual", (last["packageId"] as JsonPrimitive).content)
        assertEquals("p1", (last["paywallId"] as JsonPrimitive).content)
    }
}
