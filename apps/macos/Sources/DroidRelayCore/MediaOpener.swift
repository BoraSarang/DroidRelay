import Foundation

/// **보관함 동영상을 어느 앱으로 열지** — 브라우저가 아니라 **IINA 를 먼저 시도한다**.
///
/// ## 왜 IINA 가 첫째인가 — 실측으로 정한 것
///
/// 처음엔 "http URL 을 여는 앱은 브라우저뿐" 이라 단정했다.
/// 근거는 `NSWorkspace.urlForApplication(toOpen:)` 였다.
/// ```
/// http://…/stream/x.mp4  →  Safari.app
/// ```
/// 이건 **Launch Services 등록 목록**이고, **앱의 실제 능력을 뜻하지 않는다.**
///
/// IINA 를 http 로 직접 열면 **실제로 재생된다**(서버 로그 `206 부분` + 창 제목이 파일명).
/// IINA 는 mpv 기반이라 네트워크 스트리밍을 지원하고(`httpPrefixTextField`,
/// `URLSession` 참조 확인), **버퍼를 앞서 쌓으므로 느린 회선에서 브라우저보다 훨씬 낫다.**
/// 이 기기는 총 대역폭이 ~600KB/s 라 이 차이가 실감된다.
///
/// → **설치돼 있으면 IINA, 없으면 브라우저.** 그리고 이 기기에는 IINA 가 있다.
public enum MediaOpener {

    /// IINA 의 번들 식별자 — 설치 확인과 지정 열기에 쓴다.
    public static let iinaBundleID = "com.colliderli.iina"

    /// **우선 시도할 재생 앱들. 앞쪽이 우선이다.**
    ///
    /// `IINA` 만 넣었다. QuickTime 는 http URL 을 못 열고, VLC 는 이 기기에 없다.
    /// 목록이 비면 **브라우저로 물러난다** — 아무것도 안 되는 일은 없다.
    public static let preferredBundleIDs: [String] = [iinaBundleID]

    /// **설치된 재생 앱의 경로를 첫째로 돌려준다.** 없으면 `nil` (=브라우저 폴백).
    ///
    /// ## 왜 `lookup` 을 받는가
    ///
    /// 이 함수의 배려는 **순서**다 — "IINA 가 있으면 IINA" 를 시스템에 물어보지 않고
    /// 재현 가능하게 고정하려고. `lookup` 을 주입받으므로 테스트는
    /// 실제 설치 여부와 무관하게 **같은 판단**을 검증한다.
    ///
    /// - Parameter bundleIDs: 우선순위 목록. 앞에서부터 본다.
    /// - Parameter lookup: 번들 식별자 → 설치된 앱 URL. 없으면 `nil`.
    public static func resolve(bundleIDs: [String] = preferredBundleIDs,
                               lookup: (String) -> URL?) -> URL? {
        for id in bundleIDs {
            if let app = lookup(id) { return app }
        }
        return nil
    }
}
