import Foundation

/// **설정 창에서 "다시 찾기" 결과를 보이는 것** (T-1089).
///
/// ## 왜 별도 파일인가
///
/// 사용자가 본 그대로다:
/// > "설정 → 다시 찾기 했을때 찾았을때의 반응이 없네.
/// 설정에서 주소 채워줘야 하고 찾았습니다 또는 실패 했습니다. 등이 있어야 하는데"
///
/// `--diagnose` 실측으로 **탐색 자체는 40ms 에 정상 성공**한다.
/// 즉 버튼이 실패한 게 아니라 **결과를 말하지 않은 것**이다.
///
/// ## 실제로 없던 것 두 가지
///
/// 1. **상태 표시가 없다.** `SettingsWindow.swift` 에 `connectionLine` 참조가 **0건**이었다.
///    탐색 상태 표시(`DiscoveryBadge`, M-22)는 **팝오버에만** 있고,
///    설정 창은 별도 `NSWindow`(M-18)이므로 그 줄을 공유하지 않는다.
/// 2. **주소 자동 채움이 없다.** 주소 칸은 `@State` 이고 `onAppear` 에서만 채워진다.
///    `connect()` 가 모델 값을 바꿔도 **칸은 옛 값(또는 빈 값)** 이 남는다.
///
/// ## 왜 문구를 여기서 만든다
///
/// `DiscoveryBadge`(M-22) 와 **같은 규칙을 두 벌 쓰면 안 된다.**
/// 한쪽만 고쳐지고 테스트는 통과한 채 **화면과 진단이 다른 말을 한다** — M-27 에서 실제로 그랬다.
/// → **문구는 `DiscoveryBadge` 를 재사용**하고, 여기서는 **무엇을 보여줄지**만 정한다.
public enum SettingsDiscovery {

    /// **설정 창이 지금 말할 수 있는 상태** — 앱의 `Phase` 를 화면 언어로 옮긴 것.
    public enum State: Equatable, Sendable {
        /// 아직 아무것도 안 함
        case idle
        /// 탐색 중 — 254개 호스트를 두드리는 동안에도 **멈춰 있지 않은 것처럼 보여야 한다**
        case searching
        /// 찾음 — 어느 방법으로 붙었는지도 함께
        case found(DiscoveryStrategy)
        /// 실패 — **왜인지**까지 말해야 사용자가 뭘 할지 안다
        case failed

        /// 탐색 중에는 **다시 누를 수 없다** — 두 번 눌러 두 번 도는 것을 막는다.
        public var isButtonEnabled: Bool { self != .searching }

        /// **서버를 실제로 붙인 상태인가** — 주소·버전이 있는 쪽만 그렇다.
        ///
        /// ## 왜 필요한가
        ///
        /// **"찾았다" 는 말은 주소가 있어야 성립한다.** 주소 없이 성공이라 말하면
        /// 사용자는 *뭘* 확인한지 모른다 — 그래서 `status(_:address:version:)` 가
        /// 주소를 받지 않으면 실패 문구로 내려간다.
        ///
        /// 이 값은 **"주소·버전을 붙여서 물어도 되는가"** 를 가린다.
        public var isFoundCase: Bool {
            if case .found = self { return true }
            return false
        }
    }

    // MARK: - 상태 한 줄

    /// **사용자에게 보여줄 한 줄.**
    ///
    /// - Parameters:
    ///   - state: 지금 상태.
    ///   - address: 붙은 서버 주소. `found` 에서 필수.
    ///   - version: 서버 버전. 모르면 `nil`.
    /// - Returns: 표시할 문자열. **빈 문자열이 되면 안 된다.**
    ///
    /// ## 왜 "찾았습니다"/"실패했습니다" 만으로 안 되나
    ///
    /// 성공해도 **어느 폰인지 알 수 없다.** 주소·버전·방법이 있어야
    /// 폰 설정 화면과 숫자를 맞춰 볼 수 있고, 네트워크가 바뀌어
    /// **조용히 다른 폰에 붙었는지**도 드러난다.
    ///
    /// → 문구 구성은 `DiscoveryBadge` 가 이미 한다(M-22). **여기서 새로 만들지 않는다.**
    public static func status(_ state: State, address: String?, version: String?) -> String {
        switch state {
        case .idle:
            return "아직 찾지 않았습니다 — 아래에서 주소를 직접 입력할 수 있습니다"
        case .searching:
            return DiscoveryBadge.scanningLine()
        case .found(let strategy):
            // **주소가 없으면 "찾았다" 는 말이 성립하지 않는다.**
            // 주소 없이 성공이라 말하면 사용자는 뭘 확인한지 모른다.
            guard let address, !address.isEmpty else { return DiscoveryBadge.failureLine() }
            return DiscoveryBadge.line(strategy: strategy,
                                      address: address,
                                      version: version,
                                      isManual: strategy == .manual)
        case .failed:
            return DiscoveryBadge.failureLine()
        }
    }

    // MARK: - 주소 칸

    /// **주소 칸에 무엇을 보여줄까.**
    ///
    /// - Parameters:
    ///   - current: 사용자가 **지금 칸에 입력해 둔 값**. 실패하면 이게 지켜져야 한다.
    ///   - found: 방금 찾은 주소. 성공했을 때만 온다.
    /// - Returns: 칸에 넣을 값.
    ///
    /// ## ★ 왜 실패하면 `current` 를 그대로 둔다 — 이게 이 함수의 핵심
    ///
    /// 흔한 구현은 "탐색이 끝나면 칸을 결과로 덮어쓴다" 다.
    /// 그러면 **실패했을 때 사용자가 직접 타이핑한 주소가 지워진다.**
    ///
    /// 사용자는 "자동으로 안 찾아졌으니 내가 이 주소로 직접 붙어 본다" 하고 입력하는데,
    /// 그 입력은 아직 시도되지 않은 **유일한 단서**다. 지우면 이유를 잃는다.
    /// → **성공했을 때만 덮어쓴다.**
    ///
    /// ## 왜 `found` 가 비면 `current` 인데, 비어 있으면 그대로 빈 값인가
    ///
    /// 그렇다. 처음 실행에서 아무것도 못 찾았을 때 칸이 빈 것은 **정상**이고,
    /// 거기에 "없음" 을 굳이 적어 넣을 이유가 없다.
    public static func addressField(current: String, found: String?) -> String {
        guard let found, !found.isEmpty else { return current }
        return found
    }

    // MARK: - 색

    /// **상태 한 줄의 색** — 글자와 같은 판단으로 정한다.
    ///
    /// ## 왜 실패만 주황인가
    ///
    /// M-22 교훈 그대로다. **"연결됐다" 고 초록인데 주황이면 거짓말로 읽힌다.**
    /// 찾았으면 보통색(차가운 기본)에 두고, **실패할 때만** 눈에 띄게 한다.
    /// 탐색 중엔 회색 — 아직 결과가 없는데 색부터 말하면 거짓말이다.
    public enum Tone: Equatable, Sendable {
        /// 아직 결과 없음 — 눈에 띄지 않게
        case neutral
        /// 탐색 중
        case working
        /// 찾음
        case ok
        /// 실패 — **눈에 띄어야 한다**
        case bad

        public init(_ state: State) {
            switch state {
            case .idle: self = .neutral
            case .searching: self = .working
            case .found: self = .ok
            case .failed: self = .bad
            }
        }
    }
}
