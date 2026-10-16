/// **팝오버 하단 결과 안내** — 성공·정보는 스스로 사라지고, **실패는 남는다.**
///
/// ## 왜 종류(kind)를 데이터로 두는가
///
/// 이전에는 `lastResult: String` 하나뿐이었다. 그래서
///
/// 1. 성공("다운로드 3건 추가")에도 **경고 삼각형 아이콘**이 붙었다 —
///    성공을 문제처럼 보이게 하는 것은 거짓 신호다.
/// 2. 주석은 "성공은 화면이 증거라 띄우지 않는다"라고 했는데 실제로는 성공도
///    `lastResult` 에 들어가 **영구히 남았다**. 그러면 사용자는
///    "이 안내가 아직 유효한가" 를 알 수 없다(닫기 버튼을 찾게 된다).
///
/// → **종류** 를 값으로 두고, 아이콘·색·자동 소멸을 여기서 파생시킨다.
/// 타이머 자체는 AppKit 몫이지만 **무엇이 사라지고 무엇이 남는가** 는
/// 순수 계산이라 테스트로 고정한다.
public struct Notice: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        /// 쓰기 동작이 실제로 성공했다 — 잠깐 보여주고 지운다.
        case success
        /// 진행/참고 안내("받는 중", "재생 — IINA") — 성공과 같이 지운다.
        case info
        /// **서버가 거절했거나 열지 못했다 — 지우지 않는다.**
        ///
        /// 실패가 조용히 사라지면 사용자는 성공으로 믿는다(무음 실패 금지).
        case error
    }

    public var text: String
    public var kind: Kind

    public init(text: String, kind: Kind) {
        self.text = text
        self.kind = kind
    }

    /// **실패인가.** 아이콘·색이 여기서 갈린다(경고 vs 체크).
    public var isError: Bool { kind == .error }

    /// **스스로 사라지는가.** `false` 면 닫기 버튼을 누를 때까지 남는다.
    public var autoDismisses: Bool { !isError }

    /// 성공·정보가 화면에 머무는 시간(초). 너무 짧으면 못 읽고, 길면 방해된다.
    public static let autoDismissSeconds: Double = 4

    /// 성공/실패를 `Bool` 로 이미 아는 자리에서 쓰는 편의 생성자.
    ///
    /// `ok` 하나로 **텍스트와 종류를 같이** 정한다 — 둘을 따로 넘기다
    /// "실패 문구인데 성공 종류" 로 어긋나는 일을 막는다.
    public static func result(ok: Bool, _ text: String) -> Notice {
        Notice(text: text, kind: ok ? .success : .error)
    }
}
