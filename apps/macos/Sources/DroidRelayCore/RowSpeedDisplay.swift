import Foundation

/// **행에 어떤 속도를 보여줄지** — 다운로드·업.
///
/// ## 왜 이게 순수 함수인가
///
/// 화면에 `if/else` 로 직접 쓰면 **조건 분기가 UI 안에 파묻혀** 어떤 조합이
/// 숨겨지는지 알 수 없다. Core 로 빼면 **9가지 조합을 전부 검증**할 수 있다.
///
/// ## 이게 없었을 때의 실제 버그 (2026-09-29)
///
/// 토렌트 행이 이렇게 렌더링됐다:
/// ```
/// IPZZ-926              ▲4.6 KB/s
/// 다운로드 중 4.2 MB / 3.29 GB      ▲5 ▼104
/// ```
/// **다운로드 속도가 없었다.** 서버는 `downloadSpeed: 102427` 을 주고 있었고
/// (`/api/torrents` 실측), 파싱도 정상이었다.
///
/// ```swift
/// if uploadBps > 0 { Text("▲…") } else { Text("다운로드…") }   // ← 업이 우선
/// ```
///
/// **업 속도가 켜지는 순간 다운로드 속도가 화면에서 사라졌다.** 잡 행도 같은 구조였다.
///
/// > **두 값은 독립이다.** 하나가 0 이어도 다른 하나는 살아 있어야 한다.
/// > 조건으로 고르면 **항상 존재하는 정보를 조건에 걸어 없앤다.**
///
/// ## 왜 `if/else` 가 특히 위험한가 — "있으면 하나만" 은 보통 옳다
///
/// 이 앱의 다른 곳은 `if/else` 가 옳다. **그 판단이 굳어져서 여기로 옮겨온 것**이
/// 문제다. 이 규칙은 **상태가 아니라 값의 유무**를 다루고, 값은 **둘 다 동시에**
/// 존재할 수 있다. 상태와 값은 다르다.
public enum RowSpeedDisplay {

    /// 한 행에 붙는 속도 두 칸.
    public struct Pair: Equatable, Sendable {
        /// 다운로드 속도 표기. 0 이면 `nil` — **"0 B/s" 를 그리지 않는다.**
        public let down: String?
        /// 업 속도 표기. 0 이면 `nil`.
        public let up: String?
    }

    /// **토렌트 행** — 서버 키가 `downloadSpeed`/`uploadSpeed`.
    public static func torrent(down: Int, up: Int) -> Pair {
        Pair(down: label(down, arrow: "▼"),
             up: label(up, arrow: "▲"))
    }

    /// **잡(다운로드) 행** — 값의 이름만 다르고 규칙은 같다.
    public static func job(down: Int, up: Int) -> Pair {
        torrent(down: down, up: up)
    }

    /// **0 은 빈 칸으로 본다** — "0 B/s" 를 그리는 게 아니라 **아무것도 안 그린다.**
    ///
    /// 0 을 그리는 게 틀린 게 아니다. **"지금 아무것도 안 흐른다" 를 알고 싶은
    /// 사용자에게도**, 진행 중인 다운로드 옆에 0 을 그리는 건 **노이즈**다.
    private static func label(_ bps: Int, arrow: String) -> String? {
        guard bps > 0 else { return nil }
        return "\(arrow)\(SpeedFormat.compact(bps))"
    }
}
