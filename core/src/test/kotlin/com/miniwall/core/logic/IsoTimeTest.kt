package com.miniwall.core.logic

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoTimeTest {
    @Test
    fun formatMatchesJavaTimeAcrossTheCalendar() {
        var t = -2_000_000_000_000L
        while (t < 4_200_000_000_000L) {
            val expected = Instant.ofEpochMilli(t).toString().let { if (it.length == 20) it.dropLast(1) + ".000Z" else it }
            assertEquals(expected, IsoTime.format(t), "t=$t")
            t += 86_400_000L * 13 + 3_723_456L
        }
        assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(0))
        assertEquals("2000-02-29T23:59:59.999Z", IsoTime.format(951_868_799_999L))
    }

    @Test
    fun parseReadsWhatJavaTimeReadsPlusLongFractions() {
        for (s in listOf("2026-12-31T23:59:59Z", "2026-12-31T23:59:59.123Z", "2026-12-31T23:59:59+07:00", "2026-01-01T00:00:00-05:30", "2024-02-29T12:00:00.5Z")) {
            assertEquals(OffsetDateTime.parse(s).toInstant().toEpochMilli(), IsoTime.parse(s), s)
        }
        // More than 3 fraction digits, and no offset (UTC): browsers accept both.
        assertEquals(Instant.parse("2026-12-31T23:59:59.123Z").toEpochMilli(), IsoTime.parse("2026-12-31T23:59:59.123456Z"))
        // No offset means the device's zone (like `new Date("2026-12-31T23:59")`); a date alone is UTC midnight.
        assertEquals(Instant.parse("2026-12-31T16:59:00Z").toEpochMilli(), IsoTime.parse("2026-12-31T23:59") { 7 * 3_600_000L })
        assertEquals(Instant.parse("2026-12-31T00:00:00Z").toEpochMilli(), IsoTime.parse("2026-12-31"))
        assertNull(IsoTime.parse("tomorrow"))
        assertNull(IsoTime.parse("2026-13-01T00:00:00Z"))
        assertNull(IsoTime.parse("2026-02-31T00:00:00Z"))
    }
}
