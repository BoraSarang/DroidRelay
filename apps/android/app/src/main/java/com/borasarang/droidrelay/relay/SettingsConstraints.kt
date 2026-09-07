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

    /** 임시 포트 (리셋용) */
    fun randomEphemeralPort(): Int = 49152 + kotlin.random.Random.nextInt(16384)
}
