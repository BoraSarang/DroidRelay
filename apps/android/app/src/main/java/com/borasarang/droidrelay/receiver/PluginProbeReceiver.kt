package com.borasarang.droidrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.borasarang.droidrelay.plugin.PluginContract
import com.borasarang.droidrelay.plugin.PluginLog
import com.borasarang.droidrelay.relay.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * T-1096 — 연동 계약 v2 discovery 프로브 수신기 (SDK §2.2).
 *
 * 소비자는 명시적 브로드캐스트로 질의한다 (앱 꺼져 있어도 프로세스 기동 후 응답,
 * UI 없음, 상시 연결 없음):
 *
 *   adb shell am broadcast -a com.borasarang.droidrelay.PLUGIN_PROBE \
 *     -n com.borasarang.droidrelay/.receiver.PluginProbeReceiver
 *
 * 응답은 logcat(TAG DroidRelay)으로만, 한 사건 한 줄로 전달:
 *   [PLUGIN] version=2 actions=... logTag=DroidRelay allowed=<true|false> appVersion=<x.y.z>
 *
 * `연동 허용` OFF여도 응답은 한다 (allowed=false로 꺼짐 상태 전달).
 */
class PluginProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PluginContract.ACTION_PROBE) return
        // T-1088 전례: PendingResult.finish()는 finally 1곳에서만 1회.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appCtx = context.applicationContext
                val allowed = runCatching {
                    SettingsRepository.get(appCtx).firstBlocking().pluginAllowed
                }.getOrDefault(true)
                val appVersion = runCatching {
                    appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
                }.getOrNull() ?: "-"
                PluginLog.plugin(PluginContract.buildProbeLine(allowed, appVersion))
            } finally {
                pending.finish()
            }
        }
    }
}
