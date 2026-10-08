package com.borasarang.droidrelay.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * T-1096 — 플러그인 액션 수신기 (SDK §4.1 권장 경로).
 *
 * ```bash
 * adb -s <serial> shell am broadcast -a com.borasarang.droidrelay.PLUGIN_ACTION \
 *   --es cmd <action_id> [--es arg "<값>"]
 * ```
 *
 * Receiver가 직접 실행하므로 Activity 전면 전환이 없다 (SDK §4.3).
 * 결과 대기 없음 — 결과는 [REMOTE] 로그로만 전달된다.
 */
class PluginActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PluginContract.ACTION_INVOKE) return
        // T-1088 전례: PendingResult.finish()는 finally 1곳에서만 1회.
        val pending = goAsync()
        val cmd = intent.getStringExtra(PluginContract.EXTRA_CMD)
        val arg = intent.getStringExtra(PluginContract.EXTRA_ARG)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PluginActions.dispatch(context.applicationContext, cmd, arg)
            } finally {
                pending.finish()
            }
        }
    }
}
