import XCTest
@testable import DroidRelayCore

/// **기기 속도 스파이크 제거** — `SpeedHistory.smoothed` · `SpeedSpike`.
///
/// ## 이 테스트가 막는 실제 사고
///
/// 2026-09-29 실측. 기기(폰 전체) 속도의 카운터가 **주기적으로 보충**된다:
/// ```
/// 정상 구간   1 ~ 5 KB/s        ← 실제 트래픽
/// 보충 구간   2 ~ 5 MB/s        ← 1000배 튀었다가 **바로** 내려옴
/// ```
/// 나눗셈은 정확했다. **나눈 대상이 보충분이었다.**
///
/// 이것이 그래프에 들어가면 Y축이 5 MB/s 가 되어 **실제 트래픽이 바닥에 눌린다.**
/// 사용자는 "5 MB/s 다" 고 읽고 정작 실제로는 5 KB/s 인 상황을 5 MB/s 로 오해한다.
final class SpeedSpikeTests: XCTestCase {

    /// **실측에서 그대로 가져온 패턴** — 정상/보충이 섞인 실제 수열.
    private let realPattern: [Int] = [
        2_500, 2_415_000, 1_300, 2_000, 1_900, 1_600,
        5_071_000, 2_100, 2_600, 2_300, 3_984_000, 609_000, 205_000,
        1_400, 4_600, 4_635_000, 1_200, 18_000, 1_600,
    ]

    // MARK: - 중앙값

    /// **홀수 개는 가운데 값.**
    func test_중앙값_홀수() {
        XCTAssertEqual(SpeedSpike.median([3, 1, 2]), 2)
    }

    /// **짝수 개는 가운데 둘의 평균.**
    func test_중앙값_짝수() {
        XCTAssertEqual(SpeedSpike.median([1, 2, 3, 4]), 2)   // (2+3)/2
    }

    /// **빈 배열은 기준이 없다** — `nil` 이고 판정하지 않는다.
    func test_중앙값_빈배열() {
        XCTAssertNil(SpeedSpike.median([]))
    }

    /// **평균이 아니라 중앙값인 이유** — 스파이크가 기준을 끌고 가지 않는다.
    ///
    /// 이게 핵심이다. 평균이면 `[10, 10, 10, 5_000_000]` 의 평균이 125 만이 되어
    /// **그 다음 스파이크가 "정상" 으로 판정**된다. 중앙값은 10 이라 계속 걸러낸다.
    func test_중앙값은_스파이크에_끌려가지_않는다() {
        let withSpike = [10, 10, 10, 5_000_000]
        XCTAssertEqual(SpeedSpike.median(withSpike), 10, "스파이크 하나가 기준을 바꾸면 안 된다")
    }

    // MARK: - 판정

    /// **기준이 0 이면 아무것도 걸러내지 않는다** — 여기가 가장 위험한 자리다.
    ///
    /// "0 보다 큰 모든 것" 을 스파이크로 보면 **트래픽이 없는 구간에서 시작하는
    /// 모든 실제 전송을 버리게 된다.** 조용히 지워진 데이터가 된다.
    func test_기준이_0이면_판정하지_않는다() {
        XCTAssertFalse(SpeedSpike.isSpike(5_000_000, baselineMedian: 0),
                       "평소 트래픽이 0 이면 실제 전송을 버리면 안 된다")
    }

    /// **기준이 nil 이면 판정하지 않는다** — 과거가 없다.
    func test_기준이_없으면_판정하지_않는다() {
        XCTAssertFalse(SpeedSpike.isSpike(5_000_000, baselineMedian: nil))
    }

    /// **1회짜리 급증은 걸러진다.**
    func test_1회짜리_급증은_걸러진다() {
        XCTAssertTrue(SpeedSpike.isSpike(5_000_000, baselineMedian: 2_000))
    }

    /// **정상 변동은 통과시킨다** — 흔들림은 스파이크가 아니다.
    func test_정상_변동은_통과한다() {
        XCTAssertFalse(SpeedSpike.isSpike(3_000, baselineMedian: 2_000),
                       "1.5배는 흔들림이지 스파이크가 아니다")
    }

    // MARK: - 실전 전송량은 살아남아야 한다 (이 수정이 실패하는 방향)

