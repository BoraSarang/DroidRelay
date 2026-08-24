package com.borasarang.droidrelay.relay

import android.os.Build
import android.util.Log
import com.borasarang.droidrelay.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DebugLogger — 디버그 빌드에서만 동작하는 인메모리 링버퍼 로거 (AGENTS.md 8장·10장)
 *
 * - enabled = BuildConfig.DEBUG : 릴리즈 빌드에선 기록·Logcat 출력 모두 0비용(조기 반환)
 * - 최근 600줄 링버퍼 유지 → 디버그 패널에서 선택/전체 복사 지원 (10.2)
 * - 레벨: D(세부동작) I(주요흐름) W(경고) E(오류+원인)
 */
object DebugLogger {

    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    private const val TAG = "DroidRelay"
    private const val MAX_LINES = 600
    private val buf = ArrayDeque<String>(MAX_LINES + 16)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val cause = throwable?.let { t ->
            " :: ${t.javaClass.simpleName}: ${t.message ?: "(원인 없음)"}"
        } ?: ""
        write("E", tag, "$message$cause")
        if (enabled && throwable != null) Log.e(TAG, "[$tag] $message", throwable)
    }

    /** 성능 측정용 — block 실행 시간을 자동 기록 */
    inline fun <T> perf(tag: String, label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            i(tag, "⏱ $label 완료 (${System.currentTimeMillis() - start}ms)")
        }
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun lines(): List<String> = buf.toList()

    @Synchronized
    fun clear() {
        buf.clear()
        if (enabled) i("Logger", "로그 버퍼 비움 (사용자 요청)")
    }

    @Synchronized
    fun count(): Int = buf.size

    @Synchronized
    private fun write(level: String, tag: String, message: String) {
        if (!enabled) return
        val line = "[${time.format(Date())}][$level][$tag] $message"
        if (buf.size >= MAX_LINES) buf.removeFirst()
        buf.addLast(line)
        when (level) {
            "E" -> Log.e(TAG, line)
            "W" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
    }
}
