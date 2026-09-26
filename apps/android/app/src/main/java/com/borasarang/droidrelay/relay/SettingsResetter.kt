package com.borasarang.droidrelay.relay

import android.content.Context

/**
 * "기본값 복원" 단일 구현 — 앱(SettingsComponents)과 웹(SettingsRoutes)이 같은 것을 쓴다.
 *
 * 두 구현이 별개로 존재하며 키 집합이 어긋나 있었다:
 * - 앱 리셋: storageQuotaGb·autoClassify·torrentTrackerSync·search* 를 되돌리지 않음
 * - 웹 리셋: 되돌림 (앱에서 리셋해도 이 값들은 남아 "리셋했는데 그대로" 로 보임)
 * 어느 쪽이 맞는지는 기능 판단이 아니라 중복 제거로 해결한다.
 *
 * 엔진 반영도 여기서만 한다. 각 호출부가 `get(ctx)` / `getTorrent(ctx)` 를 따로 부르면
 * 다시 한쪽만 갱신되는 회귀가 생긴다.
 */
object SettingsResetter {

    suspend fun reset(ctx: Context, repo: SettingsRepository, category: String) {
        when (category) {
            "download" -> downloadDefaults(repo)
            "torrent" -> torrentDefaults(repo)
            else -> {
                downloadDefaults(repo)
                torrentDefaults(repo)
            }
        }
        applyAll(ctx, repo)
    }

    private suspend fun downloadDefaults(repo: SettingsRepository) {
        repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
        repo.setSpeedLimit(0)
        repo.setMaxDownloadBps(0)
        repo.setMaxUploadBps(0)
        repo.setNotifications(true)
        repo.setStorageQuotaGb(0)
        repo.setAutoClassify(false)
        repo.setSpeedSchedule(emptyList())
        repo.setCompletionAction(SettingsConstraints.COMPLETION_ACTION_NONE)
    }

    private suspend fun torrentDefaults(repo: SettingsRepository) {
        repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
        repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
        repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
        repo.setTorrentSeedRatio(2.0f)
        repo.setTorrentMinSeedWaitSec(0)
        repo.setTorrentDhtEnabled(true)
        repo.setTorrentPexEnabled(true)
        repo.setTorrentSequentialDownload(false)
        repo.setTorrentTrackerSync(true)
        repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
        repo.setTorrentSavePath(StorageGuard.dlRoot.path)
        repo.setTorrentStallEnabled(SettingsConstraints.DEFAULT_TORRENT_STALL_ENABLED)
        repo.setTorrentStallThresholdKbps(SettingsConstraints.DEFAULT_TORRENT_STALL_THRESHOLD_KBPS)
        repo.setTorrentStallTimeoutSec(SettingsConstraints.DEFAULT_TORRENT_STALL_TIMEOUT_SEC)
        repo.setSearchEnabled(false)
        repo.setSearchUrl("")
        repo.setSearchApiKey("")
    }

    /**
     * 두 엔진에 설정 적용 — 단일 팬아웃 지점.
     * 각 호출부가 엔진 하나씩만 적용하던 구조가 "앱에서는 반영되고 웹에서는 안 됨"의 원인.
     */
    fun applyAll(ctx: Context, repo: SettingsRepository) {
        val s = repo.firstBlocking()
        // runBlocking 이 메인 스레드에서 호출될 수 있으므로 IO 로 넘긴다
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            RelayApp.applySettings(s)
        }
        DebugLogger.i("Settings", "설정 엔진 반영 완료")
    }
}
