package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DispositionHeader
import com.borasarang.droidrelay.relay.VideoDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFilenameTest {

    @Test
    fun `제어문자는 제거`() {
        val out = VideoDownloadManager.safeFilename("ab\u0000cd\u001Fef", "mp4")
        assertEquals("abcdef.mp4", out)
        assertFalse(out.any { it.isISOControl() })
    }

    @Test
    fun `후행 점과 공백은 제거`() {
        assertEquals("video.mp4", VideoDownloadManager.safeFilename("video...   ", "mp4"))
    }

    @Test
    fun `Windows 예약어는 접두`() {
        assertEquals("_CON.mp4", VideoDownloadManager.safeFilename("CON", "mp4"))
        assertEquals("_nul.mp4", VideoDownloadManager.safeFilename("nul", "mp4"))
        assertEquals("_COM1.mp4", VideoDownloadManager.safeFilename("COM1", "mp4"))
        // 예약어가 아니면 그대로
        assertEquals("CONCERT.mp4", VideoDownloadManager.safeFilename("CONCERT", "mp4"))
    }

    @Test
    fun `traversal은 무력화`() {
        val out = VideoDownloadManager.safeFilename("../../etc/passwd", "mp4")
        assertFalse(out.contains("/"))
        assertFalse(out.contains(".."))
    }

    @Test
    fun `80자 cap 유지`() {
        val out = VideoDownloadManager.safeFilename("가".repeat(200), "mp4")
        assertTrue(out.removeSuffix(".mp4").length <= 80)
        assertTrue(out.endsWith(".mp4"))
    }

    @Test
    fun `빈 DispositionHeader는 폴백`() {
        val h = DispositionHeader.make("   ")
        assertTrue(h.contains("download"))
        assertTrue(h.all { it.code < 128 })
    }
}
