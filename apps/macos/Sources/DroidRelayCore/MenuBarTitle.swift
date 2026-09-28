import Foundation

/// 메뉴바 항목이 **그릴 문자열**을 만드는 순수 로직 (M-14).
///
/// ## 왜 문자열 조립을 `MenuTitleView` 밖으로 뺐는가
///
/// 뷰(`NSView`)는 단위 테스트를 할 수 없다 — `@testable import` 로 실행 파일
/// 모듈을 테스트 타깃에 붙이는 순간 컴파일이 깨진다(실측). 그래서
/// "무엇을 그릴지"를 여기서 결정하고, 뷰는 **그리기만** 한다.
///
/// **이 분리 덕에 잡힌 것** — "3줄이 메뉴바 높이(24pt)를 넘어간다" 는 사실을
/// 눈으로 보기 전부터 알 수 있었다. 뷰 안에 박아 두었으면 실측까지 몰랐을 거다.
public enum MenuBarTitle {

    /// 한 줄. 배지만 있고 속도는 별도 줄로 간다(줄 수를 안정적으로 유지하려고).
    public struct Head: Equatable, Sendable {
        public var badge: Int
        public init(badge: Int) { self.badge = max(0, badge) }
        /// 배지가 0 이면 숫자를 붙이지 않는다 — " 0" 은 작업이 없는데 진행 중처럼 보인다.
        public var showsBadge: Bool { badge > 0 }
    }

    /// 한 줄. `values` 가 비면 이 줄은 그리지 않는다.
    public struct SpeedRow: Equatable, Sendable {
        public enum Direction: String, Equatable, Sendable {
            case up, down
            /// 사용자가 지정한 표기: 방향만 알면 된다(아이콘 아님).
            public var symbol: String { self == .up ? "\u{2191}" : "\u{2193}" }
            public var label: String { self == .up ? "업" : "다운" }
        }
        public var direction: Direction
        public var values: [String]
        public init(direction: Direction, values: [String]) {
            self.direction = direction
            self.values = values
        }
        /// **값이 없으면 줄을 만들지 않는다** — `0` 을 대신 넣지 않는다.
        /// 0 과 "값 없음" 은 다른 상태다.
        public var isEmpty: Bool { values.allSatisfy { $0.isEmpty } }
    }

    /// 그릴 줄 전체 — 위에서 아래 순서.
    public static func lines(head: Head, up: SpeedRow?, down: SpeedRow?) -> [MenuBarLine] {
        var out: [MenuBarLine] = [.head(head)]
        if let up, !up.isEmpty { out.append(.speed(up)) }
        if let down, !down.isEmpty { out.append(.speed(down)) }
        return out
    }

    /// 한 줄. `.head` 는 배지 자리, `.speed` 는 방향 + 값들.
    public enum MenuBarLine: Equatable, Sendable {
        case head(Head)
        case speed(SpeedRow)

        public var text: String {
            switch self {
            case .head(let h):
                return h.showsBadge ? " \(h.badge)" : ""
            case .speed(let r):
                return "\(r.direction.symbol) \(r.values.joined(separator: "  "))"
            }
        }

        /// 값은 고정되지만 **방향 기호는 고정폭이 아니다** — 고정폭으로 잡으면
        /// 숫자 정렬이 좋아지는 대신 방향이 어중간해진다. 방향은 이미 1자로 고정이라 무의미하다.
        public var valueText: String {
            switch self {
            case .head: return ""
            case .speed(let r): return r.values.joined(separator: "  ")
            }
        }
    }

    /// `AppModel` 의 상태에서 줄을 만든다 — 뷰와 테스트가 같은 경로를 쓴다.
    ///
    /// **못 쓰는 출처는 열을 만들지 않는다.** 서버가 값을 안 주는데도 0 을 넣어두면
    /// 사용자는 "고장 났구나" 를 화면에서 바로 읽는다. 빈칸보다 없는 편이 정직하다.
    ///
    /// ## 왜 1줄인가 (실측)
    ///
    /// 원래 설계는 **2줄**(↑ / ↓)이었다. 그런데 메뉴바 두께는 **22pt** 이고
    /// 10.5pt 글꼴 한 줄이 13pt 다. 2줄이면 27pt 로 **5pt 가 잘린다** — 방향 표시가
    /// 통째로 배 밖으로 나가서 사용자는 **아무것도 못 봤다.**
    /// 9pt 로 줄여도 23pt 라 들어가지 않는다. 시스템 메뉴바 크기 설정을 사용자가
    /// 키우지 않는 한 **22pt 에는 1줄만 들어간다.**
    ///
    /// 그래서 한 줄에 압축해서 넣는다: `↑9K ↓450K`. 정보는 살아 있고, 잘리지 않는다.
    public static func lines(
        badge: Int,
        droid: SpeedReading,
        device: SpeedReading?,
        includeDroid: Bool,
        includeDevice: Bool
    ) -> [MenuBarLine] {
        var pairs: [(SpeedRow.Direction, up: String, down: String)] = []
        // 순서를 고정한다 — 열 순서가 실행마다 바뀌면 눈으로 추적할 수 없다.
        if includeDroid {
            pairs.append((.up, up: SpeedFormat.compact(droid.upBps), down: SpeedFormat.compact(droid.downBps)))
        }
        if includeDevice, let device {
            pairs.append((.up, up: SpeedFormat.compact(device.upBps), down: SpeedFormat.compact(device.downBps)))
        }
        guard !pairs.isEmpty else { return [.head(Head(badge: badge))] }

        // **출처별로 값을 하나씩** 넘긴다 — 문자열로 합치면 열 분리가 불가능해진다.
        // (합쳐서 넘겼을 때 기기 값이 잘려 화자가 "칸이 안 맞는다" 고 했다)
        let up = pairs.map(\.up)
        let down = pairs.map(\.down)
        return [
            .head(Head(badge: badge)),
            .speed(SpeedRow(direction: .up, values: up)),
            .speed(SpeedRow(direction: .down, values: down))
        ]
    }
}
