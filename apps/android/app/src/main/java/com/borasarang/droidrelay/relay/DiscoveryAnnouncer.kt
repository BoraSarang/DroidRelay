package com.borasarang.droidrelay.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * **"나 여기있소" UDP 발신기** (T-1090).
 *
 * 서버가 켜져 있는 동안 [DiscoveryBeacon.INTERVAL_SECONDS] 초마다
 * 브로드캐스트로 자기 주소를 알린다. Mac 의 `ServerDiscovery` 가 이걸 받으면
 * **스캔 없이** 바로 붙는다.
 *
 * ## ★ 설계 원칙 — 실패해도 아무것도 깨지지 않는다
 *
 * 이 클래스는 **탐색에 "추가"되는 경로**다. **유일한** 경로가 아니다.
 * - 소켓을 못 여도 → 예외를 격리하고 다음 주기에 다시 시도
 * - LAN 주소가 없으면 → **건너뛴다.** (네트워크가 없는 상태다. 고장이 아니다)
 * - 발신이 전혀 안 되면 → Mac 은 기존 3단계 탐색으로 그대로 붙는다
 *
 * → **안드로이드 권한·펌웨어·공유기에 막혀도 기능 손실이 없다.**
 * "느려질 뿐 깨지지 않는다" 가 이 기능의 성격이다.
 *
 * ## 왜 발신만 하고 열지는 않나
 *
 * Mac 이 **질문**하고 폰이 **대답**하는 구조(질문-응답)면 폰이 포트를 열어야 하고,
 * 그 포트가 **방화벽에 막히면** 기능이 죽는다.
 * 반대로 폰이 **일방적으로 알리는** 구조면 폰은 **소켓을 열고 있을 필요가 없다.**
 * → **보안에도 실패에도 유리하다.** 그래서 일방 발신이다.
 */
class DiscoveryAnnouncer(
    private val scope: CoroutineScope,
    private val version: String,
) {
    private var job: Job? = null
    private var lastError: String? = null

    /** 마지막 발신 실패 사유 — 진단용. 정상이라면 `null`. */
    fun errorOrNull(): String? = lastError

    /**
     **주기 발신을 시작한다** — 이미 돌고 있으면 아무것도 하지 않는다.
     *
     * ## 중복 등록을 막는 이유
     *
     * 서버 재시작·설정 변경 때마다 이게 불릴 수 있다. 두 루프가 동시에 돌면
     **트래픽이 2배**가 되고, 어느 것이 화면의 상태인지 알 수 없다.
     */
    fun start(port: Int, httpsPort: Int, https: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                announceOnce(port, httpsPort, https)
                delay(DiscoveryBeacon.INTERVAL_SECONDS * 1000)
            }
        }
        DebugLogger.i(TAG, "발신 시작 — ${DiscoveryBeacon.INTERVAL_SECONDS}초 주기 포트 $port")
    }

    /** 발신 중지 — 서버가 내려갈 때. */
    fun stop() {
        job?.cancel()
        job = null
        DebugLogger.i(TAG, "발신 중지")
    }

    /** **지금 발신 중인가** — 진단·테스트용. */
    fun isRunning(): Boolean = job?.isActive == true

    /**
     * **1회 발신.**
     *
     * ## 왜 `runCatching` 이 전부인가
     *
     * 서버 메인 루프에서 **throw 하면 프로세스가 죽는다.** 이건 부가 기능이라
     * 서버를 죽일 권한이 없다. → **모든 예외를 격리**하고 다음 주기에 재시도한다.
     */
    fun announceOnce(port: Int, httpsPort: Int, https: Boolean) {
        runCatching {
            // **LAN 주소가 없으면 발신할 곳이 없다.** 핫스팟이 꺼진 상태다.
            // 여기서 **오류를 만들지 않는다** — "지금은 없다" 와 "고장" 은 다르다.
            val ip = lanAddress()
            if (ip == null) {
                lastError = null
                DebugLogger.d(TAG, "LAN 주소 없음 — 발신 건너뜀 (네트워크 미연결)")
                return
            }
            val body = DiscoveryBeacon.payload(version, ip, port, httpsPort, https)
            val bytes = body.toByteArray(Charsets.UTF_8)

            // **바인딩 없이 발신한다** — 포트를 열어 두지 않으므로 방화벽에 막히지 않는다.
            DatagramSocket().use { sock ->
                // **전역 브로드캐스트 + 서브넷 브로드캐스트를 모두 시도한다.**
                // 공유기·OS 중 하나는 255.255.255.255 를 버리고 서브넷 주소만 통과시키는 경우가 있다.
                val targets = buildList {
                    add(InetAddress.getByName(BROADCAST))
                    subnetBroadcast(ip)?.let { add(it) }
                }
                var sent = 0
                for (t in targets) {
                    runCatching { sock.send(DatagramPacket(bytes, bytes.size, t, DiscoveryBeacon.PORT)) }
                        .onSuccess { sent++ }
                        .onFailure { DebugLogger.d(TAG, "브로드캐스트 실패 ${t.hostAddress}: ${it.message}") }
                }
                lastError = null
                DebugLogger.i(TAG, "[FEATURE] 나 여기있소 발신 $ip:$port → ${sent}/${targets.size}개 경로")
            }
        }.onFailure {
            // **한 번 실패해도 루프는 살아 있다.** 다음 주기에 다시 시도한다.
            lastError = it.message
            DebugLogger.w(TAG, "발신 실패(무시하고 재시도): ${it.message}")
        }
    }

    /**
     * 서브넷 브로드캐스트 주소 — `10.38.120.211` + `/24` → `10.38.120.255`.
     *
     * 넷마스크를 모르면 **저장하지 않는다.** 지어내면 잘못된 주소로 쏜다.
     * → **모르면 전역 브로드캐스트만 쓴다**(위에서 이미 시도한다).
     */
    private fun subnetBroadcast(ip: String): InetAddress? = runCatching {
        val parts = ip.split(".").map { it.toInt() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
    }.getOrNull()

    companion object {
        private const val TAG = "Beacon"
        private const val BROADCAST = "255.255.255.255"
    }
}
