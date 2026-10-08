package com.borasarang.droidrelay.plugin

import android.util.Log
import com.borasarang.droidrelay.relay.DebugLogger

/**
 * T-1095 — 계약 로그 출력기.
 *
 * 계약 3종(`[PLUGIN]`·`[REMOTE]`·`[EVENT]`)은 **릴리즈에서도 보여야 하므로**
 * `android.util.Log`로 직접 출력한다. `DebugLogger`만 쓰면 릴리즈에서 조기 반환돼
 * 계약이 깨진다. 디버그 패널 가시성용으로 `DebugLogger` 미러를 병행한다
 * (릴리즈에선 미러만 조용히 사라지고 직접 출력은 남는다).
 */
object PluginLog {

    fun plugin(line: String) {
        Log.i(PluginContract.LOG_TAG, line)
        runCatching { DebugLogger.i("Plugin", line) }
    }

    fun remote(line: String) {
        Log.i(PluginContract.LOG_TAG, line)
        runCatching { DebugLogger.i("Plugin", line) }
    }

    fun event(line: String) {
        Log.i(PluginContract.LOG_TAG, line)
        runCatching { DebugLogger.i("Plugin", line) }
    }

    fun refused() = remote(PluginContract.REFUSED_LINE)
}
