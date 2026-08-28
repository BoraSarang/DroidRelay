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
        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size != 5) return false
        return matchesField(parts[0], minute, 0, 59)
            && matchesField(parts[1], hour, 0, 23)
            && matchesField(parts[2], dayOfMonth, 1, 31)
            && matchesField(parts[3], month, 1, 12)
            && matchesField(parts[4], dayOfWeek, 1, 7)
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
        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size != 5) return -1

        val now = Calendar.getInstance()
        val check = now.clone() as Calendar
        check.set(Calendar.SECOND, 0)
        check.set(Calendar.MILLISECOND, 0)
        check.add(Calendar.MINUTE, 1)

        // 최대 7일(10080분) 탐색
        for (i in 0 until 10080) {
            if (matches(cron, check.get(Calendar.MINUTE), check.get(Calendar.HOUR_OF_DAY),
                    check.get(Calendar.DAY_OF_MONTH), check.get(Calendar.MONTH) + 1,
                    check.get(Calendar.DAY_OF_WEEK))) {
                return check.timeInMillis - now.timeInMillis
            }
            check.add(Calendar.MINUTE, 1)
        }
        return -1
    }

    fun isValid(cron: String): Boolean {
        val parts = cron.trim().split("\\s+".toRegex())
        return parts.size == 5 && msUntilNextMatch(cron) >= 0
    }
}
