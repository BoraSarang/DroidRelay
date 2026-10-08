package com.borasarang.droidrelay.plugin

import android.util.Log

/**
 * T-1096 — 계약 로그 출력기 (SDK §8).
 *
 * 계약 3종(`[PLUGIN]`·`[REMOTE]`·`[EVENT]`)은 **`android.util.Log`로만 직접 출력**한다.
 * 한 사건 한 줄 — 래퍼 미러 등 중복 출력을 내면 소비자 스크랩 노이즈가 2배가 된다.
 */
object PluginLog {

    fun plugin(line: String) = Log.i(PluginContract.LOG_TAG, line)

    fun remote(line: String) = Log.i(PluginContract.LOG_TAG, line)

    fun event(line: String) = Log.i(PluginContract.LOG_TAG, line)

    fun refused() = remote(PluginContract.REFUSED_LINE)
}
