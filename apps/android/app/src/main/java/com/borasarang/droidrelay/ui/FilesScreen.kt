package com.borasarang.droidrelay.ui

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.DebugLogger

private data class PublishedFile(
    val uri: android.net.Uri,
    val name: String,
    val size: Long,
)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val files = remember { mutableStateListOf<PublishedFile>() }
    val refresh = {
        files.clear()
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
            )
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%Download/DroidRelay%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    files += PublishedFile(
                        uri = android.net.Uri.withAppendedPath(collection, c.getLong(0).toString()),
                        name = c.getString(1) ?: "(이름 없음)",
                        size = c.getLong(2),
                    )
                }
            }
        }.onFailure { DebugLogger.e("Files", "파일 목록 조회 실패", it) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh.invoke() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "완료된 파일 (${files.size}) · Download/DroidRelay",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        items(files.size, key = { files[it].uri.toString() }) { idx ->
            val f = files[idx]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(fmtBytesUi(f.size), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        DebugLogger.i("Files", "공유 '${f.name}'")
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, f.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "파일 공유"))
                    }) { Icon(Icons.Filled.Share, "공유", tint = cs.primary) }
                    IconButton(onClick = {
                        DebugLogger.i("Files", "삭제 '${f.name}'")
                        runCatching {
                            context.contentResolver.delete(f.uri, null, null)
                        }.onFailure { DebugLogger.e("Files", "삭제 실패", it) }
                        refresh.invoke()
                    }) { Icon(Icons.Filled.Delete, "삭제", tint = cs.error) }
                }
            }
        }
        if (files.isEmpty()) {
            item { Text("아직 완료된 파일이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun fmtBytesUi(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}
