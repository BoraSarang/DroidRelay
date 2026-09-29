import AppKit
import DroidRelayCore
import SwiftUI

/// **설정을 별도 창으로 띄운다** — 팝오버 안에서 자라나던 것을 뺀다.
///
/// ## 왜 창으로 뺐나
///
/// 기존에는 `.sheet(isPresented:)` 를 `PopoverContainer`(= **팝오버 내용물**) 에 붙였다.
/// `.sheet` 는 붙은 뷰의 **윈도우**에서 뜬다. 팝오버의 윈도우는 352×520 으로 고정돼 있고
/// `isFloatingPanel` 이라 **자기가 자라난다.** 그래서 사용자가 본 건 "창"이 아니라
/// **팝오버가 설정 모양으로 바뀐 것**이었다 — 사용자가 "창으로 뜨지 않는다"고 rightly 지적한 그 동작.
///
/// `.sheet` 를 그대로 두면 이 모양에서 빠져나오지 못한다. **팝오버를 완전히 치우고
/// `NSWindow` 를 세운다** — 시트와 눈치게임이 끝난다.
///
/// ## 이 앱이 `.accessory` 라서 남는 함정 두 개
///
/// 1. **키 윈도우가 없다.** 그래도 `NSApp.activate(ignoringOtherApps: true)` 를
///    부르면 **Dock 아이콘 없이** 앱이 활성 상태가 되고 이 창이 key 가 될 수 있다.
///    (Dock 아이콘을 원하면 `.regular` 로 바꾸면 되지만, 그건 메뉴바 앱의 성격을 바꾸는
///    것이라 하지 않는다.)
/// 2. **창을 닫았다고 앱이 종료되면 안 된다.** `.accessory` 앱은 "마지막 창을 닫으면
///    종료" 경로를 타지 않지만, 안전하게 `applicationShouldTerminateAfterLastWindowClosed`
///    는 건드리지 않는다. 대신 **컨트롤러를 강한 참조로 들고 있는다** — 풀려서 창이
///    통째로 사라지는 것을 막는다.
@MainActor
final class SettingsWindowController: NSWindowController, NSWindowDelegate {

    /// **창 크기** — 생성 위치와 무관하게 여기만 진짜다.
    static let contentSize = CGSize(width: 460, height: 520)

