package com.borasarang.droidrelay.relay

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 영상 썸네일 — FFmpeg 1프레임 추출 + 캐시 (T-949) */
object ThumbManager {
    private const val TAG = "Thumb"
    private val locks = ConcurrentHashMap<String, Any>()

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ogv", "ts")

    fun isThumbable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

    /** 캐시된 썸네일 반환 — 없으면 생성, 불가면 null */
    fun thumbFor(context: Context, src: File): File? {
        if (!isThumbable(src.name) || !src.exists() || !src.isFile || src.length() <= 0) return null
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val key = md5("${src.name}|${src.lastModified()}|${src.length()}")
        val out = File(dir, "$key.jpg")
        if (out.exists() && out.length() > 0) return out
        synchronized(locks.getOrPut(key) { Any() }) {
            if (out.exists() && out.length() > 0) return out
            prune(dir)
            for (ss in listOf("10", "1")) {
                val ok = runCatching {
                    val s = FFmpegKit.execute(
                        "-hide_banner -loglevel error -ss $ss -i \"${src.absolutePath}\" " +
                            "-frames:v 1 -vf scale=320:-1 -y \"${out.absolutePath}\"",
                    )
                    ReturnCode.isSuccess(s.returnCode) && out.exists() && out.length() > 0
                }.getOrDefault(false)
                if (ok) {
                    DebugLogger.i(TAG, "[FEATURE] 썸네일 생성 '${src.name}'")
                    return out
                }
                out.delete()
            }
            return null
        }
    }

    /** 캐시 상한 300개 — 오래된 것부터 정리 */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size < 300) return
        files.take(files.size - 299).forEach { runCatching { it.delete() } }
    }

    private fun md5(s: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
