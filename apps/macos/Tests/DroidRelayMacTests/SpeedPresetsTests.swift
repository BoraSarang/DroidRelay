import XCTest
@testable import DroidRelayCore

/// 속도 프리셋이 **웹 대시보드와 같은 값·같은 라벨**인지 고정한다.
///
/// 각 테스트에 웹의 어느 행에서 왔는지 적었다.
/// 웹이 프리셋을 바꾸면 여기서 깨진다 — 그게 의도다.
final class SpeedPresetsTests: XCTestCase {

    /// **프리셋 목록이 웹 965행과 같아야 한다.**
    ///
    /// 하나라도 빠지면 웹에서 고를 수 있는 값이 앱에선 고를 수 없어진다.
    func test_프리셋목록이_웹과_같다() {
        XCTAssertEqual(SpeedPresets.kbpsList, [0, 256, 512, 1024, 2048, 5120, 10240, 20480])
    }

    /// 웹 966행 `kbpsName` — **정수 나눗셈**이라 1024 KB/s 가 `1MB/s` 다.
    func test_라벨이_웹과_같다() {
        XCTAssertEqual(SpeedPresets.name(0), "무제한")
        XCTAssertEqual(SpeedPresets.name(256), "256KB/s")
        XCTAssertEqual(SpeedPresets.name(512), "512KB/s")
        // 1024 이상은 MB 로 — 1024/1024 = 1 (버림)
        XCTAssertEqual(SpeedPresets.name(1024), "1MB/s")
        XCTAssertEqual(SpeedPresets.name(2048), "2MB/s")
        XCTAssertEqual(SpeedPresets.name(5120), "5MB/s")
        XCTAssertEqual(SpeedPresets.name(10240), "10MB/s")
        XCTAssertEqual(SpeedPresets.name(20480), "20MB/s")
    }

    /// **음수는 "무제한"** — 웹 `k<=0` 과 같다.
    /// 서버가 -1 을 보내는 경우에도 "무제한" 으로 보여야 한다.
    func test_음수와_0은_무제한() {
        XCTAssertEqual(SpeedPresets.name(-1), "무제한")
        XCTAssertEqual(SpeedPresets.name(0), "무제한")
    }

    /// 웹 979행 — **올림(ceil) 이다.** 1025 B/s 는 2KB/s 다.
    ///
    /// 버림으로 쓰면 1025 B/s 제한이 "1KB/s" 로 보여 잘못된 정보를 준다.
    func test_BPS를_KBPS로_변환할때_올림한다() {
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 0), 0)
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 1024), 1)     // 1KB/s
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 256 * 1024), 256)
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 1), 1)         // 1 B/s → 1KB/s (올림)
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 1025), 2)      // 1025 B/s → 2KB/s
        XCTAssertEqual(SpeedPresets.kbps(fromBps: 1048576), 1024) // 1MB/s
    }

    /// 웹 980~981행 — **현재 값이 목록에 없으면 끼워 넣는다.**
    ///
    /// 이게 없으면 "300KB/s 로 제한돼 있는데 선택기가 512KB/s 를 보여준다" →
    /// 사용자는 제한이 풀린 걸로 오해한다. **현재 상태 표시가 우선**이다.
    func test_현재값이_목록에_없으면_추가된다() {
        let opts = SpeedPresets.options(currentBps: 300 * 1024)
        let kb = opts.map(\.kbps)
        XCTAssertTrue(kb.contains(300), "현재 300KB/s 가 선택지에 있어야 한다")
        // 정렬 유지 — 웹은 push 만 하고 정렬하지 않지만, 정렬하는 게 낫다
        XCTAssertEqual(kb, kb.sorted(), "선택지는 오름차순이어야 한다")
    }

    /// 현재 값이 프리셋에 있으면 **중복 추가하지 않는다**
    func test_현재값이_프리셋이면_중복되지_않는다() {
        let kb = SpeedPresets.options(currentBps: 1024 * 1024).map(\.kbps)
        XCTAssertEqual(kb.filter { $0 == 1024 }.count, 1)
        XCTAssertEqual(kb, SpeedPresets.kbpsList.sorted())
    }

    /// 무제한(0)이면 목록이 **웹 목록 그대로**여야 한다
    func test_무제한이면_웹목록과_같다() {
        XCTAssertEqual(SpeedPresets.options(currentBps: 0).map(\.kbps),
                       SpeedPresets.kbpsList.sorted())
    }

    /// **라벨과 값이 어긋나면 안 된다** — 선택지가 "1MB/s" 를 보여주는데
    /// 실제로는 1KB/s 를 보낼 수는 없다.
    func test_라벨과_보내는값이_일치한다() {
        for o in SpeedPresets.options(currentBps: 5 * 1024 * 1024) {
            let back = SpeedPresets.kbps(fromBps: SpeedPresets.bps(fromKbps: o.kbps))
            XCTAssertEqual(back, o.kbps,
                           "\(o.label) 이 \(o.kbps)KB/s 라면 왕복해도 같아야 한다")
        }
    }

    /// KB/s → B/s — 웹의 `k * 1024` 와 같다
    func test_KBPS를_BPS로_변환한다() {
        XCTAssertEqual(SpeedPresets.bps(fromKbps: 0), 0)
        XCTAssertEqual(SpeedPresets.bps(fromKbps: 256), 262144)
        XCTAssertEqual(SpeedPresets.bps(fromKbps: 1024), 1048576)
        XCTAssertEqual(SpeedPresets.bps(fromKbps: 20480), 20971520)
    }
}
