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

    fun all(): List<TorrentJob> = _torrents.value
    fun get(id: String): TorrentJob? = map[id]

    fun add(job: TorrentJob) {
        val nextOrder = (map.values.maxOfOrNull { it.order } ?: 0) + 1
        map[job.id] = job.copy(order = nextOrder)
        refresh()
        DebugLogger.i(TAG, "torrent 추가 id=${job.id} name='${job.name}' state=${job.state} order=$nextOrder")
    }

    fun update(id: String, transform: (TorrentJob) -> TorrentJob) {
        map.computeIfPresent(id) { _, before ->
            val after = transform(before)
            if (before.state != after.state) {
                DebugLogger.i(
                    TAG,
                    "상태전이 id=$id '${before.name}' ${before.state} → ${after.state}" +
                        " (${after.downloadedSize}/${if (after.totalSize > 0) after.totalSize else "?"})",
                )
            }
            after
        }
        refresh()
    }

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

    fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random()
}
