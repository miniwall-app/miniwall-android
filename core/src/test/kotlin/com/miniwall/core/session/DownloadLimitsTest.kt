package com.miniwall.core.session

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadLimitsTest {
    @Test
    fun limitsAreTenMegabytesForImagesAndFiveForFonts() {
        assertEquals(10L * 1024 * 1024, DownloadLimits.MAX_IMAGE_BYTES)
        assertEquals(5L * 1024 * 1024, DownloadLimits.MAX_FONT_BYTES)
    }

    @Test
    fun declaredLengthOverTheLimitIsRefused() {
        assertTrue(DownloadLimits.declaredTooLarge(101, 100))
        assertFalse(DownloadLimits.declaredTooLarge(100, 100))
        assertFalse(DownloadLimits.declaredTooLarge(-1, 100))
    }

    @Test
    fun readsABodyWithinTheLimit() {
        val data = ByteArray(1000) { it.toByte() }
        assertContentEquals(data, DownloadLimits.readLimited(ByteArrayInputStream(data), 1000))
        assertContentEquals(ByteArray(0), DownloadLimits.readLimited(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun refusesBeforeReadingWhenContentLengthIsTooLarge() {
        val stream = object : InputStream() {
            override fun read(): Int = error("must not be read")
            override fun read(b: ByteArray): Int = error("must not be read")
        }
        assertNull(DownloadLimits.readLimited(stream, 100, contentLength = 5000))
    }

    @Test
    fun abortsWhenTheStreamPassesTheLimitEvenIfNoLengthWasDeclared() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray): Int { served += b.size; return b.size }
        }
        assertNull(DownloadLimits.readLimited(endless, 20_000))
        assertTrue(served <= 20_000 + 8192, "stopped reading soon after the limit, served $served")
    }

    @Test
    fun aBodyOneByteOverIsRefused() {
        assertNull(DownloadLimits.readLimited(ByteArrayInputStream(ByteArray(101)), 100, contentLength = 100))
    }
}
