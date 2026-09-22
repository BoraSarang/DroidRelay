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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.StatsSnapshots
import com.borasarang.droidrelay.relay.TrafficBucket
import com.borasarang.droidrelay.relay.TrafficDay
import com.borasarang.droidrelay.relay.TrafficHighlightsCalc
import com.borasarang.droidrelay.relay.TrafficLedger
import com.borasarang.droidrelay.relay.TrafficSummary

/** 트래픽 통계 탭 (v0.39 P2) — 원장 직접 조회, 5초 갱신, 하이라이트 + 최고속도/완료/기기 */
@Composable
fun StatsScreen() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var summary by remember { mutableStateOf(TrafficLedger.summary()) }
    var week by remember { mutableStateOf(TrafficLedger.daily(7)) }
    var snapTick by remember { mutableLongStateOf(0L) }
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
            week = TrafficLedger.daily(7)
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
        item { StatCard("오늘", summary.today) }
        item { StatCard("이번달", summary.month) }
        item { StatCard("누적", summary.total) }
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
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("최근 7일", style = MaterialTheme.typography.titleMedium, color = cs.primary)
                    Spacer(Modifier.height(8.dp))
                    WeekBars(week)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "■ 받기  ■ 보내기",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
private fun StatCard(title: String, b: TrafficBucket) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "⬇ ${fmtBytes(b.downTotal())}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "⬆ ${fmtBytes(b.upTotal())}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "받기 HTTP ${fmtBytes(b.downHttp)} · 비디오 ${fmtBytes(b.downVideo)}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                softWrap = true,
            )
            Text(
                "토렌트 ${fmtBytes(b.downTorrent)}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                softWrap = true,
            )
            Text(
                "보내기 서빙 ${fmtBytes(b.upServe)} · 토렌트 ${fmtBytes(b.upTorrent)}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                softWrap = true,
            )
        }
    }
}

@Composable
private fun WeekBars(week: List<TrafficDay>) {
    val cs = MaterialTheme.colorScheme
    val downColor = cs.primary
    val upColor = cs.tertiary
    val maxV = week.maxOfOrNull { maxOf(it.downTotal(), it.upTotal()) }?.coerceAtLeast(1L) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = week.size.coerceAtLeast(1)
        val slotW = size.width / n
        val barW = (slotW * 0.28f).coerceAtMost(28f)
        week.forEachIndexed { i, d ->
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEach { d ->
            Text(
                d.date.takeLast(5),
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                softWrap = false,
            )
        }
    }
}
