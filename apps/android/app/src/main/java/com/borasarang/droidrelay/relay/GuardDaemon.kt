package com.borasarang.droidrelay.relay

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 가드 데몬 — 서버 보호 모니터.
 * 열/배터리/스토리지 임계치 초과 시 자동 스로틀링.
 *
 * 임계치:
 * - 열: 50°C (설정 가능 50~70)
 * - 배터리: 20% (설정 가능 5~50)
 * - 스토리지: 90% (설정 가능 50~99)
 */
class GuardDaemon(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val TAG = "Guard"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isRunning = false
    @Volatile private var pollingJob: kotlinx.coroutines.Job? = null
    @Volatile private var cachedSettings: AppSettings? = null
    private var cachedAt = 0L
    private val cacheTtlMs = 5 * 60 * 1000L
    // 센서 상태 캐시 — /api/guard/status 폴링마다 thermal/Binder/stat 반복 제거 (15s TTL)
    @Volatile private var cachedStatus: GuardStatus? = null
    @Volatile private var cachedStatusAt = 0L
    private val statusTtlMs = 15_000L

    /** 현재 가드 상태 */
    @Volatile var isThrottled = false
        private set

    /** 가드 상태 변경 콜백 */
    @Volatile var onThrottleChange: ((throttled: Boolean, reason: String) -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        pollingJob = scope.launch {
            DebugLogger.i(TAG, "가드 데몬 시작 (120초 폴링)")
            while (isRunning) {
                checkGuard()
                delay(120_000) // 2분
            }
        }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        DebugLogger.i(TAG, "가드 데몬 중지")
    }

    /**
     * 현재 상태 확인.
     */
    fun checkNow() {
        scope.launch { checkGuard() }
    }

    /**
     * 현재 센서 값 조회.
     */
    fun getStatus(): GuardStatus {
        val now = System.currentTimeMillis()
        cachedStatus?.let { if (now - cachedStatusAt < statusTtlMs) return it }
        val (thermal, battery, storage) = GuardSensorCache.read(context, now)
        val s = settingsNow()

        val throttled = s.guardEnabled && (
            (s.guardThermalLimit > 0 && thermal > s.guardThermalLimit) ||
            (s.guardBatteryLimit > 0 && battery in 0..s.guardBatteryLimit) ||
            (s.guardStorageLimit > 0 && storage > s.guardStorageLimit)
        )

        val reason = buildList {
            if (s.guardEnabled) {
                if (thermal > s.guardThermalLimit) add("열 ${thermal}°C > ${s.guardThermalLimit}°C")
                if (battery in 0..s.guardBatteryLimit) add("배터리 ${battery}% <= ${s.guardBatteryLimit}%")
                if (storage > s.guardStorageLimit) add("스토리지 ${storage}% > ${s.guardStorageLimit}%")
            }
        }.joinToString(", ").ifEmpty { "정상" }

        return GuardStatus(
            thermal = thermal,
            thermalLimit = s.guardThermalLimit,
            batteryLevel = battery,
            batteryLimit = s.guardBatteryLimit,
            storageUsed = storage,
            storageLimit = s.guardStorageLimit,
            throttled = throttled,
            guardEnabled = s.guardEnabled,
            reason = reason,
        ).also {
            cachedStatus = it
            cachedStatusAt = now
        }
    }

    private suspend fun checkGuard() {
        val s = settingsNow()
        if (!s.guardEnabled) {
            if (isThrottled) {
                isThrottled = false
                onThrottleChange?.invoke(false, "가드 비활성화")
                DebugLogger.i(TAG, "가드 해제 — 비활성화 상태")
            }
            return
        }

        val (thermal, battery, storage) = GuardSensorCache.read(context)
        DebugLogger.d(TAG, "센서 체크 thermal=${thermal}°C battery=${battery}% storage=${storage}% (임계: ${s.guardThermalLimit}/${s.guardBatteryLimit}/${s.guardStorageLimit}%)")

        val reasons = mutableListOf<String>()
        if (s.guardThermalLimit > 0 && thermal > s.guardThermalLimit) reasons.add("열 ${thermal}°C")
        if (s.guardBatteryLimit > 0 && battery in 0..s.guardBatteryLimit) reasons.add("배터리 ${battery}%")
        if (s.guardStorageLimit > 0 && storage > s.guardStorageLimit) reasons.add("스토리지 ${storage}%")

        val shouldThrottle = reasons.isNotEmpty()

        if (shouldThrottle && !isThrottled) {
            isThrottled = true
            val reasonStr = reasons.joinToString(", ")
            onThrottleChange?.invoke(true, reasonStr)
            runCatching { StatsSnapshots.recordThrottle(true, reasonStr) }
            DebugLogger.w(TAG, "가드 스로틀링 발동: $reasonStr")
        } else if (!shouldThrottle && isThrottled) {
            isThrottled = false
            onThrottleChange?.invoke(false, "정상 복귀")
            runCatching { StatsSnapshots.recordThrottle(false, "정상 복귀") }
            DebugLogger.i(TAG, "가드 스로틀링 해제 — 정상 복귀")
        }
    }

    /**
     * 설정 조회 — 5분간 캐시, 주기 폴링의 DataStore I/O 절감 (T-848)
     */
    private fun settingsNow(): AppSettings {
        val now = System.currentTimeMillis()
        cachedSettings?.let {
            if (now - cachedAt < cacheTtlMs) return it
        }
        return settings.firstBlocking().also {
            cachedSettings = it
            cachedAt = now
        }
    }

    // 온도·배터리·스토리지 실측은 GuardSensorCache 가 15초 TTL 로 공유한다.
    // (라우트와 데몬이 각각 읽으면 폴링 1회당 sysfs+binder+statfs 가 중복된다)
}

data class GuardStatus(
    val thermal: Int,
    val thermalLimit: Int,
    val batteryLevel: Int,
    val batteryLimit: Int,
    val storageUsed: Int,
    val storageLimit: Int,
    val throttled: Boolean,
    val guardEnabled: Boolean,
    val reason: String,
)

/** 실측 센서 3종 (임계치 미포함) */
data class GuardSensors(val thermal: Int, val batteryLevel: Int, val storageUsed: Int)

/**
 * 센서 읽기 공유 캐시.
 * /api/guard/status 가 대시보드 폴링마다 호출되므로 sysfs read + BatteryManager binder IPC
 * + statfs64 2회를 매번 하면 분당 60회씩 시스템콜이 튄다. 데몬과 라우트가 같은 캐시를 쓴다.
 */
object GuardSensorCache {
    private const val TTL_MS = 15_000L

    @Volatile private var cached: GuardSensors? = null
    @Volatile private var cachedAt = 0L

    fun read(context: Context, now: Long = System.currentTimeMillis()): GuardSensors {
        cached?.let { if (now - cachedAt < TTL_MS) return it }
        return runCatching {
            val thermal = try {
                val f = File("/sys/class/thermal/thermal_zone0/temp")
                if (f.exists()) (f.readText().trim().toIntOrNull() ?: 0) / 1000 else 0
            } catch (_: Exception) { 0 }
            val battery = try {
                val bm = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (_: Exception) { -1 }
            val storage = try {
                val dir = StorageGuard.dlRoot
                if (!dir.exists()) {
                    0
                } else {
                    val total = dir.totalSpace
                    val free = dir.freeSpace
                    if (total > 0) ((total - free) * 100 / total).toInt() else 0
                }
            } catch (_: Exception) { 0 }
            GuardSensors(thermal, battery, storage)
        }.getOrDefault(GuardSensors(0, -1, 0)).also {
            cached = it
            cachedAt = now
        }
    }
}
