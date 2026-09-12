package com.borasarang.droidrelay.relay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 속도 스케줄 매니저 (v0.24 Phase C).
 *
 * 60초 틱으로 현재 창을 판정해 진입/이탈 시에만 전역 제한을 적용한다.
 * 우선순위: 가드 일시정지 > 스케줄 > 수동 (일시정지된 잡은 속도와 무관하므로 충돌 없음).
 * 가드 스로틀 중에는 적용을 보류하고, 해제 후 다음 틱에 재개한다.
 */
class SpeedScheduleManager(
    private val context: Context,
    private val isThrottled: () -> Boolean = { false },
) {
    private val TAG = "SpeedSched"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var lastAppliedId: String? = null

    fun start() {
        if (running) return
        running = true
        scope.launch {
            DebugLogger.i(TAG, "속도 스케줄 시작 (60초 틱)")
            while (running) {
                tick()
                delay(60_000)
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        DebugLogger.i(TAG, "속도 스케줄 중지")
    }

    /** 수동 트리거 (설정 변경 직후) */
    fun checkNow() {
        scope.launch { tick() }
    }

    internal suspend fun tick() {
        try {
            val repo = SettingsRepository.get(context)
            val s = repo.settings.first()
            if (s.speedSchedule.none { it.enabled }) {
                if (lastAppliedId != null) restoreManual(s)
                return
            }
            val (day, min) = SpeedSchedule.nowDayMin()
            val active = SpeedSchedule.decide(s.speedSchedule, day, min)
            if (active == null) {
                if (lastAppliedId != null) restoreManual(s)
                return
            }
            if (active.id == lastAppliedId) return
            // 가드 스로틀 중이면 보류 — 일시정지가 우선, 해제 후 다음 틱에 적용
            if (runCatching { isThrottled() }.getOrDefault(false)) {
                DebugLogger.d(TAG, "가드 스로틀 중 — 스케줄 적용 보류 id=${active.id}")
                return
            }
            RelayApp.applySpeedLimit(active.downKbps * 1024, active.upKbps * 1024)
            lastAppliedId = active.id
            DebugLogger.i(TAG, "[FEATURE] 속도스케줄 적용 id=${active.id} DL=${active.downKbps}KB/s UL=${active.upKbps}KB/s")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "스케줄 틱 실패 (다음 틱에 재시도)", e)
        }
    }

    private suspend fun restoreManual(s: AppSettings) {
        RelayApp.applySpeedLimit(s.maxDownloadBps, s.maxUploadBps)
        lastAppliedId = null
        DebugLogger.i(TAG, "[FEATURE] 속도스케줄 종료 — 수동값 복원")
    }
}
