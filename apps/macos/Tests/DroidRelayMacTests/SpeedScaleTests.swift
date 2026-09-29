import XCTest
import CoreGraphics
@testable import DroidRelayCore

/// **그래프 축 라벨** — `SpeedScale`.
///
/// ## 이 테스트가 지키는 계약
///
/// 2026-09-29 실측. 기기(폰 전체) 카운터는 **지연 정산** 때문에 주기적으로 뛴다:
/// ```
/// 정상 구간   1 ~ 5 KB/s
/// 보충 구간   2 ~ 5 MB/s      ← 1000배 튀었다가 바로 내려옴
/// ```
///
/// 앱에서 이걸 **제거하려 세 가지를 시도하고 전부 실패**했다:
/// 1. `NetworkStatsManager` → `SecurityException: uid -1 is forbidden` (권한 필요)
/// 2. `/proc/net/dev` · `/sys/class/net` → SELinux denied
/// 3. 서버 1초 폴링 → **정산이 네트워크 계층이라 무의미**(0.05초로도 5 MB 그대로)
///
/// 값으로 필터링하면 **3초짜리 실제 다운로드까지 사라진다.** 그래서 **지우지 않고
/// 축 라벨에 "평시" 를 함께 보여준다.** 이 테스트가 그 계약을 지킨다.
final class SpeedScaleTests: XCTestCase {

    /// 2026-09-29 `/api/net/speed` 20회 1초 간격 실측 수열.
    private let real: [Int] = [
        2_500, 2_415_000, 1_300, 2_000, 1_900, 1_600,
        5_071_000, 2_100, 2_600, 2_300, 3_984_000, 609_000, 205_000,
        1_400, 4_600, 4_635_000, 1_200, 18_000, 1_600,
    ]

    // MARK: - 중앙값

    func test_중앙값_홀수() { XCTAssertEqual(SpeedScale.median([3, 1, 2]), 2) }
    func test_중앙값_짝수() { XCTAssertEqual(SpeedScale.median([1, 2, 3, 4]), 2) }
    func test_중앙값_빈배열() { XCTAssertNil(SpeedScale.median([])) }

    /// **평균이 아니라 중앙값인 이유** — 스파이크가 기준을 끌고 가지 않는다.
    ///
    /// 평균이면 `[10,10,10,5_000_000]` 이 125 만이 되어 **"평시 125 MB/s"** 라고
    /// 말하게 된다. 중앙값은 10 이라 **정확한 평시**를 말한다.
    func test_중앙값은_스파이크에_끌려가지_않는다() {
        XCTAssertEqual(SpeedScale.typical([10, 10, 10, 5_000_000]), 10)
    }

    // MARK: - 축 라벨

    /// **스파이크가 없으면 축 하나만** — 같은 말을 두 번 하지 않는다.
    func test_스파이크가_없으면_축만_보인다() {
        let s = SpeedScale.axisLabel(peak: 5_000_000, typical: 4_800_000)
        XCTAssertEqual(s, SpeedFormat.axis(5_000_000), "비슷하면 붙이지 않는다")
        XCTAssertFalse(s.contains("보통"), "같은 말을 두 번 하면 읽는 사람이 답을 못 얻는다")
    }

    /// **튀면 "평시" 를 함께 보여준다** — 사용자가 급증과 평소를 구분한다.
    func test_튀면_평시를_함께_보인다() {
        let s = SpeedScale.axisLabel(peak: 5_000_000, typical: 2_000)
        // `SpeedFormat.axis` 는 1자리로 표시한다 (5,000,000 → "4.8 MB/s")
        XCTAssertEqual(s, "4.8 MB/s · 보통 2 KB/s", "최대값과 평시가 함께 보인다")
    }

    /// **평시를 모르면 축만** — 없는 값을 지어내지 않는다.
    func test_평시를_모르면_축만_보인다() {
        XCTAssertEqual(SpeedScale.axisLabel(peak: 5_000_000, typical: nil),
                       SpeedFormat.axis(5_000_000))
    }

