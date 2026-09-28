import SwiftUI
import DroidRelayCore

/// 메뉴바 팝오버 (목업 A).
/// - 폭 352px 고정, 항목 많으면 스크롤
/// - 하단에 서버 주소 + 자동 발견 상태 표시
struct PopoverView: View {
    @Bindable var model: AppModel
    var onSettings: () -> Void
    var onQuit: () -> Void
    /// 쓰기 시트 — 어느 것이 열렸는가. nil 이면 닫힘.
    @State private var sheet: WriteSheets.Sheet?

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().opacity(0.5)
            // ── 속도 그래프 ──
            // 메뉴바는 2줄 요약이고 **자세한 추이는 여기.** 그래프가 없으면 메뉴바가
            // 숫자만 반복해서 보여주는 셈이 된다.
            // DR_NO_GRAPH 로 그래프를 끄면 팝오버가 뜨는지 비교할 수 있다.
            if !model.visibleSpeedSources.isEmpty && ProcessInfo.processInfo.environment["DR_NO_GRAPH"] == nil {
                SpeedGraph(
                    sources: model.visibleSpeedSources,
                    droidDown: model.graphSeries(.droid, down: true),
                    droidUp: model.graphSeries(.droid, down: false),
                    deviceDown: model.deviceSpeedAvailable
                        ? model.graphSeries(.device, down: true) : [],
                    deviceUp: model.deviceSpeedAvailable
                        ? model.graphSeries(.device, down: false) : []
                )
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                // **폭을 명시한다.** Canvas 는 제안된 크기를 그대로 쓰는데, VStack 안에서
                // 제안 폭이 0 이면 그래프가 0 폭이 되고 팝오버 전체가 사라진다.
                .frame(width: 328)
                Divider().opacity(0.5)
            }
            // ── 액션 바 ──
            // **탭마다 다른 동작이 필요하다.** 다운로드 탭은 "추가", 보관함 탭은
            // "폴더 만들기/휴지통", 토렌트 탭은 "추가" — 한 줄에 다 몰아넣으면
            // 어느 탭에서 쓸지 모른다.
            HStack(spacing: 6) {
                actionButton("plus", "다운로드 추가") { sheet = .addDownload }
                actionButton("arrow.down.circle", "토렌트 추가") { sheet = .addTorrent }
                Spacer()
                if model.selectedTab == .storage {
                    actionButton("folder.badge.plus", "폴더") { sheet = .mkdir }
                    actionButton("trash", "휴지통") {
                        Task { await model.loadTrash() }
                        sheet = .trash
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)

            tabBar
            Divider().opacity(0.5)
            content
            Divider().opacity(0.5)
            stats
            Divider().opacity(0.5)
            footer
        }
        .frame(width: 352)
        .sheet(item: $sheet) { which in
            WriteSheets(model: model, sheet: .constant(which))
        }
        // **실패 사유는 dismiss 하지 않는다.** 서버가 준 오류(예: "이미 등록된
        // 다운로드입니다") 는 사용자가 다음 행동을 정하는 데 필요한 정보다.
        .overlay(alignment: .bottom) {
            if !model.lastResult.ok && sheet == nil {
                resultBanner
            }
        }
    }

