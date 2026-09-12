package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.StreamDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraHeadersTest {

    @Test
    fun `둘 다 비면 null`() {
        assertNull(StreamDetector.sanitizeExtra(null, null))
        assertNull(StreamDetector.sanitizeExtra("  ", ""))
        assertFalse(StreamDetector.isExtraTooLong(null, "  "))
    }

    @Test
    fun `referer만 있으면 전달`() {
        val e = StreamDetector.sanitizeExtra("https://example.com/page", null)
        assertNotNull(e)
        assertEquals("https://example.com/page", e!!.referer)
        assertNull(e.cookie)
        assertTrue(e.hasReferer)
        assertFalse(e.hasCookie)
    }

    @Test
    fun `cookie만 있으면 전달`() {
        val e = StreamDetector.sanitizeExtra(null, "sid=abc")
        assertNotNull(e)
        assertEquals("sid=abc", e!!.cookie)
        assertTrue(e.hasCookie)
    }

    @Test
    fun `앞뒤 공백은 제거`() {
        val e = StreamDetector.sanitizeExtra("  https://a.com/  ", "  k=v  ")
        assertEquals("https://a.com/", e!!.referer)
        assertEquals("k=v", e.cookie)
    }

    @Test
    fun `referer 초과는 거부`() {
        val long = "https://a.com/" + "x".repeat(StreamDetector.MAX_REFERER_LEN)
        assertNull(StreamDetector.sanitizeExtra(long, null))
        assertTrue(StreamDetector.isExtraTooLong(long, null))
    }

    @Test
    fun `cookie 초과는 거부`() {
        val long = "k=" + "y".repeat(StreamDetector.MAX_COOKIE_LEN)
        assertNull(StreamDetector.sanitizeExtra(null, long))
        assertTrue(StreamDetector.isExtraTooLong(null, long))
    }
}
