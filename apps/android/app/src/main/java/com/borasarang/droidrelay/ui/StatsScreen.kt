package com.borasarang.droidrelay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.StatsSnapshots
import com.borasarang.droidrelay.relay.TrafficDay
import com.borasarang.droidrelay.relay.TrafficHighlights
import com.borasarang.droidrelay.relay.TrafficHighlightsCalc
import com.borasarang.droidrelay.relay.TrafficLedger
import com.borasarang.droidrelay.relay.TrafficSummary

/** 트래픽 통계 탭 — 히어로(이번 달) + 최근 30일 막대 + 핵심 4지표 + 월 예상, 상세는 접이식 (v0.59 2차) */
@Composable
fun StatsScreen() {
    val ctx = LocalContext.current
    var summary by remember { mutableStateOf(TrafficLedger.summary()) }
    var days30 by remember { mutableStateOf(TrafficLedger.daily(30)) }
    var snapTick by remember { mutableLongStateOf(0L) }
    var showDetail by remember { mutableStateOf(false) }
    var highlights by remember {
        mutableStateOf(
            TrafficHighlightsCalc.compute(
                TrafficLedger.daily(400),
                TrafficLedger.summary(),
            ),
        )
    }

    LaunchedEffect(Unit) {
        runCatching { StatsSnapshots.init(ctx.applicationContext) }
        while (true) {
            val s = TrafficLedger.summary()
            summary = s
            days30 = TrafficLedger.daily(30)
            highlights = TrafficHighlightsCalc.compute(TrafficLedger.daily(400), s)
            snapTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(5000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { HeroCard(summary) }
        item { MonthBarsCard(days30) }
        item { StatTileGrid(summary, highlights) }
        item { ForecastCard(summary, highlights) }
        item {
            TextButton(
                onClick = { showDetail = !showDetail },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showDetail) "상세 접기" else "하이라이트 · 기록 자세히")
            }
        }
        if (showDetail) {
            item {
                HighlightCard(
                    bestLine = highlights.bestDay?.let { "${it.date} · ${fmtBytes(it.downTotal())}" } ?: "—",
                    top3Line = highlights.top3.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                        "${it.date.takeLast(5)} ${fmtBytes(it.downTotal())}"
                    } ?: "—",
                    avgForecastLine = "${fmtBytes(highlights.weekAvgDown)} / 약 ${fmtBytes(highlights.monthForecastDown)}",
                    shareLine = "${highlights.shareRatio?.let { "%.1f%%".format(it * 100) } ?: "—"} / " +
                        "${highlights.torrentRatio?.let { "%.1f%%".format(it * 100) } ?: "—"}",
                    streakLine = "${highlights.currentStreak}일 / ${highlights.longestStreak}일 / ${highlights.activeDays30}일",
                    mixLine = if (summary.total.downTotal() > 0) {
                        "H ${"%.0f".format(highlights.httpPct)}% · V ${"%.0f".format(highlights.videoPct)}% · " +
                            "T ${"%.0f".format(highlights.torrentPct)}%"
                    } else {
                        "—"
                    },
                )
            }
            item {
                // snapTick 읽기 → 5초마다 스냅샷 값 리컴포즈
                snapTick.let {
                    RecordCard(
                        peakDownLine = if (summary.total.maxDownBps > 0) {
                            "${fmtBytes(summary.total.maxDownBps)}/s"
                        } else {
                            "—"
                        },
                        peakUpLine = if (summary.total.maxUpBps > 0) {
                            "${fmtBytes(summary.total.maxUpBps)}/s"
                        } else {
                            "—"
                        },
                        doneLine = "${summary.total.doneTotal()}건 " +
                            "(H ${summary.total.doneHttp} · V ${summary.total.doneVideo} · " +
                            "T ${summary.total.doneTorrent})",
                        failLine = "${summary.total.failCount}건",
                        peerLine = "현재 ${StatsSnapshots.lastSeeds}시드/${StatsSnapshots.lastPeers}피어 · " +
                            "피크 ${StatsSnapshots.peakSeeds}/${StatsSnapshots.peakPeers}",
                        bootLine = "부팅 ${StatsSnapshots.bootCount}회",
                        netLine = "단절 ${StatsSnapshots.netLossCount()}회 · " +
                            "스로틀 ${StatsSnapshots.throttleCount()}회",
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 이번 달 전송량 히어로 — 화면의 단일 최대 숫자 */
@Composable
private fun HeroCard(summary: TrafficSummary) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "이번 달 전송량",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onPrimaryContainer.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                fmtBytes(summary.month.downTotal()),
                style = MaterialTheme.typography.displaySmall,
                color = cs.onPrimaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "다운로드 ${fmtBytes(summary.month.downTotal())} · 업로드 ${fmtBytes(summary.month.upTotal())}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onPrimaryContainer.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "오늘 ${fmtBytes(summary.today.downTotal())} · 누적 ${fmtBytes(summary.total.downTotal())}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onPrimaryContainer.copy(alpha = 0.62f),
            )
        }
    }
}

/** 최근 30일 막대 — 받기(primary) / 올리기(tertiary) */
@Composable
private fun MonthBarsCard(days: List<TrafficDay>) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근 30일", style = MaterialTheme.typography.titleMedium, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("■", color = cs.primary, style = MaterialTheme.typography.labelSmall)
                    Text(" 받기  ", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text("■", color = cs.tertiary, style = MaterialTheme.typography.labelSmall)
                    Text(" 올리기", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            TrafficBars(days)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(days.firstOrNull(), days.getOrNull(days.size / 2), days.lastOrNull())
                    .mapNotNull { it?.date?.takeLast(5) }
                    .forEach {
                        Text(it, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
            }
        }
    }
}

/** 핵심 4지표 — 2×2 타일 */
@Composable
private fun StatTileGrid(summary: TrafficSummary, h: TrafficHighlights) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "최고 속도",
                if (summary.total.maxDownBps > 0) "${fmtBytes(summary.total.maxDownBps)}/s" else "—",
                if (summary.total.maxDownBps > 0) cs.primary else cs.onSurfaceVariant,
                Modifier.weight(1f),
            )
            StatTile("일평균", fmtBytes(h.weekAvgDown), cs.onSurface, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("완료", "${summary.total.doneTotal()}건", cs.onSurface, Modifier.weight(1f))
            StatTile(
                "실패",
                "${summary.total.failCount}건",
                if (summary.total.failCount > 0) cs.error else cs.onSurfaceVariant,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** 월 예상 — 지금 속도가 유지되면 이번 달 총량 */
@Composable
private fun ForecastCard(summary: TrafficSummary, h: TrafficHighlights) {
    val cs = MaterialTheme.colorScheme
    val month = summary.month.downTotal()
    val forecast = h.monthForecastDown
    val ratio = if (forecast > 0) (month.toFloat() / forecast).coerceIn(0f, 1f) else 0f
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("월 예상", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    "${fmtBytes(month)} / ${fmtBytes(forecast)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            DrProgressBar(
                progress = ratio,
                modifier = Modifier.fillMaxWidth(),
                height = 6.dp,
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (forecast > 0) "이 속도면 이번 달 ${fmtBytes(forecast)}" else "표본이 쌓이면 예상치를 계산합니다",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordCard(
    peakDownLine: String,
    peakUpLine: String,
    doneLine: String,
    failLine: String,
    peerLine: String,
    bootLine: String,
    netLine: String,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("📈 최고속도 · 완료 · 기기", style = MaterialTheme.typography.titleMedium, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            HlRow("최고 다운로드 속도 (누적)", peakDownLine)
            HlRow("최고 업로드 속도 (누적)", peakUpLine)
            HlRow("완료 건수", doneLine)
            HlRow("실패 건수", failLine)
            HlRow("피어", peerLine)
            HlRow("가동", bootLine)
            HlRow("네트워크/가드", netLine)
        }
    }
}

@Composable
private fun HighlightCard(
    bestLine: String,
    top3Line: String,
    avgForecastLine: String,
    shareLine: String,
    streakLine: String,
    mixLine: String,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("✨ 하이라이트", style = MaterialTheme.typography.titleMedium, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            HlRow("최다 다운로드일", bestLine)
            HlRow("Top3", top3Line)
            HlRow("주간평균 / 월예측", avgForecastLine)
            HlRow("공유비율 전체/토렌트", shareLine)
            HlRow("연속 / 최장 / 활성30일", streakLine)
            HlRow("타입 비중", mixLine)
        }
    }
}

@Composable
private fun HlRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, softWrap = true)
    }
}

@Composable
private fun TrafficBars(days: List<TrafficDay>) {
    val cs = MaterialTheme.colorScheme
    val downColor = cs.primary
    val upColor = cs.tertiary
    val maxV = days.maxOfOrNull { maxOf(it.downTotal(), it.upTotal()) }?.coerceAtLeast(1L) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = days.size.coerceAtLeast(1)
        val slotW = size.width / n
        val barW = (slotW * 0.5f).coerceAtMost(16f)
        days.forEachIndexed { i, d ->
            val cx = slotW * i + slotW / 2f
            val dh = (d.downTotal().toFloat() / maxV) * (size.height - 20f)
            val uh = (d.upTotal().toFloat() / maxV) * (size.height - 20f)
            drawRect(
                downColor,
                topLeft = Offset(cx - barW - 1f, size.height - dh),
                size = Size(barW, dh.coerceAtLeast(if (d.downTotal() > 0) 2f else 0f)),
            )
            drawRect(
                upColor,
                topLeft = Offset(cx + 1f, size.height - uh),
                size = Size(barW, uh.coerceAtLeast(if (d.upTotal() > 0) 2f else 0f)),
            )
        }
    }
}