    private var resultBanner: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(model.lastResult.message)
                .font(.system(size: 11))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button {
                model.lastResult = .success
            } label: {
                Image(systemName: "xmark").font(.system(size: 9))
            }
            .buttonStyle(.plain).foregroundStyle(.secondary)
        }
        .padding(8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
        .padding(8)
    }

    private func actionButton(_ icon: String, _ tip: String, _ act: @escaping () -> Void) -> some View {
        Button(action: act) {
            Image(systemName: icon).font(.system(size: 11))
        }
        .buttonStyle(.borderless)
        .help(tip)
    }

    // MARK: - 탭바 (M3)

    private var tabBar: some View {
        HStack(spacing: 4) {
            ForEach(AppModel.Tab.allCases) { t in
                let on = model.selectedTab == t
                Button { Task { await model.selectTab(t) } } label: {
                    HStack(spacing: 4) {
                        Image(systemName: t.icon).font(.system(size: 10.5))
                        Text(t.title).font(.system(size: 11.5, weight: on ? .semibold : .regular))
                        if let n = badge(t), n > 0 {
                            Text("\(n)").font(.system(size: 9.5, weight: .bold))
                                .padding(.horizontal, 4).padding(.vertical, 1)
                                .background(Capsule().fill(on ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.25)))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
                    .background {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(on ? Color.accentColor.opacity(0.16) : .clear)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(t.title)
            }
        }
        .padding(.horizontal, 5)
        .padding(.vertical, 4)
    }

    /// 탭별 배지 — "이 탭에 진행 중이 몇 개나 있는가"
    private func badge(_ t: AppModel.Tab) -> Int? {
        switch t {
        case .downloads: return model.activeJobs.isEmpty ? nil : model.activeJobs.count
        case .torrents: return model.activeTorrents.isEmpty ? nil : model.activeTorrents.count
        case .storage: return nil
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.selectedTab {
        case .downloads: jobList
        case .torrents: torrentList
        case .storage: storageList
        }
    }

    // MARK: - 헤더

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 13))
            Text("DroidRelay").font(.system(size: 12.5, weight: .semibold))
            Spacer()
            if let d = model.lastUpdate {
                Text(d, style: .relative)
                    .font(.system(size: 11))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 10)
    }

    // MARK: - 목록 (다운로드)

    private var jobList: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                if model.activeJobs.isEmpty && model.seedingJobs.isEmpty {
                    empty("진행 중인 작업이 없습니다", tip: model.phase == .failed ? "설정에서 주소를 확인하세요" : nil)
                }
                ForEach(model.activeJobs) { j in
                    JobRow(job: j) { act in model.control(j.id, act) }
                }
                ForEach(model.seedingJobs) { j in
                    JobRow(job: j) { act in model.control(j.id, act) }
                }
            }
            .padding(5)
        }
        .frame(maxHeight: 300)
    }

    // MARK: - 목록 (토렌트)

    private var torrentList: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                if model.torrents.isEmpty {
                    empty("토렌트 목록이 없습니다", tip: "웹에서 magnet 를 추가하세요")
                }
                ForEach(model.torrents) { t in
                    TorrentRow(
                        torrent: t,
                        onPause: { model.torrentControl(t.id, "pause") },
                        onResume: { model.torrentControl(t.id, "resume") },
                        onDelete: { model.torrentDelete(t.id) },
                        // **시드 정보는 목록에 없다.** `/api/torrents` 의 seeds/peers 는
                        // DB 저장값이고 "지금 몇 명 붙어있는지" 는 상세만 안다.
                        onDetail: {
                            model.currentTargetID = t.id
                            Task { await model.openDetail(t.id) }
                            sheet = .detail
                        },
                        onLimit: {
                            model.currentTargetID = t.id
                            sheet = .torrentLimit
                        }
                    )
                }
            }
            .padding(5)
        }
        .frame(maxHeight: 300)
    }

    // MARK: - 목록 (보관함)

    private var storageList: some View {
        ScrollView {
            LazyVStack(spacing: 1) {
                if model.storage.isEmpty {
                    empty("보관함이 비어 있습니다", tip: nil)
                }
                ForEach(model.storage, id: \.name) { e in
                    StorageRow(entry: e, onMove: { Task { await model.storageTrash(e.path) } })
                }
            }
            .padding(5)
        }
        .frame(maxHeight: 300)
    }

    private func empty(_ title: String, tip: String?) -> some View {
        VStack(spacing: 4) {
            Text(model.phase == .discovering && title.contains("서버") ? "서버를 찾는 중…" : title)
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
            if let tip {
                Text(tip).font(.system(size: 11))
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }

    // MARK: - 통계

    /// 탭마다 의미가 다르다 — 항상 "다운로드 진행/시딩"을 보여주면
    /// 토렌트·보관함 탭에서 엉뚱한 숫자가 떠 보인다.
    private var stats: some View {
        HStack(spacing: 0) {
            switch model.selectedTab {
            case .downloads:
                stat("진행", "\(model.activeJobs.count)")
                stat("시딩", "\(model.seedingJobs.count)")
                stat("여유", model.storageFree > 0 ? RelayClient.format(bytes: model.storageFree) : "—")
            case .torrents:
                stat("진행", "\(model.activeTorrents.count)")
                stat("전체", "\(model.torrents.count)")
                stat("완료", "\(model.torrents.filter { $0.isDone }.count)")
            case .storage:
                stat("항목", "\(model.storage.count)")
                stat("폴더", "\(model.storage.filter { $0.isDirectory }.count)")
                stat("여유", model.storageFree > 0 ? RelayClient.format(bytes: model.storageFree) : "—")
            }
        }
        .padding(.vertical, 8)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.system(size: 10.5)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 12, weight: .semibold)).monospacedDigit()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 하단

    private var footer: some View {
        VStack(spacing: 0) {
            // 서버 주소 + 탐색 결과 — "어디에 붙어 있는지"를 언제나 보이게
            HStack(spacing: 6) {
                Image(systemName: serverIcon)
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                Text(model.server?.displayAddress ?? "연결 안 됨")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(strategyText)
                    .font(.system(size: 10.5))
                    .foregroundStyle(model.phase.isConnected ? Color.green : Color.secondary)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 7)

            Divider().opacity(0.4)

            VStack(spacing: 0) {
                row("대시보드 열기", "arrow.up.forward.app", hot: true) { model.openDashboard() }
                row("설정…", "gearshape", hot: false) { onSettings() }
                row("DroidRelay 종료", "power", hot: false, quit: true) { onQuit() }
            }
            .padding(4)
        }
    }

    private var serverIcon: String {
        switch model.phase {
        case .connected: return "checkmark.circle.fill"
        case .discovering: return "arrow.triangle.2.circlepath"
        default: return "exclamationmark.circle"
        }
    }

    private var strategyText: String {
        switch model.phase {
        case .connected(let s): return s.rawValue == "manual" ? "수동" : "자동 발견 \(s.displayName)"
        case .discovering: return "탐색 중"
        case .failed: return "실패"
        case .idle: return "대기"
        }
    }

    private func row(_ title: String, _ icon: String, hot: Bool,
                     quit: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 11.5)).frame(width: 16)
                Text(title).font(.system(size: 12.5))
                Spacer()
                if hot {
                    Text("⌘⇧D").font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(quit ? Color.red : Color.primary)
        .padding(.horizontal, quit ? 0 : 3)
    }
}

