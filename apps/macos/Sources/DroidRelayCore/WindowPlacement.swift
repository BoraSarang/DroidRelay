import Foundation
// `CGRect`·`CGPoint`·`CGSize` 의 속성(`midX`·`intersection`·`minX`)은 CoreGraphics 에 있다.
// Foundation 만 쓰면 이 타입들이 AppKit 의 `NSRect` 로 해석돼 **속성이 안 잡힌다.**
import CoreGraphics

/// **창을 어디에 둘지** — 저장된 위치를 되살리되 **사용자가 다시 찾을 수 있을 때만**.
///
/// ## 왜 이게 순수 함수인가
///
/// 화면 좌표 계산은 `NSScreen` 이 필요해 보이지만 **사실 그렇지 않다.**
/// 필요한 건 셋뿐이다 — **창 크기, 화면별 가시 영역 목록, 저장된 좌표.**
///
/// 이 셋을 인자로만 받으니 **디스플레이를 붙였다 떼는 모든 경우를 재현한다.**
/// 실제로 이 함수는 "설정을 눌러도 아무 일도 없다" 고 보고되는 사고를
/// 테스트 하나 없이 막기 위해 만들어졌다.
///
/// ## 규칙은 하나뿐이다
///
/// ```
/// 저장값 없음            →  주 화면 가운데
/// 어느 화면에서든 잡힘   →  그 자리에 그대로
/// 아무 화면에서도 안 잡힘 →  주 화면 가운데
/// ```
///
/// **중간에 클램프가 없다.** 처음엔 "화면 밖이면 안으로 끌어오면 되겠지" 하고
/// 넣었더니 **"창이 안 뜬다" 는 분기가 죽었다.** 클램프가 항상 창을 화면 안에
/// 넣으므로 그 뒤의 "보이는가" 검사는 **절대 실패하지 않기 때문**이다.
///
/// → **당기지 않는다. 아니면 가운데로 간다.** 판단이 하나뿐이라 검사가 값이 있다.
public enum WindowPlacement {

    /// **창 위쪽 띠 높이** — macOS 표준 제목바. 이 부분이 보여야 **사용자가 창을 끌 수 있다.**
    ///
    /// 왜 전체 창이 아니라 **띠만** 판정하는가: 창이 화면 아래로 2px만 걸친 경우를
    /// 생각해 보면, 겹친 넓이는 460×2 로 **충분히 넓어 보이지만** 보이는 건
    /// **창의 맨 아래 2px 다.** 제목바는 화면 반대편에 있어 잡을 수 없다.
    /// 사람 눈에는 "안 뜬 것"이고, 사용자는 **끌 방법이 없는 창** 앞에 놓인다.
    public static let titleBarHeight: CGFloat = 28

    /// **제목바가 잡히려면 폭이 최소한 이만큼은 보여야 한다** — 꾹 눌러야 하는 넓이.
    public static let minGrabWidth: CGFloat = 40

    /// **주 화면 가운데 좌표** — `visibleFrame`(메뉴바·독을 뺀 영역)의 중심.
    ///
    /// `frame` 의 중심이 아니라 **`visibleFrame` 의 중심**을 쓴다. 메뉴바가 있는
    /// 쪽으로 창이 20px쯤 치우치면 **눈으로 티가 난다.**
    public static func centered(in primary: CGRect, size: CGSize) -> CGPoint {
        CGPoint(x: primary.midX - size.width / 2, y: primary.midY - size.height / 2)
    }

    /// **저장된 위치를 되살리되, 사용자가 찾을 수 없으면 가운데로.**
    ///
    /// - Parameters:
    ///   - saved: 마지막으로 닫았을 때의 좌표. 없으면 `nil`.
    ///   - size: 창 크기.
    ///   - screens: **전체 화면**의 가시 영역. `visible[0]` 이 주 화면이어야 한다.
    /// - Returns: 창을 놓을 좌표.
    ///
    /// ## 왜 화면 "목록"인가 — 하나만 보면 틀린다
    ///
    /// 주 화면 하나만 보고 있으면 **2인치 모니터가 왼쪽에 붙어 있는 사용자**의 창을
    /// 못 찾았다고 판단해 **주 화면 가운데로 튕겨 보낸다.** 그 사용자는 자기
    /// 모니터를 그대로 두고 있는데 **창만 갑자기 반대편으로 이동한다.**
    ///
    /// → **전체 화면 중 어디서든 잡히면 그 자리에 둔다.**
    ///
    /// ## 왜 `visibleFrame` 인가
    ///
    /// `frame` 은 메뉴바와 독까지 포함한 전체다. 그걸 쓰면 창이 **독 뒤로 숨어서**
    /// 보이는 것 같아도 안 보일 수 있다. `visibleFrame` 이 **실제로 보이는 영역**이다.
    public static func resolve(saved: CGPoint?, size: CGSize, visible screens: [CGRect]) -> CGPoint {
        // **화면이 없으면 판단할 기준이 없다.** 그래도 창은 떠야 하므로 (0,0) 을 준다.
        guard let primary = screens.first else { return saved ?? .zero }
        guard let saved else { return centered(in: primary, size: size) }
        return canGrab(saved, size: size, in: screens) ? saved : centered(in: primary, size: size)
    }

    /// **이 자리에 창을 놓으면 사용자가 끌 수 있는가.**
    ///
    /// 제목바 띠가 **어떤 화면에서든** `minGrabWidth` 만큼 보이면 참.
    /// 전체 창의 겹침 넓이로 재지 않는다 — 맨 아래 2px 는 넓어 보여도 잡을 수 없다.
    public static func canGrab(_ origin: CGPoint, size: CGSize, in screens: [CGRect]) -> Bool {
        let band = titleBarRect(origin: origin, size: size)
        return screens.contains { frame in
            let hit = band.intersection(frame)
            guard !hit.isNull, !hit.isEmpty else { return false }
            return hit.width >= minGrabWidth && hit.height >= 8
        }
    }

