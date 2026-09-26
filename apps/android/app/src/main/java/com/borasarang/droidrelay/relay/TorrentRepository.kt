package com.borasarang.droidrelay.relay

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TorrentState {
    QUEUED,
    FETCHING_METADATA,
    DOWNLOADING,
    SEEDING,
    PAUSED,
    /** 정체(스톨) 감지 → 자동 일시정지 + 큐 맨뒤 (T-1050) */
    STALLED,
    FAILED,
    DONE,
}

data class TorrentJob(
    val id: String,
    val infoHash: String,
    val name: String,
    val magnet: String? = null,
    val torrentFileBytes: ByteArray? = null,
    val state: TorrentState = TorrentState.QUEUED,
    val progress: Float = 0f,
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val seeds: Int = 0,
    val peers: Int = 0,
    val uploadLimit: Long = 0L,
    val downloadLimit: Long = 0L,
    val files: List<TorrentFile> = emptyList(),
    val savePath: String = "",
    val errorMessage: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val order: Int = 0,
)

data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
    val progress: Float,
    val selected: Boolean,
)

object TorrentRepository {
    private const val TAG = "TorrentRepo"
    private val _torrents = MutableStateFlow<List<TorrentJob>>(emptyList())
    val torrents: StateFlow<List<TorrentJob>> = _torrents
    private val map = ConcurrentHashMap<String, TorrentJob>()
    // 진행 틱 방출 스로틀 — 500ms / 0.5% 미만 변동은 Flow 미방출 (JobsRepository 와 동일 정책)
    @Volatile private var lastEmitAt = 0L
    private const val PROGRESS_EMIT_MS = 500L
    private const val PROGRESS_EMIT_DELTA = 0.005f

    fun all(): List<TorrentJob> = _torrents.value
    fun get(id: String): TorrentJob? = map[id]

    fun add(job: TorrentJob) {
        val nextOrder = (map.values.maxOfOrNull { it.order } ?: 0) + 1
        map[job.id] = job.copy(order = nextOrder)
        refresh()
        DebugLogger.i(TAG, "torrent 추가 id=${job.id} name='${job.name}' state=${job.state} order=$nextOrder")
    }

    fun update(id: String, transform: (TorrentJob) -> TorrentJob) {
        var changed = false
        var suppressEmit = false
        map.computeIfPresent(id) { _, before ->
            val after = transform(before)
            changed = after != before
            if (changed && before.state != after.state) {
                DebugLogger.i(
                    TAG,
                    "상태전이 id=$id '${before.name}' ${before.state} → ${after.state}" +
                        " (${after.downloadedSize}/${if (after.totalSize > 0) after.totalSize else "?"})",
                )
            }
            // 진행 틱은 map 만 갱신하고 Flow 방출은 스로틀.
            // 5초 폴링이 torrent 마다 update 를 호출하므로 이게 없으면
            // 토렌트 N 개 = 5초마다 N 회 정렬 + N 회 리스트 리컴포지션이 된다.
            // JobsRepository 의 PROGRESS_EMIT_* 와 동일한 정책.
            if (changed && before.state == after.state && isProgressOnly(after)) {
                val dProgress = kotlin.math.abs(after.progress - before.progress)
                val now = System.currentTimeMillis()
                if (dProgress < PROGRESS_EMIT_DELTA && now - lastEmitAt < PROGRESS_EMIT_MS) {
                    suppressEmit = true
                }
            }
            after
        }
        if (changed && !suppressEmit) {
            lastEmitAt = System.currentTimeMillis()
            refresh()
        }
    }

    /** 진행률·속도만 바뀌고 상태/이름/크기는 그대로인지 — 방출 스로틀 적용 대상 */
    private fun isProgressOnly(after: TorrentJob): Boolean =
        after.state == TorrentState.DOWNLOADING || after.state == TorrentState.SEEDING ||
            after.state == TorrentState.FETCHING_METADATA

    fun remove(id: String): Boolean {
        val removed = map.remove(id)
        if (removed != null) {
            DebugLogger.i(TAG, "torrent 삭제 id=$id '${removed.name}' state=${removed.state}")
        }
        refresh()
        return removed != null
    }

    fun restore(job: TorrentJob) {
        map[job.id] = job
        refresh()
        DebugLogger.d(TAG, "복원 등록 id=${job.id} '${job.name}' state=${job.state}")
    }

    @Synchronized
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

    private fun refresh() {
        _torrents.value = map.values.sortedBy { it.order }
    }

    fun newId(): String = java.util.UUID.randomUUID().toString()
}
