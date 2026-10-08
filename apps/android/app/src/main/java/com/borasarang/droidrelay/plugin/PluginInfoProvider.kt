package com.borasarang.droidrelay.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.borasarang.droidrelay.relay.SettingsRepository

/**
 * T-1096 — 메타데이터 Provider (SDK §3 L2).
 *
 * 한 명령으로 표시 정보를 전부 준다 (logcat 4KB 상한 회피 채널):
 *
 * ```bash
 * adb -s <serial> shell content query --uri content://com.borasarang.droidrelay.plugin/info
 * ```
 *
 * 단일 행. 쿼리 실패·부재 시 소비자는 L1 폴백한다 (죽지 않는다).
 */
class PluginInfoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val appCtx = context?.applicationContext ?: return null
        val allowed = runCatching {
            SettingsRepository.get(appCtx).firstBlocking().pluginAllowed
        }.getOrDefault(true)
        val appVersion = runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
        }.getOrNull() ?: "-"
        return MatrixCursor(COLUMNS).apply {
            addRow(
                arrayOf(
                    "DroidRelay",
                    "기기 내 다운로드·토렌트 관제",
                    PluginContract.PLUGIN_VERSION.toString(),
                    appVersion,
                    allowed.toString(),
                    "", // iconBase64 — 비어 있으면 소비자 폴백 아이콘
                    PluginContract.actionsJson(),
                ),
            )
        }
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/plugin-info"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val COLUMNS: Array<String> = arrayOf(
            "label",
            "description",
            "contractVersion",
            "appVersion",
            "allowed",
            "iconBase64",
            "actionsJson",
        )
    }
}
