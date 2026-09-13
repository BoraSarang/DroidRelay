package com.borasarang.droidrelay.relay

import io.ktor.http.ContentType

/**
 * 웹 파비콘/북마크 아이콘 경로 매핑 (T-1007).
 * 대시보드(`/`)+디버그(`/debug`) 탭·북마크·홈화면 아이콘용 정적 6종.
 * 민감 정보가 없어 Basic Auth·게스트·기기승인 exempt 대상이다.
 */
internal object Favicon {
    data class Asset(val file: String, val contentType: ContentType)

    private val table: Map<String, Asset> = mapOf(
        "/favicon.svg" to Asset("favicon.svg", ContentType.Image.SVG),
        "/favicon-16.png" to Asset("favicon-16.png", ContentType.Image.PNG),
        "/favicon-32.png" to Asset("favicon-32.png", ContentType.Image.PNG),
        "/apple-touch-icon.png" to Asset("apple-touch-icon.png", ContentType.Image.PNG),
        "/site.webmanifest" to Asset("site.webmanifest", ContentType.parse("application/manifest+json")),
        // 구형 브라우저 자동 요청 대응 — 별도 ico 인코딩 없이 32px PNG 바이트 재사용
        "/favicon.ico" to Asset("favicon-32.png", ContentType.parse("image/x-icon")),
    )

    val paths: Set<String> get() = table.keys

    fun isFavicon(path: String): Boolean = path in table

    fun assetFor(path: String): Asset? = table[path]
}
