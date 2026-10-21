package com.borasarang.droidrelay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.relay.currentNetworkType
import com.borasarang.droidrelay.relay.lanAddress
import com.borasarang.droidrelay.relay.networkTypeFlow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 상태 탭 — 서버 주소·QR·저장공간 카드 (기존 다운로드 상단에서 이동) */
@Composable
fun StatusScreen(onCopyAddress: (String) -> Unit) {
    val ctx = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(ctx) }
    val settings by settingsRepo.settings.collectAsState(initial = null)
    val httpPort = settings?.port ?: SettingsConstraints.DEFAULT_HTTP_PORT
    val httpsPort = settings?.httpsPort ?: SettingsConstraints.DEFAULT_HTTPS_PORT
    val qr = remember(httpPort) { qrBitmap("http://${lanAddress()}:$httpPort") }
    var qrFull by remember { mutableStateOf(false) }
    val qrScale by animateFloatAsState(if (qrFull) 1f else 0.85f, animationSpec = tween(180), label = "qrScale")
    BackHandler(enabled = qrFull) { qrFull = false }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ServerCard(httpPort, httpsPort, onCopyAddress, qr, onQrClick = { qrFull = true }) }
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
                            Icon(Icons.Outlined.Close, contentDescription = "닫기", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 서버 주소·QR·저장공간 카드 + 실행 상태 + 네트워크 타입 + 공유 */
@Composable
private fun ServerCard(httpPort: Int, httpsPort: Int, onCopyAddress: (String) -> Unit, qr: Bitmap?, onQrClick: () -> Unit) {
    val ctx = LocalContext.current
    val engine = remember { RelayApp.get(ctx) }
    val repo = remember { SettingsRepository.get(ctx) }
    val serverState by repo.serverState.collectAsState()
    val ip = remember { lanAddress() }
    val httpAddr = "http://$ip:$httpPort"
    val httpsAddr = "https://$ip:$httpsPort"
    val cs = MaterialTheme.colorScheme
    // 네트워크 변화 실시간 반영 — 5초 폴링 대신 ConnectivityManager 콜백 구독.
    // 폴링이던 동안엔 메인 스레드에서 통신사 서비스 binder 호출이 분당 12회씩 났다.
    val netType by networkTypeFlow(ctx).collectAsState(initial = currentNetworkType(ctx))

    val storage = remember {
        runCatching {
            val st = android.os.StatFs(engine.workDir.path)
            st.availableBytes to st.totalBytes
        }.getOrNull()
    }

    // v0.59: 홈의 시그니처 — primary-container 히어로 카드 (시드 딥 파인)
    Card(colors = CardDefaults.cardColors(containerColor = cs.primaryContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // 실행 상태 (T-1014, v0.36 HTTPS 개별 표시 + 시작/정지)
            val httpsPart = if (serverState.httpsEnabled) " · HTTPS ${serverState.httpsPort}" else " · HTTPS 끔"
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    serverState.error != null -> cs.error
                    serverState.running -> cs.primary
                    else -> cs.onSurfaceVariant
                }
                Text("●", color = dotColor, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        serverState.error != null -> "에러: ${serverState.error}"
                        serverState.running -> "HTTP ${serverState.port}$httpsPart 실행 중"
                        else -> "HTTP ${serverState.port}$httpsPart 대기 중"
                    },
                    color = if (serverState.error != null) cs.error else cs.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (serverState.running) {
                    OutlinedButton(onClick = {
                        RelayService.stop(ctx)
                        DebugLogger.i("UI", "서버 정지 (ServerCard)")
                    }) { Text("정지") }
                } else {
                    Button(onClick = {
                        RelayService.start(ctx)
                        DebugLogger.i("UI", "[FEATURE] 서버 시작 (ServerCard)")
                    }) { Text("시작") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("테더링 기기 접속 주소", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        httpAddr.ifEmpty { "LAN 주소 탐지 중..." },
                        color = cs.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = httpAddr.isNotEmpty()) { openUrlInBrowser(ctx, httpAddr) },
                    )
                    Text(
                        httpsAddr,
                        color = cs.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = serverState.httpsEnabled && httpsAddr.isNotEmpty()) { openUrlInBrowser(ctx, httpsAddr) },
                    )
                    if (!serverState.httpsEnabled) {
                        Text(
                            "HTTPS 끔 (설정에서 사용 가능)",
                            color = cs.onPrimaryContainer.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val nt = netType
                    Text(
                        "${nt.icon} 네트워크: ${nt.label}",
                        color = if (nt.label == "오프라인") cs.error else cs.onPrimaryContainer.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val both = if (serverState.httpsEnabled) "$httpAddr\n$httpsAddr" else httpAddr
                            cm.setPrimaryClip(ClipData.newPlainText("DroidRelay", both))
                            onCopyAddress(both)
                        }) { Text("복사") }
                        OutlinedButton(onClick = {
                            val both = if (serverState.httpsEnabled) "$httpAddr\n$httpsAddr" else httpAddr
                            val shareText = "DroidRelay 접속 주소\n$both\nQR 코드를 스캔하거나 위 주소를 브라우저에 입력하세요."
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
                        contentDescription = "접속 주소 QR 코드",
                        modifier = Modifier.size(96.dp).clickable(onClick = onQrClick),
                    )
                }
            }

            if (storage != null && storage.second > 0) {
                Spacer(Modifier.height(12.dp))
                val usedRatio = ((storage.second - storage.first).toFloat() / storage.second).coerceIn(0f, 1f)
                Text(
                    "저장공간 여유 ${fmtBytes(storage.first)} / ${fmtBytes(storage.second)}",
                    color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = cs.onPrimaryContainer.copy(alpha = 0.18f),
                    color = cs.primary,
                )
            }
        }
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
