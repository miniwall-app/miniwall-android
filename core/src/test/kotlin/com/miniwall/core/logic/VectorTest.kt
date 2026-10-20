package com.miniwall.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every case in the spec repo vectors must give the web's exact output.
 * Same suite as the iOS SDK's VectorTests.
 */
class VectorTest {
    private val dir = File(com.miniwall.core.Spec.dir.path, "vectors")

    private fun cases(name: String): List<JsonObject> =
        Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

    private fun check(name: String, actual: (JsonObject) -> JsonElement) {
        val failures = cases(name).mapIndexedNotNull { i, c ->
            val got = actual(c["input"]!!.jsonObject)
            val expected = c["expected"]!!
            if (normalize(got) == normalize(expected)) null else "#$i ${c["input"]}\n  expected $expected\n  got      $got"
        }
        if (failures.isNotEmpty()) fail("$name: ${failures.size} mismatches\n" + failures.take(15).joinToString("\n"))
    }

    /** Numbers compare by value (1 and 1.0 are the same JSON number). */
    private fun normalize(e: JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { normalize(it.value) }
        is JsonArray -> e.map(::normalize)
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.double
    }

    private fun str(s: String?) = s?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun stringMap(e: JsonElement?) = (e as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()

    @Test fun replaceVariables() = check("replace-variables") { i -> str(TextLogic.replaceVariables(i.s("template"), stringMap(i["variables"]))) }

    @Test fun markdownHtml() = check("markdown-html") { i -> str(TextLogic.htmlFromMarkdown(i.s("text"))) }

    @Test fun openableUrl() = check("openable-url") { i -> JsonPrimitive(TextLogic.isOpenableUrl(i.s("url")!!)) }

    @Test fun fontName() = check("font-name") { i ->
        TextLogic.parseFontName(i.s("name")!!)?.let { JsonObject(mapOf("family" to JsonPrimitive(it.first), "weight" to JsonPrimitive(it.second))) } ?: JsonNull
    }

    @Test fun visibilityConditions() = check("visibility-conditions") { i ->
        val ctx = i["context"]!!.jsonObject
        val info = (ctx["packageInfo"] as? JsonObject)?.let {
            PackageInfo(
                hasIntroOffer = (it["hasIntroOffer"] as? JsonPrimitive)?.booleanOrNull == true,
                hasTrial = (it["hasTrial"] as? JsonPrimitive)?.booleanOrNull == true,
                hasPromoOffer = (it["hasPromoOffer"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        JsonPrimitive(
            Overrides.isVisible(
                i["overrides"],
                (i["baseVisible"] as? JsonPrimitive)?.booleanOrNull,
                ctx.s("selectedPackageId"),
                info,
                stringMap(ctx["variables"]),
            ),
        )
    }

    @Test fun targetingMatch() = check("targeting-match") { i ->
        val c = i["condition"]!!.jsonObject
        JsonPrimitive(Targeting.matches(c.s("op")!!, c["value"] as? JsonPrimitive, i["actual"] as? JsonPrimitive))
    }

    @Test fun abBucket() = check("ab-bucket") { i -> JsonPrimitive(Targeting.bucketOf(i.s("experimentId")!!, i.s("visitorId")!!)) }

    @Test fun screenLink() = check("screen-link") { i ->
        when (val link = Flow.screenLink(i.s("actionId")!!)) {
            null -> JsonNull
            Flow.Link.Back -> JsonObject(mapOf("kind" to JsonPrimitive("back")))
            is Flow.Link.GoTo -> JsonObject(mapOf("kind" to JsonPrimitive("goto"), "id" to JsonPrimitive(link.screenId)))
        }
    }

    @Test fun applyAnswer() = check("apply-answer") { i ->
        val answers = (i["answers"] as JsonObject).mapValues { (_, v) ->
            if (v is JsonArray) Flow.Answer.Many(v.map { it.jsonPrimitive.content }) else Flow.Answer.One(v.jsonPrimitive.content)
        }
        val kind = when (i.s("kind")) { "single" -> Flow.FieldKind.SINGLE; "multiple" -> Flow.FieldKind.MULTIPLE; "text" -> Flow.FieldKind.TEXT; else -> null }
        val result = Flow.applyAnswer(answers, kind, i.s("fieldId")!!, i.s("value")!!)
        JsonObject(result.mapValues { (_, a) -> when (a) { is Flow.Answer.One -> JsonPrimitive(a.value); is Flow.Answer.Many -> JsonArray(a.values.map(::JsonPrimitive)) } })
    }

    @Test fun countdown() = check("countdown") { i ->
        val now = Instant.parse(i.s("now")!!).toEpochMilli()
        val target = OffsetDateTime.parse(i.s("target")!!).toInstant().toEpochMilli()
        JsonObject(Countdown.values(target, now).mapValues { JsonPrimitive(it.value) })
    }

    @Test fun productVariables() = check("product-variables") { i ->
        val products = i["products"]!!.jsonObject.mapValues { (_, p) -> product(p.jsonObject) }
        val result = Pricing.productVariables(products, i.s("locale"))
        JsonObject(result.mapValues { (_, vars) -> JsonObject(vars.mapValues { JsonPrimitive(it.value) }) })
    }

    private fun period(e: JsonElement?): Period? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.content == "lifetime" -> Period.Lifetime
        e is JsonPrimitive -> Period.Every(Period.Unit.valueOf(e.content.uppercase()))
        else -> Period.Every(Period.Unit.valueOf(e.jsonObject.s("unit")!!.uppercase()), e.jsonObject["count"]!!.jsonPrimitive.int)
    }

    private fun product(o: JsonObject) = ProductInfo(
        price = o["price"]!!.jsonPrimitive.double,
        currency = o.s("currency")!!,
        period = period(o["period"]),
        title = o.s("title"),
        offer = (o["offer"] as? JsonObject)?.let { ProductInfo.Offer(it["price"]!!.jsonPrimitive.double, period(it["period"])!!) },
    )
}
