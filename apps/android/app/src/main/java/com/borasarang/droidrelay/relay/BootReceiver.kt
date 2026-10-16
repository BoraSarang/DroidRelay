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
        // **종료는 경로가 몇 개든 정확히 한 번만** — `SingleFinish` 가 보장한다 (T-1088).
        // 종료 지점을 하나 더 추가해도(예: 타임아웃·코루틴 취소) 중복 호출이 되지 않는다.
        val once = SingleFinish { runCatching { pendingResult.finish() } }
        // goAsync() 의 기본 허용 시간은 10초 — finish() 가 호출되지 않으면
        // 시스템이 "Timeout of broadcast BroadcastReceiver" 로 ANR 을 낸다.
        // 첫Blocking 이 DataStore 디스크 읽기라 지연될 수 있으므로 시간 상한을 둔다.
        CoroutineScope(Dispatchers.IO).launch {
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
                // **여기서 `finish()` 는 정확히 한 번만 부른다** (T-1088).
                //
                // ## 왜 `invokeOnCompletion` 백업을 뺐나
                //
                // `goAsync()` 의 `PendingResult` 는 **종료 한 번만** 가능하다.
                // 두 번째 호출은 `IllegalStateException: Broadcast already finished` 를 던져
                // `queued-work-looper` 스레드에서 **프로세스를 죽인다** (실측 2026-09-30).
                //
                // 원래는 여기에 `job.invokeOnCompletion { finish() }` 를 "백업"으로 붙여 있었다.
                // 하지만 **`invokeOnCompletion` 은 `finally` 이후에 발화**하므로
                // 정상 경로에서 **두 번 다 실행**된다 — 백업이 아니라 **중복 호출**이었다.
                //
                // `runCatching` 으로 감싸도 소용없다. 예외는 감싸인 블록 밖(다른 스레드)에서 나므로
                // **중복 호출 자체를 없애야 한다.**
                //
                // 주석의 "코루틴이 영영 끝나지 않아도 finish() 는 호출" 의도는
                // `withTimeout(8초)` + `finally` 로 이미 충족된다.
                runCatching { once.finish() }
            }
        }
    }
}
