package com.borasarang.droidrelay.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.borasarang.droidrelay.relay.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * T-1095 — 연동 계약 v1 discovery 프로브 수신기.
 *
 * dumpsys는 application meta-data를 출력하지 않아 명찰을 읽을 수 없다.
 * 대신 명시적 브로드캐스트로 능력을 질의한다 (앱 꺼져 있어도 프로세스 기동 후 응답,
 * UI 없음, 상시 연결 없음):
 *
 *   adb shell am broadcast -a com.borasarang.droidrelay.PLUGIN_PROBE \
 *     -n com.borasarang.droidrelay/.plugin.PluginProbeReceiver
 *
 * 응답은 logcat(TAG DroidRelay)으로만 전달:
 *   [PLUGIN] version=1 actions=... logTag=DroidRelay allowed=<true|false>
 *
 * `연동 허용` OFF여도 응답은 한다 (allowed=false로 꺼짐 상태 전달).
 * 계약 로그는 릴리즈 가시성이 필요해 [PluginLog] 직접 출력 (DebugLogger 경유 금지).
 */
class PluginProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PluginContract.ACTION_PROBE) return
        // T-1088 전례: PendingResult.finish()는 finally 1곳에서만 1회.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allowed = runCatching {
                    SettingsRepository.get(context.applicationContext).firstBlocking().pluginAllowed
                }.getOrDefault(true)
                PluginLog.plugin(PluginContract.buildProbeLine(allowed))
            } finally {
                pending.finish()
            }
        }
    }
}
