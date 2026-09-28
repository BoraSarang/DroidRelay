import Foundation
import ServiceManagement

/// 로그인 시 자동 실행 (M-14).
///
/// **`SMAppService` 를 쓰는 이유** — 구식 `SMLoginItemSetEnabled` 는 macOS 13 에서
/// deprecated 되었고, 동작이 "앱이 스스로 UserDefaults 에 플래그를 쓰고 시작할 때
/// 그걸 신뢰한다" 방식이라 **플래그와 실제 등록 상태가 어긋나면 조용히 실패**한다.
/// `SMAppService` 는 Launch Services 에 직접 등록/해제하고 **실제 상태를 조회**할 수 있다.
///
/// **중요한 제약** — 이 앱은 **설치본**(`/Applications/DroidRelay.app`)이어야 등록이 된다.
/// `swift run` 한 임시 바이너리는 경로가 안정적이지 않아 `register()` 가 실패한다.
/// 그래서 실패를 조용히 삼키지 않고 상태 문자열로 돌려준다(설정 화면에 그대로 보인다).
public enum LoginItem {

    /// 현재 등록 상태 — Launch Services 기준 진실
    public static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    /// 사용자가 시스템 설정에서 껄 수도 있으므로, 성공 여부만으로 상태를 갱신하지 않는다.
    public enum Result: Equatable {
        /// 성공. **결과 상태를 들고간다** — `ok` 로만 두면 해제 성공 뒤에도
        /// "켜짐" 이라는 메시지가 나가 UI 가 거짓말을 한다(실제로 그렇게 나왔다).
        case applied(enabled: Bool)
        /// 아직 .app 번들이 아니다 (`swift run` 등)
        case notBundled
        /// 시스템이 거절했다 — 권한·정책·경로 문제
        case failed(String)

        /// 사용자에게 보여줄 한 줄. **절대 빈 문자열이 되면 안 된다** —
        /// 스위치가 켜지지 않았는데 아무 말도 없으면 사용자는 그냥 버그로 여긴다.
        public var message: String {
            switch self {
            case .applied(true): return "켜짐"
            case .applied(false): return "꺼짐"
            case .notBundled: return "설치본이 아님"
            case .failed(let m):
                let t = m.trimmingCharacters(in: .whitespacesAndNewlines)
                return t.isEmpty ? "알 수 없는 오류" : t
            }
        }
    }

    /// 등록/해제를 수행한다. 실패해도 앱은 죽지 않는다 — 설정 화면에 사유를 보여줄 뿐.
    @discardableResult
    public static func set(_ on: Bool) -> Result {
        let svc = SMAppService.mainApp
        do {
            if on {
                // 이미 켜져 있으면 다시 등록하지 않는다 — OS 가 오류를 뱉는다
                guard svc.status != .enabled else { return .applied(enabled: true) }
                try svc.register()
            } else {
                guard svc.status == .enabled else { return .applied(enabled: false) }
                try svc.unregister()
            }
            return isEnabled == on ? .applied(enabled: on) : .failed("시스템이 등록하지 않았습니다")
        } catch {
            return .failed(Self.describe(error))
        }
    }

    /// `swift run` 처럼 번들이 아닌 실행이면 등록이 애초에 불가능하다.
    /// 사용자에게 "설치 후 다시 켜세요" 대신 **왜 안 되는지** 를 알려야 한다.
    public static var isBundled: Bool {
        Bundle.main.bundleURL.pathExtension == "app"
    }

    /// 오류는 `kSMError*` **C 상수** 코드 값으로 온다.
    /// Swift 에는 `SMAppService.Error` 같은 타입이 없다 — 컴파일러가 알려줬다.
    /// 값은 `ServiceManagement/SMErrors.h` 의 enum 순서와 같다.
    ///
    /// **도메인을 걸러내지 않는 이유** — `SMAppServiceErrorDomain` 상수는 macOS 15+ 전용이라
    /// 이 타깃(macOS 14)에서는 참조조차 컴파일되지 않는다. `set(_:)` 이 던질 수 있는
    /// 오류는 전부 이 도메인이고, 코드 값만으로 충분하다.
    private static func describe(_ error: Error) -> String {
        switch (error as NSError).code {
        case 2:  return "내부 오류"
        case 3:  return "서명이 없어 등록할 수 없습니다 — 정식 서명이 필요합니다"
        case 4:  return "권한이 없습니다"
        case 5:  return "앱 경로가 유효하지 않습니다 — /Applications 에 설치하세요"
        case 6:  return "등록된 항목을 찾을 수 없습니다"
        case 7:  return "시스템이 이 서비스를 사용할 수 없습니다"
        case 8, 9, 10: return "번들의 실행 파일이 올바르지 않습니다"
        case 11: return "사용자가 등록을 거부했습니다"
        case 12: return "이미 등록되어 있습니다"
        default: return error.localizedDescription
        }
    }
}
