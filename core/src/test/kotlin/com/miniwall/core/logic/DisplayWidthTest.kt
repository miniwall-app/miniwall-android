package com.miniwall.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayWidthTest {
    @Test fun sizesFollowTheWebTable() {
        assertEquals(360.0, DisplayWidth.cap("modal", "small", null))
        assertEquals(440.0, DisplayWidth.cap("modal", "medium", null))
        assertEquals(560.0, DisplayWidth.cap("modal", "large", null))
        assertEquals(320.0, DisplayWidth.cap("sheet", "small", null))
        assertEquals(440.0, DisplayWidth.cap("sheet", "large", null))
        assertEquals(360.0, DisplayWidth.cap("toast", "medium", null))
    }

    @Test fun maxContentWidthWinsOverSize() {
        assertEquals(500.0, DisplayWidth.cap("modal", "small", 500.0))
        assertEquals(500.0, DisplayWidth.cap("fullscreen", null, 500.0))
        assertEquals(500.0, DisplayWidth.cap("banner", "large", 500.0))
    }

    @Test fun noLimitByDefault() {
        assertNull(DisplayWidth.cap("modal", null, null))
        assertNull(DisplayWidth.cap("fullscreen", "large", null))
        assertNull(DisplayWidth.cap("banner", "small", null))
        assertNull(DisplayWidth.cap("modal", "huge", null))
        assertEquals(360.0, DisplayWidth.cap("modal", "small", -1.0))
    }

    private fun display(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun placementSizeIsTheDefaultAndTheExplicitOptionWins() {
        val d = display("""{"format":"modal","size":"small"}""")
        assertEquals("small", DisplayWidth.resolveSize(null, d, false))
        assertEquals("large", DisplayWidth.resolveSize("large", d, false))
        assertEquals("large", DisplayWidth.resolveSize("large", d, true))
        // The placement's size feeds the same width table as the option.
        assertEquals(360.0, DisplayWidth.cap("modal", DisplayWidth.resolveSize(null, d, false), null))
        assertEquals(320.0, DisplayWidth.cap("sheet", DisplayWidth.resolveSize(null, d, false), null))
        assertEquals(280.0, DisplayWidth.cap("toast", DisplayWidth.resolveSize(null, d, false), null))
        assertEquals(500.0, DisplayWidth.cap("modal", DisplayWidth.resolveSize(null, d, false), 500.0))
    }

    @Test fun placementSizeIsIgnoredWhenTheCallPicksTheFormat() {
        // Same as the web: `mode` in the call drops the placement's position, size and duration.
        assertNull(DisplayWidth.resolveSize(null, display("""{"format":"modal","size":"large"}"""), true))
    }

    @Test fun missingOrUnknownPlacementSizeKeepsTheOldNoLimit() {
        assertNull(DisplayWidth.resolveSize(null, null, false))
        assertNull(DisplayWidth.resolveSize(null, display("""{"format":"modal"}"""), false))
        assertNull(DisplayWidth.resolveSize(null, display("""{"size":"huge"}"""), false))
        assertNull(DisplayWidth.resolveSize(null, display("""{"size":3}"""), false))
    }
}
