import Foundation
import SwiftUI
import DroidRelayCore
import AppKit

/// 앱 전역 상태. @Observable 이므로 view 가 자동으로 갱신된다.
@Observable
@MainActor
final class AppModel {
    enum Phase: Equatable {
        case idle
        case discovering
        case connected(DiscoveryStrategy)
        case failed
    }

    /// 팝오버 3탭 (M3). 웹 대시보드의 3탭 구성과 같은 순서다 —
    /// 사용자가 두 UI 를 오갈 때 mental model 이 안 깨지게.
    enum Tab: String, CaseIterable, Identifiable {
        case downloads, torrents, storage
        var id: String { rawValue }
        var title: String {
            switch self {
            case .downloads: return "다운로드"
            case .torrents: return "토렌트"
            case .storage: return "보관함"
            }
        }
        var icon: String {
            switch self {
            case .downloads: return "arrow.down.circle"
            case .torrents: return "arrow.triangle.2.circlepath"
            case .storage: return "tray.full"
            }
        }
    }

    @ObservationIgnored var selectedTab: Tab = .downloads

    var phase: Phase = .idle
    var server: ServerInfo?
    var jobs: [RelayClient.Job] = []
    var torrents: [Torrent] = []
    var storage: [StorageEntry] = []
    /// **지금 보고 있는 보관함 위치** — `""` 이면 루트, `"M/영화"` 면 그 안.
    ///
    /// ## 왜 경로를 기억해야 하는가
    ///
    /// `GET /api/storage` 는 **항상 한 겹만** 돌려준다. 하위 폴더를 보려면
    /// `?path=` 를 줘야 하는데, 그 경로를 기억하지 않으면
    /// 폴더를 눌러도 항상 루트 목록이 나온다 — **누르는 아무것도 안 된다.**
    var storagePath: String = ""
    /// **지금 열 작업 대상** — 인라인 입력/확인에 쓴다.
    ///
    /// 이전 버전은 `model.currentTargetID` 만 설정하고 **입력 칸(`@State`)을
    /// 채우지 않았다.** 그래서 "실행" 이 **영영 비활성**이었다.
    /// → 대상과 입력을 **하나의 값**으로 묶어 둘 다 움직이게 한다.
    var storageEdit: StorageEdit?
    /// **휴지통 내용물** — `GET /api/storage/trash`.
    ///
    /// 복원/영구 삭제는 **여기서** 한다. "휴지통으로 보내기"만 있고 되돌릴 방법이
    /// 없으면 그것은 삭제가 아니라 **데이터를 잃게 하는 UI** 다.
    var trash: [StorageTrashItem] = []
    /// **마지막 쓰기 동작의 결과 — 화면에 보인다.**
    ///
    /// ## 왜 이게 필요한가
    ///
    /// 쓰기 동작(일시정지/재개/삭제/이름바꾸기)은 **조용히 실패한다.**
    /// 서버가 400 으로 거절하거나 항목이 이미 사라졌을 때 UI 는 아무 변화가 없다.
    /// 사용자는 "눌렀다" 고 믿지만 서버는 거부했다 — **근거 없이 성공을 믿게 된다.**
    ///
    /// 그래서 실패를 **눈에 보이게** 남긴다. 성공은 조용해도 된다
    /// (화면이 곧 결과다). 실패만 남기면 "왜 안 바뀌지" 를 스스로 답할 수 있다.
    /// **삭제/조작 확인 대기 중인 대상.** `nil` 이면 확인창이 없다.
    ///
    /// ## 왜 서버를 바로 때리지 않나
    ///
    /// 이 화면을 만들면서 **사용자 토렌트 2건을 지웠다.** 원인은 단순했다:
    /// 삭제 버튼이 확인 없이 곧바로 서버를 때렸다.
    ///
    /// 검증하러 눌렀는데 진짜 데이터가 사라졌다. 되돌릴 수 없는 것이었다.
    /// → **모든 파괴 동작은 이 값이 세워질 때까지만 UI 가 대기**한다.
    ///
    /// 컨펌은 `ConfirmSpec` 가 **종류별 서로 다른 문구**를 만든다.
    /// (완료 토렌트는 "파일 남음", 미완료는 "조각도 삭제", 잡은 "파일까지 삭제")
    var confirm: ConfirmRequest?

    /// **확인창을 세운다** — 아직 아무것도 지우지 않는다.
    func askConfirm(_ c: ConfirmRequest) { confirm = c }

    /// **확인창을 닫는다** — 아무 일도 하지 않는다.
    func cancelConfirm() { confirm = nil }

