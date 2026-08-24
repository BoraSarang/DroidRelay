package com.borasarang.droidrelay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.core.app.ActivityCompat
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

private val Navy = Color(0xFF0A1428)
private val CardNavy = Color(0xFF101E3A)
private val Accent = Color(0xFF8FD8FF)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugLogger.i("UI", "앱 실행 onCreate")
        RelayService.start(this)
        requestNotificationPermission()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                RelayScreen()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                DebugLogger.i("UI", "알림 권한 결과 granted=$granted")
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DebugLogger.d("UI", "알림 권한 이미 보유 또는 미대상 OS")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RelayScreen() {
    val jobs by JobsRepository.jobs.collectAsState()
    var showPanel by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    // 5탭 감지 → 디버그 패널 (AGENTS.md 10장 Android 진입 관례)
    fun onTitleTap() {
        val now = System.currentTimeMillis()
        taps = if (now - lastTapAt < 2000) taps + 1 else 1
        lastTapAt = now
        if (taps >= 5) {
            taps = 0
            showPanel = true
            DebugLogger.i("UI", "5탭 감지 → 디버그 패널 열림 (로그 ${DebugLogger.count()}줄)")
        }
    }

    Scaffold(containerColor = Navy) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Column {
                    Text(
                        "📡 DroidRelay",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier.combinedClickable(onClick = { onTitleTap() }),
                    )
                    Text(
                        "제목을 빠르게 5번 탭하면 디버그 로그가 열립니다",
                        color = Color(0xFF55688C),
                        fontSize = 11.sp,
                    )
                }
            }
            item { ServerCard() }
            item { AddRow() }
            item {
                Text(
                    "작업 목록 (${jobs.size})",
                    color = Color(0xFF8FA3BF),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(jobs, key = { it.id }) { JobCard(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showPanel) {
        ModalBottomSheet(onDismissRequest = {
            showPanel = false
            DebugLogger.d("UI", "디버그 패널 닫힘")
        }, containerColor = CardNavy) {
            DebugPanelContent()
        }
    }
}

/** 디버그 패널 — 로그 다중 선택 후 복사 (AGENTS.md 10.2 필수 기능) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugPanelContent() {
    val lines = remember { mutableStateListOf<String>().apply { addAll(DebugLogger.lines().asReversed()) } }
    val selected = remember { mutableStateListOf<Int>() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun copyToClip(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, "$label 완료", android.widget.Toast.LENGTH_SHORT).show()
        DebugLogger.i("Panel", "$label (${text.lines().size}줄)")
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🛠 디버그 로그 (${lines.size}줄 · 최신순)",
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
        }
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
                DebugLogger.clear()
                lines.clear()
                selected.clear()
            }) { Text("비우기") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            items(lines.size) { idx ->
                val line = lines[idx]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {
                            if (idx in selected) selected.remove(idx) else selected.add(idx)
                        }),
                ) {
                    Checkbox(
                        checked = idx in selected,
                        onCheckedChange = {
                            if (it) selected.add(idx) else selected.remove(idx)
                        },
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = when {
                            line.contains("[E]") -> Color(0xFFFF8A93)
                            line.contains("[W]") -> Color(0xFFFFD59E)
                            line.contains("[I]") -> Color.White
                            else -> Color(0xFF9FB4D4)
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

@Composable
private fun ServerCard() {
    val ctx = LocalContext.current
    val ip = remember { lanAddress() }
    val addr = "http://$ip:${RelayService.PORT}"

    Card(colors = CardDefaults.cardColors(containerColor = CardNavy), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("테더링 기기 접속 주소", color = Color(0xFF8FA3BF), fontSize = 12.sp)
                Text(addr.ifEmpty { "LAN 주소 탐지 중..." }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("DroidRelay", addr))
                    DebugLogger.d("UI", "주소 복사 클릭 → $addr")
                }) {
                    Text("복사")
                }
            }
            qrBitmap(addr)?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "QR",
                    modifier = Modifier.size(96.dp),
                )
            }
        }
    }
}

@Composable
private fun AddRow() {
    var text by remember { mutableStateOf("") }
    val engine = RelayApp.get(LocalContext.current)

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("https:// 다운로드 URL", fontSize = 13.sp, color = Color(0xFF55688C)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Color(0xFF2A3B5C),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Accent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (text.isNotBlank()) {
                DebugLogger.i("UI", "추가 버튼 클릭 url=${text.trim()}")
                engine.enqueue(text.trim())
                text = ""
            } else {
                DebugLogger.d("UI", "추가 버튼 클릭 — 빈 입력 무시")
            }
        }, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Filled.Add, "추가")
        }
    }
}

@Composable
private fun JobCard(job: Job) {
    val engine = RelayApp.get(LocalContext.current)
    Card(colors = CardDefaults.cardColors(containerColor = CardNavy), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.filename, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(stateLabel(job), color = Color(0xFF8FA3BF), fontSize = 12.sp)
                }
                IconButton(onClick = {
                    DebugLogger.i("UI", "카드 삭제 버튼 id=${job.id}")
                    engine.cancel(job.id)
                    JobsRepository.remove(job.id)
                }) { Icon(Icons.Filled.Close, "삭제", tint = Color(0xFF9FB4D4)) }
            }
            if (job.state == JobState.RUNNING || job.state == JobState.DONE) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    trackColor = Color(0xFF1B2B4D),
                )
            }
            job.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color(0xFFFF8A93), fontSize = 11.sp)
            }
        }
    }
}

private fun stateLabel(j: Job): String {
    val badge = when (j.state) {
        JobState.QUEUED -> "대기"
        JobState.RUNNING -> "진행 중 ${"%.0f".format(j.progress * 100)}%"
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

private fun fmtBytes(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

private fun qrBitmap(content: String): Bitmap? {
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
