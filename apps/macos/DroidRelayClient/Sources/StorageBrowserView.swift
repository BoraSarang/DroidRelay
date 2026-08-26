import SwiftUI
import UniformTypeIdentifiers

/// 서버 보관함 탐색 뷰 — 폴더 이동·업로드·드래그·삭제 (v0.7)
struct StorageBrowserView: View {
    @State private var path = ""
    @State private var items: [StorageItem] = []
    @State private var tick = false
    @State private var selected: Set<String> = []
    @State private var showNewFolder = false
    @State private var newFolderName = ""
    @State private var renameTarget: StorageItem?
    @State private var renameText = ""

    private var folders: [StorageItem] { items.filter { $0.type == "dir" } }
    private var files: [StorageItem] { items.filter { $0.type == "file" } }
    private var pathParts: [String] { path.isEmpty ? [] : path.split(separator: "/").map(String.init) }

    var body: some View {
        VStack(spacing: 0) {
            // ── 상단 툴바 ──
            HStack(spacing: 6) {
                Text("보관함")
                    .font(.callout)
                    .fontWeight(.semibold)

                Spacer()

                Button {
                    Task {
                        let panel = NSOpenPanel()
                        panel.canChooseFiles = true
                        panel.canChooseDirectories = false
                        panel.allowsMultipleSelection = true
                        panel.message = "서버로 보낼 파일을 선택하세요"
                        if panel.runModal() == .OK {
                            for url in panel.urls {
                                AppState.shared.uploadFile(url, serverPath: path)
                            }
                        }
                    }
                } label: {
                    Label("업로드", systemImage: "arrow.up.circle")
                        .font(.caption)
                }
                .controlSize(.small)

                Button { showNewFolder = true } label: {
                    Label("폴더", systemImage: "folder.badge.plus")
                        .font(.caption)
                }
                .controlSize(.small)

                if !selected.isEmpty {
                    Button { Task { await deleteSelected() } } label: {
                        Label("삭제", systemImage: "trash")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    .controlSize(.small)
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)

            // ── 브레드크럼 ──
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    crumbBtn("📱 보관함", active: path.isEmpty) { openDir("") }
                    ForEach(Array(pathParts.enumerated()), id: \.offset) { i, part in
                        Image(systemName: "chevron.right")
                            .font(.system(size: 8))
                            .foregroundStyle(.secondary)
                        let sub = pathParts[...i].joined(separator: "/")
                        crumbBtn(part, active: i == pathParts.count - 1) { openDir(sub) }
                    }
                }
                .font(.system(size: 12))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }

            Divider()

            // ── 파일/폴더 목록 ──
            ScrollView {
                VStack(spacing: 0) {
                    if folders.isEmpty && files.isEmpty {
                        Text("비어 있습니다")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .padding(.top, 32)
                    }
                    ForEach(folders) { item in folderRow(item) }
                    ForEach(files) { item in fileRow(item) }
                }
                .padding(10)
            }
        }
        .onAppear { Task { await refresh() } }
        .onReceive(Timer.publish(every: 2.0, on: .main, in: .common).autoconnect()) { _ in
            tick.toggle()
            Task { await refresh() }
        }
        .sheet(isPresented: $showNewFolder) { newFolderSheet }
        .sheet(item: $renameTarget) { item in renameSheet(item) }
    }

    // MARK: - 브레드크럼 버튼