    /// **확인했다 — 이제서야 서버를 때린다.**
    ///
    /// ## 파괴 동작을 여기서만 호출하는 이유
    ///
    /// 컨펌 UI 의 "확인" 버튼은 **이 함수 하나만** 부른다.
    /// 행의 "삭제" 버튼은 `askConfirm` 만 부른다.
    /// → 서버를 때리는 경로가 **하나**뿐이라, 우회로가 생기지 않는다.
    func performConfirm() {
        guard let c = confirm else { return }
        confirm = nil
        guard let s = server else { return }
        // 대상이 목록에서 사라졌을 수 있다(다른 곳에서 지웠을 수도 있다).
        // 그래도 시도한다 — 서버가 "없음" 이라고 답하면 배너로 보인다.
        switch c.kind {
        case .job(let j): jobDeleteNow(j)
        case .torrent(let t): torrentDeleteNow(t.id)
        case .storage(let e): storageTrash(e)
        case .purge(let name):
            Task {
                let r = await RelayClient(base: s.baseURL).storagePurge(name: name)
                lastResult = r.ok ? "영구 삭제 완료" : "영구 삭제 실패 — \(r.message)"
                if r.ok { await reloadTrash(s) }
                await refresh()
            }
        case .move(let e):
            // 이동은 **목적지 경로가 추가로 필요하다.** 컨펌에서 바로 하지 않고
            // 인라인 편집을 연다 — 물어볼 게 아직 남았다.
            beginStorageEdit(e, .move)
        case .rename(let e):
            // 이름변경도 **새 이름이 추가로 필요하다.** 같은 이유로 편집창을 연다.
            beginStorageEdit(e, .rename)
        case .restore(let name):
            // **복원 위치가 보장되지 않는다** — 서버가 원래 위치를 기억하지 않아
            // 보관함 루트로 돌아온다. 컨펌에서 확인받고 여기서 실행한다.
            Task {
                let r = await RelayClient(base: s.baseURL).storageRestore(name: name)
                lastResult = r.ok ? "복원 완료" : "복원 실패 — \(r.message)"
                if r.ok { await reloadTrash(s) }
                await refresh()
            }
        }
    }

    var lastResult: String = ""
    var storageFree: Int = 0

    // MARK: - 추가 입력 (다운로드 · 토렌트)

    /// **다운로드 추가 칸의 내용** — 모델에 둔다.
    ///
    /// ## 왜 `@State` 로 두지 않았는가 (PR #28 교훈)
    ///
    /// `@State` 로 두면 **"추가" 버튼의 활성 조건**이 화면 쪽 값(복사본)을 보고,
    /// **실제 전송**은 모델 값을 본다. 둘이 어긋나면 "눌러도 아무 일 없다".
    /// 보관함 편집이 정확히 그 증상으로 실패했다.
    ///
    /// → **입력도 모델에 두고 하나만 본다.** 어긋날 여지가 없다.
    var addUrlText: String = ""

    /// **토렌트(magnet) 추가 칸의 내용** — 같은 이유로 모델에 둔다.
    var addMagnetText: String = ""

    /// **다운로드 추가 칸이 "추가" 를 받을 수 있는가** — 웹 1789행 조건.
    var canAddUrl: Bool { !AddInput.urls(addUrlText).isEmpty }

    /// **토렌트 추가 칸이 "추가" 를 받을 수 있는가** — 웹 1806행 조건.
    var canAddMagnet: Bool { AddInput.isMagnet(addMagnetText) }

    /// **보관함 인라인 편집 상태 — 대상 경로와 입력값이 항상 같이 움직인다.**
    ///
    /// ## 왜 하나의 값인가
    ///
    /// 이전에는 `@State private var target = ""` 와 `model.currentTargetID` 가
    /// **따로** 있었다. 대상만 설정하고 입력 칸은 비워 두니까
    /// `canRun == false` → **"실행" 버튼이 영영 눌리지 않았다.**
    ///
    /// 두 값을 **하나로 묶으면** 이런 어긋남이 구조적으로 불가능해진다.
    /// 입력 칸이 비어 있으면 아예 편집창이 안 열린다.
    /// **확인 대기 중인 동작 하나** — 대상과, 사용자에게 보여줄 문구를 함께 담는다.
    ///
    /// ## 왜 문구까지 태그에 넣는가
    ///
    /// 컨펌 UI 가 `kind` 마다 `if` 로 문구를 만들면 **UI 와 Core 의 규칙이 둘로 갈라진다.**
    /// (이번에 실제 사고가 났듯이, 규칙이 어긋나면 사용자가 모르게 파일을 잃는다)
    /// → **`ConfirmSpec` 한 곳에서 만든다.** UI 는 그리기만 한다.
    struct ConfirmRequest: Identifiable, Equatable {
        enum Kind: Equatable {
            case job(RelayClient.Job)
            case torrent(Torrent)
            case storage(StorageEntry)
            /// **휴지통 항목 이름** — `StorageEntry` 로는 만들 수 없다(휴지통 목록의 항목형)
            case purge(String)
            case move(StorageEntry)
            case rename(StorageEntry)
            /// **휴지통 항목 이름** — 복원 위치가 보장되지 않아 확인이 필요하다
            case restore(String)
        }
        let id = UUID()
        let kind: Kind
        let spec: ConfirmSpec

