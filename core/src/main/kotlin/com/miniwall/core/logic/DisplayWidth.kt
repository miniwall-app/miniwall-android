package com.miniwall.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Widest a presented screen gets on wide screens (tablets, landscape), in dp. Same numbers as the Web SDK's
 * `WIDTHS` (Web SDK src/present.ts). On a phone the screen is narrower than any of them, so nothing changes there.
 * [kind]: `modal`, `sheet` (the web's slide_in), `toast`, `fullscreen` or `banner`. [size]: `small`, `medium`, `large`.
 * An explicit [maxContentWidth] wins over [size]; no size and no width means no limit (null).
 */
object DisplayWidth {
    fun cap(kind: String, size: String?, maxContentWidth: Double?): Double? {
        if (maxContentWidth != null && maxContentWidth > 0) return maxContentWidth
        val widths = when (kind) {
            "modal" -> doubleArrayOf(360.0, 440.0, 560.0)
            "sheet" -> doubleArrayOf(320.0, 380.0, 440.0)
            "toast" -> doubleArrayOf(280.0, 360.0, 440.0)
            else -> return null
        }
        return when (size) {
            "small" -> widths[0]
            "medium" -> widths[1]
            "large" -> widths[2]
            else -> null
        }
    }

    /**
     * The size a presentation uses: the call's own [explicit] size wins, then the placement's `display.size`
     * (web `layoutOf`: `options.size ?? source?.size`). Like the web, the placement's size is ignored when the call
     * picked the format itself ([styleOverridden]). Unknown values and a missing size give null (no limit).
     */
    fun resolveSize(explicit: String?, display: JsonObject?, styleOverridden: Boolean): String? {
        explicit?.let { return it }
        if (styleOverridden) return null
        val size = (display?.get("size") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (size == "small" || size == "medium" || size == "large") size else null
    }
}
