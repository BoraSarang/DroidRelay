package com.borasarang.droidrelay.relay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 피어 스냅샷 1건 */
data class PeerSnapshot(val t: Long = 0L, val seeds: Long = 0L, val peers: Long = 0L)

/** 저장공간 일별 스냅샷 1건 */
data class StorageSnapshot(
    val date: String = "",
    val dirSize: Long = 0L,
    val freeBytes: Long = 0L,
    val quotaMoved: Long = 0L,
)

/** 네트워크 단절/복구 이벤트 1건 */
data class NetEvent(val t: Long = 0L, val up: Boolean = true)

/** 가드 스로틀 전이 1건 */
data class ThrottleEvent(val t: Long = 0L, val on: Boolean = true, val reason: String = "")

/**
 * 통계 스냅샷 저장소 (v0.39 P2).
 * - traffic.json 비대화 방지용 별도 JSON 5종 (peers/uptime/storage/net/throttle)
 * - hand-rolled JSON, 손상 시 해당 파일만 빈 복구
 * - 피어 5분 스냅샷·그 외 이벤트/일별, 90일 prune
 */
object StatsSnapshots {
    const val PEER_FILE = "peers.json"
    const val UPTIME_FILE = "uptime.json"
    const val STORAGE_FILE = "storage.json"
    const val NET_FILE = "net.json"
    const val THROTTLE_FILE = "throttle.json"
    const val RETENTION_DAYS = 90
    private const val PEER_MIN_INTERVAL_MS = 5 * 60_000L
    private const val MAX_EVENTS = 200

    private var dir: File? = null

    var peakSeeds: Long = 0L
        private set
    var peakPeers: Long = 0L
        private set
    var lastSeeds: Long = 0L
        private set
    var lastPeers: Long = 0L
        private set
    private val peerHistory = ArrayList<PeerSnapshot>()

    var bootCount: Long = 0L
        private set
    var firstBootAt: Long = 0L
        private set
    var lastBootAt: Long = 0L
        private set

    private val storageDays = LinkedHashMap<String, StorageSnapshot>()
    private val netEvents = ArrayList<NetEvent>()
    private val throttleEvents = ArrayList<ThrottleEvent>()
    private var lastPeerAt = 0L

    fun init(context: android.content.Context) {
        configure(context.getExternalFilesDir(null) ?: return)
    }

    @Synchronized
    fun configure(root: File) {
        dir = root
        loadAll()
    }

    // ── 피어 ──
    @Synchronized
    fun recordPeers(seeds: Long, peers: Long, nowMs: Long = System.currentTimeMillis()) {
        lastSeeds = seeds.coerceAtLeast(0)
        lastPeers = peers.coerceAtLeast(0)
        if (lastSeeds > peakSeeds) peakSeeds = lastSeeds
        if (lastPeers > peakPeers) peakPeers = lastPeers
        if (nowMs - lastPeerAt < PEER_MIN_INTERVAL_MS && peerHistory.isNotEmpty()) {
            savePeers()
            return
        }
        lastPeerAt = nowMs
        peerHistory.add(PeerSnapshot(nowMs, lastSeeds, lastPeers))
        prunePeers(nowMs)
        savePeers()
    }

    @Synchronized
    fun peers(): List<PeerSnapshot> = peerHistory.toList()