        init(kind: Kind) {
            self.kind = kind
            switch kind {
            case .job(let j): self.spec = ConfirmSpec.job(j)
            case .torrent(let t): self.spec = ConfirmSpec.torrent(t)
            case .storage(let e): self.spec = ConfirmSpec.storage(e)
            case .purge(let n): self.spec = ConfirmSpec.purge(n)
            case .move(let e): self.spec = ConfirmSpec.move(e)
            case .rename(let e): self.spec = ConfirmSpec.rename(e)
            case .restore(let n): self.spec = ConfirmSpec.restore(n)
            }
        }
    }

    struct StorageEdit: Identifiable, Equatable {
        /// **입력값이 필요한 동작만** 담는다.
        ///
        /// ## 왜 `purge`/`restore` 가 없는가 — 컨펌 우회로 차단
        ///
        /// 원래 여기에 `purge`/`restore` 가 있었고 `runStorageEdit` 이 그것도 실행했다.
        /// 그러면 **`confirm` 을 거치지 않고 삭제하는 경로가 코드에 남는다.**
        /// (언젠가 화면 하나가 `beginStorageEdit(.purge, …)` 를 쓰면 컨펌 없이 지워진다)
        ///
        /// 파괴 동작은 **`performConfirm()` 하나만** 실행할 수 있게 한다.
        /// 여기서 빼는 것으로 **우회로가 컴파일 에러로 드러난다** —
        /// 다시 넣으려면 반드시 컨펌 경로를 고쳐야 한다.
        enum Kind: String, Equatable {
            case rename = "이름 변경"
            case move = "이동"
            case mkdir = "새 폴더"
        }
        let id = UUID()
        var kind: Kind
        /// **대상 전체 경로** (예: `M/영화/예편.mkv`) — 서버가 받는 `from` 값
        var path: String
        /// **사용자가 입력한 값** — 비어 있으면 실행 불가
        var input: String = ""
        /// **이동 목적지 폴더 경로** (`move` 에서만 쓴다)
        var destDir: String = ""

        /// **실행할 수 있는가** — 입력값이 **실제로 채워졌을 때만** 참.
        ///
        /// 서버는 이 값이 비면 조용히 실패하거나 422 를 돌려준다.
        /// 버튼을 **못 누르게** 하는 게 그보다 낫다.
        var canRun: Bool {
            switch kind {
            case .rename, .mkdir: return !input.trimmingCharacters(in: .whitespaces).isEmpty
            case .move: return !destDir.trimmingCharacters(in: .whitespaces).isEmpty
            }
        }
    }
    /// Droid(앱) 속도 — 잡+토렌트를 서버가 준 값에서 합산한다(웹 대시보드와 동일 계산)
    var droidSpeed: SpeedReading = .init(downBps: 0, upBps: 0)
    /// 기기(폰 전체) 속도 — 서버의 누적 카운터를 **여기서** 시간 차로 나눈 값.
    var deviceSpeed: SpeedReading = .init(downBps: 0, upBps: 0)
    /// 직전 기기 카운터와 그 시각 — 속도 계산에 쓴다.
    private var lastDeviceTraffic: (t: DeviceTraffic, at: Date)?
    /// 서버가 기기 카운터를 지원하는가 — 스위치를 이 값으로 켜고 끈다.
    var deviceSpeedAvailable = false
    /// **출처별 이력** — 그래프의 4계열이 각각 이걸 쓴다.
    ///
    /// **출처마다 따로 둬야 한다.** 하나로 공유하면 Droid 와 기기 샘플이 같은 배열에
    /// 번갈아 들어가서 **선이 뒤섞여** 어느 출처가 움직였는지 알 수 없다.
    ///
    /// **`@ObservationIgnored` 필수** — 이건 그래프가 읽는 **저장소**일 뿐 화면 상태가
    /// 아니다. 관찰 대상이면 `push()` 때마다 뷰가 리로드돼 1초마다 그래프가 새로 그려진다.
    @ObservationIgnored private var histories: [SpeedSource: SpeedHistory] = [
        .droid: SpeedHistory(capacity: 300), .device: SpeedHistory(capacity: 300)
    ]
    /// 그래프 표시 창(초) — 메뉴바 속도와 같은 간격 기준
    private static let historySpan: TimeInterval = 60

    /// 출처의 이력 — **읽기 전용.** 새 항목을 만들지 않는다.
    ///
    /// **여기서 지연 생성을 하면 안 된다(실제 사고).** 이 함수가 `body` 평가 중에
    /// 불리면 **뷰 갱신 중에 상태를 변형**하는 것이 되어 SwiftUI 가 예기치 않게
    /// 동작한다 — 실제로 팝오버가 뜨지 않게 됐다. 생성은 `init` 에서 끝내 둔다.
    func history(for source: SpeedSource) -> SpeedHistory {
        histories[source] ?? SpeedHistory(capacity: 300)
    }

