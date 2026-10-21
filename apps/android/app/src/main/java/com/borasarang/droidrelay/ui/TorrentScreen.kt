package com.borasarang.droidrelay.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.borasarang.droidrelay.relay.TorznabClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.SpeedLimits
import com.borasarang.droidrelay.relay.TorrentJob
import com.borasarang.droidrelay.relay.TorrentRepository
import com.borasarang.droidrelay.relay.TorrentState

/** 일부 파일 관리자는 .torrent 를 octet-stream 으로 노출한다. */
private val TORRENT_MIME_TYPES = arrayOf("application/x-bittorrent", "application/octet-stream")

@Composable
fun TorrentScreen(onShowSnack: (String) -> Unit = {}) {
    val context = LocalContext.current
    val engine = remember { RelayApp.getTorrent(context) }
    val torrents by TorrentRepository.torrents.collectAsState()

    var showMagnetDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<TorrentJob?>(null) }
    // 토렌트 검색 (T-950) — 검색 서버 미설정 시 검색행 대신 설정 안내 (웹 표시 조건과 동일)
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TorznabClient.Result>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val settingsRepo = remember { com.borasarang.droidrelay.relay.SettingsRepository.get(context) }
    val settings by settingsRepo.settings.collectAsState(initial = null)
    val searchEnabled = settings?.searchEnabled == true && settings?.searchUrl?.isNotBlank() == true
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    // 크기 상한 25MB — 대용량 readBytes OOM 방지
                    val size = context.contentResolver.openFileDescriptor(it, "r")?.use { pfd -> pfd.statSize } ?: -1L
                    if (size > 25 * 1024 * 1024) {
                        withContext(Dispatchers.Main) { onShowSnack("torrent 파일이 너무 큽니다") }
                        return@launch
                    }
                    val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@launch
                    val filename = it.lastPathSegment?.substringAfterLast('/') ?: "torrent"
                    engine.addTorrentFile(bytes, filename)
                    withContext(Dispatchers.Main) { onShowSnack("torrent 파일 추가됨: $filename") }
                } catch (e: Exception) {
                    DebugLogger.e("TorrentUI", "torrent 파일 읽기 실패", e)
                    withContext(Dispatchers.Main) { onShowSnack("torrent 파일 읽기 실패") }
                }
            }
        }
    }

    // 데드 1초 폴링 제거 — TorrentRepository Flow가 상태 갱신을 푸시하므로 리컴포지션 유도 불필요

    Box(Modifier.fillMaxSize()) {
        if (torrents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Torrent가 없습니다", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("magnet 링크 또는 .torrent 파일을 추가하세요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showMagnetDialog = true }) {
                            Icon(Icons.Outlined.Link, null)
                            Spacer(Modifier.width(8.dp))
                            Text("magnet")
                        }
                        OutlinedButton(onClick = { filePicker.launch(TORRENT_MIME_TYPES) }) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(".torrent")
                        }
                    }
                }
            }
        } else {
            // 웹 #torrentInfo와 동일 요약줄 — 합계 속도·진행·시드/피어
            val dlSum = torrents.sumOf { it.downloadSpeed }
            val upSum = torrents.sumOf { it.uploadSpeed }
            val activeCount = torrents.count {
                it.state == TorrentState.DOWNLOADING || it.state == TorrentState.SEEDING ||
                    it.state == TorrentState.FETCHING_METADATA
            }
            val seedSum = torrents.sumOf { it.seeds }
            val peerSum = torrents.sumOf { it.peers }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    TorrentSummary(dlSum, upSum, seedSum, peerSum, activeCount, torrents.size)
                }
                // 검색을 끄면 검색행·안내 카드 모두 숨긴다 (사용자 요청 — 설정에서 끈 기능은 화면에 남기지 않음)
                if (searchEnabled) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("토렌트 검색") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (searchQuery.isBlank() || searching) return@Button
                                    searching = true
                                    scope.launch(Dispatchers.IO) {
                                        val res = runCatching { engine.search(searchQuery.trim()) }
                                        withContext(Dispatchers.Main) {
                                            searching = false
                                            res.onSuccess {
                                                searchResults = it
                                                if (it.isEmpty()) onShowSnack("검색 결과 없음")
                                            }.onFailure { e ->
                                                DebugLogger.e("TorrentUI", "검색 실패", e)
                                                onShowSnack("검색 실패: ${e.message}")
                                            }
                                        }
                                    }
                                },
                                enabled = !searching,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Icon(Icons.Outlined.Search, "검색")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "⚡다운 합계 ${fmtSpeedT(dlSum)} · ▲${fmtSpeedT(upSum)} · 진행 ${activeCount}건 · " +
                            "시드 $seedSum 피어 $peerSum",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                searchResults?.let { results ->
                    items(results) { r ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${fmtSize(r.size)} · 시드 ${r.seeders}${if (r.indexer.isNotBlank()) " · ${r.indexer}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val ok = runCatching {
                                            if (r.magnet != null) engine.addMagnet(r.magnet)
                                            else engine.addTorrentUrl(r.url!!)
                                        }
                                        withContext(Dispatchers.Main) {
                                            ok.onSuccess { onShowSnack("토렌트 추가됨") }
                                                .onFailure { e -> onShowSnack("추가 실패: ${e.message}") }
                                        }
                                    }
                                }) {
                                    Text("받기")
                                }
                            }
                        }
                    }
                }
                items(torrents, key = { it.id }) { job ->
                    TorrentItem(
                        job = job,
                        // 이 연산들은 전부 sessionGate 를 잡고 libtorrent 에 JNI 를 건다.
                        // onClick 는 메인 스레드에서 실행되므로 동기 호출이면
                        // 폴러/alert 가 게이트를 쥔 동안 UI 가 멈춘다 (ANR).
                        // 전부 IO 스레드에서 실행한다.
                        onResume = { scope.launch {
                            val ok = withContext(Dispatchers.IO) { engine.resume(job.id) }
                            if (!ok) onShowSnack("재개 실패 — 앱 완전 종료 후 다시 시작하세요")
                        } },
                        onDelete = { showDeleteDialog = job },
                        onSelectFiles = { sel ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { engine.setFileSelection(job.id, sel) }
                                if (ok) onShowSnack("파일 선택 적용됨 (${sel.size}/${job.files.size}개)")
                                else onShowSnack("파일 목록 없음 — 메타데이터 수신 후 시도")
                            }
                        },
                        onSetLimit = { bps ->
                            scope.launch { withContext(Dispatchers.IO) { engine.setDownloadLimit(job.id, bps) } }
                            onShowSnack("torrent 다운로드 제한 ${SpeedLimits.labelBps(bps)}")
                        },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // 목록이 있을 때만 FAB — 빈 화면은 중앙의 magnet/.torrent 버튼이 추가 동선
        if (torrents.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("추가") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (showAddDialog) {
        AddTorrentDialog(
            onDismiss = { showAddDialog = false },
            onMagnet = {
                showAddDialog = false
                showMagnetDialog = true
            },
            onFile = {
                showAddDialog = false
                filePicker.launch(TORRENT_MIME_TYPES)
            },
        )
    }

    if (showMagnetDialog) {
        MagnetInputDialog(
            onDismiss = { showMagnetDialog = false },
            onConfirm = { magnet ->
                // addMagnet은 토렌트 OFF·형식 불량 시 throw — UI 스레드 미처리 예외가 곧 크래시이므로 스낵바로 전환
                runCatching { engine.addMagnet(magnet) }
                    .onSuccess {
                        onShowSnack("magnet 추가됨")
                        showMagnetDialog = false
                    }
                    .onFailure { e ->
                        DebugLogger.e("TorrentUI", "magnet 추가 실패", e)
                        onShowSnack("magnet 추가 실패: ${e.message}")
                    }
            },
        )
    }

    showDeleteDialog?.let { job ->
        val complete = job.state == TorrentState.DONE || job.state == TorrentState.SEEDING
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("torrent 삭제") },
            text = {
                Text(
                    "'${job.name}' torrent를 목록에서 삭제할까요?" +
                        if (complete) "\n받은 파일은 보관함에 유지됩니다." else "\n다운로드 중이던 파일도 함께 삭제됩니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.cancel(job.id)
                    showDeleteDialog = null
                }) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
private fun TorrentItem(
    job: TorrentJob,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onSelectFiles: (Set<Int>) -> Unit,
    onSetLimit: (Long) -> Unit,
) {
    val now = System.currentTimeMillis()
    var filesExpanded by remember(job.id) { mutableStateOf(false) }
    var showLimitDialog by remember(job.id) { mutableStateOf(false) }

    // 상태+ETA 한 줄 (웹 label + 경과·남음과 동일).
    // 받은 게 0이면 오버헤드 속도에서 계산한 허위 ETA(수십 시간)를 숨긴다.
    val etaLine = if (job.state == TorrentState.DOWNLOADING && job.downloadSpeed > 0 &&
        (job.downloadedSize > 0 || job.progress > 0)
    ) {
        val etaParts = mutableListOf<String>()
        if (job.startedAt > 0) etaParts.add("${fmtDurationT((now - job.startedAt) / 1000)} 경과")
        if (job.totalSize > 0 && job.downloadedSize < job.totalSize) {
            etaParts.add("${fmtDurationT((job.totalSize - job.downloadedSize) / job.downloadSpeed)} 남음")
        }
        if (etaParts.isNotEmpty()) " · " + etaParts.joinToString(" · ") else ""
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stateLabel(job) + etaLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor(job.state),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 무조건 시도 원칙: 멈춤 버튼 없음 — 멈추는 길은 삭제뿐.
                // 엔진이 미완료 항목을 스스로 살려내므로 수동 재개는 보조 수단이다.
                if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED ||
                    job.state == TorrentState.STALLED || job.state == TorrentState.QUEUED
                ) {
                    IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.PlayArrow, "재개", modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, "삭제", modifier = Modifier.size(20.dp))
                }
            }
            // 제한 + 파일선택 한 줄 (웹 작업별 제한 select와 동일)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "제한:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(
                    onClick = { showLimitDialog = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(SpeedLimits.labelBps(job.downloadLimit), style = MaterialTheme.typography.labelSmall)
                }
                if (job.files.isNotEmpty()) {
                    TextButton(
                        onClick = { filesExpanded = !filesExpanded },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "파일 ${job.files.size}개 (${job.files.count { it.selected }}개 선택)${if (filesExpanded) " ▾" else " ▸"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (job.totalSize > 0) {
                DrProgressBar(
                    progress = job.progress,
                    modifier = Modifier.fillMaxWidth(),
                    height = 4.dp,
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${fmtSize(job.downloadedSize)} / ${fmtSize(job.totalSize)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    // QUEUED/PAUSED의 미미한 속도(피어 신호 수준 오버헤드)는 실제 전송이 아니므로 숨긴다
                    val idleHold = job.state == TorrentState.QUEUED || job.state == TorrentState.PAUSED
                    Text(
                        if (idleHold) {
                            if (job.seeds > 0 || job.peers > 0) "시드 ${job.seeds} · 피어 ${job.peers}" else "대기 중"
                        } else {
                            "${fmtSpeedT(job.downloadSpeed)} ↓ / ${fmtSpeedT(job.uploadSpeed)} ↑" +
                                if (job.seeds > 0 || job.peers > 0) " · 시드 ${job.seeds} · 피어 ${job.peers}" else ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (job.seeds > 0 || job.peers > 0) {
                Text(
                    "시드 ${job.seeds} · 피어 ${job.peers}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (filesExpanded && job.files.isNotEmpty()) {
                Column {
                    job.files.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = f.selected,
                                onCheckedChange = { checked ->
                                    onSelectFiles(
                                        job.files.filter { if (it.index == f.index) checked else it.selected }
                                            .map { it.index }.toSet(),
                                    )
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    fmtSize(f.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showLimitDialog) {
        AlertDialog(
            onDismissRequest = { showLimitDialog = false },
            title = { Text("torrent 다운로드 제한") },
            text = {
                Column {
                    SpeedLimits.bpsOptions().forEach { (bps, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSetLimit(bps)
                                showLimitDialog = false
                            },
                        ) {
                            RadioButton(
                                selected = job.downloadLimit == bps,
                                onClick = {
                                    onSetLimit(bps)
                                    showLimitDialog = false
                                },
                            )
                            Text(name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLimitDialog = false }) { Text("닫기") }
            },
        )
    }
}

@Composable
private fun AddTorrentDialog(
    onDismiss: () -> Unit,
    onMagnet: () -> Unit,
    onFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("토렌트 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMagnet, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("magnet 링크")
                }
                OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(".torrent 파일")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        },
    )
}

@Composable
private fun MagnetInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var magnet by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("magnet 링크 추가") },
        text = {
            OutlinedTextField(
                value = magnet,
                onValueChange = { magnet = it },
                label = { Text("magnet:?xt=...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(magnet) },
                enabled = magnet.startsWith("magnet:"),
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

/** 상단 3분할 요약 — ↓/↑ 속도와 시드·피어 (v0.59) */
@Composable
private fun TorrentSummary(
    downBps: Long,
    upBps: Long,
    seeds: Int,
    peers: Int,
    active: Int,
    total: Int,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "활성 $active / 전체 $total",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSecondaryContainer.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                SummaryCell("내려받기", fmtSpeedT(downBps), Modifier.weight(1f))
                SummaryCell("올리기", fmtSpeedT(upBps), Modifier.weight(1f))
                SummaryCell("시드 / 피어", "$seeds / $peers", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSecondaryContainer.copy(alpha = 0.68f),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSecondaryContainer,
        )
    }
}

private fun stateLabel(job: TorrentJob): String = when (job.state) {
    TorrentState.QUEUED -> "대기 중"
    TorrentState.FETCHING_METADATA -> "메타데이터 수신 중"
    // 메타데이터는 왔으나(속도 표시) 받은 조각이 아직 없으면 받는 중으로 보이지 않게
    TorrentState.DOWNLOADING ->
        if (job.name == com.borasarang.droidrelay.relay.METADATA_PLACEHOLDER) "메타데이터 받는 중"
        else "다운로드 중"
    TorrentState.SEEDING -> "시딩 중"
    TorrentState.PAUSED -> "일시정지"
    TorrentState.STALLED -> "정체 — 다음 torrent로 전환"
    TorrentState.FAILED -> "실패: ${job.errorMessage ?: "알 수 없음"}"
    TorrentState.DONE -> "완료"
}

@Composable
private fun stateColor(state: TorrentState) = when (state) {
    TorrentState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    TorrentState.SEEDING -> MaterialTheme.colorScheme.tertiary
    TorrentState.PAUSED -> MaterialTheme.colorScheme.outline
    TorrentState.STALLED -> MaterialTheme.colorScheme.secondary
    TorrentState.FAILED -> MaterialTheme.colorScheme.error
    TorrentState.DONE -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fmtSize(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024}KB"
    n < 1_073_741_824 -> String.format("%.1fMB", n / 1_048_576.0)
    else -> String.format("%.2fGB", n / 1_073_741_824.0)
}

private fun fmtSpeedT(bps: Long): String = when {
    bps < 1_024 -> "${bps}B/s"
    bps < 1_048_576 -> "${bps / 1024}KB/s"
    else -> String.format("%.1fMB/s", bps / 1_048_576.0)
}

private fun fmtDurationT(seconds: Long): String {
    if (seconds < 0) return ""
    val sec = seconds.toInt()
    return when {
        sec < 60 -> "${sec}초"
        sec < 3600 -> "${sec / 60}분 ${sec % 60}초"
        else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
    }
}
