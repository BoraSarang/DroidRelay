import AppKit
import Foundation
import Observation

/// 연결·폴링·전체 동작을 관리하는 중앙 상태
@Observable
final class AppState {
    enum ConnState: Equatable {
        case disconnected(String)
        case scanning
        case connected(address: String)

        var label: String {
            switch self {
            case let .disconnected(msg): return msg
            case .scanning: return "서버 검색 중…"
            case let .connected(addr): return addr.replacingOccurrences(of: "http://", with: "")
            }
        }
    }

    static let shared = AppState()

    var settings = SettingsStore()
    var connection: ConnState = .disconnected("초기화 중…")
    var servers: [DiscoveredServer] = []
    var info: ServerInfo?
    var jobs: [Job] = []
    var torrents: [Torrent] = []
    var transfers = TransferManager()
    var toast: String?
    private var toastTask: Task<Void, Never>?

    @ObservationIgnored private var api: RelayAPI?
    @ObservationIgnored private var pollTask: Task<Void, Never>?

    var isConnected: Bool {
        if case .connected = connection { return true }
        return false
    }

    /// 메뉴바 표시 텍스트 (연결됨 + 속도 > 0일 때만)
    var menuBarSpeedText: String? {
        guard settings.showSpeedInMenuBar,
              isConnected,
              let speed = info?.speedTotalBps, speed > 0 else { return nil }
        return "↓" + Fmt.speed(speed)
    }

