package com.borasarang.droidrelay.relay

import java.util.Calendar

/** 속도 스케줄 — 요일+시간 창 기반 전역 속도 자동화 (v0.24 Phase C).
 *
 * 직렬화는 수동(`;`/`|` 구분, org.json 미사용 — JVM 단위 테스트 스텁 회피).
 * 요일은 Calendar.DAY_OF_WEEK (1=일..7=토).
 */
data class SpeedWindow(
    val id: String,
    val enabled: Boolean,
    val days: Set<Int>,
    val startMin: Int,
    val endMin: Int,
    val downKbps: Long,
    val upKbps: Long,
)

object SpeedSchedule {
    const val MAX_WINDOWS = 10
    const val MAX_KBPS = 1_000_000L

    /** 창 활성 여부 — endMin<=startMin이면 자정 넘김.
     * 새벽분(min<end)은 시작 요일의 다음날에 속한다 (예: 화 22:00→02:00는 수 01:00 포함). */
    fun isActive(w: SpeedWindow, day: Int, min: Int): Boolean {
        if (!w.enabled) return false
        if (w.endMin > w.startMin) {
            return day in w.days && min in w.startMin until w.endMin
        }
        if (min >= w.startMin) return day in w.days
        if (min < w.endMin) {
            val prev = if (day == 1) 7 else day - 1
            return prev in w.days
        }
        return false
    }

    /** 첫 매칭 창 — 순서가 우선순위 */
    fun decide(windows: List<SpeedWindow>, day: Int, min: Int): SpeedWindow? =
        windows.firstOrNull { isActive(it, day, min) }

    /** 유효성 — API/Repo 진입점 공통 */
    fun isValid(w: SpeedWindow): Boolean {
        if (w.id.isBlank() || w.id.any { it == '|' || it == ';' }) return false
        if (w.days.isEmpty() || w.days.any { it !in 1..7 }) return false
        if (w.startMin !in 0..1439 || w.endMin !in 0..1439) return false
        if (w.downKbps < 0 || w.upKbps < 0) return false
        if (w.downKbps > MAX_KBPS || w.upKbps > MAX_KBPS) return false
        return true
    }

    fun encode(windows: List<SpeedWindow>): String =
        windows.take(MAX_WINDOWS).filter { isValid(it) }.joinToString(";") { w ->
            listOf(
                w.id,
                if (w.enabled) "1" else "0",
                w.days.sorted().joinToString(","),
                w.startMin.toString(),
                w.endMin.toString(),
                w.downKbps.toString(),
                w.upKbps.toString(),
            ).joinToString("|")
        }

    /** 파싱 — 파손 항목은 drop (never throw) */
    fun decode(raw: String?): List<SpeedWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { part ->
            runCatching {
                val f = part.split("|")
                if (f.size != 7) return@runCatching null
                val w = SpeedWindow(
                    id = f[0],
                    enabled = f[1] == "1",
                    days = f[2].split(",").map { it.toInt() }.toSet(),
                    startMin = f[3].toInt(),
                    endMin = f[4].toInt(),
                    downKbps = f[5].toLong(),
                    upKbps = f[6].toLong(),
                )
                if (isValid(w)) w else null
            }.getOrNull()
        }.take(MAX_WINDOWS)
    }

    fun nowDayMin(): Pair<Int, Int> {
        val c = Calendar.getInstance()
        return c.get(Calendar.DAY_OF_WEEK) to (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }
}
