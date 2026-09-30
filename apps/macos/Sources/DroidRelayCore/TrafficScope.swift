import Foundation

/// **기기 트래픽을 어디까지 셀 것인가** (M-27, 3구간은 M-28).
///
/// ## 왜 세 구간인가 (M-28)
///
/// M-27 은 두 구간(`external` / `all`) 이었다. **실측에서 결함이 드러났다.**
///
/// 통제 실험 (11MB 다운로드, 실제 전송 확인됨):
/// ```
/// swlan0      Δtx = 11,693,373   ← 실제로 클라이언트에 전송된 양 (11.36MB) ★ 일치
/// rmnet_data1 Δrx =    295,326   ← 셀룰러 경유 2.6%뿐
/// external    Δrx =          0   ← ★ 화면에 0 이 뜬다
/// ```
///
/// `external` = `rmnet*`(셀룰러)만 세므로 **핫스팟 ↔ 클라이언트 구간이 통째로 누락**된다.
/// 이 앱의 목적이 핫스팟으로 파일을 주고받는 것인데, **그게 안 보이는 지표**였다.
/// → **핫스팟 구간을 독립값으로 분리**한다.
///
/// ## 세 구간
///
/// | 구간 | 의미 | 서버가 세는 것 |
/// |---|---|---|
/// | `.external` (**기본**) | 폰이 **셀룰러로** 나간 양 | 활성 `rmnet*` (오프로드 제외) |
/// | `.hotspot` | **클라이언트와 핫스팟으로** 주고받은 양 | `swlan0` (SoftAP) |
/// | `.all` | 기기 전체 | `getTotalRx/TxBytes` |
///
/// ## 왜 기본이 "외부만" 인가
///
/// "기기가 밖으로 얼마나 씀인가" 를 묻는 것이 지표의 목적이다.
/// 핫스팟 내부 트래픽은 **같은 기기 안에서 도는 것**이라
/// 밖으로 나간 용량이 아니다. → **기본값을 외부만 으로 한다.**
public enum TrafficScope: String, CaseIterable, Sendable {
    /// 활성 `rmnet*` — 폰이 셀룰러로 나갔다 오는 양. **기본값.**
    case external
    /// `swlan0` — 클라이언트와 핫스팟으로 주고받은 양. (M-28)
    case hotspot
    /// 모든 인터페이스 — 핫스팟 내부를 포함한다.
    case all

    public static let `default`: TrafficScope = .external

    public var label: String {
        switch self {
        case .external: return "외부만"
        case .hotspot: return "핫스팟"
        case .all: return "전체"
        }
    }

    /// **설정 화면에 붙는 설명** — 무슨 차이가 있는지 한 줄로.
    ///
    /// ## 왜 핫스팟 설명이 가장 중요하냐 (M-28)
    ///
    /// **11MB 를 내려줬는데 "외부만" 이 0 을 보인다.** 사용자는 이게
    /// "설정이 안 먹혔다" 고 생각한다. 실제로는 **세는 대상이 달랐던 것**이다.
    /// → 이 문장이 그 오해를 먼저 없앤다.
    public var explanation: String {
        switch self {
        case .external:
            return "핫스팟 내부를 뺀, 기기가 셀룰러로 나갔다 오는 양만 셉니다"
        case .hotspot:
            return "폰↔클라이언트(핫스팟)로 주고받은 양입니다. 파일을 주고받을 때 여기에 잡힙니다"
        case .all:
            return "핫스팟 내부 트래픽까지 모두 포함합니다. 핫스팟을 쓸 땐 실제보다 크게 보입니다"
        }
    }


    /// **클라이언트가 서버에 요구할 값.** 쿼리 파라미터로 그대로 실린다.
    public var queryValue: String { rawValue }

    /// **서버가 실제로 쓴 값** — 응답의 `scope` 로 돌아온다.
    ///
    /// **서버가 다른 값을 썼을 수 있다** — 옛 버전 서버는 이 파라미터를 모른다.
    /// **요구한 것과 실제로 쓰인 것을 구분해야**
    /// "내가 외부만 보기로 했는데 왜 핫스팟 값이 나오지" 를 설명할 수 있다.
    public static func parse(_ raw: String?) -> TrafficScope? {
        guard let raw else { return nil }
        return TrafficScope(rawValue: raw)
    }

    // MARK: - 불일치 설명

    /// **요구한 범위와 서버가 실제로 쓴 범위가 다를 때만 문장을 만든다.**
    ///
    /// ## 세 경우가 있고, 셋 중 하나만 사용자에게 말한다
    ///
    /// | 서버가 쓴 값 | 의미 | 말하는가 |
    /// |---|---|---|
    /// | 요청과 같음 | 정상 | **아무 말도 하지 않는다** |
    /// | `nil` (구버전) | 서버가 이 필드를 모름 | **"전체로 셉니다"** |
    /// | 요청과 다름 | **설정이 안 먹혔다** | **둘 다 말해 준다** |
    ///
    /// ## 왜 "아무 말도 하지 않는다" 도 계약인가
    ///
    /// 이 프로젝트 규칙: **문제가 생겼을 때만 눈에 띄게 남긴다.**
    /// 평범한 상태에도 문구를 붙이면 **사용자가 경고를 무시하게 된다.**
    /// → **정상일 때 `nil` 을 돌려주는 것도 설계다.** (테스트로 고정)
    ///
    /// ## 왜 `nil` 을 "전체" 로 가정하지 않는가
    ///
    /// "외부만" 으로 설정했는데 `nil` 이라서 **"전체" 라고 가정하면**
    /// 사용자는 **"설정이 안 먹혔다"** 고 생각하고 앱을 의심한다.
    /// 실제로는 **서버가 예전 버전**인 거다. → **다른 문제로 말한다.**
    ///
    /// - Returns: 설명 문장. **정상이면 `nil`.**
    public static func mismatchNote(requested: TrafficScope, used: TrafficScope?) -> String? {
        guard let used else {
            return "이 서버는 범위 설정을 지원하지 않아 기기 전체로 셉니다"
        }
        guard used != requested else { return nil }
        return "설정은 ‘\(requested.label)’ 이지만 서버는 ‘\(used.label)’ 로 셉니다"
    }
}

/// **기기 트래픽 범위 설정** — `UserDefaults` 에 저장되는.
public struct TrafficScopeSetting: Equatable, Sendable {
    public var scope: TrafficScope

    public static let `default` = TrafficScopeSetting(scope: .default)

    public init(scope: TrafficScope) { self.scope = scope }

    private enum Key { static let scope = "net.trafficScope" }

    /// **값이 없으면 "외부만"** 이다.
    ///
    /// `bool(forKey:)` 처럼 "없으면 false" 로 떨어뜨리지 않는다 —
    /// `UserDefaults` 에 없는 것과 `false` 를 같은 값으로 보면
    /// **기본값이 바뀐 것을 아무도 모른다.**
    public static func load(_ defaults: UserDefaults = .standard) -> TrafficScopeSetting {
        let raw = defaults.string(forKey: Key.scope)
        return TrafficScopeSetting(scope: TrafficScope.parse(raw) ?? .default)
    }

    public func save(_ defaults: UserDefaults = .standard) {
        defaults.set(scope.rawValue, forKey: Key.scope)
    }
}
