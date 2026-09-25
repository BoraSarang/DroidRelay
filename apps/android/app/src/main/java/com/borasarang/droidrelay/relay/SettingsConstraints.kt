package com.borasarang.droidrelay.relay

/** 설정 범위·기본값·라벨 단일 진실 (T-935).
 * 앱(SettingsScreen) ↔ 웹(WebAssets) ↔ 서버(Repo/Reset)가 같은 값을 참조한다.
 * 단위: 속도는 KB/s, 0의 의미는 필드별 상이 (업로드 0=끔 / 다운로드 0=무제한).
 */
object SettingsConstraints {
    // 토렌트 업로드 속도 (KB/s, 0=끔)
    const val TORRENT_UPLOAD_MIN = 0
    const val TORRENT_UPLOAD_MAX = 1024
    const val TORRENT_UPLOAD_STEP = 32
    const val DEFAULT_TORRENT_UPLOAD_KBPS = 512

    // 토렌트 다운로드 속도 (KB/s, 0=무제한)
    const val TORRENT_DOWNLOAD_MIN = 0
    const val TORRENT_DOWNLOAD_MAX = 20480
    const val TORRENT_DOWNLOAD_STEP = 1024
    const val DEFAULT_TORRENT_DOWNLOAD_KBPS = 0

    // 동시 다운로드 수
    const val CONCURRENCY_MIN = 1
    const val CONCURRENCY_MAX = 4
    const val DEFAULT_CONCURRENCY = 2

    // 토렌트 최대 활성 수
    const val TORRENT_MAX_ACTIVE_MIN = 1
    const val TORRENT_MAX_ACTIVE_MAX = 10
    const val DEFAULT_TORRENT_MAX_ACTIVE = 3

    // 정체(스톨) 토렌트 감지 → 자동 일시정지+회전 (T-1050)
    const val TORRENT_STALL_THRESHOLD_MIN = 0
    const val TORRENT_STALL_THRESHOLD_MAX = 256
    const val TORRENT_STALL_TIMEOUT_MIN = 15
    const val TORRENT_STALL_TIMEOUT_MAX = 3600
    const val DEFAULT_TORRENT_STALL_ENABLED = true
    const val DEFAULT_TORRENT_STALL_THRESHOLD_KBPS = 2
    const val DEFAULT_TORRENT_STALL_TIMEOUT_SEC = 60
    val TORRENT_STALL_THRESHOLD_PRESETS = listOf(1, 2, 4, 8, 16, 32, 64, 128)
    val TORRENT_STALL_TIMEOUT_PRESETS = listOf(30, 60, 120, 300, 600, 1800)

    /** 업로드 라벨: 0=끔, 그 외 KB/s·MB/s */
    fun uploadLabel(kbps: Long): String = when {
        kbps <= 0 -> "끔"
        kbps < 1024 -> "${kbps}KB/s"
        else -> "${kbps / 1024}M"
    }

    /** 다운로드 라벨: 0=무제한, 그 외 KB/s·MB/s */
    fun downloadLabel(kbps: Long): String = when {
        kbps <= 0 -> "무제한"
        kbps < 1024 -> "${kbps}KB/s"
        else -> "${kbps / 1024}M"
    }

    // 완료 후 동작 (v0.24)
    const val COMPLETION_ACTION_NONE = "none"
    const val COMPLETION_ACTION_STOP_SERVER = "stop_server"

    // 내장 웹 서버 포트 (v0.34) — HTTP·HTTPS는 서로 달라야 한다
    const val PORT_MIN = 1024
    const val PORT_MAX = 65535
    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_HTTPS_PORT = 8443

    /** HTTP·HTTPS 포트 쌍 유효성 — 범위 내 + 서로 다름 */
    fun validPorts(http: Int, https: Int): Boolean =
        http in PORT_MIN..PORT_MAX && https in PORT_MIN..PORT_MAX && http != https

    /** 임시 포트 (리셋용) */
    fun randomEphemeralPort(): Int = 49152 + kotlin.random.Random.nextInt(16384)
}
