package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.SignalGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalGateTest {

    @Test
    fun `정상 신호는 실행 허용`() {
        assertFalse(SignalGate.decide(rsrpDbm = -95, sinrDb = 10, held = false))
        assertFalse(SignalGate.decide(rsrpDbm = -108, sinrDb = 3, held = false))
    }

    @Test
    fun `RSRP -110 이하면 정지 진입`() {
        assertTrue(SignalGate.decide(rsrpDbm = -110, sinrDb = 10, held = false))
        assertTrue(SignalGate.decide(rsrpDbm = -115, sinrDb = 10, held = false))
    }

    @Test
    fun `SINR 0 이하면 RSRP 멀쩡해도 정지 진입`() {
        assertTrue(SignalGate.decide(rsrpDbm = -95, sinrDb = 0, held = false))
        assertTrue(SignalGate.decide(rsrpDbm = -95, sinrDb = -2, held = false))
    }

    @Test
    fun `홀드 중엔 중간값(-108)에서도 정지 유지 — 히스테리시스`() {
        assertTrue(SignalGate.decide(rsrpDbm = -108, sinrDb = 3, held = true))
        assertTrue(SignalGate.decide(rsrpDbm = -101, sinrDb = 10, held = true))
    }

    @Test
    fun `홀드 해제는 RSRP -100 이상 그리고 SINR 3 이상`() {
        assertFalse(SignalGate.decide(rsrpDbm = -100, sinrDb = 3, held = true))
        assertFalse(SignalGate.decide(rsrpDbm = -95, sinrDb = 10, held = true))
        // RSRP만 회복·SINR 미달이면 유지
        assertTrue(SignalGate.decide(rsrpDbm = -95, sinrDb = 1, held = true))
        // SINR만 회복·RSRP 미달이면 유지
        assertTrue(SignalGate.decide(rsrpDbm = -105, sinrDb = 10, held = true))
    }

    @Test
    fun `SINR 측정 불가면 RSRP만으로 판정`() {
        assertFalse(SignalGate.decide(rsrpDbm = -95, sinrDb = null, held = false))
        assertTrue(SignalGate.decide(rsrpDbm = -115, sinrDb = null, held = false))
        assertFalse(SignalGate.decide(rsrpDbm = -95, sinrDb = null, held = true))
    }

    @Test
    fun `RSRP 측정 불가면 fail-open — 절대 막지 않음`() {
        assertFalse(SignalGate.decide(rsrpDbm = null, sinrDb = 3, held = false))
        assertFalse(SignalGate.decide(rsrpDbm = null, sinrDb = null, held = false))
        assertFalse(SignalGate.decide(rsrpDbm = null, sinrDb = 3, held = true))
    }
}
