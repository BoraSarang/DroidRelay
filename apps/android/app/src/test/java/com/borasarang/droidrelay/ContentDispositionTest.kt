package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DispositionHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDispositionTest {

    private fun decode(v: String): String = java.net.URLDecoder.decode(v, "UTF-8")

    private fun assertAsciiOnly(h: String) =
        assertTrue("헤더는 ASCII만: $h", h.all { it.code < 128 })

    @Test
    fun `한글 파일명은 ASCII-only 헤더로 인코딩된다`() {
        val h = DispositionHeader.make("한글파일.mp4")
        assertTrue(h.startsWith("attachment; filename=\""))
        assertTrue(h.contains("; filename*=UTF-8''"))
        assertAsciiOnly(h)
    }

    @Test
    fun `filename 별 percent-decode 시 원본 복원`() {
        val enc = DispositionHeader.make("한글파일.mp4").substringAfter("''")
        assertEquals("한글파일.mp4", decode(enc))
    }

    @Test
    fun `일본어 중국어 이모지 넌로마자 전부 원본 복원`() {
        val names = listOf("ひらがな_テスト.txt", "中文文档.rar", "이모지🎉_테스트.jpg", "construção.pdf")
        for (name in names) {
            val h = DispositionHeader.make(name)
            assertAsciiOnly(h)
            assertEquals(name, decode(h.substringAfter("''")))
        }
    }

    @Test
    fun `공백과 퍼센트 포함 이름이 안전하게 인코딩된다`() {
        val h = DispositionHeader.make("My File 100%.txt")
        assertAsciiOnly(h)
        assertTrue(h.contains("%20"))
        assertEquals("My File 100%.txt", decode(h.substringAfter("''")))
    }
}