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

    @Volatile private var latestUploadKbps: Long = 0L
    @Volatile private var latestDownloadKbps: Long = 0L
    private var lastPersistAt = 0L
    private fun persistNow() {
        try { persistence.save(TorrentRepository.all()) } catch (_: Exception) {}
    }
    private fun persistDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt > 10_000) { // 10초 간격
            lastPersistAt = now
            persistNow()
        }
    }

    init {
        scope.launch {
            settings.settings.collect { s ->
                latestUploadKbps = s.torrentUploadLimit
                latestDownloadKbps = s.torrentDownloadLimit
                DebugLogger.d(TAG, "설정 반영 업로드=${s.torrentUploadLimit}KB/s 다운로드=${s.torrentDownloadLimit}KB/s")
                applyRateLimits()
            }
        }
    }

    val saveDir: File
        get() = File(context.getExternalFilesDir(null), "torrents").apply { mkdirs() }

    /** 보관함 경로: /sdcard/Download/DroidRelay/ */
    private val storageDir: File
        get() = File("/sdcard/Download/DroidRelay").apply { mkdirs() }

    /** 완료된 토렌트 파일을 보관함으로 이동 */
    private fun moveToStorage(id: String, torrentName: String) {
        val src = File(saveDir, torrentName)
        if (!src.exists() || !src.isDirectory) {
            DebugLogger.w(TAG, "보관함 이동 스킵(소스 없음) id=$id name=$torrentName")
            return
        }
        val dst = File(storageDir, torrentName)
        try {
            if (dst.exists()) dst.deleteRecursively()
            src.renameTo(dst).also { ok ->
                if (ok) {
                    DebugLogger.i(TAG, "보관함 이동 완료 id=$id → ${dst.absolutePath}")
                } else {
                    DebugLogger.w(TAG, "보관함 이동 실패(rename) id=$id → 복사 시도")
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                    DebugLogger.i(TAG, "보관함 복사 완료 id=$id → ${dst.absolutePath}")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "보관함 이동 실패 id=$id", e)
        }
    }

    fun start() {
        if (session != null) return
        try {
            session = SessionManager().apply {
                start()
                val sp = settings()
                    .listenInterfaces("0.0.0.0:6881")
                    .activeDownloads(4)
                    .connectionsLimit(200)
                    .maxPeerlistSize(5000)
                    .uploadRateLimit(1024)  // 기본 업로드 1KB/s
                    .downloadRateLimit(0) // 기본 다운로드 무제한
                applySettings(sp)
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
            applyRateLimits()
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
        persistNow()
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
        persistNow()
        DebugLogger.i(TAG, "torrent 파일 추가 id=$id name=$filename")

        scope.launch {
            try {
                val tempFile = File(saveDir, "temp_$id.torrent")
                tempFile.writeBytes(bytes)
                // torrent 파일을 복원용으로 영구 저장
                val persistFile = File(saveDir, "$id.torrent")
                persistFile.writeBytes(bytes)
                val ti = TorrentInfo(tempFile)
                val expectedHash = ti.infoHash().toString()
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
                    it.copy(name = ti.name(), totalSize = ti.totalSize(), files = files, infoHash = expectedHash)
                }
                DebugLogger.d(TAG, "torrent 파일 download 호출 완료 id=$id hash=$expectedHash name='${ti.name()}'")
                tempFile.delete()

                // torrent 파일은 ADD_TORRENT alert가 안 올 수 있으므로 직접 매핑 시도
                delay(300)
                if (!handleMap.containsKey(id)) {
                    try {
                        val th = session?.find(Sha1Hash.parseHex(expectedHash))
                        if (th != null && !hashToId.containsKey(expectedHash)) {
                            handleMap[id] = th
                            hashToId[expectedHash] = id
                            DebugLogger.d(TAG, "파일 torrent 수동 매핑 id=$id hash=$expectedHash")
                        }
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "파일 torrent 수동 매핑 실패 id=$id: ${e.message}")
                    }
                }

                // 메타데이터가 이미 있으므로 DOWNLOADING으로 전환
                if (handleMap.containsKey(id)) {
                    TorrentRepository.update(id) {
                        it.copy(state = TorrentState.DOWNLOADING)
                    }
                    persistNow()
                }
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
        val currentStatus = try { th.status() } catch (_: Exception) { null }
        val currentProgress = currentStatus?.progress() ?: 0f
        val currentDownloaded = currentStatus?.totalDone() ?: 0L
        val currentTotal = currentStatus?.total() ?: 0L
        th.pause()
        TorrentRepository.update(id) {
            it.copy(
                state = TorrentState.PAUSED,
                uploadSpeed = 0L,
                downloadSpeed = 0L,
                progress = currentProgress,
                downloadedSize = currentDownloaded,
                totalSize = currentTotal,
            )
        }
        persistNow()
        DebugLogger.i(TAG, "torrent 일시정지 id=$id progress=$currentProgress downloaded=$currentDownloaded")
    }

    fun resume(id: String) {
        val th = handleMap[id] ?: return
        val job = TorrentRepository.get(id) ?: return
        if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED) {
            th.resume()
            TorrentRepository.update(id) {
                it.copy(state = TorrentState.DOWNLOADING, errorMessage = null)
            }
            persistNow()
            DebugLogger.i(TAG, "torrent 재개 id=$id")
        }
    }

    fun cancel(id: String) {
        val job = TorrentRepository.get(id)
        val th = handleMap.remove(id)
        val isComplete = job?.state == TorrentState.DONE || job?.state == TorrentState.SEEDING
        var savePath = job?.savePath ?: ""
        var torrentName = job?.name ?: ""
        if (th != null) {
            try { torrentName = th.torrentFile().name() } catch (ex: Exception) { }
        }
        th?.let { session?.remove(it) }
        if (th != null) hashToId.remove(th.infoHash().toString())
        TorrentRepository.remove(id)
        persistNow()
        if (isComplete) {
            // 완료 → 보관함에 이미 이동됨, 목록에서만 제거
            DebugLogger.i(TAG, "torrent 완료 삭제(목록만) id=$id")
        } else {
            // 미완료 → 쓰레기 파일 모두 삭제
            if (savePath.isNotEmpty() && torrentName.isNotEmpty()) {
                try {
                    val dir = java.io.File(saveDir, torrentName)
                    if (dir.exists()) dir.deleteRecursively()
                    // 보관함에도 있을 수 있음
                    val storageFile = File(storageDir, torrentName)
                    if (storageFile.exists()) storageFile.deleteRecursively()
                    DebugLogger.i(TAG, "torrent 미완료 쓰레기 삭제 id=$id")
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "쓰레기 삭제 실패 id=$id: ${e.message}")
                }
            }
        }
        // .torrent 메타데이터 파일 정리
        try {
            val tf = java.io.File(context.filesDir, "torrents/$id.torrent")
            if (tf.exists()) tf.delete()
            val tf2 = File(saveDir, "$id.torrent")
            if (tf2.exists()) tf2.delete()
        } catch (_: Exception) {}
        DebugLogger.i(TAG, "torrent 취소 id=$id complete=$isComplete")
    }

    fun reorder(id: String, direction: Int) {
        val torrents = TorrentRepository.all()
        val idx = torrents.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + direction).coerceIn(0, torrents.size - 1)
        TorrentRepository.reorder(id, target)
    }

    /**
     * 설정값 → libtorrent 세션 반영
     * - 업로드 0 KB/s = 업로드 사용 안 함 (1KB/s 기본, tit-for-tat 완화)
     * - 다운로드 0 KB/s = 무제한 (libtorrent 기본 0 = 제한 없음)
     */
    private fun applyRateLimits() {
        val session = session ?: return
        val upBps = if (latestUploadKbps <= 0) 1024 else (latestUploadKbps * 1024).toInt().coerceAtLeast(1)
        val downBps = if (latestDownloadKbps <= 0) 0 else (latestDownloadKbps * 1024).toInt()
        try {
            session.uploadRateLimit(upBps)
            session.downloadRateLimit(downBps)
            val upLabel = if (latestUploadKbps <= 0) "끔" else "${latestUploadKbps}KB/s"
            val downLabel = if (latestDownloadKbps <= 0) "무제한" else "${latestDownloadKbps}KB/s"
            DebugLogger.i(TAG, "속도 제한 적용 업로드=$upLabel 다운로드=$downLabel")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "속도 제한 적용 실패", e)
        }
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
                    persistNow()
                    DebugLogger.d(TAG, "ADD_TORRENT 매핑 id=$id hash=$hash")
                }
            }
            AlertType.TORRENT_FINISHED -> {
                val th = (alert as org.libtorrent4j.alerts.TorrentFinishedAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                val job = TorrentRepository.get(id)
                val torrentName = job?.name ?: ""
                TorrentRepository.update(id) {
                    it.copy(
                        state = TorrentState.DONE,
                        progress = 1f,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                persistNow()
                DebugLogger.i(TAG, "torrent 완료 id=$id → 보관함 이동")
                if (torrentName.isNotEmpty()) {
                    moveToStorage(id, torrentName)
                }
            }
            AlertType.TORRENT_ERROR -> {
                val th = (alert as org.libtorrent4j.alerts.TorrentErrorAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = "토렌트 에러")
                }
                persistNow()
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
                    persistNow()
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
                try {
                    // handle 미매핑 torrent 자동 매핑 시도
                    val unmapped = TorrentRepository.all().filter { it.infoHash.isNotEmpty() && !handleMap.containsKey(it.id) }
                    for (job in unmapped) {
                        try {
                            val th = session?.find(Sha1Hash.parseHex(job.infoHash))
                            if (th != null) {
                                handleMap[job.id] = th
                                hashToId[job.infoHash] = job.id
                                DebugLogger.d(TAG, "폴링 자동 매핑 id=${job.id} hash=${job.infoHash}")
                            }
                        } catch (_: Exception) {}
                    }

                    val invalidIds = mutableListOf<String>()
                    handleMap.forEach { (id, th) ->
                        val status = try { th.status() } catch (_: Exception) {
                            DebugLogger.w(TAG, "무효 handle 제거 id=$id")
                            invalidIds.add(id)
                            return@forEach
                        }
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
                                seeds = status.listSeeds(),
                                peers = status.listPeers(),
                            )
                        }
                    }
                    invalidIds.forEach { id ->
                        val th = handleMap.remove(id)
                        th?.let { hashToId.remove(it.infoHash().toString()) }
                    }
                } catch (_: Exception) {}
                persistDebounced()
            }
        }
    }

    fun getPeers(id: String): List<Map<String, Any?>> {
        val th = handleMap[id] ?: return emptyList()
        return try {
            th.peerInfo().map { pi ->
                mapOf(
                    "ip" to pi.ip(),
                    "client" to pi.client(),
                    "downSpeed" to pi.downSpeed().toLong(),
                    "upSpeed" to pi.upSpeed().toLong(),
                    "progress" to pi.progress(),
                    "totalDownload" to pi.totalDownload(),
                    "totalUpload" to pi.totalUpload(),
                    "flags" to pi.flags(),
                    "connectionType" to (pi.connectionType()?.name ?: "unknown"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTorrentDetail(id: String): Map<String, Any?> {
        val job = TorrentRepository.get(id) ?: return emptyMap()
        val th = handleMap[id]
        val status = try { th?.status() } catch (_: Exception) { null }
        val peers = getPeers(id)
        val seeders = peers.count { it["flags"]?.let { f -> (f as? Int)?.and(0x1) != 0 } ?: false }
        val leechers = peers.size - seeders

        return mapOf(
            "id" to job.id,
            "name" to job.name,
            "infoHash" to job.infoHash,
            "state" to job.state.name,
            "progress" to job.progress,
            "downloadSpeed" to job.downloadSpeed,
            "uploadSpeed" to job.uploadSpeed,
            "totalSize" to job.totalSize,
            "downloadedSize" to job.downloadedSize,
            "seeds" to job.seeds,
            "peers" to job.peers,
            "savePath" to job.savePath,
            "startedAt" to job.startedAt,
            "finishedAt" to job.finishedAt,
            "errorMessage" to job.errorMessage,
            "magnet" to job.magnet,
            "files" to runCatching {
                val fp = th?.fileProgress()
                job.files.mapIndexed { i, f ->
                    val downloaded = if (fp != null && i < fp.size) fp[i] else 0L
                    val pct = if (f.size > 0) downloaded.toDouble() / f.size else 0.0
                    mapOf(
                        "index" to f.index,
                        "path" to f.path,
                        "size" to f.size,
                        "progress" to pct.coerceIn(0.0, 1.0),
                        "downloaded" to downloaded,
                        "selected" to f.selected,
                    )
                }
            }.getOrElse { job.files.map { f ->
                mapOf("index" to f.index, "path" to f.path, "size" to f.size, "progress" to f.progress, "selected" to f.selected)
            }},
            "connectedPeers" to peers.sortedWith(compareByDescending<Map<String, Any?>> { (it["progress"] as? Number)?.toDouble() ?: 0.0 }.thenByDescending { (it["downSpeed"] as? Number)?.toLong() ?: 0L }),
            "connectedSeeders" to seeders,
            "connectedLeechers" to leechers,
            "currentTracker" to (status?.currentTracker() ?: ""),
            "numConnections" to (status?.numConnections() ?: 0),
            "numPieces" to (status?.numPieces() ?: 0),
            "listSeeds" to (status?.listSeeds() ?: 0),
            "listPeers" to (status?.listPeers() ?: 0),
            "numComplete" to (status?.numComplete() ?: 0),
            "numIncomplete" to (status?.numIncomplete() ?: 0),
            "isSeeding" to (status?.isSeeding ?: false),
            "seedRank" to (status?.seedRank() ?: 0),
        )
    }

    private fun restoreTorrents() {
        val restored = persistence.load()
        restored.forEach { job ->
            if (job.state != TorrentState.DONE && job.state != TorrentState.FAILED) {
                TorrentRepository.restore(job.copy(state = TorrentState.QUEUED))
                val hasMagnet = job.magnet != null && job.magnet!!.startsWith("magnet:")
                if (hasMagnet) {
                    scope.launch {
                        try {
                            session?.download(job.magnet, saveDir, torrent_flags_t())
                            DebugLogger.d(TAG, "magnet 복원 id=${job.id}")
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "magnet 복원 실패 id=${job.id}", e)
                            TorrentRepository.update(job.id) {
                                it.copy(state = TorrentState.FAILED, errorMessage = "magnet 복원 실패: ${e.message}")
                            }
                        }
                    }
                } else {
                    // torrent 파일 기반 복원: 저장된 .torrent 파일로 재다운로드
                    val torrentFile = File(saveDir, "${job.id}.torrent")
                    if (torrentFile.exists()) {
                        scope.launch {
                            try {
                                val ti = TorrentInfo(torrentFile)
                                val expectedHash = ti.infoHash().toString()
                                session?.download(ti, saveDir)
                                DebugLogger.d(TAG, "torrent 파일 복원 id=${job.id} hash=$expectedHash")

                                delay(500)
                                if (!handleMap.containsKey(job.id)) {
                        val th = session?.find(Sha1Hash.parseHex(expectedHash))
                                    if (th != null) {
                                        handleMap[job.id] = th
                                        hashToId[expectedHash] = job.id
                                        TorrentRepository.update(job.id) {
                                            it.copy(infoHash = expectedHash, state = TorrentState.DOWNLOADING)
                                        }
                                        DebugLogger.d(TAG, "파일 torrent 복원 매핑 id=${job.id} hash=$expectedHash")
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLogger.e(TAG, "torrent 파일 복원 실패 id=${job.id}", e)
                                TorrentRepository.update(job.id) {
                                    it.copy(state = TorrentState.FAILED, errorMessage = e.message)
                                }
                            }
                        }
                    } else {
                        DebugLogger.w(TAG, "torrent 파일 없음 id=${job.id}, 복원 불가")
                        TorrentRepository.update(job.id) {
                            it.copy(state = TorrentState.FAILED, errorMessage = "torrent 파일 없음 — 재추가 필요")
                        }
                    }
                }
            } else {
                TorrentRepository.restore(job)
            }
        }
        DebugLogger.d(TAG, "복원 완료 ${restored.size}건")
    }
}
