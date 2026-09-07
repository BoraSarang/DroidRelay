package com.borasarang.droidrelay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borasarang.droidrelay.relay.DebugLogger

/** 디버그 로그 패널 — 다중 선택 후 복사 (AGENTS.md 10.2 필수) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugPanelContent() {
    val lines = remember { mutableStateListOf<String>().apply { addAll(DebugLogger.lines().asReversed()) } }
    val selected = remember { mutableStateListOf<Int>() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    fun copyToClip(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, "$label 완료", android.widget.Toast.LENGTH_SHORT).show()
        DebugLogger.i("Panel", "$label (${text.lines().size}줄)")
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "디버그 로그 (${lines.size}줄 · 최신순)",
            color = cs.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
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
                DebugLogger.clear(); lines.clear(); selected.clear()
            }) { Text("비우기") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            items(lines.size) { idx ->
                val line = lines[idx]
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {
                        if (idx in selected) selected.remove(idx) else selected.add(idx)
                    }),
                ) {
                    Checkbox(
                        checked = idx in selected,
                        onCheckedChange = { checked ->
                            if (checked) selected.add(idx) else selected.remove(idx)
                        },
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = when {
                            line.contains("[E]") -> cs.error
                            line.contains("[W]") -> Color(0xFFFFD59E)
                            line.contains("[I]") -> cs.onSurface
                            else -> cs.onSurfaceVariant
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