package com.miniwall.core.logic

import com.miniwall.core.render.Localization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class JsNumberFormatTest {
    @Test
    fun printsLikeJavaScriptsString() {
        val cases = mapOf(
            0.0 to "0", -0.0 to "0", 5.0 to "5", -5.0 to "-5", 1.5 to "1.5", 0.0001 to "0.0001", 0.000001 to "0.000001",
            1e-7 to "1e-7", 1.5e-7 to "1.5e-7", 12345678.5 to "12345678.5", 1e21 to "1e+21", 1.5e300 to "1.5e+300",
            123456789012345680000.0 to "123456789012345680000", 9e15 to "9000000000000000", 0.1 + 0.2 to "0.30000000000000004",
            Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity", Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((d, text) in cases) assertEquals(text, JsNumber.format(d), "$d")
    }

    @Test
    fun markdownDotMatchesWhatJavaScriptMatches() {
        // U+0085 is an ordinary character to JavaScript's `.`; line breaks (\n, \r, U+2028, U+2029) are not.
        assertEquals("<s>a\u0085b</s>", TextLogic.htmlFromMarkdown("~a\u0085b~"))
        assertEquals("~a\rb~", TextLogic.htmlFromMarkdown("~a\rb~"))
        assertEquals("~a b~", TextLogic.htmlFromMarkdown("~a b~"))
    }

    @Test
    fun priceLocaleFollowsTheScreenNotTheDevice() {
        val screen = Json.parseToJsonElement("""{"default_locale":"en_US","components_localizations":{"en_US":{}}}""") as JsonObject
        assertEquals("en_US", Localization.forPaywall(screen, "vi_VN"))
        assertEquals("en_US", Localization.forPaywall(screen, "en-GB"))
        val two = Json.parseToJsonElement("""{"default_locale":"en_US","components_localizations":{"en_US":{},"vi_VN":{}}}""") as JsonObject
        assertEquals("vi_VN", Localization.forPaywall(two, "vi"))
        assertEquals("en_US", Localization.forPaywall(JsonObject(emptyMap()), null))
    }
}
