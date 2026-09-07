package com.borasarang.droidrelay.relay

import android.content.Context
import com.borasarang.droidrelay.relay.DebugLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType

/** 시드 비율 도달 여부 — 순수 함수 (T-956) */
internal fun shouldPauseAtRatio(isSeeding: Boolean, uploaded: Long, downloaded: Long, limit: Float): Boolean {
    if (!isSeeding || limit <= 0 || downloaded <= 0) return false
    return uploaded.toDouble() / downloaded >= limit
}

/** magnet URI의 SHA-1 infohash(hex 40자) 그룹 캡처 */
private val magnetHexRegex = Regex("urn:btih:([0-9a-fA-F]{40})")

class TorrentEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val persistence: TorrentPersistence,
) {
    private val TAG = "TorrentEngine"
    private var session: SessionManager? = null
    // libtorrent JNI는 스레드 안전하지 않음 — remove/status 경합으로 네이티브 SIGSEGV (T-930)
    // 모든 세션/핸들 접근을 단일 락으로 직렬화한다.
    private val sessionGate = ReentrantLock()
    private inline fun <T> withGate(block: () -> T): T {
        sessionGate.lock()
        return try { block() } finally { sessionGate.unlock() }
    }

    /** alert 콜백 전용: stop()가 alert 스레드를 join하며 게이트를 잡고 있으면 데드락 → 타임아웃 후 스킵 */
    private inline fun withGateAlert(block: () -> Unit): Boolean {
        if (!sessionGate.tryLock(300, TimeUnit.MILLISECONDS)) {
            DebugLogger.w(TAG, "게이트 대기 타임아웃 — alert 스킵 (stop 진행 중일 수 있음)")
            return false
        }
        return try { block(); true } finally { sessionGate.unlock() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handleMap = ConcurrentHashMap<String, TorrentHandle>()
    private val hashToId = ConcurrentHashMap<String, String>()

    /** id↔hash 양방향 매핑 원자 등록 — 교차 매핑 덮어쓰기 정리 (T-934 S3) */
    private fun registerMapping(id: String, hash: String, th: TorrentHandle) {
        hashToId[hash]?.takeIf { it != id }?.let { handleMap.remove(it) }
        hashToId.entries.removeIf { it.value == id && it.key != hash }
        handleMap[id] = th
        hashToId[hash] = id
        // 재매핑(재시작·복원) 시 영속된 파일 선택 복원 (T-942)
        applyPersistedSelection(id)
    }

    /** 영속된 파일 선택을 libtorrent 우선순위로 적용 (T-942) */
    private fun applyPersistedSelection(id: String) {
        val job = TorrentRepository.get(id) ?: return
        if (job.files.isEmpty()) return
        val th = handleMap[id] ?: return
        try {
            withGate {
                th.prioritizeFiles(Array(job.files.size) { i ->
                    if (job.files[i].selected) Priority.DEFAULT else Priority.IGNORE
                })
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "파일 선택 적용 실패 id=$id: ${e.message}")
        }
    }

    /** 파일 선택 변경 → 영속 + 즉시 적용 (T-942) */
    fun setFileSelection(id: String, selected: Set<Int>): Boolean {
        val job = TorrentRepository.get(id) ?: return false
        if (job.files.isEmpty()) return false
        TorrentRepository.update(id) { it.copy(files = it.files.map { f -> f.copy(selected = f.index in selected) }) }
        persistNow()
        applyPersistedSelection(id)
        DebugLogger.i(TAG, "[FEATURE] 파일 선택 id=$id ${selected.size}/${job.files.size}개")
        return true
    }

    /** 매핑 해제 — job.infoHash 폴백으로 좀비 hashToId 방지 (T-934 S3) */
    private fun unregisterMapping(id: String) {
        handleMap.remove(id)
        val hash = TorrentRepository.get(id)?.infoHash?.takeIf { it.isNotEmpty() }
        if (hash != null) hashToId.remove(hash, id) else hashToId.entries.removeIf { it.value == id }
    }
    private var statusPollingJob: Job? = null

    @Volatile private var latestUploadKbps: Long = 0L
    @Volatile private var latestDownloadKbps: Long = 0L
    @Volatile private var latestSequentialDownload: Boolean = false
    @Volatile private var torrentMinSeedWaitSec: Int = 0
    @Volatile private var latestSeedRatio: Float = 2.0f
    @Volatile private var latestDhtEnabled: Boolean = true
    @Volatile private var latestSavePath: String = "/sdcard/Download/DroidRelay"
    /** id → 시더 부재 대기 시작 시각(ms). 0이면 미측정 */
    private val seedWaitSince = ConcurrentHashMap<String, Long>()
    private var lastPersistAt = 0L
    private var lastSavedSnapshot: List<TorrentJob>? = null
    private fun persistNow() {
        try { persistence.save(TorrentRepository.all()) } catch (_: Exception) {}
        lastSavedSnapshot = TorrentRepository.all()
    }
    private fun persistDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt > 10_000) { // 10초 간격
            // 내용이 마지막 저장본과 달라졌을 때만 저장 (T-845)
            if (lastSavedSnapshot != TorrentRepository.all()) {
                lastPersistAt = now
                persistNow()
            }
        }
    }

    init {
        scope.launch {
            settings.settings.collect { s ->
                latestUploadKbps = s.torrentUploadLimit
                latestDownloadKbps = s.torrentDownloadLimit
                latestSequentialDownload = s.torrentSequentialDownload
                latestSeedRatio = s.torrentSeedRatio
                latestDhtEnabled = s.torrentDhtEnabled
                latestSavePath = s.torrentSavePath.ifBlank { "/sdcard/Download/DroidRelay" }
                DebugLogger.d(TAG, "설정 반영 업로드=${s.torrentUploadLimit}KB/s 다운로드=${s.torrentDownloadLimit}KB/s 시퀀셜=${s.torrentSequentialDownload} 비율=${s.torrentSeedRatio} DHT=${s.torrentDhtEnabled}")
                applyRateLimits()
                applySequentialToAll(s.torrentSequentialDownload)
                applyDhtEnabled(s.torrentDhtEnabled)
            }
        }
    }

    val saveDir: File
        get() = File(context.getExternalFilesDir(null), "torrents").apply { mkdirs() }

    /** 보관함 경로 — 설정값 (기본 /sdcard/Download/DroidRelay, T-958) */
    private val storageDir: File
        get() = File(latestSavePath).apply { mkdirs() }

    /** 완료된 토렌트 파일을 보관함으로 이동 */
    private fun moveToStorage(id: String, torrentName: String) {
        val src = File(saveDir, torrentName)
        if (!src.exists()) {
            DebugLogger.w(TAG, "보관함 이동 스킵(소스 없음) id=$id name=$torrentName")
            return
        }
        val dst = File(storageDir, torrentName)
        try {
            if (dst.exists()) dst.deleteRecursively()
            if (src.isDirectory) {
                src.renameTo(dst).also { ok ->
                    if (!ok) {
                        DebugLogger.w(TAG, "보관함 이동 실패(rename) id=$id → 복사 시도")
                        src.copyRecursively(dst, overwrite = true)
                        src.deleteRecursively()
                    }
                }
            } else {
                // 단일 파일 토렌트
                src.renameTo(dst).also { ok ->
                    if (!ok) {
                        DebugLogger.w(TAG, "보관함 이동 실패(rename) id=$id → 복사 시도")
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                    }
                }
            }
            DebugLogger.i(TAG, "보관함 이동 완료 id=$id → ${dst.absolutePath}")
            // 보관함 자동 운영 (v0.19) — 단일 파일은 분류+쿼터, 폴더는 쿼터만
            runCatching {
                if (dst.isFile) StorageJanitor.onCompleted(context, dst)
                else StorageJanitor.enforceIfNeeded(context)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "보관함 이동 실패 id=$id", e)
        }
    }

    fun start() {
        if (session != null) return
        withGate {
            if (session != null) return
            try {
                session = SessionManager().apply {
                    start()
                    val sp = settings()
                        .listenInterfaces("0.0.0.0:${settings.firstBlocking().torrentListenPort}")
                        .activeDownloads(3)
                        .connectionsLimit(200)
                        .maxPeerlistSize(5000)
                        .uploadRateLimit(1024)  // 기본 업로드 1KB/s (0=사용안함 → 1KB/s로 완화)
                        .downloadRateLimit(0) // 기본 다운로드 무제한
                    applySettings(sp)
                    // DHT는 설정에 따라 (T-957)
                    if (settings.firstBlocking().torrentDhtEnabled) startDht()
                    else DebugLogger.i(TAG, "DHT 끔 (설정)")
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
                DebugLogger.i(TAG, "세션 시작 완료")
                applyRateLimits()
                startStatusPolling()
                restoreTorrents()
            } catch (e: Exception) {
                DebugLogger.e(TAG, "세션 시작 실패", e)
            }
        }
    }

    fun stop() {
        withGate {
            statusPollingJob?.cancel()
            session?.stop()
            session = null
            handleMap.clear()
            hashToId.clear()
            seedWaitSince.clear()
            DebugLogger.i(TAG, "세션 정지")
        }
    }

    fun addMagnet(magnet: String): TorrentJob {
        val hash = magnetInfoHash(magnet)
        // 중복 가드: 같은 infohash가 이미 활성/완료 상태면 새 job을 만들지 않고 기존을 반환
        // (libtorrent는 동일 infohash download를 조용히 무시 → 새 job이 FETCHING_METADATA에 갇힘)
        if (hash != null) {
            val existing = TorrentRepository.all().find { it.infoHash.isNotEmpty() && it.infoHash == hash }
            if (existing != null) {
                DebugLogger.i(TAG, "중복 magnet 감지 (${hash}) → 기존 id=${existing.id} 반환")
                return existing
            }
        }
        val id = TorrentRepository.newId()
        val job = TorrentJob(
            id = id,
            infoHash = hash ?: "",
            name = "추출 중...",
            magnet = magnet,
            state = TorrentState.FETCHING_METADATA,
            savePath = saveDir.absolutePath,
            startedAt = System.currentTimeMillis(),
        )
        TorrentRepository.add(job)
        persistNow()
        DebugLogger.i(TAG, "magnet 추가 id=$id hash=${hash ?: "-"}")

        scope.launch {
            try {
                val flags = if (latestSequentialDownload) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
                withGate { session?.download(magnet, saveDir, flags) }
                DebugLogger.d(TAG, "magnet download 호출 완료 id=$id (ADD_TORRENT 대기) 시퀀셜=$latestSequentialDownload")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "magnet 추가 실패 id=$id", e)
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = e.message)
                }
            }
        }
        return job
    }

    /** magnet의 infohash가 이미 다운로드 중(활성/완료)인지 중복 여부 — 라우트에서 선검사용 */
    fun isDuplicateMagnet(magnet: String): Boolean {
        val hash = magnetInfoHash(magnet) ?: return false
        return TorrentRepository.all().any { it.infoHash.isNotEmpty() && it.infoHash == hash }
    }

    /** .torrent URL 다운로드 → 파일 추가 (검색 결과 바로 받기, T-950) */
    fun addTorrentUrl(url: String): TorrentJob {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .build()
        val bytes = client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IllegalStateException("빈 응답")
        }
        if (bytes.size < 8 || bytes.size > 10 * 1_048_576) throw IllegalStateException("torrent 파일 크기 이상 (${bytes.size}B)")
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "search-${System.currentTimeMillis()}.torrent" }
        return addTorrentFile(bytes, name)
    }

    /** Torznab 검색 — 설정 미완이면 예외 (T-950) */
    fun search(q: String): List<TorznabClient.Result> {
        val s = settings.firstBlocking()
        if (!s.searchEnabled || s.searchUrl.isBlank() || s.searchApiKey.isBlank()) {
            throw IllegalStateException("토렌트 검색 미설정 — 설정에서 Jackett/Prowlarr을 입력하세요")
        }
        return TorznabClient(context).search(s.searchUrl, s.searchApiKey, q)
    }

    /** magnet URI에서 infohash(SHA-1 hex 40자) 추출. 없거나 base32면 null. */
    private fun magnetInfoHash(magnet: String): String? {
        return magnetHexRegex.find(magnet)?.groupValues?.get(1)
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
                val flags = if (latestSequentialDownload) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
                withGate { session?.download(ti, saveDir, null, null, null, flags) }
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

                // torrent 파일은 ADD_TORRENT alert가 안 올 수 있으므로 직접 매핑 시도 (재시도 루프)
                var mapped = handleMap.containsKey(id)
                if (!mapped) {
                    repeat(25) { // 최대 5초
                        delay(200)
                        try {
val th = withGate { session?.find(Sha1Hash.parseHex(expectedHash)) }
                            if (th != null) {
                                registerMapping(id, expectedHash, th)
                                DebugLogger.d(TAG, "파일 torrent 수동 매핑 id=$id hash=$expectedHash")
                            }
                        } catch (_: Exception) {}
                        if (handleMap.containsKey(id)) { mapped = true; return@repeat }
                    }
                }

                // 메타데이터가 이미 있으므로 DOWNLOADING으로 전환 + 트래커 발표 강제
                if (mapped) {
                    TorrentRepository.update(id) {
                        it.copy(state = TorrentState.DOWNLOADING)
                    }
                    handleMap[id]?.let { announceKick(it, id) }
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
        withGate {
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
        }
        persistNow()
        DebugLogger.i(TAG, "torrent 일시정지 id=$id")
    }

    fun resume(id: String) {
        val th = handleMap[id] ?: return
        val job = TorrentRepository.get(id) ?: return
        if (job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED) {
            withGate { th.resume() }
            TorrentRepository.update(id) {
                it.copy(state = TorrentState.DOWNLOADING, errorMessage = null)
            }
            persistNow()
            DebugLogger.i(TAG, "torrent 재개 id=$id")
        }
    }

    fun cancel(id: String) {
        val job = TorrentRepository.get(id)
        val th = handleMap[id]
        val isComplete = job?.state == TorrentState.DONE || job?.state == TorrentState.SEEDING
        val savePath = job?.savePath ?: ""
        val infoHash = job?.infoHash ?: ""
        var torrentName = job?.name ?: ""
        // Repository를 먼저 제거 → polling 재등록 레이스 차단, 매핑 해제 (T-934 S3)
        TorrentRepository.remove(id)
        unregisterMapping(id)
        seedWaitSince.remove(id)
        withGate {
            th?.let {
                try { torrentName = it.torrentFile().name() } catch (_: Exception) {}
            }
            th?.let { session?.remove(it) }
        }
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
            // FETCHING_METADATA 단계 잔존: job.name="추출 중..."이라 위 매칭이 빗나간
            // libtorrent 실제 디렉토리(saveDir/<infohash>/) 정리 (T-937)
            if (infoHash.isNotEmpty()) {
                try {
                    val hashDir = java.io.File(saveDir, infoHash)
                    if (hashDir.exists()) {
                        hashDir.deleteRecursively()
                        DebugLogger.i(TAG, "torrent 미완료 잔존 infohash 정리 id=$id")
                    }
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "infohash 정리 실패 id=$id: ${e.message}")
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
        val upBps = if (latestUploadKbps <= 0) 1024 else (latestUploadKbps * 1024).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val downBps = if (latestDownloadKbps <= 0) 0 else (latestDownloadKbps * 1024).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        try {
            withGate {
                // stop()과 레이스 방지 — 게이트 안에서 null 체크 (T-934 S4)
                val session = session ?: return@withGate
                session.uploadRateLimit(upBps)
                session.downloadRateLimit(downBps)
            }
            val upLabel = if (latestUploadKbps <= 0) "끔" else "${latestUploadKbps}KB/s"
            val downLabel = if (latestDownloadKbps <= 0) "무제한" else "${latestDownloadKbps}KB/s"
            DebugLogger.i(TAG, "속도 제한 적용 업로드=$upLabel 다운로드=$downLabel")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "속도 제한 적용 실패", e)
        }
    }

    /** 전역 속도 제한 (웹/앱에서 즉시 적용) — BPS 단위 */
    fun applySpeedLimit(downloadBps: Long, uploadBps: Long) {
        // Long → Int 오버플로우 방지 (libtorrent는 Int BPS)
        val upBps = if (uploadBps <= 0) 1024 else uploadBps.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val downBps = if (downloadBps <= 0) 0 else downloadBps.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        try {
            withGate {
                // stop()과 레이스 방지 — 게이트 안에서 null 체크 (T-934 S4)
                val session = session ?: return@withGate
                session.uploadRateLimit(upBps)
                session.downloadRateLimit(downBps)
            }
            val upLabel = if (uploadBps <= 0) "끔" else fmtBps(upBps)
            val downLabel = if (downloadBps <= 0) "무제한" else fmtBps(downBps)
            DebugLogger.i(TAG, "전역 속도 제한 적용 업로드=$upLabel 다운로드=$downLabel")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "전역 속도 제한 적용 실패", e)
        }
    }

    private fun fmtBps(bps: Int): String = when {
        bps < 1024 -> "${bps}B/s"
        bps < 1_048_576 -> "${bps / 1024}KB/s"
        else -> "${bps / 1_048_576}MB/s"
    }

    /** 시퀀셜 다운로드를 전체 활성 토렌트에 적용 */
    private fun applySequentialToAll(sequential: Boolean) {
        withGate {
            handleMap.forEach { (id, th) ->
                applySequentialToHandle(th, sequential)
                DebugLogger.i(TAG, "시퀀셜 핸들 적용 id=$id seq=$sequential")
            }
            if (handleMap.isNotEmpty()) {
                DebugLogger.i(TAG, "시퀀셜 다운로드 ${if (sequential) "활성화" else "비활성화"} (${handleMap.size}개 토렌트)")
            }
        }
    }

    /** 시퀀셜 다운로드를 개별 핸들에 적용 */
    private fun applySequentialToHandle(th: TorrentHandle, sequential: Boolean) {
        try {
            if (sequential) {
                th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            } else {
                th.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "시퀀셜 핸들 설정 실패: ${e.message}")
        }
    }

    /** DHT on/off 반영 (T-957) */
    private fun applyDhtEnabled(enabled: Boolean) {
        try {
            withGate {
                val session = session ?: return@withGate
                val running = runCatching { session.isDhtRunning() }.getOrDefault(true)
                if (enabled && !running) {
                    session.startDht()
                    DebugLogger.i(TAG, "DHT 시작")
                } else if (!enabled && running) {
                    session.stopDht()
                    DebugLogger.i(TAG, "DHT 정지")
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "DHT 전환 실패: ${e.message}")
        }
    }

    /** 전체 설정 동적 적용 (재시작 불필요) */
    fun applySettings(s: AppSettings) {
        withGate {
            // stop()과 레이스 방지 — 게이트 안에서 null 체크 (T-934 S4)
            val session = session ?: return@withGate
            val sp = session.settings()
                .activeDownloads(s.torrentMaxActive)
                .connectionsLimit(200)
                .maxPeerlistSize(5000)
            session.applySettings(sp)

            // 리슨 포트 변경은 재시작 필요 — 로그만 남김
            if (s.torrentListenPort != 6881) {
                DebugLogger.w(TAG, "listenPort(${s.torrentListenPort}) 변경은 서버 재시작 후 반영됩니다")
            }

            // 속도 제한도 함께 적용
            val upBps = if (s.torrentUploadLimit <= 0) 1024 else (s.torrentUploadLimit * 1024).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val downBps = if (s.torrentDownloadLimit <= 0) 0 else (s.torrentDownloadLimit * 1024).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            try {
                session.uploadRateLimit(upBps)
                session.downloadRateLimit(downBps)
            } catch (e: Exception) { DebugLogger.e(TAG, "토렌트 속도 제한 적용 실패", e) }
            DebugLogger.i(TAG, "토렌트 설정 적용 maxActive=${s.torrentMaxActive} up=${if(s.torrentUploadLimit<=0) "끔" else "${s.torrentUploadLimit}KB/s"} down=${if(s.torrentDownloadLimit<=0) "무제한" else "${s.torrentDownloadLimit}KB/s"}")

            // 시더 부재 자동 중단 대기 시간 (0 = 꺼짐)
            torrentMinSeedWaitSec = s.torrentMinSeedWaitSec
            latestSeedRatio = s.torrentSeedRatio
            latestDhtEnabled = s.torrentDhtEnabled
            latestSavePath = s.torrentSavePath.ifBlank { "/sdcard/Download/DroidRelay" }
            applyDhtEnabled(s.torrentDhtEnabled)
        }
    }

    private fun handleAlert(alert: Alert<*>) {
        // JNI 호출 포함 — remove/status 경합 방지 위해 게이트 내에서 처리
        // 주의: alert.handle()은 콜백 생존 중에만 유효한 transient 참조 → 장기 보관 금지 (T-931).
        // FINISHED의 파일 이동은 게이트 밖에서 처리 (T-934 S2).
        var finishedMove: Pair<String, String>? = null
        withGateAlert {
            when (alert.type()) {
            AlertType.ADD_TORRENT -> {
                val addAlert = alert as org.libtorrent4j.alerts.AddTorrentAlert
                // alert.handle()이 주는 TorrentHandle은 alert C++ 객체 내부 메모리를 가리키는 참조
                // (swigCMemOwn=false) → alert 소멸 시 dangling → 장기 보관 금지 (T-931)
                // 안전한 장기 참조는 session.find() 기반 독립 heap 카피로 보관한다.
                val hash = addAlert.handle().infoHash().toString()
                val id = findJobIdForNewTorrent(hash)
                if (id != null) {
                    val th = session?.find(Sha1Hash.parseHex(hash))
                    if (th != null) {
                        registerMapping(id, hash, th)
                        TorrentRepository.update(id) {
                            it.copy(infoHash = hash)
                        }
                        // 시퀀셜 다운로드 적용
                        applySequentialToHandle(th, latestSequentialDownload)
                        persistNow()
                        DebugLogger.d(TAG, "ADD_TORRENT 매핑(id=find 기반) id=$id hash=$hash 시퀀셜=$latestSequentialDownload")
                    } else {
                        DebugLogger.w(TAG, "ADD_TORRENT 하: session.find 실패 id=$id hash=$hash — 폴링 매핑 대기")
                        TorrentRepository.update(id) {
                            it.copy(infoHash = hash)
                        }
                    }
                }
            }
            AlertType.TORRENT_FINISHED -> {
                // transient handle: 콜백 내에서 infoHash 조회만 (저장 금지)
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
                DebugLogger.i(TAG, "torrent 완료 id=$id → 보관함 이동")
                finishedMove = id to torrentName
            }
            AlertType.TORRENT_ERROR -> {
                // transient handle: 콜백 내에서 infoHash 조회만 (저장 금지)
                val th = (alert as org.libtorrent4j.alerts.TorrentErrorAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = "토렌트 에러")
                }
                persistNow()
                DebugLogger.e(TAG, "torrent 에러 id=$id")
            }
            AlertType.METADATA_RECEIVED -> {
                // transient handle: 콜백 내에서만 torrentFile 읽기 (저장 금지)
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
        // 게이트 밖 파일 IO (락 점유 최소화)
        finishedMove?.let { (id, name) ->
            if (name.isNotEmpty()) moveToStorage(id, name)
            persistNow()
        }
    }

    /** ADD_TORRENT 시 아직 매핑되지 않은 torrent를 찾음 */
    private fun findJobIdForNewTorrent(hash: String): String? {
        // 이미 매핑된 경우
        hashToId[hash]?.let { return it }
        // addMagnet이 미리 infoHash를 주입한 job과 정확히 일치하는 것 우선 매핑 (교차 매핑 방지)
        TorrentRepository.all()
            .firstOrNull { it.infoHash == hash && it.state == TorrentState.FETCHING_METADATA }
            ?.let { return it.id }
        // infoHash가 비어있는 가장 오래된 미매핑 torrent (해시 파싱 실패 폴백, FIFO)
        val candidates = TorrentRepository.all().filter { it.infoHash.isEmpty() && it.state == TorrentState.FETCHING_METADATA }
        return candidates.minByOrNull { it.id }?.id
    }

    private fun startStatusPolling() {
        statusPollingJob = scope.launch {
            while (true) {
                delay(5_000L)
                try {
                    // handle 미매핑 torrent 자동 매핑 시도
                    val unmapped = TorrentRepository.all().filter { it.infoHash.isNotEmpty() && !handleMap.containsKey(it.id) }
                    for (job in unmapped) {
                        try {
                            val th = withGate { session?.find(Sha1Hash.parseHex(job.infoHash)) }
                            if (th != null) {
                                registerMapping(job.id, job.infoHash, th)
                                DebugLogger.d(TAG, "폴링 자동 매핑 id=${job.id} hash=${job.infoHash}")
                                // 새로 매핑된 즉시 트래커/DHT 발표 (시드·피어 수집 지연 방지)
                                announceKick(th, job.id)
                            }
                        } catch (_: Exception) {}
                    }

                    val invalidIds = mutableListOf<String>()
                    handleMap.forEach { (id, th) ->
                        val status = try { withGate { th.status() } } catch (_: Exception) {
                            DebugLogger.w(TAG, "무효 handle 제거 id=$id")
                            invalidIds.add(id)
                            return@forEach
                        }
                        val job = TorrentRepository.get(id) ?: return@forEach
                        // 시드 비율 강제 (T-956) — SEEDING 중 비율 도달 시 자동 일시정지 (0=제한 없음)
                        val ratioLimit = latestSeedRatio
                        if (shouldPauseAtRatio(status.isSeeding, status.totalUpload(), status.totalDownload(), ratioLimit)) {
                            val ratio = status.totalUpload().toDouble() / status.totalDownload()
                            DebugLogger.i(TAG, "[FEATURE] 시드 비율 도달(${String.format("%.2f", ratio)}≥$ratioLimit) → 자동 일시정지 id=$id")
                            pause(id)
                            return@forEach
                        }
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

                        // 시더 부재 자동 중단 (이슈 4) — 설정 토글 시에만 동작
                        val seedLimit = torrentMinSeedWaitSec
                        if (seedLimit > 0 && state == TorrentState.DOWNLOADING && status.progress() < 1f) {
                            if (status.listSeeds() > 0) {
                                seedWaitSince.remove(id)
                            } else {
                                val waitSince = seedWaitSince[id] ?: System.currentTimeMillis().also { seedWaitSince[id] = it }
                                val waitedSec = (System.currentTimeMillis() - waitSince) / 1000
                                if (waitedSec >= seedLimit) {
                                    seedWaitSince.remove(id)
                                    DebugLogger.w(TAG, "시더 부재 ${waitedSec}초(제한 ${seedLimit}초) → 자동 일시정지 id=$id")
                                    pause(id)
                                }
                            }
                        } else {
                            seedWaitSince.remove(id)
                        }
                    }
                    invalidIds.forEach { unregisterMapping(it) }
                } catch (_: Exception) {}
                persistDebounced()
            }
        }
    }

    /** 트래커 재발표 + DHT 발표 강제 (추가 직후 시드·피어 0 지연 해소) */
    private fun announceKick(th: TorrentHandle, id: String) {
        try {
            withGate {
                th.forceReannounce()
                th.forceDHTAnnounce()
            }
        } catch (_: Exception) {}
        DebugLogger.i(TAG, "발표 강제 id=$id (tracker+DHT)")
    }

    /** 조각 정보: (보유 조각 수, 전체 조각 수) */
    fun pieceInfo(id: String): Pair<Int, Int> {
        val th = handleMap[id] ?: return 0 to 0
        return try {
            withGate {
                val status = th.status()
                val tf = try { th.torrentFile() } catch (_: Exception) { null }
                val total = tf?.numPieces() ?: 0
                val pieceLen = tf?.pieceLength()?.toLong() ?: 0L
                val done = if (pieceLen > 0) (((status.totalDone() + pieceLen - 1) / pieceLen).toInt()) else 0
                done.coerceAtMost(total) to total
            }
        } catch (_: Exception) {
            0 to 0
        }
    }

    fun getPeers(id: String): List<Map<String, Any?>> {
        val th = handleMap[id] ?: return emptyList()
        return try {
            withGate {
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
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTorrentDetail(id: String): Map<String, Any?> {
        val job = TorrentRepository.get(id) ?: return emptyMap()
        val th = handleMap[id]
        val status = if (th != null) {
            try { withGate { th.status() } } catch (_: Exception) { null }
        } else null
        val peers = getPeers(id)
        val seeders = peers.count { it["flags"]?.let { f -> (f as? Int)?.and(0x1) != 0 } ?: false }
        val leechers = peers.size - seeders

        val fileProgress = if (th != null) {
            try { withGate { th.fileProgress() } } catch (_: Exception) { null }
        } else null

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
                val fp = fileProgress
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
                            val flags = if (latestSequentialDownload) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
                            withGate { session?.download(job.magnet, saveDir, flags) }
                            DebugLogger.d(TAG, "magnet 복원 id=${job.id} 시퀀셜=$latestSequentialDownload")
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
                                val flags = if (latestSequentialDownload) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
                                withGate { session?.download(ti, saveDir, null, null, null, flags) }
                                DebugLogger.d(TAG, "torrent 파일 복원 id=${job.id} hash=$expectedHash")

                                delay(500)
                                if (!handleMap.containsKey(job.id)) {
                        val th = withGate { session?.find(Sha1Hash.parseHex(expectedHash)) }
                                    if (th != null) {
                                        registerMapping(job.id, expectedHash, th)
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
