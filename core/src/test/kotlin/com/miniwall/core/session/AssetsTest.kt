package com.miniwall.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun iconKeyIsTheSetAndNameOfTheSvgFile() {
        assertEquals("lucide/x", Assets.iconKey("https://api.iconify.design/lucide/x.svg"))
        assertEquals("lucide/arrow-right", Assets.iconKey("https://api.iconify.design/lucide/arrow-right.svg?color=red"))
        assertEquals("custom/3f2a-9b", Assets.iconKey("https://api.miniwall.app/v1/sdk/icons/custom/3f2a-9b.svg"))
        assertEquals("tabler/home", Assets.iconKey("https://api.iconify.design/tabler/home.svg"))
        assertNull(Assets.iconKey("https://api.iconify.design/lucide/x.png"))
        assertNull(Assets.iconKey("https://api.miniwall.app/v2/sdk/icons/custom/a.svg"))
        assertNull(Assets.iconKey("https://cdn.example.com/a/b/icon.svg"))
        assertNull(Assets.iconKey("not a url"))
    }

    @Test
    fun assetsOfABodyIncludeWhatItsExperimentSideAndAudienceScreensShip() {
        val body = obj(
            """{"assets":{"icons":{"lucide/x":"<svg/>"},"fonts":{"Inter@600":"https://f/a.ttf"}},
              "experiment":{"b":{"assets":{"icons":{"lucide/check":"<svg c/>"},"fonts":{}}}},
              "rules":[{"paywall":{"assets":{"icons":{},"fonts":{"Nunito@700":"https://f/n.ttf"}}}},{"paywall":null}]}""",
        )
        val assets = Assets.of(body)
        assertEquals(setOf("lucide/x", "lucide/check"), assets.icons.keys)
        assertEquals(setOf("Inter@600", "Nunito@700"), assets.fonts.keys)
        assertEquals(DeliveryAssets(), Assets.of(obj("""{"paywall":{}}""")))
        assertEquals(DeliveryAssets(), Assets.of(obj("""{"assets":{"icons":5,"fonts":{"a":3}}}""")))
        assertEquals(DeliveryAssets(), Assets.of(null))
    }

    @Test
    fun fontLinkPrefersTheServerThenTheScreenAndRejectsNonFontFiles() {
        val declaration = obj("""{"ios":"https://x/screen.ttf","android":"https://x/screen-android.otf","web":"https://x/a.woff2"}""")
        val served = DeliveryAssets(fonts = mapOf("Inter@600" to "https://server/inter.ttf"))
        assertEquals("https://server/inter.ttf", Assets.fontLink("Inter@600", declaration, "android", served))
        assertEquals("https://x/screen-android.otf", Assets.fontLink("Inter@600", declaration, "android", DeliveryAssets()))
        assertEquals("https://x/screen.ttf", Assets.fontLink("Inter@600", declaration, "ios", DeliveryAssets()))
        // Not a TTF/OTF, not https, or a system font name: nothing to download.
        assertNull(Assets.fontLink("Inter@600", obj("""{"android":"Helvetica"}"""), "android", DeliveryAssets()))
        assertNull(Assets.fontLink("Inter@600", obj("""{"android":"http://x/a.ttf"}"""), "android", DeliveryAssets()))
        assertEquals("https://x/screen.ttf", Assets.fontLink("A", declaration, "ios", DeliveryAssets(fonts = mapOf("A" to "https://x/a.woff2"))))
        assertNull(Assets.fontLink("A", null, "ios", DeliveryAssets()))
    }
}

class AutoAttributesTest {
    @Test
    fun countryComesFromTheLocaleRegionOnly() {
        assertEquals("VN", AutoAttributes.country("vi-VN"))
        assertEquals("VN", AutoAttributes.country("vi_VN"))
        assertEquals("us".uppercase(), AutoAttributes.country("en_us"))
        assertEquals("CN", AutoAttributes.country("zh-Hans-CN"))
        assertEquals("CN", AutoAttributes.country("zh_CN_#Hans"))
        assertEquals("419", AutoAttributes.country("es-419"))
        assertNull(AutoAttributes.country("vi"))
        assertNull(AutoAttributes.country("en-u-nu-latn"))
        assertNull(AutoAttributes.country(""))
        assertNull(AutoAttributes.country(null))
    }

    @Test
    fun osVersionIsTheMajorNumberAndAppVersionIsKeptAsWritten() {
        assertEquals("17", AutoAttributes.osVersion("17.4.1"))
        assertEquals("14", AutoAttributes.osVersion("14"))
        assertEquals("8", AutoAttributes.osVersion(" 8.1.0 "))
        assertNull(AutoAttributes.osVersion("preview"))
        assertNull(AutoAttributes.osVersion(null))
        assertEquals("2.3.1", AutoAttributes.appVersion(" 2.3.1 "))
        assertNull(AutoAttributes.appVersion("  "))
    }

    @Test
    fun audienceFieldsReadTheAutomaticFacts() {
        val memory = DeviceMemory(MemoryStore())
        val input = TargetingInput(emptyMap(), "vi_VN", "tablet", "ios", osVersion = "17", appVersion = "2.3.1")
        fun field(name: String, i: TargetingInput = input) = Delivery.fieldValue(name, "home", i, memory)
        assertEquals(JsonPrimitive("17"), field("os_version"))
        assertEquals(JsonPrimitive("2.3.1"), field("app_version"))
        assertEquals(JsonPrimitive("VN"), field("country"))
        assertEquals(JsonPrimitive("ES"), field("country", input.copy(country = "ES")))
        assertEquals(JsonPrimitive("tablet"), field("device"))
        assertNull(field("os_version", input.copy(osVersion = null)))
        assertNull(field("country", input.copy(locale = null)))
    }

    @Test
    fun anAudienceCanTestThemLikeTheWebWould() {
        val memory = DeviceMemory(MemoryStore())
        val screen = """{"components_config":{"base":{"stack":{}}},"components_localizations":{}}"""
        val body = Json.parseToJsonElement(
            """{"paywall":$screen,"paywallId":"p1","placement":"home","rules":[
              {"id":"r1","conditions":[{"field":"country","op":"eq","value":"vn"},{"field":"os_version","op":"gte","value":16},{"field":"app_version","op":"starts_with","value":"2."}],
               "paywall":{"paywall":$screen,"paywallId":"p2"}}]}""",
        ).jsonObject
        val vn = TargetingInput(emptyMap(), "vi-VN", "mobile", "ios", osVersion = "17", appVersion = "2.3.1")
        assertEquals("p2", Delivery.decide(body, vn, memory).context?.paywallId)
        assertEquals("p1", Delivery.decide(body, vn.copy(osVersion = "15"), memory).context?.paywallId)
        assertEquals("p1", Delivery.decide(body, vn.copy(appVersion = "1.9"), memory).context?.paywallId)
        assertEquals("p1", Delivery.decide(body, vn.copy(locale = "en-US"), memory).context?.paywallId)
        // A device that cannot say its version never matches (like a missing attribute on the web).
        assertEquals("p1", Delivery.decide(body, vn.copy(osVersion = null), memory).context?.paywallId)
    }
}
