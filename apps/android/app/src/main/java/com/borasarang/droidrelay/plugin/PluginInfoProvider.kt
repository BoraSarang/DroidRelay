package com.borasarang.droidrelay.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import com.borasarang.droidrelay.R
import com.borasarang.droidrelay.relay.SettingsRepository
import java.io.ByteArrayOutputStream

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
                    iconBase64(appCtx),
                    PluginContract.actionsJson(),
                ),
            )
        }
    }

    /**
     * 런처 적응형 아이콘을 96px PNG base64 한 줄로 렌더한다 (SDK §3 iconBase64).
     * 벡터 리소스라 런타임에 그린다. 실패하면 빈값 → 소비자 폴백 아이콘.
     */
    private fun iconBase64(appCtx: android.content.Context): String = runCatching {
        val sizePx = 96
        val bg = appCtx.getDrawable(R.drawable.ic_launcher_background) ?: return ""
        val fg = appCtx.getDrawable(R.drawable.ic_launcher_foreground) ?: return ""
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        bg.setBounds(0, 0, sizePx, sizePx)
        bg.draw(canvas)
        // 적응형 안전영역 72/108dp를 중앙에 — 바깥 링이 잘리지 않게
        val inset = (sizePx * (1 - 72.0 / 108.0) / 2).toInt()
        fg.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        fg.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

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
