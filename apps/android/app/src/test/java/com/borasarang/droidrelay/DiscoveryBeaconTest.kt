package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DiscoveryBeacon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **"나 여기있소" 신호 페이로드** (T-1090).
 *
 * ## 왜 이 테스트가 중요하나
 *
 * **포맷이 어긋나면 "조용히" 안 통한다.** 화면에 에러도 없고 로그에도 조용히 넘어간다 —
 * Mac 은 254개를 그냥 두드린다. 사용자는 "왜 안 되지?" 할 뿐 이유를 모른다.
 *
 * → **양쪽 값(앱 ID·포트)이 같은지** 여기서 고정한다.
 */
class DiscoveryBeaconTest {

    @Test
    fun `페이로드에_주소와_버전이_들어간다`() {
        val p = JSONObject(
            DiscoveryBeacon.payload(
                version = "0.50.0", ip = "10.38.120.211",
                port = 3000, httpsPort = 8443, https = false,
            )
        )
        assertEquals("10.38.120.211", p.getString("ip"))
        assertEquals("0.50.0", p.getString("v"))
        assertEquals(3000, p.getInt("port"))
        assertEquals(8443, p.getInt("httpsPort"))
        assertEquals(false, p.getBoolean("https"))
    }

    /**
     * ★ 브로드캐스트에는 **발신자 주소가 없다.**
     * 맥은 "누가 보냈는가" 를 알 수 없다 → 폰이 자기 IP 를 직접 실어야 한다.
     * 이게 빠지면 맥이 자기 패킷을 자기 폰으로 찾는 참사가 난다.
     */
    @Test
    fun `ip_는_반드시_들어간다`() {
        val p = JSONObject(DiscoveryBeacon.payload("0.50.0", "192.168.1.50", 8080, 8443, true))
        assertTrue("ip 가 없으면 맥이 붙을 대상을 모른다", p.has("ip"))
        assertEquals("192.168.1.50", p.getString("ip"))
    }

    /** 앱 식별자 — 맥이 남의 패킷을 걸러 내는 기준. */
    @Test
    fun `앱_식별자가_들어간다`() {
        val p = JSONObject(DiscoveryBeacon.payload("0.50.0", "10.0.0.9", 3000, 8443, false))
        assertEquals(DiscoveryBeacon.APP_ID, p.getString("app"))
    }

    /** 핫스팟이 꺼졌다 켜지면 **주소가 바뀐다** → 매번 새로 실어야 한다. */
    @Test
    fun `호스트가_바뀌면_페이로드도_바뀐다`() {
        val a = DiscoveryBeacon.payload("0.50.0", "10.38.120.211", 3000, 8443, false)
        val b = DiscoveryBeacon.payload("0.50.0", "10.38.120.147", 3000, 8443, false)
        assertTrue("주소가 바뀌었는데 페이로드가 같으면 맥이 옛 주소로 붙는다", a != b)
    }

    /** 포트가 바뀌면(랜덤 포트 설정) 그것도 실어야 한다. */
    @Test
    fun `포트가_바뀌면_페이로드도_바뀐다`() {
        val a = DiscoveryBeacon.payload("0.50.0", "10.0.0.9", 3000, 8443, false)
        val b = DiscoveryBeacon.payload("0.50.0", "10.0.0.9", 8080, 8443, false)
        assertTrue(a != b)
    }

    /** 한 줄이어야 한다 — UDP 는 경계가 없고 뒤에 개행이 붙을 수 있다. */
    @Test
    fun `개행이_없다`() {
        val p = DiscoveryBeacon.payload("0.50.0", "10.0.0.9", 3000, 8443, false)
        assertTrue("개행이 있으면 맥 파싱이 실패한다", !p.contains("\n"))
    }

    // MARK: - 포맷 상수는 맥과 같아야 한다

    /**
     * ★ **값이 다르면 "조용히" 안 통한다.**
     * 에러도 로그도 없다 — 그냥 254개를 두드린다. 사용자만 손해다.
     */
    @Test
    fun `포맷_상수는_맥과_같아야_한다`() {
        assertEquals("DroidRelay", DiscoveryBeacon.APP_ID)
        assertEquals(45454, DiscoveryBeacon.PORT)
    }

    /**
     * ★ **처음에 틀렸던 근거를 여기서 바로잡았다.**
     *
     * 처음엔 "발신이 `/24` 스캔(0.11초)보다 빨라야 신호가 도움이 된다" 는 테스트를
     * 넣고 `5초` 로 정했다. **5 > 0.11 이니 실패했다 — 그리고 그게 옳았다.**
     * 근거가 아니라 **비교 대상**이 틀렸던 것이다.
     *
     * 맥의 스캔은 **"다시 찾기" 를 눌렀을 때만** 도는데,
     * 맥의 리스너는 **앱이 떠 있는 내내** 듣고 있다. 즉
     * **신호의 이득은 "스캔보다 빠르다" 가 아니라 "스캔을 하지 않는다"** 이다.
     *
     * → 주기를 스캔 시간과 비교하는 테스트는 **삭제**했다. 무의미하다.
     */
    @Test
    fun `주기는_한_번_기다리면_온다`() {
        // 사용자가 폰을 켠 직후 "다시 찾기" 를 눌렀을 때, 최대 이만큼만 기다린다.
        assertTrue(
            "10초를 넘으면 '왜 반응 없지' 가 된다",
            DiscoveryBeacon.INTERVAL_SECONDS <= 10,
        )
    }

    /** 너무 짧으면 **네트워크에 의미 없는 트래픽**을 만든다. 하한은 둔다. */
    @Test
    fun `주기가_과도하게_짧지_않다`() {
        assertTrue("1초마다 상대를 때리는 것은 과하다", DiscoveryBeacon.INTERVAL_SECONDS >= 3)
    }
}
