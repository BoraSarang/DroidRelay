package com.borasarang.droidrelay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SettingsRepository
import kotlinx.coroutines.launch

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, enabled = enabled, onCheckedChange = {
            DebugLogger.i("Settings", "$label → $it")
            onChange(it)
        })
    }
}

/** 속도 제한 프리셋 선택 (KB/s, T-1050) — 앱 레벨/전역/토렌트 기본이 같은 SpeedLimits 목록 사용 */
@Composable
internal fun SpeedSelectRow(
    label: String,
    value: Int,
    onSelect: (Int) -> Unit,
    options: List<Pair<Int, String>> = com.borasarang.droidrelay.relay.SpeedLimits.kbpsOptions(value),
) {
    var show by remember { mutableStateOf(false) }
    SpeedPresetRow(label, options.firstOrNull { it.first == value }?.second ?: "${value}KB/s") { show = true }
    if (show) {
        SpeedPresetDialog(label, options, value, onSelect = {
            DebugLogger.i("Settings", "$label → ${if (it <= 0) "무제한" else "${it}KB/s"}")
            onSelect(it)
            show = false
        }, onDismiss = { show = false })
    }
}

/** 속도 제한 프리셋 선택 (B/s, T-1050) — 전역 다운로드/토렌트 개별 제한용 */
@Composable
internal fun SpeedSelectBpsRow(
    label: String,
    value: Long,
    onSelect: (Long) -> Unit,
    options: List<Pair<Long, String>> = com.borasarang.droidrelay.relay.SpeedLimits.bpsOptions(),
) {
    var show by remember { mutableStateOf(false) }
    SpeedPresetRow(label, options.firstOrNull { it.first == value }?.second
        ?: com.borasarang.droidrelay.relay.SpeedLimits.labelBps(value)) { show = true }
    if (show) {
        SpeedPresetDialog(label, options, value, onSelect = {
            DebugLogger.i("Settings", "$label → ${com.borasarang.droidrelay.relay.SpeedLimits.labelBps(it)}")
            onSelect(it)
            show = false
        }, onDismiss = { show = false })
    }
}

@Composable
private fun SpeedPresetRow(label: String, valueLabel: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(onClick = onClick) { Text(valueLabel) }
    }
}

@Composable
private fun <T> SpeedPresetDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(key) },
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == key,
                            onClick = { onSelect(key) },
                        )
                        Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

internal fun resetSettings(ctx: android.content.Context, repo: SettingsRepository, category: String) {
    DebugLogger.w("Settings", "설정 기본값 복원 진행 category=$category")
    val scope = kotlinx.coroutines.MainScope()
    when (category) {
        "download" -> scope.launch {
            repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
            repo.setSpeedLimit(0)
            repo.setNotifications(true)
            repo.setSpeedSchedule(emptyList())
            repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE)
            RelayApp.get(ctx).applySettings(repo.firstBlocking())
        }
        "torrent" -> scope.launch {
            repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
            repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
            repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
            repo.setTorrentSeedRatio(2.0f)
            repo.setTorrentDhtEnabled(true)
            repo.setTorrentPexEnabled(true)
            repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
                repo.setTorrentSavePath(com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path)
            repo.setTorrentStallEnabled(SettingsConstraints.DEFAULT_TORRENT_STALL_ENABLED)
            repo.setTorrentStallThresholdKbps(SettingsConstraints.DEFAULT_TORRENT_STALL_THRESHOLD_KBPS)
            repo.setTorrentStallTimeoutSec(SettingsConstraints.DEFAULT_TORRENT_STALL_TIMEOUT_SEC)
            RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
        }
        "all" -> {
            scope.launch {
                repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
                repo.setSpeedLimit(0)
                repo.setNotifications(true)
                repo.setSpeedSchedule(emptyList())
                repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE)
                RelayApp.get(ctx).applySettings(repo.firstBlocking())
            }
            scope.launch {
                repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
                repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
                repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
                repo.setTorrentSeedRatio(2.0f)
                repo.setTorrentDhtEnabled(true)
                repo.setTorrentPexEnabled(true)
                repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
                repo.setTorrentSavePath(com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path)
                repo.setTorrentStallEnabled(SettingsConstraints.DEFAULT_TORRENT_STALL_ENABLED)
                repo.setTorrentStallThresholdKbps(SettingsConstraints.DEFAULT_TORRENT_STALL_THRESHOLD_KBPS)
                repo.setTorrentStallTimeoutSec(SettingsConstraints.DEFAULT_TORRENT_STALL_TIMEOUT_SEC)
                RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
            }
        }
    }
}

