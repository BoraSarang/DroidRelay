import Foundation

/// **삭제 전에 보여줄 확인 정보** — 무엇을 지우는지, 되돌릴 수 있는지.
///
/// ## 왜 이게 별도 파일인가
///
/// 이 화면을 만들며 **사용자 토렌트 2건을 지웠다.** 원인은 단순했다:
/// 삭제 버튼이 확인 없이 곧바로 서버를 때렸다.
///
/// 그래서 삭제는 두 단계를 강제한다.
/// 1) 화면에서 **무엇이 사라지는지** 보여주고
/// 2) 사용자가 **명시적으로 확인**한 뒤에만 서버를 때린다.
///
/// ## 문구를 종류마다 다르게 쓰는 이유
///
/// 서버는 **제거 대상이 세 종류마다 다르다.** 한 문구로 통일하면
/// "되돌릴 수 있습니다" 라고 말해 놓고 실제로는 파일을 지우는 사고가 난다.
///
/// | 종류 | 서버 동작 | 파일 |
/// |---|---|---|
/// | 잡 | `DownloadEngine.cancel` | **partial·done 파일까지 삭제** |
/// | 토렌트(완료) | 목록에서만 제거 | **그대로 남음** |
/// | 토렌트(미완료) | 쓰레기 정리 | **받은 조각 삭제됨** |
/// | 보관함 | 휴지통으로 이동 | **휴지통에서 복원 가능** |
///
/// ── 서버 근거 ──
/// - `JobRoutes` 142행 → `DownloadEngine.cancel`
/// - `DownloadEngine.cancel` 193~194행 → `partialFile()?.delete()`, `doneFile()?.delete()`
/// - `TorrentEngine.cancel` 830~831행 → `if (isComplete) { /* 목록만 */ }`
/// - `StorageRoutes` → 휴지통 이동 + `trash/restore`
///
/// ## 왜 **마크다운 문자열이 아니라** `(텍스트, 강조)` 조각으로 저장하는가
///
/// 처음엔 `body` 에 `**굵게**` 마크다운을 넣고 화면에서
/// `AttributedString(markdown:)` 로 해석하게 했다. **두 가지 사고가 났다.**
///
/// 1. `Text("")` 에는 마크다운이 없어서 **`**` 가 그대로 화면에 나왔다.**
/// 2. 해석이 되게 한 뒤에는 **파일명에 포함된 `*` `_` 가 서식 기호로 먹혔다.**
///    `__agent_test__` 가 화면에 **`agent_test`** 로 나갔다.
///    파일명에 밑줄이나 별표가 있는 건 흔하다 → **이름이 틀리면 무엇을 지우는지도 틀려진다.**
///
/// 그래서 **문자열 해석을 아예 하지 않는다.**
/// 강조가 필요한 곳만 **구조로 표시**하고, 파일명은 **원본 그대로** 넣는다.
public struct ConfirmSpec: Equatable, Sendable {

    /// **본문의 한 조각** — 텍스트와, 강조할지 여부.
    public struct Part: Equatable, Sendable {
        public let text: String
        /// **굵게 보여줄 것인가** — 여기가 화면에서 "여기가 위험한 부분" 을 알려 준다.
        public let strong: Bool
        public init(_ text: String, strong: Bool = false) {
            self.text = text
            self.strong = strong
        }
    }

    /// **제목** — 무엇을 지우는지. 대상 이름은 넣지 않는다(본문이 그 역할).
    public let title: String
    /// **본문 조각들** — 순서대로 이어 붙여 읽는다.
    public let parts: [Part]
    /// **파괴 동작인가** — `true` 면 되돌릴 수 없다.
    ///
    /// 화면은 이 값으로 배너·버튼을 **빨강**으로 칠한다. 되돌릴 수 있는 동작과
    /// 구분되어야 사용자가 실수로 확정하지 않는다.
    public let isDestructive: Bool

    /// **표현을 모두 걷어낸 평문** — 테스트와 대체 표시(plain)용.
    public var body: String { parts.map(\.text).joined() }

    public init(title: String, parts: [Part], isDestructive: Bool) {
        self.title = title
        self.parts = parts
        self.isDestructive = isDestructive
    }

    /// **표현 가능한 문자열** — 파일명에는 손대지 않고, 지정한 조각만 굵게 만든다.
    ///
    /// `AttributedString` 를 이어 붙이는 방식이라 **원본 텍스트가 그대로 보존**된다.
    /// (마크다운처럼 `@` `*` `_` 를 해석하지 않는다)
    public var attributedBody: AttributedString {
        var out = AttributedString()
        for p in parts {
            var piece = AttributedString(p.text)
            if p.strong { piece.inlinePresentationIntent = .stronglyEmphasized }
            out += piece
        }
        return out
    }

    /// 이름이 비었을 때 화면을 비워 보이지 않게 하는 값.
    private static func safeName(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "(이름 없음)" : t
    }

    // MARK: - 잡

    /// **잡 삭제 — 완전 삭제가 된다.**
    ///
    /// 서버가 `partialFile()` 과 `doneFile()` 을 **둘 다** 지운다.
    /// 즉 **다 받은 파일까지 사라진다.** 진행 중이든 완료든 예외가 없다.
    ///
    /// → "되돌릴 수 없습니다" 를 **반드시** 말한다.
    public static func job(_ j: RelayClient.Job) -> ConfirmSpec {
        ConfirmSpec(
            title: "다운로드 삭제",
            parts: [
                .init("「\(safeName(j.name))」 — "),
                .init("지금까지 받은 파일까지 삭제", strong: true),
                .init("됩니다. 되돌릴 수 없습니다."),
            ],
            isDestructive: true
        )
    }

