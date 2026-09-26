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

/** 정체(스톨) 판정 — 순수 함수 (T-1050).
 *  - 끄기이면 항상 false
 *  - 기준 KB/s > 0 이면서 속도가 그 미만
 *  - 또는 연결된 시더가 0 (파실 물건이 아직 안 잡힘) */
internal fun isStalledTorrent(enabled: Boolean, rateBps: Long, thresholdKbps: Int, numSeeds: Int): Boolean {
    if (!enabled) return false
    val thresholdBps = thresholdKbps.toLong() * 1024
    return (thresholdBps > 0 && rateBps < thresholdBps) || numSeeds == 0
}

/** 정체 조건이 임계 시간만큼 유지됐는지 판정 — 순수 함수 (T-1050) */
internal fun stallTimedOut(sinceMs: Long, nowMs: Long, timeoutSec: Int): Boolean =
    timeoutSec > 0 && nowMs - sinceMs >= timeoutSec * 1000L

/** magnet URI의 SHA-1 infohash(hex 40자) 그룹 캡처 */
private val magnetHexRegex = Regex("urn:btih:([0-9a-fA-F]{40})")
/** magnet URI의 SHA-1 infohash(base32 32자, padding 없음) 그룹 캡처 */
private val magnetBase32Regex = Regex("urn:btih:([A-Za-z2-7]{32})")

/** base32 infohash(32자) → hex(40자) — RFC 4648. 잘못된 문자면 null */
internal fun base32ToHex(s: String): String? {
    if (s.length != 32) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var value = 0
    val sb = StringBuilder(40)
    for (c in s.uppercase()) {
        val idx = alphabet.indexOf(c)
        if (idx < 0) return null
        value = (value shl 5) or idx
        bits += 5
        if (bits >= 8) {
            bits -= 8
            sb.append(((value shr bits) and 0xFF).toString(16).padStart(2, '0'))
        }
    }
    return if (sb.length == 40) sb.toString() else null
}

/** magnet URI에서 infohash(SHA-1 hex 40자) 추출 — hex 40자 / base32 32자 모두 지원. 없으면 null. */
internal fun magnetInfoHash(magnet: String): String? {
    magnetHexRegex.find(magnet)?.groupValues?.get(1)?.let { return it.lowercase() }
    magnetBase32Regex.find(magnet)?.groupValues?.get(1)?.let { base32ToHex(it) }?.let { return it }
    return null
}

