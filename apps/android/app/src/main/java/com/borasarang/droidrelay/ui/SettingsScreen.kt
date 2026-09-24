package com.borasarang.droidrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ServerSection(s, repo, scope, onPortChanged)
        HorizontalDivider(color = cs.outlineVariant)
        DisplaySection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        DownloadSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        SecuritySection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        TorrentSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        GuardSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        ScheduleSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        DebridSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        TunnelSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        McpSection(s, repo, scope)
        HorizontalDivider(color = cs.outlineVariant)
        ResetSection(repo)
        HorizontalDivider(color = cs.outlineVariant)
        CrashTestSection()
        HorizontalDivider(color = cs.outlineVariant)
        AboutSection(ctx)
    }
}