    // ── 가동 ──
    @Synchronized
    fun recordBoot(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs <= 0) return
        bootCount++
        if (firstBootAt == 0L) firstBootAt = nowMs
        lastBootAt = nowMs
        saveUptime()
    }

    // ── 저장공간 ──
    @Synchronized
    fun recordStorage(dirSize: Long, freeBytes: Long, quotaMoved: Long = 0L, nowMs: Long = System.currentTimeMillis()) {
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val cur = storageDays[key]
        storageDays[key] = StorageSnapshot(
            date = key,
            dirSize = dirSize.coerceAtLeast(0),
            freeBytes = freeBytes.coerceAtLeast(0),
            quotaMoved = (cur?.quotaMoved ?: 0L) + quotaMoved.coerceAtLeast(0),
        )
        pruneStorage()
        saveStorage()
    }

    @Synchronized
    fun storage(): List<StorageSnapshot> = storageDays.values.sortedBy { it.date }

    // ── 네트워크/스로틀 이벤트 ──
    @Synchronized
    fun recordNet(up: Boolean, nowMs: Long = System.currentTimeMillis()) {
        netEvents.add(NetEvent(nowMs, up))
        while (netEvents.size > MAX_EVENTS) netEvents.removeAt(0)
        saveNet()
    }

    @Synchronized
    fun recordThrottle(on: Boolean, reason: String = "", nowMs: Long = System.currentTimeMillis()) {
        throttleEvents.add(ThrottleEvent(nowMs, on, reason.take(120)))
        while (throttleEvents.size > MAX_EVENTS) throttleEvents.removeAt(0)
        saveThrottle()
    }

    @Synchronized
    fun netLossCount(): Int = netEvents.count { !it.up }

    @Synchronized
    fun throttleCount(): Int = throttleEvents.count { it.on }

    // ── 영속 ──
    private fun f(name: String): File? = dir?.let { File(it, name) }

    private fun prunePeers(nowMs: Long) {
        val cut = nowMs - RETENTION_DAYS * 86_400_000L
        peerHistory.removeAll { it.t < cut }
    }

    private fun pruneStorage() {
        if (storageDays.size <= RETENTION_DAYS) return
        val keep = storageDays.toSortedMap().entries.toList().takeLast(RETENTION_DAYS)
        storageDays.clear()
        keep.forEach { storageDays[it.key] = it.value }
    }

    private fun savePeers() {
        val f = f(PEER_FILE) ?: return
        runCatching {
            val sb = StringBuilder("{\"peakSeeds\":$peakSeeds,\"peakPeers\":$peakPeers,\"items\":[")
            peerHistory.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append("{\"t\":${p.t},\"s\":${p.seeds},\"p\":${p.peers}}")
            }
            sb.append("]}")
            f.writeText(sb.toString())
        }
    }

    private fun saveUptime() {
        val f = f(UPTIME_FILE) ?: return
        runCatching { f.writeText("{\"bootCount\":$bootCount,\"firstBootAt\":$firstBootAt,\"lastBootAt\":$lastBootAt}") }
    }

    private fun saveStorage() {
        val f = f(STORAGE_FILE) ?: return
        runCatching {
            val sb = StringBuilder("{\"days\":[")
            storageDays.toSortedMap().values.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append("{\"date\":\"${s.date}\",\"dirSize\":${s.dirSize},\"freeBytes\":${s.freeBytes},\"quotaMoved\":${s.quotaMoved}}")
            }
            sb.append("]}")
            f.writeText(sb.toString())
        }
    }

    private fun saveNet() {
        val f = f(NET_FILE) ?: return
        runCatching {
            val sb = StringBuilder("{\"events\":[")
            netEvents.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                sb.append("{\"t\":${e.t},\"up\":${if (e.up) 1 else 0}}")
            }
            sb.append("]}")
            f.writeText(sb.toString())
        }
    }

    private fun saveThrottle() {
        val f = f(THROTTLE_FILE) ?: return
        runCatching {
            val sb = StringBuilder("{\"events\":[")
            throttleEvents.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                val r = e.reason.replace("\"", "")
                sb.append("{\"t\":${e.t},\"on\":${if (e.on) 1 else 0},\"reason\":\"$r\"}")
            }
            sb.append("]}")
            f.writeText(sb.toString())
        }
    }

    private fun loadAll() {
        peerHistory.clear()
        storageDays.clear()
        netEvents.clear()
        throttleEvents.clear()
        peakSeeds = 0L
        peakPeers = 0L
        bootCount = 0L
        firstBootAt = 0L
        lastBootAt = 0L
        lastPeerAt = 0L
        runCatching {
            val t = f(PEER_FILE)?.takeIf { it.exists() }?.readText() ?: ""
            Regex(""""peakSeeds":(\d+)""").find(t)?.let { peakSeeds = it.groupValues[1].toLong() }
            Regex(""""peakPeers":(\d+)""").find(t)?.let { peakPeers = it.groupValues[1].toLong() }
            Regex("""\{"t":(\d+),"s":(\d+),"p":(\d+)\}""").findAll(t).forEach {
                peerHistory.add(PeerSnapshot(it.groupValues[1].toLong(), it.groupValues[2].toLong(), it.groupValues[3].toLong()))
            }
        }
        runCatching {
            val t = f(UPTIME_FILE)?.takeIf { it.exists() }?.readText() ?: ""
            Regex(""""bootCount":(\d+)""").find(t)?.let { bootCount = it.groupValues[1].toLong() }
            Regex(""""firstBootAt":(\d+)""").find(t)?.let { firstBootAt = it.groupValues[1].toLong() }
            Regex(""""lastBootAt":(\d+)""").find(t)?.let { lastBootAt = it.groupValues[1].toLong() }
        }
        runCatching {
            val t = f(STORAGE_FILE)?.takeIf { it.exists() }?.readText() ?: ""
            Regex("""\{"date":"(\d{4}-\d{2}-\d{2})","dirSize":(\d+),"freeBytes":(\d+),"quotaMoved":(\d+)\}""").findAll(t).forEach {
                val s = StorageSnapshot(it.groupValues[1], it.groupValues[2].toLong(), it.groupValues[3].toLong(), it.groupValues[4].toLong())
                storageDays[s.date] = s
            }
        }
        runCatching {
            val t = f(NET_FILE)?.takeIf { it.exists() }?.readText() ?: ""
            Regex("""\{"t":(\d+),"up":([01])\}""").findAll(t).forEach {
                netEvents.add(NetEvent(it.groupValues[1].toLong(), it.groupValues[2] == "1"))
            }
        }
        runCatching {
            val t = f(THROTTLE_FILE)?.takeIf { it.exists() }?.readText() ?: ""
            Regex("""\{"t":(\d+),"on":([01]),"reason":"([^"]*)"\}""").findAll(t).forEach {
                throttleEvents.add(ThrottleEvent(it.groupValues[1].toLong(), it.groupValues[2] == "1", it.groupValues[3]))
            }
        }
    }

    /** 테스트 격리용 */
    @Synchronized
    fun resetForTest() {
        dir = null
        peakSeeds = 0L
        peakPeers = 0L
        lastSeeds = 0L
        lastPeers = 0L
        peerHistory.clear()
        bootCount = 0L
        firstBootAt = 0L
        lastBootAt = 0L
        storageDays.clear()
        netEvents.clear()
        throttleEvents.clear()
        lastPeerAt = 0L
    }
}
