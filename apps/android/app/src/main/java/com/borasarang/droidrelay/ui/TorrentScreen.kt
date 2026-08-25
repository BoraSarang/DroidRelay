package com.borasarang.droidrelay.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.TorrentJob
import com.borasarang.droidrelay.relay.TorrentRepository
import com.borasarang.droidrelay.relay.TorrentState
import kotlinx.coroutines.launch

@Composable
fun TorrentScreen() {
    val context = LocalContext.current
    val engine = remember { RelayApp.getTorrent(context) }
    val torrents by TorrentRepository.torrents.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMagnetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<TorrentJob?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
                val filename = it.lastPathSegment?.substringAfterLast('/') ?: "torrent"
                engine.addTorrentFile(bytes, filename)
                scope.launch { snackbarHostState.showSnackbar("torrent 파일 추가됨: $filename") }
            } catch (e: Exception) {
                DebugLogger.e("TorrentUI", "torrent 파일 읽기 실패", e)
                scope.launch { snackbarHostState.showSnackbar("torrent 파일 읽기 실패") }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            // 강제 리컴포지션 유도 (상태 갱신 반영)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column {
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (torrents.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
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
                Modifier.fillMaxSize().padding(inner),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(torrents, key = { it.id }) { job ->
                    TorrentItem(
                        job = job,
                        onPause = { engine.pause(job.id) },
                        onResume = { engine.resume(job.id) },
                        onDelete = { showDeleteDialog = job },
                    )
                }
            }
        }
    }

    if (showMagnetDialog) {
        MagnetInputDialog(
            onDismiss = { showMagnetDialog = false },
            onConfirm = { magnet ->
                engine.addMagnet(magnet)
                scope.launch { snackbarHostState.showSnackbar("magnet 추가됨") }
                showMagnetDialog = false
            },
        )
    }

    showDeleteDialog?.let { job ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("torrent 삭제") },
            text = { Text("'${job.name}' torrent를 삭제할까요?") },
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
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
                }
                if (job.state == TorrentState.DOWNLOADING || job.state == TorrentState.FETCHING_METADATA) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Filled.Pause, "일시정지")
                    }
                } else if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, "재개")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "삭제")
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
        }
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
    TorrentState.FAILED -> "실패: ${job.errorMessage ?: "알 수 없음"}"
    TorrentState.DONE -> "완료"
}

@Composable
private fun stateColor(state: TorrentState) = when (state) {
    TorrentState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    TorrentState.SEEDING -> MaterialTheme.colorScheme.tertiary
    TorrentState.PAUSED -> MaterialTheme.colorScheme.outline
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
