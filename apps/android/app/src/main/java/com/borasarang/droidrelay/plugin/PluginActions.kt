package com.borasarang.droidrelay.plugin

import android.content.Context
import com.borasarang.droidrelay.relay.JobsRepository
import com.borasarang.droidrelay.relay.RelayApp
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.relay.lanAddress
import kotlinx.coroutines.flow.first

/**
 * T-1096 — 플러그인 액션 실행 (SDK §4, fire-and-forget, 결과는 [REMOTE] 로그로만 보고).
 *
 * 호출 경로는 [PluginActionReceiver](브로드캐스트, UI 없음) 하나다.
 * MainActivity 전면 경로는 SDK §4.3에서 금지돼 v2 승격 시 제거됐다.
 */
object PluginActions {

    /** cmd/arg 1회 처리 — allowed 검사부터 거부 로그까지 전부 여기서 끝낸다 */
    suspend fun dispatch(ctx: Context, cmd: String?, arg: String?) {
        val appCtx = ctx.applicationContext
        if (cmd.isNullOrBlank()) {
            PluginLog.remote(
                PluginContract.remoteFailure("unknown", PluginContract.ERR_INVALID_INPUT, "empty cmd"),
            )
            return
        }
        val allowed = runCatching {
            SettingsRepository.get(appCtx).settings.first().pluginAllowed
        }.getOrDefault(true)
        if (!allowed) {
            PluginLog.refused()
            return
        }
        when (cmd) {
            "server_status" -> reportStatus(appCtx)
            "server_control" -> controlServer(appCtx, arg)
            "download_add" -> addDownload(appCtx, arg)
            "torrent_add" -> addTorrent(appCtx, arg)
            else -> PluginLog.remote(
                PluginContract.remoteFailure(cmd, PluginContract.ERR_INVALID_INPUT, "unknown cmd=$cmd"),
            )
        }
    }

    private suspend fun reportStatus(appCtx: Context) {
        val repo = SettingsRepository.get(appCtx)
        val settings = runCatching { repo.settings.first() }.getOrNull()
        val running = repo.serverState.value.running
        val ip = runCatching { lanAddress() }.getOrNull()
        val version = runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
        }.getOrNull() ?: "-"
        PluginLog.remote(PluginContract.remoteServerStatus(running, ip, settings?.port ?: 3000, version))
    }

    private fun controlServer(appCtx: Context, arg: String?) {
        when (arg) {
            PluginContract.SERVER_OP_START -> {
                RelayService.start(appCtx)
                PluginLog.remote(PluginContract.remoteServerControl(arg, true))
            }
            PluginContract.SERVER_OP_STOP -> {
                RelayService.stop(appCtx)
                PluginLog.remote(PluginContract.remoteServerControl(arg, false))
            }
            else -> PluginLog.remote(
                PluginContract.remoteFailure(
                    "server_control",
                    PluginContract.ERR_INVALID_INPUT,
                    "unknown op=${arg ?: "-"}",
                ),
            )
        }
    }

    private fun addDownload(appCtx: Context, url: String?) {
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            PluginLog.remote(
                PluginContract.remoteFailure("download_add", PluginContract.ERR_INVALID_INPUT, "invalid url"),
            )
            return
        }
        val existing = JobsRepository.findDuplicateUrl(url)
        if (existing != null) {
            PluginLog.remote(PluginContract.remoteDownloadAdd(existing.id, true))
            return
        }
        runCatching { RelayApp.get(appCtx).enqueue(url) }
            .onSuccess { PluginLog.remote(PluginContract.remoteDownloadAdd(it.id, false)) }
            .onFailure {
                PluginLog.remote(
                    PluginContract.remoteFailure("download_add", PluginContract.ERR_EXEC_FAILED, it.message ?: "-"),
                )
            }
    }

    private fun addTorrent(appCtx: Context, magnet: String?) {
        if (magnet.isNullOrBlank() || !magnet.startsWith("magnet:")) {
            PluginLog.remote(
                PluginContract.remoteFailure("torrent_add", PluginContract.ERR_INVALID_INPUT, "invalid magnet"),
            )
            return
        }
        val engine = RelayApp.getTorrent(appCtx)
        val dup = engine.isDuplicateMagnet(magnet)
        runCatching { engine.addMagnet(magnet) }
            .onSuccess { PluginLog.remote(PluginContract.remoteTorrentAdd(it.id, dup)) }
            .onFailure {
                PluginLog.remote(
                    PluginContract.remoteFailure("torrent_add", PluginContract.ERR_EXEC_FAILED, it.message ?: "-"),
                )
            }
    }
}
