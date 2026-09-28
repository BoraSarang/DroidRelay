import SwiftUI
import DroidRelayCore

/// 쓰기 동작 UI — 추가 / 속도 제한 / 상세 / 보관함 조작.
///
/// ## 왜 한 파일에 다 모았나
///
/// 사용자가 손댈 수 있는 동작은 **여섯 갈래**인데(다운로드 추가·속도, 토렌트 추가·속도·상세,
/// 보관함 이동/휴지통/복원/삭제) 각각 시트를 따로 만들면 UI 가 산으로 번진다.
/// **모두 "무엇을 입력/선택했는가 → 무엇을 실행했는가"** 라는 같은 모양이므로
/// 입력 폼 하나 + 결과 배너 하나로 통일한다.
///
/// ## 결과 배너는 항상 보인다
///
/// 실패 사유가 서버에서 온다(`E-AND-DOWN-1006: 이미 등록된 다운로드입니다`).
/// **토스트로 흘려버리면 읽을 시간이 없다** — 특히 "이미 있다" 는 사용자가
/// 다음 행동을 정하는 데 필요한 정보다. 실패는 dismiss 하지 않는다.
struct WriteSheets: View {
    @Bindable var model: AppModel
    @Binding var sheet: Sheet?

    enum Sheet: String, Identifiable {
        case addDownload, addTorrent, jobLimit, torrentLimit, detail, storageAction, trash, mkdir
        var id: String { rawValue }
    }

    // 입력값
    @State private var url = ""
    @State private var magnet = ""
    @State private var bps = ""
    @State private var target: String = ""       // 이동/삭제 대상
    @State private var folder = ""              // 이동 목적지
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 14, weight: .bold))
            Text(hint).font(.system(size: 11)).foregroundStyle(.secondary)
            fields
            Spacer(minLength: 0)
            if !model.lastResult.ok {
                banner(model.lastResult.message)
            }
            HStack {
                Spacer()
                Button("취소") { sheet = nil }
                Button("실행") { run(); sheet = nil }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canRun || model.busy)
            }
        }
        .padding(20)
        .frame(width: 420, height: 260)
    }

    private var title: String {
        switch sheet {
        case .addDownload: "다운로드 추가"
        case .addTorrent: "토렌트 추가"
        case .jobLimit: "다운로드 속도 제한"
        case .torrentLimit: "토렌트 속도 제한"
        case .detail: "토렌트 상세"
        case .storageAction: "보관함"
        case .trash: "휴지통"
        case .mkdir: "폴더 만들기"
        case nil: ""
        }
    }

    private var hint: String {
        switch sheet {
        case .addDownload: "http:// 또는 https:// 로 시작하는 주소"
        case .addTorrent: "magnet: 링크, .torrent URL, 또는 infohash"
        case .jobLimit, .torrentLimit: "0 은 무제한. 예: 1048576 = 1 MB/s"
        case .storageAction: "항목을 폴더로 옮기거나 휴지통으로 보냅니다"
        case .trash: "복원하거나 영구 삭제합니다"
        case .mkdir: "새 폴더 이름"
        case .detail: ""
        case nil: ""
        }
    }

    @ViewBuilder
    private var fields: some View {
        switch sheet {
        case .addDownload:
            field("주소", $url)
        case .addTorrent:
            field("magnet 또는 URL", $magnet)
        case .jobLimit, .torrentLimit:
            field("초당 바이트 (0 = 무제한)", $bps)
        case .storageAction:
            field("대상 항목", $target)
            field("이동할 폴더 이름", $folder)
        case .mkdir:
            field("폴더 이름", $newName)
        case .detail:
            if let d = model.detail { TorrentDetailView(d: d) }
        case .trash:
            TrashListView(model: model)
        case nil:
            EmptyView()
        }
    }

    private func field(_ label: String, _ text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
            TextField("", text: text).textFieldStyle(.roundedBorder)
        }
    }

    private func banner(_ msg: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(msg)
                .font(.system(size: 11))
                .textSelection(.enabled)      // 오류 문구를 복사할 수 있어야 함
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 5))
    }

    private var canRun: Bool {
        switch sheet {
        case .addDownload: !url.trimmingCharacters(in: .whitespaces).isEmpty
        case .addTorrent: !magnet.trimmingCharacters(in: .whitespaces).isEmpty
        case .jobLimit, .torrentLimit: Int(bps) != nil
        case .storageAction: !target.isEmpty && !folder.isEmpty
        case .mkdir: !newName.trimmingCharacters(in: .whitespaces).isEmpty
        case .detail, .trash, nil: false
        }
    }

    private func run() {
        let t = model.currentTargetID
        switch sheet {
        case .addDownload: Task { await model.addDownload(url) }
        case .addTorrent: Task { await model.addTorrent(magnet) }
        case .jobLimit: Task { await model.setJobLimit(t, bps: Int(bps) ?? 0) }
        case .torrentLimit: Task { await model.setTorrentLimit(t, bps: Int(bps) ?? 0) }
        case .storageAction: Task { await model.storageMove(target, to: folder) }
        case .mkdir: Task { await model.storageMkdir("", newName) }
        case .detail, .trash, nil: break
        }
    }
}