    /// 그래프가 그릴 최근 N 초치 (샘플).
    ///
    /// **배열 전체를 주지 않는 이유** — 최대 300샘플을 그대로 그리면 화면 폭보다
    /// 촘촘해져 aliasing 이 생기고, 1초 샘플링이 잠시 멈췄다가 복구되면 x 간격이
    /// 왜곡된다(과거가 오른쪽에 몰린다). **시간 축을 균등하게** 재구성한다.
    func graphSeries(_ source: SpeedSource, down: Bool, now: Date = Date()) -> [Int] {
        let h = history(for: source)
        let raw = h.series(down)
        guard !raw.isEmpty else { return [] }
        let points = Int(Self.historySpan)             // 60점
        guard raw.count >= 2 else { return raw }
        // 최근 points 개만 취하고, 부족하면 앞을 0 으로 채워 왼쪽(=과거) 부터 그린다.
        let tail = raw.suffix(points)
        return Array(repeating: 0, count: max(0, points - tail.count)) + tail
    }
    /// 속도 샘플러 — 서버 tick(가변 간격)으로 부하를 늘리지 않고 1초에 한 번만 뽑는다
    private var speedTask: Task<Void, Never>?
    var storageTotal: Int = 0
    var lastUpdate: Date?

    private var discovery: ServerDiscovery
    private var stream = EventStream()
    private var pollTask: Task<Void, Never>?
    private var stored: String?

    init(storedAddress: String? = nil) {
        if let s = storedAddress {
            let parts = s.split(separator: ":")
            if parts.count == 2, let p = Int(parts[1]) {
                stored = s
                discovery = ServerDiscovery(cached: ServerInfo(host: String(parts[0]), port: p, version: ""))
            } else {
                discovery = ServerDiscovery()
            }
        } else {
            discovery = ServerDiscovery()
        }
    }

    var activeJobs: [RelayClient.Job] { jobs.filter { $0.isActive } }
    var seedingJobs: [RelayClient.Job] { jobs.filter { $0.isSeeding } }
    /// 배지는 **탭과 무관하게** 전체 활성 수를 센다 — 어느 탭에 있든 진행 중임을 알린다.
    var activeTorrents: [Torrent] { torrents.filter { $0.isActive } }
    var badgeCount: Int { activeJobs.count + activeTorrents.count }

    /// 메뉴바에 표시할 출처 — 설정으로 고른다.
    ///
    /// **저장된 값을 로드한다** — 기본값으로 시작하면 토글을 아무리 눌러도
    /// 재실행할 때마다 되돌아가서 "저장이 안 된다" 고 보인다.
    /// (테스트만 저장/로드를 검증하고 실제 앱은 기본값만 쓰고 있었다)
    var speedSetting = SpeedDisplaySetting.load() {
        didSet {
            speedSetting.save()
            NotificationCenter.default.post(name: .drBadgeChanged, object: nil)
        }
    }

    /// **실제로 그릴 수 있는 출처** — 설정이 켠 것 중 **서버가 값을 주는 것만.**
    ///
    /// `speedSetting.sources` 를 그대로 쓰면 안 된다. 기기 속도는 서버가 아직
    /// 제공하지 않는데 설정은 켜져 있으므로(기본값) 열 자리가 하나 더 생기고
    /// 그 칸은 계속 `—` 로 찬다. 즉 **"고장 난 기능" 이 화면에 그대로 노출된다** —
    /// 내가 처음 설계할 때 경고한 바로 그 상황이다.
    ///
    /// 그래서 `0` 이 아니라 **열 자체를 만들지 않는다.** 못 쓰는 칸을 비워두는 게
    /// 사용자에게는 정직하다(값이 0 인 것과 값을 모르는 것은 다르다).
    var visibleSpeedSources: [SpeedSource] {
        speedSetting.sources.filter { $0 != .device || deviceSpeedAvailable }
    }

    func speed(for source: SpeedSource) -> SpeedReading {
        switch source {
        case .droid: return droidSpeed
        case .device: return deviceSpeed
        }
    }

    /// Droid 속도 = HTTP 잡(다운로드만) + 활성 토렌트(다운/업)
    private func recomputeDroidSpeed() {
        // **잡 상태 문자열을 하드코딩하면 안 된다.** 이전엔 `"RUNNING"` 을 찾았는데
        // 서버는 `"DOWNLOADING"` 을 보낸다 — 그래서 잡 속도는 **영영 0** 이었다.
        // 대소문자·언어를 무시하도록 바꾼다.
        let jobsDown = jobs.filter { $0.isRunning }.reduce(0) { $0 + $1.speedBps }
        let active = torrents.filter { $0.isActive }
        let tDown = active.reduce(0) { $0 + $1.downloadBps }
        let tUp = active.reduce(0) { $0 + $1.uploadBps }
        droidSpeed = SpeedReading(downBps: jobsDown + tDown, upBps: tUp)
        history(for: .droid).push(droidSpeed)
    }

