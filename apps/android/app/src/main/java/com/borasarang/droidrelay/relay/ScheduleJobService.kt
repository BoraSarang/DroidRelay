package com.borasarang.droidrelay.relay

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * WorkManager/JobScheduler에 의해 호출되는 서비스.
 * 조건 충족 시 대기 중인 다운로드 재개.
 */
class ScheduleJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: -1
        DebugLogger.i("ScheduleJob", "스케줄 작업 시작 jobId=$jobId")

        val settingsRepo = SettingsRepository.get(applicationContext)
        val scheduler = SchedulerManager(applicationContext)

        scope.launch {
            val settings = settingsRepo.settings.first()
            val constraints = scheduler.checkConstraints(settings)
            DebugLogger.d("ScheduleJob", "제약 조건 결과=$constraints wifi=${settings.scheduleWifiOnly} charging=${settings.scheduleChargingOnly} batteryMin=${settings.scheduleBatteryMin}")
            if (constraints) {
                DebugLogger.i("ScheduleJob", "조건 충족 — 대기 중인 다운로드 재개")
                // 대기 중인 다운로드 재개
                RelayApp.get(applicationContext).retryFailed()
            } else {
                DebugLogger.i("ScheduleJob", "조건 미충족 — 건너뜀")
            }
            jobFinished(params, false)
        }

        return true // 비동기 완료
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        DebugLogger.w("ScheduleJob", "스케줄 작업 중단")
        scope.cancel()
        return true // 재시도
    }
}
