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
    val order: Int = 0,
    val type: String = "http",
    val maxDownBps: Long = 0L, // 작업별 다운로드 상한 (B/s, 0=무제한) — v0.25
    val segmentsTotal: Int = 0, // HLS 세그먼트 전체 (0=미지원) — video 전용
    val segmentsDone: Int = 0, // 처리된 세그먼트 수 — video 전용 (진행률 근거)
    val totalDurationMs: Long = 0L, // HLS 총 재생 시간(ms) — video 전용 (-progress 기반 진행률 근거)
)

object JobsRepository {
    private val TAG = "Jobs"
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs
    private val map = ConcurrentHashMap<String, Job>()

    fun all(): List<Job> = _jobs.value
    fun get(id: String): Job? = map[id]

    fun add(url: String, filename: String, type: String = "http"): Job {
        map.values.none { it.url == url && it.state == JobState.RUNNING }
            .also { dup ->
                if (!dup) DebugLogger.w(TAG, "중복 URL 재추가 감지: $url")
            }
        val nextOrder = (map.values.maxOfOrNull { it.order } ?: 0) + 1
        val job = Job(
            id = newId(), url = url, filename = uniqueName(filename),
            order = nextOrder, type = type,
        )
        map[job.id] = job
        refresh()
        DebugLogger.i(TAG, "작업 추가 id=${job.id} file='${job.filename}' state=QUEUED type=$type order=$nextOrder")
        return job
    }

    fun update(id: String, transform: (Job) -> Job) {
        var changed = false
        map.computeIfPresent(id) { _, before ->
            val after = transform(before)
            changed = after != before
            if (changed && before.state != after.state) {
                DebugLogger.i(
                    TAG,
                    "상태전이 id=$id '${before.filename}' ${before.state} → ${after.state}" +
                        " (${fmt(after.downloadedBytes)}/${if (after.totalBytes > 0) fmt(after.totalBytes) else "?"})",
                )
            } else if (changed && after.downloadedBytes != before.downloadedBytes &&
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
        if (changed) refresh()
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
        // 1순위: 쿼리 파라미터 (서명 URL의 response-content-disposition 등) — T-1004
        filenameFromQuery(url)?.let {
            DebugLogger.d(TAG, "파일명 결정(쿼리 우선): '$it'")
            return it.takeLast(120)
        }
        val path = url.substringBefore('?').substringBefore('#')
        val raw = path.substringAfterLast('/')
        val last = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

        // 범용 이름이거나 확장자가 없으면 호스트+시각으로 대체 (예: file-speed.cloudflare.com-0825-054312)
        val generic = last.isBlank() ||
            GENERIC_NAMES.contains(last.lowercase()) ||
            !last.contains('.')
        if (!generic) {
            val sanitized = sanitizeLeaf(last) ?: last.takeLast(120)
            DebugLogger.d(TAG, "파일명 결정(URL 그대로): '$sanitized'")
            return sanitized
        }

        val host = url.substringAfter("//").substringBefore('/').substringBefore(':')
            .takeLast(24).ifBlank { "download" }
        val ts = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val fallback = "file-$host-$ts"
        DebugLogger.d(TAG, "파일명 결정(범용 폴백): '$raw' → '$fallback'")
        return fallback
    }

    /** 폴백 이름 여부 — 응답 헤더 교정 대상 판단용 (T-1004) */
    fun isFallbackName(name: String): Boolean {
        if (!name.startsWith("file-")) return false
        return Regex("""^file-.+-\d{4}-\d{6}$""").matches(name)
    }

    /** 응답 Content-Disposition 헤더로 교정할 이름 반환 — 폴백일 때만, 아니면 null (T-1004) */
    fun correctedWithHeader(current: String, header: String?): String? {
        if (!isFallbackName(current)) return null
        val parsed = DispositionHeader.parse(header) ?: return null
        return sanitizeLeaf(parsed)
    }

    /** 쿼리에서 파일명 후보 추출 — response-content-disposition 우선, 없으면 filename/file/name */
    internal fun filenameFromQuery(url: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }
                .getOrDefault(pair.substring(0, eq)).trim()
            val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                .getOrDefault(pair.substring(eq + 1))
            key to value
        }
        // response-content-disposition 안의 filename= / filename*= (GitHub release-assets 서명 URL)
        params.firstOrNull { it.first.equals("response-content-disposition", ignoreCase = true) }
            ?.let { (_, v) ->
                DispositionHeader.parse(v)?.let { sanitizeLeaf(it) }?.let { return it }
            }
        // 일반 filename/file/name 쿼리 (확장자 있는 것만)
        for ((k, v) in params) {
            if (k.equals("filename", ignoreCase = true) ||
                k.equals("file", ignoreCase = true) ||
                k.equals("name", ignoreCase = true)
            ) {
                sanitizeLeaf(v)?.let { return it }
            }
        }
        return null
    }

