package com.borasarang.droidrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.AccessScope
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.relay.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onPortChanged: (Int) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val settings by repo.settings.collectAsState(initial = null)
    val s = settings ?: return
    val cs = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ── 서버 ──
        SettingSection("서버") {
            var portText by remember(s.port) { mutableStateOf(s.port.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() } },
                label = { Text("포트 (1024~65535)") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        DebugLogger.w("Settings", "포트 무효 값: $portText (E-AND-DOWN-2002)")
                    } else if (p != s.port) {
                        DebugLogger.i("Settings", "포트 변경 ${s.port} → $p")
                        onPortChanged(p)
                        kotlinx.coroutines.MainScope().launch { repo.setPort(p) }
                    }
                }) { Text("포트 적용") }
            }
            SwitchRow("앱 실행 시 서버 자동 시작", s.autoStart) { v -> kotlinx.coroutines.MainScope().launch { repo.setAutoStart(v) } }
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