    // MARK: - 토렌트

    /// **토렌트 삭제 — 완료 여부에 따라 확정한 의미가 다르다.**
    ///
    /// - **완료(DONE/SEEDING)**: 서버는 `if (isComplete)` 분기로 **목록에서만** 지운다.
    ///   파일은 이미 보관함으로 옮겨졌다. → "파일이 남는다" 고 알려야 한다.
    /// - **미완료**: `saveDir/<이름>` 을 `deleteRecursively()` 한다.
    ///   **지금까지 받은 조각도 사라진다.** → 반드시 경고해야 한다.
    ///
    /// 사용자가 "이미 다 받았는데 지워도 되나" 하고 지웠는데 **파일까지 없어지면**
    /// 그건 컨펌이 있어도 막을 수 없다. 그래서 **상태를 나눠 말한다.**
    public static func torrent(_ t: Torrent) -> ConfirmSpec {
        let n = safeName(t.name)
        if t.isDone || t.isSeedingFlag {
            return ConfirmSpec(
                title: "토렌트 삭제",
                parts: [
                    .init("「\(n)」 — "),
                    .init("목록에서만", strong: true),
                    .init(" 사라집니다. 이미 받은 파일은 그대로 남습니다."),
                ],
                isDestructive: false
            )
        }
        return ConfirmSpec(
            title: "토렌트 삭제",
            parts: [
                .init("「\(n)」 — "),
                .init("지금까지 받은 조각도 삭제", strong: true),
                .init("됩니다. 되돌릴 수 없습니다."),
            ],
            isDestructive: true
        )
    }

    // MARK: - 보관함

    /// **보관함 삭제 — 휴지통으로 옮긴다.**
    ///
    /// 서버는 휴지통 디렉토리로 이동만 하고, `trash/restore` 로 되돌릴 수 있다.
    /// → **파괴 동작이 아니다.** 그렇다고 아무 말 없이 지우면 안 된다.
    public static func storage(_ e: StorageEntry) -> ConfirmSpec {
        ConfirmSpec(
            title: "휴지통으로 이동",
            parts: [
                .init("「\(safeName(e.name))」 — 휴지통으로 옮깁니다. "),
                .init("되돌릴 수 있습니다.", strong: true),
            ],
            isDestructive: false
        )
    }

    /// **휴지통 영구 삭제 — 유일하게 되돌릴 수 없다.**
    public static func purge(_ name: String) -> ConfirmSpec {
        ConfirmSpec(
            title: "영구 삭제",
            parts: [
                .init("「\(safeName(name))」 — "),
                .init("되돌릴 수 없게", strong: true),
                .init(" 지웁니다. 휴지통에도 남지 않습니다."),
            ],
            isDestructive: true
        )
    }

    /// **휴지통 → 복원 — 되돌릴 수 있는 동작이지만, 위치가 보장되지 않는다.**
    ///
    /// 서버는 휴지통에 **원래 위치를 저장하지 않는다**(StorageRoutes 252~262행).
    /// 복원하면 `StorageGuard.dlRoot`(**보관함 루트**)로 돌아가고, 거기가 차면
    /// `이름-2`, `이름-3` 이 붙는다(충돌 회피).
    ///
    /// → 사용자가 "원래 자리로 돌아갔다"고 믿으면 **거짓말**이 된다.
    /// 그래서 **"보관함 맨 위로 돌아온다"** 고 미리 말한다.
    public static func restore(_ name: String) -> ConfirmSpec {
        ConfirmSpec(
            title: "휴지통에서 복원",
            parts: [
                .init("「\(safeName(name))」 — 되살립니다. "),
                .init("보관함 맨 위로", strong: true),
                .init(" 돌아오므로 위치가 바뀔 수 있습니다."),
            ],
            isDestructive: false
        )
    }

    // MARK: - 보관함 조작 (이동 · 이름 변경)

    /// **이동 — 원래 위치로 돌아오는 경로는 없다.**
    ///
    /// 서버는 `renameTo` 로 옮기므로 되돌릴 방법이 없다. 휴지통과 달리
    /// 복원 기능도 없다 → 그래도 **파괴 표시는 하지 않는다.** 되돌리려면
    /// "다시 옮기면" 그만이다. 복구 불가와 되돌릴 수 없음은 **다른 말**이다.
    public static func move(_ e: StorageEntry) -> ConfirmSpec {
        ConfirmSpec(
            title: "이동",
            parts: [
                .init("「\(safeName(e.name))」 — 옮깁니다. "),
                .init("원래 위치에 두려면 다시 옮겨야 합니다.", strong: true),
            ],
            isDestructive: false
        )
    }

    /// **이름 변경 — 파일은 남고 이름만 바뀐다.** 되돌릴 수 있다(또 바꿔 주면 된다).
    public static func rename(_ e: StorageEntry) -> ConfirmSpec {
        ConfirmSpec(
            title: "이름 변경",
            parts: [
                .init("「\(safeName(e.name))」 — 이름이 바뀝니다. 파일 내용은 "),
                .init("그대로입니다.", strong: true),
            ],
            isDestructive: false
        )
    }
}
