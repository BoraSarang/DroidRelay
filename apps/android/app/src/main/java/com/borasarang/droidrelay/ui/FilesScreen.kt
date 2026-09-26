package com.borasarang.droidrelay.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/** 보관함 정렬 기준 — DIR(디렉토리 우선)이 기본, 어느 기준이든 폴더는 파일보다 위 (NAME만 예외 혼합) */
private enum class SortMode(val label: String) {
    DIR("디렉토리순"),
    DATE("최신순"),
    NAME("이름순"),
    SIZE("용량순"),
}

/** 폴더 용량 — 재귀 합계 (.trash 제외) */
private fun dirSizeOf(f: File): Long =
    if (f.isFile) f.length()
    else f.walkTopDown().onEnter { it.name != ".trash" }.filter { it.isFile }.sumOf { it.length() }

private fun sortComparator(mode: SortMode): Comparator<StorageItem> = when (mode) {
    SortMode.DIR -> compareByDescending<StorageItem> { it.isDir }.thenBy { it.name }
    SortMode.DATE -> compareByDescending<StorageItem> { it.isDir }.thenByDescending { it.modified }
    SortMode.SIZE -> compareByDescending<StorageItem> { it.isDir }.thenByDescending { it.size }
    SortMode.NAME -> compareBy { it.name }
}

private fun fmtDateS(ts: Long): String =
    if (ts <= 0) "" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

private fun fmtBytesS(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

/** 폴더를 cache ZIP으로 묶음 — level 0 패스스루 + 심볼릭링크 탈출 차단 (서버 /dl-folder와 동일 규칙) */
private fun zipFolder(context: android.content.Context, dir: File): File {
    val root = dir.canonicalFile
    require(root.isDirectory) { "폴더 없음" }
    val safeName = dir.name.replace(Regex("[^a-zA-Z0-9가-힣._-]"), "_").ifBlank { "download" }
    val out = File(context.cacheDir, "share-$safeName.zip")
    if (out.exists()) out.delete()
    java.io.BufferedOutputStream(out.outputStream(), 256 * 1024).use { buffered ->
        java.util.zip.ZipOutputStream(buffered).use { zip ->
            zip.setLevel(0)
            fun addDir(d: File, prefix: String) {
                d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val c = runCatching { f.canonicalFile }.getOrNull() ?: return@forEach
                    if (!c.path.startsWith(root.path)) return@forEach
                    val entryName = prefix + f.name
                    if (f.isDirectory) {
                        zip.putNextEntry(java.util.zip.ZipEntry("$entryName/"))
                        zip.closeEntry()
                        addDir(f, "$entryName/")
                    } else if (f.isFile) {
                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                        f.inputStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) zip.write(buf, 0, n)
                        }
                        zip.closeEntry()
                    }
                }
            }
            addDir(root, "")
        }
    }
    return out
}

private fun shareZip(context: android.content.Context, zip: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", zip,
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(send, "폴더 ZIP 공유"))
}

private val DL_ROOT = com.borasarang.droidrelay.relay.StorageGuard.dlRoot

@Composable
fun FilesScreen(onShowSnack: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val currentPath = remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<StorageItem>() }
    val sortMode = remember { mutableStateOf(SortMode.DIR) }
    val sortMenuOpen = remember { mutableStateOf(false) }

    val showNewFolder = remember { mutableStateOf(false) }
    val newFolderName = remember { mutableStateOf("") }

    val renameTarget = remember { mutableStateOf<StorageItem?>(null) }
    val renameText = remember { mutableStateOf("") }

    val deleteTarget = remember { mutableStateOf<StorageItem?>(null) }

    val cutItem = remember { mutableStateOf<StorageItem?>(null) }

    /** 목록 로딩 — suspend 구조로, LaunchedEffect 재실행 시 이전 로딩이 취소되어 정렬 race 방지 */
    val refresh: suspend () -> Unit = {
        runCatching {
            val path = currentPath.value
            val mode = sortMode.value
            val list = withContext(Dispatchers.IO) {
                val dir = if (path.isEmpty()) DL_ROOT else File(DL_ROOT, path)
                if (!dir.exists()) dir.mkdirs()
                dir.listFiles()?.map { f ->
                    StorageItem(
                        name = f.name,
                        isDir = f.isDirectory,
                        size = dirSizeOf(f),
                        count = if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0,
                        modified = f.lastModified(),
                    )
                }?.filter { !(path.isEmpty() && it.name == ".trash") }
                    ?.sortedWith(sortComparator(mode)) ?: emptyList()
            }
            items.clear()
            items.addAll(list)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            DebugLogger.e("Storage", "목록 조회 실패", it)
        }
    }

    LaunchedEffect(currentPath.value, sortMode.value) { refresh() }

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
            Spacer(Modifier.weight(1f))
            // 정렬 기준 선택 (최신순·이름순·용량순·디렉토리순)
            Box {
                IconButton(onClick = { sortMenuOpen.value = true }) {
                    Icon(Icons.Filled.Sort, "정렬", tint = cs.primary)
                }
                DropdownMenu(expanded = sortMenuOpen.value, onDismissRequest = { sortMenuOpen.value = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    mode.label,
                                    color = if (sortMode.value == mode) cs.primary else cs.onSurface,
                                    fontWeight = if (sortMode.value == mode) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                if (sortMode.value == mode) Icon(Icons.Filled.Check, null, tint = cs.primary)
                                else Spacer(Modifier.size(24.dp))
                            },
                        onClick = {
                            sortMenuOpen.value = false
                            if (sortMode.value != mode) sortMode.value = mode
                        },
                        )
                    }
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
                                (if (item.isDir) {
                                    "${item.count}개" + (if (item.size > 0) " · ${fmtBytesS(item.size)}" else "")
                                } else {
                                    fmtBytesS(item.size)
                                }) + (fmtDateS(item.modified).let { if (it.isNotEmpty()) " · $it" else "" }),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        // 이름 변경
                        IconButton(onClick = { renameTarget.value = item; renameText.value = item.name }) {
                            Icon(Icons.Filled.Edit, "이름변경", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                        // 폴더 ZIP 공유 (T-938 — 서버 /dl-folder와 동일 level 0 패스스루)
                        if (item.isDir) {
                            IconButton(onClick = {
                                toast("ZIP 만드는 중…")
                                scope.launch(Dispatchers.IO) {
                                    runCatching { zipFolder(context, File(DL_ROOT, fullPath(item.name))) }
                                        .onSuccess { zip ->
                                            withContext(Dispatchers.Main) { shareZip(context, zip) }
                                        }
                                        .onFailure { e ->
                                            DebugLogger.e("Storage", "폴더 ZIP 실패", e)
                                            toast("ZIP 실패: ${e.message}")
                                        }
                                }
                            }) {
                                Icon(Icons.Filled.Share, "폴더 ZIP 공유", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                            }
                        }
                        // 잘라내기 (파일만)
                        if (!item.isDir) {
                            IconButton(onClick = { cutItem.value = item; toast("${item.name} 잘라냄") }) {
                                Icon(Icons.Filled.ContentCut, "잘라내기", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                            }
                        }
                        // 삭제
                        IconButton(onClick = { deleteTarget.value = item }) {
                            Icon(Icons.Filled.Delete, "삭제", tint = cs.error, modifier = Modifier.size(24.dp))
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
