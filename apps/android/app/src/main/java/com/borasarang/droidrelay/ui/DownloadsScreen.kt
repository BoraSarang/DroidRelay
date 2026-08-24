package com.borasarang.droidrelay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.Job
import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.relay.JobsRepository
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.lanAddress
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onCopyAddress: (String) -> Unit) {
    val jobs by JobsRepository.jobs.collectAsState()
    var showPanel by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    fun onTitleTap() {
        val now = System.currentTimeMillis()
        taps = if (now - lastTapAt < 2000) taps + 1 else 1
        lastTapAt = now
        if (taps >= 5) {
            taps = 0
            showPanel = true
            DebugLogger.i("UI", "5탭 감지 → 디버그 패널 열림 (${DebugLogger.count()}줄)")
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onTitleTap() }),
        ) {
            Text("📡 DroidRelay", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            Text("제목 5연속 탭 → 디버그 로그 패널", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))

        ServerCard(onCopyAddress)
        Spacer(Modifier.height(12.dp))
        AddRow()
        Spacer(Modifier.height(8.dp))
        Text("작업 목록 (${jobs.size})", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { JobCard(it) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showPanel) {
        ModalBottomSheet(onDismissRequest = {
            showPanel = false
            DebugLogger.d("UI", "디버그 패널 닫힘")
        }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DebugPanelContent()
        }
    }
}

/** 서버 주소·QR·저장공간 카드 */
@Composable
private fun ServerCard(onCopyAddress: (String) -> Unit) {
    val ctx = LocalContext.current
    val engine = remember { RelayApp.get(ctx) }
    val ip = remember { lanAddress() }
    val addr = "http://$ip:${RelayService.PORT}"
    val cs = MaterialTheme.colorScheme

    val storage = remember {
        runCatching {
            val st = android.os.StatFs(engine.workDir.path)
            st.availableBytes to st.totalBytes
        }.getOrNull()
    }

    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("테더링 기기 접속 주소", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        addr.ifEmpty { "LAN 주소 탐지 중..." },
                        color = cs.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("DroidRelay", addr))
                        onCopyAddress(addr)
                    }) { Text("복사") }
                }
                qrBitmap(addr)?.let { bmp ->
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(96.dp))
                }
            }

            if (storage != null && storage.second > 0) {
                Spacer(Modifier.height(12.dp))
                val usedRatio = ((storage.second - storage.first).toFloat() / storage.second).coerceIn(0f, 1f)
                Text(
                    "저장공간 여유 ${fmtBytes(storage.first)} / ${fmtBytes(storage.second)}",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = cs.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddRow() {
    var text by remember { mutableStateOf("") }
    val engine = RelayApp.get(LocalContext.current)
    val cs = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("https:// 다운로드 URL", fontSize = 13.sp, color = cs.onSurfaceVariant) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.outlineVariant,
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
                cursorColor = cs.primary,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (text.isNotBlank()) {
                DebugLogger.i("UI", "추가 클릭 url=${text.trim()}")
                engine.enqueue(text.trim())
                text = ""
            }
        }, shape = MaterialTheme.shapes.small) {
            Icon(Icons.Filled.Add, "추가")
        }
    }
}

@Composable
private fun JobCard(job: Job) {
    val engine = RelayApp.get(LocalContext.current)
    val cs = MaterialTheme.colorScheme

    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.filename, color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(stateLabel(job), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                when (job.state) {
                    JobState.RUNNING -> IconButton(onClick = {
                        DebugLogger.i("UI", "일시정지 버튼 id=${job.id}")
                        engine.pause(job.id)
                    }) { Icon(Icons.Filled.Pause, "일시정지", tint = cs.onSurfaceVariant) }
                    JobState.PAUSED, JobState.FAILED -> IconButton(onClick = {
                        DebugLogger.i("UI", "재개 버튼 id=${job.id}")
                        engine.resume(job.id)
                    }) { Icon(Icons.Filled.PlayArrow, "재개", tint = cs.primary) }
                    else -> IconButton(onClick = {
                        DebugLogger.i("UI", "삭제 버튼 id=${job.id}")
                        engine.cancel(job.id)
                        JobsRepository.remove(job.id)
                    }) { Icon(Icons.Filled.Close, "삭제", tint = cs.onSurfaceVariant) }
                }
            }
            if (job.state in listOf(JobState.RUNNING, JobState.DONE, JobState.PAUSED)) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = cs.primary,
                    trackColor = cs.surfaceVariant,
                )
            }
            job.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = cs.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 디버그 패널 — 다중 선택 후 복사 (AGENTS.md 10.2 필수) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DebugPanelContent() {
    val lines = remember { mutableStateListOf<String>().apply { addAll(DebugLogger.lines().asReversed()) } }
    val selected = remember { mutableStateListOf<Int>() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    fun copyToClip(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, "$label 완료", android.widget.Toast.LENGTH_SHORT).show()
        DebugLogger.i("Panel", "$label (${text.lines().size}줄)")
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "🛠 디버그 로그 (${lines.size}줄 · 최신순)",
            color = cs.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val text = selected.sorted().mapNotNull { lines.getOrNull(it) }.joinToString("\n")
                    if (text.isNotEmpty()) copyToClip(text, "선택 복사")
                },
                enabled = selected.isNotEmpty(),
            ) { Text("선택 복사 (${selected.size})") }
            OutlinedButton(onClick = { copyToClip(DebugLogger.dump(), "전체 복사") }) { Text("전체 복사") }
            OutlinedButton(onClick = {
                DebugLogger.clear(); lines.clear(); selected.clear()
            }) { Text("비우기") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            items(lines.size) { idx ->
                val line = lines[idx]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {
                        if (idx in selected) selected.remove(idx) else selected.add(idx)
                    }),
                ) {
                    Checkbox(
                        checked = idx in selected,
                        onCheckedChange = { checked ->
                            if (checked) selected.add(idx) else selected.remove(idx)
                        },
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = when {
                            line.contains("[E]") -> cs.error
                            line.contains("[W]") -> Color(0xFFFFD59E)
                            line.contains("[I]") -> cs.onSurface
                            else -> cs.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun stateLabel(j: Job): String {
    val badge = when (j.state) {
        JobState.QUEUED -> "대기"
        JobState.RUNNING -> {
            val sp = if (j.speedBps > 0) " ⚡${j.speedBps / 1024}KB/s" else ""
            "진행 중 ${"%.0f".format(j.progress * 100)}%$sp"
        }
        JobState.PAUSED -> "일시정지"
        JobState.DONE -> "완료 ✓"
        JobState.FAILED -> "실패"
        JobState.CANCELED -> "취소됨"
    }
    return buildString {
        append(badge)
        if (j.downloadedBytes > 0) {
            append("  ·  ${fmtBytes(j.downloadedBytes)}")
            if (j.totalBytes > 0) append(" / ${fmtBytes(j.totalBytes)}")
        }
    }
}

internal fun fmtBytes(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

internal fun qrBitmap(content: String): Bitmap? {
    if (content.isBlank() || content.contains("null")) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }.getOrNull()
}