    /// **셋업을 한 번만** — `NSWindowController.init(window:)` 로 창을 직접 준다.
    ///
    /// ## 창을 만들어서 **즉시 가운데에 둔다** — 이게 왼쪽 하단 버그의 답
    ///
    /// 원래 `contentRect: NSRect(x: 0, y: 0, …)` 로 만들어 **위치를 (0,0)에 고정**했다.
    /// 그 결과 **첫 실행에 창이 화면 왼쪽 아래에 떴다.** 사용자가 본 그대로였다.
    ///
    /// 실제 초기 프레임은 `(0, -28)` 이었다. `contentRect` 는 **내용물** 영역이라
    /// 위로 제목바 28pt 가 붙어 **제목바가 화면 밖으로 삐져나갔다.**
    /// "왼쪽 아래"보다 나쁜 "제목바가 화면 밖에 있다"였다.
    ///
    /// `applyPlacement()` 로 나중에 옮기면 되므로 **굳이 (0,0) 에 만들 이유가 없다.**
    /// 창이 **화면에 뜨는 첫 순간부터** 제자리에 있게 하는 게 정직하다.
    init(model: AppModel) {
        // **내용물 기준으로 가운데를 잡는다** — `frame` 이 아니라 `contentRect`.
        // 제목바가 붙은 최종 프레임이 정중앙에 오려면 **내용물을 먼저** 세팅해야 한다.
        let vf = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let s = Self.contentSize
        let start = WindowPlacement.initialContentOrigin(in: vf, contentSize: s)
        let w = NSWindow(
            contentRect: NSRect(origin: start, size: s),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        w.title = "DroidRelay 설정"
        w.titlebarAppearsTransparent = false
        w.isReleasedWhenClosed = false          // 닫아도 창이 파괴되지 않는다
        super.init(window: w)
        w.contentView = NSHostingView(rootView: SettingsView(model: model))
        w.delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:)는 쓰지 않는다") }

    /// **창을 앞으로 올린다** — 이미 떠 있으면 그냥 앞에 세운다.
    ///
    /// `NSApp.activate` 를 **먼저** 부르는 게 순서다. 반대면 창이 key 가 되는 시점에
    /// 앱이 아직 비활성이라 **키보드가 안 들어간다** — 입력창이 있어도 못 받는 창이 된다.
    func present() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    /// **창을 놓을 자리를 정한다 — 가운데, 혹은 사용자가 마지막으로 둔 자리.**
    ///
    /// ## 왜 처음엔 오른쪽이 아니라 가운데인가
    ///
    /// 원래는 메뉴바 오른쪽에 붙였다. 사용자가 "창이 아니라 팝오버처럼 보인다"고
    /// 받아들였고, **메뉴바와 무관한 앱의 설정 창**이라는 걸 화면 가운데가 말해 준다.
    /// 메뉴바 앱이라는 사실은 **아이콘**이 이미 말하고 있다.
    ///
    /// ## 왜 `NSScreen.main` 하나로 안 되나
    ///
    /// 사용자가 창을 2인치 모니터 쪽으로 옮겨뒀다면 **거기서 뜨는 게 맞다.**
    /// 화면 가운데를 무조건 강제하면 **배치한 사용자를 매번 무시하는 것**이 된다.
    ///
    /// 그래서 **전체 화면**을 넘기고, 그 어디에서도 안 보일 때만 가운데로 되돌린다
    /// (`WindowPlacement` 규칙). 모니터가 빠졌는데 그 자리에 띄우면
    /// **사용자는 아무것도 못 본다.**
    func applyPlacement() {
        guard let w = window else { return }
        // **주 화면이 첫째여야 한다** — `visible[0]` 이 "돌아갈 곳"으로 쓰인다.
        var frames = NSScreen.screens.map(\.visibleFrame)
        if let main = NSScreen.main?.visibleFrame { frames.removeAll { $0 == main }; frames.insert(main, at: 0) }
        guard !frames.isEmpty else { w.center(); return }
        let origin = WindowPlacement.resolve(saved: WindowPlacement.loadOrigin(),
                                             size: w.frame.size, visible: frames)
        w.setFrameOrigin(origin)
    }

    /// **저장된 위치를 버리고 가운데로 되돌린다** — 진단·문제 해결용.
    ///
    /// 사용자 배치까지 지우므로 **자동으로 부르지 않는다.**
    func resetPlacement() {
        WindowPlacement.clearOrigin()
        applyPlacement()
    }

    /// **창이 사라질 때 위치를 기억한다.**
    ///
    /// ## 왜 `NSWindowDelegate` 인가 — 알림보다 나은 이유
    ///
    /// `NSWindow.willCloseNotification` 에도 걸 수 있지만, **여러 창이 같은 알림을
    /// 듣기 때문에 "이 창이 닫혔다" 를 다시 확인해야 한다.** delegate 는
    /// **이 창한테만** 불린다. 필터 한 줄이 사라진다.
    ///
    /// 저장 실패가 문제가 되지 않는 이유: 이건 **편의다.** 잃어도 다음에 가운데로 뜬다.
    func windowWillClose(_ notification: Notification) {
        guard let origin = window?.frame.origin else { return }
        WindowPlacement.saveOrigin(origin)
    }
}

/// **설정 창 내용** — 주소 · 폴링 주기 · 속도 표시 · 자동 실행.
struct SettingsView: View {
    @Bindable var model: AppModel