    // MARK: - 탐색

    func connect() async {
        phase = .discovering
        guard let r = await discovery.discover() else {
            phase = .failed
            return
        }
        server = r.info
        stored = r.info.displayAddress
        phase = .connected(r.strategy)
        await refresh()
        subscribe()
    }

    /// 설정 창에서 주소를 직접 넣었을 때.
    func useManualAddress(_ address: String) async {
        let parts = address.split(separator: ":")
        // 포트는 형식 검증에만 쓴다 — 실제 접속은 displayAddress(포트 포함)로 간다
        guard parts.count == 2, Int(parts[1]) != nil,
              let u = URL(string: "http://\(address)")
        else { phase = .failed; return }
        guard let info = await RelayClient.probe(u) else { phase = .failed; return }
        server = info
        stored = info.displayAddress
        phase = .connected(.manual)
        await refresh()
        subscribe()
    }

    var storedAddress: String? { stored }

    /// 진단 출력용 — 지금 어느 전략으로 붙어 있는지
    var phaseLabel: String {
        switch phase {
        case .connected(let s): return s.displayName
        case .discovering: return "탐색 중"
        case .failed: return "실패"
        case .idle: return "대기"
        }
    }

    // MARK: - 갱신

    func refresh() async {
        guard let s = server else { return }
        let client = RelayClient(base: s.baseURL)
        // 3개 탭이 같은 서버를 본다 — 필요한 것만 한 번에.
        // SSE tick(빠르면 1초)마다 4요청을 쏘면 서버·망이 불필요하게 busy 해진다.
        // **선택된 탭의 것만** 갱신하고 나머지는 그 탭이 열릴 때 당긴다.
        async let j = selectedTab == .downloads ? client.jobs() : jobs
        // **토렌트는 탭이 열려 있지 않아도 반드시 당긴다.** Droid 속도의 대부분을
        // 잡이 아니라 토렌트가 차지하는데, 여기서 조건부로 부르면 탭이 '다운로드' 일 때
        // `torrents` 가 영영 비어 속도가 계속 0 이 된다.
        // (실측: 서버는 10KB/s 를 보내는데 메뉴바는 '—' — 탭 조건이 원인이었다)
        async let t = client.torrents()
        // **현재 위치 경로를 반드시 넘긴다.** 루트 고정이면 하위 폴더를 못 연다.
        async let f = selectedTab == .storage ? client.storage(path: storagePath) : storage
        async let i = client.serverInfo()
        let (jobs, torrents, storage, info) = await (j, t, f, i)
        self.jobs = jobs
        self.torrents = torrents
        self.storage = storage
        recomputeDroidSpeed()
        if let info {
            storageFree = info.storageFree
            storageTotal = info.storageTotal
        }
        lastUpdate = Date()
    }

    /// 탭을 바꾸면 그 탭 데이터는 즉시 당긴다 — 10초 백업 폴링을 기다리지 않는다.
    func selectTab(_ tab: Tab) async {
        guard tab != selectedTab else { return }
        selectedTab = tab
        await refresh()
    }

    // MARK: - 추가 (다운로드 · 토렌트)

    /// **다운로드 추가** — `POST /api/jobs` 를 URL 개수만큼.
    ///
    /// ## 웹과 같은 일, 웹과 같은 문구 (1786~1803행)
    ///
    /// - 공백/쉼표/줄바꿈으로 나누고 `https?://` 만 보낸다 (`AddInput`).
    /// - **여러 개면 전부 시도**하고 성공/실패 개수를 알려준다.
    ///   하나만 보내고 조용히 놓치는 일은 없다.
    /// - **전부 시도한 뒤** 입력 칸을 비우고 목록을 다시 당긴다.
    ///
    /// ## 왜 개수를 세어 알려주는가
    ///
    /// 웹은 `showDlToast(ok+'건 추가'+(fail>0?' · 실패 '+fail+'건':''))` 라고
    /// **실패가 섞였음을 드러낸다.** 클라이언트가 "완료" 만 말하면
    /// 3개 중 1개가 조용히 422 를 받은 걸 사용자는 모른다.
    func addDownloads() {
        guard let s = server else { return }
        let urls = AddInput.urls(addUrlText)
        // **빈 입력으로는 아무 요청도 가지 않는다.** 웹 1789행 alert 과 같은 조건.
        guard !urls.isEmpty else {
            lastResult = "추가 실패 — http:// 또는 https:// 로 시작하는 주소가 없습니다"
            return
        }
        let raw = addUrlText
        Task {
            var ok = 0
            var firstErr = ""
            for u in urls {
                let r = await RelayClient(base: s.baseURL).addDownload(url: u)
                if r.ok { ok += 1 } else if firstErr.isEmpty { firstErr = r.message }
            }
            // **같은 칸을 연속으로 두 번 추가할 수 있게 지운다.**
            // (사용자가 전송 도중 지우고 싶어할 수 있다. 웹도 지운다 — 1798행)
            if addUrlText == raw { addUrlText = "" }
            if ok == urls.count {
                lastResult = "다운로드 \(ok)건 추가"
            } else {
                // 실패 사유를 **버리지 않는다** — 몇 건 중 몇 건, 그리고 왜.
                lastResult = "추가 \(ok)/\(urls.count)건 · 실패 사유: \(firstErr)"
            }
            await refresh()
        }
    }

