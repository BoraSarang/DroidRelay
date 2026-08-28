package com.borasarang.droidrelay.relay

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * 외장 스토리지 감지 매니저 (Phase 3).
 * USB OTG / 외장 SSD 마운트 감지 → 다운로드 경로 제안.
 */
object StorageDetector {

    private const val TAG = "Storage"

    data class StorageInfo(
        val path: String,
        val label: String,
        val totalBytes: Long,
        val freeBytes: Long,
        val isExternal: Boolean,
    )

    /**
     * 사용 가능한 외부 스토리지 목록 반환.
     */
    fun detectExternal(context: Context): List<StorageInfo> {
        val result = mutableListOf<StorageInfo>()

        // SD카드 / 외장 스토리지 (removable)
        val externalDirs = context.getExternalFilesDirs(null)
        DebugLogger.d(TAG, "getExternalFilesDirs ${externalDirs.size}개")
        for (dir in externalDirs) {
            if (dir == null) continue
            val state = Environment.getExternalStorageState(dir)
            if (state != Environment.MEDIA_MOUNTED) continue

            val stat = try { StatFs(dir.path) } catch (_: Exception) { continue }
            val total = stat.blockSizeLong * stat.blockCountLong
            val free = stat.blockSizeLong * stat.availableBlocksLong
            val isRemovable = Environment.isExternalStorageRemovable(dir)

            result.add(StorageInfo(
                path = dir.absolutePath,
                label = if (isRemovable) "외장 스토리지" else "내부 공유",
                totalBytes = total,
                freeBytes = free,
                isExternal = isRemovable,
            ))
            DebugLogger.d(TAG, "스토리지 발견 path=${dir.absolutePath} removable=$isRemovable free=${free / 1_073_741_824}GB")
        }

        // USB OTG 감지 (/storage/ 하위 removable 마운트)
        val storageDir = File("/storage")
        storageDir.listFiles()?.forEach { mount ->
            if (!mount.isDirectory) return@forEach
            val state = try {
                val method = Environment::class.java.getMethod("getStorageState", File::class.java)
                method.invoke(null, mount) as? String
            } catch (_: Exception) { null }

            if (state == Environment.MEDIA_MOUNTED) {
                val stat = try { StatFs(mount.path) } catch (_: Exception) { return@forEach }
                val total = stat.blockSizeLong * stat.blockCountLong
                if (total < 1_073_741_824) return@forEach // 1GB 미만은 무시

                val already = result.any { it.path.startsWith(mount.path) }
                if (!already) {
                    result.add(StorageInfo(
                        path = mount.path,
                        label = mount.name,
                        totalBytes = total,
                        freeBytes = stat.blockSizeLong * stat.availableBlocksLong,
                        isExternal = true,
                    ))
                }
            }
        }

        return result
    }

    /**
     * 가장 여유 공간이 많은 외부 스토리지 경로 반환.
     */
    fun bestExternalPath(context: Context): String? {
        val path = detectExternal(context)
            .filter { it.isExternal && it.freeBytes > 1_073_741_824 }
            .maxByOrNull { it.freeBytes }
            ?.let { File(it.path, "DroidRelay").absolutePath }
        DebugLogger.d(TAG, "bestExternalPath result=$path")
        return path
    }
}
