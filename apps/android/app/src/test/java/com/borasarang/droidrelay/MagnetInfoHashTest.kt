package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.base32ToHex
import com.borasarang.droidrelay.relay.magnetInfoHash
import com.borasarang.droidrelay.relay.sanitizeDnValue
import com.borasarang.droidrelay.relay.sanitizeMagnetDn
import com.borasarang.droidrelay.relay.validateMagnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `validateMagnet는 정상 링크를 trim해 통과시킨다`() {
        val hex = "a9993e364706816aba3e25717850c26c9cd0d89d"
        assertEquals(
            "magnet:?xt=urn:btih:$hex&dn=x",
            validateMagnet("  magnet:?xt=urn:btih:$hex&dn=x\n"),
        )
    }

    @Test
    fun `validateMagnet는 infohash 없는 링크를 거부한다`() {
        for (bad in listOf("magnet:?dn=x", "magnet:", "http://example.com/", "", "magnet:?xt=urn:btih:tooshort")) {
            try {
                validateMagnet(bad)
                fail("거부돼야 함: '$bad'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `validateMagnet는 과도하게 긴 링크를 거부한다`() {
        val hex = "a9993e364706816aba3e25717850c26c9cd0d89d"
        try {
            validateMagnet("magnet:?xt=urn:btih:$hex&dn=" + "x".repeat(4096))
            fail("거부돼야 함: 과도한 길이")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    /** dn 값의 %XX를 디코드해 엄격한 UTF-8인지 — 테스트용 미러 */
    private fun dnStrictValid(enc: String): Boolean {
        val out = java.io.ByteArrayOutputStream(enc.length)
        var i = 0
        while (i < enc.length) {
            val c = enc[i]
            if (c == '%' && i + 2 <= enc.lastIndex) {
                val v1 = enc[i + 1].digitToIntOrNull(16)
                val v2 = enc[i + 2].digitToIntOrNull(16)
                if (v1 != null && v2 != null) {
                    out.write((v1 shl 4) or v2)
                    i += 3
                    continue
                }
            }
            val cp = enc.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    @Test
    fun `sanitizeDnValue는 잘린 UTF-8 꼬리를 잘라 유효하게 만든다 — S22 SIGABRT 재현`() {
        // 실기기 poison: dn이 UTF-8 선행 바이트(%E3) 단독으로 끝남 → NewStringUTF abort
        val poison = "%2B%2B%2B%20%5BFHD%5D%20SNOS-418%20%E3%81%82%E3%81%A9%E3%81%91%E3%81%AA%E3%81%8B%E3%81%A3%E3%81%9F%E5%AD%AB%E3%82%82%E8%B5%A4%E3%81%A1%E3%82%83%E3%82%93%E4%BD%9C%E3%82%8C%E3%81%9D%E3%81%86%E3%81%AA%E4%BD%93%E3%81%A4%E3%81%8D%E3%81%AB%E3"
        assertTrue(!dnStrictValid(poison))
        val fixed = sanitizeDnValue(poison)
        assertTrue("정화 후 유효해야 함: $fixed", dnStrictValid(fixed))
        // 완성된 앞부분은 보존 — 앞 60자는 그대로
        assertTrue(fixed.startsWith(poison.take(60)))
    }

    @Test
    fun `sanitizeDnValue는 미완성 이스케이프 꼬리를 제거한다`() {
        assertEquals("abc", sanitizeDnValue("abc%"))
        assertEquals("abc", sanitizeDnValue("abc%E"))
        assertEquals("abc", sanitizeDnValue("abc%E3%81")) // 완성형이어도 깨진 꼬리면 제거
        assertEquals("AZaz09-._~", sanitizeDnValue("AZaz09-._~"))
        assertEquals("", sanitizeDnValue(""))
    }

    @Test
    fun `sanitizeMagnetDn은 정상 magnet을 그대로 둔다`() {
        val hex = "2667380e86914d6aaf85a1f292b05df9230e84d1"
        val ok = "magnet:?xt=urn:btih:$hex&dn=test+file"
        assertEquals(ok, sanitizeMagnetDn(ok))
        val noDn = "magnet:?xt=urn:btih:$hex"
        assertEquals(noDn, sanitizeMagnetDn(noDn))
    }

    @Test
    fun `sanitizeMagnetDn 후에도 infohash는 유지된다`() {
        val hex = "2667380e86914d6aaf85a1f292b05df9230e84d1"
        val poison = "magnet:?xt=urn:btih:$hex&dn=%E3%81%82%E3"
        val fixed = sanitizeMagnetDn(validateMagnet(poison))
        assertEquals(hex, magnetInfoHash(fixed))
        val dn = fixed.substringAfter("dn=")
        assertTrue(dnStrictValid(dn))
    }
}
