package com.borasarang.droidrelay.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.borasarang.droidrelay.relay.DebugLogger

/**
 * 주소 클릭 시 외부 브라우저로 열기 (T-1000).
 * 실패(브라우저 없음·잘못된 URI 등)해도 크래시 없이 false 반환.
 */
fun openUrlInBrowser(ctx: Context, url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        DebugLogger.i("UI", "[FEATURE] 주소 브라우저 열기 → $url")
        true
    }.getOrElse { e ->
        DebugLogger.e("UI", "브라우저 열기 실패 url=$url err=${e.message}")
        false
    }
}
