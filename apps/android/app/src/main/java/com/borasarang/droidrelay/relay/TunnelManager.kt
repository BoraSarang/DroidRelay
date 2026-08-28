package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress

/**
 * 터널 매니저 — 원격 접속 지원.
 * Tailscale / Cloudflare Tunnel 연동.
 *
 * 현재 구현: 터널 상태 관리 + 연결 테스트.
 * 실제 터널 바이너리는 ARM64 번들 (추후 구현).
 */
class TunnelManager(private val context: Context) {

    private val TAG = "Tunnel"
    @Volatile var isRunning = false
        private set
    @Volatile var currentUrl: String? = null
        private set

    /**
     * 터널 시작 (설정된 프로바이더에 따라).
     */
    suspend fun start(settings: AppSettings): TunnelResult = withContext(Dispatchers.IO) {
        if (!settings.tunnelEnabled) {
            return@withContext TunnelResult(false, "터널 비활성화")
        }

        if (settings.tunnelProvider.isBlank()) {
            return@withContext TunnelResult(false, "터널 프로바이더 미설정")
        }

        val provider = TunnelProvider.fromString(settings.tunnelProvider)
        if (provider == null) {
            return@withContext TunnelResult(false, "지원하지 않는 프로바이더: ${settings.tunnelProvider}")
        }

        DebugLogger.i(TAG, "터널 시작 시도 provider=${provider.name}")

        when (provider) {
            TunnelProvider.TAILSCALE -> startTailscale()
            TunnelProvider.CLOUDFLARE -> startCloudflareTunnel()
        }
    }

    /**
     * 터널 중지.
     */
    fun stop() {
        isRunning = false
        currentUrl = null
        DebugLogger.i(TAG, "터널 중지")
    }

    /**
     * 터널 상태 조회.
     */
    fun getStatus(): TunnelStatus {
        // Tailscale 설치 여부 + 100.x.x.x 인터페이스 감지
        val tailscaleInstalled = isTailscaleInstalled()
        val tailscaleIp = findTailscaleIp()
        val cloudflaredBinary = File(context.filesDir, "cloudflared").exists()
        DebugLogger.d(TAG, "상태 조회 installed=$tailscaleInstalled ip=$tailscaleIp cfBin=$cloudflaredBinary running=$isRunning")

        return TunnelStatus(
            running = isRunning || tailscaleIp != null,
            url = currentUrl ?: tailscaleIp?.let { "http://$it:8080" },
            tailscaleInstalled = tailscaleInstalled,
            tailscaleConnected = tailscaleIp != null,
            cloudflaredAvailable = cloudflaredBinary,
        )
    }

    /**
     * Tailscale 앱 설치 여부 확인.
     */
    private fun isTailscaleInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("io.tailscale.tailscale", 0)
            DebugLogger.d(TAG, "Tailscale 앱 감지됨")
            true
        } catch (_: Exception) {
            val binaryExists = File(context.filesDir, "tailscale").exists()
            DebugLogger.d(TAG, "Tailscale 앱 미설정 바이너리=$binaryExists")
            binaryExists
        }
    }

    /**
     * 네트워크 인터페이스에서 Tailscale IP (100.x.x.x) 감지.
     */
    private fun findTailscaleIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            DebugLogger.d(TAG, "네트워크 인터페이스 ${interfaces.size}개 확인")
            interfaces.asSequence().flatMap { nif ->
                val addrs = nif.inetAddresses?.asSequence() ?: emptySequence()
                addrs
            }.firstOrNull { addr ->
                val match = addr is Inet4Address && !addr.isLoopbackAddress && addr.hostAddress?.startsWith("100.") == true
                if (match) DebugLogger.i(TAG, "Tailscale IP 감지: ${addr.hostAddress}")
                match
            }?.hostAddress
        } catch (_: Exception) { null }
    }

    /**
     * 외부에서 터널 URL 주입 (실제 터널 프로세스에서 갱신).
     */
    fun setUrl(url: String?) {
        currentUrl = url
        isRunning = url != null
        DebugLogger.i(TAG, "터널 URL 갱신 url=$url")
    }

    private fun startTailscale(): TunnelResult {
        // Tailscale 바이너리 존재 확인
        val binary = File(context.filesDir, "tailscale")
        if (!binary.exists()) {
            DebugLogger.w(TAG, "Tailscale 바이너리 없음 — 설정 안내")
            return TunnelResult(false, "Tailscale 바이너리가 없습니다. 설정에서 설치하세요.")
        }

        // Tailscale 상태 확인
        return try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec(arrayOf(binary.toString(), "status"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.contains("Connected")) {
                isRunning = true
                // Tailscale IP 추출 시도
                val ip = extractTailscaleIp(output)
                currentUrl = ip?.let { "http://$it:8080" }
                TunnelResult(true, currentUrl ?: "연결됨")
            } else {
                TunnelResult(false, "Tailscale 연결 안됨")
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Tailscale 시작 실패", e)
            TunnelResult(false, "Tailscale 시작 실패: ${e.message}")
        }
    }

    private fun startCloudflareTunnel(): TunnelResult {
        // Cloudflare Tunnel (cloudflared) 존재 확인
        val binary = File(context.filesDir, "cloudflared")
        if (!binary.exists()) {
            DebugLogger.w(TAG, "cloudflared 바이너리 없음 — 설정 안내")
            return TunnelResult(false, "cloudflared 바이너리가 없습니다. 설정에서 설치하세요.")
        }

        return try {
            // 로컬 포트로 터널 시작
            val runtime = Runtime.getRuntime()
            val process = runtime.exec(arrayOf(
                binary.toString(),
                "tunnel",
                "--url", "http://localhost:8080",
                "--no-autoupdate",
            ))

            // 출력에서 URL 추출 (비동기)
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("trycloudflare.com")) {
                            val url = line.substringAfter("https://").substringBefore(" ")
                            currentUrl = "https://$url"
                            isRunning = true
                            DebugLogger.i(TAG, "Cloudflare 터널 URL: $currentUrl")
                        }
                    }
                } catch (_: Exception) { }
            }.start()

            TunnelResult(true, "Cloudflare 터널 시작됨")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Cloudflare 터널 시작 실패", e)
            TunnelResult(false, "Cloudflare 터널 시작 실패: ${e.message}")
        }
    }

    private fun extractTailscaleIp(output: String): String? {
        // Tailscale status 출력에서 IP 추출
        val lines = output.lines()
        for (line in lines) {
            if (line.contains("100.") && line.contains("tun")) {
                val parts = line.trim().split("\\s+".toRegex())
                for (part in parts) {
                    if (part.matches(Regex("^100\\..*"))) return part
                }
            }
        }
        return null
    }
}

enum class TunnelProvider(val displayName: String) {
    TAILSCALE("Tailscale"),
    CLOUDFLARE("Cloudflare Tunnel");

    companion object {
        fun fromString(value: String): TunnelProvider? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}

data class TunnelResult(val success: Boolean, val message: String)
data class TunnelStatus(
    val running: Boolean,
    val url: String?,
    val tailscaleInstalled: Boolean = false,
    val tailscaleConnected: Boolean = false,
    val cloudflaredAvailable: Boolean = false,
)