/// 토렌트 상세 — **시드/피어 정보**.
struct TorrentDetailView: View {
    let d: TorrentDetail

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            stat("상태", d.state)
            stat("진행", "\(Int(d.progress * 100))%  (\\(RelayClient.format(bytes: d.downloadedSize)) / \\(RelayClient.format(bytes: d.totalSize)))")
            // **두 수를 구분해 보여준다** — 다르면 트래커가 뒤처진 것이다.
            stat("스웜", "시드 \\(d.numComplete) / 완전본 \\(d.numIncomplete)   (트래커 보고: \\(d.seeds))")
            stat("지금 연결", "시드 \\(d.connectedSeeders) · 리처 \\(d.connectedLeechers) · 연결 \\(d.numConnections)")
            stat("트래커", d.currentTracker.isEmpty ? "—" : d.currentTracker)
            stat("피스", "\\(d.numPieces)")
            if !d.errorMessage.isEmpty {
                stat("오류", d.errorMessage)
            }
            if !d.connectedPeers.isEmpty {
                Divider().padding(.vertical, 2)
                Text("피어 \\(d.connectedPeers.count)명").font(.system(size: 11, weight: .semibold))
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(d.connectedPeers.prefix(20)) { p in
                            HStack {
                                Image(systemName: p.isSeeder ? "arrow.up.circle.fill" : "circle")
                                    .font(.system(size: 9))
                                    .foregroundStyle(p.isSeeder ? .green : .secondary)
                                Text(p.address).font(.system(size: 10, design: .monospaced))
                                Text("\\(Int(p.progress * 100))%")
                                    .font(.system(size: 10, design: .monospaced))
                                Spacer()
                                Text("↓\\(SpeedFormat.compact(p.downSpeed))")
                                    .font(.system(size: 10, design: .monospaced))
                            }
                        }
                    }
                }
                .frame(height: 90)
            }
        }
    }

    private func stat(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(k).font(.system(size: 11)).foregroundStyle(.secondary)
                .frame(width: 62, alignment: .leading)
            Text(v).font(.system(size: 11))
            Spacer(minLength: 0)
        }
    }
}

/// 휴지통 — 복원 / 영구 삭제
struct TrashListView: View {
    @Bindable var model: AppModel

    var body: some View {
        if model.trash.isEmpty {
            Text("휴지통이 비어 있습니다").font(.system(size: 11)).foregroundStyle(.secondary)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(model.trash) { t in
                        HStack {
                            Image(systemName: t.isDirectory ? "folder" : "doc")
                                .font(.system(size: 10))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(t.name).font(.system(size: 11))
                                Text(RelayClient.format(bytes: t.size))
                                    .font(.system(size: 9.5)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("복원") { Task { await model.storageRestore(t.name) } }
                                .buttonStyle(.link).font(.system(size: 10))
                            Button("영구 삭제") { Task { await model.storagePurge(t.name) } }
                                .buttonStyle(.link).font(.system(size: 10))
                                .foregroundStyle(.red)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
    }
}