/** 동일 infohash의 .torrent 중복 추가 방지 (결함 #9) */
class DuplicateTorrentException(val infoHash: String) : IllegalStateException("이미 다운로드 중인 토렌트입니다")

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
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** id↔hash 양방향 매핑 원자 등록 — 교차 매핑 덮어쓰기 정리 (T-934 S3) */
    private fun registerMapping(id: String, hash: String, th: TorrentHandle) {
        hashToId[hash]?.takeIf { it != id }?.let { handleMap.remove(it) }
        hashToId.entries.removeIf { it.value == id && it.key != hash }
        handleMap[id] = th
        hashToId[hash] = id
        // 재매핑(재시작·복원) 시 영속된 파일 선택 복원 (T-942)
        applyPersistedSelection(id)
        // 영속된 torrent 개별 다운로드 제한 복원 (T-1050)
        applyPersistedLimit(id)
        // 표시 순서 ↔ libtorrent 큐 순서 동기 (T-1050)
        syncQueueOrder()
        // 동기된 커뮤니티 트래커 주입 (T-971, 실패해도 계속)
        applyExtraTrackers(id)
    }

    /** 동기된 트래커를 핸들에 추가 — 도달 우선 최대 20개. 전체 try-catch (T-931 교훈). */
    internal fun applyExtraTrackers(id: String) {
        try {
            if (!settings.firstBlocking().torrentTrackerSync) return
            val extra = TrackerListProvider.getCached(context)
            if (extra.isEmpty()) return
            val th = handleMap[id] ?: return
            val existing = withGate { th.trackers().map { it.url() }.toSet() }
            val probe = TrackerProbe.getProbeCached(context)
            val missing = TrackerProbe.orderByProbe(extra.filter { it !in existing }, probe).take(20)
            if (missing.isEmpty()) return
            withGate {
                missing.forEach { u ->
                    runCatching { th.addTracker(org.libtorrent4j.AnnounceEntry(u)) }
                }
            }
            DebugLogger.i(TAG, "[FEATURE] 트래커 주입 id=$id ${missing.size}개")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "트래커 주입 실패 id=$id (무시)", e)
        }
    }

    /** 트래커 도달성 백그라운드 측정 (v0.26) — 중복 실행 가드, never throw */
    @Volatile var probingTrackers = false
        private set

    fun probeTrackers(): Boolean {
        if (probingTrackers) return false
        probingTrackers = true
        scope.launch {
            try {
                val urls = TrackerListProvider.getCached(context)
                DebugLogger.i(TAG, "[FEATURE] 트래커 프로브 시작 ${urls.size}개")
                TrackerProbe.probeAndCache(context, urls)
            } finally {
                probingTrackers = false
            }
        }
        return true
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

    /** 영속된 torrent 개별 다운로드 제한을 libtorrent에 적용 (0 이하 = 무제한) */
    private fun applyPersistedLimit(id: String) {
        val th = handleMap[id] ?: return
        val bps = TorrentRepository.get(id)?.downloadLimit ?: 0L
        try {
            withGate {
                th.setDownloadLimit(if (bps <= 0) 0 else bps.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "개별 다운로드 제한 적용 실패 id=$id: ${e.message}")
        }
    }

    /** torrent 개별 다운로드 제한 변경 — 즉시 적용 + 영속 (T-1050) */
    fun setDownloadLimit(id: String, bps: Long) {
        if (TorrentRepository.get(id) == null) return
        val v = bps.coerceAtLeast(0L)
        TorrentRepository.update(id) { it.copy(downloadLimit = v) }
        persistNow()
        applyPersistedLimit(id)
        DebugLogger.i(TAG, "torrent 개별 다운로드 제한 id=$id ${SpeedLimits.labelBps(v)}")
    }

    /** 표시 순서(order) → libtorrent queue_position 전체 동기 (T-1050) */
    fun syncQueueOrder() {
        withGate {
            TorrentRepository.all().forEachIndexed { i, job ->
                val th = handleMap[job.id] ?: return@forEachIndexed
                try {
                    if (th.queuePosition() != i) th.queuePositionSet(i)
                } catch (e: Exception) {
                    DebugLogger.d(TAG, "큐 순서 동기 실패 id=${job.id}: ${e.message}")
                }
            }
        }
    }

    /** 매개변수 없는 swig settings_pack 정수 키 조회 — Kotlin 식별자에 `$` 쓸 수 없어 리플렉션 + 캐시 */
    private fun swigSetting(kind: String, name: String): Int? {
        val key = "$kind.$name"
        swigSettingCache[key]?.let { return it }
        return try {
            val cls = Class.forName("org.libtorrent4j.swig.settings_pack\$$kind")
            val v = cls.getField(name).get(null)
            (v.javaClass.getMethod("swigValue").invoke(v) as Int).also { swigSettingCache[key] = it }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "swig 세팅 조회 실패 $key: ${e.message}")
            null
        }
    }

    /** 매핑 해제 — job.infoHash 폴백으로 좀비 hashToId 방지 (T-934 S3) */
    private fun unregisterMapping(id: String) {
        handleMap.remove(id)
        TorrentCounters.forget(id)
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
    @Volatile private var latestPexEnabled: Boolean = true
    @Volatile private var latestSavePath: String = StorageGuard.dlRoot.path
    /** id → 시더 부재 대기 시작 시각(ms). 0이면 미측정 */
    private val seedWaitSince = ConcurrentHashMap<String, Long>()
    /** id → 정체(스톨) 조건 첫 충족 시각(ms) — 키가 지워지면 조건이 해소된 것 */
    private val stallSince = ConcurrentHashMap<String, Long>()
    @Volatile private var latestStallEnabled: Boolean = SettingsConstraints.DEFAULT_TORRENT_STALL_ENABLED
    @Volatile private var latestStallThresholdKbps: Int = SettingsConstraints.DEFAULT_TORRENT_STALL_THRESHOLD_KBPS
    @Volatile private var latestStallTimeoutSec: Int = SettingsConstraints.DEFAULT_TORRENT_STALL_TIMEOUT_SEC
    @Volatile private var latestMaxActive: Int = SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE
    private val swigSettingCache = ConcurrentHashMap<String, Int>()
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
                latestPexEnabled = s.torrentPexEnabled
                latestSavePath = s.torrentSavePath.ifBlank { StorageGuard.dlRoot.path }
                latestStallEnabled = s.torrentStallEnabled
                latestStallThresholdKbps = s.torrentStallThresholdKbps
                latestStallTimeoutSec = s.torrentStallTimeoutSec
                latestMaxActive = s.torrentMaxActive
                DebugLogger.d(TAG, "설정 반영 업로드=${s.torrentUploadLimit}KB/s 다운로드=${s.torrentDownloadLimit}KB/s 시퀀셜=${s.torrentSequentialDownload} 비율=${s.torrentSeedRatio} DHT=${s.torrentDhtEnabled} PEX=${s.torrentPexEnabled} 정체=${s.torrentStallEnabled}(${s.torrentStallThresholdKbps}KB/s·${s.torrentStallTimeoutSec}초)")
                applyRateLimits()
                applySequentialToAll(s.torrentSequentialDownload)
                applyDhtEnabled(s.torrentDhtEnabled)
                applyPexEnabled(s.torrentPexEnabled)
                applyStallSessionSettings(s)
            }
        }
    }

    val saveDir: File
        get() = File(context.getExternalFilesDir(null), "torrents").apply { mkdirs() }

    /** 보관함 경로 — 설정값 (기본 StorageGuard.dlRoot, T-958) */
    private val storageDir: File
        get() = File(latestSavePath).apply { mkdirs() }

    /** 완료된 토렌트 파일을 보관함으로 이동 */
    private fun moveToStorage(id: String, torrentName: String) {
        val src = File(saveDir, torrentName)
        if (!src.exists()) {
            DebugLogger.w(TAG, "보관함 이동 스킵(소스 없음) id=$id name=$torrentName")
            return
        }
        var dst = File(storageDir, torrentName)
        if (dst.exists()) {
            // 기존 보존 — 삭제 대신 고유 이름 회피 (데이터 손실 방지)
            val dot = torrentName.lastIndexOf('.')
            val base = if (dot > 0) torrentName.substring(0, dot) else torrentName
            val ext = if (dot > 0) torrentName.substring(dot) else ""
            var i = 2
            while (File(storageDir, "$base-$i$ext").exists()) i++
            dst = File(storageDir, "$base-$i$ext")
            DebugLogger.w(TAG, "보관함 이름 충돌 → 회피 경로 id=$id → ${dst.name}")
        }
        try {
            if (src.isDirectory) {
                src.renameTo(dst).also { ok ->
                    if (!ok) {
                        DebugLogger.w(TAG, "보관함 이동 실패(rename) id=$id → 복사 시도")
                        src.copyRecursively(dst, overwrite = false)
                        src.deleteRecursively()
                    }
                }
            } else {
                // 단일 파일 토렌트
                src.renameTo(dst).also { ok ->
                    if (!ok) {
                        DebugLogger.w(TAG, "보관함 이동 실패(rename) id=$id → 복사 시도")
                        src.copyTo(dst, overwrite = false)
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
                // 세션 세팅(정체 감지·활성 한도) 즉시 반영 (T-1050)
                applySettings(settings.firstBlocking())
                startStatusPolling()
                restoreTorrents()
                // 트래커 목록 백그라운드 동기 (T-971, 실패해도 번들 목록 사용)
                scope.launch {
                    if (runCatching { settings.firstBlocking().torrentTrackerSync }.getOrDefault(true)) {
                        TrackerListProvider.refresh(context)
                        probeTrackers()
                    }
                }
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
            stallSince.clear()
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
                // 세션 미기동 시 session?.download()는 no-op → FETCHING_METADATA 영구 고착 방지 (결함 #1)
                val sess = session ?: throw IllegalStateException("내장 서버 미기동 — 서버를 시작한 뒤 다시 추가하세요")
                withGate { sess.download(magnet, saveDir, flags) }
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
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .build()
        val bytes = http.newCall(req).execute().use { resp ->
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

    fun addTorrentFile(bytes: ByteArray, filename: String): TorrentJob {
        // .torrent 중복 가드 (결함 #9) — job 추가 전 infohash 검사. 손상 파일은 파싱 실패 → 기존 FAILED 흐름 진행
        runCatching { TorrentInfo(bytes) }.getOrNull()?.let { ti ->
            val h = ti.infoHash().toString()
            TorrentRepository.all().find { it.infoHash.isNotEmpty() && it.infoHash == h }?.let {
                DebugLogger.w(TAG, "중복 .torrent 감지 ($h) → 거부 (기존 id=${it.id})")
                throw DuplicateTorrentException(h)
            }
        }
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
                // 세션 미기동 가드 (결함 #1)
                val sess = session ?: throw IllegalStateException("내장 서버 미기동 — 서버를 시작한 뒤 다시 추가하세요")
                withGate { sess.download(ti, saveDir, null, null, null, flags) }
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
            // AUTO_MANAGED를 끄지 않으면 libtorrent auto-manage가 다시 살려 일시정지가 무력화된다 (T-1050)
            try { th.unsetFlags(TorrentFlags.AUTO_MANAGED) } catch (_: Exception) {}
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
        stallSince.remove(id)
        persistNow()
        DebugLogger.i(TAG, "torrent 일시정지 id=$id")
    }

    fun resume(id: String) {
        val th = handleMap[id] ?: return
        val job = TorrentRepository.get(id) ?: return
        val resumable = job.state == TorrentState.PAUSED || job.state == TorrentState.FAILED ||
            job.state == TorrentState.STALLED || job.state == TorrentState.QUEUED
        if (resumable) {
            withGate {
                try { th.setFlags(TorrentFlags.AUTO_MANAGED) } catch (_: Exception) {}
                th.resume()
            }
            stallSince.remove(id)
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
        stallSince.remove(id)
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
                    // 보관함(storageDir)은 삭제하지 않음 — 이름 충돌 회피로 타인 파일과 같은 이름일 수 있음 (결함 #4)
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
        reorderTo(id, target)
    }

    /** 표시 순서 + libtorrent 큐 순서 동시 변경 (T-1050) — 웹 reorder 라우트도 경유 */
    fun reorderTo(id: String, newOrder: Int) {
        TorrentRepository.reorder(id, newOrder)
        syncQueueOrder()
        persistNow()
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

    /** PEX on/off 반영 (결함 #6) — settings_pack.enable_pex는 세션 레벨 설정 */
    private fun applyPexEnabled(enabled: Boolean) {
        try {
            withGate {
                val session = session ?: return@withGate
                val sp = session.settings()
                swigSetting("bool_types", "enable_pex")?.let { sp.setBoolean(it, enabled) }
                session.applySettings(sp)
                DebugLogger.i(TAG, "PEX ${if (enabled) "활성화" else "비활성화"}")
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "PEX 전환 실패: ${e.message}")
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
            applyStallSessionSettings(s)
            applyPexEnabled(s.torrentPexEnabled)

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
            latestSavePath = s.torrentSavePath.ifBlank { StorageGuard.dlRoot.path }
            latestStallEnabled = s.torrentStallEnabled
            latestStallThresholdKbps = s.torrentStallThresholdKbps
            latestStallTimeoutSec = s.torrentStallTimeoutSec
            latestMaxActive = s.torrentMaxActive
            applyDhtEnabled(s.torrentDhtEnabled)
        }
    }

    /** 정체(스톨) 감지·회전에 필요한 libtorrent 세션 세팅 (T-1050)
     *  - incoming_starts_queued_torrents=false: 새 토렌트가 대기열을 추월하지 않도록
     *  - dont_count_slow_torrents=false (정체 ON): 느린 torrent도 활성 한도에 포함 → maxActive 실제 강제
     *  - inactive_down_rate / inactive_up_rate: 임계값 이하는 libtorrent가 "비활성"으로 간주
     */
    private fun applyStallSessionSettings(s: AppSettings) {
        try {
            withGate {
                val session = session ?: return@withGate
                val sp = session.settings()
                swigSetting("bool_types", "incoming_starts_queued_torrents")?.let { sp.setBoolean(it, false) }
                swigSetting("bool_types", "dont_count_slow_torrents")?.let { sp.setBoolean(it, !s.torrentStallEnabled) }
                val threshold = (s.torrentStallThresholdKbps * 1024).coerceIn(0, Int.MAX_VALUE)
                swigSetting("int_types", "inactive_down_rate")?.let { sp.setInteger(it, threshold) }
                swigSetting("int_types", "inactive_up_rate")?.let { sp.setInteger(it, threshold) }
                session.applySettings(sp)
                if (s.torrentStallEnabled) {
                    DebugLogger.i(TAG, "정체 감지 세션 세팅 임계=${s.torrentStallThresholdKbps}KB/s timeout=${s.torrentStallTimeoutSec}초 maxActive=${s.torrentMaxActive}")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "정체 세션 세팅 적용 실패", e)
        }
    }

    private fun handleAlert(alert: Alert<*>) {
        // JNI 호출 포함 — remove/status 경합 방지 위해 게이트 내에서 처리
        // 주의: alert.handle()은 콜백 생존 중에만 유효한 transient 참조 → 장기 보관 금지 (T-931).
        // 보관함 이동은 alert가 아니라 폴링의 완료 전이 시점에 일원화 (알림 유실 대비, 결함 #2).
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
                TorrentRepository.update(id) {
                    it.copy(
                        state = TorrentState.DONE,
                        progress = 1f,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                TrafficLedger.addDoneTorrent()
                DebugLogger.i(TAG, "torrent 완료 id=$id (보관함 이동은 폴링 전이 처리)")
            }
            AlertType.TORRENT_ERROR -> {
                // transient handle: 콜백 내에서 infoHash 조회만 (저장 금지)
                val th = (alert as org.libtorrent4j.alerts.TorrentErrorAlert).handle()
                val id = hashToId[th.infoHash().toString()] ?: return
                TorrentRepository.update(id) {
                    it.copy(state = TorrentState.FAILED, errorMessage = "토렌트 에러")
                }
                TrafficLedger.addFail()
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
                        val prevState = job.state
                        // 시드 비율 강제 (T-956) — SEEDING 중 비율 도달 시 자동 일시정지 (0=제한 없음)
                        val ratioLimit = latestSeedRatio
                        if (shouldPauseAtRatio(status.isSeeding, status.totalUpload(), status.totalDownload(), ratioLimit)) {
                            val ratio = status.totalUpload().toDouble() / status.totalDownload()
                            DebugLogger.i(TAG, "[FEATURE] 시드 비율 도달(${String.format("%.2f", ratio)}≥$ratioLimit) → 자동 일시정지 id=$id")
                            pause(id)
                            return@forEach
                        }
                        if (prevState == TorrentState.PAUSED || prevState == TorrentState.DONE || prevState == TorrentState.FAILED) return@forEach

                        // libtorrent 2.x에는 QUEUED 상태가 없어 status.state()만 보면 전부 DOWNLOADING으로 보인다.
                        // paused/auto-managed 플래그로 대기(QUEUED)·정체 정지(STALLED)를 가른다 (T-1050)
                        val flags = try { status.flags() } catch (_: Exception) { null }
                        val libPaused = flags?.and_(TorrentFlags.PAUSED)?.non_zero() ?: false
                        val state = if (libPaused) {
                            // 사용자 정지는 위에서 걸러졌음 → auto-manage 대기 또는 정체 정지
                            if (job.state == TorrentState.STALLED) TorrentState.STALLED else TorrentState.QUEUED
                        } else {
                            when (status.state()) {
                                TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
                                TorrentStatus.State.SEEDING -> TorrentState.SEEDING
                                TorrentStatus.State.CHECKING_FILES -> TorrentState.FETCHING_METADATA
                                TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.FETCHING_METADATA
                                TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.FETCHING_METADATA
                                TorrentStatus.State.FINISHED -> TorrentState.DONE
                                TorrentStatus.State.UNKNOWN -> job.state
                            }
                        }

                        // 완료 전이 감지 → 보관함 이동 일원화 (FINISHED alert 유실 대비, 결함 #2)
                        // 이동 전 시딩 중단 — 시딩 대상 파일 소실·오류 방지 (결함 #3)
                        val wasComplete = prevState == TorrentState.DONE || prevState == TorrentState.SEEDING
                        val completeNow = state == TorrentState.DONE || state == TorrentState.SEEDING
                        if (completeNow && !wasComplete && job.name.isNotEmpty()) {
                            try {
                                withGate { th.unsetFlags(TorrentFlags.AUTO_MANAGED); th.pause() }
                            } catch (_: Exception) {}
                            moveToStorage(id, job.name)
                            DebugLogger.i(TAG, "torrent 완료 전이 → 시딩 중단 + 보관함 이동 id=$id")
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

                        // 트래픽 통계 (v0.37) — 누적 카운터 diff (첫 관측·역행은 0)
                        val (countDown, countUp) = TorrentCounters.diff(id, status.totalDone(), status.totalUpload())
                        if (countDown > 0) TrafficLedger.addDownTorrent(countDown)
                        if (countUp > 0) TrafficLedger.addUpTorrent(countUp)
                        val dlRate = status.downloadRate().toLong().coerceAtLeast(0)
                        val ulRate = status.uploadRate().toLong().coerceAtLeast(0)
                        if (dlRate > 0 || ulRate > 0) TrafficLedger.recordSpeed(dlRate, ulRate)

                        // 정체(스톨) 감지 → 임계 시간이 지나면 일시정지 + 큐 맨뒤 회전 (T-1050)
                        // 메타데이터 미수신(DOWNLOADING_METADATA)도 포함 — 죽은 마그넷이 슬롯을 영구 점유하지 않도록
                        // (파일 검사(CHECKING_*)는 제외 — 검사 중 재귀 동작 방지)
                        val stuckMeta = status.state() == TorrentStatus.State.DOWNLOADING_METADATA
                        val running = !libPaused && status.progress() < 1f &&
                            (state == TorrentState.DOWNLOADING || (stuckMeta && state == TorrentState.FETCHING_METADATA))
                        val stalled = running &&
                            isStalledTorrent(latestStallEnabled, dlRate, latestStallThresholdKbps, status.numSeeds())
                        if (stalled) {
                            val since = stallSince[id] ?: System.currentTimeMillis().also { stallSince[id] = it }
                            if (stallTimedOut(since, System.currentTimeMillis(), latestStallTimeoutSec)) {
                                if (rotateStalled(id, dlRate, status.numSeeds())) return@forEach
                            }
                        } else {
                            stallSince.remove(id)
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
                    // 슬롯 유지 — 정체 회전/자연 완료로 빈 슬롯이 생기면 다음 torrent 즉시 기동 (T-1050)
                    maintainSlots()
                    // 피어 스냅샷 (v0.39 P2, 5분 디바운스)
                    runCatching {
                        val all = TorrentRepository.all()
                        StatsSnapshots.recordPeers(
                            all.sumOf { it.seeds.toLong() },
                            all.sumOf { it.peers.toLong() },
                        )
                    }
                } catch (_: Exception) {}
                persistDebounced()
            }
        }
    }

    /** 정체 torrent를 일시정지 + 큐 맨뒤로 보내 슬롯을 넘긴다 (T-1050).
     *  대체 torrent가 없으면 회전하지 않는다 (상태 요동 방지) — 측정 시계는 유지된다.
     *  @return 회전했으면 true */
    private fun rotateStalled(id: String, rateBps: Long, numSeeds: Int): Boolean {
        val alternative = TorrentRepository.all().any {
            it.id != id && handleMap.containsKey(it.id) &&
                (it.state == TorrentState.QUEUED || it.state == TorrentState.STALLED)
        }
        if (!alternative) {
            DebugLogger.d(TAG, "정체 감지 → 대체 torrent 없어 회전 보류 id=$id rate=${rateBps}B/s seeds=$numSeeds")
            return false
        }
        val th = handleMap[id] ?: return false
        withGate {
            try { th.queuePositionBottom() } catch (_: Exception) {}
            th.pause()
        }
        stallSince.remove(id)
        TorrentRepository.update(id) {
            it.copy(state = TorrentState.STALLED, downloadSpeed = 0L, uploadSpeed = 0L)
        }
        DebugLogger.i(
            TAG,
            "[FEATURE] 정체 torrent 회전 id=$id rate=${rateBps}B/s 임계=${latestStallThresholdKbps}KB/s seeds=$numSeeds → 큐 맨뒤",
        )
        return true
    }

    /** 슬롯이 남으면 다음 torrent를 즉시 기동한다 (T-1050).
     *  우선순위: 대기(QUEUED) → 모두 정체한 경우에만 정체(STALLED) 재기동(하트비트) */
    private fun maintainSlots() {
        val all = TorrentRepository.all()
        val active = all.count {
            it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA
        }
        if (active >= latestMaxActive) return
        val next = all.firstOrNull { it.state == TorrentState.QUEUED && handleMap.containsKey(it.id) }
            ?: (if (active == 0) all.firstOrNull { it.state == TorrentState.STALLED && handleMap.containsKey(it.id) } else null)
            ?: return
        withGate { handleMap[next.id]?.resume() }
        stallSince.remove(next.id)
        TorrentRepository.update(next.id) { it.copy(state = TorrentState.DOWNLOADING) }
        DebugLogger.i(TAG, "[FEATURE] 슬롯 승격 id=${next.id} '${next.name}' 활성=${active + 1}/${latestMaxActive}")
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
