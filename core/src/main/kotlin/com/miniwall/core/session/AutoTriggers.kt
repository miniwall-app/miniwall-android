package com.miniwall.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One entry of `GET /v1/sdk/auto?platform=...` (D-120): a placement and when it shows by itself. */
data class AutoTrigger(
    val placement: String,
    /** `app_open`, `event` or `delay`; others are not for apps and are dropped on parse. */
    val type: String,
    val seconds: Int? = null,
    val event: String? = null,
    /** `app_open`: every Nth open. */
    val every: Int = 1,
    val priority: Int = 0,
)

/** What an app opening asks for: placements to try now (in order) and the ones to try after a wait. */
data class AppOpened(val now: List<String>, val delayed: List<Delayed>) {
    data class Delayed(val placement: String, val seconds: Int)
}

/**
 * Decides which automatic placements an app's moments fire. The platform layer owns the
 * presenting (one screen at a time, the first placement that does not skip wins, like the web
 * SDK's queue); this holds the rules, so they are tested once.
 */
class AutoTriggers(private val store: KeyValueStore) {
    var triggers: List<AutoTrigger> = emptyList()
        private set

    /** Replaces the list from an `/auto` response; highest priority first, otherwise the server's order. */
    fun load(body: JsonObject) {
        triggers = ((body["placements"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { parse(it as? JsonObject) }.sortedByDescending { it.priority }
    }

    /** The app came to the foreground: counts the open and returns what fires. */
    fun appOpened(): AppOpened {
        val opens = (store.get(DeviceMemory.OPENS_KEY)?.toLongOrNull() ?: 0L) + 1
        store.set(DeviceMemory.OPENS_KEY, opens.toString())
        val now = triggers.filter { it.type == "app_open" && opens % it.every == 0L }.map { it.placement }
        val delayed = triggers.filter { it.type == "delay" }.map { AppOpened.Delayed(it.placement, it.seconds ?: 0) }
        return AppOpened(now, delayed)
    }

    /** The app reported [name] (`Miniwall.track`): the placements waiting for that event. */
    fun event(name: String): List<String> = triggers.filter { it.type == "event" && it.event == name }.map { it.placement }

    private fun parse(o: JsonObject?): AutoTrigger? {
        o ?: return null
        val key = (o["placement"] as? JsonPrimitive)?.content ?: return null
        val t = o["trigger"] as? JsonObject ?: return null
        fun int(name: String) = (t[name] as? JsonPrimitive)?.doubleOrNull?.toInt()
        val type = (t["type"] as? JsonPrimitive)?.content ?: return null
        return when (type) {
            "app_open" -> AutoTrigger(key, type, every = (int("every") ?: 1).coerceAtLeast(1), priority = int("priority") ?: 0)
            "event" -> AutoTrigger(key, type, event = (t["event"] as? JsonPrimitive)?.content ?: return null, priority = int("priority") ?: 0)
            "delay" -> AutoTrigger(key, type, seconds = int("seconds") ?: return null, priority = int("priority") ?: 0)
            else -> null
        }
    }
}
