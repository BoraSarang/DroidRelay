package com.borasarang.droidrelay.relay

/** 속도 제한 프리셋 단일 진실 (T-1050).
 *  작업별 제한 / 토렌트 개별 제한 / 전역 다운로드 제한 / 앱 레벨 속도 제한이 같은 목록을 쓴다.
 *  단위는 내부적으로 KB/s, 작업 API는 B/s(=KB/s*1024).
 */
object SpeedLimits {
    /** KB/s 프리셋 (0 = 무제한/끔) */
    val KBPS: List<Int> = listOf(0, 256, 512, 1024, 2048, 5120, 10240, 20480)

    /** KB/s 라벨 */
    fun label(kbps: Long): String = when {
        kbps <= 0 -> "무제한"
        kbps < 1024 -> "${kbps}KB/s"
        else -> "${kbps / 1024}MB/s"
    }

    /** B/s 라벨 (0 이하 = 무제한) */
    fun labelBps(bps: Long): String {
        if (bps <= 0) return "무제한"
        val kbps = (bps + 1023) / 1024
        return label(kbps)
    }

    /** B/s 옵션 — 작업별·토렌트별 개별 제한 셀렉트용 */
    fun bpsOptions(): List<Pair<Long, String>> = KBPS.map { it * 1024L to label(it.toLong()) }

    /** KB/s 옵션 — 설정 셀렉트용. 목록에 없는 현재값은 끝에 폴백 추가 */
    fun kbpsOptions(current: Int): List<Pair<Int, String>> =
        KBPS.map { it to label(it.toLong()) } +
            if (current in KBPS) emptyList() else listOf(current to label(current.toLong()))
}
