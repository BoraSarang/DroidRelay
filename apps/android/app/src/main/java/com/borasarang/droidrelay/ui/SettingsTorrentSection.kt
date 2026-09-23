package com.borasarang.droidrelay.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.borasarang.droidrelay.relay.AccessScope
import com.borasarang.droidrelay.relay.AppSettings
import com.borasarang.droidrelay.relay.CronParser
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.DebridProvider
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.relay.ThemeMode
import com.borasarang.droidrelay.relay.TunnelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


@Composable
internal fun TorrentSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── Torrent (웹 슬라이더와 동일 단위 — SettingsConstraints 단일 진실, T-935) ──
    SettingSection("Torrent") {
        // 업로드 속도 (0 = 끔)
        Text(
            "기본 업로드 속도: ${SettingsConstraints.uploadLabel(s.torrentUploadLimit)}",
            color = cs.onSurface,
        )
        Slider(
            value = s.torrentUploadLimit.toFloat(),
            onValueChange = { v ->
                val kbps = (v.roundToInt() / SettingsConstraints.TORRENT_UPLOAD_STEP * SettingsConstraints.TORRENT_UPLOAD_STEP)
                    .coerceIn(SettingsConstraints.TORRENT_UPLOAD_MIN, SettingsConstraints.TORRENT_UPLOAD_MAX)
                scope.launch { repo.setTorrentUploadLimit(kbps) }
            },
            valueRange = SettingsConstraints.TORRENT_UPLOAD_MIN.toFloat()..SettingsConstraints.TORRENT_UPLOAD_MAX.toFloat(),
            steps = SettingsConstraints.TORRENT_UPLOAD_MAX / SettingsConstraints.TORRENT_UPLOAD_STEP - 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("끔", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("1M", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))

        // 다운로드 속도 (0 = 무제한)
        Text(
            "기본 다운로드 속도: ${SettingsConstraints.downloadLabel(s.torrentDownloadLimit)}",
            color = cs.onSurface,
        )
        Slider(
            value = s.torrentDownloadLimit.toFloat(),
            onValueChange = { v ->
                val kbps = (v.roundToInt() / SettingsConstraints.TORRENT_DOWNLOAD_STEP * SettingsConstraints.TORRENT_DOWNLOAD_STEP)
                    .coerceIn(SettingsConstraints.TORRENT_DOWNLOAD_MIN, SettingsConstraints.TORRENT_DOWNLOAD_MAX)
                scope.launch { repo.setTorrentDownloadLimit(kbps) }
            },
            valueRange = SettingsConstraints.TORRENT_DOWNLOAD_MIN.toFloat()..SettingsConstraints.TORRENT_DOWNLOAD_MAX.toFloat(),
            steps = SettingsConstraints.TORRENT_DOWNLOAD_MAX / SettingsConstraints.TORRENT_DOWNLOAD_STEP - 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("무제한", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("20M", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }

        // 최대 활성 torrent
        Text("최대 활성 torrent: ${s.torrentMaxActive}개", color = cs.onSurface)
        Slider(
            value = s.torrentMaxActive.toFloat(),
            onValueChange = { scope.launch { repo.setTorrentMaxActive(it.toInt()) } },
            valueRange = SettingsConstraints.TORRENT_MAX_ACTIVE_MIN.toFloat()..SettingsConstraints.TORRENT_MAX_ACTIVE_MAX.toFloat(),
            steps = SettingsConstraints.TORRENT_MAX_ACTIVE_MAX - SettingsConstraints.TORRENT_MAX_ACTIVE_MIN - 1,
        )

        // 시드 ratio
        Text("최대 시드 ratio: ${String.format("%.1f", s.torrentSeedRatio)}", color = cs.onSurface)
        Text(
            "받은 양 대비 업로드 비율 도달 시 자동 일시정지 · 0 = 제한 없음",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = s.torrentSeedRatio,
            onValueChange = { scope.launch { repo.setTorrentSeedRatio(it) } },
            valueRange = 0f..10f,
            steps = 9,
        )
        OutlinedButton(onClick = {
            DebugLogger.i("Settings", "업로드 최소화 프리셋 적용")
            scope.launch {
                repo.setTorrentSeedRatio(0.5f)
                repo.setTorrentUploadLimit(32)
                repo.setTorrentDhtEnabled(false)
            }
        }) { Text("⬇ 업로드 최소화 (비율 0.5 + 업로드 32KB/s + DHT 끔)") }

        SwitchRow("DHT (분산 해시 테이블)", s.torrentDhtEnabled) { v -> scope.launch { repo.setTorrentDhtEnabled(v) } }
        SwitchRow("PEX (피어 교환)", s.torrentPexEnabled) { v -> scope.launch { repo.setTorrentPexEnabled(v) } }
        Text(
            "PEX는 libtorrent에 on/off가 없어 항상 켜짐. 피어 탐색용으로 트래픽은 미미합니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(16.dp))
        Text("고급", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        SwitchRow("시퀀셜 다운로드 (스트리밍 프리뷰)", s.torrentSequentialDownload) { v ->
            scope.launch { repo.setTorrentSequentialDownload(v) }
        }
        Text(
            "첫 조각부터 순서대로 받아 재생 미리보기를 지원합니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        SwitchRow("트래커 자동 동기 (24시간)", s.torrentTrackerSync) { v ->
            scope.launch { repo.setTorrentTrackerSync(v) }
        }
        Text(
            "커뮤니티 트래커 목록을 받아 새 토렌트에 자동 추가합니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        TrackerProbeRow()

        var seedWait by remember(s.torrentMinSeedWaitSec) { mutableStateOf(s.torrentMinSeedWaitSec.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = seedWait,
                onValueChange = { seedWait = it.filter { c -> c.isDigit() } },
                label = { Text("시더 부재 대기 (초, 0=끄기)") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val sec = seedWait.toIntOrNull()
                if (sec == null || sec !in 0..3600) {
                    DebugLogger.w("Settings", "시더 대기 무효 값: $seedWait")
                } else {
                    DebugLogger.i("Settings", "시더 대기 → $sec 초")
                    scope.launch { repo.setTorrentMinSeedWaitSec(sec) }
                }
            }) { Text("적용") }
        }

        var listenPort by remember(s.torrentListenPort) { mutableStateOf(s.torrentListenPort.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = listenPort,
                onValueChange = { listenPort = it.filter { c -> c.isDigit() } },
                label = { Text("리슨 포트") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val r = (1024..65535).random()
                listenPort = r.toString()
                DebugLogger.i("Settings", "리슨 포트 랜덤 → $r")
                scope.launch { repo.setTorrentListenPort(r) }
            }) { Text("랜덤") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = {
                val p = listenPort.toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    DebugLogger.w("Settings", "리슨 포트 무효 값: $listenPort")
                } else {
                    DebugLogger.i("Settings", "리슨 포트 → $p")
                    scope.launch { repo.setTorrentListenPort(p) }
                }
            }) { Text("적용") }
        }
        Text(
            "변경 시 토렌트 엔진 재시작이 필요합니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

        var savePath by remember(s.torrentSavePath) { mutableStateOf(s.torrentSavePath.ifBlank { com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path }) }
        var pathOk by remember { mutableStateOf<Boolean?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = savePath,
                onValueChange = { savePath = it; pathOk = null },
                label = { Text("저장 경로") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val dir = java.io.File(savePath)
                if (dir.exists() || dir.mkdirs()) {
                    pathOk = true
                    DebugLogger.i("Settings", "토렌트 저장 경로 → $savePath")
                    scope.launch { repo.setTorrentSavePath(savePath.trimEnd('/')) }
                } else {
                    pathOk = false
                    DebugLogger.w("Settings", "토렌트 저장 경로 사용 불가: $savePath")
                }
            }) { Text("적용") }
        }
        pathOk?.let { ok ->
            Text(
                if (ok) "저장 경로를 사용할 수 있습니다" else "저장 경로를 사용할 수 없습니다",
                color = if (ok) cs.primary else cs.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("토렌트 검색 (Jackett/Prowlarr)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        SwitchRow("검색 사용", s.searchEnabled) { v -> scope.launch { repo.setSearchEnabled(v) } }
        var searchUrl by remember(s.searchUrl) { mutableStateOf(s.searchUrl) }
        var searchKey by remember(s.searchApiKey) { mutableStateOf(s.searchApiKey) }
        OutlinedTextField(
            value = searchUrl,
            onValueChange = { searchUrl = it },
            label = { Text("서버 주소 (예: http://서버:9117)") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchKey,
                onValueChange = { searchKey = it },
                label = { Text("API 키") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                DebugLogger.i("Settings", "토렌트 검색 설정 저장")
                scope.launch {
                    repo.setSearchUrl(searchUrl)
                    repo.setSearchApiKey(searchKey)
                }
            }) { Text("저장") }
        }
    }
    
}

