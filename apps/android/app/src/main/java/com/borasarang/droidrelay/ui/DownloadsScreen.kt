package com.borasarang.droidrelay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.borasarang.droidrelay.relay.VideoApi
import com.borasarang.droidrelay.relay.VideoException
import com.borasarang.droidrelay.relay.currentNetworkType
import com.borasarang.droidrelay.relay.lanAddress
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onCopyAddress: (String) -> Unit) {
    val jobs by JobsRepository.jobs.collectAsState()
    val qr = remember { qrBitmap("http://${lanAddress()}:${RelayService.PORT}") }
    var qrFull by remember { mutableStateOf(false) }
    val qrScale by animateFloatAsState(if (qrFull) 1f else 0.85f, animationSpec = tween(180), label = "qrScale")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ServerCard(onCopyAddress, qr, onQrClick = { qrFull = true }) }
            item { AddRow() }
            item { VideoAddRow(onDlStarted = { }) }
            item {
                Text(
                    "작업 목록 (${jobs.size})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (jobs.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "다운로드한 작업이 없습니다",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(jobs, key = { it.id }) { JobCard(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (qrFull) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { qrFull = false },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val cs = MaterialTheme.colorScheme
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Box(Modifier.padding(16.dp)) {
                        qr?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "접속 주소 QR 코드 (확대)",
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .aspectRatio(1f)
                                    .scale(qrScale)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { qrFull = false },
                                    ),
                            )
                        }
                        IconButton(
                            onClick = { qrFull = false },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "닫기", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 서버 주소·QR·저장공간 카드 + 네트워크 타입 + 공유 */
@Composable
private fun ServerCard(onCopyAddress: (String) -> Unit, qr: Bitmap?, onQrClick: () -> Unit) {
    val ctx = LocalContext.current
    val engine = remember { RelayApp.get(ctx) }
    val ip = remember { lanAddress() }
    val addr = "http://$ip:${RelayService.PORT}"
    val cs = MaterialTheme.colorScheme
    val netType = remember { mutableStateOf(currentNetworkType(ctx)) }

    // 네트워크 변화 실시간 반영 (5초 폴링)
    LaunchedEffect(Unit) {
        while (true) {
            netType.value = currentNetworkType(ctx)
            kotlinx.coroutines.delay(5000)
        }
    }

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
                    Spacer(Modifier.height(4.dp))
                    val nt = netType.value
                    Text(
                        "${nt.icon} 네트워크: ${nt.label}",
                        color = if (nt.label == "오프라인") cs.error else cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("DroidRelay", addr))
                            onCopyAddress(addr)
                        }) { Text("복사") }
                        OutlinedButton(onClick = {
                            val shareText = "DroidRelay 접속 주소\n$addr\nQR 코드를 스캔하거나 위 주소를 브라우저에 입력하세요."
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            // QR 비트맵을 jpg로 저장 후 공유
                            qr?.let { bmp ->
                                val file = java.io.File(ctx.cacheDir, "droidrelay_qr.jpg")
                                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, "${ctx.packageName}.fileprovider", file,
                                )
                                sendIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                sendIntent.type = "image/jpeg"
                            }
                            ctx.startActivity(android.content.Intent.createChooser(sendIntent, "서버 주소 공유"))
                        }) { Text("공유") }
                    }
                }
                qr?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR",
                        modifier = Modifier.size(96.dp).clickable(onClick = onQrClick),
                    )
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

