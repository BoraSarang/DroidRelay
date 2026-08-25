package com.borasarang.droidrelay.relay

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val concurrency: Int = 2,
    val autoStart: Boolean = true,
    val notifications: Boolean = true,
    val webAuthEnabled: Boolean = false,
    val webUser: String = "droidrelay",
    val webPassword: String = "",
    val speedLimitKbps: Int = 0,
    val allowedIps: Set<String> = emptySet(),
    val accessScope: AccessScope = AccessScope.SUBNET_ONLY,
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
        val ALLOWED_IPS = stringSetPreferencesKey("allowed_ips")
        val ACCESS_SCOPE = stringPreferencesKey("access_scope")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            port = (p[Keys.PORT] ?: 8080).coerceIn(1024, 65535),
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            concurrency = (p[Keys.CONCURRENCY] ?: 2).coerceIn(1, 4),
            autoStart = p[Keys.AUTO_START] ?: true,
            notifications = p[Keys.NOTIFICATIONS] ?: true,
            webAuthEnabled = p[Keys.WEB_AUTH] ?: false,
            webUser = p[Keys.WEB_USER] ?: "droidrelay",
            webPassword = p[Keys.WEB_PASS] ?: "",
            speedLimitKbps = (p[Keys.SPEED_LIMIT] ?: 0).coerceAtLeast(0),
            allowedIps = p[Keys.ALLOWED_IPS] ?: emptySet(),
            accessScope = runCatching { AccessScope.valueOf(p[Keys.ACCESS_SCOPE] ?: AccessScope.SUBNET_ONLY.name) }
                .getOrDefault(AccessScope.SUBNET_ONLY),
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
        context.settingsDataStore.edit { it[Keys.CONCURRENCY] = n.coerceIn(1, 4) }

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

    suspend fun addAllowedIp(ip: String) =
        context.settingsDataStore.edit { it[Keys.ALLOWED_IPS] = (it[Keys.ALLOWED_IPS] ?: emptySet()) + ip }

    suspend fun removeAllowedIp(ip: String) =
        context.settingsDataStore.edit { it[Keys.ALLOWED_IPS] = (it[Keys.ALLOWED_IPS] ?: emptySet()) - ip }

    suspend fun setAccessScope(scope: AccessScope) =
        context.settingsDataStore.edit { it[Keys.ACCESS_SCOPE] = scope.name }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
