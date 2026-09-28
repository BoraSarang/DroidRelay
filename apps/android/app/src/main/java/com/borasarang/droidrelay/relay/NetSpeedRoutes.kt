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
 * 셸 권한이 있어야만 가능한 경로라 앱 안에서 쓸 수 없다.
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
