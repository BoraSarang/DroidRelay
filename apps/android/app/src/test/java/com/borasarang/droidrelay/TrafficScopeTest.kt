package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.DeviceTrafficReader
import com.borasarang.droidrelay.relay.TrafficScope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **기기 트래픽 범위** — `TrafficScope` / `DeviceTrafficReader` (M-27).
 *
 * ## 이 테스트가 지키는 계약
 *
 * 1. **기본은 "외부만"** — 핫스팟 내부 트래픽이 지표의 2/3 를 차지하는 기기에서
 *    전체를 기본값으로 두면 **실제보다 훨씬 크게 보인다.**
 * 2. **요청이 없거나 모르면 `ALL`** — 옛 클라이언트가 파라미터를 안 보내도
 *    **예전 동작이 유지되어야 한다.** 새 파라미터가 예전을 깨뜨리면 안 된다.
 * 3. **실패를 0 으로 바꾸지 않는다** — 0 은 "트래픽이 없다" 다.
 *    **읽을 수 없다** 는 다른 상태이며 사용자가 구분해야 한다.
 */
class TrafficScopeTest {

    // MARK: - 1. 기본값

    /**
     * ★ **"기본값" 이 두 군데에서 다르고, 그게 맞다.**
     *
     * | 위치 | 기본 | 이유 |
     * |---|---|---|
     * | **클라이언트 설정** | `EXTERNAL` | "기기가 밖으로 얼마나 씀인가" 가 지표의 목적 |
     * | **서버: 파라미터 없음** | `ALL` | **파라미터를 안 보낸다 = 이 필드를 모르는 구버전** |
     *
     * ## 왜 서버는 `ALL` 이고 `EXTERNAL` 이 아닌가
     *
     * **서버와 클라이언트는 따로 업그레이드된다.** 서버만 먼저 올린 상황에서
     * 옛 클라이언트가 `scope` 를 안 보내는데 **서버가 "외부만" 으로 답하면**
     * 그 클라이언트 화면에는 **조용히 다른 값이 보인다** —
     * "설정을 안 한 것인데 왜 값이 달라졌지" . **가장 나쁜 종류의 실패** 다.
     *
     * → **파라미터가 없다는 건 "이 기능을 모른다" 다. 모르는 쪽에는 예전 값을 준다.**
     *
     * **새 클라이언트는 항상 파라미터를 보낸다**(그 기본값이 `external` 이므로).
     * → **사용자가 받는 기본은 "외부만" 이다.** 이 구분이 전부다.
     */
    @Test
    fun `파라미터가_없으면_예전_동작_ALL을_쓴다`() {
        // **서버가 모르는 요청 = 예전 클라이언트** — 예전 값을 준다.
        assertEquals(TrafficScope.ALL, TrafficScope.of(null))
    }

    @Test
    fun `새_클라이언트가_보내는_기본은_외부만이다`() {
        // **클라이언트 설정의 기본값** — 이것이 사용자가 실제로 받는 값이다.
        // (Swift `TrafficScope.default` = .external 와 짝을 이룬다)
        assertEquals("external", TrafficScope.EXTERNAL.raw)
    }

    /** **모르는 값은 `ALL`** — 옛 동작 유지. 새 파라미터가 예전을 깨뜨리면 안 된다. */
    @Test
    fun `모르는_값은_ALL로_떨어진다`() {
        assertEquals(TrafficScope.ALL, TrafficScope.of("bogus"))
        assertEquals(TrafficScope.ALL, TrafficScope.of(""))
        assertEquals(TrafficScope.ALL, TrafficScope.of("EXTERNAL"))   // 대소문자 구분
    }

    @Test
    fun `알려진_값은_그대로_읽는다`() {
        assertEquals(TrafficScope.EXTERNAL, TrafficScope.of("external"))
        assertEquals(TrafficScope.ALL, TrafficScope.of("all"))
    }

    // MARK: - 2. 읽기 실패는 0 이 아니다

