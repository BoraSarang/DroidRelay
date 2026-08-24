package com.borasarang.droidrelay.relay

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val persistence: JobsPersistence,
) {
    private val TAG = "Engine"
    private val client = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(90))
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentLinkedQueue<String>()
    private val active = AtomicInteger(0)

    @Volatile private var concurrencyTarget = 2
    @Volatile private var limitKbps = 0

    val workDir: File
        get() = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    fun partialFile(job: Job): File = File(workDir, job.filename + ".part")
    fun doneFile(job: Job): File = File(workDir, job.filename)

    init {
        DebugLogger.i(TAG, "엔진 초기화 workDir=${workDir.absolutePath}")
        scope.launch {
            settings.settings.collect { s ->
                concurrencyTarget = s.concurrency
                limitKbps = s.speedLimitKbps
                DebugLogger.d(TAG, "설정 반영 동시성=${s.concurrency} 스로틀=${s.speedLimitKbps}KB/s")
                tryStart()
            }
        }
        restore()
    }

    /** 앱 시작 시 jobs.json 복원 → 대기 항목 자동 재개 */
    private fun restore() {
        val restored = persistence.load()
        restored.forEach { job ->
            mapPut(job)
            if (job.state == JobState.QUEUED) {
                DebugLogger.i(TAG, "복원 → 재개 큐 진입 id=${job.id} '${job.filename}'")
                pending.add(job.id)
            }
        }
        tryStart()
    }

    private fun mapPut(job: Job) {
        // JobsRepository의 내부 맵에 위임: public add/update/get 사용
        if (JobsRepository.get(job.id) == null) JobsRepository.restore(job)
    }

    fun enqueue(url: String): Job {
        val name = JobsRepository.filenameFromUrl(url)
        val job = JobsRepository.add(url, URLDecoder.decode(name, "UTF-8"))
        DebugLogger.i(TAG, "큐 진입 id=${job.id} url=$url")
        pending.add(job.id)
        tryStart()
        return job
    }

    fun pause(id: String) {
        when (JobsRepository.get(id)?.state) {
            JobState.RUNNING -> JobsRepository.update(id) { it.copy(state = JobState.PAUSED, speedBps = 0L) }
            JobState.QUEUED -> JobsRepository.update(id) { it.copy(state = JobState.PAUSED) }
            else -> return
        }
        pending.remove(id)
        DebugLogger.i(TAG, "일시정지 id=$id (.part 유지)")
    }

    fun resume(id: String) {
        val job = JobsRepository.get(id) ?: return
        if (job.state != JobState.PAUSED && job.state != JobState.FAILED) return
        JobsRepository.update(id) { it.copy(state = JobState.QUEUED, errorMessage = null, errorCode = null) }
        pending.add(id)
        DebugLogger.i(TAG, "재개 요청 id=$id 오프셋=${fmt(partialFile(job).takeIf { it.exists() }?.length() ?: 0L)}")
        tryStart()
    }

    fun cancel(id: String) {
        val job = JobsRepository.get(id) ?: run {
            DebugLogger.w(TAG, "취소 실패(대상 없음) id=$id"); return
        }
        if (job.state == JobState.DONE) return
        pending.remove(id)
        JobsRepository.update(id) { it.copy(state = JobState.CANCELED, speedBps = 0L) }
        DebugLogger.i(TAG, "취소 id=$id '${job.filename}' (${fmt(job.downloadedBytes)} 시점)")
        scope.launch { partialFile(job).takeIf { it.exists() }?.delete() }
    }

    private fun tryStart() {
        while (active.get() < concurrencyTarget) {
            val id = pending.poll() ?: break
            val j = JobsRepository.get(id) ?: continue
            if (j.state != JobState.QUEUED) continue
            active.incrementAndGet()
            DebugLogger.d(TAG, "작업 기동 id=$id (활성 ${active.get()}/$concurrencyTarget)")
            scope.launch {
                try {
                    runWithRetry(id)
                } finally {
                    active.decrementAndGet()
                    tryStart()
                }
            }
        }
    }

    private suspend fun runWithRetry(id: String) {
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            DebugLogger.i(TAG, "시도 $attempt/$MAX_RETRY id=$id")
            when (runOnce(id)) {
                Outcome.COMPLETED, Outcome.CANCELED, Outcome.PAUSED -> return
                Outcome.RETRY -> {
                    if (JobsRepository.get(id)?.state == JobState.CANCELED) return
                    val wait = 2000L * attempt
                    DebugLogger.w(TAG, "실패 → ${wait}ms 후 재시도 id=$id (E-AND-DOWN-1001)")
                    JobsRepository.update(id) {
                        it.copy(state = JobState.RUNNING, errorMessage = "재시도 $attempt/$MAX_RETRY (E-AND-DOWN-1001)")
                    }
                    delay(wait)
                }
            }
        }
        JobsRepository.update(id) {
            it.copy(state = JobState.FAILED, errorCode = "E-AND-DOWN-1001", errorMessage = "E-AND-DOWN-1001: 네트워크 단절 ${MAX_RETRY}회 실패", speedBps = 0L)
        }
        DebugLogger.e(TAG, "최종 실패 id=$id")
    }

    private enum class Outcome { COMPLETED, CANCELED, PAUSED, RETRY }

    private suspend fun runOnce(id: String): Outcome = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val job = JobsRepository.get(id) ?: return@withContext Outcome.COMPLETED
        val partial = partialFile(job)
        var start = if (partial.exists()) partial.length() else 0L

        val req = Request.Builder().url(job.url).apply {
            if (start > 0) header("Range", "bytes=$start-")
        }.build()

        try {
            client.newCall(req).execute().use { res ->
                DebugLogger.d(TAG, "HTTP ${res.code} range=${res.header("Content-Range") ?: "-"} id=$id")
                if (!res.isSuccessful) {
                    if (res.code == 416 || start > 0) {
                        DebugLogger.w(TAG, "Range 거부(code=${res.code}) → 처음부터 id=$id")
                        partial.delete(); start = 0
                        return@withContext Outcome.RETRY
                    }
                    JobsRepository.update(id) {
                        it.copy(state = JobState.FAILED, errorCode = "E-AND-DOWN-1003", errorMessage = "E-AND-DOWN-1003: HTTP ${res.code}", speedBps = 0L)
                    }
                    return@withContext Outcome.COMPLETED
                }

                val contentLen = res.body?.contentLength() ?: -1L
                val resumed = start > 0 && res.code == 206
                val offset = if (resumed) start else 0L
                val total = if (contentLen > 0) contentLen + (if (resumed) start else 0L) else -1L
                DebugLogger.i(
                    TAG,
                    "전송 개시 id=$id mode=${if (resumed) "이어받기" else "신규"} 오프셋=${fmt(offset)} 총량=${if (total > 0) fmt(total) else "?"}",
                )

                JobsRepository.update(id) { j ->
                    j.copy(state = JobState.RUNNING, totalBytes = total, downloadedBytes = offset, progress = if (total > 0) offset.toFloat() / total else 0f)
                }

                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(offset)
                    val input = res.body!!.byteStream()
                    val buf = ByteArray(BUFFER_SIZE)
                    var lastTick = System.currentTimeMillis()
                    var lastPos = offset
                    var emaBps = 0.0
                    var firstTick = true
                    var windowStart = System.currentTimeMillis()
                    var sentInWindow = 0L

                    while (true) {
                        val cur = JobsRepository.get(id)!!
                        when (cur.state) {
                            JobState.CANCELED -> {
                                DebugLogger.i(TAG, "취소 감지 → .part 삭제 id=$id")
                                partial.delete(); return@withContext Outcome.CANCELED
                            }
                            JobState.PAUSED -> {
                                DebugLogger.i(TAG, "일시정지 감지 → .part 유지(${fmt(raf.length())}) id=$id")
                                return@withContext Outcome.PAUSED
                            }
                            else -> Unit
                        }

                        val n = input.read(buf)
                        if (n == -1) break

                        // 스로틀: 윈도우(1s) 예산 초과 시 대기
                        if (limitKbps > 0) {
                            sentInWindow += n
                            val budget = limitKbps.toLong() * 1024
                            val elapsedInWindow = System.currentTimeMillis() - windowStart
                            if (sentInWindow >= budget && elapsedInWindow < 1000) {
                                val sleepMs = 1000 - elapsedInWindow
                                DebugLogger.d(TAG, "스로틀 대기 ${sleepMs}ms id=$id")
                                delay(sleepMs)
                                windowStart = System.currentTimeMillis(); sentInWindow = 0
                            } else if (elapsedInWindow >= 1000) {
                                windowStart = System.currentTimeMillis(); sentInWindow = n.toLong()
                            }
                        }

                        raf.write(buf, 0, n)

                        val now = System.currentTimeMillis()
                        if (now - lastTick >= TICK_MS) {
                            val dt = (now - lastTick).coerceAtLeast(1)
                            val inst = (raf.filePointer - lastPos) * 1000.0 / dt
                            emaBps = if (firstTick) inst else emaBps * 0.6 + inst * 0.4
                            firstTick = false
                            lastTick = now
                            lastPos = raf.filePointer
                            val curBytes = offset + raf.filePointer
                            JobsRepository.update(id) { j ->
                                j.copy(
                                    downloadedBytes = curBytes,
                                    progress = if (total > 0) (curBytes.toFloat() / total) else 0f,
                                    speedBps = emaBps.toLong(),
                                )
                            }
                        }
                    }
                }

                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                val finalSize = rafLength(partial)
                val done = File(workDir, job.filename)
                if (done.exists()) done.delete()
                if (!partial.renameTo(done)) {
                    DebugLogger.w(TAG, "rename 실패 → copyTo 대체 id=$id")
                    partial.copyTo(done, overwrite = true); partial.delete()
                }
                publishToDownloads(done, id)
                JobsRepository.update(id) { j ->
                    j.copy(state = JobState.DONE, progress = 1f, downloadedBytes = finalSize, totalBytes = finalSize, speedBps = 0L)
                }
                DebugLogger.perf(TAG, "다운로드 id=$id '${done.name}' ${fmt(finalSize)} 평균=${fmt(finalSize * 1000 / elapsed)}/s") {}
                return@withContext Outcome.COMPLETED
            }
        } catch (e: Exception) {
            val st = JobsRepository.get(id)?.state
            if (st == JobState.CANCELED || st == JobState.PAUSED) return@withContext if (st == JobState.PAUSED) Outcome.PAUSED else Outcome.CANCELED
            if (e is kotlinx.coroutines.CancellationException) throw e
            DebugLogger.e(TAG, "예외 → 재시도 예정 id=$id", e)
            return@withContext Outcome.RETRY
        }
    }

    private fun rafLength(f: File): Long = f.length()

    private fun publishToDownloads(file: File, jobId: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DroidRelay")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                DebugLogger.w(TAG, "MediaStore insert null → 게시 생략 id=$jobId"); return
            }
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            DebugLogger.d(TAG, "공용 Downloads 게시 id=$jobId")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "게시 실패(원본 유지) id=$jobId", e)
        }
    }

    private fun fmt(n: Long): String = when {
        n < 1_048_576 -> "${n / 1024}KB"
        n < 1_073_741_824 -> String.format("%.1fMB", n / 1_073_741_824.0)
        else -> String.format("%.2fGB", n / 1_073_741_824.0)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val TICK_MS = 400L
        private const val MAX_RETRY = 3
    }
}

