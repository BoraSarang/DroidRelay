package com.borasarang.droidrelay.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.SettingsRepository

@Composable
fun SettingsScreen(onPortChanged: (Int) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        DeviceCard()

        // 섹션 자체 제목이 "서버" 라 그룹 라벨은 생략 (중복 방지)
        ServerSection(s, repo, scope, onPortChanged)

        SettingsGroup("디자인") {
            DisplaySection(s, repo, scope)
        }
        SettingsGroup("전송") {
            DownloadSection(s, repo, scope)
            SecuritySection(s, repo, scope)
            GuardSection(s, repo, scope)
            ScheduleSection(s, repo, scope)
        }
        SettingsGroup("토렌트") {
            TorrentSection(s, repo, scope)
            DebridSection(s, repo, scope)
        }
        SettingsGroup("원격 접속") {
            TunnelSection(s, repo, scope)
            McpSection(s, repo, scope)
        }
        SettingsGroup("고급") {
            ResetSection(repo)
            CrashTestSection()
            AboutSection(ctx)
        }
    }
}

/** 상단 기기 카드 — 모델·버전을 한눈에 (목업 `settings` 의 tonal 카드) */
@Composable
private fun DeviceCard() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val model = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = cs.secondaryContainer,
            contentColor = cs.onSecondaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(cs.onSecondaryContainer.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSecondaryContainer,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    model.ifBlank { "이 기기" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v$version · Android ${Build.VERSION.RELEASE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/** 설정 그룹 라벨 — 섹션을 의미 단위로 묶는다 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
        content()
    }
}
