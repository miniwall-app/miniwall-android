package com.miniwall.core.session

import com.miniwall.core.logic.Targeting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/** Persistent key-value storage (UserDefaults, SharedPreferences). Values are JSON text. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Why a placement showed nothing (Web SDK src/types.ts SkipReason). */
enum class SkipReason(val wire: String) { FREQUENCY_CAP("frequency_cap"), AUDIENCE("audience"), HOLDOUT("holdout") }

/** Which published screen an event belongs to (PaywallContext on the web). */
data class EventContext(
    val paywallId: String,
    val version: Int? = null,
    val placement: String? = null,
    val experimentId: String? = null,
    val variant: String? = null,
)

/** What a placement or screen id resolved to. */
data class Resolved(
    val paywall: JsonObject?,
    val context: EventContext?,
    val skip: SkipReason? = null,
    /** The placement's display settings from the dashboard. */
    val display: JsonObject? = null,
)

/** Targeting input of one call: app attributes (never sent), locale, device. */
data class TargetingInput(
    val attributes: Map<String, JsonPrimitive>,
    val locale: String?,
    /** `mobile` or `tablet`. */
    val device: String,
    /** `ios` or `android`, readable as the `platform` field. */
    val platform: String,
    val ignoreCap: Boolean = false,
    /** Major OS version (`17`), see [AutoAttributes.osVersion]. */
    val osVersion: String? = null,
    /** The app's version name (`2.3.1`). */
    val appVersion: String? = null,
    /** Region, upper case. Null: taken from [locale] (never from the IP address). */
    val country: String? = null,
)

/**
 * Per-device memory: visitor id, how often each placement showed (frequency cap,
 * `seen_count`), first-seen day, last exposure (purchase attribution). Same keys and
 * rules as the web's localStorage (Web SDK src/targeting.ts, track.ts).
 */
