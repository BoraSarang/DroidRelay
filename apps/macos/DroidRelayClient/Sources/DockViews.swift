import SwiftUI
import AppKit

// MARK: - 도크 탭/펼침 상태 컨트롤러

enum DockTab: String, CaseIterable, Identifiable {
    case downloads = "전송"
    case storage = "보관함"
    case debug = "디버그"
    case settings = "설정"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .downloads: return "arrow.down.circle"
        case .storage: return "internaldrive"
        case .debug: return "ant.circle"
        case .settings: return "gearshape.circle"
        }
    }
}

@MainActor
final class DockController: ObservableObject {
    @Published var tab: DockTab = .downloads
    @Published var expanded = false
    var onHeightChange: ((CGFloat) -> Void)?

    static let collapsedHeight: CGFloat = 32
    static let expandedHeight: CGFloat = 380

    func open(_ t: DockTab) {
        tab = t
        if !expanded { expand() }
        NSApp.activate(ignoringOtherApps: true)
    }

    func expand() {
        guard !expanded else { return }
        expanded = true
        onHeightChange?(Self.expandedHeight)
    }

    func toggle() {
        expanded.toggle()
        onHeightChange?(expanded ? Self.expandedHeight : Self.collapsedHeight)
    }
}

// MARK: - 루트

struct DockRootView: View {
    @ObservedObject var ctl: DockController
    @State private var summary = "대기 없음"
    private var settings: SettingsStore { AppState.shared.settings }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Picker("", selection: $ctl.tab) {
                    ForEach(DockTab.allCases) { t in
                        Label(t.rawValue, systemImage: t.icon).tag(t)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .controlSize(.small)
                .frame(minWidth: 220, maxWidth: 280)

                Spacer(minLength: 4)

                Text(summary)
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Button {
                    ctl.toggle()
                } label: {
                    Image(systemName: ctl.expanded ? "chevron.down" : "chevron.up")
                        .font(.system(size: 11, weight: .semibold))
                        .frame(width: 20, height: 16)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .help(ctl.expanded ? "도크 접기" : "도크 펼치기")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 5)

            Divider()

            if ctl.expanded {
                Group {
                    switch ctl.tab {
                    case .downloads: DownloadsListView()
                    case .storage: StorageBrowserView()
                    case .debug: DebugLogView()
                    case .settings: SettingsViewMac()
                    }
                }
                .frame(maxHeight: DockController.expandedHeight - DockController.collapsedHeight)
                .transition(.opacity)
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .animation(.easeOut(duration: 0.15), value: ctl.expanded)
        .preferredColorScheme(settings.colorScheme)
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
            let app = AppState.shared
            let active = app.transfers.actives.filter { !$0.finished && !$0.failed }
            let dlCount = active.filter { $0.direction == .download }.count
            let ulCount = active.filter { $0.direction == .upload }.count
            let speed = app.info?.speedTotalBps ?? 0
            if active.isEmpty {
                summary = app.isConnected ? "대기 없음" : "서버 연결 안 됨"
            } else {
                var parts: [String] = []
                if dlCount > 0 { parts.append("↓\(dlCount)건") }
                if ulCount > 0 { parts.append("↑\(ulCount)건") }
                if speed > 0 { parts.append(Fmt.speed(speed)) }
                summary = parts.joined(separator: " · ")
            }
        }
    }
}

// MARK: - 📥 다운로드 목록

struct DownloadsListView: View {
    private var transfers: TransferManager { AppState.shared.transfers }
    @State private var tick = false
    @State private var showAddSheet = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("전송 목록")
                    .font(.callout)
                    .fontWeight(.semibold)
                Spacer()
                Button {
                    showAddSheet = true
                } label: {
                    Label("추가", systemImage: "plus.circle")
                        .font(.caption)
                }
                .controlSize(.small)

                Button("완료 정리") { transfers.clearFinished() }
                    .controlSize(.small)
                    .disabled(transfers.actives.isEmpty)
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .sheet(isPresented: $showAddSheet) { AddDownloadSheet() }

