package com.miniwall.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/** What a package offers, for `intro_offer` / `promo_offer` overrides and conditions. */
data class PackageInfo(val hasIntroOffer: Boolean = false, val hasTrial: Boolean = false, val hasPromoOffer: Boolean = false)

/** Overrides (`overrides[]`) and visibility conditions, ported from the web renderer */
object Overrides {
    /** State flags of a node that select an override (`selected`, `intro_offer`, ...). */
    data class State(
        val selected: Boolean = false,
        val introOffer: Boolean = false,
        val promoOffer: Boolean = false,
        val focus: Boolean = false,
        val error: Boolean = false,
    )

    private fun JsonObject.conditionTypes(): List<String?> =
        (this["conditions"] as? JsonArray)?.map { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content } ?: emptyList()

    /**
     * The node's properties with the matching overrides merged in, in the web's order:
     * the first override with a `selected` condition (when selected), then `intro_offer`,
     * then `promo_offer` (text only reads these two), `focus`, `error`. Later wins.
     */
    fun apply(node: JsonObject, state: State): JsonObject {
        val overrides = node["overrides"] as? JsonArray ?: return node
        val merged = node.toMutableMap()
        fun mergeFirst(type: String, on: Boolean) {
            if (!on) return
            val o = overrides.firstOrNull { (it as? JsonObject)?.conditionTypes()?.contains(type) == true } as? JsonObject ?: return
            (o["properties"] as? JsonObject)?.forEach { (k, v) -> merged[k] = v }
        }
        mergeFirst("selected", state.selected)
        mergeFirst("intro_offer", state.introOffer)
        mergeFirst("promo_offer", state.promoOffer)
        mergeFirst("focus", state.focus)
        mergeFirst("error", state.error)
        return JsonObject(merged)
    }

    private val VISIBILITY_TYPES = setOf("selected_package_condition", "intro_offer_condition", "promo_offer_condition", "variable_condition")
    private val ORDER = listOf("intro_offer_condition", "promo_offer_condition", "selected_package_condition", "variable_condition")

