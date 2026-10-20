package com.miniwall.sdk

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.miniwall.core.logic.Flow
import com.miniwall.core.logic.Period
import com.miniwall.core.logic.ProductInfo
import com.miniwall.core.session.PurchaseResult
import com.miniwall.core.session.SkipReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests of the Android SDK: a real presentation (MiniwallActivity) on an emulator or device, driven through the
 * semantics tree (buttons are found by their text or content description, as TalkBack would read them).
 * The screens are the .json files in the spec's mock-api/ui (assets of this test APK); the same screens drive the iOS, React Native and Flutter suites.
 * Run: see README.md in this folder. Not run at cloud (no Android SDK there): written against the real APIs, to be run on first use.
 */
@RunWith(AndroidJUnit4::class)
class MiniwallUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var server: MockWebServer? = null

    private fun asset(name: String): JsonObject =
        Json.parseToJsonElement(instrumentation.context.assets.open(name).bufferedReader().readText()).jsonObject

    private val paywall by lazy { asset("paywall.json") }
    private val welcome by lazy { asset("flow-welcome.json") }
    private val flowScreens by lazy { mapOf("flow_question" to asset("flow-question.json"), "flow_done" to asset("flow-done.json")) }

    private val products = mapOf(
        "monthly" to ProductInfo(9.99, "USD", Period.Every(Period.Unit.MONTH)),
        "annual" to ProductInfo(79.99, "USD", Period.Every(Period.Unit.YEAR)),
    )

    @Before
    fun setUp() {
        // "Remove animations": the SDK then draws no spinner animation, so Compose can reach idle while a purchase runs.
        instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
        server = MockWebServer().apply {
            dispatcher = StubDispatcher(asset("placements.json"), asset("paywalls.json"))
            start()
        }
        Miniwall.configure(
            instrumentation.targetContext,
            "pk_test_ui",
            Miniwall.Options(apiUrl = server!!.url("/").toString().trimEnd('/'), analytics = false, timeoutMs = 3000),
        )
    }

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        runCatching { server?.shutdown() }
        instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 1").close()
    }

    // ---------------------------------------------------------------- helpers

    private fun present(source: ScreenSource, options: PresentOptions): CompletableDeferred<Result<PurchaseResult>> {
        val out = CompletableDeferred<Result<PurchaseResult>>()
        scope.launch { out.complete(runCatching { Miniwall.present(compose.activity, source, options) }) }
        return out
    }

    private fun label(text: String): SemanticsMatcher = hasText(text, substring = true) or hasContentDescription(text, substring = true)

    private fun shown(text: String) = runCatching { compose.onAllNodes(label(text)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 15_000) { shown(text) }

    private fun waitGone(text: String) = compose.waitUntil(timeoutMillis = 15_000) { !shown(text) }

    private fun node(text: String) = compose.onAllNodes(label(text)).onFirst()

    private fun tap(text: String) = node(text).performClick()

    private fun result(deferred: CompletableDeferred<Result<PurchaseResult>>): PurchaseResult =
        runBlocking { withTimeout(15_000) { deferred.await() } }.getOrThrow()

    private fun optionsWithStore(
        buys: Boolean = true,
        restores: Boolean = true,
        seen: MutableList<PurchaseRequest> = mutableListOf(),
        screens: Map<String, JsonObject> = emptyMap(),
    ) = PresentOptions(
        products = products,
        screens = screens,
        // The fake store: 300 ms, then yes or no.
        onPurchase = { request -> seen += request; delay(300); buys },
        onRestore = { delay(300); restores },
        onAction = { _, _ -> true },
    )

    // ---------------------------------------------------------------- open, choose, buy, restore, close

    /** A custom font whose server never answers (here: an address nothing listens on) must not hold the screen back for its 6 s timeout. */
    @Test
    fun aFontThatNeverArrivesDoesNotHoldTheScreenBack() {
        val config = paywall["components_config"] as JsonObject
        val fonts = JsonObject(mapOf("Blackhole@400" to JsonObject(mapOf("android" to JsonPrimitive("https://10.255.255.1/blackhole.ttf")))))
        val withFont = JsonObject(paywall + ("components_config" to JsonObject(config + ("fonts" to fonts))))
        val started = System.nanoTime()
        val r = present(ScreenSource.Json(withFont), optionsWithStore())
        waitFor("Go Pro")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("the screen took $ms ms to show (limit ${FontFiles.SHOW_WAIT_MS} ms for fonts, plus opening it)", ms < 4_000)
        tap("Close")
        assertTrue(result(r) is PurchaseResult.Closed)
    }

    @Test
    fun opensAndTheCloseButtonEndsItAsClosed() {
        val r = present(ScreenSource.Json(paywall), optionsWithStore())
        waitFor("Go Pro")
        node("Go Pro").assertExists()
        tap("Close")
        assertTrue(result(r) is PurchaseResult.Closed)
        waitGone("Go Pro")
    }

    @Test
    fun choosingAPlanThenSubscribeBuysThatPlan() {
        val seen = mutableListOf<PurchaseRequest>()
        val r = present(ScreenSource.Json(paywall), optionsWithStore(seen = seen))
        waitFor("Go Pro")
        node("Annual").assertIsSelected() // is_selected_by_default
        node("Monthly").assertIsNotSelected()
        tap("Monthly")
        node("Monthly").assertIsSelected()
        node("Annual").assertIsNotSelected()
        tap("Subscribe")
        val done = result(r)
        assertEquals("monthly", (done as PurchaseResult.Purchased).packageId)
        assertEquals("monthly", seen.single().packageId)
        assertEquals(9.99, seen.single().product?.price ?: 0.0, 0.001)
    }

    @Test
    fun aCancelledPurchaseKeepsTheScreenOpen() {
        val r = present(ScreenSource.Json(paywall), optionsWithStore(buys = false))
        waitFor("Go Pro")
        tap("Subscribe")
        compose.waitForIdle()
        Thread.sleep(800) // longer than the fake store
        node("Go Pro").assertExists()
        assertTrue("still open", !r.isCompleted)
        tap("Close")
        assertTrue(result(r) is PurchaseResult.Closed)
    }

    @Test
    fun restoreEndsRestoredWhenSomethingWasRestoredElseStays() {
        val none = present(ScreenSource.Json(paywall), optionsWithStore(restores = false))
        waitFor("Restore purchases")
        tap("Restore purchases")
        Thread.sleep(800)
        assertTrue("nothing restored: still open", !none.isCompleted)
        tap("Close")
        assertTrue(result(none) is PurchaseResult.Closed)
        waitGone("Go Pro")

        val some = present(ScreenSource.Json(paywall), optionsWithStore(restores = true))
        waitFor("Restore purchases")
        tap("Restore purchases")
        assertTrue(result(some) is PurchaseResult.Restored)
    }

    @Test
    fun withoutOnRestoreTheRestoreButtonIsHidden() {
        val r = present(ScreenSource.Json(paywall), PresentOptions(products = products, onPurchase = { true }))
        waitFor("Go Pro")
        assertTrue(!shown("Restore purchases"))
        tap("Close")
        result(r)
    }

    // ---------------------------------------------------------------- system Back

    @Test
    fun systemBackOnTheFirstScreenCloses() {
        val r = present(ScreenSource.Json(paywall), optionsWithStore())
        waitFor("Go Pro")
        device.pressBack()
        assertTrue(result(r) is PurchaseResult.Closed)
    }

    /**
     * Rule: "inside a flow, Back is screen:back". MiniwallActivity currently calls close() for system Back on
     * every screen, so this test is expected to FAIL until the Activity routes Back to the session (previous screen, close on the first).
     */
    @Test
    fun systemBackInsideAFlowGoesToThePreviousScreen() {
        val r = present(ScreenSource.Json(welcome), optionsWithStore(screens = flowScreens))
        waitFor("Welcome aboard")
        tap("Next")
        waitFor("What is your goal?")
        device.pressBack()
        waitFor("Welcome aboard")
        assertTrue("Back inside a flow does not end it", !r.isCompleted)
        device.pressBack()
        assertTrue(result(r) is PurchaseResult.Closed)
    }

    // ---------------------------------------------------------------- flow, required question

    @Test
    fun flowWithARequiredQuestionAndTheScreensBackButton() {
        val r = present(ScreenSource.Json(welcome), optionsWithStore(screens = flowScreens))
        waitFor("Welcome aboard")
        tap("Next")
        waitFor("What is your goal?")
        node("Continue").assertIsNotEnabled() // required: nothing answered yet
        tap("Lose weight")
        node("Lose weight").assertIsSelected()
        node("Continue").assertIsEnabled()
        tap("Continue")
        waitFor("All set")
        tap("Back") // the screen's own button = screen:back
        waitFor("What is your goal?")
        node("Lose weight").assertIsSelected() // the answer survived
        tap("Continue")
        waitFor("All set")
        tap("Finish")
        val done = result(r) as PurchaseResult.ActionTaken
        assertEquals("finish", done.actionId)
        assertEquals(Flow.Answer.One("lose_weight"), done.answers["goal"])
    }

    // ---------------------------------------------------------------- placement over the network, offline

    @Test
    fun offlineTheLastCopyOfAPlacementStillShows() {
        val first = present(ScreenSource.Placement("onboarding"), optionsWithStore())
        waitFor("Go Pro")
        tap("Close")
        assertTrue(result(first) is PurchaseResult.Closed)
        waitGone("Go Pro")

        server!!.shutdown() // the network is gone
        val offline = present(ScreenSource.Placement("onboarding"), optionsWithStore())
        waitFor("Go Pro")
        tap("Close")
        assertTrue(result(offline) is PurchaseResult.Closed)
        waitGone("Go Pro")

        val never = present(ScreenSource.Placement("never_loaded"), optionsWithStore())
        val failure = runBlocking { withTimeout(15_000) { never.await() } }
        assertNotNull("no cache and no network: present throws", failure.exceptionOrNull())
    }

    @Test
    fun aHoldoutPlacementIsSkippedWithoutOpeningAnything() {
        val r = present(ScreenSource.Placement("holdout"), optionsWithStore())
        assertEquals(PurchaseResult.Skipped(SkipReason.HOLDOUT), result(r))
        assertTrue(!shown("Go Pro"))
    }
}

/** Plays the delivery API for the offline and skip tests: /v1/sdk/placements/<name>, /v1/sdk/paywalls/<id>, events. */
private class StubDispatcher(private val placements: JsonObject, private val paywalls: JsonObject) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(400)
        fun json(body: Any?) = MockResponse().setResponseCode(if (body == null) 404 else 200).setHeader("Content-Type", "application/json").setBody(body?.toString() ?: "{}")
        Regex("^/v1/sdk/placements/([^/]+)$").find(path)?.let { return json(placements[it.groupValues[1]]) }
        Regex("^/v1/sdk/paywalls/([^/]+)$").find(path)?.let { return json(paywalls[it.groupValues[1]]) }
        return json(JsonObject(emptyMap())) // events and anything else
    }
}
