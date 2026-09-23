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
import androidx.compose.runtime.collectAsState
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
internal fun ServerSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
    onPortChanged: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
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
                scope.launch {
                    repo.setPort(random)
                    onPortChanged(random)
                }
            }) { Text("랜덤") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = {
                val p = portText.toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    DebugLogger.w("Settings", "포트 무효 값: $portText (E-AND-DOWN-2002)")
                } else if (s.httpsEnabled && !SettingsConstraints.validPorts(p, s.httpsPort)) {
                    DebugLogger.w("Settings", "HTTP 포트 충돌: HTTP $p = HTTPS ${s.httpsPort} (E-AND-SRV-0111)")
                } else if (p != s.port) {
                    DebugLogger.i("Settings", "포트 변경 ${s.port} → $p")
                    scope.launch {
                        repo.setPort(p)
                        onPortChanged(p)
                    }
                }
            }) { Text("적용") }
        }

        // Line 1b: HTTPS 사용 스위치 + 포트 입력 + 랜덤 + 적용 (v0.36 개별 제어)
        SwitchRow("HTTPS 사용", s.httpsEnabled) { v ->
            scope.launch {
                repo.setHttpsEnabled(v)
                DebugLogger.i("Settings", if (v) "[FEATURE] HTTPS 사용 설정" else "[FEATURE] HTTPS 끔 설정 — HTTP 단일 동작")
            }
        }
        var httpsPortText by remember(s.httpsPort) { mutableStateOf(s.httpsPort.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = httpsPortText,
                onValueChange = { httpsPortText = it.filter { c -> c.isDigit() } },
                label = { Text("HTTPS 포트") },
                singleLine = true,
                enabled = s.httpsEnabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = s.httpsEnabled, onClick = {
                val random = (9000..9999).random()
                httpsPortText = random.toString()
                DebugLogger.i("Settings", "랜덤 HTTPS 포트 생성 $random")
                scope.launch {
                    if (SettingsConstraints.validPorts(s.port, random)) {
                        repo.setHttpsPort(random)
                        onPortChanged(random)
                    } else {
                        DebugLogger.w("Settings", "HTTPS 포트 충돌: HTTP ${s.port} = HTTPS $random (E-AND-SRV-0111)")
                    }
                }
            }) { Text("랜덤") }
            Spacer(Modifier.width(4.dp))
            Button(enabled = s.httpsEnabled, onClick = {
                val p = httpsPortText.toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    DebugLogger.w("Settings", "HTTPS 포트 무효 값: $httpsPortText (E-AND-DOWN-2002)")
                } else if (!SettingsConstraints.validPorts(s.port, p)) {
                    DebugLogger.w("Settings", "HTTPS 포트 충돌: HTTP ${s.port} = HTTPS $p (E-AND-SRV-0111)")
                } else if (p != s.httpsPort) {
                    DebugLogger.i("Settings", "[FEATURE] HTTPS 포트 변경 ${s.httpsPort} → $p")
                    scope.launch {
                        repo.setHttpsPort(p)
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
                    serverState.running && serverState.httpsEnabled -> "${serverState.port}(HTTP) · ${serverState.httpsPort}(HTTPS) 실행 중"
                    serverState.running -> "${serverState.port}(HTTP) 실행 중 · HTTPS 끔"
                    serverState.httpsEnabled -> "${serverState.port}(HTTP) · ${serverState.httpsPort}(HTTPS) 대기 중"
                    else -> "${serverState.port}(HTTP) 대기 중 · HTTPS 끔"
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

        // Line 3: 자동 시작 분리 (v0.36)
        SwitchRow("재부팅 시 서버 자동 시작", s.bootAutoStart) { v -> scope.launch { repo.setBootAutoStart(v) } }
        SwitchRow("앱 실행 시 서버 자동 시작", s.launchAutoStart) { v -> scope.launch { repo.setLaunchAutoStart(v) } }

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
    
}