    /// **제목바가 있는 띠** — 창 좌표계에서 **맨 위** 28pt.
    ///
    /// AppKit 좌표는 **아래에서 위로**다. `y + height` 가 위쪽이므로 더해야 한다.
    /// 이걸 빼먹으면 **맨 아래 띠**를 검사하게 되고, 테스트는 통과하는데
    /// 실제 화면에서는 "잡을 수 있는데 안 잡힌다" 고 판단한다.
    public static func titleBarRect(origin: CGPoint, size: CGSize) -> CGRect {
        CGRect(x: origin.x,
               y: origin.y + size.height - titleBarHeight,
               width: size.width,
               height: titleBarHeight)
    }

    /// **창을 만들 때 넘길 `contentRect` 의 원점** — **창 전체가** 정중앙이 되도록.
    ///
    /// ## 왜 이게 별도 함수인가 — `(0, -28)` 버그의 재발 방지
    ///
    /// 처음엔 `NSWindow(contentRect: NSRect(x: 0, y: 0, …))` 로 만들었다.
    /// `x: 0, y: 0` 이 **화면 왼쪽 아래**라는 뜻이었다. 사용자가 실제로 본 그대로다.
    ///
    /// 게다가 초기 프레임이 `(0, -28)` 이었다. **`contentRect` 는 내용물 영역**이라
    /// 위로 제목바 28pt 가 붙고, 그만큼 **화면 밖으로 삐져나간 것**이다.
    /// "왼쪽 아래" 보다 나쁜 "제목바가 화면 밖에 있다"였다.
    ///
    /// ## 왜 `titleBar` 를 **빼** 나야 하는가 — 이게 미묘한 부분이다
    ///
    /// `contentRect` 는 **내용물** 영역이고, 제목바는 그 **위쪽**에 붙는다.
    /// 즉 최종 프레임은 `[y - 28, y + 520]` 이고 그 중심은 `y + 246` 다.
    ///
    /// ```
    ///   frame.midY = (y - titleBar + y + contentH) / 2 = y + (contentH - titleBar) / 2
    ///   이것이 visible.midY 가 되려면
    ///   y = visible.midY - (contentH - titleBar) / 2
    /// ```
    ///
    /// **덧셈이 아니라 뺄셈이다.** 처음에 `+ titleBar` 로 적어 테스트가 28pt 어긋남을
    /// 잡았다. 부호를 반대로 적으면 **창이 아래로 처져 보인다.**
    ///
    /// 화면이 1130pt 라 오차는 눈에 거의 안 띄지만, **"가운데" 라는 말은
    /// 어긋난 자리 가운데가 아니다.** → **창 전체가 정확히 가운데가 되도록 계산한다.**
    public static func initialContentOrigin(in visible: CGRect, contentSize: CGSize,
                                            titleBar: CGFloat = titleBarHeight) -> CGPoint {
        CGPoint(x: visible.midX - contentSize.width / 2,
                y: visible.midY - (contentSize.height - titleBar) / 2)
    }

    /// **내용물을 가운데에 넣었을 때, 제목바까지 붙인 최종 창 프레임.**
    ///
    /// 제목바가 창 **위쪽**에 붙으므로 `y` 는 `titleBar` 만큼 내려간다.
    /// 이 함수가 있어야 **"창 전체가 화면 안인가"** 를 순수하게 검증할 수 있다.
    public static func frameAfterTitleBar(contentOrigin: CGPoint,
                                          contentSize: CGSize,
                                          titleBar: CGFloat = titleBarHeight) -> CGRect {
        CGRect(x: contentOrigin.x,
               y: contentOrigin.y - titleBar,
               width: contentSize.width,
               height: contentSize.height + titleBar)
    }

    // MARK: - 저장소

    /// `UserDefaults` 키 — 앱 이름으로 접두어를 붙여 **다른 앱 값과 섞이지 않게** 한다.
    public static let defaultsKey = "com.borasarang.DroidRelay.settingsWindowOrigin"

    private static var defaults: UserDefaults { .standard }

    /// 마지막으로 닫았을 때의 좌표. **좌표 쌍이 다 있어야** 인정한다.
    public static func loadOrigin() -> CGPoint? {
        let d = defaults
        // **한쪽만 있으면 좌표가 아니라 쓰레기다.** `UserDefaults` 는 손으로
        // 편집할 수 있다. `BackupPoll` 이 `0` 으로 이벤트 루프를 태운 사고와 같은 종류다 —
        // **저장된 값이 화면 밖에서 들어오는 순간을 막아야 한다.**
        guard d.object(forKey: "\(defaultsKey).x") != nil,
              d.object(forKey: "\(defaultsKey).y") != nil else { return nil }
        return CGPoint(x: d.double(forKey: "\(defaultsKey).x"),
                       y: d.double(forKey: "\(defaultsKey).y"))
    }

    public static func saveOrigin(_ origin: CGPoint) {
        defaults.set(Double(origin.x), forKey: "\(defaultsKey).x")
        defaults.set(Double(origin.y), forKey: "\(defaultsKey).y")
    }

    /// **저장 위치를 잊는다** — 다음 열 때 가운데로.
    public static func clearOrigin() {
        defaults.removeObject(forKey: "\(defaultsKey).x")
        defaults.removeObject(forKey: "\(defaultsKey).y")
    }
}