    /// **평시가 0 이면 붙이지 않는다** — "보통 0 B/s" 는 아무 정보가 아니다.
    func test_평시가_0이면_붙이지_않는다() {
        XCTAssertEqual(SpeedScale.axisLabel(peak: 5_000_000, typical: 0),
                       SpeedFormat.axis(5_000_000))
    }

    /// **라벨이 한 줄이어야 한다** — 개행이 있으면 그래프 영역을 밀어낸다.
    func test_라벨은_한줄이다() {
        let s = SpeedScale.axisLabel(peak: 5_000_000, typical: 2_000)
        XCTAssertFalse(s.contains("\n"), "개행이 있으면 그래프가 밀린다: \(s)")
    }

    /// **352pt 팝오버에 들어갈 길이** — 9pt 모노스페이스 기준.
    func test_라벨이_팝오버_폭에_맞는다() {
        let s = SpeedScale.axisLabel(peak: 5_000_000_000, typical: 2_000)
        XCTAssertLessThan(s.count, 32, "그래프 위에 놓이는 라벨이다 — 실제: \(s)")
    }

    // MARK: - 실측 수열

    /// **실측 수열로 만든 라벨이 사용자가 본 문제를 설명한다.**
    ///
    /// 사용자가 본 화면은 라벨 `5.3 MB/s` + 범례 `기기 ↓4K ↑17K` 다.
    /// 새 라벨은 **"5.3 MB/s · 보통 17 KB/s"** 가 되어 **왜 그 두 값이 다른지
    /// 설명하지 않아도 읽힌다.**
    func test_실측_수열로_라벨이_설명한다() {
        let h = SpeedHistory(capacity: 100)
        var t = Date()
        for v in real {
            _ = h.push(SpeedReading(downBps: 0, upBps: v), now: t)
            t = t.addingTimeInterval(1)
        }
        let label = SpeedScale.axisLabel(peak: h.peak(false), typical: h.typical(false))
        XCTAssertTrue(label.contains("보통"), "평시가 붙어야 한다 — 실제: \(label)")
        // **평시는 KB 급이어야 한다** — 실측에서 정상 구간이 1~5 KB/s 였다
        XCTAssertTrue(label.contains("KB/s"), "평시가 KB 급이다 — 실제: \(label)")
    }

    /// **원본 값이 보존된다** — 이 수정이 실패하는 방향.
    ///
    /// M-23 은 스파이크를 **0 으로 바꿔** 축을 고쳤지만, **3초짜리 실제 다운로드를
    /// 전부 지웠다.** 지금은 **지우지 않는다.**
    func test_원본_값이_보존된다() {
        let h = SpeedHistory(capacity: 100)
        var t = Date()
        for v in real {
            _ = h.push(SpeedReading(downBps: 0, upBps: v), now: t)
            t = t.addingTimeInterval(1)
        }
        XCTAssertEqual(h.series(false), real, "그래프가 그리는 값은 원본 그대로다")
    }

    /// **3초짜리 실제 다운로드가 사라지지 않는다** — M-23 의 실제 결함.
    func test_짧은_다운로드가_사라지지_않는다() {
        let dl: [Int] = Array(repeating: 2_000, count: 10)
            + [5_000_000, 5_000_000, 5_000_000]
            + Array(repeating: 2_000, count: 10)
        let h = SpeedHistory(capacity: 100)
        var t = Date()
        for v in dl {
            _ = h.push(SpeedReading(downBps: 0, upBps: v), now: t)
            t = t.addingTimeInterval(1)
        }
        let s = h.series(false)
        XCTAssertEqual(s[10], 5_000_000, "3초짜리 실제 다운로드 첫 초")
        XCTAssertEqual(s[11], 5_000_000, "둘째 초")
        XCTAssertEqual(s[12], 5_000_000, "셋째 초 — 0 이 되면 실제 전송을 숨긴 것이다")
    }
}