    /// **실제 다운로드는 버려지지 않는다** — 이게 이 수정이 실패하는 방향이다.
    ///
    /// 다운로드가 시작되면 **몇 초 안에** 높은 값이 연달아 들어온다.
    /// 첫 프레임만 걸리고 **이후에는 기준 자체가 올라가** 살아남아야 한다.
    func test_지속되는_다운로드는_버려지지_않는다() {
        // 평소 2KB/s → 다운로드 시작 후 5MB/s 가 계속 이어지는 실제 상황
        var pattern: [Int] = Array(repeating: 2_000, count: 10)
        pattern += Array(repeating: 5_000_000, count: 10)
        let out = SpeedSpike.smooth(pattern)

        // **시작 직후 몇 개만 걸리고, 나머지는 살아남아야 한다**
        let survived = out.filter { $0 > 0 && $0 > 100_000 }.count
        XCTAssertGreaterThan(survived, 0, "실제 다운로드가 전부 지워지면 안 된다")
        XCTAssertEqual(out.count, pattern.count, "점 개수는 바뀌면 안 된다")
    }

    /// **다운로드 중반에는 확실히 살아난다** — 기준이 이미 올라간 상태다.
    func test_다운로드_중반은_확실히_살아남는다() {
        var pattern: [Int] = Array(repeating: 2_000, count: 10)
        pattern += Array(repeating: 5_000_000, count: 20)
        let out = SpeedSpike.smooth(pattern)
        let tail = out.suffix(10)
        for v in tail {
            XCTAssertEqual(v, 5_000_000, "기준이 올라간 뒤에는 정상 값이다 — 지우면 실제 전송을 숨긴다")
        }
    }

    /// **끝나면 다시 0 으로 내려온다** — 다음 구간도 정상 판정된다.
    func test_트래픽이_끝나면_다시_정상이다() {
        var pattern: [Int] = Array(repeating: 2_000, count: 5)
        pattern += [5_000_000, 5_000_000, 5_000_000, 5_000_000, 5_000_000]
        pattern += Array(repeating: 2_000, count: 5)
        let out = SpeedSpike.smooth(pattern)
        for v in out.suffix(5) {
            XCTAssertEqual(v, 2_000, "트래픽이 끝나면 평소 값으로 돌아온다")
        }
    }

    // MARK: - 실측 수열

    /// **실측 패턴에서 1회짜리 스파이크만 제거된다.**
    func test_실측_패턴에서_스파이크만_제거된다() {
        let out = SpeedSpike.smooth(realPattern)
        XCTAssertEqual(out.count, realPattern.count, "점 개수 유지")
        // 첫 값은 기준이 없어 그대로
        XCTAssertEqual(out[0], realPattern[0])
        // 보충 구간이 0 이 되어야 한다
        XCTAssertEqual(out[1], 0, "2.4 MB/s 보충분은 제거된다")
        XCTAssertEqual(out[6], 0, "5.0 MB/s 보충분은 제거된다")
        // 정상 구간은 그대로
        XCTAssertEqual(out[2], 1_300, "정상 구간은 보존된다")
    }

    /// **실측 패턴의 Y축 최대값이 정상 범위로 내려온다** — 이것이 사용자가 본 문제다.
    ///
    /// 제거 전에는 축이 5 MB/s 였고, 실제 트래픽(1~5 KB/s)은 바닥에 눌렸다.
    func test_축이_정상_범위로_내려온다() {
        let before = realPattern.max() ?? 0
        let after = SpeedSpike.smooth(realPattern).max() ?? 0
        XCTAssertGreaterThan(before, 4_000_000, "제거 전에는 축이 수 MB 다")
        XCTAssertLessThan(after, 100_000, "제거 후에는 축이 KB 급이다 — 실제: \(after)")
    }

    /// **`peak` 도 걸러진 값으로 산다** — 축 라벨이 원본을 쓰면 고쳐도 그대로다.
    func test_peak도_걸러진_값을_쓴다() {
        let h = SpeedHistory(capacity: 100)
        var t = Date()
        for v in realPattern {
            _ = h.push(SpeedReading(downBps: 0, upBps: v), now: t)
            t = t.addingTimeInterval(1)
        }
        XCTAssertLessThan(h.peak(false), 100_000, "축이 MB 급이면 고쳐지지 않은 것이다")
    }

    // MARK: - 경계

    /// **배열이 1개면 그대로** — 비교할 과거가 없다.
    func test_한개만_있으면_그대로() {
        XCTAssertEqual(SpeedSpike.smooth([9_999_999]), [9_999_999])
    }

    /// **빈 배열은 빈 배열** — 크래시하지 않는다.
    func test_빈배열은_빈배열() {
        XCTAssertEqual(SpeedSpike.smooth([]), [])
    }

    /// **전부 0 이어도 안전** — 나누기·비교 예외가 없다.
    func test_전부_0이면_안전하다() {
        XCTAssertEqual(SpeedSpike.smooth([0, 0, 0, 0]), [0, 0, 0, 0])
    }
}