    private func crumbBtn(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: active ? .semibold : .regular))
                .foregroundStyle(active ? .primary : Color.accentColor)
        }
        .buttonStyle(.plain)
    }

    // MARK: - 폴더 행

    private func folderRow(_ item: StorageItem) -> some View {
        let subPath = path.isEmpty ? item.name : "\(path)/\(item.name)"
        return HStack(spacing: 8) {
            Image(systemName: "folder.fill")
                .foregroundStyle(.orange)
                .font(.system(size: 14))
            Text(item.name)
                .font(.system(size: 13))
                .lineLimit(1)
            Spacer()
            Image(systemName: "arrow.right.circle")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(selected.contains(item.name) ? Color.accentColor.opacity(0.15) : Color.clear)
        )
        .onTapGesture { openDir(subPath) }
        .onLongPressGesture { toggleSelect(item.name) }
    }

    // MARK: - 파일 행

    private func fileRow(_ item: StorageItem) -> some View {
        HStack(spacing: 8) {
            Image(systemName: fileIcon(item.name))
                .foregroundStyle(Color.accentColor)
                .font(.system(size: 14))
            Text(item.name)
                .font(.system(size: 13))
                .lineLimit(1)
            Spacer()
            Text(Fmt.bytes(item.size))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
            Button {
                AppState.shared.receiveStorage(item: item, in: path)
            } label: {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .help("로컬로 다운로드")
            Button {
                renameText = item.name
                renameTarget = item
            } label: {
                Image(systemName: "pencil.circle")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(selected.contains(item.name) ? Color.accentColor.opacity(0.15) : Color.clear)
        )
        .onTapGesture { toggleSelect(item.name) }
    }

    // MARK: - 액션

    private func openDir(_ newPath: String) {
        path = newPath
        selected = []
        Task { await refresh() }
    }

    private func toggleSelect(_ name: String) {
        if selected.contains(name) { selected.remove(name) }
        else { selected.insert(name) }
    }

    private func refresh() async {
        items = await AppState.shared.storageList(path)
    }

    private func deleteSelected() async {
        for name in selected {
            let sub = path.isEmpty ? name : "\(path)/\(name)"
            await AppState.shared.storageDelete(at: sub)
        }
        selected = []
        await refresh()
    }

    private func fileIcon(_ name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "mp4","mkv","mov","avi": return "film"
        case "mp3","flac","ogg","m4a","wav": return "music.note"
        case "jpg","jpeg","png","gif","webp","heic": return "photo"
        case "pdf": return "doc.richtext"
        case "zip","tar","gz","7z","rar": return "archivebox"
        case "gguf","bin","exe","dmg","apk","iso": return "externaldrive"
        default: return "doc"
        }
    }

    // MARK: - 시트

    private var newFolderSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("폴더 만들기").font(.headline)
            TextField("폴더 이름", text: $newFolderName)
                .textFieldStyle(.roundedBorder)
                .onSubmit { createFolder() }
            HStack {
                Spacer()
                Button("취소") { showNewFolder = false }.keyboardShortcut(.cancelAction)
                Button("만들기") { createFolder() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(newFolderName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(16)
        .frame(width: 320)
    }

    private func createFolder() {
        let name = newFolderName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        showNewFolder = false
        newFolderName = ""
        let sub = path.isEmpty ? name : "\(path)/\(name)"
        Task {
            _ = await AppState.shared.storageOp("폴더 생성") { try await $0.storageMkdir(path: sub, name: name) }
            await refresh()
        }
    }

    private func renameSheet(_ item: StorageItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("이름 바꾸기").font(.headline)
            TextField("새 이름", text: $renameText)
                .textFieldStyle(.roundedBorder)
                .onSubmit { doRename(item) }
            HStack {
                Spacer()
                Button("취소") { renameTarget = nil }.keyboardShortcut(.cancelAction)
                Button("변경") { doRename(item) }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(16)
        .frame(width: 340)
    }

    private func doRename(_ item: StorageItem) {
        let newName = renameText.trimmingCharacters(in: .whitespaces)
        guard !newName.isEmpty, newName != item.name else { renameTarget = nil; return }
        let oldPath = path.isEmpty ? item.name : "\(path)/\(item.name)"
        renameTarget = nil
        Task {
            _ = await AppState.shared.storageOp("이름 바꾸기") { try await $0.storageRename(from: oldPath, to: newName) }
            await refresh()
        }
    }
}
