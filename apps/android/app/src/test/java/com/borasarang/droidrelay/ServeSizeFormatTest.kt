package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Serve 로그의 전송량 표기 — MB 분기가 GB 나눗셈을 썼던 버그를 고정한다 (T-1086) */
class ServeSizeFormatTest {

    @Test
    fun `MB 미만은 KB`() {
        assertEquals("0KB", fmt(500))
        assertEquals("1KB", fmt(1024))
        assertEquals("1023KB", fmt(1_048_575))
    }

    @Test
    fun `MB 경계값은 1024 제곱으로 나눈다`() {
        assertEquals("1.0MB", fmt(1_048_576))
    }

    /** 버그 당시 250MB 파일이 "0.2MB"로 찍혔다 — GB 나눗셈을 썼기 때문 */
    @Test
    fun `250MB 파일이 0이 아닌 실제 크기로 찍힌다`() {
        assertEquals("238.9MB", fmt(250_536_055))
    }

    @Test
    fun `GB 이상은 1024 세제곱으로 나눈다`() {
        assertEquals("1.00GB", fmt(1_073_741_824))
        assertEquals("1.05GB", fmt(1_125_099_854))
    }

    @Test
    fun `대용량 실측값 그대로`() {
        assertEquals("8.01GB", fmt(8_595_477_990))
        assertEquals("6.71GB", fmt(7_206_168_928))
    }

    /** 단위 경계가 1000 단위로 보이면 계산이 틀린 것 — 몫이 급락하지 않아야 한다 */
    @Test
    fun `단위 올림 경계에서 값이 급락하지 않는다`() {
        val under = fmt(1_048_575).removeSuffix("KB").toDouble()
        val over = fmt(1_048_576).removeSuffix("MB").toDouble()
        assertEquals(1023.0, under, 1.0)
        assertEquals(1.0, over, 0.1)
    }
}