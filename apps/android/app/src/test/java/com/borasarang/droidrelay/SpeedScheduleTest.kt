package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.SpeedSchedule
import com.borasarang.droidrelay.relay.SpeedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedScheduleTest {

    private fun w(
        id: String = "a",
        enabled: Boolean = true,
        days: Set<Int> = setOf(2),
        start: Int = 0,
        end: Int = 360,
        down: Long = 512,
        up: Long = 0,
    ) = SpeedWindow(id, enabled, days, start, end, down, up)

    @Test
    fun `창 안에서 매칭`() {
        val hit = SpeedSchedule.decide(listOf(w()), 2, 120)
        assertEquals("a", hit?.id)
    }

    @Test
    fun `창 밖·다른 요일·꺼짐은 null`() {
        assertNull(SpeedSchedule.decide(listOf(w()), 2, 400))
        assertNull(SpeedSchedule.decide(listOf(w()), 3, 120))
        assertNull(SpeedSchedule.decide(listOf(w(enabled = false)), 2, 120))
    }

    @Test
    fun `자정 넘김 창`() {
        val n = w(start = 1320, end = 120) // 22:00→02:00, days={월=2}
        assertTrue(SpeedSchedule.isActive(n, 2, 1400))
        assertTrue(SpeedSchedule.isActive(n, 3, 60)) // 다음날 새벽은 시작 요일에 속함
        assertFalse(SpeedSchedule.isActive(n, 2, 600))
        assertFalse(SpeedSchedule.isActive(n, 4, 60)) // 시작일이 아니면 제외
    }

    @Test
    fun `경계는 시작 포함 종료 제외`() {
        val v = w(start = 60, end = 120)
        assertTrue(SpeedSchedule.isActive(v, 2, 60))
        assertFalse(SpeedSchedule.isActive(v, 2, 120))
    }

    @Test
    fun `첫 매칭 우선`() {
        val first = SpeedSchedule.decide(listOf(w("a"), w("b")), 2, 100)
        assertEquals("a", first?.id)
    }

    @Test
    fun `직렬화 왕복`() {
        val list = listOf(w("x", days = setOf(1, 7), down = 1024, up = 32), w("y", enabled = false))
        val back = SpeedSchedule.decode(SpeedSchedule.encode(list))
        assertEquals(2, back.size)
        assertEquals(setOf(1, 7), back[0].days)
        assertEquals(1024L, back[0].downKbps)
        assertFalse(back[1].enabled)
    }

    @Test
    fun `파손 항목은 drop`() {
        val back = SpeedSchedule.decode("ok|1|2|0|60|512|0;broken|xx;|1|2|0|60|0|0")
        assertEquals(1, back.size)
        assertEquals("ok", back[0].id)
        assertEquals(emptyList<SpeedWindow>(), SpeedSchedule.decode(null))
    }

    @Test
    fun `무효 창은 영속 제외`() {
        val bad = w("bad|id")
        val empty = w("e", days = emptySet())
        assertEquals("", SpeedSchedule.encode(listOf(bad, empty)))
    }
}
