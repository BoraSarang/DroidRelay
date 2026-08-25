import SwiftUI
import UniformTypeIdentifiers

/// 보관함 탭 — 서버 /sdcard/Download/DroidRelay 탐색·관리
struct StorageTab: View {
    @Environment(AppState.self) private var st
    @State private var path = ""
    @State private var items: [StorageItem] = []
    @State private var cutItem: (item: StorageItem, fromPath: String)?
    @State private var showNewFolderAlert = false
    @State private var newFolderName = ""
    @State private var showUploadImporter = false
    @State private var renameTarget: StorageItem?
    @State private var renameText = ""
    @State private var deleteTarget: StorageItem?
    @State private var loadedOnce = false

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            breadcrumb
                .padding(.horizontal, 12)
                .padding(.bottom, 6)

            if items.isEmpty {
                EmptyHint(icon: "folder",
                          text: st.isConnected ? "이 폴더는 비어 있습니다." : "서버에 연결하면 보관함을 볼 수 있어요.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(items) { item in
                            row(item)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.bottom, 10)
                }
            }
        }
        .disabled(!st.isConnected)
        .task(id: path) {
            items = await st.storageList(path)
        }
        .onChange(of: st.isConnected) { _, connected in
            if connected, !loadedOnce {
                loadedOnce = true
                Task { await reload() }
            }
        }
        .alert("새 폴더", isPresented: $showNewFolderAlert) {
            TextField("폴더 이름", text: $newFolderName)
            Button("만들기") { Task { await mkdir() } }
            Button("취소", role: .cancel) {}
        } message: {
            Text("현재 위치에 새 폴더를 만듭니다.")
        }
        .alert("이름 변경", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } })) {
            TextField("새 이름", text: $renameText)
            Button("변경") { Task { await rename() } }
            Button("취소", role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog("'\(deleteTarget?.name ?? "")'을(를) 삭제할까요?",
                            isPresented: Binding(
                                get: { deleteTarget != nil },
                                set: { if !$0 { deleteTarget = nil } }),
                            titleVisibility: .visible) {
            Button("삭제", role: .destructive) {
                guard let target = deleteTarget else { return }
                Task {
                    let rel = Fmt.joinPath(path, target.name)
                    if await st.storageOp("삭제", { try await $0.storageDelete(path: rel) }) != nil {
                        await reload()
                    }
                }
            }
            Button("취소", role: .cancel) {}
        }
        .fileImporter(isPresented: $showUploadImporter,
                      allowedContentTypes: [.data],
                      allowsMultipleSelection: false) { result in
            if case let .success(urls) = result, let url = urls.first {
                Task { await upload(url) }
            }
        }
    }

    // MARK: - 구성 요소

    private var toolbar: some View {
        HStack(spacing: 8) {
            Button {
                newFolderName = ""
                showNewFolderAlert = true
            } label: {
                Label("폴더", systemImage: "folder.badge.plus")
            }
            Button {
                showUploadImporter = true
            } label: {
                Label("올리기", systemImage: "square.and.arrow.up")
            }
            if let cut = cutItem {
                Button {
                    paste(from: cut)
                } label: {
                    Label("붙여넣기: \(cut.item.name)", systemImage: "arrow.down.doc")
                }
            }
            Spacer()
            Button {
                Task { await reload() }
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .help("새로고침")
        }
        .font(.system(size: 11))
        .buttonStyle(.borderless)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            Button("보관함") { path = "" }
                .buttonStyle(.link)
                .font(.system(size: 11))
            ForEach(Array(path.split(separator: "/").enumerated()), id: \.offset) { _, seg in
                Image(systemName: "chevron.right")
                    .font(.system(size: 8))
                    .foregroundStyle(.tertiary)
                Button(String(seg)) {
                    let segs = path.split(separator: "/").map(String.init)
                    if let idx = segs.firstIndex(of: String(seg)) {
                        path = segs[0 ... idx].joined(separator: "/")
                    }
                }
                .buttonStyle(.link)
                .font(.system(size: 11))
            }
            Spacer()
        }
    }

    private func row(_ item: StorageItem) -> some View {
        HStack(spacing: 8) {
            Image(systemName: item.isDir ? "folder.fill" : "doc")
                .foregroundStyle(item.isDir ? Color.blue : Color.secondary)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 1) {
                Text(item.name)
                    .font(.system(size: 12))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(item.isDir ? "폴더 · \(item.count)개 항목" : Fmt.bytes(item.size))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Menu {
                if !item.isDir {
                    Button("받기") { st.receiveStorage(item: item, in: path) }
                }
                Button("이름 변경") {
                    renameTarget = item
                    renameText = item.name
                }
                Button(cutItem?.item.id == item.id ? "잘라내기 취소" : "잘라내기") {
                    if cutItem?.item.id == item.id {
                        cutItem = nil
                    } else {
                        cutItem = (item, path)
                    }
                }
                Button("삭제", role: .destructive) { deleteTarget = item }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) {
            if item.isDir {
                path = Fmt.joinPath(path, item.name)
                cutItem = nil
            } else {
                st.receiveStorage(item: item, in: path)
            }
        }
        .background(Color.primary.opacity(0.03), in: RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - 동작

    private func reload() async {
        items = await st.storageList(path)
    }

    private func mkdir() async {
        let name = newFolderName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        if await st.storageOp("폴더 생성", { try await $0.storageMkdir(path: path, name: name) }) != nil {
            await reload()
        }
    }

    private func rename() async {
        guard let target = renameTarget else { return }
        renameTarget = nil
        let from = Fmt.joinPath(path, target.name)
        let to = Fmt.joinPath(path, renameText.trimmingCharacters(in: .whitespaces))
        if await st.storageOp("이름 변경", { try await $0.storageRename(from: from, to: to) }) != nil {
            await reload()
        }
    }

    private func paste(from item: (item: StorageItem, fromPath: String)) {
        let src = Fmt.joinPath(item.fromPath, item.item.name)
        let dest = Fmt.joinPath(path, item.item.name)
        guard src != dest else {
            st.showToast("같은 위치로는 이동할 수 없습니다.")
            cutItem = nil
            return
        }
        Task {
            if await st.storageOp("이동", { try await $0.storageMove(from: src, toDir: path) }) != nil {
                await reload()
            }
        }
    }

    private func upload(_ url: URL) async {
        do {
            guard url.startAccessingSecurityScopedResource() else { return }
            defer { url.stopAccessingSecurityScopedResource() }
            let data = try Data(contentsOf: url)
            if await st.storageOp("업로드",
                                  { try await $0.storageUpload(path: path, name: url.lastPathComponent, data: data) }) != nil {
                await reload()
            }
        } catch {
            st.showToast("파일을 읽을 수 없습니다: \(error.localizedDescription)")
        }
    }
}
