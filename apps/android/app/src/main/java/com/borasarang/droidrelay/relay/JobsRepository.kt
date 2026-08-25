package com.borasarang.droidrelay.relay

import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class JobState { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

data class Job(
    val id: String,
    val url: String,
    val filename: String,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBps: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
)

object JobsRepository {
    private val TAG = "Jobs"
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs
    private val map = ConcurrentHashMap<String, Job>()

    fun all(): List<Job> = _jobs.value
    fun get(id: String): Job? = map[id]

    fun add(url: String, filename: String): Job {
        map.values.none { it.url == url && it.state == JobState.RUNNING }
            .also { dup ->
                if (!dup) DebugLogger.w(TAG, "중복 URL 재추가 감지: $url")
            }
        val job = Job(id = newId(), url = url, filename = uniqueName(filename))
        map[job.id] = job
        refresh()
        DebugLogger.i(TAG, "작업 추가 id=${job.id} file='${job.filename}' state=QUEUED")
        return job
    }

    fun update(id: String, transform: (Job) -> Job) {
        map.computeIfPresent(id) { _, before ->
            val after = transform(before)
            if (before.state != after.state) {
                DebugLogger.i(
                    TAG,
                    "상태전이 id=$id '${before.filename}' ${before.state} → ${after.state}" +
                        " (${fmt(after.downloadedBytes)}/${if (after.totalBytes > 0) fmt(after.totalBytes) else "?"})",
                )
            } else if (after.downloadedBytes != before.downloadedBytes &&
                after.state == JobState.RUNNING &&
                crossedMilestone(before.downloadedBytes, after.downloadedBytes, after.totalBytes)
            ) {
                val pct = if (after.totalBytes > 0) (after.progress * 100).toInt() else 0
                DebugLogger.d(TAG, "진행 id=$id '${
                    before.filename
                }' $pct% (${fmt(after.downloadedBytes)})")
            }
            after
        }
        refresh()
    }

    fun remove(id: String): Boolean {        val removed = map.remove(id)
        if (removed != null) {
            DebugLogger.i(TAG, "작업 삭제 id=$id '${removed.filename}' state=${removed.state}")
        } else {
            DebugLogger.w(TAG, "삭제 실패(존재하지 않음) id=$id")
        }
        refresh()
        return removed != null
    }

    /** 진행률 분기(25/50/75/100%) 통과 시에만 세부 로그 */
    private fun crossedMilestone(prevBytes: Long, newBytes: Long, total: Long): Boolean {
        if (total <= 0 || newBytes == prevBytes) return false
        fun quarter(b: Long) = (b * 4 / total).toInt()
        return quarter(newBytes) != quarter(prevBytes)
    }

    private fun fmt(n: Long): String = when {
        n < 1_048_576 -> "${n / 1024}KB"
        n < 1_073_741_824 -> String.format("%.1fMB", n / 1_048_576.0)
        else -> String.format("%.2fGB", n / 1_073_741_824.0)
    }

    fun filenameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val raw = path.substringAfterLast('/')
        val last = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

        // 범용 이름이거나 확장자가 없으면 호스트+시각으로 대체 (예: file-speed.cloudflare.com-0825-054312)
        val generic = last.isBlank() ||
            GENERIC_NAMES.contains(last.lowercase()) ||
            !last.contains('.')
        if (!generic) {
            DebugLogger.d(TAG, "파일명 결정(URL 그대로): '$last'")
            return last.takeLast(120)
        }

        val host = url.substringAfter("//").substringBefore('/').substringBefore(':')
            .takeLast(24).ifBlank { "download" }
        val ts = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val fallback = "file-$host-$ts"
        DebugLogger.d(TAG, "파일명 결정(범용 폴백): '$raw' → '$fallback'")
        return fallback
    }

    private val GENERIC_NAMES = setOf(
        "__down", "download", "index", "file", "get", "blob", "raw", "dl",
    )

    /** 복원용 — 상태 보존하여 직접 등록 (엔진 초기화 시 jobs.json 로드) */
    fun restore(job: Job) {
        map[job.id] = job
        refresh()
        DebugLogger.d(TAG, "복원 등록 id=${job.id} '${job.filename}' state=${job.state}")
    }

    private fun refresh() {
        _jobs.value = map.values.sortedByDescending { it.id }
    }

    private fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random()

    private fun uniqueName(name: String): String {
        val taken = map.values.map { it.filename }.toSet()
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while ("$base-$i$ext" in taken) i++
        return "$base-$i$ext"
    }
}
