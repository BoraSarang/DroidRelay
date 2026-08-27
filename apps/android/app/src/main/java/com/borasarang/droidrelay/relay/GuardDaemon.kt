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
 * - 열: 45°C (설정 가능 30~60)
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

    /** 현재 가드 상태 */
    @Volatile var isThrottled = false
        private set

    /** 가드 상태 변경 콜백 */
    @Volatile var onThrottleChange: ((throttled: Boolean, reason: String) -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        pollingJob = scope.launch {
            DebugLogger.i(TAG, "가드 데몬 시작 (30초 폴링)")
            while (isRunning) {
                checkGuard()
                delay(30_000) // 30초
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
        val thermal = readThermal()
        val battery = readBattery()
        val storage = readStorage()
        val s = settings.firstBlocking()

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
        )
    }

    private suspend fun checkGuard() {
        val s = settings.firstBlocking()
        if (!s.guardEnabled) {
            if (isThrottled) {
                isThrottled = false
                onThrottleChange?.invoke(false, "가드 비활성화")
                DebugLogger.i(TAG, "가드 해제 — 비활성화 상태")
            }
            return
        }

        val thermal = readThermal()
        val battery = readBattery()
        val storage = readStorage()
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
            DebugLogger.w(TAG, "가드 스로틀링 발동: $reasonStr")
        } else if (!shouldThrottle && isThrottled) {
            isThrottled = false
            onThrottleChange?.invoke(false, "정상 복귀")
            DebugLogger.i(TAG, "가드 스로틀링 해제 — 정상 복귀")
        }
    }

    /**
     * 온도 읽기 (°C).
     */
    private fun readThermal(): Int {
        return try {
            val file = File("/sys/class/thermal/thermal_zone0/temp")
            if (file.exists()) {
                (file.readText().trim().toIntOrNull() ?: 0) / 1000
            } else 0
        } catch (_: Exception) { 0 }
    }

    /**
     * 배터리 잔량 (%).
     */
    private fun readBattery(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) { -1 }
    }

    /**
     * 스토리지 사용량 (%).
     */
    private fun readStorage(): Int {
        return try {
            val dir = File("/sdcard/Download/DroidRelay")
            if (!dir.exists()) return 0
            val total = dir.totalSpace
            val free = dir.freeSpace
            if (total > 0) ((total - free) * 100 / total).toInt() else 0
        } catch (_: Exception) { 0 }
    }
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
