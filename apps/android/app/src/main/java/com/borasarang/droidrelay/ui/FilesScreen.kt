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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.DebugLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PublishedFile(
    val uri: android.net.Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,
)

/** 확장자별 아이콘·색상 매핑 (T-117) */
private data class FileTypeStyle(val icon: String, val color: Color)

private fun fileTypeOf(name: String): FileTypeStyle {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp4", "mkv", "mov", "avi", "wmv", "webm" -> FileTypeStyle("🎬", Color(0xFFE53935))
        "mp3", "flac", "wav", "aac", "ogg", "m4a" -> FileTypeStyle("🎵", Color(0xFF8E24AA))
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> FileTypeStyle("🖼", Color(0xFF1E88E5))
        "pdf", "doc", "docx", "txt", "hwp", "pptx", "xlsx" -> FileTypeStyle("📄", Color(0xFFFF8F00))
        "zip", "7z", "tar", "gz", "rar" -> FileTypeStyle("📦", Color(0xFF43A047))
        "apk", "exe", "dmg", "deb", "rpm" -> FileTypeStyle("⚙", Color(0xFF546E7A))
        else -> FileTypeStyle("📎", Color(0xFF90A4AE))
    }
}

private fun fmtBytesFs(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

private fun fmtDate(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    return sdf.format(Date(millis))
}

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val files = remember { mutableStateListOf<PublishedFile>() }

    // 삭제 확인 다이얼로그 상태
    val deleteTarget = remember { mutableStateOf<PublishedFile?>(null) }
    // 이름변경 다이얼로그 상태
    val renameTarget = remember { mutableStateOf<PublishedFile?>(null) }
    val renameText = remember { mutableStateOf("") }

    val refresh = {
        files.clear()
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
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
                        dateModified = c.getLong(3) * 1000,
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
            val style = fileTypeOf(f.name)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 확장자 아이콘
                    Text(style.icon, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(fmtBytesFs(f.size), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            if (f.dateModified > 0) {
                                Text(fmtDate(f.dateModified), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
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
                        renameTarget.value = f
                        renameText.value = f.name
                    }) { Icon(Icons.Filled.Edit, "이름변경", tint = cs.onSurfaceVariant) }
                    IconButton(onClick = {
                        deleteTarget.value = f
                    }) { Icon(Icons.Filled.Delete, "삭제", tint = cs.error) }
                }
            }
        }
        if (files.isEmpty()) {
            item { Text("아직 완료된 파일이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    // 삭제 확인 다이얼로그
    deleteTarget.value?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("파일 삭제") },
            text = { Text("'${f.name}'을(를) 정말 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    DebugLogger.i("Files", "삭제 확인 '${f.name}'")
                    runCatching {
                        context.contentResolver.delete(f.uri, null, null)
                    }.onFailure { DebugLogger.e("Files", "삭제 실패", it) }
                    deleteTarget.value = null
                    refresh.invoke()
                }) { Text("삭제", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) { Text("취소") }
            },
        )
    }

    // 이름변경 다이얼로그
    renameTarget.value?.let { f ->
        AlertDialog(
            onDismissRequest = { renameTarget.value = null },
            title = { Text("이름변경") },
            text = {
                OutlinedTextField(
                    value = renameText.value,
                    onValueChange = { renameText.value = it },
                    label = { Text("파일 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.value.trim()
                    if (newName.isNotBlank() && newName != f.name) {
                        DebugLogger.i("Files", "이름변경 '${f.name}' → '$newName'")
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, newName)
                        }
                        runCatching {
                            context.contentResolver.update(f.uri, values, null, null)
                        }.onFailure { DebugLogger.e("Files", "이름변경 실패", it) }
                        renameTarget.value = null
                        refresh.invoke()
                    }
                }) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget.value = null }) { Text("취소") }
            },
        )
    }
}
