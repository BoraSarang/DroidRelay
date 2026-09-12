package com.borasarang.droidrelay.relay

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 비디오 다운로드 — FFmpegKit 통합 파이프라인.
 * - yt/스트림 모두 FFmpeg 단일 실행 (`-c copy` 머지/패스스루)
 * - 진행: 출력 파일 크기 1초 폴링 → Job.downloadedBytes/speedBps
 *   (StatisticsCallback은 `-c copy` 리먹스에서 이벤트를 내지 않아 폴링 방식 사용)
 * - 완료: DownloadEngine 공용 보관함(MediaStore) 게시 재사용
 * - 재시작 복원: RUNNING/QUEUED 비디오 잡은 FAILED 처리 (재개 미지원, 재분석 안내)
 */
class VideoDownloadManager(
    private val context: Context,
) {
    private val TAG = "Video"

    private val sessions = ConcurrentHashMap<String, FFmpegSession>()
    private val logBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val progressFiles = ConcurrentHashMap<String, File>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var inited = false
    @Volatile private var scope: CoroutineScope? = null

    private fun ensureScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

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
    fun createAndStart(url: String, filename: String, argv: List<String>, segmentsTotal: Int = 0, totalDurationMs: Long = 0): Job {
        val job = JobsRepository.add(url, filename, type = "video")
        if (segmentsTotal > 0 || totalDurationMs > 0) {
            JobsRepository.update(job.id) {
                it.copy(
                    segmentsTotal = if (segmentsTotal > 0) segmentsTotal else it.segmentsTotal,
                    totalDurationMs = totalDurationMs,
                )
            }
        }
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

        // FFmpeg -progress 출력(안정적인 진행률 근거)을 잡별 파일로 받는다 — LogCallback은 HLS 브랜치에서 불안정
        val progressFile = File(workDir, ".prog-$jobId.txt")
        if (progressFile.exists()) progressFile.delete()
        progressFiles[jobId] = progressFile

        val full = buildList {
            add("-y"); add("-nostdin"); add("-hide_banner")
            add("-progress"); add(progressFile.absolutePath)
            addAll(argv)
        }
        // HLS 세그먼트 진행 로그 수집용 버퍼 (보조 표시용 — 읽기 전용)
        val logBuf = StringBuilder()
        val logCb = LogCallback { log ->
            if (log != null && log.message.isNotBlank()) {
                synchronized(logBuf) { logBuf.append(log.message).append('\n') }
            }
        }
        val done = FFmpegSessionCompleteCallback { session ->
            handleComplete(jobId, session, out)
        }
        val session = FFmpegKit.executeWithArgumentsAsync(full.toTypedArray(), done, logCb, null)
        sessions[jobId] = session
        logBuffers[jobId] = logBuf
        pollProgress(jobId, session, out)
    }

    /** 출력 파일 크기 1초 폴링 + HLS 진행률 — 우선순위: -progress 재생시간 → 세그먼트 로그 → 파일 크기 */
    private fun pollProgress(jobId: String, session: FFmpegSession, out: File) {
        ensureScope().launch {
            var lastSize = 0L
            var lastTime = 0L
            while (true) {
                val state = session.state
                if (state == SessionState.COMPLETED || state == SessionState.FAILED || !sessions.containsKey(jobId)) break
                val len = runCatching { out.length() }.getOrDefault(0L)
                val now = System.currentTimeMillis()
                val speed = if (lastTime > 0L && now - lastTime > 0L) {
                    ((len - lastSize) * 1000 / (now - lastTime)).coerceAtLeast(0)
                } else {
                    0L
                }
                lastSize = len
                lastTime = now
                JobsRepository.update(jobId) { j ->
                    val prog = computeProgress(jobId, j)
                    val segProgress = if (j.segmentsTotal > 0) currentSegmentProgress(jobId, j) else j.segmentsDone
                    j.copy(downloadedBytes = len, speedBps = speed, segmentsDone = segProgress, progress = prog)
                }
                delay(1_000L)
            }
        }
    }

    /** 진행률 결정: -progress 파일의 out_time / 총재생시간(신뢰) → 세그먼트(불안정) → 파일크기/총용량 순 */
    private fun computeProgress(jobId: String, job: Job): Float {
        if (job.totalDurationMs > 0) {
            val us = progressFiles[jobId]?.let { f -> runCatching { parseOutTimeUs(f.readText()) }.getOrDefault(0L) } ?: 0L
            if (us > 0) return (us / 1000.0 / job.totalDurationMs).toFloat().coerceIn(0f, 1f)
        }
        if (job.segmentsTotal > 0) {
            val done = currentSegmentProgress(jobId, job)
            if (done > 0) return (done.toFloat() / job.segmentsTotal).coerceIn(0f, 1f)
        }
        if (job.totalBytes > 0) return (job.downloadedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
        return job.progress
    }

    /** 축적된 FFmpeg 로그에서 현재 처리된 HLS 세그먼트 수를 파싱 (보조 표시용) */
    private fun currentSegmentProgress(jobId: String, job: Job): Int {
        if (job.segmentsTotal <= 0) return 0
        val buf = logBuffers[jobId]?.toString() ?: return 0
        val done = synchronized(buf) { segmentsDoneFromLog(buf, job.segmentsTotal) }
        return done
    }

    companion object {
        // HLS 세그먼트 오픈 로그: [hls @ 0x..] Opening '0000.ts' for reading (버전/경로에 따라 Text/정규화 URI)
        private val SEG_OPEN_RE = Regex("""Opening\s+['"]([^'"]+\.ts)['"]\s+for\s+reading""", RegexOption.IGNORE_CASE)

        /** FFmpeg 로그에서 HLS 세그먼트 오픈 라인을 찾아 처리된 세그먼트 수를 센다 (순수 함수 — 테스트 대상)
         *  실제 세그먼트 URL은 제각각(url_N/파일명, 0000.ts, 해시 등) → '오픈' 전체 URL(쿼리 제외)의 distinct 수로 센다. */
        fun segmentsDoneFromLog(log: String, total: Int): Int {
            val keys = HashSet<String>()
            for (m in SEG_OPEN_RE.findAll(log)) {
                if (m.groupValues.size > 1) {
                    val url = m.groupValues[1].substringBefore('?')
                    if (url.isNotBlank()) keys.add(url)
                }
            }
            return keys.size.coerceIn(0, total.coerceAtLeast(0))
        }

        /** -progress 파일 내용에서 마지막 out_time_us(마이크로초)를 파싱 (순수 함수 — 테스트 대상) */
        fun parseOutTimeUs(content: String): Long {
            var last = 0L
            for (line in content.lineSequence()) {
                val v = line.trim()
                if (v.startsWith("out_time_us=")) {
                    last = v.substringAfter('=').trim().toLongOrNull() ?: 0L
                }
            }
            return last
        }

        /** Windows 예약 파일명 — 충돌 시 "_" 접두 (대소문자 무시, 확장자 제외 비교) */
        private val RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )

        /** 사용자 입력/제목 기반 안전 파일명 (확장자 포함).
         * Motrix 매트릭스 차용: 제어문자 제거·Windows 예약어 회피·후행 점/공백 제거·traversal 무력화·80자 cap·한글 유지. */
        fun safeFilename(raw: String?, ext: String): String {
            val cleaned = (raw ?: "")
                .replace(Regex("\\p{Cntrl}+"), "")
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .trim('.', '_', ' ')
                .take(80)
            val base = cleaned.ifBlank { "video-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(System.currentTimeMillis())}" }
            val guarded = if (RESERVED_NAMES.contains(base.substringBefore('.').uppercase())) "_$base" else base
            return "$guarded.$ext"
        }
    }

    private fun handleComplete(jobId: String, session: FFmpegSession, out: File) {
        sessions.remove(jobId)
        logBuffers.remove(jobId)
        progressFiles.remove(jobId)?.delete()
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
                // 보관함 자동 운영 (분류·쿼터, v0.19)
                runCatching { StorageJanitor.onCompleted(context, java.io.File(StorageGuard.dlRoot, out.name)) }
            }
            else -> {
                val log = runCatching { session.allLogsAsString }.getOrDefault("")
                val code = when {
                    log.contains("encrypted", true) || log.contains("widevine", true) ||
                        log.contains("cannot decrypt", true) -> "E-AND-VID-0300"
                    else -> "E-AND-VID-0202"
                }
                val tail = log.lines().takeLast(6).joinToString(" ").take(220)
                out.delete()
                val msg = when (code) {
                    "E-AND-VID-0300" -> "DRM(저작권 보호) 콘텐츠는 다운로드할 수 없습니다"
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
        scope?.cancel()
        scope = null
    }
}