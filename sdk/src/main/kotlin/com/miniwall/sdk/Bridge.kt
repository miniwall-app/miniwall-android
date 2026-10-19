package com.miniwall.sdk

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.miniwall.core.logic.Flow
import com.miniwall.core.logic.Period
import com.miniwall.core.logic.ProductInfo
import com.miniwall.core.session.PurchaseResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * Map-based API for the React Native and Flutter wrappers (same protocol as the iOS
 * `MiniwallBridge`). [handler] forwards the screen's requests to JavaScript / Dart:
 * `purchase` {packageId, answers} -> Boolean, `restore` -> Boolean, `action` {actionId, answers}
 * -> Boolean, `answer` {fieldId, value, answers}, `openUrl` {url}, `error` {message}, `autoResult` {placement, result}.
 * `answer`, `openUrl`, `error` and `autoResult` are fire-and-forget: the wrapper sends them and does not wait for a reply.
 * A `purchase`/`restore` reply may be `{error: message}` (the screen's onError fires and nothing is credited); an `action`
 * reply of `{error: message}` keeps the screen open and fires onError.
 */
class MiniwallBridge(private val handler: (method: String, arguments: Map<String, Any?>, reply: (Any?) -> Unit) -> Unit) {
    fun configure(context: Context, args: Map<String, Any?>) {
        Miniwall.configure(
            context,
            args["apiKey"] as? String ?: "",
            Miniwall.Options(
                apiUrl = args["apiUrl"] as? String,
                locale = args["locale"] as? String,
                colorMode = colorMode(args["colorMode"] as? String) ?: ColorMode.SYSTEM,
                analytics = args["analytics"] as? Boolean ?: true,
                attributes = (args["attributes"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
                timeoutMs = (args["timeoutMs"] as? Number)?.toInt() ?: 8000,
                maxFontScale = (args["maxFontScale"] as? Number)?.toDouble() ?: 1.6,
            ),
        )
    }

    fun setAttributes(args: Map<String, Any?>) = Miniwall.setAttributes(args)

    fun reportPurchase(args: Map<String, Any?>) = Miniwall.reportPurchase(args["packageId"] as? String, args["paywallId"] as? String)

    fun reset(args: Map<String, Any?>) = Miniwall.reset((args["placements"] as? List<*>)?.map { it.toString() } ?: emptyList())

    fun preload(args: Map<String, Any?>, done: () -> Unit) {
        Miniwall.scope.launch {
            runCatching { Miniwall.preload((args["placements"] as? List<*>)?.map { it.toString() } ?: emptyList()) }
            done()
        }
    }

    /**
     * Starts placements that show by themselves (app open, delay, events). [args] `options` (same shape
     * as [present]'s) applies to all of them; each outcome goes to the handler as `autoResult`
     * {placement, result}.
     */
    fun startAutoPlacements(args: Map<String, Any?>, activity: Activity? = null) {
        val o = args["options"] as? Map<*, *> ?: emptyMap<String, Any?>()
        Miniwall.startAutoPlacements(
            onResult = { placement, outcome -> handler("autoResult", mapOf("placement" to placement, "result" to result(outcome))) {} },
            options = { options(o) },
            activity = activity,
        )
    }

    /** Stops the placements started by [startAutoPlacements] (the wrapper's engine or context is going away). Runs on the main thread. */
    @Suppress("UNUSED_PARAMETER")
    fun stopAutoPlacements(args: Map<String, Any?> = emptyMap()) {
        if (Looper.myLooper() == Looper.getMainLooper()) Miniwall.stopAutoPlacements()
        else Handler(Looper.getMainLooper()).post { Miniwall.stopAutoPlacements() }
    }

    /** `Miniwall.track(event)`: {event}. */
    fun track(args: Map<String, Any?>) = Miniwall.track(args["event"] as? String ?: "")

    /** `source`: {placement} | {paywallId} | {json}; `options`: see the iOS bridge. */
    fun present(activity: Activity, args: Map<String, Any?>, done: (Map<String, Any?>?, Throwable?) -> Unit) {
        val s = args["source"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val source = when {
            s["placement"] is String -> ScreenSource.Placement(s["placement"] as String)
            s["paywallId"] is String -> ScreenSource.PaywallId(s["paywallId"] as String)
            s["json"] is Map<*, *> -> ScreenSource.Json(toJson(s["json"]) as JsonObject)
            else -> return done(null, MiniwallException("Miniwall: present needs a source (placement, paywallId or json)"))
        }
        val options = options(args["options"] as? Map<*, *> ?: emptyMap<String, Any?>())
        Miniwall.present(activity, source, options) { outcome -> outcome.fold({ done(result(it), null) }, { done(null, it) }) }
    }

    /**
     * Asks JavaScript or Dart and waits. A purchase can take minutes on the store's payment sheet, so the limit is long (10 min):
     * it only frees the screen when the wrapper was reloaded or never answers.
     */
    private suspend fun ask(method: String, args: Map<String, Any?>): Any? = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) {
        suspendCancellableCoroutine<Any?> { continuation ->
            handler(method, args) { reply -> if (continuation.isActive) runCatching { continuation.resume(reply) } }
        }
    }

    private fun failure(reply: Any?): String? = ((reply as? Map<*, *>)?.get("error")) as? String

    private fun options(o: Map<*, *>): PresentOptions {
        val hasPurchase = o["hasPurchase"] as? Boolean ?: true
        val hasRestore = o["hasRestore"] as? Boolean ?: false
        val hasAction = o["hasAction"] as? Boolean ?: false
        val hasOpenUrl = o["hasOpenUrl"] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        return PresentOptions(
            products = (o["products"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> product(v)?.let { k.toString() to it } }?.toMap() ?: emptyMap(),
            variablesPerPackage = (o["variablesPerPackage"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to ((v as? Map<*, *>)?.entries?.associate { (a, b) -> a.toString() to b.toString() } ?: emptyMap()) } ?: emptyMap(),
            customVariables = (o["customVariables"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> v?.let { k.toString() to it } }?.toMap() ?: emptyMap(),
            attributes = (o["attributes"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
            locale = o["locale"] as? String,
            colorMode = colorMode(o["colorMode"] as? String),
            style = style(o["style"] as? Map<*, *>),
            screens = (o["screens"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (toJson(v) as? JsonObject)?.let { k.toString() to it } }?.toMap() ?: emptyMap(),
            onPurchase = if (!hasPurchase) null else ({ request ->
                val reply = ask("purchase", mapOf("packageId" to request.packageId, "answers" to answers(request.answers)))
                failure(reply)?.let { throw MiniwallException(it) }
                reply as? Boolean ?: false
            }),
            onRestore = if (!hasRestore) null else ({
                val reply = ask("restore", emptyMap())
                failure(reply)?.let { throw MiniwallException(it) }
                reply as? Boolean ?: false
            }),
            onAction = if (!hasAction) null else ({ actionId, current ->
                val reply = ask("action", mapOf("actionId" to actionId, "answers" to answers(current)))
                // A handler that failed: tell onError and keep the screen open, rather than closing as if it had worked.
                val message = failure(reply)
                if (message != null) {
                    handler("error", mapOf("message" to message)) {}
                    false
                } else reply as? Boolean ?: true
            }),
            onAnswer = { fieldId, value, answers -> handler("answer", mapOf("fieldId" to fieldId, "value" to value?.let(::answer), "answers" to answers(answers))) {} },
            onOpenUrl = if (!hasOpenUrl) null else ({ url: String -> handler("openUrl", mapOf("url" to url)) {} }),
            onError = { error -> handler("error", mapOf("message" to (error.message ?: error.toString()))) {} },
            // onClose / onSkip are not bridged: the wrapper derives them from the result (`closed` / `skipped`).
            maxContentWidth = (o["maxContentWidth"] as? Number)?.toDouble(),
            closeOnBackdrop = o["closeOnBackdrop"] as? Boolean ?: true,
            size = size(o["size"] as? String),
            position = position(o["position"] as? String),
        )
    }

    companion object {
        fun colorMode(s: String?) = when (s) { "light" -> ColorMode.LIGHT; "dark" -> ColorMode.DARK; "system" -> ColorMode.SYSTEM; else -> null }

        fun period(v: Any?): Period? = when (v) {
            "lifetime" -> Period.Lifetime
            is String -> Period.Unit.entries.firstOrNull { it.key == v }?.let { Period.Every(it) }
            is Map<*, *> -> Period.Unit.entries.firstOrNull { it.key == v["unit"] }?.let { Period.Every(it, (v["count"] as? Number)?.toInt() ?: 1) }
            else -> null
        }

        fun product(v: Any?): ProductInfo? {
            val o = v as? Map<*, *> ?: return null
            val price = (o["price"] as? Number)?.toDouble() ?: return null
            val currency = o["currency"] as? String ?: return null
            val offer = (o["offer"] as? Map<*, *>)?.let { offer ->
                val p = (offer["price"] as? Number)?.toDouble()
                val period = period(offer["period"])
                if (p != null && period != null) ProductInfo.Offer(p, period) else null
            }
            return ProductInfo(price, currency, period(o["period"]), o["title"] as? String, offer)
        }

        fun size(s: String?) = PresentationSize.entries.firstOrNull { it.wire == s }

        fun position(s: String?) = when (s) { "top" -> PresentationPosition.TOP; "bottom" -> PresentationPosition.BOTTOM; else -> null }

        fun style(o: Map<*, *>?): PresentationStyle? = when (o?.get("type")) {
            "fullscreen" -> PresentationStyle.Fullscreen
            "modal" -> PresentationStyle.Modal
            "sheet" -> PresentationStyle.Sheet
            "banner" -> PresentationStyle.Banner(top = o["position"] != "bottom")
            "toast" -> PresentationStyle.Toast((o["seconds"] as? Number)?.toDouble() ?: 6.0, top = o["position"] == "top")
            else -> null
        }

        fun answer(a: Flow.Answer): Any = when (a) {
            is Flow.Answer.One -> a.value
            is Flow.Answer.Many -> a.values
        }

        fun answers(a: Map<String, Flow.Answer>): Map<String, Any> = a.mapValues { answer(it.value) }

        fun result(r: PurchaseResult): Map<String, Any?> = when (r) {
            is PurchaseResult.Purchased -> mapOf("outcome" to "purchased", "packageId" to r.packageId, "answers" to answers(r.answers))
            is PurchaseResult.Restored -> mapOf("outcome" to "restored", "answers" to answers(r.answers))
            is PurchaseResult.Closed -> mapOf("outcome" to "closed", "answers" to answers(r.answers))
            is PurchaseResult.ActionTaken -> mapOf("outcome" to "action", "actionId" to r.actionId, "answers" to answers(r.answers))
            is PurchaseResult.Skipped -> mapOf("outcome" to "skipped", "reason" to r.reason.wire)
        }

        /** Plain maps/lists from React Native or Flutter -> JSON. */
        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
            is List<*> -> JsonArray(v.map(::toJson))
            is Boolean -> JsonPrimitive(v)
            // React Native turns every number into a Double: whole ones go back to integers (a version is not "3.0").
            is Number -> if (v is Double && v == Math.floor(v) && Math.abs(v) < 9e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}
