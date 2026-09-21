package com.borasarang.droidrelay.relay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 트래픽 통계 일별 원장 (v0.37).
 *
 * - 일자별 down(3종) + up(2종) 바이트 누적, 합산+개별 breakdown 보관
 * - 영속: traffic.json (hand-rolled JSON, org.json 미사용 — 로컬 unit 테스트 가능)
 * - 손상 시 .bak 보존 후 빈 복구 (PersistenceGuard 규칙)
 * - 400일 초과분은 로드 시 제거
 */
data class TrafficDay(
    val date: String,
    val downHttp: Long = 0L,
    val downVideo: Long = 0L,
    val downTorrent: Long = 0L,
    val upServe: Long = 0L,
    val upTorrent: Long = 0L,
) {
    fun downTotal(): Long = downHttp + downVideo + downTorrent
    fun upTotal(): Long = upServe + upTorrent
}

data class TrafficBucket(
    val downHttp: Long = 0L,
    val downVideo: Long = 0L,
    val downTorrent: Long = 0L,
    val upServe: Long = 0L,
    val upTorrent: Long = 0L,
) {
    fun downTotal(): Long = downHttp + downVideo + downTorrent
    fun upTotal(): Long = upServe + upTorrent
}

data class TrafficSummary(
    val today: TrafficBucket = TrafficBucket(),
    val month: TrafficBucket = TrafficBucket(),
    val total: TrafficBucket = TrafficBucket(),
)

object TrafficLedger {
    const val FILE_NAME = "traffic.json"
    const val RETENTION_DAYS = 400
    private const val SAVE_DEBOUNCE_MS = 10_000L

    private var dir: File? = null
    private val days = LinkedHashMap<String, TrafficDay>()
    private var lastSaveAt = 0L

    /** 프로덕션 진입점 — RelayService.onCreate에서 1회 */
    fun init(context: android.content.Context) {
        configure(File(context.getExternalFilesDir(null), FILE_NAME))
    }

    /** 테스트용 주입 (TemporaryFolder 등) */
    @Synchronized
    fun configure(file: File) {
        dir = file.parentFile
        loadFrom(file)
    }