extension DiscoveryStrategy { var isConnected: Bool { self != .manual } }

private extension AppModel.Phase {
    var isConnected: Bool { if case .connected = self { true } else { false } }
}

// MARK: - 잡 행

struct JobRow: View {
    let job: RelayClient.Job
    let onAction: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                Text(job.isVideo ? "🎬" : (job.isSeeding ? "🌊" : "📦"))
                    .font(.system(size: 11))
                    .frame(width: 14)
                Text(job.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 4)
                if job.uploadedBps > 0 {
                    Text("▲\(job.upText)").font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                } else {
                    Text(job.speedText).font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.cyan)
                }
            }

            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(Color.green).frame(width: g.size.width * CGFloat(job.progress) / 100)
                }
            }
            .frame(height: 4)

            HStack(spacing: 8) {
                Text(job.isSeeding ? "시딩" : "\(job.progress)%")
                    .foregroundStyle(.secondary)
                Spacer()
                if !job.etaText.isEmpty {
                    Text("남은 \(job.etaText)").foregroundStyle(.tertiary)
                }
            }
            .font(.system(size: 10.5))
            .monospacedDigit()

            HStack(spacing: 5) {
                if job.isSeeding {
                    small("시딩 중지", .orange) { onAction("cancel") }
                } else {
                    small("일시정지", .primary) { onAction("pause") }
                    small("취소", .red) { onAction("cancel") }
                }
            }
            .padding(.top, 1)
        }
        .padding(8)
    }

    private func small(_ t: String, _ c: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 10.5))
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(.quaternary, in: Capsule())
                .foregroundStyle(c)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 토렌트 행