            ScrollView {
                VStack(spacing: 6) {
                    // ── 서버 잡 (전체/일시정지/진행중) ──
                    let jobs = AppState.shared.jobs
                    if !jobs.isEmpty {
                        serverJobsSection(jobs)
                    }

                    // ── 로컬 전송 ──
                    if transfers.actives.isEmpty && jobs.isEmpty {
                        Text("서버에서 다운로드 중인 작업이 없습니다.\n'추가' 버튼으로 URL을 입력하세요.")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 24)
                    }
                    ForEach(Array(transfers.actives.reversed())) { a in
                        RowView(a: a)
                    }
                }
                .padding(10)
            }
        }
        .onReceive(Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()) { _ in
            tick.toggle()
        }
        .opacity(tick ? 1 : 0.999)
    }

    @ViewBuilder
    private func serverJobsSection(_ jobs: [Job]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("서버 다운로드")
                .font(.caption2)
                .fontWeight(.semibold)
                .foregroundStyle(.secondary)
                .padding(.leading, 4)

            ForEach(jobs) { job in
                serverJobRow(job)
            }
        }
    }

    @ViewBuilder
    private func serverJobRow(_ job: Job) -> some View {
        HStack(spacing: 8) {
            Image(systemName: stateIcon(job.state))
                .font(.system(size: 11))
                .foregroundStyle(stateColor(job.state))
                .frame(width: 14)

            VStack(alignment: .leading, spacing: 2) {
                Text(job.filename)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(job.state)
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    if job.totalBytes > 0 {
                        Text("\(Int(job.progress * 100))%")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer()

            // ⬇ 로컬에 저장
            Button {
                AppState.shared.receiveJob(job)
            } label: {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .help("서버에서 로컬 저장 폴더로 다운로드")
            .disabled(job.state == "DONE" || job.state == "FAILED")

            // ⏸ ▶ 일시정지/재개
            Button {
                Task { await AppState.shared.toggleJob(job) }
            } label: {
                Image(systemName: job.state == "PAUSED" ? "play.circle" : "pause.circle")
                    .font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .help(job.state == "PAUSED" ? "재개" : "일시정지")

            // 🗑 삭제
            Button {
                Task { await AppState.shared.removeJob(job) }
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help("서버에서 삭제")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.04)))
    }

    private func stateIcon(_ s: String) -> String {
        switch s {
        case "DONE": return "checkmark.circle.fill"
        case "FAILED": return "xmark.circle.fill"
        case "PAUSED": return "pause.circle.fill"
        default: return "arrow.down.circle.fill"
        }
    }

    private func stateColor(_ s: String) -> Color {
        switch s {
        case "DONE": return .green
        case "FAILED": return .red
        case "PAUSED": return .orange
        default: return .accentColor
        }
    }
}

private struct AddDownloadSheet: View {
    @State private var urlText = ""
    @State private var shaText = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("다운로드 추가")
                .font(.headline)
            Text("여러 URL은 줄바꿈 또는 공백으로 구분")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextEditor(text: $urlText)
                .font(.system(size: 12, design: .monospaced))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 90)
                .padding(6)
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Color.primary.opacity(0.15)))
            TextField("SHA-256 (선택 — 단일 URL에만 적용, 완료 후 검증)", text: $shaText)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 11, design: .monospaced))
            HStack {
                Spacer()
                Button("취소") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("추가") {
                    let urls = urlText
                    let sha = shaText
                    dismiss()
                    Task { await AppState.shared.addDownloads(urls, sha256: sha) }
                }
                .keyboardShortcut(.defaultAction)
                .buttonStyle(.borderedProminent)
                .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(16)
        .frame(width: 440)
    }
}

private struct RowView: View {
    let a: TransferManager.Active

