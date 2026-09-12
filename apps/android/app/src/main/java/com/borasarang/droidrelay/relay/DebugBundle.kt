package com.borasarang.droidrelay.relay

/** 진단 번들 조립 (v0.22 Phase A).
 * 순수함수로 단위 테스트 가능. 실제 ZIP 스트리밍은 DebugRoutes가 담당.
 * org.json 미사용 — JVM 단위 테스트에서 Android 스텁 반환 문제를 피하기 위해 수동 직렬화.
 */
object DebugBundle {
    internal fun settingsToJson(masked: Map<String, Any?>): String {
        val body = masked.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            val vs = when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            "\"$k\":$vs"
        }
        return "{$body}"
    }

    /** 번들 엔트리 조립 — 키=ZIP 내 파일명, 값=바이트 */
    fun buildEntries(
        logs: List<String>,
        apiCalls: List<String>,
        metricsJson: String,
        settingsMasked: Map<String, Any?>,
        jobsRaw: String,
        torrentsRaw: String,
        deviceJson: String,
    ): Map<String, ByteArray> {
        val settingsJson = settingsToJson(settingsMasked)
        return mapOf(
            "logs.txt" to logs.joinToString("\n").toByteArray(),
            "api-calls.txt" to apiCalls.joinToString("\n").toByteArray(),
            "metrics.json" to metricsJson.toByteArray(),
            "settings.json" to settingsJson.toByteArray(),
            "jobs.json" to jobsRaw.toByteArray(),
            "torrents.json" to torrentsRaw.toByteArray(),
            "device.json" to deviceJson.toByteArray(),
        )
    }
}
