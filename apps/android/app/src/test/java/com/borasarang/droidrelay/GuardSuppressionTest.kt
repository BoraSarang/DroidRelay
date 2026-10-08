package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.guardEffective
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardSuppressionTest {

    @Test
    fun `평시는 설정과 보유 여부의 AND`() {
        assertTrue(guardEffective(true, true, false))
        assertFalse(guardEffective(true, false, false))
        assertFalse(guardEffective(false, true, false))
        assertFalse(guardEffective(false, false, false))
    }

    @Test
    fun `억제 중엔 설정·보유 무관하게 전부 false`() {
        assertFalse(guardEffective(true, true, true))
        assertFalse(guardEffective(true, false, true))
        assertFalse(guardEffective(false, true, true))
        assertFalse(guardEffective(false, false, true))
    }
}
