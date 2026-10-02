import XCTest
@testable import DroidRelayCore

/// **폰 알림(beacon) 신호 해석** (T-1090).
///
/// 안드로이드 `DiscoveryBeacon` 이 보내는 패킷을 Mac 이 받아 즉시 붙는다.
///
/// ## 왜 이 테스트가 중요하나
///
/// **포맷이 어긋나면 "조용히" 안 통한다.** 화면에 에러도 없고 로그에도 조용히 넘어간다.
final class BeaconSignalTests: XCTestCase {

    /// 실제 안드로이드가 보내는 그대로의 한 줄.
    private let 정상 = #"{"app":"DroidRelay","v":"0.50.0","ip":"10.38.120.211","port":3000,"httpsPort":8443,"https":false}"#

    func test_정상_패킷을_읽는다() {
        let s = try? XCTUnwrap(BeaconSignal.parse(정상))
        XCTAssertEqual(s?.host, "10.38.120.211")
        XCTAssertEqual(s?.port, 3000)
        XCTAssertEqual(s?.version, "0.50.0")
    }

    /// UDP 라우팅 때문에 **뒤에 개행이 붙는다.** 붙은 채로 버리면 **한 번도 안 된다.**
    func test_뒤에_개행이_붙어도_읽는다() {
        XCTAssertNotNil(BeaconSignal.parse(정상 + "\n"))
        XCTAssertNotNil(BeaconSignal.parse("  " + 정상 + "\r\n"))
    }

    // MARK: - 남의 패킷은 버린다

    /// ★ 45454 는 **임의로 고른 포트라 다른 프로그램의 패킷이 섞일 수 있다.**
    /// "알았다" 하고 붙으면 **엉뚱한 주소로 붙는다** — "왜 갑자기 옛날 자료가 나오지" 의 원인이 된다.
    func test_다른_앱의_패킷은_버린다() {
        XCTAssertNil(BeaconSignal.parse(#"{"app":"SomeOtherApp","ip":"10.0.0.9","port":3000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"droidrelay","ip":"10.0.0.9","port":3000}"#),
                     "대소문자가 다르면 남의 패킷이다")
        XCTAssertNil(BeaconSignal.parse(#"{"ip":"10.0.0.9","port":3000}"#),
                     "식별자가 없으면 남의 패킷일 수 있다")
    }

    func test_쓰레기를_버린다() {
        XCTAssertNil(BeaconSignal.parse(""))
        XCTAssertNil(BeaconSignal.parse("   "))
        XCTAssertNil(BeaconSignal.parse("hello"))
        XCTAssertNil(BeaconSignal.parse("{\"app\":"))
        XCTAssertNil(BeaconSignal.parse("[1,2,3]"))
        XCTAssertNil(BeaconSignal.parse("null"))
    }

    // MARK: - 없는 값을 지어내지 않는다

    /// **"10.0.0.999" 를 그대로 쓰면 무의미한 프로브가 된다** — `/24` 스캔이랑 다를 게 없다.
    func test_IP가_유효하지_않으면_버린다() {
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.999","port":3000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"999.1.1.1","port":3000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0","port":3000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"","port":3000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","port":3000}"#),
                     "IP 가 없으면 브로드캐스트 발신자 주소가 없어 붙을 대상을 모른다")
    }

    func test_포트가_유효하지_않으면_버린다() {
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9","port":0}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9","port":70000}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9","port":-1}"#))
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9","port":"3000"}"#),
                     "문자열 포트는 프로브할 수 없다")
        XCTAssertNil(BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9"}"#))
    }

    /// **버전은 없어도 된다** — 새 키가 추가돼도 구버전 파서가 죽지 않게 하는 것이 목적이다.
    /// 그런데 **"v" 가 붙은 것처럼 보여서는 안 된다** — 잘린 버전처럼 보인다 (M-22).
    func test_버전이_없어도_붙는다() {
        let s = BeaconSignal.parse(#"{"app":"DroidRelay","ip":"10.0.0.9","port":3000}"#)
        XCTAssertEqual(s?.host, "10.0.0.9")
        XCTAssertEqual(s?.version, "")
        XCTAssertFalse(DiscoveryBadge.line(strategy: .beacon,
                                          address: s!.info.displayAddress,
                                          version: s?.version,
                                          isManual: false).contains("· v"),
                       "빈 버전이 있으면 '· v' 가 붙어 잘린 것처럼 보인다")
    }

    /// 새 키가 추가돼도 **구버전 파서가 죽지 않아야 한다** — 앞의 키를 다 본다.
    func test_알_모르는_키가_추가돼도_읽는다() {
        let s = BeaconSignal.parse(
            #"{"app":"DroidRelay","v":"0.51.0","ip":"10.0.0.9","port":3000,"https":true,"futureKey":123}"#)
        XCTAssertEqual(s?.host, "10.0.0.9")
        XCTAssertEqual(s?.version, "0.51.0")
    }

    // MARK: - 포맷 상수는 양쪽이 같아야 한다

    /// ★ **값이 다르면 "조용히" 안 통한다.** 에러도 로그도 없다 — 254개를 그냥 두드린다.
    func test_포맷_상수는_안드로이드와_같아야_한다() {
        XCTAssertEqual(BeaconSignal.appID, "DroidRelay")
        XCTAssertEqual(BeaconSignal.port, 45454)
    }

    /// 포트 0 은 리스너가 아무 데도 못 연다. 상수가 밀려난 것인지 확인한다.
    func test_포트는_0_이_아니다() {
        XCTAssertNotEqual(BeaconSignal.port, 0)
        XCTAssertNotEqual(BeaconSignal.port, UInt16.max)
    }

    // MARK: - 전략

    /// **`beacon` 이 전략으로 말하기 위한 준비** — 주소·버전·방법이 함께 있어야 확인된다.
    func test_beacon_전략은_확인_가능한_말을_한다() {
        let line = DiscoveryBadge.line(strategy: .beacon,
                                       address: "10.38.120.211:3000",
                                       version: "0.50.0",
                                       isManual: false)
        XCTAssertTrue(line.contains("10.38.120.211:3000"))
        XCTAssertTrue(line.contains("0.50.0"))
        XCTAssertTrue(line.contains("폰 알림"))
        XCTAssertFalse(line.contains("직접 입력"),
                       "자동 발견을 직접 입력으로 표시하면 오해의 방향이 정반대다")
    }
}
