package com.miniwall.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `assets` of a delivery body (MOBILE-SDK-PLAN S12): SVG markup per icon (`lucide/x`) and a TTF link per
 * `font_name` (`Inter@600`), so a phone does not call api.iconify.design or trust the links saved in the
 * screen. Older servers send none; every lookup then falls back to the node's own link.
 */
data class DeliveryAssets(val icons: Map<String, String> = emptyMap(), val fonts: Map<String, String> = emptyMap()) {
    operator fun plus(other: DeliveryAssets) = DeliveryAssets(icons + other.icons, fonts + other.fonts)
}

/**
 * Size limits of downloads (images and fonts come from links in the screen, so a bad link must not fill memory).
 * A download whose `Content-Length` is larger is refused before the body is read; one without a length (or lying
 * about it) is aborted as soon as the bytes read pass the limit. Port: `DownloadLimits` in `Assets.swift`.
 */
object DownloadLimits {
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_FONT_BYTES = 5L * 1024 * 1024

    /** True when a declared `Content-Length` (-1 = unknown) is already over [max]. */
    fun declaredTooLarge(contentLength: Long, max: Long): Boolean = contentLength > max

    /**
     * The bytes of [input], or null when [contentLength] declares more than [max] or the stream holds more than [max]
     * (reading stops at max + 1 bytes, so nothing larger than the limit is ever buffered).
     */
    fun readLimited(input: java.io.InputStream, max: Long, contentLength: Long = -1): ByteArray? {
        if (declaredTooLarge(contentLength, max)) return null
        val out = java.io.ByteArrayOutputStream(if (contentLength in 1..max) contentLength.toInt() else 8192)
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

object Assets {
    /** An icon file in a path-style URL: `https://api.iconify.design/lucide/x.svg` -> `lucide/x`. */
    private val ICON_URL = Regex("""^https?://[^/?#]+(?:/v1/sdk/icons)?/([a-z0-9-]+)/([a-z0-9-]+)\.svg(?:[?#].*)?$""")

    /** The name the server files an icon under, or null for a link that is not a `set/name.svg` file. */
    fun iconKey(url: String): String? = ICON_URL.matchEntire(url)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }

    /**
     * Assets of a body and of everything nested in it that ships its own (an A/B side in `experiment.b`,
     * each audience's screen in `rules[].paywall`), since each of those is a full delivery body.
     */
    fun of(body: JsonObject?): DeliveryAssets {
        body ?: return DeliveryAssets()
        val own = (body["assets"] as? JsonObject)?.let { a ->
            DeliveryAssets(strings(a["icons"]), strings(a["fonts"]))
        } ?: DeliveryAssets()
        val sides = listOfNotNull((body["experiment"] as? JsonObject)?.get("b") as? JsonObject) +
            ((body["rules"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("paywall") as? JsonObject } ?: emptyList())
        return sides.fold(own) { acc, side -> acc + of(side) }
    }

    private fun strings(value: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        (value as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap() ?: emptyMap()

    private fun isFontLink(url: String) =
        url.startsWith("https://") && url.substringBefore('?').lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") }

    /**
     * Where to get the font file for [name]: the server's link first, else the one saved in the screen
     * (`fonts[name].<platform>`: `ios` or `android`). Null for a system font or a link that is not a TTF/OTF over https.
     */
    fun fontLink(name: String, declaration: JsonObject?, platform: String, assets: DeliveryAssets): String? =
        listOf(assets.fonts[name], (declaration?.get(platform) as? JsonPrimitive)?.takeIf { it.isString }?.content)
            .firstOrNull { it != null && isFontLink(it) }
}

/** Facts the SDK reads from the device for audiences (docs/specs/targeting.md, mobile section). All are strings or null. */
object AutoAttributes {
    /** Region of a locale (`vi-VN`, `vi_VN`, `zh-Hans-CN`, `es-419`), upper case; null when the locale has none. Never from an IP. */
    fun country(locale: String?): String? {
        val parts = (locale ?: return null).split('-', '_').drop(1)
        for (part in parts) {
            if (part.length == 1) return null // extension or private use: nothing region-like after it
            if (part.length == 2 && part.all { it in 'a'..'z' || it in 'A'..'Z' }) return part.uppercase()
            if (part.length == 3 && part.all { it in '0'..'9' }) return part
        }
        return null
    }

    /** Major version of the OS (`17.4.1` -> `17`, `14` -> `14`), so `gte 16` compares as a number like on the web. */
    fun osVersion(raw: String?): String? = raw?.trim()?.takeWhile { it in '0'..'9' }?.takeIf { it.isNotEmpty() }

    /** The app's own version name as the store shows it (`2.3.1`); blank is none. */
    fun appVersion(raw: String?): String? {
        // Events carry it as an analytics label (letters, digits, . + _ -, up to 32): others become `-`.
        val cleaned = raw?.trim()?.map { if (it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it in ".+_-") it else '-' }?.joinToString("")?.take(32)
        return cleaned?.takeIf { it.isNotEmpty() && (it[0].isLetterOrDigit() && it[0].code < 128) }
    }
}
