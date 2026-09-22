package com.borasarang.droidrelay.relay

import java.util.Calendar

/**
 * 통계 하이라이트 6종 (v0.38) — traffic.json 스키마 변경 없이
 * [TrafficLedger.daily]/[TrafficLedger.summary] 연산만으로 파생.
 * 순수함수 — 단위테스트 가능.
 */
data class TrafficHighlights(
    val bestDay: TrafficDay? = null,
    val top3: List<TrafficDay> = emptyList(),
    val weekAvgDown: Long = 0L,
    val weekAvgUp: Long = 0L,
    val monthForecastDown: Long = 0L,
    /** 전체 업/다운 비율 (down=0이면 null → "—" 표시) */
    val shareRatio: Double? = null,
    /** 토렌트 한정 업/다운 비율 */
    val torrentRatio: Double? = null,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val activeDays30: Int = 0,
    val httpPct: Double = 0.0,
    val videoPct: Double = 0.0,
    val torrentPct: Double = 0.0,
)

object TrafficHighlightsCalc {
    fun compute(
        days: List<TrafficDay>,
        summary: TrafficSummary,
        nowMs: Long = System.currentTimeMillis(),
    ): TrafficHighlights {
        val withDown = days.filter { it.downTotal() > 0 }
        val best = withDown.maxByOrNull { it.downTotal() }
        val top3 = withDown.sortedByDescending { it.downTotal() }.take(3)

        val last7 = days.takeLast(7)
        val weekAvgDown = if (last7.isEmpty()) 0L else last7.sumOf { it.downTotal() } / last7.size
        val weekAvgUp = if (last7.isEmpty()) 0L else last7.sumOf { it.upTotal() } / last7.size

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val elapsed = cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthDown = summary.month.downTotal()
        val forecast = monthDown / elapsed.toLong() * daysInMonth

        val totalDown = summary.total.downTotal()
        val totalUp = summary.total.upTotal()
        val share = if (totalDown > 0) totalUp.toDouble() / totalDown else null
        val tDown = summary.total.downTorrent
        val tUp = summary.total.upTorrent
        val tRatio = if (tDown > 0) tUp.toDouble() / tDown else null

        var cur = 0
        for (d in days.asReversed()) {
            if (d.downTotal() > 0) cur++ else break
        }
        var longest = 0
        var run = 0
        for (d in days) {
            if (d.downTotal() > 0) {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        val active30 = days.takeLast(30).count { it.downTotal() + it.upTotal() > 0 }

        val httpP = if (totalDown > 0) summary.total.downHttp * 100.0 / totalDown else 0.0
        val videoP = if (totalDown > 0) summary.total.downVideo * 100.0 / totalDown else 0.0
        val torrentP = if (totalDown > 0) tDown * 100.0 / totalDown else 0.0

        return TrafficHighlights(
            bestDay = best,
            top3 = top3,
            weekAvgDown = weekAvgDown,
            weekAvgUp = weekAvgUp,
            monthForecastDown = forecast,
            shareRatio = share,
            torrentRatio = tRatio,
            currentStreak = cur,
            longestStreak = longest,
            activeDays30 = active30,
            httpPct = httpP,
            videoPct = videoP,
            torrentPct = torrentP,
        )
    }
}
