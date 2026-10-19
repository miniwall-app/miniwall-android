package com.miniwall.core.session

import com.miniwall.core.logic.Targeting
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppIdentityTest {
    private fun v(op: String, actual: String?, expected: Any) = Targeting.matchesField(
        "app_version", op,
        if (expected is Number) JsonPrimitive(expected) else JsonPrimitive(expected as String),
        actual?.let(::JsonPrimitive),
    )

    @Test
    fun orderingComparesVersionSegmentsAsNumbers() {
        assertTrue(v("gt", "1.10.0", "1.9.9"))      // 10 > 9, not text order
        assertTrue(v("gte", "2.0", "2.0.0"))        // a missing segment is 0
        assertTrue(v("lte", "2", "2.0.0"))
        assertFalse(v("gt", "2", "2.0.0"))
        assertTrue(v("lt", "1.2.3", "1.2.10"))
        assertTrue(v("gte", "3", 2))                // a number in the audience rule
        assertTrue(v("lt", "01.2", "1.3"))          // leading zeros are just numbers
        assertTrue(v("gt", "99999999999999999999.0", "1.0"))
    }

    @Test
    fun aSegmentThatIsNotANumberNeverMatches() {
        for (op in listOf("gt", "gte", "lt", "lte")) {
            assertFalse(v(op, "2.0-beta", "1.0"), op)
            assertFalse(v(op, "2.0.x", "1.0"), op)
            assertFalse(v(op, "1.0", "1.x"), op)
            assertFalse(v(op, "1..2", "1.0"), op)
            assertFalse(v(op, "", "1.0"), op)
            assertFalse(v(op, null, "1.0"), op)
        }
    }

    @Test
    fun otherOperatorsStayTextual() {
        assertTrue(v("eq", "2.3.1", "2.3.1"))
        assertTrue(v("neq", "2.3.1", "2.3.2"))
        assertTrue(v("contains", "2.3.1", ".3."))
        assertTrue(v("starts_with", "2.3.1", "2."))
        assertTrue(Targeting.matchesField("app_version", "exists", null, JsonPrimitive("1.0")))
        // other fields keep the plain numeric rule
        assertTrue(Targeting.matchesField("os_version", "gte", JsonPrimitive(16), JsonPrimitive("17")))
        assertTrue(Targeting.matchesField("user.build", "gt", JsonPrimitive("1.0"), JsonPrimitive("1.5")))
        assertFalse(Targeting.matchesField("user.build", "gt", JsonPrimitive("1.0"), JsonPrimitive("1.2.3")))  // not a number: plain rule
    }

    @Test
    fun anAudienceOnAppVersionPicksTheScreen() {
        val memory = DeviceMemory(MemoryStore())
        val screen = """{"components_config":{"base":{"stack":{}}},"components_localizations":{}}"""
        val body = Json.parseToJsonElement(
            """{"paywall":$screen,"paywallId":"p1","placement":"home","rules":[
              {"id":"r1","conditions":[{"field":"app_version","op":"gte","value":"2.10"}],"paywall":{"paywall":$screen,"paywallId":"p2"}}]}""",
        ).jsonObject
        val input = TargetingInput(emptyMap(), "en-US", "mobile", "android", appVersion = "2.9.4")
        assertEquals("p1", Delivery.decide(body, input, memory).context?.paywallId)
        assertEquals("p2", Delivery.decide(body, input.copy(appVersion = "2.10.0"), memory).context?.paywallId)
        assertEquals("p1", Delivery.decide(body, input.copy(appVersion = null), memory).context?.paywallId)
    }

    @Test
    fun theAppHeaderNamesPlatformAndId() {
        assertEquals("android:com.acme.app", Events.appHeader("android", "com.acme.app"))
        assertEquals("ios:com.acme.app", Events.appHeader("ios", " com.acme.app "))
        assertNull(Events.appHeader("ios", null))
        assertNull(Events.appHeader("android", "  "))
    }

    @Test
    fun eventsCarryPlatformAndVersionsWhenKnown() {
        val ctx = EventContext("p1", 3, "home")
        val e = Events.event("view", ctx, "visitor-1", "2026-01-01T00:00:00.000Z", client = EventClient("ios", "0.1.0", "2.3.1"))
        assertEquals("ios", e["platform"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", e["sdkVersion"]!!.jsonPrimitive.content)
        assertEquals("2.3.1", e["appVersion"]!!.jsonPrimitive.content)
        val noApp = Events.event("view", ctx, "visitor-1", "2026-01-01T00:00:00.000Z", client = EventClient("android", "0.1.0"))
        assertFalse(noApp.containsKey("appVersion"))
        assertEquals("android", noApp["platform"]!!.jsonPrimitive.content)
        assertFalse(Events.event("view", ctx, "visitor-1", "2026-01-01T00:00:00.000Z").containsKey("platform"))
    }

    @Test
    fun `app version label keeps events valid for the server`() {
        assertEquals("1.2.3--beta-", AutoAttributes.appVersion("1.2.3 (beta)"))
        assertEquals(32, AutoAttributes.appVersion("1".repeat(50))!!.length)
        assertNull(AutoAttributes.appVersion("(x)"))
        assertNull(AutoAttributes.appVersion("  "))
    }
}