    /// **토렌트 추가** — `POST /api/torrents/add`.
    ///
    /// magnet 도 `.torrent` URL 도 받는다는 점은 웹과 같지만,
    /// **형식을 여기서 먼저 확인한다** — 서버까지 가서 422 를 받는 것보다 빠르다.
    func addMagnet() {
        guard let s = server else { return }
        let raw = addMagnetText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            lastResult = "추가 실패 — magnet:? 로 시작하는 링크를 입력하세요"
            return
        }
        let original = addMagnetText
        Task {
            let r = await RelayClient(base: s.baseURL).addTorrent(magnetOrURL: raw)
            // **성공한 경우에만** 칸을 비운다. 실패하면 입력값을 지워서
            // 사용자가 "방금 붙여넣은 링크" 를 잃어버리게 하지 않는다.
            if r.ok, addMagnetText == original { addMagnetText = "" }
            lastResult = r.ok ? "토렌트 추가됨" : "추가 실패 — \(r.message)"
            await refresh()
        }
    }

    /// 잡 일시정지/재개 — 실패하면 **화면에 남긴다.**
    func control(_ id: String, _ action: String) {
        guard let s = server else { return }
        Task {
            let ok = await RelayClient(base: s.baseURL).control(id, action)
            if !ok { lastResult = "\(action) 실패 — 서버가 거절했습니다" }
            await refresh()
        }
    }

    /// 잡 삭제 — `DELETE /api/jobs/{id}`.
    ///
    /// 일시정지/재개와 **다른 경로**다. 잡 라우트에 `cancel` 이 없어서
    /// `control(id, "cancel")` 은 400 "지원 없는 동작" 이었다(실측).
    ///
    /// **컨펌을 거쳐야 한다** — 이 함수는 "확인" 에서만 호출된다.
    /// UI 의 삭제 버튼은 `askConfirm(.job(j))` 만 한다.
    func jobDeleteNow(_ j: RelayClient.Job) {
        guard let s = server else { return }
        Task {
            let ok = await RelayClient(base: s.baseURL).jobDelete(j.id)
            if !ok { lastResult = "삭제 실패 — 서버가 거절했습니다" }
            await refresh()
        }
    }

    /// **잡 삭제 컨펌을 세운다** — 아직 지우지 않는다.
    func askJobDelete(_ j: RelayClient.Job) { askConfirm(.init(kind: .job(j))) }

    /// 잡 속도 제한 — `POST /api/jobs/{id}/limit` (B/s).
    ///
    /// **UI 가 보낸 B/s 를 그대로 넘긴다.** 단위 변환은 `SpeedPresets` 가
    /// 화면에서 이미 끝냈고, 여기서 한 번 더 바꾸면 값이 어긋난다.
    func jobLimit(_ id: String, _ maxDownBps: Int) {
        guard let s = server else { return }
        Task {
            let r = await RelayClient(base: s.baseURL).setJobLimit(id: id, maxDownBps: maxDownBps)
            if !r.ok { lastResult = "속도 제한 실패 — \(r.message)" }
            await refresh()
        }
    }

    /// 토렌트 일시정지/재개 — `POST /api/torrents/{id}/{action}`.
    ///
    /// **반환값을 버리지 않는다.** 서버가 400 으로 거절할 수 있고
    /// (예: 이미 중지된 토렌트에 pause), 그럼 사용자는 "눌렀는데 아무 일 없다" 는
    /// 상태로 남는다. 실패를 알리고 조용히 넘어가지 않는다.
    func torrentControl(_ id: String, _ action: String) {
        guard let s = server else { return }
        Task {
            let ok = await RelayClient(base: s.baseURL).torrentControl(id, action)
            if !ok { lastResult = "토렌트 \(action) 실패 (서버가 거절했습니다)" }
            await refresh()
        }
    }

    /// **컨펌을 거쳐야 한다** — 이 함수는 "확인" 에서만 호출된다.
    func torrentDeleteNow(_ id: String) {
        guard let s = server else { return }
        Task {
            let ok = await RelayClient(base: s.baseURL).torrentDelete(id)
            if !ok { lastResult = "토렌트 삭제 실패 — 서버가 거절했습니다" }
            await refresh()
        }
    }

    /// **토렌트 삭제 컨펌을 세운다** — 아직 지우지 않는다.
    ///
    /// 미완료면 **받은 조각까지 사라진다**(서버 `deleteRecursively`).
    /// 완료면 목록에서만 빠진다. `ConfirmSpec.torrent` 가 둘을 나눠 말한다.
    func askTorrentDelete(_ t: Torrent) { askConfirm(.init(kind: .torrent(t))) }

    /// 토렌트 속도 제한 — `POST /api/torrents/{id}/limit` (B/s).
    func torrentLimit(_ id: String, _ maxDownBps: Int) {
        guard let s = server else { return }
        Task {
            let r = await RelayClient(base: s.baseURL).setTorrentLimit(id: id, maxDownBps: maxDownBps)
            if !r.ok { lastResult = "속도 제한 실패 — \(r.message)" }
            await refresh()
        }
    }

    // MARK: - 보관함 (탐색 + 조작)

    /// **하위 폴더로 내려간다.** 목록은 `storagePath` 기준으로 다시 당긴다.
    func storageEnter(_ entry: StorageEntry) {
        guard entry.isDirectory else { return }
        storagePath = entry.path
        storageEdit = nil
        Task { await refresh() }
    }

    /// **한 단계 위로.** 루트면 아무 일도 없다.
    func storageUp() {
        guard !storagePath.isEmpty else { return }
        // 마지막 `/` 앞에서 자른다 — `M/영화` → `M`, `M` → ""
        let t = storagePath.split(separator: "/").dropLast().joined(separator: "/")
        storagePath = t
        storageEdit = nil
        Task { await refresh() }
    }

    /// **탐색 경로 조각 — 빵조각 UI 용.**
    ///
    /// ## 여기서 한 번 틀렸었다
    ///
    /// 처음엔 `storagePath.isEmpty ? ["보관함"] : storagePath.split(...)` 라 써서
    /// **하위 폴더에서 루트 조각이 사라졌다.** `M/영화` 에 있으면
    /// `["영화"]` 만 떠서 **어디로든 돌아갈 방법이 화면에 없다.**
    ///
    /// 탐색 UI 는 **언제나 루트에서 시작**한다. 경로 조각 앞에 "보관함" 을 붙인다.
    /// (이걸 놓치면 사용자는 팝오버를 닫고 다시 열어야만 루트로 돌아갈 수 있다)
    var storageCrumbs: [(label: String, path: String)] {
        let parts = storagePath.isEmpty ? [] : storagePath.split(separator: "/").map(String.init)
        var out: [(label: String, path: String)] = [("보관함", "")]
        for (i, p) in parts.enumerated() {
            out.append((p, parts[0...i].joined(separator: "/")))
        }
        return out
    }

    /// **편집창을 연다.** 대상 경로와 종류를 **같이** 넣는다.
    func beginStorageEdit(_ entry: StorageEntry, _ kind: StorageEdit.Kind) {
        storageEdit = StorageEdit(kind: kind, path: entry.path,
                                  input: kind == .rename ? entry.name : "")
    }

    /// **새 폴더 만들기** — 대상은 현재 위치다.
    func beginMkdir() {
        storageEdit = StorageEdit(kind: .mkdir, path: storagePath)
    }

    /// **편집창 실행.** `canRun == false` 면 **아무것도 하지 않는다.**
    ///
    /// 서버를 불렀다 422 를 받는 것보다, 못 누르게 하는 게 낫다.
    /// (그리고 성공하면 **편집창을 닫고** 목록을 다시 당긴다 —
    ///  닫지 않으면 "실행됨" 인데 입력창이 남아 있어 성공인지 실패인지 모른다)
    func runStorageEdit() {
        guard var e = storageEdit, e.canRun, let s = server else { return }
        storageEdit = nil          // 먼저 닫는다 — 중복 실행 방지 + 즉시 반응
        let name = e.input.trimmingCharacters(in: .whitespaces)
        let dest = e.destDir.trimmingCharacters(in: .whitespaces)
        Task {
            let c = RelayClient(base: s.baseURL)
            let r: WriteResult
            switch e.kind {
            case .rename: r = await c.storageRename(from: e.path, to: name)
            case .mkdir: r = await c.storageMkdir(path: e.path, name: name)
            case .move: r = await c.storageMove(from: e.path, to: dest)
            }
            lastResult = r.ok ? "\(e.kind.rawValue) 완료" : "\(e.kind.rawValue) 실패 — \(r.message)"
            await refresh()
        }
    }

    /// **휴지통으로 보내기** — `POST /api/storage/delete`.
    ///
    /// **이 엔드포인트는 200 을 주면서 `{"error": …}` 를 담아 보낸다(실측).**
    /// 즉 **상태 코드만 보면 삭제가 성공한 것처럼 보인다.**
    /// → `WriteResult` 가 본문을 봐야 한다. 여기서 실패를 무시하면
    /// "휴지통으로 보냈다" 고 잘못 안내한다.
    /// **휴지통으로 보내기 — 컨펌을 먼저.**
    ///
    /// 되돌릴 수 있지만, **사용자가 실수로 누를 수 있다.**
    /// "삭제" 라는 이름의 버튼이 파일을 움직인다는 것을 모를 수 있다.
    func askStorageTrash(_ e: StorageEntry) { askConfirm(.init(kind: .storage(e))) }

    /// **휴지통으로 이동 실행** — 컨펌 "확인" 에서만 부른다.
    func storageTrash(_ entry: StorageEntry) {
        guard let s = server else { return }
        Task {
            let r = await RelayClient(base: s.baseURL).storageTrash(path: entry.path)
            lastResult = r.ok ? "휴지통으로 이동 — \(entry.name)" : "휴지통 이동 실패 — \(r.message)"
            // 휴지통이 하나 늘었을 수 있다. 안 읽으면 "휴지통 0개" 가 그대로 남는다.
            if r.ok { await reloadTrash(s) }
            await refresh()
        }
    }

    /// **휴지통 목록을 연다** — 복원·영구 삭제는 여기서 한다.
    func loadTrash() {
        guard let s = server else { return }
        Task { await reloadTrash(s) }
    }

    /// **휴지통 목록을 다시 읽는다.**
    ///
    /// ## 왜 쓰기 직후에도 부르는가
    ///
    /// `refresh()` 는 목록·잡·토렌트만 갱신한다. 휴지통은 안 건드린다.
    /// → 복원/영구 삭제/휴지통 이동을 하고도 **화면의 휴지통 목록은 옛날 것** 이 남는다.
    /// → 사용자는 "지웠는데 왜 아직 있어" 하고, **화면이 거짓말** 하게 된다.
    ///
    /// 쓰기 동작이 **휴지통에 영향을 주면** 반드시 이걸 다시 불러온다.
    @discardableResult
    private func reloadTrash(_ s: ServerInfo) async -> [StorageTrashItem] {
        let t = await RelayClient(base: s.baseURL).storageTrashList()
        trash = t
        return t
    }

    // MARK: - 실시간

    private func subscribe() {
        guard let s = server else { return }
        Task { await stream.run(base: s.baseURL) { @Sendable [weak self] in
            await self?.refresh()
        } }
        startSpeedSampling()
        // SSE 가 조용히 죽어도 진행률이 멈추면 안 된다 — 10초 백업 폴링
        // (웹 대시보드가 쓰는 것과 동일한 계약)
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(10))
                if !Task.isCancelled { await refresh() }
            }
        }
    }

    /// 속도 샘플러 — **1초 주기로 스스로 시작한다.**
    ///
    /// 서버 tick 으로 부르지 않는 이유: 서버는 상태가 바뀔 때만 tick 을 보내고 평소엔
    /// 15초 beat 이다. 그걸로 그래프를 그리면 평소엔 직선이 되어 첨부 목업처럼
    /// 촘촘한 곡선이 나오지 않는다. 1초 폴링 1회로 클라이언트가 직접 쌓는다.
    private func startSpeedSampling() {
        speedTask?.cancel()
        speedTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { break }
                await refresh()
                await sampleDeviceTraffic()
            }
        }
    }

    /// 기기 트래픽을 읽어 속도로 바꾼다.
    ///
    /// **서버가 값을 안 주면 `deviceSpeedAvailable = false` 로 두고 열을 만들지 않는다.**
    /// `0` 으로 대체하면 "지원하지만 지금 0" 과 "지원 안 함" 이 구분되지 않아,
    /// 스위치를 켜놓고 항상 0 이 보이는 **고장 난 기능** 이 된다.
    private func sampleDeviceTraffic() async {
        guard let s = server else { return }
        let cur = await RelayClient(base: s.baseURL).deviceTraffic()
        guard let cur else {
            if deviceSpeedAvailable { deviceSpeedAvailable = false; deviceSpeed = .init(downBps: 0, upBps: 0) }
            return
        }
        deviceSpeedAvailable = true
        let now = Date()
        let dt = now.timeIntervalSince(lastDeviceTraffic?.at ?? now)
        deviceSpeed = DeviceTrafficRate.rate(
            previous: lastDeviceTraffic?.t, current: cur, elapsed: dt
        )
        lastDeviceTraffic = (cur, now)
        history(for: .device).push(deviceSpeed)
    }

    func disconnect() {
        Task { await stream.stop() }
        pollTask?.cancel()
        pollTask = nil
        speedTask?.cancel()
        speedTask = nil
    }

    // MARK: - 표시용

        func openDashboard() {
        guard let s = server else { return }
        NSWorkspace.shared.open(s.baseURL)
    }
}
