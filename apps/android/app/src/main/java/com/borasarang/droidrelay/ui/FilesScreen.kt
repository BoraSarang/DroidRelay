package com.borasarang.droidrelay.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.borasarang.droidrelay.relay.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class StorageItem(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val count: Int = 0,
    val modified: Long = 0,
)

private fun fmtDateS(ts: Long): String =
    if (ts <= 0) "" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

private fun fmtBytesS(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

private val DL_ROOT = File("/sdcard/Download/DroidRelay")

@Composable
fun FilesScreen(onShowSnack: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val currentPath = remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<StorageItem>() }

    val showNewFolder = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }

    val renameTarget = remember { mutableStateOf<StorageItem?>(null) }
    val renameText = remember { mutableStateOf("") }

    val deleteTarget = remember { mutableStateOf<StorageItem?>(null) }

    val cutItem = remember { mutableStateOf<StorageItem?>(null) }

    val refresh = {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = if (currentPath.value.isEmpty()) DL_ROOT else File(DL_ROOT, currentPath.value)
                if (!dir.exists()) dir.mkdirs()
                val list = dir.listFiles()?.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name }
                )?.filter { !(currentPath.value.isEmpty() && it.name == ".trash") }?.map { f ->
                    StorageItem(
                        name = f.name,
                        isDir = f.isDirectory,
                        size = if (f.isFile) f.length() else 0,
                        count = if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0,
                        modified = f.lastModified(),
                    )
                } ?: emptyList()
                withContext(Dispatchers.Main) {
                    items.clear()
                    items.addAll(list)
                }
            }.onFailure { DebugLogger.e("Storage", "목록 조회 실패", it) }
        }
    }

    LaunchedEffect(currentPath.value) { refresh.invoke() }

    fun fullPath(name: String) = if (currentPath.value.isEmpty()) name else "${currentPath.value}/$name"

    fun toast(msg: String) { onShowSnack(msg) }

    Column(Modifier.fillMaxSize()) {
        // 상단 브레드크럼
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentPath.value.isNotEmpty()) {
                IconButton(onClick = {
                    currentPath.value = currentPath.value.substringBeforeLast('/')
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                }
            }
            val parts = currentPath.value.split('/').filter { it.isNotEmpty() }
            Text(
                "보관함",
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                modifier = Modifier.clickable { currentPath.value = "" },
            )
            parts.forEachIndexed { idx, part ->
                Text(" / ", color = cs.onSurfaceVariant)
                Text(
                    part,
                    color = cs.primary,
                    modifier = Modifier.clickable {
                        currentPath.value = parts.take(idx + 1).joinToString("/")
                    },
                )
            }
        }

        // 도구 모음
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { showNewFolder.value = true }) {
                Icon(Icons.Filled.CreateNewFolder, "폴더 만들기", tint = cs.primary)
            }
            if (cutItem.value != null) {
                IconButton(onClick = {
                    val cut = cutItem.value ?: return@IconButton
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val src = File(DL_ROOT, fullPath(cut.name))
                            val dstDir = if (currentPath.value.isEmpty()) DL_ROOT else File(DL_ROOT, currentPath.value)
                            val dst = File(dstDir, cut.name)
                            src.copyTo(dst, overwrite = true)
                            src.deleteRecursively()
                            cutItem.value = null
                            withContext(Dispatchers.Main) { refresh.invoke() }
                            toast("이동 완료")
                        }.onFailure { toast("이동 실패") }
                    }
                }) {
                    Icon(Icons.Filled.Add, "붙여넣기", tint = cs.tertiary)
                }
            }
        }

        // 파일 목록
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items.size, key = { items[it].name + items[it].isDir }) { idx ->
                val item = items[idx]
                Card(
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (item.isDir) {
                            currentPath.value = fullPath(item.name)
                        }
                    },
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (item.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            null,
                            tint = if (item.isDir) cs.tertiary else cs.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                            Text(
                                (if (item.isDir) "${item.count}개" else fmtBytesS(item.size)) +
                                    (fmtDateS(item.modified).let { if (it.isNotEmpty()) " · $it" else "" }),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        // 이름 변경
                        IconButton(onClick = { renameTarget.value = item; renameText.value = item.name }) {
                            Icon(Icons.Filled.Edit, "이름변경", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                        // 잘라내기 (파일만)
                        if (!item.isDir) {
                            IconButton(onClick = { cutItem.value = item; toast("${item.name} 잘라냄") }) {
                                Icon(Icons.Filled.ContentCut, "잘라내기", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                        // 삭제
                        IconButton(onClick = { deleteTarget.value = item }) {
                            Icon(Icons.Filled.Delete, "삭제", tint = cs.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.InsertDriveFile, null, tint = cs.outline, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("비어 있습니다", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // 새 폴더 다이얼로그
    if (showNewFolder.value) {
        AlertDialog(
            onDismissRequest = { showNewFolder.value = false },
            title = { Text("폴더 만들기") },
            text = {
                OutlinedTextField(
                    value = newFolderName.value,
                    onValueChange = { newFolderName.value = it },
                    label = { Text("폴더 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolderName.value.trim()
                    if (name.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val dir = if (currentPath.value.isEmpty()) DL_ROOT else File(DL_ROOT, currentPath.value)
                            val target = File(dir, name)
                            if (target.exists()) {
                                toast("이미 존재")
                            } else {
                                target.mkdirs()
                                withContext(Dispatchers.Main) { refresh.invoke() }
                                toast("폴더 생성: $name")
                            }
                        }
                    }
                    showNewFolder.value = false
                    newFolderName.value = ""
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder.value = false; newFolderName.value = "" }) { Text("취소") }
            },
        )
    }

    // 이름변경 다이얼로그
    renameTarget.value?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget.value = null },
            title = { Text("이름변경") },
            text = {
                OutlinedTextField(
                    value = renameText.value,
                    onValueChange = { renameText.value = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.value.trim()
                    if (newName.isNotBlank() && newName != item.name) {
                        scope.launch(Dispatchers.IO) {
                            val src = File(DL_ROOT, fullPath(item.name))
                            val dst = File(DL_ROOT, fullPath(newName))
                            if (dst.exists()) toast("이미 존재") else {
                                src.renameTo(dst)
                                withContext(Dispatchers.Main) { refresh.invoke() }
                            }
                        }
                    }
                    renameTarget.value = null
                }) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget.value = null }) { Text("취소") }
            },
        )
    }

    // 삭제 확인 다이얼로그
    deleteTarget.value?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("삭제") },
            text = { Text("'${item.name}'을(를) 정말 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val target = File(DL_ROOT, fullPath(item.name))
                        target.deleteRecursively()
                        withContext(Dispatchers.Main) { refresh.invoke() }
                    }
                    deleteTarget.value = null
                }) { Text("삭제", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) { Text("취소") }
            },
        )
    }
}
