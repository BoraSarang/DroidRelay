package com.borasarang.droidrelay.relay

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 비디오 다운로드 — FFmpegKit 통합 파이프라인.
 * - yt/스트림 모두 FFmpeg 단일 실행 (`-c copy` 머지/패스스루)
 * - 진행: 출력 파일 크기 2초 폴링 → Job.downloadedBytes/speedBps
 * - 완료: DownloadEngine 공용 보관함(MediaStore) 게시 재사용
 * - 재시작 복원: RUNNING/QUEUED 비디오 잡은 FAILED 처리 (재개 미지원, 재분석 안내)
 */
class VideoDownloadManager(
    private val context: Context,
) {
    private val TAG = "Video"

    private val sessions = ConcurrentHashMap<String, FFmpegSession>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    private val ticks = ConcurrentHashMap<String, Tick>()
    @Volatile private var inited = false

    private data class Tick(var lastSize: Long = 0L, var lastTime: Long = 0L)

    val workDir: File
        get() = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    fun init() {
        if (inited) return
        inited = true
        smokeTest()
        failStaleVideoJobs()
    }

    private fun smokeTest() {
        try {
            val session = FFmpegKit.execute("-hide_banner -version")
            val line = session.allLogsAsString.lines().firstOrNull { "ffmpeg version" in it }
                ?.trim()?.take(80)
            DebugLogger.i(TAG, "[FEATURE] FFmpeg 스모크 성공 — ${line ?: "버전 확인"}")
        } catch (e: Throwable) {
            DebugLogger.e(TAG, "FFmpeg 네이티브 로드 실패 (E-AND-VID-0201)", e)
        }
    }

    private fun failStaleVideoJobs() {
        JobsRepository.all()
            .filter { it.type == "video" && (it.state == JobState.RUNNING || it.state == JobState.QUEUED) }
            .forEach { j ->
                JobsRepository.update(j.id) {
                    it.copy(
                        state = JobState.FAILED,
                        errorCode = "E-AND-VID-0401",
                        errorMessage = "앱 재시작 후 비디오 다운로드는 재개하지 않습니다 — 다시 분석해 다운로드해 주세요 (E-AND-VID-0401)",
                        speedBps = 0L,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                File(workDir, j.filename).delete()
                DebugLogger.w(TAG, "재시작 스테일 비디오 잡 FAILED id=${j.id} '${j.filename}'")
            }
    }

    /** 잡 등록(type=video) 후 즉시 FFmpeg 시작 */
    fun createAndStart(url: String, filename: String, argv: List<String>): Job {
        val job = JobsRepository.add(url, filename, type = "video")
        start(job.id, argv)
        return job
    }

    fun start(jobId: String, argv: List<String>) {
        val job = JobsRepository.get(jobId) ?: return
        val out = File(workDir, job.filename)
        if (out.exists()) out.delete()
        DebugLogger.i(TAG, "[FEATURE] FFmpeg 시작 id=$jobId '${job.filename}' args=${argv.take(6).joinToString(" ")}…")
        JobsRepository.update(jobId) {
            it.copy(state = JobState.RUNNING, startedAt = System.currentTimeMillis(), speedBps = 0L, downloadedBytes = 0L)
        }

        val full = buildList {
            add("-y"); add("-nostdin"); add("-hide_banner")
            addAll(argv)
        }
        val stats = StatisticsCallback { statistics: Statistics ->
            updateProgress(jobId, out, statistics)
        }
        val done = FFmpegSessionCompleteCallback { session ->
            handleComplete(jobId, session, out)
        }
        val session = FFmpegKit.executeWithArgumentsAsync(full.toTypedArray(), done, null, stats)
        sessions[jobId] = session
    }

    private fun updateProgress(jobId: String, out: File, stats: Statistics) {
        val len = runCatching { out.length() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val t = ticks.computeIfAbsent(jobId) { Tick() }
        synchronized(t) {
            if (now - t.lastTime < TICK_MS) return
            val speed = if (t.lastTime > 0) ((len - t.lastSize) * 1000 / (now - t.lastTime)).coerceAtLeast(0) else 0L
            t.lastSize = len
            t.lastTime = now
            JobsRepository.update(jobId) { it.copy(downloadedBytes = len, speedBps = speed) }
        }
    }

    private fun handleComplete(jobId: String, session: FFmpegSession, out: File) {
        sessions.remove(jobId)
        ticks.remove(jobId)
        val now = System.currentTimeMillis()
        val canceled = cancelRequested.remove(jobId) || ReturnCode.isCancel(session.returnCode)
        val size = runCatching { out.length() }.getOrDefault(0L)
        when {
            canceled -> {
                out.delete()
                JobsRepository.update(jobId) {
                    it.copy(
                        state = JobState.CANCELED, errorCode = null,
                        errorMessage = "사용자가 취소했습니다 (E-AND-VID-0400)", speedBps = 0L, finishedAt = now,
                    )
                }
                DebugLogger.w(TAG, "취소됨 id=$jobId")
            }
            ReturnCode.isSuccess(session.returnCode) && size > 0 -> {
                val engine = RelayApp.get(context)
                engine.publishToDownloads(out, jobId)
                if (out.exists()) out.delete()
                JobsRepository.update(jobId) {
                    it.copy(
                        state = JobState.DONE, progress = 1f, downloadedBytes = size,
                        totalBytes = size, speedBps = 0L, finishedAt = now,
                    )
                }
                DebugLogger.perf(TAG, "비디오 완료 id=$jobId '${out.name}' ${size / 1024}KB") {}
            }
            else -> {
                val log = runCatching { session.allLogsAsString }.getOrDefault("")
                val code = when {
                    log.contains("encrypted", true) || log.contains("widevine", true) ||
                        log.contains("cannot decrypt", true) -> "E-AND-VID-0300"
                    log.contains("/proxy?", true) && log.contains("HTTP error 502", true) ->
                        "E-AND-VID-0203"
                    else -> "E-AND-VID-0202"
                }
                val tail = log.lines().takeLast(6).joinToString(" ").take(220)
                out.delete()
                val msg = when (code) {
                    "E-AND-VID-0300" -> "DRM(저작권 보호) 콘텐츠는 다운로드할 수 없습니다"
                    "E-AND-VID-0203" -> "yt-dlp 서버가 YouTube에 차단됐습니다. 서버 IP를 바꾸거나 잠시 후 다시 시도해 주세요"
                    else -> "스트림 다운로드 실패 — 다시 시도하거나 재분석해 주세요"
                }
                JobsRepository.update(jobId) {
                    it.copy(
                        state = JobState.FAILED, errorCode = code,
                        errorMessage = "$msg${if (tail.isNotBlank()) " · $tail" else ""} ($code)",
                        speedBps = 0L, finishedAt = now,
                    )
                }
                DebugLogger.e(TAG, "비디오 실패 id=$jobId ($code) tail=$tail", null)
            }
        }
    }

    fun cancel(jobId: String) {
        cancelRequested += jobId
        val s = sessions[jobId]
        if (s != null) {
            DebugLogger.w(TAG, "취소 요청 id=$jobId")
            s.cancel()
        } else {
            JobsRepository.update(jobId) {
                if (it.type == "video") {
                    it.copy(state = JobState.CANCELED, errorMessage = "사용자가 취소했습니다 (E-AND-VID-0400)", speedBps = 0L)
                } else {
                    it
                }
            }
        }
    }

    fun stopAll() {
        DebugLogger.i(TAG, "video 매니저 중지 — FFmpeg 세션 전체 취소")
        sessions.forEach { (_, s) -> s.cancel() }
        sessions.clear()
        cancelRequested.clear()
    }

    companion object {
        private const val TICK_MS = 1_000L

        /** 사용자 입력/제목 기반 안전 파일명 (확장자 포함) */
        fun safeFilename(raw: String?, ext: String): String {
            val base = (raw ?: "")
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .trim('.', '_', ' ')
                .take(80)
                .ifBlank { "video-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(System.currentTimeMillis())}" }
            return "$base.$ext"
        }
    }
}