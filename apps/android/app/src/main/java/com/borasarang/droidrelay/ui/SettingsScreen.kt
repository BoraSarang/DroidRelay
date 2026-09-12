package com.borasarang.droidrelay.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.borasarang.droidrelay.relay.CronParser
import com.borasarang.droidrelay.relay.DebridProvider
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.ServerState
import com.borasarang.droidrelay.relay.SettingsConstraints
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.relay.ThemeMode
import com.borasarang.droidrelay.relay.TunnelProvider
import com.borasarang.droidrelay.relay.lanAddress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onPortChanged: (Int) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val settings by repo.settings.collectAsState(initial = null)
    val s = settings ?: return
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // ── 서버 ──
        SettingSection("서버") {
            var portText by remember(s.port) { mutableStateOf(s.port.toString()) }
            val serverState by repo.serverState.collectAsState()

            // Line 1: 포트 입력 + 랜덤 + 적용
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("포트") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val random = (9000..9999).random()
                    portText = random.toString()
                    DebugLogger.i("Settings", "랜덤 포트 생성 $random")
                    kotlinx.coroutines.MainScope().launch {
                        repo.setPort(random)
                        onPortChanged(random)
                    }
                }) { Text("랜덤") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        DebugLogger.w("Settings", "포트 무효 값: $portText (E-AND-DOWN-2002)")
                    } else if (p != s.port) {
                        DebugLogger.i("Settings", "포트 변경 ${s.port} → $p")
                        kotlinx.coroutines.MainScope().launch {
                            repo.setPort(p)
                            onPortChanged(p)
                        }
                    }
                }) { Text("적용") }
            }

            // Line 2: 상태 표시
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val dotColor = when {
                    serverState.error != null -> cs.error
                    serverState.running -> cs.primary
                    else -> cs.onSurfaceVariant
                }
                Text("●", color = dotColor, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        serverState.error != null -> "에러: ${serverState.error}"
                        serverState.running -> "${serverState.port} 포트로 실행 중"
                        else -> "${serverState.port} 포트로 대기 중"
                    },
                    color = dotColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (serverState.running && serverState.url != null) {
                    val addr = serverState.url!!
                    Text(
                        addr,
                        color = cs.primary,
                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                        modifier = Modifier.clickable { openUrlInBrowser(ctx, addr) },
                    )
                }
            }

            // Line 3: 자동 시작
            SwitchRow("앱 실행 시 서버 자동 시작", s.autoStart) { v -> kotlinx.coroutines.MainScope().launch { repo.setAutoStart(v) } }

            // Line 4: 배터리 최적화 예외 (백그라운드 안정성)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            var batteryUnrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                        DebugLogger.i("Settings", "배터리 예외 상태 갱신: $batteryUnrestricted")
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (batteryUnrestricted) "배터리 최적화 예외: 허용됨" else "배터리 최적화 예외: 미허용",
                    color = if (batteryUnrestricted) cs.primary else cs.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!batteryUnrestricted) {
                Text(
                    "백그라운드에서 안정적으로 동작하려면 '배터리 사용 제한 없음' 설정이 필요합니다",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    }.onFailure {
                        DebugLogger.w("Settings", "배터리 예외 요청 실패: ${it.message}")
                    }
                }) { Text("배터리 무제한 허용 요청") }
            } else {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }.onFailure {
                        DebugLogger.w("Settings", "배터리 최적화 설정 화면 열기 실패: ${it.message}")
                    }
                }) { Text("배터리 무제한 해제") }
                Text(
                    "해제 화면에서 DroidRelay를 눌러 '최적화'로 바꾸면 원복됩니다",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 화면 ──
        SettingSection("화면") {
            Text("테마", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = s.themeMode == mode
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "시스템"
                        ThemeMode.LIGHT -> "라이트"
                        ThemeMode.DARK -> "다크"
                    }
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            DebugLogger.i("Settings", "테마 변경 → $mode")
                            kotlinx.coroutines.MainScope().launch { repo.setThemeMode(mode) }
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Material You 동적 색상 (배경화면 따라가기)", s.dynamicColor) { v -> kotlinx.coroutines.MainScope().launch { repo.setDynamicColor(v) } }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 다운로드 ──
        SettingSection("다운로드") {
            Text(
                "동시 다운로드 수: ${s.concurrency}개",
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = s.concurrency.toFloat(),
                onValueChange = { kotlinx.coroutines.MainScope().launch { repo.setConcurrency(it.toInt()) } },
                valueRange = 1f..4f,
                steps = 2,
            )
            Text(
                "속도 제한: ${if (s.speedLimitKbps == 0) "무제한" else "${s.speedLimitKbps} KB/s"}",
                color = cs.onSurface,
            )
            Slider(
                value = s.speedLimitKbps.toFloat(),
                onValueChange = { v ->
                    val kb = (v / 128).toInt() * 128  // 128KB/s 스텝
                    kotlinx.coroutines.MainScope().launch { repo.setSpeedLimit(kb) }
                },
                valueRange = 0f..2048f,
            )
            SwitchRow("다운로드 알림 표시", s.notifications) { v -> kotlinx.coroutines.MainScope().launch { repo.setNotifications(v) } }

            Spacer(Modifier.height(12.dp))
            Text("보관함 자동 운영", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(
                "보관함 쿼터: ${if (s.storageQuotaGb == 0) "끔" else "${s.storageQuotaGb}GB (초과 시 오래된 파일 자동 휴지통)"}",
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = s.storageQuotaGb.toFloat(),
                onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setStorageQuotaGb(v.toInt()) } },
                valueRange = 0f..128f,
                steps = 127,
            )
            SwitchRow("완료 파일 자동 분류 (영상/음악/문서)", s.autoClassify) { v -> kotlinx.coroutines.MainScope().launch { repo.setAutoClassify(v) } }

            Spacer(Modifier.height(12.dp))
            Text("전역 속도 제한 (다운로드·토렌트 공통)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            val dlMbps = if (s.maxDownloadBps > 0) (s.maxDownloadBps / 1_048_576).toInt().coerceIn(1, 10) else 0
            SwitchRow(
                "전역 다운로드 속도 제한",
                s.maxDownloadBps > 0,
            ) { on -> kotlinx.coroutines.MainScope().launch { repo.setMaxDownloadBps(if (on) 3L * 1_048_576 else 0L) } }
            if (dlMbps > 0) {
                Text("${dlMbps} Mbps", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = dlMbps.toFloat(),
                    onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setMaxDownloadBps(v.roundToInt().toLong() * 1_048_576) } },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }
            val ulMbps = if (s.maxUploadBps > 0) (s.maxUploadBps / 1_048_576).toInt().coerceIn(1, 10) else 0
            SwitchRow(
                "전역 업로드 속도 제한",
                s.maxUploadBps > 0,
            ) { on -> kotlinx.coroutines.MainScope().launch { repo.setMaxUploadBps(if (on) 3L * 1_048_576 else 0L) } }
            if (ulMbps > 0) {
                Text("${ulMbps} Mbps", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = ulMbps.toFloat(),
                    onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setMaxUploadBps(v.roundToInt().toLong() * 1_048_576) } },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("완료 후 동작", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = s.completionAction == SettingsConstraints.COMPLETION_ACTION_NONE,
                    onClick = {
                        kotlinx.coroutines.MainScope().launch { repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE) }
                    },
                    label = { Text("없음") },
                )
                androidx.compose.material3.FilterChip(
                    selected = s.completionAction == SettingsConstraints.COMPLETION_ACTION_STOP_SERVER,
                    onClick = {
                        DebugLogger.i("Settings", "완료 후 동작 → 서버 정지")
                        kotlinx.coroutines.MainScope().launch { repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_STOP_SERVER) }
                    },
                    label = { Text("전체 완료 시 서버 정지") },
                )
            }

            Spacer(Modifier.height(12.dp))
            SpeedScheduleSection(s.speedSchedule)
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 보안 ──
        SettingSection("보안") {
            // 접속 범위
            Text("클라이언트 접속 범위", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessScope.entries.forEach { scope ->
                    val selected = s.accessScope == scope
                    val label = when (scope) {
                        AccessScope.SUBNET_ONLY -> "같은 핫스팟"
                        AccessScope.ANY_WITH_PASSWORD -> "암호만 있으면"
                        AccessScope.APPROVED_ONLY -> "승인만"
                    }
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            DebugLogger.i("Settings", "접속 범위 변경 → $scope")
                            kotlinx.coroutines.MainScope().launch { repo.setAccessScope(scope) }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                when (s.accessScope) {
                    AccessScope.SUBNET_ONLY -> "같은 Wi-Fi/핫스팟에 연결된 기기만 접속 가능 (기본값)"
                    AccessScope.ANY_WITH_PASSWORD -> "비밀번호를 아는 모든 기기 접속 가능"
                    AccessScope.APPROVED_ONLY -> "허용된 IP만 접속 가능 (승인 팝업)"
                },
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(12.dp))

            var authEnabled by remember(s.webAuthEnabled) { mutableStateOf(s.webAuthEnabled) }
            var user by remember(s.webUser) { mutableStateOf(s.webUser) }
            var pass by remember(s.webPassword) { mutableStateOf("") }
            SwitchRow("웹 접속 암호 요청 (HTTP Basic)", authEnabled) {
                authEnabled = it
                kotlinx.coroutines.MainScope().launch { repo.setWebAuth(it, user, pass) }
            }
            if (authEnabled) {
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("사용자명") },
                    singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text(if (s.webPassword.isEmpty()) "비밀번호" else "비밀번호 (변경 시 입력)") },
                    singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    DebugLogger.i("Settings", "웹 인증 저장 user=$user")
                    kotlinx.coroutines.MainScope().launch { repo.setWebAuth(true, user, pass.ifBlank { s.webPassword }) }
                }) { Text("인증 정보 저장") }
            }
            Spacer(Modifier.height(12.dp))
            var guestEnabled by remember(s.guestEnabled) { mutableStateOf(s.guestEnabled) }
            var guestPass by remember { mutableStateOf("") }
            SwitchRow("게스트 읽기전용 (열람·다운로드만)", guestEnabled) {
                guestEnabled = it
                kotlinx.coroutines.MainScope().launch { repo.setGuestEnabled(it) }
            }
            if (guestEnabled) {
                Text(
                    "웹 인증이 켜져 있을 때만 유효. 사용자명 guest로 접속, 등록·삭제·설정은 차단됩니다",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = guestPass,
                        onValueChange = { guestPass = it },
                        label = { Text(if (s.guestPassword.isEmpty()) "게스트 비밀번호" else "게스트 비밀번호 (변경 시 입력)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (guestPass.isBlank()) {
                            DebugLogger.w("Settings", "게스트 비밀번호 비어 있음")
                        } else {
                            DebugLogger.i("Settings", "게스트 비밀번호 저장")
                            kotlinx.coroutines.MainScope().launch { repo.setGuestPassword(guestPass) }
                            guestPass = ""
                        }
                    }) { Text("저장") }
                }
            }
            Text(
                "허용된 기기 IP: ${s.allowedIps.ifEmpty { setOf("(없음 — 신규 접속 시 승인 팝업)") }}",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            s.allowedIps.forEach { ip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ip, Modifier.weight(1f), color = cs.onSurface)
                    Button(onClick = {
                        kotlinx.coroutines.MainScope().launch { repo.removeAllowedIp(ip) }
                    }) { Text("해제") }
                }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

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
                    kotlinx.coroutines.MainScope().launch { repo.setTorrentUploadLimit(kbps) }
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
                    kotlinx.coroutines.MainScope().launch { repo.setTorrentDownloadLimit(kbps) }
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
                onValueChange = { kotlinx.coroutines.MainScope().launch { repo.setTorrentMaxActive(it.toInt()) } },
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
                onValueChange = { kotlinx.coroutines.MainScope().launch { repo.setTorrentSeedRatio(it) } },
                valueRange = 0f..10f,
                steps = 9,
            )
            OutlinedButton(onClick = {
                DebugLogger.i("Settings", "업로드 최소화 프리셋 적용")
                kotlinx.coroutines.MainScope().launch {
                    repo.setTorrentSeedRatio(0.5f)
                    repo.setTorrentUploadLimit(32)
                    repo.setTorrentDhtEnabled(false)
                }
            }) { Text("⬇ 업로드 최소화 (비율 0.5 + 업로드 32KB/s + DHT 끔)") }

            SwitchRow("DHT (분산 해시 테이블)", s.torrentDhtEnabled) { v -> kotlinx.coroutines.MainScope().launch { repo.setTorrentDhtEnabled(v) } }
            SwitchRow("PEX (피어 교환)", s.torrentPexEnabled) { v -> kotlinx.coroutines.MainScope().launch { repo.setTorrentPexEnabled(v) } }
            Text(
                "PEX는 libtorrent에 on/off가 없어 항상 켜짐. 피어 탐색용으로 트래픽은 미미합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(Modifier.height(16.dp))
            Text("고급", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            SwitchRow("시퀀셜 다운로드 (스트리밍 프리뷰)", s.torrentSequentialDownload) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setTorrentSequentialDownload(v) }
            }
            Text(
                "첫 조각부터 순서대로 받아 재생 미리보기를 지원합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            SwitchRow("트래커 자동 동기 (24시간)", s.torrentTrackerSync) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setTorrentTrackerSync(v) }
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
                        kotlinx.coroutines.MainScope().launch { repo.setTorrentMinSeedWaitSec(sec) }
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
                    kotlinx.coroutines.MainScope().launch { repo.setTorrentListenPort(r) }
                }) { Text("랜덤") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    val p = listenPort.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        DebugLogger.w("Settings", "리슨 포트 무효 값: $listenPort")
                    } else {
                        DebugLogger.i("Settings", "리슨 포트 → $p")
                        kotlinx.coroutines.MainScope().launch { repo.setTorrentListenPort(p) }
                    }
                }) { Text("적용") }
            }
            Text(
                "변경 시 토렌트 엔진 재시작이 필요합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            var savePath by remember(s.torrentSavePath) { mutableStateOf(s.torrentSavePath.ifBlank { "/sdcard/Download/DroidRelay" }) }
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
                        kotlinx.coroutines.MainScope().launch { repo.setTorrentSavePath(savePath.trimEnd('/')) }
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
            SwitchRow("검색 사용", s.searchEnabled) { v -> kotlinx.coroutines.MainScope().launch { repo.setSearchEnabled(v) } }
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
                    kotlinx.coroutines.MainScope().launch {
                        repo.setSearchUrl(searchUrl)
                        repo.setSearchApiKey(searchKey)
                    }
                }) { Text("저장") }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 가드 보호 ──
        SettingSection("가드 보호") {
            SwitchRow("가드 데몬 활성화", s.guardEnabled) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setGuardEnabled(v) }
            }
            Text(
                "열·배터리·스토리지 임계치 초과 시 다운로드를 자동 일시정지합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            Text("열 제한: ${s.guardThermalLimit}°C", color = cs.onSurface)
            Slider(
                value = s.guardThermalLimit.toFloat(),
                onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setGuardThermalLimit(v.roundToInt()) } },
                valueRange = 50f..70f,
                steps = 19,
            )

            Text("배터리 제한: ${s.guardBatteryLimit}%", color = cs.onSurface)
            Slider(
                value = s.guardBatteryLimit.toFloat(),
                onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setGuardBatteryLimit((v / 5).roundToInt() * 5) } },
                valueRange = 5f..50f,
                steps = 8,
            )

            Text("스토리지 제한: ${s.guardStorageLimit}%", color = cs.onSurface)
            Slider(
                value = s.guardStorageLimit.toFloat(),
                onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setGuardStorageLimit(v.roundToInt()) } },
                valueRange = 50f..99f,
                steps = 48,
            )

            var wd by remember(s.watchdogIntervalSec) { mutableStateOf(s.watchdogIntervalSec.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = wd,
                    onValueChange = { wd = it.filter { c -> c.isDigit() } },
                    label = { Text("헬스체크 주기 (초)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val sec = wd.toIntOrNull()
                    if (sec == null || sec !in 15..3600) {
                        DebugLogger.w("Settings", "watchdog 주기 무효 값: $wd")
                    } else {
                        DebugLogger.i("Settings", "watchdog 주기 → $sec 초")
                        kotlinx.coroutines.MainScope().launch { repo.setWatchdogIntervalSec(sec) }
                    }
                }) { Text("적용") }
            }
            Text(
                "서버가 응답하지 않으면 자동으로 재시작해 복구합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )

            SwitchRow(
                "HTTP → HTTPS 강제 리다이렉트",
                s.forceHttpsRedirect,
            ) { v -> kotlinx.coroutines.MainScope().launch { repo.setForceHttpsRedirect(v) } }
            Text(
                "켜면 웨일/사파리에서 HTTPS 인증서 신뢰가 필요할 수 있습니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 스케줄 ──
        SettingSection("스케줄") {
            SwitchRow("예약 다운로드 활성화", s.scheduleEnabled) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setScheduleEnabled(v) }
            }
            Text(
                "Cron 표현식으로 다운로드 시간대를 예약합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            var cron by remember(s.scheduleCron) { mutableStateOf(s.scheduleCron) }
            var cronValid by remember(s.scheduleCron) { mutableStateOf(CronParser.isValid(s.scheduleCron)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = cron,
                    onValueChange = { cron = it; cronValid = CronParser.isValid(it) },
                    label = { Text("Cron (예: 0 2 * * *)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (cronValid) {
                        DebugLogger.i("Settings", "스케줄 cron → $cron")
                        kotlinx.coroutines.MainScope().launch { repo.setScheduleCron(cron) }
                    } else {
                        DebugLogger.w("Settings", "Cron 형식 무효: $cron (E-AND-VALID-0001)")
                    }
                }) { Text("적용") }
            }
            Text(
                if (cronValid) "유효한 Cron 표현식입니다" else "Cron 형식이 올바르지 않습니다",
                color = if (cronValid) cs.primary else cs.error,
                style = MaterialTheme.typography.labelSmall,
            )
            SwitchRow("Wi-Fi 연결 시에만", s.scheduleWifiOnly) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setScheduleWifiOnly(v) }
            }
            SwitchRow("충전 중에만", s.scheduleChargingOnly) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setScheduleChargingOnly(v) }
            }
            Text("최소 배터리: ${s.scheduleBatteryMin}%", color = cs.onSurface)
            Slider(
                value = s.scheduleBatteryMin.toFloat(),
                onValueChange = { v -> kotlinx.coroutines.MainScope().launch { repo.setScheduleBatteryMin(v.roundToInt()) } },
                valueRange = 5f..100f,
                steps = 18,
            )
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── Debrid ──
        SettingSection("Debrid") {
            SwitchRow("클라우드 다운로드 활성화", s.debridEnabled) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setDebridEnabled(v) }
            }
            Text(
                "토렌트/대용량 링크를 제공자 클라우드에서 언리스트링크하여 받습니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text("제공자", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebridProvider.entries.forEach { provider ->
                    androidx.compose.material3.FilterChip(
                        selected = s.debridProvider == provider.name,
                        onClick = {
                            DebugLogger.i("Settings", "Debrid 제공자 → ${provider.name}")
                            kotlinx.coroutines.MainScope().launch { repo.setDebridProvider(provider.name) }
                        },
                        label = { Text(provider.displayName) },
                    )
                }
            }
            var apiKey by remember(s.debridApiKey) { mutableStateOf(s.debridApiKey) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API 키") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                DebugLogger.i("Settings", "Debrid API 키 저장 (${apiKey.length}자)")
                kotlinx.coroutines.MainScope().launch { repo.setDebridApiKey(apiKey.trim()) }
            }) { Text("저장") }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 터널 ──
        SettingSection("터널") {
            SwitchRow("터널 사용", s.tunnelEnabled) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setTunnelEnabled(v) }
            }
            Text(
                "Tailscale/Cloudflare Tunnel로 외부 네트워크에서 접속 가능하게 합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text("제공자", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TunnelProvider.entries.forEach { provider ->
                    androidx.compose.material3.FilterChip(
                        selected = s.tunnelProvider == provider.name,
                        onClick = {
                            DebugLogger.i("Settings", "터널 제공자 → ${provider.name}")
                            kotlinx.coroutines.MainScope().launch { repo.setTunnelProvider(provider.name) }
                        },
                        label = { Text(provider.displayName) },
                    )
                }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── MCP 서버 권한 ──
        SettingSection("MCP 서버 권한") {
            SwitchRow("프라이버시 모드", s.mcpPrivacyMode) { v ->
                kotlinx.coroutines.MainScope().launch { repo.setMcpPrivacyMode(v) }
            }
            Text(
                "MCP 클라이언트가 명령 실행 시 상세 내용을 표시합니다",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            val mcpTools = listOf(
                "file_list" to "file_list — 보관함 파일 목록",
                "file_read" to "file_read — 파일 내용 읽기",
                "download_add" to "download_add — 다운로드 추가",
                "download_list" to "download_list — 다운로드 목록",
                "download_control" to "download_control — 다운로드 제어",
            )
            mcpTools.forEach { (name, label) ->
                SwitchRow(label, name !in s.mcpToolsDisabled) { enabled ->
                    kotlinx.coroutines.MainScope().launch { repo.setMcpToolDisabled(name, !enabled) }
                }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 기본값 복원 ──
        var resetCategory by remember { mutableStateOf<String?>(null) }
        SettingSection("기본값 복원") {
            Text(
                "설정을 기본값으로 되돌립니다. 복원 후 즉시 적용됩니다.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { resetCategory = "download" }) { Text("다운로드") }
                OutlinedButton(onClick = { resetCategory = "torrent" }) { Text("토렌트") }
                Button(onClick = { resetCategory = "all" }) { Text("전체 초기화") }
            }
        }

        resetCategory?.let { cat ->
            AlertDialog(
                onDismissRequest = { resetCategory = null },
                title = { Text(if (cat == "all") "전체 초기화" else "기본값 복원") },
                text = {
                    Text(
                        when (cat) {
                            "all" -> "모든 설정을 기본값으로 되돌립니다. 계속할까요?"
                            "download" -> "다운로드 설정(동시 수·속도·알림)을 기본값으로 되돌립니다. 계속할까요?"
                            else -> "토렌트 설정(속도·활성 수·포트·저장 경로)을 기본값으로 되돌립니다. 계속할까요?"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        resetSettings(ctx, repo, cat)
                        resetCategory = null
                    }) { Text("복원", color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { resetCategory = null }) { Text("취소") }
                },
            )
        }

        HorizontalDivider(color = cs.outlineVariant)

        // ── 앱 정보 ──
        val appVersion = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        }
        SettingSection("앱 정보") {
            InfoRow("버전", appVersion)
            InfoRow("제작자", "BoRaSaRang")
            InfoRow("문의", "leeborasarang@gmail.com")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = {
            DebugLogger.i("Settings", "$label → $it")
            onChange(it)
        })
    }
}

private fun resetSettings(ctx: android.content.Context, repo: SettingsRepository, category: String) {
    DebugLogger.w("Settings", "설정 기본값 복원 진행 category=$category")
    when (category) {
        "download" -> kotlinx.coroutines.MainScope().launch {
            repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
            repo.setSpeedLimit(0)
            repo.setNotifications(true)
            repo.setSpeedSchedule(emptyList())
            repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE)
            RelayApp.get(ctx).applySettings(repo.firstBlocking())
        }
        "torrent" -> kotlinx.coroutines.MainScope().launch {
            repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
            repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
            repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
            repo.setTorrentSeedRatio(2.0f)
            repo.setTorrentDhtEnabled(true)
            repo.setTorrentPexEnabled(true)
            repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
            repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
            RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
        }
        "all" -> {
            kotlinx.coroutines.MainScope().launch {
                repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
                repo.setSpeedLimit(0)
                repo.setNotifications(true)
                repo.setSpeedSchedule(emptyList())
                repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE)
                RelayApp.get(ctx).applySettings(repo.firstBlocking())
            }
            kotlinx.coroutines.MainScope().launch {
                repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
                repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
                repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
                repo.setTorrentSeedRatio(2.0f)
                repo.setTorrentDhtEnabled(true)
                repo.setTorrentPexEnabled(true)
                repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
                repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
                RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
            }
        }
    }
}

/** 트래커 도달성 측정 행 (v0.26) */
@Composable
private fun TrackerProbeRow() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var status by remember { mutableStateOf("확인 중…") }
    fun refresh() {
        kotlinx.coroutines.MainScope().launch {
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
            kotlinx.coroutines.MainScope().launch {
                com.borasarang.droidrelay.relay.RelayApp.getTorrent(ctx).probeTrackers()
                kotlinx.coroutines.delay(6000)
                refresh()
            }
        }) { Text("도달 측정") }
    }
}

/** 속도 스케줄 목록 + 추가 다이얼로그 (v0.24) */
@Composable
private fun SpeedScheduleSection(windows: List<com.borasarang.droidrelay.relay.SpeedWindow>) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val cs = MaterialTheme.colorScheme
    var showAdd by remember { mutableStateOf(false) }
    val dayNames = mapOf(1 to "일", 2 to "월", 3 to "화", 4 to "수", 5 to "목", 6 to "금", 7 to "토")

    fun save(next: List<com.borasarang.droidrelay.relay.SpeedWindow>) {
        kotlinx.coroutines.MainScope().launch { repo.setSpeedSchedule(next) }
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
