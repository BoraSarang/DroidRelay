package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.TrafficStats
import android.os.Build
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.json.JSONObject

/** 기기(폰 전체) 트래픽 카운터. **누적 바이트이며, 클라이언트가 시간 차로 나눈다.** */
internal data class DeviceTraffic(val rx: Long, val tx: Long, val supported: Boolean)

/**
 * 기기(폰 전체) 네트워크 트래픽 API.
 *
 * ## 왜 이게 필요한가
 *
 * 앱이 쓰는 트래픽은 이미 잡+토렌트로 알 수 있다. 그건 **이 앱이 발신·수신한 양**이고,
 * macOS 메뉴바의 "기기" 열은 **폰 전체**를 뜻한다.
 *
 * ## `/proc/net/dev` 는 못 쓴다
 *
 * 인터페이스별 카운터를 그 파일에서 직접 읽는 게 가장 정확하지만, **앱 uid 로는
 * SELinux 가 막는다**(실측: `run-as … cat /proc/net/dev` → `Permission denied`).
 * `/sys/class/net/wlan0/statistics/tx_bytes` 도 마찬가지다(실측: 동일하게 denied).
 * 셸 권한이 있어야만 가능한 경로라 앱 안에서 쓸 수 없다.
 *
 * ## 시도하고 **실패한** 두 경로 — 기록을 남긴다 (2026-09-29)
 *
 * ### ① `NetworkStatsManager.querySummaryForDevice` — 권한이 없다
 *
 * SELinux 제한 없이 **기기 전체**를 읽는다고 보여 선택했지만, 실측 결과 **불가능**이었다:
 * ```
 * SecurityException: Network stats history of uid -1 is forbidden for caller 10375
 * ```
 * `uid -1`(전체) 조회는 **시스템 권한**이 필요하다. `PACKAGE_USAGE_STATS` grant 로도
 * 안 되고, **시그니처에 uid 인자가 없어** 내 uid 로 바꿔 읽는 우회로도 없다.
 * → **일반 앱에서 성립하지 않는다.**
 *
 * ### ② 서버가 직접 1초마다 폴링 — 정산은 폴링 주기와 무관하다
 *
 * "클라이언트가 5초마다 물어보니까 그 사이에 정산이 끼는 거다" 는 가설을 세우고
 * **서버가 1초마다 직접 폴링**하게 만들어 실측했다. **해결되지 않았다.**
 *
 * ```
 * 폴링 주기      클라이언트가 본 최대 증분
 * 1.00초              5,002,000 B/s
 * 0.20초              5,002,000 B/s
 * 0.05초              5,002,000 B/s
 * ```
 *
 * **정산은 네트워크 계층에서 일어나 폴링 주기와 무관하다.** 아무리 빨리 읽어도
 * 두 폴링 사이에 정산이 끼면 그대로 증분으로 나타난다.
 *
 * 증분에 상한을 두는 방법도 시도했지만, **진짜 대용량 다운로드까지 잘라내면서
 * 총량까지 틀린다.** 총량은 보존돼야 하므로 더 나쁘다.
 *
 * ### 남는 결론
 *
 * **정산 지연은 앱에서 제거할 수 없다.** 원천을 없앨 수 없으니 **표현에서 처리한다.**
 * → 클라이언트(macOS)가 **1회짜리 스파이크만 걸러낸다**(v0.47.0, M-23).
 * 원본 카운터는 **그대로 유지하고 지우지 않는다.**
 *
 * ## `TrafficStats.getNetworkStats()` 도 못 쓴다
 *
 * API 28 에 추가됐지만 **`android.jar` 에 없다** — hidden/system API 다(실측:
 * `javap android.net.TrafficStats` 에 목록 없음). 컴파일러가
 * `Unresolved reference` 로 막는다. 그래서 `getTotalRxBytes()` 로 간다.
 *
 * ## 왜 누적값을 보내는가
 *
 * 서버가 "초당 속도"로 나눠 보내면 **폴링 주기가 흔들릴 때 값이 출렁인다** — 같은
 * 트래픽이라도 1초 간격일 때와 5초 간격일 때의 표시가 다르다. 누적 카운터를 주고
 * 클라이언트가 **자신의 타이머로** 나누게 하면 간격이 얼마든 측정 오차가 0 이다.
 */
internal fun Route.netSpeedRoutes(context: Context, serverRef: RelayServer) {
    get("/api/net/speed") {
        val t = DeviceTrafficReader.read()
        call.respondText(
            JSONObject().apply {
                put("rxTotal", t.rx)
                put("txTotal", t.tx)
                put("supported", t.supported)
            }.toString(),
            ContentType.Application.Json
        )
    }
}

internal object DeviceTrafficReader {

    fun read(): DeviceTraffic = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+ (minSdk 26 이므로 항상 이 경로다).
            // **uid 를 넘기는 API 는 쓰지 않는다** — 그건 앱 자신의 양이라
            // "기기 전체" 가 아니다.
            DeviceTraffic(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), true)
        } else {
            @Suppress("DEPRECATION")
            DeviceTraffic(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), true)
        }
    } catch (_: SecurityException) {
        // 일부 기기(OEM)에서 READ_PHONE_STATE 를 요구한다.
        // **0 으로 대체하지 않고 "미지원" 을 알린다** — 클라이언트가
        // 빈 열을 만들지 않고 이유를 보여줄 수 있어야 한다.
        DeviceTraffic(0, 0, supported = false)
    }
}
