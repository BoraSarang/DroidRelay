package com.borasarang.droidrelay.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 디바이스 재부팅 시 서버를 자동으로 다시 띄운다. (이슈 1 — 24시간 연속 동작)
 * autoStart 설정이 꺼져 있으면 시작하지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = runCatching { SettingsRepository.get(context).firstBlocking() }.getOrNull()
        if (settings?.autoStart == true) {
            DebugLogger.i("Boot", "부팅 완료 감지 — RelayService 자동 시작")
            RelayService.start(context)
        } else {
            DebugLogger.i("Boot", "부팅 완료 감지 — autoStart 꺼짐, 시작 안 함")
        }
    }
}
