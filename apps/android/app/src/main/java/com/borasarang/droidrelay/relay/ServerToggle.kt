package com.borasarang.droidrelay.relay

import android.content.Context

/** 위젯·퀵타일 공용 서버 토글 (T-953) */
object ServerToggle {
    const val ACTION_TOGGLE = "com.borasarang.droidrelay.SERVER_TOGGLE"

    fun isRunning(ctx: Context): Boolean =
        runCatching { SettingsRepository.get(ctx).serverState.value.running }.getOrDefault(false)

    /** 토글 후 실행 상태 반환 */
    fun toggle(ctx: Context): Boolean {
        return if (isRunning(ctx)) {
            RelayService.stop(ctx)
            DebugLogger.i("Widget", "서버 정지 (위젯/타일)")
            false
        } else {
            RelayService.start(ctx)
            DebugLogger.i("Widget", "[FEATURE] 서버 시작 (위젯/타일)")
            true
        }
    }
}
