package com.miniwall.core.logic

/**
 * ISO 8601 timestamps without java.time (which needs API 26: the library supports API 24).
 * Always UTC with milliseconds, like JavaScript's `toISOString()`: `2026-10-10T12:00:00.123Z`.
 */
object IsoTime {
    fun format(epochMs: Long): String {
        val days = Math.floorDiv(epochMs, 86_400_000L)
        val inDay = Math.floorMod(epochMs, 86_400_000L)
        // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val h = inDay / 3_600_000
        val m = inDay / 60_000 % 60
        val s = inDay / 1_000 % 60
        val ms = inDay % 1_000
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(year, month, day, h, m, s, ms)
    }

    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?)?$""")

    /**
     * Epoch milliseconds of an ISO 8601 date or date-time, read like JavaScript's `new Date(text)`:
     * a date alone and a date-time with `Z` or `±hh:mm` are exact; a date-time with no offset is in the device's
     * time zone ([offsetMsAt] gives that zone's offset at a moment). Null when it is not an ISO date.
     */
    fun parse(text: String, offsetMsAt: (Long) -> Long = { java.util.TimeZone.getDefault().getOffset(it).toLong() }): Long? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toLong()
        val month = g[2].toLong()
        val day = g[3].toLong()
        val hasTime = g[4].isNotEmpty()
        val hour = if (hasTime) g[4].toLong() else 0L
        val minute = if (hasTime) g[5].toLong() else 0L
        val second = g[6].ifEmpty { "0" }.toLong()
        if (month !in 1..12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val ms = if (g[7].isEmpty()) 0L else g[7].take(3).padEnd(3, '0').toLong()
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        val asUtc = days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + ms
        val zone = g[8]
        return when {
            !hasTime -> asUtc // a date alone is UTC midnight
            zone.isEmpty() -> asUtc - offsetMsAt(asUtc) // no offset: the device's own zone
            zone[0] == 'Z' || zone[0] == 'z' -> asUtc
            else -> {
                val digits = zone.drop(1).replace(":", "")
                val minutes = digits.take(2).toLong() * 60 + digits.drop(2).ifEmpty { "0" }.toLong()
                asUtc - minutes * 60_000L * if (zone[0] == '-') -1 else 1
            }
        }
    }

    private fun daysIn(year: Long, month: Long): Long = when (month) {
        2L -> if (year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)) 29 else 28
        4L, 6L, 9L, 11L -> 30
        else -> 31
    }
}
