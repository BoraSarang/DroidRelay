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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.TrafficBucket
import com.borasarang.droidrelay.relay.TrafficDay
import com.borasarang.droidrelay.relay.TrafficLedger
import com.borasarang.droidrelay.relay.TrafficSummary

/** 트래픽 통계 탭 (v0.37) — 원장 직접 조회, 5초 갱신 */
@Composable
fun StatsScreen() {
    val cs = MaterialTheme.colorScheme
    var summary by remember { mutableStateOf(TrafficLedger.summary()) }
    var week by remember { mutableStateOf(TrafficLedger.daily(7)) }

    LaunchedEffect(Unit) {
        while (true) {
            summary = TrafficLedger.summary()
            week = TrafficLedger.daily(7)
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
private fun StatCard(title: String, b: TrafficBucket) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.primary)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("⬇ ${fmtBytes(b.downTotal())}", style = MaterialTheme.typography.bodyLarge)
                Text("⬆ ${fmtBytes(b.upTotal())}", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "받기 HTTP ${fmtBytes(b.downHttp)} · 비디오 ${fmtBytes(b.downVideo)} · 토렌트 ${fmtBytes(b.downTorrent)}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "보내기 서빙 ${fmtBytes(b.upServe)} · 토렌트 ${fmtBytes(b.upTorrent)}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
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
            )
        }
    }
}