    private var fraction: Double {
        guard a.total > 0 else { return a.finished ? 1 : 0 }
        return min(Double(a.received) / Double(a.total), 1)
    }
    private var statusLabel: String {
        if a.failed { return "실패" }
        if a.finished { return "완료" }
        return a.direction == .upload ? "보내는 중" : "받는 중"
    }
    private var color: Color {
        if a.failed { return .red }
        if a.finished { return .green }
        return a.direction == .upload ? .orange : .accentColor
    }
    private var icon: String {
        if a.failed { return "exclamationmark.triangle" }
        if a.finished { return "checkmark.circle" }
        return a.direction == .upload ? "arrow.up.circle" : "arrow.down.circle"
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .font(.system(size: 16))

            VStack(alignment: .leading, spacing: 3) {
                Text(a.name)
                    .font(.callout)
                    .lineLimit(1)
                    .truncationMode(.middle)
                ProgressView(value: fraction)
                    .progressViewStyle(.linear)
                    .tint(color)
                HStack(spacing: 6) {
                    Text(a.total > 0 ? "\(Fmt.bytes(a.received)) / \(Fmt.bytes(a.total))" : Fmt.bytes(a.received))
                    if a.total > 0 { Text("\(Int((fraction * 100).rounded()))%") }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            Spacer(minLength: 4)

            Text(statusLabel)
                .font(.caption)
                .foregroundStyle(color)
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(color.opacity(0.14), in: Capsule())

            if a.finished, !a.failed, !a.destPath.isEmpty {
                Button {
                    NSWorkspace.shared.selectFile(a.destPath, inFileViewerRootedAtPath: "")
                } label: {
                    Image(systemName: "folder")
                }
                .buttonStyle(.borderless)
                .help("Finder에서 보기")
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - 🐞 디버그

struct DebugLogView: View {
    @State private var all: [DebugLog.Line] = []
    @State private var query = ""
    @State private var selected: Set<Int> = []
    @State private var autoScroll = true
    @State private var lastCount = 0

    private var filtered: [DebugLog.Line] {
        query.isEmpty ? all : all.filter { $0.text.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Text("DebugLog · \(filtered.count)줄")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Image(systemName: "magnifyingglass")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                TextField("로그 검색", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .controlSize(.small)
                    .frame(maxWidth: 260)

                Toggle("자동 스크롤", isOn: $autoScroll)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .controlSize(.small)

                Spacer()

                Button("선택 복사") { copySelected() }
                    .controlSize(.small)
                    .disabled(selected.isEmpty)
                    .keyboardShortcut("c", modifiers: .command)
                    .help("선택한 줄을 클립보드로 복사 (Cmd+클릭/Shift+클릭으로 다중 선택)")
                Button("전체 복사") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(filtered.map(\.text).joined(separator: "\n"), forType: .string)
                }
                .controlSize(.small)
                Button("비우기", role: .destructive) {
                    DebugLog.shared.clear()
                    all = []
                    selected = []
                }
                .controlSize(.small)
            }
            .padding(.horizontal, 12)
            .padding(.top, 6)

            ScrollViewReader { proxy in
                List(selection: $selected) {
                    ForEach(filtered) { line in
                        Text(line.text)
                            .font(.system(size: 11, design: .monospaced))
                            .textSelection(.enabled)
                            .id(line.id)
                            .listRowSeparator(.hidden)
                    }
                }
                .listStyle(.plain)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
                .onChange(of: all.count) { _ in
                    guard autoScroll, lastCount < all.count,
                          let last = filtered.last else { lastCount = all.count; return }
                    withAnimation(.none) { proxy.scrollTo(last.id, anchor: .bottom) }
                    lastCount = all.count
                }
            }
        }
        .onReceive(Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()) { _ in
            all = DebugLog.shared.taggedLines
        }
    }

    private func copySelected() {
        let text = filtered.filter { selected.contains($0.id) }.map(\.text).joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

// MARK: - ⚙️ 설정

struct SettingsViewMac: View {
    @State private var address = AppState.shared.settings.manualAddress
    @State private var password = ""
    @State private var connState = ""

    private var s: SettingsStore { AppState.shared.settings }
    private var app: AppState { AppState.shared }

    var body: some View {
        Form {
            Section("서버") {
                HStack {
                    TextField("주소 (예: 192.168.0.10:8080)", text: $address)
                        .textFieldStyle(.roundedBorder)
                    Button("연결") {
                        s.manualAddress = AppState.normalize(address)
                        Task {
                            let ok = await app.tryConnect(s.manualAddress)
                            connState = ok ? "연결됨" : "연결 실패"
                        }
                    }
                    .disabled(address.isEmpty)
                    Button("다시 검색") {
                        Task { await app.rescan() }
                    }
                }
                Toggle("실행 시 서브넷 자동 스캔", isOn: boolBind(\.autoScan))
                Text(connState.isEmpty ? app.connection.label : connState)
                    .font(.caption)
                    .foregroundStyle(app.isConnected ? .green : .secondary)
            }

            Section("웹 접속 암호 (HTTP Basic)") {
                Toggle("인증 사용", isOn: boolBind(\.authEnabled))
                if s.authEnabled {
                    TextField("사용자명", text: Binding(
                        get: { s.authUser },
                        set: { s.authUser = $0 }
                    ))
                    HStack {
                        SecureField(
                            password.isEmpty && !s.authPassword.isEmpty ? "저장된 비밀번호" : "비밀번호",
                            text: $password
                        )
                        Button("저장") {
                            s.authPassword = password
                            DebugLog.shared.i("Settings", "비밀번호 변경")
                        }
                        .disabled(password.isEmpty)
                    }
                }
            }

            Section("받기") {
                HStack {
                    Text(s.downloadFolder)
                        .font(.caption)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Button("변경…") { chooseFolder() }
                    Button {
                        NSWorkspace.shared.open(URL(fileURLWithPath: s.downloadFolder))
                    } label: {
                        Image(systemName: "folder")
                    }
                    .help("폴더 열기")
                }
                Toggle("받기 완료 시 macOS 알림", isOn: boolBind(\.notificationsEnabled))
            }

            Section("일반") {
                Picker("테마:", selection: Binding(
                    get: { s.themeMode },
                    set: { s.themeMode = $0 }
                )) {
                    Text("시스템").tag("system")
                    Text("라이트").tag("light")
                    Text("다크").tag("dark")
                }
                .pickerStyle(.segmented)
                .frame(width: 240)
                Toggle("메뉴바에 총 속도 표시", isOn: boolBind(\.showSpeedInMenuBar))
                Toggle("로그인 시 자동 실행", isOn: boolBind(\.launchAtLogin))
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
    }

    private func boolBind(_ kp: ReferenceWritableKeyPath<SettingsStore, Bool>) -> Binding<Bool> {
        Binding(
            get: { s[keyPath: kp] },
            set: { s[keyPath: kp] = $0 }
        )
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: s.downloadFolder)
        panel.message = "받은 파일을 저장할 폴더를 선택해 주세요"
        if panel.runModal() == .OK, let url = panel.url {
            s.downloadFolder = url.path
            DebugLog.shared.i("Settings", "받기 폴더 변경: \(url.path)")
        }
    }
}
