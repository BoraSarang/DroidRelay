package com.borasarang.droidrelay.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

@Composable
fun TorrentScreen(onShowSnack: (String) -> Unit = {}) {
    val context = LocalContext.current
    val engine = remember { RelayApp.getTorrent(context) }
    val torrents by TorrentRepository.torrents.collectAsState()

    var showMagnetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<TorrentJob?>(null) }
    // 토렌트 검색 (T-950)
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<TorznabClient.Result>?>(null) }
    var searching by remember { mutableStateOf(false) }
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
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Torrent가 없습니다", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("magnet 링크 또는 .torrent 파일을 추가하세요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                            Icon(Icons.Filled.Search, "검색")
                        }
                    }
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
                        onPause = { engine.pause(job.id) },
                        onResume = { engine.resume(job.id) },
                        onDelete = { showDeleteDialog = job },
                        onMoveUp = { engine.reorder(job.id, -1) },
                        onMoveDown = { engine.reorder(job.id, 1) },
                        onSelectFiles = { sel ->
                            if (engine.setFileSelection(job.id, sel)) onShowSnack("파일 선택 적용됨 (${sel.size}/${job.files.size}개)")
                            else onShowSnack("파일 목록 없음 — 메타데이터 수신 후 시도")
                        },
                        onSetLimit = { bps ->
                            engine.setDownloadLimit(job.id, bps)
                            onShowSnack("torrent 다운로드 제한 ${SpeedLimits.labelBps(bps)}")
                        },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            FloatingActionButton(
                onClick = { showMagnetDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Filled.Link, "magnet 추가")
            }
            Spacer(Modifier.height(12.dp))
            FloatingActionButton(
                onClick = { filePicker.launch(arrayOf("application/x-bittorrent")) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(Icons.Filled.Add, "파일 추가")
            }
        }
    }

    if (showMagnetDialog) {
        MagnetInputDialog(
            onDismiss = { showMagnetDialog = false },
            onConfirm = { magnet ->
                engine.addMagnet(magnet)
                onShowSnack("magnet 추가됨")
                showMagnetDialog = false
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
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSelectFiles: (Set<Int>) -> Unit,
    onSetLimit: (Long) -> Unit,
) {
    val now = System.currentTimeMillis()
    var filesExpanded by remember(job.id) { mutableStateOf(false) }
    var showLimitDialog by remember(job.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.width(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.ArrowDropUp, "위로", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.ArrowDropDown, "아래로", modifier = Modifier.size(16.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                job.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stateLabel(job),
                                style = MaterialTheme.typography.bodySmall,
                                color = stateColor(job.state),
                            )
                            if (job.state == TorrentState.DOWNLOADING && job.downloadSpeed > 0) {
                                val etaParts = mutableListOf<String>()
                                if (job.startedAt > 0) {
                                    val elapsed = (now - job.startedAt) / 1000
                                    etaParts.add("${fmtDurationT(elapsed)} 경과")
                                }
                                if (job.totalSize > 0 && job.downloadedSize < job.totalSize) {
                                    val remain = (job.totalSize - job.downloadedSize) / job.downloadSpeed
                                    etaParts.add("${fmtDurationT(remain)} 남음")
                                }
                                if (etaParts.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        etaParts.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (job.state == TorrentState.DOWNLOADING || job.state == TorrentState.FETCHING_METADATA) {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Filled.Pause, "일시정지")
                            }
                        } else if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED ||
                            job.state == TorrentState.STALLED || job.state == TorrentState.QUEUED
                        ) {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Filled.PlayArrow, "재개")
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, "삭제")
                        }
                    }
                }
            }
            // torrent 개별 다운로드 제한 (T-1050)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "다운로드 제한",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = { showLimitDialog = true }) {
                    Text(SpeedLimits.labelBps(job.downloadLimit), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (job.totalSize > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${fmtSize(job.downloadedSize)} / ${fmtSize(job.totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${fmtSpeed(job.downloadSpeed)} ↓ / ${fmtSpeed(job.uploadSpeed)} ↑",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (job.seeds > 0 || job.peers > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "시드 ${job.seeds} · 피어 ${job.peers}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 파일 선택 (T-942)
            if (job.files.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { filesExpanded = !filesExpanded }) {
                    Text("파일 ${job.files.size}개 (${job.files.count { it.selected }}개 선택)")
                }
                if (filesExpanded) {
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

private fun stateLabel(job: TorrentJob): String = when (job.state) {
    TorrentState.QUEUED -> "대기 중"
    TorrentState.FETCHING_METADATA -> "메타데이터 수신 중"
    TorrentState.DOWNLOADING -> "다운로드 중"
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

private fun fmtSpeed(bps: Long): String = when {
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
