import Foundation

/// **다운로드 추가 입력 파싱** — 웹 대시보드와 **완전히 같은 규칙.**
///
/// ## 왜 여러 줄을 받는가
///
/// 웹은 한 칸에 여러 URL 을 붙여넣게 한다(1788행):
/// ```javascript
/// raw.split(/[\s,]+/).filter(function(u){return /^https?:\/\//i.test(u);})
/// ```
/// 클라이언트가 "URL 하나"만 받으면, 웹에서 되는 "파일 5개 한 번에" 가
/// macOS 에서는 5번 반복해야 한다. **같은 서버에 붙은 두 화면이 다른 일을 하게 된다.**
///
/// ## 규칙을 한 곳에 모은 이유
///
/// 분리자·필터를 여기저기서 다르게 쓰면 "웹에선 되는데 앱에선 안 돼" 가 생긴다.
/// 웹이 무엇을 하는지를 **테스트로 고정**해서 서버가 바뀌어도 같이 따라간다.
public enum AddInput {

    /// 웹 1788행 `raw.split(/[\s,]+/)`
    ///
    /// **구분자는 공백류와 쉼표뿐이다.** `、`(일본어 쉼표)나 `，`(전체 쉼표)를
    /// 넣으면 안 된다 — 웹이 그것으로 나누지 않으므로 **서로 다른 규칙**이 된다.
    ///
    /// - Note: 웹의 `\s` 는 유니코드 공백도 포함한다. Swift 의
    ///   `whitespacesAndNewlines` 가 같은 범위를 덮는다.
    public static var separators: CharacterSet {
        var s = CharacterSet.whitespacesAndNewlines
        s.insert(charactersIn: ",")
        return s
    }

    /// **URL 로 쓸 수 있는가** — 웹 `/^https?:\/\//i` (대소문자 무시).
    ///
    /// `magnet:`, `ftp:`, `javascript:` 같은 것은 통과시키지 않는다.
    /// 서버도 `url.startsWith("http://") || startsWith("https://")` 만 받는다
    /// (JobRoutes 47행) → 여기서 걸러야 422 를 미리 피한다.
    public static func isHttpURL(_ s: String) -> Bool {
        let t = s.lowercased()
        return t.hasPrefix("http://") || t.hasPrefix("https://")
    }

    /// 붙여넣은 문자열에서 **추가할 URL 목록**을 뽑는다.
    ///
    /// - Returns: 유효한 http(s) URL 만, 입력 순서 그대로. 없으면 빈 배열.
    public static func urls(_ raw: String) -> [String] {
        raw.components(separatedBy: separators)
            .filter { !$0.isEmpty }
            .filter(isHttpURL)
    }

    /// **magnet 입력 검증** — 웹 1805~1806행.
    ///
    /// 웹은 `if(!u) return;` 만 하고 형식은 서버에 맡긴다. 클라이언트도 **같이** 한다:
    /// `magnet:` 로 시작하지 않으면 **누르는 순간** 이유를 알려준다.
    /// 서버까지 갔다 422 를 받는 것보다 빠르고 명확하다.
    public static func isMagnet(_ s: String) -> Bool {
        s.lowercased().hasPrefix("magnet:")
    }
}