    /** 경로 탈출·제어문자·예약문자 정제 — 한글 유지, 120자 cap, 확장자 필수 */
    internal fun sanitizeLeaf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .replace(Regex("\\p{Cntrl}+"), "")
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('.', '_', ' ')
            .take(120)
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
        if (!cleaned.contains('.')) return null
        if (GENERIC_NAMES.contains(cleaned.lowercase())) return null
        return cleaned
    }

    private val GENERIC_NAMES = setOf(
        "__down", "download", "index", "file", "get", "blob", "raw", "dl",
    )

    /** 복원용 — 상태 보존하여 직접 등록 (엔진 초기화 시 jobs.json 로드) */
    fun restore(job: Job) {
        // progress는 파생값이라 저장하지 않음 → 복원 시 bytes로 재계산 (T-601 회귀 방지)
        val fixed = if (job.totalBytes > 0 && job.downloadedBytes > 0 && job.progress <= 0f)
            job.copy(progress = (job.downloadedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f))
        else job
        map[fixed.id] = fixed
        refresh()
        DebugLogger.d(TAG, "복원 등록 id=${fixed.id} '${fixed.filename}' state=${fixed.state} progress=${(fixed.progress * 100).toInt()}%")
    }

    fun reorder(id: String, newOrder: Int) {
        val job = map[id] ?: return
        val others = map.values.filter { it.id != id }.sortedBy { it.order }.toMutableList()
        val target = newOrder.coerceIn(0, others.size)
        others.add(target, job)
        others.forEachIndexed { i, j -> map[j.id] = j.copy(order = i) }
        map[id] = map[id]!!.copy(order = target)
        refresh()
        DebugLogger.i(TAG, "순서 변경 id=$id → order=$target")
    }

    fun nextOrder(): Int = (map.values.maxOfOrNull { it.order } ?: 0) + 1

    /** URL 정규화 — 스킴/호스트 소문자 + fragment 제거 + 끝 슬래시 정리 (T-947) */
    fun normalizedUrl(url: String): String {
        val t = url.trim()
        return runCatching {
            val u = java.net.URI(t)
            val scheme = (u.scheme ?: "").lowercase()
            val host = (u.host ?: "").lowercase()
            val port = if (u.port > 0) ":${u.port}" else ""
            val path = (u.rawPath?.trimEnd('/') ?: "").ifEmpty { "" }
            val query = if (!u.rawQuery.isNullOrEmpty()) "?${u.rawQuery}" else ""
            "$scheme://$host$port$path$query"
        }.getOrDefault(t.substringBefore('#').trim().trimEnd('/'))
    }

    /** 중복 URL 잡 조회 — CANCELED/FAILED는 재등록 허용 (T-947) */
    fun findDuplicateUrl(url: String): Job? {
        val norm = normalizedUrl(url)
        return map.values.firstOrNull {
            normalizedUrl(it.url) == norm &&
                it.state != JobState.CANCELED && it.state != JobState.FAILED
        }
    }

    private fun refresh() {
        _jobs.value = map.values.sortedBy { it.order }
    }

    private fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random()

    private fun uniqueName(name: String): String {
        return uniqueAmong(name, map.values.map { it.filename }.toSet())
    }

    /** 헤더 교정용 — 자기 자신 제외하고 중복 회피 (T-1004) */
    fun uniqueFor(excludeId: String, desired: String): String {
        val taken = map.values.filter { it.id != excludeId }.map { it.filename }.toSet()
        return uniqueAmong(desired, taken)
    }

    private fun uniqueAmong(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while ("$base-$i$ext" in taken) i++
        return "$base-$i$ext"
    }
}
