package com.borasarang.droidrelay.relay

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadEngine(private val context: Context) {

    private val TAG = "Engine"
    private val client = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(90))
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)

    val workDir: File
        get() = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    fun partialFile(job: Job): File = File(workDir, job.filename + ".part")
    fun doneFile(job: Job): File = File(workDir, job.filename)

    init {
        DebugLogger.i(TAG, "엔진 초기화 workDir=${workDir.absolutePath} 동시성=2 재시도=$MAX_RETRY")
    }

    fun enqueue(url: String): Job {
        val name = JobsRepository.filenameFromUrl(url)
        val job = JobsRepository.add(url, URLDecoder.decode(name, "UTF-8"))
        DebugLogger.i(TAG, "큐 진입 id=${job.id} url=$url")
        scope.launch {
            permits.acquire()
            DebugLogger.d(TAG, "작업 시작 허가 획득 id=${job.id} (동시 슬롯 진입)")
            try {
                if (JobsRepository.get(job.id)?.state == JobState.QUEUED) runWithRetry(job.id)
                else DebugLogger.d(TAG, "시작 전 취소됨 → 건너뜀 id=${job.id}")
            } finally {
                permits.release()
                DebugLogger.d(TAG, "동시 슬롯 반납 id=${job.id}")
            }
        }
        return job
    }

    fun cancel(id: String) {
        val job = JobsRepository.get(id) ?: run {
            DebugLogger.w(TAG, "취소 실패(대상 없음) id=$id"); return
        }
        if (job.state == JobState.DONE) {
            DebugLogger.d(TAG, "완료 작업은 취소 무시 id=$id"); return
        }
        JobsRepository.update(id) { it.copy(state = JobState.CANCELED) }
        DebugLogger.i(TAG, "취소 요청 접수 id=$id '${job.filename}' (${fmt(job.downloadedBytes)} 받은 시점)")
    }

    private suspend fun runWithRetry(id: String) {
        var attempt = 0
        while (attempt < MAX_RETRY) {
            attempt++
            DebugLogger.i(TAG, "시도 $attempt/$MAX_RETRY id=$id")
            val ok = runOnce(id)
            when {
                ok -> return
                JobsRepository.get(id)?.state == JobState.CANCELED -> {
                    DebugLogger.i(TAG, "취소로 종료 id=$id"); return
                }
                else -> {
                    val wait = 2000L * attempt
                    DebugLogger.w(
                        TAG,
                        "실패 → ${wait}ms 후 재시도 예정 id=$id (E-AND-DOWN-1001)",
                    )
                    JobsRepository.update(id) {
                        it.copy(state = JobState.RUNNING, errorMessage = "재시도 $attempt/$MAX_RETRY (E-AND-DOWN-1001)")
                    }
                    delay(wait)
                }
            }
        }
        fail(id, "E-AND-DOWN-1001", "네트워크 단절: ${MAX_RETRY}회 재시도 실패")
    }

    private suspend fun runOnce(id: String): Boolean = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val job = JobsRepository.get(id) ?: return@withContext true
        val partial = partialFile(job)
        var start = if (partial.exists()) partial.length() else 0L
        if (start > 0) DebugLogger.d(TAG, ".part 발견 → 이어받기 오프셋=${fmt(start)} id=$id file='${partial.name}'")

        val req = Request.Builder().url(job.url).apply {
            if (start > 0) header("Range", "bytes=$start-")
        }.build()

        try {
            client.newCall(req).execute().use { res ->
                DebugLogger.d(
                    TAG,
                    "HTTP 응답 code=${res.code} range헤더=${res.header("Content-Range") ?: "-"} id=$id",
                )
                if (!res.isSuccessful) {
                    if (res.code == 416 || start > 0) {
                        DebugLogger.w(TAG, "Range 거부/만료(code=${res.code}) → 처음부터 재시작 id=$id")
                        partial.delete(); start = 0
                        return@withContext false
                    }
                    fail(id, "E-AND-DOWN-1003", "HTTP ${res.code}")
                    return@withContext true
                }

                val total = res.body?.contentLength() ?: -1L
                val resumed = start > 0 && res.code == 206
                val effectiveStart = if (resumed) start else 0L
                val effectiveTotal = if (total > 0) total + (if (resumed) start else 0L) else -1L
                DebugLogger.i(
                    TAG,
                    "전송 개시 id=$id mode=${if (resumed) "이어받기(206)" else "신규"} " +
                        "오프셋=${fmt(effectiveStart)} 총량=${if (effectiveTotal > 0) fmt(effectiveTotal) else "미지"}",
                )

                JobsRepository.update(id) { j ->
                    j.copy(state = JobState.RUNNING, totalBytes = effectiveTotal, downloadedBytes = effectiveStart)
                }

                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(effectiveStart)
                    val body = res.body ?: throw IllegalStateException("빈 응답 본문")
                    val buf = ByteArray(BUFFER_SIZE)
                    var lastTick = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            if (JobsRepository.get(id)?.state == JobState.CANCELED) {
                                DebugLogger.i(TAG, "취소 감지 → .part 삭제 후 중단 id=$id")
                                partial.delete()
                                return@withContext true
                            }
                            val n = input.read(buf)
                            if (n == -1) break
                            raf.write(buf, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastTick > TICK_MS) {
                                lastTick = now
                                val cur = effectiveStart + raf.filePointer
                                JobsRepository.update(id) { j ->
                                    j.copy(
                                        downloadedBytes = cur,
                                        progress = if (effectiveTotal > 0) (cur.toFloat() / effectiveTotal) else 0f,
                                    )
                                }
                            }
                        }
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    val finalSize = raf.length()
                    val done = File(workDir, job.filename)
                    if (done.exists()) done.delete()
                    if (!partial.renameTo(done)) {
                        DebugLogger.w(TAG, "rename 실패 → copyTo 대체 사용 id=$id")
                        partial.copyTo(done, overwrite = true)
                        partial.delete()
                    }
                    publishToDownloads(done, id)
                    JobsRepository.update(id) { j ->
                        j.copy(state = JobState.DONE, progress = 1f, downloadedBytes = finalSize, totalBytes = finalSize)
                    }
                    DebugLogger.perf(TAG, "다운로드 id=$id '${done.name}' ${fmt(finalSize)}") {}
                    DebugLogger.i(TAG, "완료 확정 id=$id 크기=${fmt(finalSize)} 소요=${elapsed}ms 평균=${fmt((finalSize * 1000 / (elapsed.coerceAtLeast(1))))}/s")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            if (JobsRepository.get(id)?.state == JobState.CANCELED) return@withContext true
            if (e is kotlinx.coroutines.CancellationException) throw e
            DebugLogger.e(TAG, "예외로 시도 실패(재시도 예정) id=$id", e)
            return@withContext false
        }
    }

    private fun fail(id: String, code: String, message: String) {
        DebugLogger.e(TAG, "최종 실패 id=$id [$code] $message")
        JobsRepository.update(id) { it.copy(state = JobState.FAILED, errorCode = code, errorMessage = "$code: $message") }
    }

    /** 완료 파일을 공용 Downloads/DroidRelay 로 복사해 Files 앱·MTP에서 보이게 함 */
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
                DebugLogger.w(TAG, "MediaStore insert null → 게시 생략 id=$jobId")
                return
            }
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            DebugLogger.d(TAG, "공용 Downloads 게시 완료 id=$jobId path=Download/DroidRelay/${file.name}")
        } catch (e: Exception) {
            // 게시 실패는 치명적이지 않음 — 앱 전용 경로에 원본 유지됨
            DebugLogger.e(TAG, "게시 실패(원본 유지) id=$jobId", e)
        }
    }

    private fun fmt(n: Long): String = when {
        n < 1_048_576 -> "${n / 1024}KB"
        n < 1_073_741_824 -> String.format("%.1fMB", n / 1_048_576.0)
        else -> String.format("%.2fGB", n / 1_073_741_824.0)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val TICK_MS = 300L
        private const val MAX_RETRY = 3
    }
}