class DeviceMemory(private val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val sessionShown = HashMap<String, Int>()

    val visitorId: String
        get() = store.get(VISITOR_KEY) ?: UUID.randomUUID().toString().also { store.set(VISITOR_KEY, it) }

    private fun shownLog(placement: String): Pair<Long, List<Long>> {
        val raw = store.get(SHOWN_PREFIX + placement) ?: return 0L to emptyList()
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return 0L to emptyList()
        val total = (o["total"] as? JsonPrimitive)?.longOrNull ?: 0L
        val times = (o["times"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
        return total to times
    }

    fun recordShown(placement: String) {
        val (total, times) = shownLog(placement)
        val next = (times + clock()).takeLast(KEEP_TIMES)
        store.set(SHOWN_PREFIX + placement, JsonObject(mapOf("total" to JsonPrimitive(total + 1), "times" to JsonArray(next.map(::JsonPrimitive)))).toString())
        sessionShown[placement] = (sessionShown[placement] ?: 0) + 1
    }

    fun isCapped(placement: String, cap: JsonObject?): Boolean {
        cap ?: return false
        val max = (cap["max"] as? JsonPrimitive)?.doubleOrNull ?: return false
        val (total, times) = shownLog(placement)
        val now = clock()
        val count = when ((cap["per"] as? JsonPrimitive)?.content) {
            "session" -> sessionShown[placement]?.toLong() ?: 0L
            "ever" -> total
            "day" -> times.count { now - it < DAY_MS }.toLong()
            else -> times.count { now - it < 7 * DAY_MS }.toLong()
        }
        return count >= max
    }

    fun seenCount(placement: String) = shownLog(placement).first

    fun daysSinceFirstSeen(): Long {
        val now = clock()
        val first = store.get(FIRST_SEEN_KEY)?.toLongOrNull() ?: now.also { store.set(FIRST_SEEN_KEY, it.toString()) }
        return (now - first) / DAY_MS
    }

    fun rememberExposure(context: EventContext, heldOut: Boolean) {
        val o = buildMap<String, JsonElement> {
            put("paywallId", JsonPrimitive(context.paywallId))
            context.version?.let { put("version", JsonPrimitive(it)) }
            put("placement", JsonPrimitive(context.placement ?: ""))
            put("heldOut", JsonPrimitive(heldOut))
            put("at", JsonPrimitive(clock()))
        }
        store.set(EXPOSURE_KEY, JsonObject(o).toString())
    }

    /** The last placement met within 30 days: (context, heldOut). */
    fun lastExposure(): Pair<EventContext, Boolean>? {
        val o = store.get(EXPOSURE_KEY)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return null
        val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: return null
        if (clock() - at >= 30 * DAY_MS) return null
        val ctx = EventContext(
            paywallId = (o["paywallId"] as? JsonPrimitive)?.content ?: return null,
            version = (o["version"] as? JsonPrimitive)?.longOrNull?.toInt(),
            placement = (o["placement"] as? JsonPrimitive)?.content,
        )
        return ctx to ((o["heldOut"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun rememberPendingPurchase(context: EventContext, packageId: String) =
        store.set(PENDING_KEY, Events.contextJson(context, mapOf("packageId" to JsonPrimitive(packageId))).toString())

    fun pendingPurchase(): JsonObject? = store.get(PENDING_KEY)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
    fun forgetPendingPurchase() = store.set(PENDING_KEY, null)

    /** Forgets everything (`Miniwall.reset()`): a new visitor id, counters and exposures. */
    fun reset(keys: Collection<String>) {
        (keys + listOf(VISITOR_KEY, FIRST_SEEN_KEY, EXPOSURE_KEY, PENDING_KEY, OPENS_KEY)).forEach { store.set(it, null) }
        sessionShown.clear()
    }

    companion object {
        const val VISITOR_KEY = "uikit:vid"
        const val SHOWN_PREFIX = "uikit:shown:"
        const val FIRST_SEEN_KEY = "uikit:first-seen"
        const val OPENS_KEY = "uikit:app-opens"
        const val EXPOSURE_KEY = "uikit:last-exposure"
        const val PENDING_KEY = "uikit:pending-purchase"
        const val KEEP_TIMES = 100
        const val DAY_MS = 86_400_000L
    }
}

/** A delivery API body turned into the screen to show (Web SDK src/load.ts). */
object Delivery {
    private fun contextOf(body: JsonObject): EventContext? {
        val id = (body["paywallId"] as? JsonPrimitive)?.content ?: return null
        return EventContext(
            paywallId = id,
            version = (body["version"] as? JsonPrimitive)?.longOrNull?.toInt(),
            placement = (body["placement"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    /** The paywall JSON of a body (`paywall`, possibly wrapped in `paywallData`); null when it is not one. */
    fun asPaywall(value: JsonElement?): JsonObject? {
        val o = value as? JsonObject ?: return null
        val unwrapped = (o["paywallData"] as? JsonObject) ?: o
        val base = (unwrapped["components_config"] as? JsonObject)?.get("base") as? JsonObject ?: return null
        if (base["stack"] !is JsonObject) return null
        return if (unwrapped["components_localizations"] is JsonObject) unwrapped
        else JsonObject(unwrapped + ("components_localizations" to JsonObject(emptyMap())))
    }

    /** A/B side for this visitor: B when its bucket is below `shareB`. */
    fun pickSide(body: JsonObject, visitorId: String): Resolved {
        val test = body["experiment"] as? JsonObject
        val b = test?.get("b") as? JsonObject
        if (test == null || b == null) return Resolved(asPaywall(body["paywall"]), contextOf(body))
        val id = (test["id"] as? JsonPrimitive)?.content ?: ""
        val shareB = (test["shareB"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val variant = if (Targeting.bucketOf(id, visitorId) < shareB) "b" else "a"
        val chosen = if (variant == "b") b else body
        return Resolved(asPaywall(chosen["paywall"]), contextOf(chosen)?.copy(experimentId = id, variant = variant))
    }

    /** Targeting in order: frequency cap, first matching audience, then holdout. */
    fun decide(body: JsonObject, input: TargetingInput, memory: DeviceMemory): Resolved {
        val placement = (body["placement"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val display = body["display"] as? JsonObject
        if (placement.isNotEmpty() && !input.ignoreCap && memory.isCapped(placement, body["frequency"] as? JsonObject)) {
            return Resolved(null, null, SkipReason.FREQUENCY_CAP)
        }
        val rules = (body["rules"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val rule = if (placement.isEmpty()) null else rules.firstOrNull { r ->
            ((r["conditions"] as? JsonArray) ?: JsonArray(emptyList())).all { c ->
                val o = c as? JsonObject ?: return@all false
                val field = (o["field"] as? JsonPrimitive)?.content ?: ""
                Targeting.matchesField(field, (o["op"] as? JsonPrimitive)?.content ?: "", o["value"] as? JsonPrimitive, fieldValue(field, placement, input, memory))
            }
        }
        if (rule != null && rule["paywall"].let { it == null || it is JsonNull }) return Resolved(null, null, SkipReason.AUDIENCE)
        val chosen = (rule?.get("paywall") as? JsonObject)?.let { Resolved(asPaywall(it["paywall"]), contextOf(it)) } ?: pickSide(body, memory.visitorId)
        val holdout = body["holdout"] as? JsonObject
        val share = (holdout?.get("share") as? JsonPrimitive)?.doubleOrNull ?: 0.0
        if (holdout != null && share > 0 && Targeting.bucketOf("holdout:${(holdout["id"] as? JsonPrimitive)?.content}", memory.visitorId) < share) {
            return Resolved(null, chosen.context, SkipReason.HOLDOUT)
        }
        return chosen.copy(display = display)
    }

    /** Built-in audience fields on mobile; `user.<key>` reads the app's attributes. */
    fun fieldValue(field: String, placement: String, input: TargetingInput, memory: DeviceMemory): JsonPrimitive? = when {
        field.startsWith("user.") -> input.attributes[field.removePrefix("user.")]
        field == "locale" -> input.locale?.let(::JsonPrimitive)
        field == "device" -> JsonPrimitive(input.device)
        field == "platform" -> JsonPrimitive(input.platform)
        field == "os_version" -> input.osVersion?.let(::JsonPrimitive)
        field == "app_version" -> input.appVersion?.let(::JsonPrimitive)
        field == "country" -> (input.country ?: AutoAttributes.country(input.locale))?.let(::JsonPrimitive)
        field == "seen_count" -> JsonPrimitive(memory.seenCount(placement))
        field == "days_since_first_seen" -> JsonPrimitive(memory.daysSinceFirstSeen())
        else -> null
    }
}

/** Who sends an event: sent as `platform` (`ios` | `android`), `sdkVersion` and `appVersion` (none of it personal). */
data class EventClient(val platform: String, val sdkVersion: String, val appVersion: String? = null)

/** Analytics events as the API takes them (`POST /v1/sdk/events`, Web SDK src/track.ts). */
object Events {
    fun contextJson(context: EventContext, extra: Map<String, JsonElement> = emptyMap()): JsonObject = JsonObject(
        buildMap {
            put("paywallId", JsonPrimitive(context.paywallId))
            context.version?.let { put("version", JsonPrimitive(it)) }
            context.placement?.let { put("placement", JsonPrimitive(it)) }
            context.experimentId?.let { put("experimentId", JsonPrimitive(it)) }
            context.variant?.let { put("variant", JsonPrimitive(it)) }
            putAll(extra)
        },
    )

    /** `X-Miniwall-App` (`ios:<bundleId>` / `android:<applicationId>`), checked against the project's Allowed apps; null when the id is unknown. */
    fun appHeader(platform: String, appId: String?): String? = appId?.trim()?.takeIf { it.isNotEmpty() }?.let { "$platform:$it" }

    fun event(type: String, context: EventContext, visitorId: String, occurredAtIso: String, extra: Map<String, String?> = emptyMap(), client: EventClient? = null): JsonObject =
        contextJson(
            context,
            buildMap {
                put("type", JsonPrimitive(type))
                put("visitorId", JsonPrimitive(visitorId))
                put("occurredAt", JsonPrimitive(occurredAtIso))
                extra.forEach { (k, v) -> if (v != null) put(k, JsonPrimitive(v)) }
                if (client != null) {
                    put("platform", JsonPrimitive(client.platform))
                    put("sdkVersion", JsonPrimitive(client.sdkVersion))
                    client.appVersion?.let { put("appVersion", JsonPrimitive(it)) }
                }
            },
        )

    /** Request bodies of at most 50 events each. */
    fun batches(events: List<JsonObject>): List<String> =
        events.chunked(MAX_BATCH).map { JsonObject(mapOf("events" to JsonArray(it))).toString() }

    const val MAX_BATCH = 50
}
