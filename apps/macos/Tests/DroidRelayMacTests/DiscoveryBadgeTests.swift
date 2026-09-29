import XCTest
@testable import DroidRelayCore

/// **탐색 상태 표시** — `DiscoveryBadge`.
///
/// ## 왜 이걸 테스트로 두나
///
/// 화면 글자는 "그냥 쓰는 것" 이 아니라 **규칙**이다.
/// 전략이 4개이고 버전을 붙이는지와 수동 입력을 다루는지가 얽혀 있어서
/// **디스플레이 없이 전부 고정**할 수 있는 곳에 두는 편이 낫다.
final class DiscoveryBadgeTests: XCTestCase {

    // MARK: - 정상 표시

    /// **주소 + 버전 + 방법을 한 줄로** — 사용자가 확인하는 세 가지를 다 준다.
    func test_주소_버전_방법을_한줄로_보인다() {
        let s = DiscoveryBadge.line(strategy: .gateway, address: "10.38.120.211:3000",
                                   version: "0.43.0", isManual: false)
        XCTAssertTrue(s.contains("10.38.120.211:3000"), "주소가 있어야 '내 폰이 맞아?' 에 답한다")
        XCTAssertTrue(s.contains("v0.43.0"), "버전이 있어야 폰 설정 화면과 숫자를 맞춰 볼 수 있다")
        XCTAssertTrue(s.contains("게이트웨이"), "어떤 경로로 붙었는지")
    }

    /// **네 전략이 전부 방법 이름을 드러내야 한다** — 하나라도 흐리면 사용자는 모른다.
    func test_모든_전략이_방법을_말한다() {
        for s in DiscoveryStrategy.allCases {
            let line = DiscoveryBadge.line(strategy: s, address: "1.2.3.4:3000",
                                           version: "0.43.0", isManual: false)
            XCTAssertFalse(line.isEmpty, "\(s) 는 빈 줄이면 안 된다")
            if s != .manual {
                XCTAssertTrue(line.contains(s.displayName),
                              "\(s.rawValue) 전략이 이름을 안 드러내면 사용자가 모른다")
            }
        }
    }

    // MARK: - 수동 입력

    /// **수동 입력은 자동 발견과 구분돼야 한다** — 오해의 방향이 정반대다.
    ///
    /// 자동 발견 → "자동으로 붙었다" 는 **안심**
    /// 수동 입력 → "내가 넣은 곳" 은 **확인 필요**
    func test_수동_입력을_자동_발견과_구분한다() {
        let s = DiscoveryBadge.line(strategy: .manual, address: "192.168.0.9:3000",
                                   version: "0.43.0", isManual: true)
        XCTAssertTrue(s.contains("직접 입력"), "내가 넣은 곳이라는 말")
        XCTAssertFalse(s.contains("게이트웨이"), "찾은 게 아니라 넣은 곳이다")
    }

    /// **전략이 `manual` 이 아니어도 `isManual` 이면 직접 입력으로 본다.**
    ///
    /// 사용자가 주소를 직접 넣었는데 `useManualAddress` 경로가
    /// 다른 전략을 보고하는 일이 생기면 **"자동으로 찾았다"고 거짓말한다.**
    func test_isManual이_최우선이다() {
        let s = DiscoveryBadge.line(strategy: .cached, address: "10.0.0.1:3000",
                                   version: "0.43.0", isManual: true)
        XCTAssertTrue(s.contains("직접 입력"))
        XCTAssertFalse(s.contains("저장된 주소"), "전략 이름이 직접 입력을 덮어쓰면 안 된다")
    }

    // MARK: - 버전 표기 함정

    /// **빈 버전은 붙이지 않는다** — `cached` 경로는 `version: ""` 로 만든다.
    ///
    /// 그대로 붙이면 **"10.38.120.211:3000 · v"** 가 나온다.
    /// 사용자는 **버전 번호가 잘렸다** 고 읽고, 서버가 깨졌다고 오해한다.
    func test_빈_버전은_붙이지_않는다() {
        let s = DiscoveryBadge.line(strategy: .cached, address: "10.0.0.1:3000",
                                   version: "", isManual: false)
        XCTAssertFalse(s.contains("· v"), "잘린 버전 번호처럼 보인다 — 실제: \(s)")
    }

    /// **공백뿐인 버전도 같은 취급** — `probe` 가 못 읽으면 `nil` 이 아니라 공백이 남는다.
    func test_공백뿐인_버전도_붙이지_않는다() {
        let s = DiscoveryBadge.line(strategy: .cached, address: "10.0.0.1:3000",
                                   version: "   ", isManual: false)
        XCTAssertFalse(s.contains("· v"), "공백도 없는 것과 같다")
    }

    /// **버전을 모르면(`nil`) 주소와 방법은 여전히 보인다** — 연결 상태는 알 수 있어야 한다.
    func test_버전을_모르면_주소와_방법은_보인다() {
        let s = DiscoveryBadge.line(strategy: .subnetScan, address: "10.0.0.1:3000",
                                   version: nil, isManual: false)
        XCTAssertTrue(s.contains("10.0.0.1:3000"), "주소는 항상 보여야 한다")
        XCTAssertTrue(s.contains("네트워크 검색"), "방법도 항상 보여야 한다")
    }

    // MARK: - 실패 · 탐색 중

    /// **실패는 원인과 조치까지 말한다** — "실패" 만으로는 뭘 해야 할지 모른다.
    func test_실패는_조치를_함께_말한다() {
        let s = DiscoveryBadge.failureLine()
        XCTAssertTrue(s.contains("연결 실패"))
        XCTAssertTrue(s.contains("확인"), "사용자가 할 수 있는 조치가 있어야 한다")
    }

    /// **탐색 중에 무엇을 하는지 말한다** — 254개 스캔은 0.1초 이상 걸린다.
    func test_탐색중은_무엇을_하는지_말한다() {
        let s = DiscoveryBadge.scanningLine()
        XCTAssertTrue(s.contains("탐색 중"))
        XCTAssertTrue(s.contains("게이트웨이"), "순서를 알려야 기다릴 이유가 생긴다")
    }

    /// **한 줄이어야 한다** — 두 줄로 늘어나면 팝오버 352pt 를 다 먹는다.
    func test_모두_한줄이다() {
        for s in [DiscoveryBadge.failureLine(), DiscoveryBadge.scanningLine()] {
            XCTAssertFalse(s.contains("\n"), "개행이 있으면 팝오버를 두 줄 먹는다: \(s)")
        }
    }

    /// **문구가 너무 길면 352pt 에 안 들어간다** — 팝오버 폭을 넘지 않아야 한다.
    ///
    /// 10pt 시스템 폰트로 352pt 에 practical 한 글자 수를 넘지 않아야 한다.
    /// 여기서는 **한계선을 두기 위함**이고, 넘으면 UI 를 줄여야 한다는 신호다.
    func test_문구가_팝오버_폭에_맞는다() {
        let s = DiscoveryBadge.line(strategy: .subnetScan, address: "10.38.120.211:3000",
                                   version: "0.43.0", isManual: false)
        XCTAssertLessThan(s.count, 60, "10pt 폰트로 352pt 안에 들어갈 길이여야 한다 — 실제: \(s)")
    }
}
