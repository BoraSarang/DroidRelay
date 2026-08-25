package com.borasarang.droidrelay.relay

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType

class TorrentEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val persistence: TorrentPersistence,
) {
    private val TAG = "TorrentEngine"
    private var session: SessionManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handleMap = ConcurrentHashMap<String, TorrentHandle>()
    private val hashToId = ConcurrentHashMap<String, String>()
    private var statusPollingJob: Job? = null

    val saveDir: File
        get() = File(context.getExternalFilesDir(null), "torrents").apply { mkdirs() }

    fun start() {
        if (session != null) return
        try {
            session = SessionManager().apply {
                start()
                startDht()
                addListener(object : AlertListener {
                    override fun alert(alert: Alert<*>) {
                        handleAlert(alert)
                    }
                    override fun types(): IntArray {
                        return intArrayOf(
                            AlertType.ADD_TORRENT.swig(),
                            AlertType.TORRENT_FINISHED.swig(),
                            AlertType.TORRENT_ERROR.swig(),
                            AlertType.METADATA_RECEIVED.swig(),
                        )
                    }
                })
            }
            DebugLogger.i(TAG, "세션 시작 완료 (DHT 활성화)")
            startStatusPolling()
            restoreTorrents()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "세션 시작 실패", e)
        }
    }

    fun stop() {
        statusPollingJob?.cancel()
        session?.stop()
        session = null
        handleMap.clear()
        hashToId.clear()
        DebugLogger.i(TAG, "세션 정지")
    }

    fun addMagnet(magnet: String): TorrentJob {
        val id = TorrentRepository.newId()
        val job = TorrentJob(
            id = id,
            infoHash = "",
            name = "추출 중...",
            magnet = magnet,
            state = TorrentState.FETCHING_METADATA,
            savePath = saveDir.absolutePath,
            startedAt = System.currentTimeMillis(),
        )
        TorrentRepository.add(job)
        DebugLogger.i(TAG, "magnet 추가 id=$id")

        scope.launch {
            try {
                session?.download(magnet, saveDir, torrent_flags_t())
                DebugLogger.d(TAG, "magnet download 호출 완료 id=$id (ADD_TORRENT 대기)")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "magnet 추가 실패 id=$id", e)
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = e.message)
                }
            }
        }
        return job
    }

    fun addTorrentFile(bytes: ByteArray, filename: String): TorrentJob {
        val id = TorrentRepository.newId()
        val job = TorrentJob(
            id = id,
            infoHash = "",
            name = filename.removeSuffix(".torrent"),
            torrentFileBytes = bytes,
            state = TorrentState.FETCHING_METADATA,
            savePath = saveDir.absolutePath,
            startedAt = System.currentTimeMillis(),
        )
        TorrentRepository.add(job)
        DebugLogger.i(TAG, "torrent 파일 추가 id=$id name=$filename")

        scope.launch {
            try {
                val tempFile = File(saveDir, "temp_$id.torrent")
                tempFile.writeBytes(bytes)
                val ti = TorrentInfo(tempFile)
                session?.download(ti, saveDir)
                val files = (0 until ti.numFiles()).map { fi ->
                    TorrentFile(
                        index = fi,
                        path = ti.files().filePath(fi),
                        size = ti.files().fileSize(fi),
                        progress = 0f,
                        selected = true,
                    )
                }
                TorrentRepository.update(id) {
                    it.copy(name = ti.name(), totalSize = ti.totalSize(), files = files)
                }
                DebugLogger.d(TAG, "torrent 파일 download 호출 완료 id=$id name='${ti.name()}'")
                tempFile.delete()
            } catch (e: Exception) {
                DebugLogger.e(TAG, "torrent 파일 추가 실패 id=$id", e)
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = e.message)
                }
            }
        }
        return job
    }

    fun pause(id: String) {
        val th = handleMap[id] ?: return
        th.pause()
        TorrentRepository.update(id) {
            it.copy(state = TorrentState.PAUSED, uploadSpeed = 0L, downloadSpeed = 0L)
        }
        DebugLogger.i(TAG, "torrent 일시정지 id=$id")
    }

    fun resume(id: String) {
        val th = handleMap[id] ?: return
        val job = TorrentRepository.get(id) ?: return
        if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED) {
            th.resume()
            TorrentRepository.update(id) {
                it.copy(state = TorrentState.DOWNLOADING, errorMessage = null)
            }
            DebugLogger.i(TAG, "torrent 재개 id=$id")
        }
    }

    fun cancel(id: String) {
        val th = handleMap.remove(id)
        th?.let { session?.remove(it) }
        if (th != null) hashToId.remove(th.infoHash().toString())
        TorrentRepository.remove(id)
        DebugLogger.i(TAG, "torrent 취소 id=$id")
    }

    fun setUploadLimit(bytesPerSec: Int) {
        session?.uploadRateLimit(bytesPerSec)
        DebugLogger.d(TAG, "업로드 제한 설정 ${bytesPerSec / 1024}KB/s")
    }

    fun setDownloadLimit(bytesPerSec: Int) {
        session?.downloadRateLimit(bytesPerSec)
        DebugLogger.d(TAG, "다운로드 제한 설정 ${bytesPerSec / 1024}KB/s")
    }

    private fun handleAlert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.ADD_TORRENT -> {
                val addAlert = alert as org.libtorrent4j.alerts.AddTorrentAlert
                val th = addAlert.handle()
                val hash = th.infoHash().toString()
                val id = findJobIdForNewTorrent(hash)
                if (id != null) {
                    handleMap[id] = th
                    hashToId[hash] = id
                    TorrentRepository.update(id) {
                        it.copy(infoHash = hash)
                    }
                    DebugLogger.d(TAG, "ADD_TORRENT 매핑 id=$id hash=$hash")
                }
            }
            AlertType.TORRENT_FINISHED -> {
                val th = (alert as org.libtorrent4j.alerts.TorrentFinishedAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                TorrentRepository.update(id) {
                    it.copy(
                        state = TorrentState.DONE,
                        progress = 1f,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                DebugLogger.i(TAG, "torrent 완료 id=$id")
            }
            AlertType.TORRENT_ERROR -> {
                val th = (alert as org.libtorrent4j.alerts.TorrentErrorAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = "토렌트 에러")
                }
                DebugLogger.e(TAG, "torrent 에러 id=$id")
            }
            AlertType.METADATA_RECEIVED -> {
                val th = (alert as org.libtorrent4j.alerts.MetadataReceivedAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                val ti = th.torrentFile()
                if (ti != null) {
                    val files = (0 until ti.numFiles()).map { fi ->
                        TorrentFile(
                            index = fi,
                            path = ti.files().filePath(fi),
                            size = ti.files().fileSize(fi),
                            progress = 0f,
                            selected = true,
                        )
                    }
                    TorrentRepository.update(id) {
                        it.copy(
                            infoHash = th.infoHash().toString(),
                            name = ti.name(),
                            totalSize = ti.totalSize(),
                            state = TorrentState.DOWNLOADING,
                            files = files,
                        )
                    }
                    DebugLogger.d(TAG, "메타데이터 수신 id=$id name='${ti.name()}'")
                }
            }
            else -> Unit
        }
    }

    /** ADD_TORRENT 시 아직 매핑되지 않은 torrent를 찾음 */
    private fun findJobIdForNewTorrent(hash: String): String? {
        // 이미 매핑된 경우
        hashToId[hash]?.let { return it }
        // infoHash가 비어있는 가장 최근 torrent에 매핑
        val candidates = TorrentRepository.all().filter { it.infoHash.isEmpty() && it.state == TorrentState.FETCHING_METADATA }
        return candidates.maxByOrNull { it.id }?.id
    }

    private fun startStatusPolling() {
        statusPollingJob = scope.launch {
            while (true) {
                delay(1000L)
                handleMap.forEach { (id, th) ->
                    val status = th.status()
                    val job = TorrentRepository.get(id) ?: return@forEach
                    if (job.state == TorrentState.PAUSED || job.state == TorrentState.DONE || job.state == TorrentState.FAILED) return@forEach

                    val state = when (status.state()) {
                        TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
                        TorrentStatus.State.SEEDING -> TorrentState.SEEDING
                        TorrentStatus.State.CHECKING_FILES -> TorrentState.FETCHING_METADATA
                        TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.FETCHING_METADATA
                        TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.FETCHING_METADATA
                        TorrentStatus.State.FINISHED -> TorrentState.DONE
                        TorrentStatus.State.UNKNOWN -> job.state
                    }

                    TorrentRepository.update(id) {
                        it.copy(
                            state = state,
                            progress = status.progress(),
                            downloadSpeed = status.downloadRate().toLong(),
                            uploadSpeed = status.uploadRate().toLong(),
                            totalSize = status.total(),
                            downloadedSize = status.totalDone(),
                            seeds = status.numSeeds(),
                            peers = status.numPeers(),
                        )
                    }
                }
            }
        }
    }

    private fun restoreTorrents() {
        val restored = persistence.load()
        restored.forEach { job ->
            if (job.state != TorrentState.DONE && job.state != TorrentState.FAILED) {
                TorrentRepository.restore(job.copy(state = TorrentState.QUEUED))
                if (job.magnet != null) {
                    scope.launch {
                        session?.download(job.magnet, saveDir, torrent_flags_t())
                    }
                }
            } else {
                TorrentRepository.restore(job)
            }
        }
        DebugLogger.d(TAG, "복원 완료 ${restored.size}건")
    }
}
