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

/**
 * 트래픽 범위. **요청이 없거나 모르는 값이면 `ALL` 로 본다** — 옛 동작 유지.
 *
 * ## 왜 세 구간인가 (M-28)
 *
 * M-27 은 두 구간(`external` / `all`) 이었다. 실측에서 결함이 드러났다 —
 * **핫스팟 ↔ 클라이언트 구간이 어느 쪽에도 제대로 안 잡혔다.**
 *
 * 통제 실험 (11MB 다운로드, 실제 전송 확인됨):
 * ```
 * swlan0      Δtx = 11,693,373   ← 실제로 클라이언트에 전송된 양 (11.36MB) ★ 일치
 * rmnet_data1 Δrx =    295,326   ← 셀룰러 경유 2.6%뿐
 * external    Δrx =          0   ← ★ 화면에 0 이 뜬다
 * ```
 *
 * `external` = `rmnet*`(셀룰러)만 세므로 **핫스팟 구간이 통째로 누락**된다.
 * 이 앱의 목적이 핫스팟으로 파일을 주고받는 것인데, **그게 안 보이는 지표**다.
 *
 * | 구간 | 세는 것 | 용도 |
 * |---|---|---|
 * | `EXTERNAL` | 활성 `rmnet*` (IPA 제외) | 폰이 **셀룰러로** 나간 양 |
 * | `HOTSPOT` | `swlan0` | **클라이언트와 핫스팟으로** 주고받은 양 |
 * | `ALL` | 모든 인터페이스 | 전체 |
 */
internal enum class TrafficScope(val raw: String) {
    /** 활성 셀룰러(`rmnet*`). 핫스팟 내부 제외. **기본값.** */
    EXTERNAL("external"),
    /** 핫스팟 AP(`swlan0`) — 클라이언트와 주고받은 양. */
    HOTSPOT("hotspot"),
    /** 모든 인터페이스. 핫스팟 내부 포함. */
    ALL("all");

    companion object {
        fun of(raw: String?): TrafficScope =
            entries.firstOrNull { it.raw == raw } ?: ALL
    }
}

internal object DeviceTrafficReader {

    /**
     * @Volatile — 이 폴링은 Ktor 워커 스레드에서 돌고 API 요청도 다른 스레드에서
     * 들어온다. **가시성 보장 없으면 1회 늦게 반영될 수 있다.**
     *
     * **리플렉션 Method 도 캐시한다** — 부를 때마다 클래스를 찾으면
     * 1초 폴링마다 reflection 비용을 다시 낸다.
     */
    @Volatile private var reflectRx: Method? = null
    @Volatile private var reflectTx: Method? = null
    @Volatile private var reflectChecked = false

    /**
     * **이 기기의 셀룰러 인터페이스 목록** — 이름이 기기마다 다르므로 고정하지 않는다.
     *
     * ## ★ 만료 시각을 둔다 (M-28)
     *
     * 이전엔 **영구 캐시**였다. 셀룰러가 재접속되면 인터페이스가 바뀐다
     * (`data1` → `data5` 등). 영구 캐시는 **죽은 이름을 계속 읽는다** —
     * 0 을 계속 반환해 속도가 0 으로 굳는다.
     *
     * → **30초마다 다시 찾는다.** 1초 폴링 중 `getNetworkInterfaces()` 는
     * 시스템 IPC 라 비용이 있다. **캐시가 목적인데 매번 부르면 캐시가 없다.**
     */
    @Volatile private var rmnetCache: IfaceCache? = null

    /** 핫스팟 AP 인터페이스 캐시 — 같은 만료 규칙. */
    @Volatile private var hotspotCache: IfaceCache? = null

    /** **캐시 + 그게 언제까지 유효한지.** 데이터와 만료를 한 쌍으로 다룬다. */
    private data class IfaceCache(val names: List<String>?, val expiresAt: Long) {
        fun alive(now: Long) = now < expiresAt
    }

