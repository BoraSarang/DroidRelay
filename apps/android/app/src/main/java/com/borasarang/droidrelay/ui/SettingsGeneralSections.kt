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
internal fun DisplaySection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
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
                        scope.launch { repo.setThemeMode(mode) }
                    },
                    label = { Text(label) },
                )
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            SwitchRow("Material You 동적 색상 (배경화면 따라가기)", s.dynamicColor) { v -> scope.launch { repo.setDynamicColor(v) } }
        }
    }
    
}


@Composable
internal fun DownloadSection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── 다운로드 ──
    SettingSection("다운로드") {
        Text(
            "동시 다운로드 수: ${s.concurrency}개",
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = s.concurrency.toFloat(),
            onValueChange = { scope.launch { repo.setConcurrency(it.toInt()) } },
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
                scope.launch { repo.setSpeedLimit(kb) }
            },
            valueRange = 0f..2048f,
        )
        SwitchRow("다운로드 알림 표시", s.notifications) { v -> scope.launch { repo.setNotifications(v) } }

        Spacer(Modifier.height(12.dp))
        Text("보관함 자동 운영", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            "보관함 쿼터: ${if (s.storageQuotaGb == 0) "끔" else "${s.storageQuotaGb}GB (초과 시 오래된 파일 자동 휴지통)"}",
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = s.storageQuotaGb.toFloat(),
            onValueChange = { v -> scope.launch { repo.setStorageQuotaGb(v.toInt()) } },
            valueRange = 0f..128f,
            steps = 127,
        )
        SwitchRow("완료 파일 자동 분류 (영상/음악/문서)", s.autoClassify) { v -> scope.launch { repo.setAutoClassify(v) } }

        Spacer(Modifier.height(12.dp))
        Text("전역 속도 제한 (다운로드·토렌트 공통)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        val dlMbps = if (s.maxDownloadBps > 0) (s.maxDownloadBps / 1_048_576).toInt().coerceIn(1, 10) else 0
        SwitchRow(
            "전역 다운로드 속도 제한",
            s.maxDownloadBps > 0,
        ) { on -> scope.launch { repo.setMaxDownloadBps(if (on) 3L * 1_048_576 else 0L) } }
        if (dlMbps > 0) {
            Text("${dlMbps} Mbps", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = dlMbps.toFloat(),
                onValueChange = { v -> scope.launch { repo.setMaxDownloadBps(v.roundToInt().toLong() * 1_048_576) } },
                valueRange = 1f..10f,
                steps = 8,
            )
        }
        val ulMbps = if (s.maxUploadBps > 0) (s.maxUploadBps / 1_048_576).toInt().coerceIn(1, 10) else 0
        SwitchRow(
            "전역 업로드 속도 제한",
            s.maxUploadBps > 0,
        ) { on -> scope.launch { repo.setMaxUploadBps(if (on) 3L * 1_048_576 else 0L) } }
        if (ulMbps > 0) {
            Text("${ulMbps} Mbps", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = ulMbps.toFloat(),
                onValueChange = { v -> scope.launch { repo.setMaxUploadBps(v.roundToInt().toLong() * 1_048_576) } },
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
                    scope.launch { repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE) }
                },
                label = { Text("없음") },
            )
            androidx.compose.material3.FilterChip(
                selected = s.completionAction == SettingsConstraints.COMPLETION_ACTION_STOP_SERVER,
                onClick = {
                    DebugLogger.i("Settings", "완료 후 동작 → 서버 정지")
                    scope.launch { repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_STOP_SERVER) }
                },
                label = { Text("전체 완료 시 서버 정지") },
            )
        }

        Spacer(Modifier.height(12.dp))
        SpeedScheduleSection(s.speedSchedule)
    }
    
}


@Composable
internal fun SecuritySection(
    s: AppSettings,
    repo: SettingsRepository,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    // ── 보안 ──
    SettingSection("보안") {
        // 접속 범위
        Text("클라이언트 접속 범위", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessScope.entries.forEach { ascope ->
                val selected = s.accessScope == ascope
                val label = when (ascope) {
                    AccessScope.SUBNET_ONLY -> "같은 핫스팟"
                    AccessScope.ANY_WITH_PASSWORD -> "암호만 있으면"
                    AccessScope.APPROVED_ONLY -> "승인만"
                }
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = {
                        DebugLogger.i("Settings", "접속 범위 변경 → $ascope")
                        scope.launch { repo.setAccessScope(ascope) }
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
            scope.launch { repo.setWebAuth(it, user, pass) }
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
                scope.launch { repo.setWebAuth(true, user, pass.ifBlank { s.webPassword }) }
            }) { Text("인증 정보 저장") }
        }
        Spacer(Modifier.height(12.dp))
        var guestEnabled by remember(s.guestEnabled) { mutableStateOf(s.guestEnabled) }
        var guestPass by remember { mutableStateOf("") }
        SwitchRow("게스트 읽기전용 (열람·다운로드만)", guestEnabled) {
            guestEnabled = it
            scope.launch { repo.setGuestEnabled(it) }
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
                        scope.launch { repo.setGuestPassword(guestPass) }
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
                    scope.launch { repo.removeAllowedIp(ip) }
                }) { Text("해제") }
            }
        }
    }
    
}

