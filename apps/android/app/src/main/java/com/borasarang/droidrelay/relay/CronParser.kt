package com.borasarang.droidrelay.relay

import java.util.Calendar

// 간이 크론 파서 - "분 시 일 월 요일" 5필드 지원.
// "*" (모든 값) 또는 숫자/콤마/범위만 지원.
// 예: 0 2 * * * = 매일 새벽 2시, step/15 * * * * = 15분 간격
object CronParser {

    data class CronMatch(
        val minute: Int,
        val hour: Int,
        val dayOfMonth: Int,
        val month: Int,
        val dayOfWeek: Int,
    )

    /**
     * 현재 시간이 크론 표현식과 매칭되는지 확인.
     * 분 단위 정밀도 (초는 무시).
     */
    fun matchesNow(cron: String): Boolean {
        val cal = Calendar.getInstance()
        return matches(cron, cal.get(Calendar.MINUTE), cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_WEEK))
    }

    fun matches(cron: String, minute: Int, hour: Int, dayOfMonth: Int, month: Int, dayOfWeek: Int): Boolean {
        val fields = parse(cron) ?: return false
        return matchesField(fields[0], minute, 0, 59)
            && matchesField(fields[1], hour, 0, 23)
            && matchesField(fields[2], dayOfMonth, 1, 31)
            && matchesField(fields[3], month, 1, 12)
            && matchesField(fields[4], dayOfWeek, 1, 7)
    }

    /**
     * 5필드 분리 — 루프 밖에서 한 번만.
     * 이전 구현은 matches() 가 호출될 때마다 `split("\\s+".toRegex())` 로
     * 새 Regex 를 컴파일했다. msUntilNextMatch 는 최대 10,080회 matches 를 호출하므로
     * 루프 안에서 10,080번의 정규식 컴파일 + split 이 발생했고,
     * isValid() 가 앱 설정 화면의 onValueChange(키 입력마다)에서 호출되어
     * 문자 한 글자마다 메인 스레드가 멈췄다.
     */
    private fun parse(cron: String): List<String>? {
        val parts = cron.trim().split(WHITESPACE)
        return if (parts.size == 5) parts else null
    }

    private fun matchesField(field: String, value: Int, min: Int, max: Int): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) {
            val step = field.removePrefix("*/").toIntOrNull() ?: return false
            return step > 0 && (value - min) % step == 0
        }
        // 콤마 separated values
        for (part in field.split(",")) {
            if (part.contains("-")) {
                val (a, b) = part.split("-", limit = 2).map { it.toIntOrNull() ?: -1 }
                if (value in a..b) return true
            } else {
                if (part.toIntOrNull() == value) return true
            }
        }
        return false
    }

    /**
     * 다음 매칭 시각까지의 밀리초 반환.
     * 크론이 유효하지 않으면 -1 반환.
     */
    fun msUntilNextMatch(cron: String): Long {
        // 필드 분리는 1회 — matches() 가 10,080회 호출되어도 다시 나누지 않는다
        val fields = parse(cron) ?: return -1

        val now = Calendar.getInstance()
        val check = now.clone() as Calendar
        check.set(Calendar.SECOND, 0)
        check.set(Calendar.MILLISECOND, 0)
        check.add(Calendar.MINUTE, 1)

        // 최대 7일(10080분) 탐색
        for (i in 0 until 10080) {
            if (matchesField(fields[0], check.get(Calendar.MINUTE), 0, 59) &&
                matchesField(fields[1], check.get(Calendar.HOUR_OF_DAY), 0, 23) &&
                matchesField(fields[2], check.get(Calendar.DAY_OF_MONTH), 1, 31) &&
                matchesField(fields[3], check.get(Calendar.MONTH) + 1, 1, 12) &&
                matchesField(fields[4], check.get(Calendar.DAY_OF_WEEK), 1, 7)
            ) {
                return check.timeInMillis - now.timeInMillis
            }
            check.add(Calendar.MINUTE, 1)
        }
        return -1
    }

    fun isValid(cron: String): Boolean {
        // 필드 수 검사를 먼저 — 5필드가 아니면 탐색 자체가 불필요하다
        parse(cron) ?: return false
        return msUntilNextMatch(cron) >= 0
    }

    private val WHITESPACE = Regex("\\s+")
}