    /** **30초** — 재접속 직후 어긋남은 허용한다(점진적). */
    private const val CACHE_TTL_MS = 30_000L

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
        return if (scope == TrafficScope.HOTSPOT) readHotspot(scope) else readExternal(scope)
    }

    /**
     * **`rmnet*` 합계** = "기기가 **셀룰러로** 나갔다 오는 양".
     *
     * ## 왜 합으로 빼지 않는가
     *
     * `total − swlan0` 로도 되지만 **뺄셈은 언제든 음수가 된다**
     * (카운터 리셋·인터페이스 추가·셀룰러 재접속). → **직접 읽는다.**
     */
    private fun readExternal(scope: TrafficScope): DeviceTraffic {
        val ifaces = rmnetIfaces()
        if (ifaces.isNullOrEmpty()) {
            return DeviceTraffic(0, 0, supported = false, scope = scope.raw,
                note = "셀룰러 인터페이스(rmnet*) 를 찾지 못했습니다")
        }
        return sum(ifaces, scope, "셀룰러 인터페이스(rmnet*) 를 찾지 못했습니다")
    }

    /**
     * **핫스팟 AP 인터페이스 합계** = "클라이언트와 주고받은 양". (M-28 신규)
     *
     * ## 왜 이 구간이 꼭 필요한가
     *
     * 실측: 11MB 를 클라이언트에 내려줬을 때 `swlan0 Δtx = 11,693,373` 이지만
     * `rmnet Δrx = 295,326` (2.6%) 뿐이었고 **`external` 은 0** 이었다.
     * → 이 앱의 **핵심 동작이 안 보이는 값**이었다.
     *
     * ## 이름이 기기마다 다르다
     *
     * 삼성 `swlan0` · AOSP `wlan1`(AP) / `wlan0`(STA 모드에선 STA).
     * **정확한 이름이 아니라 "핫스팟 AP 인 것"으로 판정**해야 하는데,
     * 앱은 `NetworkInterface` 로 IP 가 붙은 것만 본다.
     * → **패턴 후보를 넓게 잡고**, 그래도 없으면 **미지원으로 알린다.**
     */
    private fun readHotspot(scope: TrafficScope): DeviceTraffic {
        val ifaces = hotspotIfaces()
        if (ifaces.isNullOrEmpty()) {
            return DeviceTraffic(0, 0, supported = false, scope = scope.raw,
                note = "핫스팟 인터페이스(swlan0 등) 를 찾지 못했습니다 — 핫스팟을 켜면 표시됩니다")
        }
        return sum(ifaces, scope, "핫스팟 인터페이스를 읽지 못했습니다")
    }

    /**
     * **목록을 합산한다** — 두 구간이 같은 계산을 쓰므로 한 곳에 모은다.
     *
     * ## 왜 합쳤나
     *
     * `readExternal` 과 `readHotspot` 이 **같은 루프를 두 번 복사**하면
     * 한쪽만 고쳐진다. **실패 처리 규칙**(전부 실패만 미_SUPPORT)이 두 벌 생긴다.
     */
    private fun sum(ifaces: List<String>, scope: TrafficScope, notFound: String): DeviceTraffic {
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
     * **활성 셀룰러 인터페이스 목록** — 캐시 + 만료.
     *
     * ## ★ `rmnet_ipa0` 를 왜 뺀다 (M-28 실측)
     *
     * 처음엔 "누락된 40GB" 라 판단했다. **틀렸고, 실측이 뒤집었다.**
     * 같은 구간 tx:
     * ```
     * rmnet_ipa0  Δtx = 5,717,550 B
     * rmnet_data1 Δtx = 5,677,102 B   ← 99.2% 일치
     * ```
     * → `ipa0` 는 **쿨컴 IPA 오프로드 가상 장치**다. `data1` 을 **그대로 통과**시킨다.
     * `dumpsys netstats` 에도 **한 번도 안 나온다** — 시스템이 트래픽 경로로 안 쓴다.
     *
     * → **더하면 2배 계상이다.**
     *
     * ## 그래도 명시적으로 뺀다
     *
     * `NetworkInterface` 는 IP 없는 걸 안 보이므로 **지금은 자동으로 빠진다.**
     * 그러나 **"왜 빠지는지" 를 코드에 적어야** 다음 사람이 중복 계상을 고치지 않는다.
     * (규칙 4: 추측 금지 — 근거를 남긴다)
     */
    private fun rmnetIfaces(): List<String>? {
        val now = System.currentTimeMillis()
        rmnetCache?.takeIf { it.alive(now) }?.let { return it.names }
        val hit = try { NetworkInterfaceNames.all().filter(::isActiveCellular) } catch (e: Exception) { null }
        val c = IfaceCache(hit, now + CACHE_TTL_MS)
        rmnetCache = c
        return hit
    }

    /**
     * **활성 셀룰러 판정** — `rmnet` 계열이면서 **오프로드 장치는 아니다.**
     *
     * `ipa`(Inline Packet Architecture) = 하드웨어 오프로드용 가상 장치 → **제외**.
     */
    internal fun isActiveCellular(name: String): Boolean =
        name.startsWith("rmnet") && !OFFLOAD_MARKERS.any { name.contains(it) }

    /**
     * **오프로드 마커** — 실측된 `rmnet_ipa0` 을 잡는다.
     *
     * Qualcomm `ipa` · MTK `ccmni` 계열은 **이중 계상된다.**
     */
    private val OFFLOAD_MARKERS = listOf("ipa", "ccmni")

    /**
     * **핫스팟 AP 인터페이스 목록** — 캐시 + 만료.
     *
     * ## 판정 근거
     *
     * 앱은 `NetworkInterface` 로 **IP 가 붙은 것만** 본다. 핫스팟은 `swlan0` 에
     * IP 가 붙으므로 **보인다.** (실측 확인)
     *
     * 다만 기기마다 이름이 다르다. 삼성 `swlan0` · AOSP `wlan1` 등.
     * → **확실한 후보만** 잡는다. `wlan0` 은 STA 모드(폰이 클라이언트일 때)
     * 이므로 **동시에 잡으면 안 된다** — 없는 값을 만들어낸다.
     */
    private fun hotspotIfaces(): List<String>? {
        val now = System.currentTimeMillis()
        hotspotCache?.takeIf { it.alive(now) }?.let { return it.names }
        val names = try { NetworkInterfaceNames.all() } catch (e: Exception) { null }
        val hit = names?.filter(::isHotspotAp)
        val c = IfaceCache(hit, now + CACHE_TTL_MS)
        hotspotCache = c
        return hit
    }

    /**
     * **핫스팟 AP 판정** — **AP 모드에서 쓰이는 이름만** 인정한다.
     *
     * ## 왜 `wlan` 을 통째로 받지 않는가
     *
     * `wlan0` 은 **STA 모드**(폰이 공유기에 접속)일 때의 이름이다.
     * 핫스팟과 STA 는 **동시에 활성화되지 않으므로**, `wlan*` 을 넓게 잡으면
     * **핫스팟이 아닌데 핫스팟 값을 보이는** 잘못된 표시가 난다.
     *
     * → **`swlan`(SoftAP)이 명시적 표식**이라 그것만 믿는다. 없으면 **미지원.**
     */
    internal fun isHotspotAp(name: String): Boolean = HOTSPOT_MARKERS.any { name.startsWith(it) }

    /**
     * 핫스팟 AP 이름 표식.
     *
     * - `swlan` — 삼성 SoftAP (실측 확인)
     * - `ap` — AOSP SoftAP (`ap0`, `ap1`)
     *
     * `wlan` 을 넣지 않는 이유 = [isHotspotAp] 참고.
     */
    private val HOTSPOT_MARKERS = listOf("swlan", "ap")


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
