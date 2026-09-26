package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.CronParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CronParser 성능·정확성 (Phase 4).
 *
 * 배경: matches() 가 호출될 때마다 `split("\\s+".toRegex())` 로 새 Regex 를
 * 컴파일했고, msUntilNextMatch 는 matches 를 최대 10,080회 호출한다.
 * isValid() 는 앱 설정 화면의 onValueChange 에서 매 키 입력마다 호출되어
 * "절대 매칭 안 되는" 표현식(예: 0 0 30 2 *)에서 문자 한 글자마다
 * 10,080회 정규식 컴파일 + Calendar.add 가 메인 스레드에서 돌았다.
 */
class CronParserPerfTest {

    @Test
    fun `절대 매칭 안 되는 5필드 표현식은 유효하지 않음`() {
        // 2월 30일은 존재하지 않으므로 7일 탐색을 모두 소진하고 -1 을 반환해야 한다
        assertFalse(CronParser.isValid("0 0 30 2 *"))
    }

    @Test
    fun `필드 수가 5가 아니면 탐색 없이 거부`() {
        assertFalse(CronParser.isValid("0 2 * *"))
        assertFalse(CronParser.isValid("0 2 * * * *"))
        assertFalse(CronParser.isValid(""))
        assertFalse(CronParser.msUntilNextMatch("* * *") >= 0)
    }

    @Test
    fun `정상 표현식은 유효`() {
        assertTrue(CronParser.isValid("0 2 * * *"))
        assertTrue(CronParser.isValid("*/15 * * * *"))
        assertTrue(CronParser.isValid("30 3 1 * *"))
    }

    @Test
    fun `필드 분리 결과가 matches 로직과 동일`() {
        // 별표/범위/콤마/step 조합
        assertTrue(CronParser.matches("0 2 * * *", minute = 0, hour = 2, dayOfMonth = 15, month = 6, dayOfWeek = 3))
        assertFalse(CronParser.matches("0 2 * * *", minute = 1, hour = 2, dayOfMonth = 15, month = 6, dayOfWeek = 3))
        assertTrue(CronParser.matches("*/15 * * * *", minute = 45, hour = 9, dayOfMonth = 1, month = 1, dayOfWeek = 1))
        assertFalse(CronParser.matches("*/15 * * * *", minute = 46, hour = 9, dayOfMonth = 1, month = 1, dayOfWeek = 1))
        assertTrue(CronParser.matches("0 9-17 * * *", minute = 0, hour = 9, dayOfMonth = 1, month = 1, dayOfWeek = 1))
        assertTrue(CronParser.matches("0 0,12 * * *", minute = 0, hour = 12, dayOfMonth = 1, month = 1, dayOfWeek = 1))
    }

    @Test
    fun `탐색 결과는 항상 양수이며 7일 이내`() {
        val delta = CronParser.msUntilNextMatch("0 2 * * *")
        assertTrue("다음 실행까지는 양수여야 함", delta > 0)
        assertTrue("최대 7일 이하여야 함", delta <= 7 * 24 * 3600_000L)
    }

    @Test
    fun `잘못된 step 은 거부`() {
        assertFalse(CronParser.isValid("*/0 * * * *"))
        assertFalse(CronParser.matches("*/0 * * * *", 0, 0, 1, 1, 1))
    }

    @Test
    fun `반복 호출에도 결과가 동일해야 한다 - 상태 공유 금지`() {
        val first = CronParser.msUntilNextMatch("*/15 * * * *")
        val second = CronParser.msUntilNextMatch("*/15 * * * *")
        // 1분 단위 ticks 두 번 사이에 경계를 넘었을 수 있으므로 큰 차이가 나면 안 된다
        assertTrue("두 호출 차이가 2분 미만이어야 함", kotlin.math.abs(first - second) < 2 * 60_000L)
        assertEquals(CronParser.isValid("0 2 * * *"), CronParser.isValid("0 2 * * *"))
    }
}
