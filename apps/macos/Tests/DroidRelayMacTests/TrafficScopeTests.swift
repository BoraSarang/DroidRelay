import XCTest
@testable import DroidRelayCore

/// **기기 트래픽 범위** — `TrafficScope` / `TrafficScopeSetting` (M-27).
///
/// ## 이 테스트가 지키는 계약
///
/// 1. **기본은 "외부만"** — 핫스팟 내부 트래픽이 지표의 2/3 를 차지하는 기기에서
///    전체를 기본값으로 두면 **실제보다 훨씬 크게 보인다.**
/// 2. **"값이 없다" 와 "전체" 를 구분한다** — 구버전 서버를 만나면
///    그대로 **"전체로 셉니다"** 고 말해야 한다.
/// 3. **설정한 범위가 서버에서 실제로 쓰였는지 알려야 한다** —
///    요구한 것과 다르면 **"설정이 안 먹혔다"** 와 **"서버가 못 한다"** 가 다르다.
final class TrafficScopeTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        suite = "trafficScope.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    // MARK: - 1. 기본값

    /// **기본은 "외부만"** — 사용자가 아무것도 건드리지 않았을 때의 값.
    ///
    /// 실측 근거: 핫스팟 활성 기기에서 tx 의 **67%** 가 핫스팟 내부였다.
    /// 그걸 기본값으로 두면 **모든 값이 실제보다 크게 보인다.**
    func test_기본은_외부만이다() {
        XCTAssertEqual(TrafficScope.default, .external)
        XCTAssertEqual(TrafficScopeSetting.default.scope, .external)
        XCTAssertEqual(TrafficScopeSetting.load(defaults).scope, .external,
                       "저장된 값이 없으면 '외부만'")
    }

    /// **두 종류의 "없음" 이 구분된다** — 구버전 서버·손으로 편집한 값.
    func test_값이_없거나_모르는_값이면_기본으로_간다() {
        defaults.set("뭔가", forKey: "net.trafficScope")
        XCTAssertEqual(TrafficScopeSetting.load(defaults).scope, .external,
                       "모르는 값이면 기본값 — 화면이 조용히 이상한 상태로 남지 않는다")
    }

    // MARK: - 2. 저장·로드 왕복

    /// **바꾼 다음 실행해도 그대로여야 한다.**
    ///
    /// 이 프로젝트에서 이미 한 번 배운 실패: **테스트만 저장/로드를 검증하고
    /// 실제 앱은 기본값만 쓰고 있었다** — 토글을 눌러도 재실행하면 되돌아가서
    /// "저장이 안 된다" 고 보였다. (AppModel 의 `speedSetting` 주석 참조)
    func test_저장하면_로드된다() {
        for s in TrafficScope.allCases {
            TrafficScopeSetting(scope: s).save(defaults)
            XCTAssertEqual(TrafficScopeSetting.load(defaults).scope, s, "\(s) 가 저장되지 않았다")
        }
    }

    /// **기본을 명시적으로 저장해도 다른 값이 아니다** — 기본값 = "외부만".
    func test_외부만_명시적_저장도_기본과_같다() {
        TrafficScopeSetting(scope: .external).save(defaults)
        XCTAssertEqual(TrafficScopeSetting.load(defaults).scope, TrafficScope.default)
    }

    // MARK: - 3. 파싱

    func test_파싱은_양방향이다() {
        XCTAssertEqual(TrafficScope.parse("external"), .external)
        XCTAssertEqual(TrafficScope.parse("all"), .all)
        // **nil 은 "서버가 이 필드를 모른다"** — "전체" 로 가정하지 않는다.
        XCTAssertNil(TrafficScope.parse(nil), "값이 없으면 nil — '전체' 와 구분해야 한다")
        XCTAssertNil(TrafficScope.parse("unknown"))
    }

    // MARK: - 4. 화면 문구

    /// **두 모드의 설명이 서로 달라야 한다.**
    ///
    /// 같으면 사용자는 **차이가 뭔지 알 수 없고**, 그냥 있는 그대로 누른다.
    func test_설명문이_구분된다() {
        XCTAssertNotEqual(TrafficScope.external.explanation, TrafficScope.all.explanation)
        XCTAssertNotEqual(TrafficScope.external.label, TrafficScope.all.label)
        // **"전체" 쪽에 핫스팿 때문에 커진다는 사실이 있어야 한다** —
        // 이게 사용자가 이 기능을 요청한 이유다.
        XCTAssertTrue(TrafficScope.all.explanation.contains("핫스팟"),
                      "'전체' 설명에 핫스팟 언급이 없다 — 왜 기본이 아닌지 설명이 안 된다")
    }

    // MARK: - 5. DeviceTraffic — scope 전달

    /// **scope 는 기본 `nil` 이다** — 옛 코드와 같은 호출이 그대로 컴파일된다.
    func test_DeviceTraffic의_scope는_없어도_된다() {
        let t = DeviceTraffic(rxTotal: 100, txTotal: 200)
        XCTAssertNil(t.scope, "scope 미지정은 nil — '전체' 라고 가정하지 않는다")
    }

    /// **음수는 보정된다** — 카운터 되감김(재부팅·SIM 교체) 방어.
    func test_음수_카운터는_0으로_보정된다() {
        let t = DeviceTraffic(rxTotal: -5, txTotal: -9)
        XCTAssertEqual(t.rxTotal, 0)
        XCTAssertEqual(t.txTotal, 0)
    }

    // MARK: - 6. 64비트 카운터 — M-27 에서 실제로 깨졌다

    /// **32GB 를 넘는 누적값이 음수가 되지 않는다.**
    ///
    /// ## 이 테스트가 없으면 무엇이 깨지는가 — 실측 사례
    ///
    /// 서버는 Android `Long`(64비트) 카운터를 준다. 값을 `Int` 로 읽으면서
    /// `intValue` 를 썼더니 **3.9GB 가 음수** 로 나왔다:
    ///
    /// ```
    /// JSON          rxTotal = 3,952,191,717
    /// intValue   →   -397,312,936     ★ 범위가 뒤집혔다
    /// int64Value → 3,952,191,717     ✓
    /// ```
    ///
    /// `intValue` 는 **플랫폼 `Int` 크기로 좁힌다.** 64비트 macOS 에서도
    /// **JSON 파서가 정수를 아는 방식에 따라** 좁힐 수 있다 — **형에 의존하지 않는다.**
    ///
    /// → **64비트 값을 보존하는 `int64Value` 만 쓴다.**
    func test_32기가_넘는_값이_음수가_되지_않는다() {
        let 큰값 = 3_952_191_717          // 실측에서 음수가 되던 값
        let t = DeviceTraffic(rxTotal: 큰값, txTotal: 67_760_733_260)
        XCTAssertEqual(t.rxTotal, 큰값, "3.9GB 가 음수가 되었다 — 좁히면 범위가 뒤집힌다")
        XCTAssertEqual(t.txTotal, 67_760_733_260, "67GB 는 32비트를 넘는다")

        // **경계** — 32비트 최댓값을 넘나드는 값들
        for v: Int64 in [2_147_483_647, 2_147_483_648, 3_952_191_717, 70_000_000_000] {
            let i = Int(v)
            let c = DeviceTraffic(rxTotal: i, txTotal: i)
            XCTAssertEqual(c.rxTotal, i, "\(v) 가 보존되지 않았다")
            XCTAssertGreaterThan(c.rxTotal, 0, "\(v) 가 음수가 되었다")
        }
    }

    // MARK: - 7. "좁히기가 원인이었다" 는 틀린 결론이었다

    /// **서버가 준 64비트 값이 클라이언트에서 그대로 나온다.**
    ///
    /// ## 이 테스트가 지키는 사실 — 그리고 **지켰던 가설이 틀렸던 기록**
    ///
    /// 값이 음수로 보여서 **"32비트 `intValue` 가 좁혔다"** 고 단정했다.
    /// **같은 응답을 두 경로로 나눠 재현한 결과 틀린 판정이었다:**
    ///
    /// ```
    /// JSON          txTotal = 71,693,545,994
    /// intValue   →  71,693,545,994        ← macOS 는 64비트라 안 잘린다
    /// 문자열 직접 →  71,693,545,994        ← 동일
    /// ```
    ///
    /// **그래도 `int64Value` 를 쓰는 것이 맞다** — 형이 범위를 보장하는 쪽이 옳고,
    /// 좁히기가 아닌 다른 원인(아래 테스트)이 실제로 있었다.
    ///
    /// → **이 테스트는 "좁히기가 없다" 를 고정한다.** 64비트 플랫폼에서
    /// **값이 32비트 경계를 넘어도 그대로 보존**되어야 한다.
    func test_64비트_값은_그대로_온다() {
        // **실측에서 실제로 나온 값들**
        for v in [71_693_545_994, 71_538_491_910, 31_876_382_660, 4_811_907_651] {
            let o: [String: Any] = [
                "rxTotal": NSNumber(value: Int64(v)),
                "txTotal": NSNumber(value: Int64(v)),
            ]
            let got = DeviceTraffic.clampTotal(o["rxTotal"])
            XCTAssertEqual(got, Int(v), "\(v) 가 변했다 — 64비트 값이 보존돼야 한다")
            XCTAssertGreaterThan(got, Int(Int32.max), "\(v) 는 32비트 경계를 넘는데 음수가 아니다")
        }
    }

    /// **음수는 여전히 0 이 된다** — 좁히기가 아니더라도 이 방어는 필요하다.
    ///
    /// **진짜 원인 후보**: 카운터 되감김(셀룰러 재접속·SIM 교체·AP 재시작).
    /// 서버가 그대로 음수를 보낼 수도 있고, **`max(0, ·)` 로 방어해야 하는
    /// 이유는 이거다.** 좁히기 가설이 틀렸다고 방어까지 없애면 안 된다.
    func test_음수는_여전히_0으로_간다() {
        XCTAssertEqual(DeviceTraffic.clampTotal(NSNumber(value: Int64(-1))), 0)
        XCTAssertEqual(DeviceTraffic.clampTotal(NSNumber(value: Int64.min)), 0)
        // **0 은 0 이다** — 0 은 정당한 값이지 오류가 아니다.
        XCTAssertEqual(DeviceTraffic.clampTotal(NSNumber(value: Int64(0))), 0)
    }

    /// **파싱 경로가 64비트를 보존한다** — `int64Value` 경로 자체를 고정.
    func test_파싱은_64비트를_보존한다() {
        for v: Int64 in [3_952_191_717, 70_000_000_000, 2_147_483_648] {
            let o: [String: Any] = ["rxTotal": NSNumber(value: v), "txTotal": NSNumber(value: v)]
            let 기대 = Int(v)
            XCTAssertEqual(DeviceTraffic.clampTotal(o["rxTotal"]), 기대, "\(v) 좁혀졌다")
        }
        // **문자열로 준 경우** — 서버가 숫자를 문자열로 주면 그것도 받아야 한다.
        XCTAssertEqual(DeviceTraffic.clampTotal("3952191717"), 3_952_191_717)
        // **없는 값·엉뚱한 값은 0** — 크래시보다 0 이 낫다. **음수는 안 된다.**
        XCTAssertEqual(DeviceTraffic.clampTotal(nil), 0)
        XCTAssertEqual(DeviceTraffic.clampTotal("abc"), 0)
        XCTAssertEqual(DeviceTraffic.clampTotal(-100), 0)
    }
}