/** 🎬 비디오 — 동영상(mp4)/스트림(m3u8/mpd) URL 분석 → 해상도·파일명 선택 → 다운로드 */
@Composable
private fun VideoAddRow(onDlStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var qIndex by remember { mutableIntStateOf(0) }

    fun analyze() {
        val u = url.trim()
        if (u.isBlank() || analyzing) return
        DebugLogger.i("UI Video", "[FEATURE] 분석 요청 url=${u.take(90)}")
        analyzing = true; result = null; error = null; qIndex = 0
        scope.launch {
            try {
                val r = VideoApi.analyze(u)
                result = r
                fileName = defaultFileName(r, u)
                DebugLogger.i("UI Video", "분석 성공 kind=${r.optString("kind") ?: "stream"} qualities=${r.optJSONArray("qualities")?.length() ?: 0}")
            } catch (e: VideoException) {
                error = "${e.code}: ${e.message}"
                DebugLogger.w("UI Video", "분석 실패 ${e.code}: ${e.message}")
            } catch (e: Exception) {
                error = e.message ?: "분석 실패"
                DebugLogger.w("UI Video", "분석 오류: ${e.message}")
            }
            analyzing = false
        }
    }

    fun start() {
        val r = result ?: return
        val u = url.trim()
        starting = true; error = null
        scope.launch {
            try {
                val qs = r.optJSONArray("qualities")
                val streamUrl = if (qs != null && qs.length() > 1 && qIndex in 0 until qs.length()) {
                    qs.getJSONObject(qIndex).optString("url").ifBlank { r.optString("streamUrl") }
                } else {
                    r.optString("streamUrl")
                }.ifBlank { null }
                VideoApi.create(ctx, u, streamUrl, fileName.trim().ifBlank { null })
                DebugLogger.i("UI Video", "다운로드 시작 url=${u.take(90)} q=$qIndex file=${fileName.trim()}")
                onDlStarted()
                result = null; url = ""; fileName = ""
            } catch (e: VideoException) {
                error = "${e.code}: ${e.message}"
                DebugLogger.w("UI Video", "다운로드 시작 실패 ${e.code}: ${e.message}")
            } catch (e: Exception) {
                error = e.message ?: "다운로드 시작 실패"
                DebugLogger.w("UI Video", "다운로드 시작 오류: ${e.message}")
            }
            starting = false
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("비디오 (스트림)", color = cs.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text("스트림 주소(m3u8/mpd) 또는 스트리밍 웹페이지", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("스트림 URL", fontSize = 13.sp, color = cs.onSurfaceVariant) },
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
                if (analyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Button(onClick = { analyze() }, shape = MaterialTheme.shapes.small) { Text("분석") }
                }
            }
            error?.let { e ->
                Spacer(Modifier.height(8.dp))
                Text(e, color = cs.error, style = MaterialTheme.typography.labelSmall)
            }
            result?.let { r ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                    val kindTxt = when (r.optString("kind")) {
                        "mp4" -> "MP4 직접 동영상"
                        "stream" -> "스트림 (m3u8/mpd)"
                        else -> "웹페이지"
                    }
                    Text(r.optString("title").ifBlank { url }, color = cs.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(kindTxt, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(r.optString("streamUrl"), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    val qs = r.optJSONArray("qualities")
                    if (qs != null && qs.length() > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("해상도 선택", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        for (idx in 0 until qs.length()) {
                            val q = qs.getJSONObject(idx)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.combinedClickable(onClick = { qIndex = idx }),
                            ) {
                                RadioButton(
                                    selected = qIndex == idx,
                                    onClick = { qIndex = idx },
                                )
                                Text(q.optString("label"), color = cs.onSurface, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("파일명", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = cs.primary,
                            unfocusedBorderColor = cs.outlineVariant,
                            focusedTextColor = cs.onSurface,
                            unfocusedTextColor = cs.onSurface,
                            cursorColor = cs.primary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { start() },
                        enabled = !starting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (starting) "시작 중..." else "▶ 다운로드")
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: Job) {
    val ctx = LocalContext.current
    val engine = RelayApp.get(ctx)
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()

    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 좌측 순서 변경 화살표 (고정폭)
                Column(
                    modifier = Modifier.width(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = { engine.reorder(job.id, -1) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.ArrowDropUp, "위로", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { engine.reorder(job.id, 1) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.ArrowDropDown, "아래로", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
                // 콘텐츠 + 액션
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (job.type == "video") {
                                    Icon(Icons.Filled.VideoLibrary, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    job.filename,
                                    modifier = Modifier.weight(1f),
                                    color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(stateLabel(job), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            if (job.state == JobState.RUNNING && job.speedBps > 0) {
                                val etaParts = mutableListOf<String>()
                                if (job.startedAt > 0) {
                                    val elapsed = (now - job.startedAt) / 1000
                                    etaParts.add("${fmtDuration(elapsed)} 경과")
                                }
                                if (job.totalBytes > 0 && job.downloadedBytes < job.totalBytes) {
                                    val remain = (job.totalBytes - job.downloadedBytes) / job.speedBps
                                    etaParts.add("${fmtDuration(remain)} 남음")
                                }
                                if (etaParts.isNotEmpty()) {
                                    Spacer(Modifier.height(1.dp))
                                    Text(etaParts.joinToString(" · "), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        when (job.state) {
                            JobState.RUNNING -> IconButton(onClick = {
                                DebugLogger.i("UI", "일시정지 버튼 id=${job.id}")
                                engine.pause(job.id)
                            }) { Icon(Icons.Filled.Pause, "일시정지", tint = cs.onSurfaceVariant) }
                            JobState.PAUSED, JobState.FAILED -> IconButton(onClick = {
                                if (job.type == "video") {
                                    // video 잡은 DownloadEngine 미소관 — 원본 url로 재분석+재다운로드 (부분 재개 불가)
                                    DebugLogger.i("UI", "비디오 재시도 버튼 id=${job.id}")
                                    JobsRepository.update(job.id) { it.copy(state = JobState.QUEUED, errorCode = null, errorMessage = "재다운로드 준비 중…") }
                                    scope.launch {
                                        try {
                                            VideoApi.create(ctx, job.url, null, null)
                                        } catch (e: VideoException) {
                                            JobsRepository.update(job.id) { it.copy(state = JobState.FAILED, errorCode = e.code, errorMessage = "${e.code}: ${e.message}") }
                                            DebugLogger.w("UI Video", "재시도 실패 ${e.code}: ${e.message}")
                                        } catch (e: Exception) {
                                            JobsRepository.update(job.id) { it.copy(state = JobState.FAILED, errorMessage = e.message ?: "재시도 실패") }
                                            DebugLogger.w("UI Video", "재시도 오류: ${e.message}")
                                        }
                                    }
                                } else {
                                    DebugLogger.i("UI", "재개 버튼 id=${job.id}")
                                    engine.resume(job.id)
                                }
                            }) { Icon(Icons.Filled.PlayArrow, if (job.type == "video") "재시도" else "재개", tint = cs.primary) }
                            else -> IconButton(onClick = {
                                DebugLogger.i("UI", "삭제 버튼 id=${job.id}")
                                engine.cancel(job.id)
                                JobsRepository.remove(job.id)
                            }) { Icon(Icons.Filled.Close, "삭제", tint = cs.onSurfaceVariant) }
                        }
                    }
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

private fun stateLabel(j: Job): String {
    val badge = when (j.state) {
        JobState.QUEUED -> "대기"
        JobState.RUNNING -> {
            val sp = if (j.speedBps > 0) " · ${j.speedBps / 1024}KB/s" else ""
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

internal fun fmtDuration(seconds: Long): String {
    if (seconds < 0) return ""
    val sec = seconds.toInt()
    return when {
        sec < 60 -> "${sec}초"
        sec < 3600 -> "${sec / 60}분 ${sec % 60}초"
        else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
    }
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

/** 분석 결과 JSON과 원본 입력 URL로 기본 다운로드 파일명(확장자 제외)을 만든다 */
private fun defaultFileName(r: JSONObject, inputUrl: String): String {
    val base = if (r.optBoolean("direct")) {
        val u = r.optString("streamUrl").ifBlank { inputUrl }
        u.substringAfterLast('/').substringBefore('?').substringBefore('#')
            .substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .ifBlank { "video" }
    } else {
        r.optString("title").ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('.', '_', ' ')
    }
    return base.trim('.', '_', ' ').take(60).ifBlank { "video" }.plus(".mp4")
}
