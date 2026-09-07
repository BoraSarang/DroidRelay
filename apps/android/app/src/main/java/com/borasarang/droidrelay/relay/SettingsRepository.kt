package com.borasarang.droidrelay.relay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 접속 범위 (T-112: 같은 핫스팟 기본값) */
enum class AccessScope { SUBNET_ONLY, ANY_WITH_PASSWORD, APPROVED_ONLY }

/** 서버 상태 (메모리만, DataStore 불필요) */
data class ServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val url: String? = null,
    val error: String? = null,
)

data class AppSettings(
    val port: Int = 8080,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val concurrency: Int = SettingsConstraints.DEFAULT_CONCURRENCY,
    val autoStart: Boolean = true,
    val notifications: Boolean = true,
    val webAuthEnabled: Boolean = false,
    val webUser: String = "droidrelay",
    val webPassword: String = "",
    val speedLimitKbps: Int = 0,
    val maxDownloadBps: Long = 0L,
    val maxUploadBps: Long = 0L,
    val allowedIps: Set<String> = emptySet(),
    val accessScope: AccessScope = AccessScope.SUBNET_ONLY,
    val torrentSavePath: String = "",
    val torrentUploadLimit: Long = SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS.toLong(),
    val torrentDownloadLimit: Long = 0L,
    val torrentMaxActive: Int = 3,
    val torrentSeedRatio: Float = 2.0f,
    val torrentDhtEnabled: Boolean = true,
    val torrentPexEnabled: Boolean = true,
    val torrentListenPort: Int = 6881,
    val torrentSequentialDownload: Boolean = false,
    // Debrid (Phase 1.3)
    val debridEnabled: Boolean = false,
    val debridProvider: String = "",
    val debridApiKey: String = "",
    // Guard (Phase 2.4)
    val guardEnabled: Boolean = false,
    val guardThermalLimit: Int = 50,
    val guardBatteryLimit: Int = 20,
    val guardStorageLimit: Int = 90,
    // Webhook (Phase 2.2)
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookSecret: String = "",
    // Tunnel (Phase 2.3)
    val tunnelEnabled: Boolean = false,
    val tunnelProvider: String = "",
    // MCP permissions (Phase 2.1 확장)
    val mcpPrivacyMode: Boolean = false,
    val mcpToolsDisabled: Set<String> = emptySet(),
    // Schedule (Phase 3 확장)
    val scheduleEnabled: Boolean = false,
    val scheduleCron: String = "",
    val scheduleWifiOnly: Boolean = true,
    val scheduleChargingOnly: Boolean = false,
    val scheduleBatteryMin: Int = 30,
    val watchdogIntervalSec: Int = 60,
    val torrentMinSeedWaitSec: Int = 0,
    val forceHttpsRedirect: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore("droidrelay_settings")

class SettingsRepository(private val context: Context) {

    // 서버 상태 (메모리 StateFlow)
    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState

    fun updateServerState(state: ServerState) {
        _serverState.value = state
        DebugLogger.d("Settings", "서버 상태 갱신 running=${state.running} port=${state.port} error=${state.error}")
    }

    private object Keys {
        val PORT = intPreferencesKey("port")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val CONCURRENCY = intPreferencesKey("concurrency")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val WEB_AUTH = booleanPreferencesKey("web_auth_enabled")
        val WEB_USER = stringPreferencesKey("web_user")
        val WEB_PASS = stringPreferencesKey("web_password")
        val SPEED_LIMIT = intPreferencesKey("speed_limit_kbps")
        val MAX_DOWNLOAD_BPS = longPreferencesKey("max_download_bps")
        val MAX_UPLOAD_BPS = longPreferencesKey("max_upload_bps")
        val ALLOWED_IPS = stringSetPreferencesKey("allowed_ips")
        val ACCESS_SCOPE = stringPreferencesKey("access_scope")
        val TORRENT_SAVE_PATH = stringPreferencesKey("torrent_save_path")
        val TORRENT_UPLOAD_LIMIT = intPreferencesKey("torrent_upload_limit")
        val TORRENT_DOWNLOAD_LIMIT = intPreferencesKey("torrent_download_limit")
        val TORRENT_MAX_ACTIVE = intPreferencesKey("torrent_max_active")
        val TORRENT_SEED_RATIO = intPreferencesKey("torrent_seed_ratio")
        val TORRENT_DHT = booleanPreferencesKey("torrent_dht")
        val TORRENT_PEX = booleanPreferencesKey("torrent_pex")
        val TORRENT_LISTEN_PORT = intPreferencesKey("torrent_listen_port")
        val TORRENT_SEQUENTIAL = booleanPreferencesKey("torrent_sequential")
        // Debrid
        val DEBRID_ENABLED = booleanPreferencesKey("debrid_enabled")
        val DEBRID_PROVIDER = stringPreferencesKey("debrid_provider")
        val DEBRID_API_KEY = stringPreferencesKey("debrid_api_key")
        // Guard
        val GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
        val GUARD_THERMAL = intPreferencesKey("guard_thermal_limit")
        val GUARD_BATTERY = intPreferencesKey("guard_battery_limit")
        val GUARD_STORAGE = intPreferencesKey("guard_storage_limit")
        // Webhook
        val WEBHOOK_ENABLED = booleanPreferencesKey("webhook_enabled")
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val WEBHOOK_SECRET = stringPreferencesKey("webhook_secret")
        // Tunnel
        val TUNNEL_ENABLED = booleanPreferencesKey("tunnel_enabled")
        val TUNNEL_PROVIDER = stringPreferencesKey("tunnel_provider")
        // MCP permissions
        val MCP_PRIVACY = booleanPreferencesKey("mcp_privacy_mode")
        val MCP_TOOLS_DISABLED = stringSetPreferencesKey("mcp_tools_disabled")
        // Schedule
        val SCHED_ENABLED = booleanPreferencesKey("sched_enabled")
        val SCHED_CRON = stringPreferencesKey("sched_cron")
        val SCHED_WIFI = booleanPreferencesKey("sched_wifi_only")
        val SCHED_CHARGING = booleanPreferencesKey("sched_charging_only")
        val SCHED_BATTERY_MIN = intPreferencesKey("sched_battery_min")
        val WATCHDOG_INTERVAL_SEC = intPreferencesKey("watchdog_interval_sec")
        val TORRENT_MIN_SEED_WAIT_SEC = intPreferencesKey("torrent_min_seed_wait_sec")
        val FORCE_HTTPS_REDIRECT = booleanPreferencesKey("force_https_redirect")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            port = (p[Keys.PORT] ?: 8080).coerceIn(1024, 65535),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            concurrency = (p[Keys.CONCURRENCY] ?: SettingsConstraints.DEFAULT_CONCURRENCY)
                .coerceIn(SettingsConstraints.CONCURRENCY_MIN, SettingsConstraints.CONCURRENCY_MAX),
            autoStart = p[Keys.AUTO_START] ?: true,
            notifications = p[Keys.NOTIFICATIONS] ?: true,
            webAuthEnabled = p[Keys.WEB_AUTH] ?: false,
            webUser = p[Keys.WEB_USER] ?: "droidrelay",
            webPassword = p[Keys.WEB_PASS] ?: "",
            speedLimitKbps = (p[Keys.SPEED_LIMIT] ?: 0).coerceAtLeast(0),
            maxDownloadBps = (p[Keys.MAX_DOWNLOAD_BPS] ?: 0L).coerceAtLeast(0),
            maxUploadBps = (p[Keys.MAX_UPLOAD_BPS] ?: 0L).coerceAtLeast(0),
            allowedIps = p[Keys.ALLOWED_IPS] ?: emptySet(),
            accessScope = runCatching { AccessScope.valueOf(p[Keys.ACCESS_SCOPE] ?: AccessScope.SUBNET_ONLY.name) }
                .getOrDefault(AccessScope.SUBNET_ONLY),
            torrentSavePath = p[Keys.TORRENT_SAVE_PATH] ?: "",
            torrentUploadLimit = (p[Keys.TORRENT_UPLOAD_LIMIT] ?: SettingsConstraints.DEFAULT_TORRENT_UPLOAD_KBPS).toLong().coerceAtLeast(0),
            torrentDownloadLimit = (p[Keys.TORRENT_DOWNLOAD_LIMIT] ?: 0).toLong().coerceAtLeast(0),
            torrentMaxActive = (p[Keys.TORRENT_MAX_ACTIVE] ?: 3).coerceIn(1, 10),
            torrentSeedRatio = (p[Keys.TORRENT_SEED_RATIO] ?: 200).toInt().coerceIn(0, 1000) / 100f,
            torrentDhtEnabled = p[Keys.TORRENT_DHT] ?: true,
            torrentPexEnabled = p[Keys.TORRENT_PEX] ?: true,
            torrentListenPort = (p[Keys.TORRENT_LISTEN_PORT] ?: 6881).coerceIn(1024, 65535),
            torrentSequentialDownload = p[Keys.TORRENT_SEQUENTIAL] ?: false,
            debridEnabled = p[Keys.DEBRID_ENABLED] ?: false,
            debridProvider = p[Keys.DEBRID_PROVIDER] ?: "",
            debridApiKey = p[Keys.DEBRID_API_KEY] ?: "",
            guardEnabled = p[Keys.GUARD_ENABLED] ?: false,
            guardThermalLimit = (p[Keys.GUARD_THERMAL] ?: 50).coerceIn(50, 70),
            guardBatteryLimit = (p[Keys.GUARD_BATTERY] ?: 20).coerceIn(5, 50),
            guardStorageLimit = (p[Keys.GUARD_STORAGE] ?: 90).coerceIn(50, 99),
            webhookEnabled = p[Keys.WEBHOOK_ENABLED] ?: false,
            webhookUrl = p[Keys.WEBHOOK_URL] ?: "",
            webhookSecret = p[Keys.WEBHOOK_SECRET] ?: "",
            tunnelEnabled = p[Keys.TUNNEL_ENABLED] ?: false,
            tunnelProvider = p[Keys.TUNNEL_PROVIDER] ?: "",
            mcpPrivacyMode = p[Keys.MCP_PRIVACY] ?: false,
            mcpToolsDisabled = p[Keys.MCP_TOOLS_DISABLED] ?: emptySet(),
            scheduleEnabled = p[Keys.SCHED_ENABLED] ?: false,
            scheduleCron = p[Keys.SCHED_CRON] ?: "",
            scheduleWifiOnly = p[Keys.SCHED_WIFI] ?: true,
            scheduleChargingOnly = p[Keys.SCHED_CHARGING] ?: false,
            scheduleBatteryMin = (p[Keys.SCHED_BATTERY_MIN] ?: 30).coerceIn(5, 100),
            watchdogIntervalSec = (p[Keys.WATCHDOG_INTERVAL_SEC] ?: 60).coerceIn(15, 3600),
            torrentMinSeedWaitSec = (p[Keys.TORRENT_MIN_SEED_WAIT_SEC] ?: 0).coerceAtLeast(0),
            forceHttpsRedirect = p[Keys.FORCE_HTTPS_REDIRECT] ?: false,
        )
    }

    /** 시작 직후 1회 동기 로드 (서비스 자동시작 판단용) */
    fun firstBlocking(): AppSettings = kotlinx.coroutines.runBlocking { settings.first() }

    suspend fun setPort(v: Int) =
        context.settingsDataStore.edit { it[Keys.PORT] = v.coerceIn(1024, 65535) }

    suspend fun setThemeMode(m: ThemeMode) =
        context.settingsDataStore.edit { it[Keys.THEME] = m.name }

    suspend fun setDynamicColor(b: Boolean) =
        context.settingsDataStore.edit { it[Keys.DYNAMIC] = b }

    suspend fun setConcurrency(n: Int) =
        context.settingsDataStore.edit {
            it[Keys.CONCURRENCY] = n.coerceIn(SettingsConstraints.CONCURRENCY_MIN, SettingsConstraints.CONCURRENCY_MAX)
        }

    suspend fun setAutoStart(b: Boolean) =
        context.settingsDataStore.edit { it[Keys.AUTO_START] = b }

    suspend fun setNotifications(b: Boolean) =
        context.settingsDataStore.edit { it[Keys.NOTIFICATIONS] = b }

    suspend fun setWebAuth(enabled: Boolean, user: String, pass: String) =
        context.settingsDataStore.edit {
            it[Keys.WEB_AUTH] = enabled
            if (user.isNotBlank()) it[Keys.WEB_USER] = user.trim()
            if (pass.isNotBlank()) it[Keys.WEB_PASS] = pass
        }

    suspend fun setSpeedLimit(kbps: Int) =
        context.settingsDataStore.edit { it[Keys.SPEED_LIMIT] = kbps.coerceAtLeast(0) }

    suspend fun setMaxDownloadBps(bps: Long) =
        context.settingsDataStore.edit { it[Keys.MAX_DOWNLOAD_BPS] = bps.coerceAtLeast(0) }

    suspend fun setMaxUploadBps(bps: Long) =
        context.settingsDataStore.edit { it[Keys.MAX_UPLOAD_BPS] = bps.coerceAtLeast(0) }

    suspend fun setWatchdogIntervalSec(sec: Int) =
        context.settingsDataStore.edit { it[Keys.WATCHDOG_INTERVAL_SEC] = sec.coerceIn(15, 3600) }

    suspend fun setTorrentMinSeedWaitSec(sec: Int) =
        context.settingsDataStore.edit { it[Keys.TORRENT_MIN_SEED_WAIT_SEC] = sec.coerceAtLeast(0) }

    suspend fun setForceHttpsRedirect(b: Boolean) =
        context.settingsDataStore.edit { it[Keys.FORCE_HTTPS_REDIRECT] = b }

    suspend fun addAllowedIp(ip: String) =
        context.settingsDataStore.edit { it[Keys.ALLOWED_IPS] = (it[Keys.ALLOWED_IPS] ?: emptySet()) + ip }

    suspend fun removeAllowedIp(ip: String) =
        context.settingsDataStore.edit { it[Keys.ALLOWED_IPS] = (it[Keys.ALLOWED_IPS] ?: emptySet()) - ip }

    suspend fun setAccessScope(scope: AccessScope) =
        context.settingsDataStore.edit { it[Keys.ACCESS_SCOPE] = scope.name }

    suspend fun setTorrentSavePath(path: String) =
        context.settingsDataStore.edit { it[Keys.TORRENT_SAVE_PATH] = path }

    suspend fun setTorrentUploadLimit(kbps: Int) =
        context.settingsDataStore.edit { it[Keys.TORRENT_UPLOAD_LIMIT] = kbps.coerceAtLeast(0) }

    suspend fun setTorrentDownloadLimit(kbps: Int) =
        context.settingsDataStore.edit { it[Keys.TORRENT_DOWNLOAD_LIMIT] = kbps.coerceAtLeast(0) }

    suspend fun setTorrentMaxActive(n: Int) =
        context.settingsDataStore.edit { it[Keys.TORRENT_MAX_ACTIVE] = n.coerceIn(1, 10) }

    suspend fun setTorrentSeedRatio(ratio: Float) =
        context.settingsDataStore.edit { it[Keys.TORRENT_SEED_RATIO] = (ratio * 100).toInt().coerceIn(0, 1000) }

    suspend fun setTorrentDhtEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.TORRENT_DHT] = enabled }

    suspend fun setTorrentPexEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.TORRENT_PEX] = enabled }

    suspend fun setTorrentListenPort(port: Int) =
        context.settingsDataStore.edit { it[Keys.TORRENT_LISTEN_PORT] = port.coerceIn(1024, 65535) }

    suspend fun setTorrentSequentialDownload(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.TORRENT_SEQUENTIAL] = enabled }

    // Debrid setters
    suspend fun setDebridEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.DEBRID_ENABLED] = enabled }

    suspend fun setDebridProvider(provider: String) =
        context.settingsDataStore.edit { it[Keys.DEBRID_PROVIDER] = provider }

    suspend fun setDebridApiKey(key: String) =
        context.settingsDataStore.edit { it[Keys.DEBRID_API_KEY] = key }

    // Guard setters
    suspend fun setGuardEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.GUARD_ENABLED] = enabled }

    suspend fun setGuardThermalLimit(limit: Int) =
        context.settingsDataStore.edit { it[Keys.GUARD_THERMAL] = limit.coerceIn(50, 70) }

    suspend fun setGuardBatteryLimit(limit: Int) =
        context.settingsDataStore.edit { it[Keys.GUARD_BATTERY] = limit.coerceIn(5, 50) }

    suspend fun setGuardStorageLimit(limit: Int) =
        context.settingsDataStore.edit { it[Keys.GUARD_STORAGE] = limit.coerceIn(50, 99) }

    // Webhook setters
    suspend fun setWebhookEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.WEBHOOK_ENABLED] = enabled }

    suspend fun setWebhookUrl(url: String) =
        context.settingsDataStore.edit { it[Keys.WEBHOOK_URL] = url }

    suspend fun setWebhookSecret(secret: String) =
        context.settingsDataStore.edit { it[Keys.WEBHOOK_SECRET] = secret }

    // Tunnel setters
    suspend fun setTunnelEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.TUNNEL_ENABLED] = enabled }

    suspend fun setTunnelProvider(provider: String) =
        context.settingsDataStore.edit { it[Keys.TUNNEL_PROVIDER] = provider }

    // MCP permission setters
    suspend fun setMcpPrivacyMode(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.MCP_PRIVACY] = enabled }

    suspend fun setMcpToolDisabled(toolName: String, disabled: Boolean) =
        context.settingsDataStore.edit {
            val current = it[Keys.MCP_TOOLS_DISABLED] ?: emptySet()
            it[Keys.MCP_TOOLS_DISABLED] = if (disabled) current + toolName else current - toolName
        }

    // Schedule setters
    suspend fun setScheduleEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.SCHED_ENABLED] = enabled }

    suspend fun setScheduleCron(cron: String) =
        context.settingsDataStore.edit { it[Keys.SCHED_CRON] = cron }

    suspend fun setScheduleWifiOnly(wifiOnly: Boolean) =
        context.settingsDataStore.edit { it[Keys.SCHED_WIFI] = wifiOnly }

    suspend fun setScheduleChargingOnly(chargingOnly: Boolean) =
        context.settingsDataStore.edit { it[Keys.SCHED_CHARGING] = chargingOnly }

    suspend fun setScheduleBatteryMin(min: Int) =
        context.settingsDataStore.edit { it[Keys.SCHED_BATTERY_MIN] = min.coerceIn(5, 100) }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
