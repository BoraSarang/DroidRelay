package com.borasarang.droidrelay.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 디바이스 재부팅 시 서버를 자동으로 다시 띄운다. (이슈 1 — 24시간 연속 동작)
 * bootAutoStart 설정이 꺼져 있으면 시작하지 않는다. (v0.36 분리)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        // goAsync() 의 기본 허용 시간은 10초 — finish() 가 호출되지 않으면
        // 시스템이 "Timeout of broadcast BroadcastReceiver" 로 ANR 을 낸다.
        // 첫Blocking 이 DataStore 디스크 읽기라 지연될 수 있으므로 시간 상한을 둔다.
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val settings = runCatching { SettingsRepository.get(context).firstBlocking() }.getOrNull()
                    if (settings?.bootAutoStart == true) {
                        DebugLogger.i("Boot", "부팅 완료 감지 — RelayService 자동 시작")
                        RelayService.start(context)
                    } else {
                        DebugLogger.i("Boot", "부팅 완료 감지 — 부팅 자동시작 꺼짐, 시작 안 함")
                    }
                }
            } catch (e: Throwable) {
                DebugLogger.e("Boot", "부팅 처리 실패(무시)", e)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
        // 코루틴이 어떤 이유로든 영영 끝나지 않아도 finish() 는 반드시 호출되도록 백stop 예약
        job.invokeOnCompletion { runCatching { pendingResult.finish() } }
    }
}