    /** 일자 키 (기기 기본 TZ, yyyy-MM-dd) — 순수함수 */
    fun dayKey(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))

    /** 월 prefix (yyyy-MM) — 순수함수 */
    fun monthKey(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timeMs))

    @Synchronized
    fun addDownHttp(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(nowMs) { it.copy(downHttp = it.downHttp + bytes) }

    @Synchronized
    fun addDownVideo(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(nowMs) { it.copy(downVideo = it.downVideo + bytes) }

    @Synchronized
    fun addDownTorrent(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(nowMs) { it.copy(downTorrent = it.downTorrent + bytes) }

    @Synchronized
    fun addUpServe(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(nowMs) { it.copy(upServe = it.upServe + bytes) }

    @Synchronized
    fun addUpTorrent(bytes: Long, nowMs: Long = System.currentTimeMillis()) = add(nowMs) { it.copy(upTorrent = it.upTorrent + bytes) }

    private fun add(nowMs: Long, transform: (TrafficDay) -> TrafficDay) {
        if (nowMs <= 0) return
        val key = dayKey(nowMs)
        val cur = days[key] ?: TrafficDay(date = key)
        // 음수 가산 방어 (diff 역행 등)
        val next = transform(cur).let {
            it.copy(
                downHttp = it.downHttp.coerceAtLeast(0),
                downVideo = it.downVideo.coerceAtLeast(0),
                downTorrent = it.downTorrent.coerceAtLeast(0),
                upServe = it.upServe.coerceAtLeast(0),
                upTorrent = it.upTorrent.coerceAtLeast(0),
            )
        }
        days[key] = next
        maybeSave()
    }

    @Synchronized
    fun summary(nowMs: Long = System.currentTimeMillis()): TrafficSummary {
        val today = dayKey(nowMs)
        val month = monthKey(nowMs)
        var t = TrafficBucket()
        var m = TrafficBucket()
        var all = TrafficBucket()
        days.values.forEach { d ->
            val b = TrafficBucket(d.downHttp, d.downVideo, d.downTorrent, d.upServe, d.upTorrent)
            all = all + b
            if (d.date == today) t = t + b
            if (d.date.startsWith(month)) m = m + b
        }
        return TrafficSummary(today = t, month = m, total = all)
    }

    @Synchronized
    fun daily(lastN: Int, nowMs: Long = System.currentTimeMillis()): List<TrafficDay> {
        if (lastN <= 0) return emptyList()
        val keys = (0 until lastN).map { dayKey(nowMs - it * 86_400_000L) }.reversed()
        return keys.map { days[it] ?: TrafficDay(date = it) }
    }

    /** 보관 상한 초과분 제거 — 순수 로직 (테스트용) */
    fun prune(all: LinkedHashMap<String, TrafficDay>, retention: Int = RETENTION_DAYS): LinkedHashMap<String, TrafficDay> {
        if (all.size <= retention) return all
        return LinkedHashMap(all.toSortedMap().entries.toList().takeLast(retention).associate { it.key to it.value })
    }

    @Synchronized
    fun flush() {
        save()
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_DEBOUNCE_MS) return
        lastSaveAt = now
        save()
    }

    private fun save() {
        val f = file() ?: return
        try {
            f.parentFile?.mkdirs()
            val sb = StringBuilder("{\"days\":[")
            days.toSortedMap().values.forEachIndexed { i, d ->
                if (i > 0) sb.append(',')
                sb.append("{\"date\":\"${d.date}\",\"downHttp\":${d.downHttp},\"downVideo\":${d.downVideo},\"downTorrent\":${d.downTorrent},\"upServe\":${d.upServe},\"upTorrent\":${d.upTorrent}}")
            }
            sb.append("]}")
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(sb.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f) || run { tmp.copyTo(f, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            DebugLogger.e("Traffic", "원장 저장 실패(무시 가능)", e)
        }
    }

    private fun loadFrom(f: File) {
        days.clear()
        if (!f.exists()) return
        runCatching {
            val text = f.readText()
            val rec = Regex("""\{"date":"(\d{4}-\d{2}-\d{2})","downHttp":(\d+),"downVideo":(\d+),"downTorrent":(\d+),"upServe":(\d+),"upTorrent":(\d+)\}""")
            rec.findAll(text).forEach { m ->
                val d = TrafficDay(
                    date = m.groupValues[1],
                    downHttp = m.groupValues[2].toLong(),
                    downVideo = m.groupValues[3].toLong(),
                    downTorrent = m.groupValues[4].toLong(),
                    upServe = m.groupValues[5].toLong(),
                    upTorrent = m.groupValues[6].toLong(),
                )
                days[d.date] = d
            }
            if (days.isEmpty() && text.isNotBlank() && !text.contains("\"days\":[]")) {
                throw IllegalStateException("원장 파싱 0건 (손상 의심)")
            }
            val pruned = prune(days)
            if (pruned.size != days.size) {
                days.clear()
                days.putAll(pruned)
                save()
            }
        }.onFailure {
            PersistenceGuard.backupCorrupt(f)
            days.clear()
            DebugLogger.w("Traffic", "원장 손상 → 백업 후 빈 복구")
        }
    }

    /** 테스트 격리용 */
    @Synchronized
    fun resetForTest() {
        days.clear()
        dir = null
        lastSaveAt = 0L
    }

    private operator fun TrafficBucket.plus(o: TrafficBucket) = TrafficBucket(
        downHttp + o.downHttp, downVideo + o.downVideo, downTorrent + o.downTorrent,
        upServe + o.upServe, upTorrent + o.upTorrent,
    )
}

/**
 * 토렌트 누적 카운터 diff (v0.37).
 * - 첫 관측은 베이스라인만 저장하고 0 반환 (재시작 안전)
 * - 감소(세션 리셋) 시 베이스라인 리셋 후 0 반환
 */
object TorrentCounters {
    private val last = HashMap<String, Pair<Long, Long>>()

    @Synchronized
    fun diff(id: String, totalDone: Long, totalUpload: Long): Pair<Long, Long> {
        val prev = last[id]
        if (prev == null) {
            last[id] = totalDone.coerceAtLeast(0) to totalUpload.coerceAtLeast(0)
            return 0L to 0L
        }
        val down = (totalDone - prev.first).coerceAtLeast(0)
        val up = (totalUpload - prev.second).coerceAtLeast(0)
        // 역행이면 새 세션으로 보고 베이스라인만 갱신
        last[id] = totalDone.coerceAtLeast(0) to totalUpload.coerceAtLeast(0)
        return down to up
    }

    @Synchronized
    fun forget(id: String) {
        last.remove(id)
    }

    @Synchronized
    fun resetForTest() {
        last.clear()
    }
}