    /**
     * Whether a node shows: the first override whose conditions are all visibility conditions
     * decides (all must hold); without one, `visible` (default true).
     */
    fun isVisible(
        overrides: JsonElement?,
        baseVisible: Boolean?,
        selectedPackageId: String?,
        packageInfo: PackageInfo?,
        variables: Map<String, String>,
    ): Boolean {
        val list = overrides as? JsonArray
        val rule = list?.firstOrNull { o ->
            val conditions = (o as? JsonObject)?.get("conditions") as? JsonArray ?: return@firstOrNull false
            conditions.all { (((it as? JsonObject)?.get("type")) as? JsonPrimitive)?.content in VISIBILITY_TYPES }
        } as? JsonObject ?: return baseVisible ?: true
        val conditions = (rule["conditions"] as JsonArray).mapNotNull { it as? JsonObject }
            .sortedBy { ORDER.indexOf((it["type"] as? JsonPrimitive)?.content) }
        return conditions.all { c ->
            val op = (c["operator"] as? JsonPrimitive)?.content
            when ((c["type"] as? JsonPrimitive)?.content) {
                "intro_offer_condition" -> {
                    val has = packageInfo?.hasIntroOffer == true || packageInfo?.hasTrial == true
                    val value = (c["value"] as? JsonPrimitive)?.booleanOrNull
                    when (op) { "=" -> has == value; "!=" -> has != value; else -> false }
                }
                "promo_offer_condition" -> {
                    val has = packageInfo?.hasPromoOffer == true
                    val value = (c["value"] as? JsonPrimitive)?.booleanOrNull
                    when (op) { "=" -> has == value; "!=" -> has != value; else -> false }
                }
                "selected_package_condition" -> {
                    val packages = (c["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    val selected = selectedPackageId ?: ""
                    when (op) { "in" -> selected in packages; "not in" -> selected !in packages; else -> false }
                }
                "variable_condition" -> {
                    val name = (c["variable"] as? JsonPrimitive)?.content ?: ""
                    val current = variables[name]
                    val expected = jsString(((c["value"] as? JsonObject)?.get("value")))
                    when (op) { "=" -> current == expected; "!=" -> current != expected; else -> false }
                }
                else -> false
            }
        }
    }
}

/** `String(value)` as JavaScript prints it (numbers without a trailing `.0`). */
internal fun jsString(e: JsonElement?): String = when {
    e == null || e is JsonNull -> if (e == null) "undefined" else "null"
    e is JsonPrimitive && e.isString -> e.content
    e is JsonPrimitive && e.booleanOrNull != null -> e.content
    e is JsonPrimitive -> jsNumber(e.doubleOrNull ?: Double.NaN)
    else -> e.toString()
}

internal fun jsNumber(d: Double): String = when {
    d.isNaN() -> "NaN"
    d == floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 9e15 -> d.toLong().toString()
    d == floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e21 -> java.math.BigDecimal(d).toPlainString()
    else -> d.toString()
}

/** Placement audiences, A/B buckets and screen flows (sdks/web/src/targeting.ts, load.ts, flow.ts). */
object Targeting {
    /**
     * A value an audience condition compares: a string, number, boolean or null.
     * Kept as JsonPrimitive so `"5"` and `5` behave as on the web.
     */
    fun matches(op: String, expected: JsonPrimitive?, actual: JsonPrimitive?): Boolean {
        val has = actual != null && actual !is JsonNull && !(actual.isString && actual.content.isEmpty())
        fun text(v: JsonPrimitive?) = jsString(v).lowercase()
        return when (op) {
            "exists" -> has
            "not_exists" -> !has
            "eq" -> has && text(actual) == text(expected)
            "neq" -> !has || text(actual) != text(expected)
            "contains" -> has && text(actual).contains(text(expected))
            "starts_with" -> has && text(actual).startsWith(text(expected))
            else -> {
                val a = numeric(actual)
                val b = numeric(expected)
                if (a.isNaN() || b.isNaN()) return false
                when (op) { "gt" -> a > b; "gte" -> a >= b; "lt" -> a < b; else -> a <= b }
            }
        }
    }

    /**
     * Audience condition on a named field. `app_version` (a native-only fact) compares ordering ops
     * (`gt`/`gte`/`lt`/`lte`) as dotted version numbers; every other op and field is [matches].
     */
    fun matchesField(field: String, op: String, expected: JsonPrimitive?, actual: JsonPrimitive?): Boolean {
        if (field != "app_version" || op !in setOf("gt", "gte", "lt", "lte")) return matches(op, expected, actual)
        if (actual == null || actual is JsonNull || expected == null || expected is JsonNull) return false
        val c = compareVersions(jsString(actual), jsString(expected)) ?: return false
        return when (op) { "gt" -> c > 0; "gte" -> c >= 0; "lt" -> c < 0; else -> c <= 0 }
    }

    /**
     * Compares `1.2.3` style versions segment by segment as numbers; a missing segment is 0
     * (`2` = `2.0.0`). Null when either side has a segment that is not plain digits.
     */
    fun compareVersions(a: String, b: String): Int? {
        fun parts(v: String): List<java.math.BigInteger>? =
            v.trim().split('.').map { seg -> if (seg.isNotEmpty() && seg.all { it in '0'..'9' }) java.math.BigInteger(seg) else return null }
        val x = parts(a) ?: return null
        val y = parts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(y.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    private val DECIMAL = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")

    /**
     * JavaScript's `Number(text)` for a trimmed, non-empty string: decimals, `Infinity`, and `0x`/`0o`/`0b` integers.
     * Kotlin's own parser also takes "5f", "NaN" and hex floats, which JavaScript turns into NaN.
     */
    internal fun jsParse(t: String): Double {
        if (DECIMAL.matches(t)) return t.toDoubleOrNull() ?: Double.NaN
        when (t) { "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY; "-Infinity" -> return Double.NEGATIVE_INFINITY }
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> return Double.NaN }
            return t.substring(2).toBigIntegerOrNull(radix)?.toDouble() ?: Double.NaN
        }
        return Double.NaN
    }

    private fun numeric(v: JsonPrimitive?): Double = when {
        v == null || v is JsonNull -> Double.NaN
        v.isString -> if (v.content.isBlank()) (if (v.content.isEmpty()) Double.NaN else 0.0) else jsParse(v.content.trim())
        v.booleanOrNull != null -> Double.NaN
        else -> v.doubleOrNull ?: Double.NaN
    }

    /** FNV-1a over UTF-16 code units, like the web: the same visitor lands on the same side everywhere. */
    fun bucketOf(experimentId: String, visitorId: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in "$experimentId:$visitorId") {
            h = h xor c.code
            h *= 0x01000193
        }
        return ((h.toLong() and 0xffffffffL) % 100).toInt()
    }
}

/** Screen links and answers (sdks/web/src/flow.ts). */
object Flow {
    sealed interface Link {
        data object Back : Link
        data class GoTo(val screenId: String) : Link
    }

    fun screenLink(actionId: String): Link? = when {
        actionId == "screen:back" -> Link.Back
        actionId.startsWith("screen:") && actionId.length > "screen:".length -> Link.GoTo(actionId.removePrefix("screen:"))
        else -> null
    }

    enum class FieldKind { SINGLE, MULTIPLE, TEXT }

    /** An answer: one option id / typed text, or several option ids (multiple choice). */
    sealed interface Answer {
        data class One(val value: String) : Answer
        data class Many(val values: List<String>) : Answer
    }

    fun applyAnswer(answers: Map<String, Answer>, kind: FieldKind?, fieldId: String, value: String): Map<String, Answer> {
        val next = answers.toMutableMap()
        if (kind == FieldKind.MULTIPLE) {
            val current = (next[fieldId] as? Answer.Many)?.values ?: emptyList()
            val toggled = if (value in current) current.filter { it != value } else current + value
            if (toggled.isNotEmpty()) next[fieldId] = Answer.Many(toggled) else next.remove(fieldId)
            return next
        }
        if (value.isEmpty()) next.remove(fieldId) else next[fieldId] = Answer.One(value)
        return next
    }

    /** Every node object in a JSON tree, however nested. */
    fun walk(e: JsonElement?, visit: (JsonObject) -> Unit) {
        when (e) {
            is JsonArray -> e.forEach { walk(it, visit) }
            is JsonObject -> {
                if ((e["type"] as? JsonPrimitive)?.isString == true) visit(e)
                e.values.forEach { walk(it, visit) }
            }
            else -> {}
        }
    }

    fun questionFields(componentsConfig: JsonElement?): Map<String, FieldKind> {
        val out = LinkedHashMap<String, FieldKind>()
        walk(componentsConfig) { node ->
            val kind = when ((node["type"] as? JsonPrimitive)?.content) {
                "input_single_choice" -> FieldKind.SINGLE
                "input_multiple_choice" -> FieldKind.MULTIPLE
                "input_text" -> FieldKind.TEXT
                else -> null
            }
            val field = (node["field_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (kind != null && !field.isNullOrEmpty()) out[field] = kind
        }
        return out
    }

    fun linkedScreens(componentsConfig: JsonElement?): List<String> {
        val ids = LinkedHashSet<String>()
        walk(componentsConfig) { node ->
            val onPress = ((node["triggers"] as? JsonObject)?.get("on_press") as? JsonPrimitive)?.content ?: ""
            (screenLink(onPress) as? Link.GoTo)?.let { ids += it.screenId }
        }
        return ids.toList()
    }

    /** Chosen options for analytics; typed text never leaves the app. */
    fun chosenOptions(answers: Map<String, Answer>, fields: Map<String, FieldKind>): List<Pair<String, String>> =
        // By field id (Swift's objects have no order): both platforms report answers in the same order.
        fields.entries.sortedBy { it.key }.flatMap { (fieldId, kind) ->
            if (kind == FieldKind.TEXT) return@flatMap emptyList()
            when (val a = answers[fieldId]) {
                is Answer.One -> listOf(fieldId to a.value)
                is Answer.Many -> a.values.map { fieldId to it }
                null -> emptyList()
            }
        }
}

/** Countdown variables (the web renderer). */
object Countdown {
    fun values(targetEpochMs: Long, nowEpochMs: Long): Map<String, String> {
        val diff = targetEpochMs - nowEpochMs
        fun put(m: MutableMap<String, String>, unit: String, v: Long) {
            m["count_${unit}_with_zero"] = if (v < 10) "0$v" else "$v"
            m["count_${unit}_without_zero"] = "$v"
        }
        val out = LinkedHashMap<String, String>()
        if (diff <= 0) {
            listOf("days", "hours", "minutes", "seconds").forEach { put(out, it, 0) }
            return out
        }
        val totalSeconds = diff / 1000
        val totalMinutes = totalSeconds / 60
        val totalHours = totalMinutes / 60
        put(out, "days", totalHours / 24)
        put(out, "hours", totalHours % 24)
        put(out, "minutes", totalMinutes % 60)
        put(out, "seconds", totalSeconds % 60)
        return out
    }

    fun hasEnded(targetEpochMs: Long, nowEpochMs: Long) = targetEpochMs <= nowEpochMs
}