struct TorrentRow: View {
    let torrent: Torrent
    let onPause: () -> Void
    let onResume: () -> Void
    let onDelete: () -> Void
    /// **시드 정보 열기** — 목록에 없는 값의 유일한 출처
    var onDetail: () -> Void = {}
    /// 속도 제한 시트
    var onLimit: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                Image(systemName: torrent.isDone ? "checkmark.circle.fill" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 10.5))
                    .foregroundStyle(torrent.isDone ? Color.green : Color.accentColor)
                Text(torrent.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                if torrent.uploadBps > 0 {
                    Text("▲\(torrent.upText)").font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                } else {
                    Text(torrent.speedText).font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.cyan)
                }
            }

            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(torrent.isDone ? Color.green : Color.accentColor)
                        .frame(width: g.size.width * CGFloat(torrent.progress))
                }
            }
            .frame(height: 4)

            HStack(spacing: 8) {
                Text(torrent.stateLabel).foregroundStyle(.secondary)
                Text(torrent.sizeText).foregroundStyle(.tertiary)
                Spacer()
                if torrent.seeds > 0 || torrent.peers > 0 {
                    Text("▲\(torrent.seeds) ▼\(torrent.peers)").foregroundStyle(.tertiary)
                }
            }
            .font(.system(size: 10.5))
            .monospacedDigit()
            .lineLimit(1)

            HStack(spacing: 5) {
                // **상세는 완료 여부와 무관하게 항상 보인다** — 완료된 토렌트도
                // "몇 명이 시드해 주는지" 가 궁금한 대상이다.
                small("시드 정보", .primary, onDetail)
                small("속도", .primary, onLimit)
                if torrent.isDone {
                    Text("완료 — 보관함으로 이동됨").font(.system(size: 10.5))
                        .foregroundStyle(.tertiary)
                } else {
                    small("일시정지", .primary, onPause)
                    small("재개", .primary, onResume)
                    small("삭제", .red, onDelete)
                }
            }
            .padding(.top, 1)
        }
        .padding(8)
    }

    private func small(_ t: String, _ c: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 10.5))
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(.quaternary, in: Capsule())
                .foregroundStyle(c)
        }
        .buttonStyle(.plain)
    }
}

struct StorageRow: View {
    let entry: StorageEntry
    /// 폴더로 이동 — **시트에서 대상/목적지를 입력**한다(서버가 판단한다)
    var onMove: () -> Void = {}
    /// 휴지통으로 보내기 — **즉시 지우지 않는다**
    var onTrash: () -> Void = {}
    /// Mac 으로 내려받기(공유 링크)
    var onShare: () -> Void = {}

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: entry.isDirectory ? "folder.fill" : "doc.fill")
                .font(.system(size: 10.5))
                .foregroundStyle(entry.isDirectory ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.name)
                    .font(.system(size: 12.5))
                    .lineLimit(1).truncationMode(.middle)
                HStack(spacing: 6) {
                    Text(entry.sizeText)
                    if !entry.modifiedText.isEmpty { Text(entry.modifiedText) }
                }
                .font(.system(size: 10.5))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
            // **액션은 호버 시에만** — 항상 보이면 4개 아이콘이 목록 전체를 덮는다.
            rowActions
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }

    private var rowActions: some View {
        HStack(spacing: 7) {
            Button(action: onMove) {
                Image(systemName: "folder").font(.system(size: 10.5))
            }.buttonStyle(.borderless).help("폴더로 이동")
            Button(action: onShare) {
                Image(systemName: "square.and.arrow.down").font(.system(size: 10.5))
            }.buttonStyle(.borderless).help("Mac 으로 내려받기")
            Button(action: onTrash) {
                Image(systemName: "trash").font(.system(size: 10.5))
            }.buttonStyle(.borderless).foregroundStyle(.orange).help("휴지통으로 보내기")
        }
    }
}
