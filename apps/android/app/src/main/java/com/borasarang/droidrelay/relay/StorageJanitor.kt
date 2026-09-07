package com.borasarang.droidrelay.relay

import android.content.Context

/** 보관함 자동 운영 — 쿼터 정리 + 자동 분류 (v0.19) */
object StorageJanitor {

    /** 확장자 → 분류 폴더 (null이면 루트 유지) */
    fun categoryFor(name: String): String? {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "ogv", "ts" -> "영상"
            "mp3", "m4a", "ogg", "oga", "wav", "flac", "opus" -> "음악"
            "pdf", "epub", "txt", "srt", "vtt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z" -> "문서"
            else -> null
        }
    }

    /** 다운로드 완료 후처리 — 분류 후 쿼터 검사 */
    fun onCompleted(context: Context, file: java.io.File) {
        val s = SettingsRepository.get(context).firstBlocking()
        var target = file
        if (s.autoClassify) {
            classifyFile(file)?.let { target = it }
        }
        if (s.storageQuotaGb > 0) {
            enforceQuota(s.storageQuotaGb)
        }
        DebugLogger.d("Janitor", "완료 후처리 '${target.name}' 분류=${s.autoClassify} 쿼터=${s.storageQuotaGb}GB")
    }

    /** 쿼터만 검사 (토렌트 보관함 이동 후 등 파일 특정 없는 경우) */
    fun enforceIfNeeded(context: Context) {
        val q = SettingsRepository.get(context).firstBlocking().storageQuotaGb
        if (q > 0) enforceQuota(q)
    }

    /** 단일 파일을 분류 폴더로 이동 — 이동된 파일 반환, 대상 없으면 null */
    fun classifyFile(file: java.io.File): java.io.File? {
        val category = categoryFor(file.name) ?: return null
        if (!file.exists() || !file.isFile) return null
        if (file.parentFile?.name == category) return file
        val dir = StorageGuard.storageFile(category) ?: return null
        if (!dir.exists()) dir.mkdirs()
        val dest = java.io.File(dir, file.name)
        if (dest.exists()) return file // 이름 충돌 시 원본 유지
        val moved = runCatching {
            if (file.renameTo(dest)) true
            else {
                file.copyTo(dest, overwrite = false)
                file.delete()
                true
            }
        }.getOrDefault(false)
        if (moved) DebugLogger.i("Janitor", "[FEATURE] 자동 분류 '${file.name}' → $category/")
        return if (moved) dest else null
    }

    /** 보관함 전체 크기 (휴지통 제외) */
    fun dirSize(root: java.io.File = StorageGuard.dlRoot): Long {
        if (!root.exists()) return 0L
        var total = 0L
        root.walkTopDown()
            .onEnter { it.name != ".trash" }
            .forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 쿼터 초과 시 오래된 파일부터 휴지통 이동 — 이동 건수 반환 */
    fun enforceQuota(
        quotaGb: Int,
        root: java.io.File = StorageGuard.dlRoot,
        trashDir: java.io.File = StorageGuard.trashDir,
    ): Int {
        if (quotaGb <= 0) return 0
        val limit = quotaGb.toLong() * 1_073_741_824L
        var size = dirSize(root)
        if (size <= limit) return 0
        if (!trashDir.exists()) trashDir.mkdirs()
        var moved = 0
        root.walkTopDown()
            .onEnter { it.name != ".trash" }
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .forEach { f ->
                if (size <= limit) return@forEach
                val sizeBefore = f.length()
                var dest = java.io.File(trashDir, f.name)
                var i = 2
                while (dest.exists()) {
                    dest = java.io.File(trashDir, "${f.name}-$i"); i++
                }
                val ok = runCatching {
                    if (f.renameTo(dest)) true
                    else {
                        f.copyTo(dest, overwrite = false)
                        f.delete()
                        true
                    }
                }.getOrDefault(false)
                if (ok) {
                    size -= sizeBefore
                    moved++
                }
            }
        // 빈 폴더 정리
        root.listFiles()
            ?.filter { it.isDirectory && it.name != ".trash" && (it.listFiles()?.isEmpty() == true) }
            ?.forEach { it.delete() }
        DebugLogger.i("Janitor", "[FEATURE] 쿼터 정리 ${quotaGb}GB 초과 → ${moved}개 휴지통 이동")
        return moved
    }
}
