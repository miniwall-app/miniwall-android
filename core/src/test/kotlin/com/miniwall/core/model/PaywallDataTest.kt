package com.miniwall.core.model

import com.miniwall.core.layout.LayoutNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Same cases on every platform SDK. */
class PaywallDataTest {
    @Test
    fun decodesRootStackAndLocalizations() {
        val paywall = PaywallData.decode(
            """
            {"id":"p1","default_locale":"fr_FR",
             "components_config":{"colors":{"primary":"#112233"},"base":{"stack":{"type":"stack","id":"root","components":[],
               "size":{"width":{"type":"fill"},"height":{"type":"fixed","value":120}},
               "dimension":{"type":"horizontal","alignment":"bottom","distribution":"space_between"},
               "spacing":8,"padding":{"top":1,"bottom":2,"leading":3,"trailing":4},"border":{"width":2,"color":{"light":{"type":"hex","value":"#000"}}}}}},
             "components_localizations":{"fr_FR":{"k":"Bonjour"}}}
            """,
        )
        assertEquals("p1", paywall.id)
        assertEquals("fr_FR", paywall.defaultLocale)
        assertEquals("#112233", paywall.colors["primary"])
        assertEquals("Bonjour", paywall.localizations["fr_FR"]?.get("k"))
        val root = paywall.base.stack
        assertEquals(Size.Fill, root.width)
        assertEquals(Size.Fixed(120.0), root.height)
        assertEquals(Dimension.Horizontal(Align.END, Distribution.SPACE_BETWEEN), root.dimension)
        assertEquals(Spacing(1.0, 2.0, 3.0, 4.0), root.padding)
        assertEquals(2.0, root.borderWidth)
    }

    @Test
    fun unknownNodeUsesFallbackAndHiddenNodesAreDropped() {
        val paywall = PaywallData.decode(
            """
            {"components_config":{"base":{"stack":{"type":"stack","id":"root","dimension":{"type":"vertical","alignment":"leading","distribution":"start"},
              "size":{"width":{"type":"fill"},"height":{"type":"fill"}},"components":[
                {"type":"future_widget","id":"x","fallback":{"type":"stack","id":"fb","components":[],"size":{"width":{"type":"fixed","value":10},"height":{"type":"fixed","value":10}}}},
                {"type":"future_widget","id":"y"},
                {"type":"stack","id":"hidden","visible":false,"components":[]}
              ]}}}}
            """,
        )
        val root = LayoutNode.from(paywall.base.stack)!!
        assertEquals(listOf("fb"), root.children.map { it.id })
    }

    @Test
    fun missingRootStackThrows() {
        assertFailsWith<PaywallDecodingException> { PaywallData.decode("""{"components_config":{"base":{}}}""") }
        assertFailsWith<Exception> { PaywallData.decode("not json") }
    }

    @Test
    fun unknownDimensionTypeIsARowLikeTheWeb() {
        val d = Dimension.decode(JsonObject(mapOf("type" to JsonPrimitive("diagonal"), "alignment" to JsonPrimitive("top"))))
        assertEquals(Dimension.Horizontal(Align.START, Distribution.CENTER), d)
    }
}