/** 트래커 도달성 측정 행 (v0.26) */
@Composable
internal fun TrackerProbeRow() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("확인 중…") }
    fun refresh() {
        scope.launch {
            status = runCatching {
                val engine = com.borasarang.droidrelay.relay.RelayApp.getTorrent(ctx)
                val cached = com.borasarang.droidrelay.relay.TrackerProbe.getProbeCached(ctx)
                val ok = cached.values.count { it.result.reachable }
                if (engine.probingTrackers) "측정 중…"
                else if (cached.isEmpty()) "미측정"
                else "도달 $ok/${cached.size}개"
            }.getOrDefault("확인 실패")
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(status, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            status = "측정 시작됨"
            DebugLogger.i("Settings", "[FEATURE] 트래커 프로브 수동 시작")
            scope.launch {
                com.borasarang.droidrelay.relay.RelayApp.getTorrent(ctx).probeTrackers()
                kotlinx.coroutines.delay(6000)
                refresh()
            }
        }) { Text("도달 측정") }
    }
}

/** 속도 스케줄 목록 + 추가 다이얼로그 (v0.24) */
@Composable
internal fun SpeedScheduleSection(windows: List<com.borasarang.droidrelay.relay.SpeedWindow>) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    val dayNames = mapOf(1 to "일", 2 to "월", 3 to "화", 4 to "수", 5 to "목", 6 to "금", 7 to "토")

    fun save(next: List<com.borasarang.droidrelay.relay.SpeedWindow>) {
        scope.launch { repo.setSpeedSchedule(next) }
    }

    Text("속도 스케줄 (요일+시간)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    if (windows.isEmpty()) {
        Text("등록된 스케줄 없음", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
    windows.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    w.days.sorted().map { dayNames[it] ?: "?" }.joinToString(",") +
                        " ${w.startMin / 60}:${(w.startMin % 60).toString().padStart(2, '0')}" +
                        "~${w.endMin / 60}:${(w.endMin % 60).toString().padStart(2, '0')}" +
                        " ↓${w.downKbps}KB/s ↑${w.upKbps}KB/s",
                    color = cs.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = w.enabled, onCheckedChange = { on ->
                save(windows.map { if (it.id == w.id) it.copy(enabled = on) else it })
            })
            TextButton(onClick = { save(windows.filter { it.id != w.id }) }) { Text("삭제") }
        }
    }
    OutlinedButton(onClick = { showAdd = true }, enabled = windows.size < 10) { Text("+ 스케줄 추가 (최대 10개)") }

    if (showAdd) {
        var days by remember { mutableStateOf(setOf(2, 3, 4, 5, 6)) }
        var start by remember { mutableStateOf("00:00") }
        var end by remember { mutableStateOf("06:00") }
        var down by remember { mutableStateOf("0") }
        var up by remember { mutableStateOf("0") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("스케줄 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { d ->
                            androidx.compose.material3.FilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days) days - d else days + d },
                                label = { Text(dayNames[d] ?: "?", fontSize = 12.sp) },
                            )
                        }
                    }
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("시작 HH:MM") }, singleLine = true)
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("종료 HH:MM") }, singleLine = true)
                    OutlinedTextField(
                        value = down,
                        onValueChange = { down = it.filter { c -> c.isDigit() } },
                        label = { Text("다운 KB/s (0=무제한)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = up,
                        onValueChange = { up = it.filter { c -> c.isDigit() } },
                        label = { Text("업 KB/s (0=무제한)") },
                        singleLine = true,
                    )
                    err?.let { Text(it, color = cs.error, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fun toMin(v: String): Int? {
                        val p = v.split(":")
                        if (p.size != 2) return null
                        val h = p[0].toIntOrNull() ?: return null
                        val m = p[1].toIntOrNull() ?: return null
                        if (h !in 0..23 || m !in 0..59) return null
                        return h * 60 + m
                    }
                    val s = toMin(start)
                    val e = toMin(end)
                    val w = com.borasarang.droidrelay.relay.SpeedWindow(
                        id = "w" + System.currentTimeMillis(),
                        enabled = true,
                        days = days,
                        startMin = s ?: -1,
                        endMin = e ?: -1,
                        downKbps = down.toLongOrNull() ?: -1,
                        upKbps = up.toLongOrNull() ?: -1,
                    )
                    if (!com.borasarang.droidrelay.relay.SpeedSchedule.isValid(w)) {
                        err = "요일·시간·속도를 확인해 주세요"
                    } else {
                        DebugLogger.i("Settings", "[FEATURE] 속도스케줄 추가 id=${w.id}")
                        save(windows + w)
                        showAdd = false
                    }
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("취소") } },
        )
    }
}