    /// 주소는 **열 때마다 실제 저장값을 다시 읽는다** — 위에서 고친 다음
    /// 팝오버를 닫았다가 다시 열면 옛 값이 남아 있으면 사용자는 "저장이 안 된다"고 읽는다.
    @State private var address = ""
    /// 로그인 자동 실행 — **Launch Services 를 직접 조회**한다(UserDefaults 플래그가 아니라).
    @State private var loginOn = LoginItem.isEnabled
    @State private var loginMsg: String? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                connection
                Divider()
                polling
                Divider()
                speed
                Divider()
                startup
            }
            .padding(20)
        }
        .frame(width: 460, height: 520)
        .onAppear {
            address = model.storedAddress ?? ""
            loginOn = LoginItem.isEnabled
            loginMsg = nil
        }
    }

    // MARK: - 서버

    private var connection: some View {
        VStack(alignment: .leading, spacing: 10) {
            header("DroidRelay 연결")
            Text("자동으로 찾지 못하면 주소를 직접 입력하세요.")
                .font(.system(size: 11.5)).foregroundStyle(.secondary)
            HStack {
                TextField("10.0.0.5:3000", text: $address)
                    .textFieldStyle(.roundedBorder)
                Button("연결") { Task { await model.useManualAddress(address) } }
                    .disabled(address.isEmpty)
            }
            HStack {
                Text("자동 검색으로 다시 시도").font(.system(size: 12))
                Spacer()
                Button("다시 찾기") { Task { await model.connect() } }
            }
        }
    }

    // MARK: - 폴링 주기

    /// **SSE 안전망 주기** — 서버 주소 바로 아래에 둔다.
    ///
    /// 왜 이 자리인가: **"서버와 얼마나 자주 대화하는가" 는 주소와 한 덩어리**다.
    /// 반면 속도 표시·자동 실행은 자주 안 바꾸는 설정이라 아래 묶음에 둔다.
    /// 위험도 계층이 위에서 아래로 흐른다 — **연결 → 빈도 → 표시 → 시작.**
    private var polling: some View {
        VStack(alignment: .leading, spacing: 10) {
            header("폴링 주기")
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("안전망 폴링").font(.system(size: 12))
                    Text("SSE 가 끊겼을 때 상태를 되찾는 주기입니다. 평소 갱신은 SSE 가 하므로 늘려도 진행률은 실시간입니다.")
                        .font(.system(size: 10.5)).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Picker("", selection: pollBinding) {
                    ForEach(BackupPoll.choices, id: \.self) { s in
                        Text("\(s) 초").tag(s)
                    }
                }
                .labelsHidden()
                .frame(width: 96)
            }
        }
    }

    /// 설정값 → 모델. **양쪽 다 클램프된 값만 오간다.**
    private var pollBinding: Binding<Int> {
        Binding(
            get: { BackupPoll.clamp(model.backupPollSeconds) },
            set: { model.backupPollSeconds = $0 }
        )
    }

    // MARK: - 속도 표시

    private var speed: some View {
        VStack(alignment: .leading, spacing: 10) {
            header("메뉴바 속도")
            toggle("Droid 속도", "이 앱이 쓰는 트래픽", Binding(
                get: { model.speedSetting.showDroid },
                set: { model.speedSetting.showDroid = $0 }
            ))
            // **경고 문구라 색이 다르다** — `toggle` 은 라벨을 무조건 `.secondary` 로
            // 칠하므로, "지원 대기 중" 이 주황색으로 안 떠야 할 때 회색으로 죽는다.
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("기기 속도").font(.system(size: 12))
                    Text(model.deviceSpeedAvailable
                         ? "폰 전체 트래픽"
                         : "서버 지원 대기 중 — 켜도 값이 나오지 않습니다")
                        .font(.system(size: 10.5))
                        .foregroundStyle(model.deviceSpeedAvailable ? .secondary : Color.orange)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { model.speedSetting.showDevice },
                    set: { model.speedSetting.showDevice = $0 }
                ))
                .labelsHidden()
                .disabled(!model.deviceSpeedAvailable)
            }
        }
    }

    // MARK: - 시작

    private var startup: some View {
        VStack(alignment: .leading, spacing: 10) {
            header("시작")
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("로그인 시 자동 실행").font(.system(size: 12))
                    if let loginMsg {
                        Text(loginMsg).font(.system(size: 10.5)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        Text("Mac 에 로그인하면 DroidRelay 가 자동으로 뜹니다")
                            .font(.system(size: 10.5)).foregroundStyle(.tertiary)
                    }
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { loginOn },
                    set: { toggleLogin($0) }
                ))
                .labelsHidden()
            }
        }
    }

    // MARK: - 조각

    private func header(_ t: String) -> some View {
        Text(t).font(.system(size: 14, weight: .bold))
    }

    private func toggle(_ t: String, _ h: String, _ binding: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(t).font(.system(size: 12))
                Text(h).font(.system(size: 10.5)).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: binding).labelsHidden()
        }
    }

    /// 실패해도 앱은 죽지 않는다 — 사유를 설정 화면에 남기고 스위치를 원래대로 되돌린다.
    private func toggleLogin(_ want: Bool) {
        let r = LoginItem.set(want)
        loginOn = LoginItem.isEnabled   // 요청한 값이 아니라 **실제** 상태를 따른다
        if case .applied = r { loginMsg = nil } else { loginMsg = r.message }
    }
}