    func showToast(_ msg: String) {
        toast = msg
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { await MainActor.run { self?.toast = nil } }
        }
    }

    // MARK: - 연결

    func bootstrap() {
        Task { await connectStartup() }
    }

    @MainActor
    func connectStartup() async {
        DebugLog.shared.i("App", "기동: 자동연결 시작")
        if !settings.manualAddress.isEmpty {
            if await tryConnect(settings.manualAddress) { return }
        }
        if settings.autoScan {
            await rescan()
            if isConnected { return }
        }
        connection = .disconnected("서버가 켜져 있는지 확인해 주세요. 주소를 직접 입력해 연결할 수 있어요.")
        DebugLog.shared.e("E-MAC-NET-1001", "자동 연결 실패")
    }

    @MainActor
    func tryConnect(_ rawAddress: String) async -> Bool {
        let address = Self.normalize(rawAddress)
        guard let url = URL(string: address), !address.isEmpty else {
            showToast("주소 형식이 올바르지 않습니다. 예) 192.168.0.10:8080")
            return false
        }
        let client = RelayAPI(baseURL: url, authHeader: settings.basicAuthHeader())
        do {
            let inf = try await client.getInfo()
            api = client
            info = inf
            connection = .connected(address: address)
            settings.manualAddress = address.replacingOccurrences(of: "^https?://", with: "", options: .regularExpression)
            DebugLog.shared.i("Conn", "연결됨: \(address) v\(inf.version ?? "?")")
            startPolling()
            return true
        } catch {
            DebugLog.shared.w("Conn", "연결 실패 \(address): \(error.localizedDescription)")
            return false
        }
    }

    @MainActor
    func rescan() async {
        connection = .scanning
        let found = await ServerDiscovery.scan(auth: settings.basicAuthHeader())
        servers = found
        if let first = found.first, await tryConnect(first.address) { return }
        if !isConnected {
            connection = .disconnected("네트워크에서 DroidRelay 서버를 찾지 못했습니다. 서버가 켜져 있는지 확인해 주세요.")
        }
    }

    func disconnect() {
        pollTask?.cancel()
        pollTask = nil
        api = nil
        info = nil
        jobs = []
        torrents = []
        connection = .disconnected("연결이 해제되었습니다.")
        DebugLog.shared.i("Conn", "연결 해제")
    }

    static func normalize(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return t }
        let lower = t.lowercased()
        if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
            t = "http://" + t
        }
        while t.hasSuffix("/") { t.removeLast() }
        return t
    }

    // MARK: - 폴링 (1초)

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                let ok = await self.pollOnce()
                if !ok {
                    await MainActor.run {
                        guard self.isConnected else { return }
                        self.connection = .disconnected("연결이 끊겼습니다. 서버가 켜져 있는지 확인해 주세요.")
                        DebugLog.shared.e("E-MAC-NET-1001", "폴링 실패로 연결 끊김")
                    }
                    break
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func pollOnce() async -> Bool {
        guard let client = api else { return false }
        do {
            let j = try await client.jobs()
            let t = try await client.torrents()
            let i = try await client.getInfo()
            await MainActor.run {
                jobs = j
                torrents = t
                info = i
            }
            return true
        } catch is CancellationError {
            return false
        } catch {
            DebugLog.shared.w("Poll", "\(error.localizedDescription)")
            return false
        }
    }

    @MainActor
    private func requireAPI() throws -> RelayAPI {
        guard let a = api else {
            throw APIError(code: "E-MAC-NET-1001", message: "서버에 연결되어 있지 않습니다")
        }
        return a
    }

    // MARK: - 다운로드 잡 동작

    @MainActor
    func addDownload(_ urlString: String) async {
        guard !urlString.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        do {
            let a = try requireAPI()
            _ = try await a.addJob(url: urlString.trimmingCharacters(in: .whitespaces))
            DebugLog.shared.i("Jobs", "다운로드 추가: \(urlString)")
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
            DebugLog.shared.e("E-MAC-NET-1002", "다운로드 추가 실패: \(error.localizedDescription)")
        }
    }

    @MainActor
    func toggleJob(_ job: Job) async {
        do {
            let a = try requireAPI()
            if job.state == "PAUSED" {
                try await a.jobAction(id: job.id, "resume")
                DebugLog.shared.i("Jobs", "재개: \(job.filename)")
            } else {
                try await a.jobAction(id: job.id, "pause")
                DebugLog.shared.i("Jobs", "일시정지: \(job.filename)")
            }
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    @MainActor
    func removeJob(_ job: Job) async {
        do {
            let a = try requireAPI()
            try await a.deleteJob(id: job.id)
            DebugLog.shared.i("Jobs", "삭제: \(job.filename)")
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    @MainActor
    func receiveJob(_ job: Job) {
        do {
            let a = try requireAPI()
            transfers.receive(job: job,
                              api: a,
                              folderURL: URL(fileURLWithPath: settings.downloadFolder),
                              notifyEnabled: settings.notificationsEnabled,
                              auth: settings.basicAuthHeader())
        } catch {
            showToast(friendly(error))
        }
    }

    // MARK: - 토렌트 동작

    @MainActor
    func addMagnet(_ magnet: String) async {
        let m = magnet.trimmingCharacters(in: .whitespacesAndNewlines)
        guard m.hasPrefix("magnet:") else {
            showToast("magnet:?xt=… 로 시작하는 링크를 입력해 주세요.")
            return
        }
        do {
            let a = try requireAPI()
            _ = try await a.addTorrent(magnet: m)
            DebugLog.shared.i("Torrent", "토렌트 추가(magnet): \(m.prefix(48))…")
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    @MainActor
    func addTorrentFile(at url: URL) async {
        do {
            let data = try Data(contentsOf: url)
            let a = try requireAPI()
            _ = try await a.addTorrent(fileData: data, filename: url.lastPathComponent)
            DebugLog.shared.i("Torrent", "토렌트 추가(파일): \(url.lastPathComponent)")
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    @MainActor
    func toggleTorrent(_ t: Torrent) async {
        do {
            let a = try requireAPI()
            if t.state == "PAUSED" || t.state == "STOPPED" {
                try await a.torrentAction(id: t.id, "resume")
            } else {
                try await a.torrentAction(id: t.id, "pause")
            }
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    @MainActor
    func removeTorrent(_ t: Torrent) async {
        do {
            let a = try requireAPI()
            try await a.deleteTorrent(id: t.id)
            DebugLog.shared.i("Torrent", "토렌트 삭제: \(t.name)")
            _ = await pollOnce()
        } catch {
            showToast(friendly(error))
        }
    }

    // MARK: - 보관함 동작

    @MainActor
    func storageList(_ path: String) async -> [StorageItem] {
        do {
            let a = try requireAPI()
            return try await a.storageList(path: path)
        } catch {
            showToast(friendly(error))
            return []
        }
    }

    @MainActor
    func storageOp<T>(_ label: String, _ body: (RelayAPI) async throws -> T) async -> T? {
        do {
            let a = try requireAPI()
            let r = try await body(a)
            DebugLog.shared.i("Storage", "\(label) 완료")
            return r
        } catch {
            showToast(friendly(error))
            DebugLog.shared.e("E-MAC-STOR-1003", "\(label) 실패: \(error.localizedDescription)")
            return nil
        }
    }

    @MainActor
    func receiveStorage(item: StorageItem, in path: String) {
        do {
            let a = try requireAPI()
            transfers.receiveStorage(item: item,
                                     in: path,
                                     api: a,
                                     folderURL: URL(fileURLWithPath: settings.downloadFolder),
                                     notifyEnabled: settings.notificationsEnabled,
                                     auth: settings.basicAuthHeader())
        } catch {
            showToast(friendly(error))
        }
    }

    // MARK: - 기타

    func openDashboard() {
        guard let a = api else { return }
        NSWorkspace.shared.open(a.dashboardURL())
        DebugLog.shared.i("App", "웹 대시보드 열기: \(a.baseURL.absoluteString)")
    }

    func revealInFinder(_ path: String) {
        NSWorkspace.shared.selectFile(path, inFileViewerRootedAtPath: settings.downloadFolder)
    }

    func friendly(_ error: Error) -> String {
        if let e = error as? APIError {
            switch e.code {
            case "E-MAC-AUTH-1004":
                return "인증이 필요합니다. 설정에서 사용자명/비밀번호를 확인해 주세요."
            case "E-MAC-NET-1001":
                return "서버에 연결되어 있지 않습니다."
            default:
                return e.message
            }
        }
        return error.localizedDescription
    }
}
