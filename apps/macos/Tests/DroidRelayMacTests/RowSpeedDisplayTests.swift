import XCTest
@testable import DroidRelayCore

/// **행에 다운로드·업 속도를 함께 보여줄지** — `RowSpeedDisplay`.
///
/// ## 이 테스트가 막는 실제 버그 (2026-09-29 실측)
///
/// 토렌트 행이 이렇게 렌더링됐다:
/// ```
/// IPZZ-926              ▲4.6 KB/s
/// 다운로드 중 4.2 MB / 3.29 GB      ▲5 ▼104
/// ```
/// **다운로드 속도가 없었다.** 서버는 `downloadSpeed: 102427` 을 주고 있었고
///(`/api/torrents` 실측), 파싱도 정상이었다. 문제는 **표시 로직**이었다.
///
/// ```swift
/// if uploadBps > 0 { Text("▲…") } else { Text("다운로드…") }   // ← 업이 우선
/// ```
///
/// **업 속도가 켜지는 순간 다운로드 속도가 화면에서 사라졌다.** 잡 행도 같은 구조였다.
///
/// > **두 값은 독립이다.** 하나가 0 이어도 다른 하나는 살아 있어야 한다.
/// > 조건으로 고르면 **항상 존재하는 정보를 조건에 걸어 없앤다.**
final class RowSpeedDisplayTests: XCTestCase {

    // MARK: - 토렌트

    /// **실측 상황** — 업은 4,716 · 다운은 102,427. 둘 다 보여야 한다.
    ///
    /// 표기는 `SpeedFormat.compact`(메뉴바 압축 표기)다. MB 경계가 **1,000,000** 이라
    /// 102,427 B/s 는 `"100K"` 가 된다 — 사용자가 원래 보던 표기와 같다.
    func test_다운과_업이_모두_보인다() {
        let r = RowSpeedDisplay.torrent(down: 102_427, up: 4_716)
        XCTAssertEqual(r.down, "▼100K", "다운로드 속도가 보여야 한다")
        XCTAssertEqual(r.up, "▲5K", "업 속도도 보여야 한다")
    }

    /// **이게 이번 버그다** — 업이 0 이 아니면 다운로드가 사라지지 않는다.
    func test_업이_있어도_다운로드가_사라지지_않는다() {
        // 업이 켜진 순간의 실측값을 그대로 쓴다
        let r = RowSpeedDisplay.torrent(down: 102_427, up: 4_716)
        XCTAssertNotNil(r.down, "업이 있어도 다운로드는 반드시 보인다")
        XCTAssertNotNil(r.up)
    }

    /// **업만 있으면 업만** — 없는 값을 지어내지 않는다.
    func test_다운로드만_있으면_다운로드만() {
        let r = RowSpeedDisplay.torrent(down: 5_000_000, up: 0)
        XCTAssertNotNil(r.down)
        XCTAssertNil(r.up, "0 을 빈 칸 대신 0 으로 쓰지 않는다")
    }

    /// **둘 다 0 이면 둘 다 없다** — "0 B/s" 를 두 번 쓰지 않는다.
    func test_둘_다_0이면_아무것도_없다() {
        let r = RowSpeedDisplay.torrent(down: 0, up: 0)
        XCTAssertNil(r.down)
        XCTAssertNil(r.up)
    }

    /// **시딩(업만) 상태** — 다운로드 0 · 업만 양수.
    func test_시딩은_업만_보인다() {
        let r = RowSpeedDisplay.torrent(down: 0, up: 12_000)
        XCTAssertNil(r.down)
        XCTAssertNotNil(r.up, "시딩 중에는 업 속도가 핵심 정보다")
    }

    // MARK: - 잡 (다운로드)

    /// **잡도 같은 버그가 있었다** — 업이 켜지면 다운로드 속도가 사라졌다.
    func test_잡도_다운로드가_사라지지_않는다() {
        let r = RowSpeedDisplay.job(down: 1_500_000, up: 300_000)
        XCTAssertNotNil(r.down, "잡도 업이 있어도 다운로드는 보인다")
        XCTAssertNotNil(r.up)
    }

    /// **잡도 둘 다 0 이면 없다.**
    func test_잡도_둘_다_0이면_없다() {
        let r = RowSpeedDisplay.job(down: 0, up: 0)
        XCTAssertNil(r.down)
        XCTAssertNil(r.up)
    }

    // MARK: - 표기

    /// **화살표가 붙는다** — 방향이 색에만 의존하지 않는다.
    ///
    /// 색을 못 보는 사용자와 스크린샷을 읽는 사람이 **같은 정보를** 얻어야 한다.
    func test_화살표가_붙는다() {
        let r = RowSpeedDisplay.torrent(down: 102_427, up: 4_716)
        XCTAssertTrue(r.down?.hasPrefix("▼") ?? false, "다운로드 화살표")
        XCTAssertTrue(r.up?.hasPrefix("▲") ?? false, "업 화살표")
    }

    /// **두 값이 붙으면 한 줄에 들어간다** — 행이 넘치면 이름이 잘린다.
    func test_두_값이_한줄에_들어간다() {
        let r = RowSpeedDisplay.torrent(down: 102_427, up: 4_716)
        let line = [r.down, r.up].compactMap { $0 }.joined(separator: " ")
        XCTAssertLessThan(line.count, 28, "352pt 행에 두 값이 들어가야 한다 — 실제: \(line)")
    }

    /// **표시가 두 개를 넘는 일은 없다** — 줄이 늘어나면 이름이 사라진다.
    func test_표시는_최대_두개다() {
        for (d, u) in [(0, 0), (1, 0), (0, 1), (102_427, 4_716)] {
            let r = RowSpeedDisplay.torrent(down: d, up: u)
            XCTAssertLessThanOrEqual([r.down, r.up].compactMap { $0 }.count, 2)
        }
    }
}
