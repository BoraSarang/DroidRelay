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
internal fun GuardSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── 가드 보호 ──
    SettingSection("가드 보호") {
        SwitchRow("가드 데몬 활성화", s.guardEnabled) { v ->
            scope.launch { repo.setGuardEnabled(v) }
        }
        Text(
            "열·배터리·스토리지 임계치 초과 시 다운로드를 자동 일시정지합니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

        Text("열 제한: ${s.guardThermalLimit}°C", color = cs.onSurface)
        Slider(
            value = s.guardThermalLimit.toFloat(),
            onValueChange = { v -> scope.launch { repo.setGuardThermalLimit(v.roundToInt()) } },
            valueRange = 50f..70f,
            steps = 19,
        )

        Text("배터리 제한: ${s.guardBatteryLimit}%", color = cs.onSurface)
        Slider(
            value = s.guardBatteryLimit.toFloat(),
            onValueChange = { v -> scope.launch { repo.setGuardBatteryLimit((v / 5).roundToInt() * 5) } },
            valueRange = 5f..50f,
            steps = 8,
        )

        Text("스토리지 제한: ${s.guardStorageLimit}%", color = cs.onSurface)
        Slider(
            value = s.guardStorageLimit.toFloat(),
            onValueChange = { v -> scope.launch { repo.setGuardStorageLimit(v.roundToInt()) } },
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
                    scope.launch { repo.setWatchdogIntervalSec(sec) }
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
            s.forceHttpsRedirect && s.httpsEnabled,
            enabled = s.httpsEnabled,
        ) { v -> scope.launch { repo.setForceHttpsRedirect(v && s.httpsEnabled) } }
        Text(
            if (s.httpsEnabled) "켜면 웨일/사파리에서 HTTPS 인증서 신뢰가 필요할 수 있습니다" else "HTTPS가 꺼져 있어 리다이렉트를 사용할 수 없습니다",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    
}


@Composable
internal fun ScheduleSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── 스케줄 ──
    SettingSection("스케줄") {
        SwitchRow("예약 다운로드 활성화", s.scheduleEnabled) { v ->
            scope.launch { repo.setScheduleEnabled(v) }
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
                    scope.launch { repo.setScheduleCron(cron) }
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
            scope.launch { repo.setScheduleWifiOnly(v) }
        }
        SwitchRow("충전 중에만", s.scheduleChargingOnly) { v ->
            scope.launch { repo.setScheduleChargingOnly(v) }
        }
        Text("최소 배터리: ${s.scheduleBatteryMin}%", color = cs.onSurface)
        Slider(
            value = s.scheduleBatteryMin.toFloat(),
            onValueChange = { v -> scope.launch { repo.setScheduleBatteryMin(v.roundToInt()) } },
            valueRange = 5f..100f,
            steps = 18,
        )
    }
    
}


@Composable
internal fun DebridSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── Debrid ──
    SettingSection("Debrid") {
        SwitchRow("클라우드 다운로드 활성화", s.debridEnabled) { v ->
            scope.launch { repo.setDebridEnabled(v) }
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
                        scope.launch { repo.setDebridProvider(provider.name) }
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
            scope.launch { repo.setDebridApiKey(apiKey.trim()) }
        }) { Text("저장") }
    }
    
}


@Composable
internal fun TunnelSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── 터널 ──
    SettingSection("터널") {
        SwitchRow("터널 사용", s.tunnelEnabled) { v ->
            scope.launch { repo.setTunnelEnabled(v) }
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
                        scope.launch { repo.setTunnelProvider(provider.name) }
                    },
                    label = { Text(provider.displayName) },
                )
            }
        }
    }
    
}


@Composable
internal fun McpSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── MCP 서버 권한 ──
    SettingSection("MCP 서버 권한") {
        SwitchRow("프라이버시 모드", s.mcpPrivacyMode) { v ->
            scope.launch { repo.setMcpPrivacyMode(v) }
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
                scope.launch { repo.setMcpToolDisabled(name, !enabled) }
            }
        }
    }
    
}


@Composable
internal fun ResetSection(
    repo: SettingsRepository,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
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
    
}


@Composable
internal fun AboutSection(
    ctx: Context,
) {
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

/** 크래시 감지 도구 검증용 — 의도적 강제 크래시 3종 (확인 다이얼로그 필수) */
@Composable
internal fun CrashTestSection() {
    val cs = MaterialTheme.colorScheme
    var pendingCrash by remember { mutableStateOf<(() -> Unit)?>(null) }

    SettingSection("크래시 테스트") {
        Text(
            "크래시 감지 도구 검증용 — 확인 후 앱이 즉시 강제 종료됩니다.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pendingCrash = {
                    DebugLogger.w("CrashTest", "의도적 Java 예외 크래시")
                    throw IllegalStateException("크래시 테스트: Java 메인스레드 예외 (의도적)")
                }
            }) { Text("Java 예외") }
            OutlinedButton(onClick = {
                pendingCrash = {
                    DebugLogger.w("CrashTest", "의도적 SIGSEGV 크래시")
                    android.os.Process.sendSignal(android.os.Process.myPid(), 11)
                }
            }) { Text("SIGSEGV") }
            OutlinedButton(onClick = {
                pendingCrash = {
                    DebugLogger.w("CrashTest", "의도적 SIGABRT 크래시")
                    android.os.Process.sendSignal(android.os.Process.myPid(), 6)
                }
            }) { Text("SIGABRT") }
        }
    }

    pendingCrash?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingCrash = null },
            title = { Text("크래시 실행?") },
            text = {
                Text("앱이 강제 종료됩니다. 크래시 감지 테스트용입니다. 계속할까요?")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrash = null
                    action()
                }) { Text("실행", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCrash = null }) { Text("취소") }
            },
        )
    }
}

