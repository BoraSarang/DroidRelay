package com.borasarang.droidrelay.relay

import android.content.Context
import android.os.StatFs
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 설정·RSS·Debrid·가드·웹훅·터널·MCP·스케줄 라우트 (T-936 D1 — RelayServer에서 분리) */
internal fun Route.settingsRoutes(context: Context, serverRef: RelayServer) {
    get("/api/info") {
        val dir = RelayApp.get(context).workDir
        val stat = runCatching { StatFs(dir.path) }.getOrNull()
        val jobs = JobsRepository.all()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.5.0"
        val info = JSONObject().apply {
            put("ip", lanAddress() ?: JSONObject.NULL)
            put("port", serverRef.port)
            put("version", appVersion)
            put("storageFree", stat?.availableBytes ?: JSONObject.NULL)
            put("storageTotal", stat?.totalBytes ?: JSONObject.NULL)
            put("running", jobs.count { it.state == JobState.RUNNING })
            put("speedTotalBps", jobs.filter { it.state == JobState.RUNNING }.sumOf { it.speedBps })
        }
        call.respondText(info.toString(), ContentType.Application.Json)
    }

    // ── 전역 속도 제한 설정 ──
    get("/api/settings/speed-limit") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("maxDownloadBps", s.maxDownloadBps)
                put("maxUploadBps", s.maxUploadBps)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/speed-limit") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("maxDownloadBps") == true) {
            val dl = json?.optLong("maxDownloadBps", 0) ?: 0L
            repo.setMaxDownloadBps(dl)
        }
        if (json?.has("maxUploadBps") == true) {
            val ul = json?.optLong("maxUploadBps", 0) ?: 0L
            repo.setMaxUploadBps(ul)
        }
        // 엔진에 즉시 반영
        val s = repo.firstBlocking()
        RelayApp.get(context).applySpeedLimit(s.maxDownloadBps, s.maxUploadBps)
        RelayApp.getTorrent(context).applySpeedLimit(s.maxDownloadBps, s.maxUploadBps)
        serverRef.settings = s
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 다운로드 설정 ──
    get("/api/settings/download") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("concurrency", s.concurrency)
                put("speedLimitKbps", s.speedLimitKbps)
                put("notifications", s.notifications)
                put("storageQuotaGb", s.storageQuotaGb)
                put("autoClassify", s.autoClassify)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/download") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        json?.optInt("concurrency", -1)?.let { if (it >= 1) repo.setConcurrency(it) }
        json?.optInt("speedLimitKbps", -1)?.let { if (it >= 0) repo.setSpeedLimit(it) }
        if (json?.has("notifications") == true) json?.optBoolean("notifications")?.let { repo.setNotifications(it) }
        json?.optInt("storageQuotaGb", -1)?.let { if (it >= 0) repo.setStorageQuotaGb(it) }
        if (json?.has("autoClassify") == true) json?.optBoolean("autoClassify")?.let { repo.setAutoClassify(it) }
        // 엔진에 즉시 반영
        RelayApp.get(context).applySettings(repo.firstBlocking())
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 토렌트 설정 ──
    get("/api/settings/torrent") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("torrentUploadLimit", s.torrentUploadLimit)
                put("torrentDownloadLimit", s.torrentDownloadLimit)
                put("torrentMaxActive", s.torrentMaxActive)
                put("torrentSeedRatio", s.torrentSeedRatio)
                put("torrentDhtEnabled", s.torrentDhtEnabled)
                put("torrentPexEnabled", s.torrentPexEnabled)
                put("torrentListenPort", s.torrentListenPort)
                put("torrentSavePath", s.torrentSavePath)
                put("torrentSequentialDownload", s.torrentSequentialDownload)
                put("torrentMinSeedWaitSec", s.torrentMinSeedWaitSec)
                put("torrentTrackerSync", s.torrentTrackerSync)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/torrent") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        json?.optLong("torrentUploadLimit", -1)?.let { if (it >= 0) repo.setTorrentUploadLimit(it.toInt()) }
        json?.optLong("torrentDownloadLimit", -1)?.let { if (it >= 0) repo.setTorrentDownloadLimit(it.toInt()) }
        json?.optInt("torrentMaxActive", -1)?.let { if (it >= 1) repo.setTorrentMaxActive(it) }
        json?.optDouble("torrentSeedRatio", -1.0)?.let { if (it >= 0) repo.setTorrentSeedRatio(it.toFloat()) }
        if (json?.has("torrentDhtEnabled") == true) json?.optBoolean("torrentDhtEnabled")?.let { repo.setTorrentDhtEnabled(it) }
        if (json?.has("torrentPexEnabled") == true) json?.optBoolean("torrentPexEnabled")?.let { repo.setTorrentPexEnabled(it) }
        json?.optInt("torrentListenPort", -1)?.let { if (it >= 1024) repo.setTorrentListenPort(it) }
        json?.optString("torrentSavePath", "")?.let { if (it.isNotBlank()) repo.setTorrentSavePath(it) }
        if (json?.has("torrentSequentialDownload") == true) json?.optBoolean("torrentSequentialDownload")?.let { repo.setTorrentSequentialDownload(it) }
        json?.optInt("torrentMinSeedWaitSec", -1)?.let { if (it >= 0) repo.setTorrentMinSeedWaitSec(it) }
        if (json?.has("torrentTrackerSync") == true) json?.optBoolean("torrentTrackerSync")?.let { repo.setTorrentTrackerSync(it) }
        // 엔진에 즉시 반영
        RelayApp.getTorrent(context).applySettings(repo.firstBlocking())
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 토렌트 검색 설정 (Torznab, T-950) ──
    get("/api/settings/search") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("searchEnabled", s.searchEnabled)
                put("searchUrl", s.searchUrl)
                put("searchApiKey", s.searchApiKey)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/search") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("searchEnabled") == true) json?.optBoolean("searchEnabled")?.let { repo.setSearchEnabled(it) }
        json?.optString("searchUrl", "")?.let { if (it.isNotBlank()) repo.setSearchUrl(it) }
        if (json?.has("searchApiKey") == true) json?.optString("searchApiKey", "")?.let { repo.setSearchApiKey(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 설정 리셋 (기본값 복원) ──
    post("/api/settings/reset") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val category = json.optString("category", "all")
        val repo = SettingsRepository.get(context)
        val ctx = context
        when (category) {
            "download" -> {
                repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
                repo.setSpeedLimit(0)
                repo.setNotifications(true)
                repo.setStorageQuotaGb(0)
                repo.setAutoClassify(false)
                RelayApp.get(ctx).applySettings(repo.firstBlocking())
            }
            "torrent" -> {
                repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
                repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
                repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
                repo.setTorrentSeedRatio(2.0f)
                repo.setTorrentDhtEnabled(true)
                repo.setTorrentPexEnabled(true)
                repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
                repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
                repo.setSearchEnabled(false)
                repo.setSearchUrl("")
                repo.setSearchApiKey("")
                RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
            }
            else -> {
                repo.setConcurrency(SettingsConstraints.DEFAULT_CONCURRENCY)
                repo.setSpeedLimit(0)
                repo.setNotifications(true)
                repo.setStorageQuotaGb(0)
                repo.setAutoClassify(false)
                repo.setTorrentUploadLimit(SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS)
                repo.setTorrentDownloadLimit(SettingsConstraints.DEFAULT_TORRENT_DOWNLOAD_KBPS)
                repo.setTorrentMaxActive(SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE)
                repo.setTorrentSeedRatio(2.0f)
                repo.setTorrentDhtEnabled(true)
                repo.setTorrentPexEnabled(true)
                repo.setTorrentListenPort(SettingsConstraints.randomEphemeralPort())
                repo.setTorrentSavePath("/sdcard/Download/DroidRelay")
                repo.setTorrentTrackerSync(true)
                repo.setSearchEnabled(false)
                repo.setSearchUrl("")
                repo.setSearchApiKey("")
                RelayApp.get(ctx).applySettings(repo.firstBlocking())
                RelayApp.getTorrent(ctx).applySettings(repo.firstBlocking())
            }
        }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 경로 테스트 (쓰기 권한 확인) ──
    post("/api/storage/test-path") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val path = json?.optString("path", "") ?: ""
        if (path.isBlank()) {
            call.respondText("""{"ok":false,"error":"경로 없음"}""", ContentType.Application.Json)
            return@post
        }
        val testDir = java.io.File(path)
        try {
            testDir.mkdirs()
            val testFile = java.io.File(testDir, ".droidrelay_test_${System.currentTimeMillis()}")
            testFile.writeText("test")
            val ok = testFile.exists() && testFile.delete()
            call.respondText(
                JSONObject().apply { put("ok", ok) }.toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.respondText(
                JSONObject().apply { put("ok", false); put("error", e.message ?: "알 수 없는 오류") }.toString(),
                ContentType.Application.Json
            )
        }
    }

    // ── RSS 피드 관리 ──
    get("/api/rss") {
        val arr = JSONArray()
        RssFeedRepository.all().forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("url", f.url)
                put("name", f.name)
                put("filterKeyword", f.filterKeyword)
                put("filterRegex", f.filterRegex)
                put("autoDownload", f.autoDownload)
                put("enabled", f.enabled)
                put("lastCheckedAt", f.lastCheckedAt)
                put("lastItemTitle", f.lastItemTitle)
                put("lastItemCount", f.lastItemCount)
                put("error", f.error ?: JSONObject.NULL)
            })
        }
        call.respondText(arr.toString(), ContentType.Application.Json)
    }

    post("/api/rss") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val url = json?.optString("url", "") ?: ""
        if (url.isBlank()) {
            call.respondText("""{"ok":false,"error":"URL 필요"}""", ContentType.Application.Json)
            return@post
        }
        val name = json?.optString("name", "") ?: ""
        val filterKeyword = json?.optString("filterKeyword", "") ?: ""
        val filterRegex = json?.optString("filterRegex", "") ?: ""
        val autoDownload = json?.optBoolean("autoDownload", true) ?: true
        val feed = RssFeedRepository.add(url, name, filterKeyword, filterRegex, autoDownload)
        call.respondText(
            JSONObject().apply { put("ok", true); put("id", feed.id) }.toString(),
            ContentType.Application.Json
        )
    }

    delete("/api/rss/{id}") {
        val id = call.parameters["id"] ?: ""
        val ok = RssFeedRepository.remove(id)
        call.respondText(
            JSONObject().apply { put("ok", ok) }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/rss/{id}/check") {
        val id = call.parameters["id"] ?: ""
        // id "0" = 전체 피드 확인 (웹 UI "지금 확인" 버튼)
        if (id != "0") {
            val feed = RssFeedRepository.get(id)
            if (feed == null) {
                call.respondText("""{"ok":false,"error":"피드 없음"}""", ContentType.Application.Json)
                return@post
            }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                RssFeedManager(context).checkAllFeeds()
            } catch (e: Exception) {
                DebugLogger.e("RelayServer", "RSS 수동 확인 실패", e)
            }
        }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── Debrid 설정 ──
    get("/api/settings/debrid") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("debridEnabled", s.debridEnabled)
                put("debridProvider", s.debridProvider)
                put("debridApiKey", s.debridApiKey)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/debrid") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("debridEnabled") == true) json?.optBoolean("debridEnabled")?.let { repo.setDebridEnabled(it) }
        if (json?.has("debridProvider") == true) json?.optString("debridProvider")?.let { repo.setDebridProvider(it) }
        if (json?.has("debridApiKey") == true) json?.optString("debridApiKey")?.let { repo.setDebridApiKey(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── Debrid 언리스트링크 ──
    post("/api/debrid/unrestrict") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val url = json?.optString("url", "") ?: ""
        if (url.isBlank()) {
            call.respondText("""{"ok":false,"error":"URL 필요"}""", ContentType.Application.Json)
            return@post
        }
        val s = serverRef.settings
        if (!s.debridEnabled || s.debridApiKey.isBlank()) {
            call.respondText("""{"ok":false,"error":"Debrid 미설정 또는 비활성화"}""", ContentType.Application.Json)
            return@post
        }
        val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
        if (provider == null) {
            call.respondText("""{"ok":false,"error":"지원하지 않는 제공자: ${s.debridProvider}"}""", ContentType.Application.Json)
            return@post
        }
        try {
            val client = DebridClient(context)
            val link = client.unrestrict(url, provider, s.debridApiKey)
            call.respondText(
                JSONObject().apply {
                    put("ok", true)
                    put("id", link.id)
                    put("filename", link.filename)
                    put("filesize", link.filesize)
                    put("directUrl", link.directUrl)
                    put("chunks", link.chunks)
                    put("streamable", link.streamable)
                }.toString(),
                ContentType.Application.Json
            )
        } catch (e: DebridException) {
            DebugLogger.e("Debrid", "언리스트링크 실패 url=${url.take(80)}", e)
            call.respondText(
                JSONObject().apply { put("ok", false); put("error", e.message ?: "Debrid API 오류") }.toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            DebugLogger.e("Debrid", "언리스트링크 실패", e)
            call.respondText(
                JSONObject().apply { put("ok", false); put("error", "네트워크 오류: ${e.message}") }.toString(),
                ContentType.Application.Json
            )
        }
    }

    // ── Debrid 계정 확인 ──
    post("/api/debrid/check") {
        val s = serverRef.settings
        if (s.debridApiKey.isBlank()) {
            call.respondText("""{"ok":false,"error":"API 키 미설정"}""", ContentType.Application.Json)
            return@post
        }
        val provider = runCatching { DebridProvider.valueOf(s.debridProvider) }.getOrNull()
        if (provider == null) {
            call.respondText("""{"ok":false,"error":"제공자 미설정"}""", ContentType.Application.Json)
            return@post
        }
        try {
            val client = DebridClient(context)
            val info = client.checkAccount(provider, s.debridApiKey)
            call.respondText(
                JSONObject().apply {
                    put("ok", true)
                    put("premium", info.optBoolean("premium", false))
                    put("username", info.optString("username", ""))
                    put("email", info.optString("email", ""))
                }.toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.respondText(
                JSONObject().apply { put("ok", false); put("error", e.message ?: "확인 실패") }.toString(),
                ContentType.Application.Json
            )
        }
    }

    // ── 가드 데몬 설정 (Phase 2.4) ──
    get("/api/settings/guard") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("guardEnabled", s.guardEnabled)
                put("guardThermalLimit", s.guardThermalLimit)
                put("guardBatteryLimit", s.guardBatteryLimit)
                put("guardStorageLimit", s.guardStorageLimit)
                put("watchdogIntervalSec", s.watchdogIntervalSec)
                put("forceHttpsRedirect", s.forceHttpsRedirect)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/guard") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("guardEnabled") == true) json?.optBoolean("guardEnabled")?.let { repo.setGuardEnabled(it) }
        json?.optInt("guardThermalLimit", -1)?.let { if (it in 50..70) repo.setGuardThermalLimit(it) }
        json?.optInt("guardBatteryLimit", -1)?.let { if (it in 5..50) repo.setGuardBatteryLimit(it) }
        json?.optInt("guardStorageLimit", -1)?.let { if (it in 50..99) repo.setGuardStorageLimit(it) }
        json?.optInt("watchdogIntervalSec", -1)?.let { if (it in 15..3600) repo.setWatchdogIntervalSec(it) }
        if (json?.has("forceHttpsRedirect") == true) json?.optBoolean("forceHttpsRedirect")?.let { repo.setForceHttpsRedirect(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 가드 데몬 상태 ──
    get("/api/guard/status") {
        val ctx = context
        val thermal = try {
            val file = java.io.File("/sys/class/thermal/thermal_zone0/temp")
            if (file.exists()) (file.readText().trim().toIntOrNull() ?: 0) / 1000 else 0
        } catch (_: Exception) { 0 }

        val batteryManager = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val storageDir = java.io.File("/sdcard/Download/DroidRelay")
        val storageUsed = if (storageDir.exists()) {
            val total = storageDir.totalSpace
            val free = storageDir.freeSpace
            if (total > 0) ((total - free) * 100 / total).toInt() else 0
        } else 0

        val s = serverRef.settings
        val throttled = s.guardEnabled && (
            (thermal > s.guardThermalLimit) ||
                (batteryLevel in 0..s.guardBatteryLimit) ||
                (storageUsed > s.guardStorageLimit)
            )

        call.respondText(
            JSONObject().apply {
                put("thermal", thermal)
                put("thermalLimit", s.guardThermalLimit)
                put("batteryLevel", batteryLevel)
                put("batteryLimit", s.guardBatteryLimit)
                put("storageUsed", storageUsed)
                put("storageLimit", s.guardStorageLimit)
                put("throttled", throttled)
                put("guardEnabled", s.guardEnabled)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // ── 웹훅 설정 (Phase 2.2) ──
    get("/api/settings/webhook") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("webhookEnabled", s.webhookEnabled)
                put("webhookUrl", s.webhookUrl)
                put("webhookSecret", if (s.webhookSecret.isNotEmpty()) "••••••••" else "")
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/webhook") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("webhookEnabled") == true) json?.optBoolean("webhookEnabled")?.let { repo.setWebhookEnabled(it) }
        if (json?.has("webhookUrl") == true) json?.optString("webhookUrl")?.let { repo.setWebhookUrl(it) }
        if (json?.has("webhookSecret") == true) json?.optString("webhookSecret")?.let { repo.setWebhookSecret(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 터널 설정 (Phase 2.3) ──
    get("/api/settings/tunnel") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("tunnelEnabled", s.tunnelEnabled)
                put("tunnelProvider", s.tunnelProvider)
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/tunnel") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("tunnelEnabled") == true) json?.optBoolean("tunnelEnabled")?.let { repo.setTunnelEnabled(it) }
        if (json?.has("tunnelProvider") == true) json?.optString("tunnelProvider")?.let { repo.setTunnelProvider(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 터널 상태 (Phase 2.3 확장) ──
    get("/api/tunnel/status") {
        val s = serverRef.settings
        val provider = runCatching { TunnelProvider.fromString(s.tunnelProvider) }.getOrNull()
        val statusText = if (provider != null && s.tunnelEnabled) {
            when (provider) {
                TunnelProvider.TAILSCALE -> {
                    val ip = try {
                        java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { it.inetAddresses?.asSequence() ?: emptySequence() }
                            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.hostAddress?.startsWith("100.") == true }?.hostAddress
                    } catch (_: Exception) { null }
                    if (ip != null) JSONObject().apply { put("connected", true); put("ip", ip); put("url", "http://$ip:8080") }.toString()
                    else JSONObject().apply { put("connected", false); put("reason", "Tailscale 미연결 또는 미설치") }.toString()
                }
                TunnelProvider.CLOUDFLARE -> JSONObject().apply { put("connected", false); put("reason", "cloudflared 바이너리 필요") }.toString()
            }
        } else JSONObject().apply { put("connected", false); put("reason", "터널 비활성화") }.toString()
        call.respondText(statusText, ContentType.Application.Json)
    }

    // ── MCP 권한 설정 (Phase 2.1 확장) ──
    get("/api/settings/mcp") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("mcpPrivacyMode", s.mcpPrivacyMode)
                put("mcpToolsDisabled", org.json.JSONArray(s.mcpToolsDisabled.toList()))
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/mcp") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("mcpPrivacyMode") == true) json?.optBoolean("mcpPrivacyMode")?.let { repo.setMcpPrivacyMode(it) }
        if (json?.has("mcpToolsDisabled") == true) {
            val arr = json.optJSONArray("mcpToolsDisabled")
            if (arr != null) {
                val disabled = (0 until arr.length()).map { arr.getString(it) }.toSet()
                // 기존 설정에서 업데이트
                val current = repo.firstBlocking().mcpToolsDisabled
                val toDisable = disabled - current
                val toEnable = current - disabled
                toDisable.forEach { repo.setMcpToolDisabled(it, true) }
                toEnable.forEach { repo.setMcpToolDisabled(it, false) }
            }
        }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── 스케줄 설정 (Phase 3 확장) ──
    get("/api/settings/schedule") {
        val s = serverRef.settings
        call.respondText(
            JSONObject().apply {
                put("scheduleEnabled", s.scheduleEnabled)
                put("scheduleCron", s.scheduleCron)
                put("scheduleWifiOnly", s.scheduleWifiOnly)
                put("scheduleChargingOnly", s.scheduleChargingOnly)
                put("scheduleBatteryMin", s.scheduleBatteryMin)
                put("cronValid", CronParser.isValid(s.scheduleCron))
            }.toString(),
            ContentType.Application.Json
        )
    }

    post("/api/settings/schedule") {
        val body = call.receiveText()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val repo = SettingsRepository.get(context)
        if (json?.has("scheduleEnabled") == true) json?.optBoolean("scheduleEnabled")?.let { repo.setScheduleEnabled(it) }
        if (json?.has("scheduleCron") == true) json?.optString("scheduleCron")?.let { repo.setScheduleCron(it) }
        if (json?.has("scheduleWifiOnly") == true) json?.optBoolean("scheduleWifiOnly")?.let { repo.setScheduleWifiOnly(it) }
        if (json?.has("scheduleChargingOnly") == true) json?.optBoolean("scheduleChargingOnly")?.let { repo.setScheduleChargingOnly(it) }
        if (json?.has("scheduleBatteryMin") == true) json?.optInt("scheduleBatteryMin")?.let { repo.setScheduleBatteryMin(it) }
        serverRef.settings = repo.firstBlocking()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }
}
