package com.borasarang.droidrelay.relay

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 스케줄/조건부 다운로드 매니저 (Phase 3).
// 크론 표현식 + Wi-Fi/충전/배터리 제약으로 자동 다운로드 실행.
//
// 크론 예시:
// 0 2 * * * = 매일 새벽 2시
// step/15 * * * * = 15분 간격
// 0 0 * * 1-5 = 월~금 자정
class SchedulerManager(private val context: Context) {

    private val TAG = "Scheduler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    private var settingsRepo: SettingsRepository? = null

    fun start(settingsRepo: SettingsRepository) {
        if (running) return
        this.settingsRepo = settingsRepo
        running = true
        DebugLogger.i(TAG, "스케줄러 시작")

        scope.launch {
            settingsRepo.settings.collect { s ->
                if (s.scheduleEnabled && s.scheduleCron.isNotBlank()) {
                    scheduleNext(s)
                } else {
                    cancelJob()
                }
            }
        }
    }

    fun stop() {
        running = false
        cancelJob()
        scope.cancel()
        DebugLogger.i(TAG, "스케줄러 중지")
    }

    private fun cancelJob() {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        js?.cancel(JOB_ID)
    }

    private fun scheduleNext(settings: AppSettings) {
        val cron = settings.scheduleCron
        if (!CronParser.isValid(cron)) {
            DebugLogger.w(TAG, "잘못된 크론 표현식: $cron")
            return
        }

        val delayMs = CronParser.msUntilNextMatch(cron)
        if (delayMs < 0) {
            DebugLogger.w(TAG, "다음 매칭 시각 없음")
            return
        }

        DebugLogger.i(TAG, "다음 스케줄 ${delayMs / 1000}초 후 (크론: $cron)")

        // 제약 조건 빌더
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, ScheduleJobService::class.java))
            .setOverrideDeadline(delayMs + 30_000) // 크론 +30초 버퍼
            .setMinimumLatency(delayMs)

        if (settings.scheduleWifiOnly) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            DebugLogger.d(TAG, "제약: Wi-Fi 전용")
        }
        if (settings.scheduleChargingOnly) {
            builder.setRequiresCharging(true)
            DebugLogger.d(TAG, "제약: 충전 중만")
        }
        if (settings.scheduleBatteryMin > 0) {
            builder.setRequiresBatteryNotLow(true)
            DebugLogger.d(TAG, "제약: 배터리 ${settings.scheduleBatteryMin}% 이상")
        }

        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        val result = js?.schedule(builder.build()) ?: -1
        DebugLogger.i(TAG, "JobScheduler 등록 result=$result (0=실패)")
    }

    /**
     * 조건 체크 — Wi-Fi, 충전, 배터리.
     */
    fun checkConstraints(settings: AppSettings): Boolean {
        val wifiOk = if (settings.scheduleWifiOnly) isWifiConnected() else true
        val chargingOk = if (settings.scheduleChargingOnly) isCharging() else true
        val batteryLevel = getBatteryLevel()
        val batteryOk = if (settings.scheduleBatteryMin > 0) batteryLevel >= settings.scheduleBatteryMin else true
        DebugLogger.d(TAG, "제약 체크 wifi=$wifiOk charging=$chargingOk battery=${batteryLevel}% ok=$batteryOk")
        if (!wifiOk) {
            DebugLogger.d(TAG, "Wi-Fi 연결 안됨 — 스케줄 건너뜀")
            return false
        }
        if (!chargingOk) {
            DebugLogger.d(TAG, "충전 중 아님 — 스케줄 건너뜀")
            return false
        }
        if (!batteryOk) {
            DebugLogger.d(TAG, "배터리 부족 (${batteryLevel}%) — 스케줄 건너뜀")
            return false
        }
        return true
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    companion object {
        private const val JOB_ID = 9901
    }
}
