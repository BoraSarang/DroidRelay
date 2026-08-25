import SwiftUI
import UniformTypeIdentifiers

/// 토렌트 탭 — magnet/.torrent 추가 + 목록 제어
struct TorrentsTab: View {
    @Environment(AppState.self) private var st
    @State private var magnetInput = ""
    @State private var showImporter = false

    static let torrentType: UTType = UTType(filenameExtension: "torrent") ?? .data

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                TextField("magnet:?xt=urn:btih:…", text: $magnetInput)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12))
                    .disabled(!st.isConnected)
                    .onSubmit { submitMagnet() }
                Button("추가", action: submitMagnet)
                    .disabled(!st.isConnected || !magnetInput.hasPrefix("magnet:"))
                Button {
                    showImporter = true
                } label: {
                    Image(systemName: "folder.badge.plus")
                }
                .help(".torrent 파일 열기")
                .disabled(!st.isConnected)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)

            if st.torrents.isEmpty {
                EmptyHint(icon: "arrow.triangle.branch",
                          text: st.isConnected ? "magnet 링크나 .torrent 파일을 추가해 주세요." : "서버에 연결하면 토렌트를 관리할 수 있어요.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(st.torrents) { t in
                            TorrentRow(torrent: t)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
                }
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [Self.torrentType],
                      allowsMultipleSelection: false) { result in
            if case let .success(urls) = result, let url = urls.first {
                Task { await st.addTorrentFile(at: url) }
            }
        }
    }

    private func submitMagnet() {
        let m = magnetInput
        guard m.hasPrefix("magnet:") else {
            st.showToast("magnet:?xt=… 로 시작하는 링크를 입력해 주세요.")
            return
        }
        magnetInput = ""
        Task { await st.addMagnet(m) }
    }
}

struct TorrentRow: View {
    @Environment(AppState.self) private var st
    let torrent: Torrent

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(torrent.name)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Text("\(Fmt.pct(torrent.progress))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(torrent.progress >= 1 ? Color.green : Color.blue)
            }

            ProgressView(value: torrent.progress >= 1 ? 1 : max(torrent.progress, 0.02))

            HStack(spacing: 10) {
                Label(Fmt.speed(torrent.downloadSpeed), systemImage: "arrow.down")
                    .foregroundStyle(.secondary)
                Label(Fmt.speed(torrent.uploadSpeed), systemImage: "arrow.up")
                    .foregroundStyle(.secondary)
                Text("S \(torrent.seeds) · P \(torrent.peers)")
                    .foregroundStyle(.secondary)
                Text(Fmt.bytes(torrent.totalSize))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(torrent.isActiveState ? "정지" : "재개") {
                    Task { await st.toggleTorrent(torrent) }
                }
                .buttonStyle(.link)
                .font(.caption)
                Button("삭제", role: .destructive) {
                    Task { await st.removeTorrent(torrent) }
                }
                .buttonStyle(.link)
                .font(.caption)
            }
            .font(.caption)

            if let files = torrent.files, files.count > 1 {
                Text("파일 \(files.count)개")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(9)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
    }
}