    /**
     * **rmnet 을 못 찾으면 0 이 아니라 "미지원"** 이다.
     *
     * ## 왜 이게 계약인가
     *
     * 0 을 보내면 클라이언트는 **"지원하는데 지금 트래픽이 없다"** 고 읽는다.
     * 실제로는 **hidden API 가 깨졌다거나 셀룰러가 없는 기기**다.
     * 사용자가 **스위치를 켜놓고 영영 0** 인 상태를 "고장" 으로 이해한다.
     *
     * → **`supported=false` + `note` 로 구분**한다. (M-14 에서 같은 함정을 이미 밟았다)
     */
    @Test
    fun `rmnet을_못_찾으면_0이_아니라_미지원이다`() {
        val t = DeviceTrafficReader.readForTest(externalIfaces = emptyList())
        assertEquals(false, t.supported)
        assertTrue("사유가 있어야 한다", !t.note.isNullOrBlank())
        assertTrue("무엇을 못 찾았는지 말해야 한다",
                   t.note!!.contains("rmnet"))
    }

    /** **값은 0 이어도 지원이다** — 읽기는 됐으니까. 미지원이 아니다. */
    @Test
    fun `값이_0이어도_지원으로_본다`() {
        val t = DeviceTrafficReader.readForTest(externalIfaces = listOf("rmnet_data9"))
        assertEquals(true, t.supported)          // 읽기에 성공했으므로
        assertEquals(0L, t.rx)                   // 값은 0 — 트래픽이 없을 뿐
        assertEquals(0L, t.tx)
    }

    // MARK: - 3. 합계

    /**
     * **여러 rmnet 인터페이스를 합산한다.**
     *
     * 실측: 이 기기는 `rmnet_ipa0` + `rmnet_data0~14` 를 가진다.
     * **하나만 읽으면 대부분을 놓친다.**
     */
    @Test
    fun `여러_rmnet을_모두_합산한다`() {
        val t = DeviceTrafficReader.readForTest(
            externalIfaces = listOf("rmnet_data1", "rmnet_data2"),
            fakeValues = mapOf(
                "rmnet_data1" to (100L to 200L),
                "rmnet_data2" to (300L to 400L),
            )
        )
        assertEquals(400L, t.rx)      // 100 + 300
        assertEquals(600L, t.tx)      // 200 + 400
        assertEquals(true, t.supported)
    }

    // MARK: - 4. 오프로드 인터페이스 제외 (M-28)

    /**
     * **`rmnet_ipa0` 은 합산에서 제외한다 — 2배 계상되기 때문이다.**
     *
     * ## 실측 근거 (M-28)
     *
     * 처음엔 "누락된 40GB" 라 판단했다. **틀렸고 실측이 뒤집었다.**
     * 같은 구간 tx 증분:
     * ```
     * rmnet_ipa0  Δtx = 5,717,550
     * rmnet_data1 Δtx = 5,677,102   ← 99.2% 일치
     * ```
     * → `ipa0` 는 **쿨컴 IPA 오프로드 가상 장치**다. `data1` 을 **그대로 통과**시킨다.
     * `dumpsys netstats` 에도 **한 번도 안 나온다** — 시스템이 트래픽 경로로 안 쓴다.
     *
     * → **더하면 2배 계상이다.**
     */
    @Test
    fun `ipa_오프로드는_제외한다_중복계상_방지`() {
        // **★ JUnit 은 메시지가 첫 인자다** — `assertTrue(조건, 메시지)` 가 아니라
        // `assertTrue(메시지, 조건)` 이다. 순서를 바꾸면 컴파일 에러로 잡힌다.
        assertFalse(
            "ipa0 는 오프로드 장치다 — 더하면 2배 계상된다",
            DeviceTrafficReader.isActiveCellular("rmnet_ipa0")
        )
    }

