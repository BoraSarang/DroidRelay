package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.base32ToHex
import com.borasarang.droidrelay.relay.magnetInfoHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetInfoHashTest {

    @Test
    fun `hex infohash는 소문자로 통일된다`() {
        val hex = "a9993e364706816aba3e25717850c26c9cd0d89d"
        assertEquals(hex, magnetInfoHash("magnet:?xt=urn:btih:$hex&dn=x"))
        assertEquals(hex, magnetInfoHash("magnet:?xt=urn:btih:${hex.uppercase()}&dn=x"))
    }

    @Test
    fun `base32 infohash는 hex로 변환된다`() {
        // SHA1("abc") — RFC vector: base32 ↔ hex (python base64.b32encode 검증값)
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            magnetInfoHash("magnet:?xt=urn:btih:VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5&dn=x"),
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            magnetInfoHash("magnet:?xt=urn:btih:vgmt4nsha2awvor6evyxqugcnsonbwe5&dn=x"),
        )
    }

    @Test
    fun `infohash가 없으면 null`() {
        assertNull(magnetInfoHash("magnet:?dn=x"))
        assertNull(magnetInfoHash("magnet:?xt=urn:btih:tooshort"))
        assertNull(magnetInfoHash("http://example.com/"))
    }

    @Test
    fun `base32는 잘못된 길이와 문자를 거부한다`() {
        assertNull(base32ToHex("VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE")) // 31자
        assertNull(base32ToHex("VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE55")) // 33자
        assertNull(base32ToHex("VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE1")) // 1은 미포함
        assertNull(base32ToHex("0VGMT4NSHA2AWVOR6EVYXQUGCNSONBW")) // 0은 미포함
    }

    @Test
    fun `base32 32자는 항상 40자 hex를 만든다`() {
        val b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val hex = base32ToHex(b32)
        assertEquals(40, hex?.length)
        // 전체 알파벳 문자열은 고정 값으로 회귀 보장 (python base64.b32decode 검증값)
        assertEquals("00443214c74254b635cf84653a56d7c675be77df", hex)
    }
}
