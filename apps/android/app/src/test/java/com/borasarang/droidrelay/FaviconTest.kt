package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.Favicon
import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaviconTest {

    @Test
    fun `아이콘 6경로는 파비콘으로 판정`() {
        listOf(
            "/favicon.svg",
            "/favicon-16.png",
            "/favicon-32.png",
            "/apple-touch-icon.png",
            "/site.webmanifest",
            "/favicon.ico",
        ).forEach { assertTrue(it, Favicon.isFavicon(it)) }
    }

    @Test
    fun `일반 경로는 파비콘이 아님`() {
        listOf("/", "/debug", "/api/jobs", "/api/settings", "/s/abc").forEach {
            assertFalse(it, Favicon.isFavicon(it))
        }
    }

    @Test
    fun `경로별 에셋 매핑`() {
        assertEquals("favicon.svg", Favicon.assetFor("/favicon.svg")?.file)
        assertEquals("favicon-32.png", Favicon.assetFor("/favicon.ico")?.file)
        assertEquals("apple-touch-icon.png", Favicon.assetFor("/apple-touch-icon.png")?.file)
        assertNull(Favicon.assetFor("/api/jobs"))
    }

    @Test
    fun `경로별 Content-Type 매핑`() {
        assertEquals("image/svg+xml", Favicon.assetFor("/favicon.svg")?.contentType.toString())
        assertEquals("image/png", Favicon.assetFor("/favicon-32.png")?.contentType.toString())
        assertEquals("image/png", Favicon.assetFor("/apple-touch-icon.png")?.contentType.toString())
        assertEquals("application/manifest+json", Favicon.assetFor("/site.webmanifest")?.contentType.toString())
        assertEquals("image/x-icon", Favicon.assetFor("/favicon.ico")?.contentType.toString())
    }

    @Test
    fun `대시보드 head에 아이콘 링크 5종+테마컬러`() {
        val html = WebAssets.dashboardHtml
        assertTrue(html.contains("""rel="icon" type="image/svg+xml" href="/favicon.svg""""))
        assertTrue(html.contains("""sizes="32x32" href="/favicon-32.png""""))
        assertTrue(html.contains("""sizes="16x16" href="/favicon-16.png""""))
        assertTrue(html.contains("""rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png""""))
        assertTrue(html.contains("""rel="manifest" href="/site.webmanifest""""))
        assertTrue(html.contains("""name="theme-color" content="#0A1428""""))
    }

    @Test
    fun `디버그 head에 아이콘 링크 5종+테마컬러`() {
        val html = WebAssets.debugHtml
        assertTrue(html.contains("""href="/favicon.svg""""))
        assertTrue(html.contains("""href="/apple-touch-icon.png""""))
        assertTrue(html.contains("""href="/site.webmanifest""""))
        assertTrue(html.contains("""name="theme-color""""))
    }

    @Test
    fun `paths와 assetFor가 일치`() {
        assertEquals(6, Favicon.paths.size)
        Favicon.paths.forEach { assertNotNull(it, Favicon.assetFor(it)) }
    }
}