    /** **일반 셀룰러 인터페이스는 통과시킨다** — 위 테스트가 과다 제외로 새지 않았는지. */
    @Test
    fun `일반_rmnet은_통과시킨다`() {
        for (name in listOf("rmnet_data0", "rmnet_data1", "rmnet_data2", "rmnet_data14")) {
            assertTrue("$name 은 활성 셀룰러다", DeviceTrafficReader.isActiveCellular(name))
        }
    }

    // MARK: - 5. 핫스팟 AP 판정 (M-28)

    /**
     * **핫스팟 AP 인터페이스만 `hotspot` 으로 인정한다.**
     *
     * ## 왜 `wlan` 을 통째로 받지 않는가
     *
     * `wlan0` 은 **STA 모드**(폰이 공유기에 접속)일 때의 이름이다.
     * 핫스팟과 STA 는 **동시에 활성화되지 않으므로**, `wlan*` 을 넓게 잡으면
     * **핫스팟이 아닌데 핫스팟 값을 보이는** 잘못된 표시가 난다.
     */
    @Test
    fun `핫스팟은_swlan과_ap만_인정한다`() {
        assertTrue("삼성 SoftAP (실측 확인)", DeviceTrafficReader.isHotspotAp("swlan0"))
        assertTrue("swlan1 도 SoftAP", DeviceTrafficReader.isHotspotAp("swlan1"))
        assertTrue("AOSP SoftAP", DeviceTrafficReader.isHotspotAp("ap0"))
    }

    /** **STA 모드 인터페이스는 핫스팟이 아니다** — 없으면 안 되는 값을 만들어낸다. */
    @Test
    fun `sta_mode_wlan0은_핫스팟이_아니다`() {
        assertFalse(
            "wlan0 은 STA 모드 — 핫스팟 값으로 쓰면 거짓말이다",
            DeviceTrafficReader.isHotspotAp("wlan0")
        )
    }

    /** **셀룰러도 핫스팟이 아니다** — 두 축이 섞이면 안 된다. */
    @Test
    fun `cellular은_핫스팟으로_안_잡힌다`() {
        assertFalse(DeviceTrafficReader.isHotspotAp("rmnet_data1"))
    }

    // MARK: - 6. 3구간 파라미터

    /** **`hotspot` 파라미터가 그대로 읽힌다** — M-28 신규 구간. */
    @Test
    fun `hotspot_구간을_읽는다`() {
        assertEquals(TrafficScope.HOTSPOT, TrafficScope.of("hotspot"))
        assertEquals("hotspot", TrafficScope.HOTSPOT.raw)
    }

    /** **구버전 클라이언트는 `hotspot` 을 모른다** — 그래서 `external`/`all` 은 그대로여야 한다. */
    @Test
    fun `구간은_세_개다_그리고_기본은_외부`() {
        assertEquals(3, TrafficScope.entries.size)
        // **새 클라이언트가 파라미터를 안 보내는 일은 없다** (항상 보낸다)
        assertEquals("external", TrafficScope.EXTERNAL.raw)
    }

    // MARK: - 7. ALL 은 숨기지 않는다

    /** **`all` 은 숨김 없이 그대로 보낸다** — 예전 동작이고 거짓말이 아니다. */
    @Test
    fun `ALL은_숨기지_않는다`() {
        val t = DeviceTrafficReader.readAllForTest(rx = 30_262_128_941L, tx = 70_699_509_183L)
        assertEquals(30_262_128_941L, t.rx)
        assertEquals(70_699_509_183L, t.tx)
        assertEquals("all", t.scope)
        assertEquals(true, t.supported)
    }

    /** **64비트 값이 잘리지 않는다** — 카운터는 `Long` 다. */
    @Test
    fun `64비트_카운터가_잘리지_않는다`() {
        val 큰값 = 70_699_509_183L          // 실측에서 실제로 나온 값 (71GB)
        val t = DeviceTrafficReader.readAllForTest(rx = 큰값, tx = 큰값)
        assertEquals(큰값, t.rx)
        assertTrue("32비트 경계를 넘었어야 한다", t.rx > Int.MAX_VALUE.toLong())
    }
}
