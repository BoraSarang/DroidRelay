import Foundation
import Observation
import UserNotifications

enum Notify {
    static func send(title: String, body: String, enabled: Bool) {
        guard enabled else { return }
        Task {
            let center = UNUserNotificationCenter.current()
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            try? await center.add(UNNotificationRequest(identifier: UUID().uuidString,
                                                        content: content, trigger: nil))
        }
    }
}

/// ⬇ 받기 / ⬆ 올리기 매니저 — /file/{id}·/dl-file/{name} 스트리밍 저장, .part 이어받기, 업로드
@Observable
final class TransferManager {
    enum Direction: String, Hashable { case download, upload }

    struct Active: Identifiable, Hashable {
        let id: String // jobId 또는 "dl:" + 경로 또는 "up:" + UUID
        var name: String
        var received: Int64 = 0
        var total: Int64 = 0
        var finished: Bool = false
        var failed: Bool = false
        var destPath: String = ""
        var direction: Direction = .download
    }

    var actives: [Active] = []

    func isActive(_ key: String) -> Bool {
        actives.contains { $0.id == key && !$0.finished && !$0.failed }
    }

    func progress(_ key: String) -> Active? {
        actives.first { $0.id == key }
    }

    /// 완료 항목 목록 정리
    func clearFinished() {
        actives.removeAll { $0.finished || $0.failed }
    }

    // MARK: - WKDownload 연동 (웹 대시보드 📥 클릭)

    @MainActor
    func beginWeb(id: String, name: String) {
        guard !isActive(id) else { return }
        let entry = Active(id: id, name: name)
        actives.append(entry)
        DebugLog.shared.i("Transfer", "웹 받기 시작: \(name)")
    }

    @MainActor
    func updateWeb(id: String, totalWritten: Int64, expected: Int64) {
        guard let idx = actives.firstIndex(where: { $0.id == id }) else { return }
        actives[idx].received = totalWritten
        if expected > 0 { actives[idx].total = expected }
    }

    @MainActor
    func finishWeb(id: String, ok: Bool, dest: String, name: String, notifyEnabled: Bool) {
        guard let idx = actives.firstIndex(where: { $0.id == id }) else { return }
        actives[idx].finished = true
        actives[idx].failed = !ok
        actives[idx].destPath = dest
        if ok {
            Notify.send(title: "받기 완료", body: name, enabled: notifyEnabled)
            DebugLog.shared.i("Transfer", "웹 받기 완료: \(name) → \(dest)")
        } else {
            DebugLog.shared.e("E-MAC-NET-1002", "웹 받기 실패: \(name)")
        }
    }

    // MARK: - 받기

    @MainActor
    func receive(job: Job, api: RelayAPI, folderURL: URL, notifyEnabled: Bool, auth: String?) {
        receive(url: api.fileURL(id: job.id),
                key: job.id,
                name: job.filename,
                totalHint: job.totalBytes,
                folderURL: folderURL,
                notifyEnabled: notifyEnabled,
                auth: auth)
    }

    @MainActor
    func receiveStorage(item: StorageItem, in path: String, api: RelayAPI,
                        folderURL: URL, notifyEnabled: Bool, auth: String?) {
        let rel = Fmt.joinPath(path, item.name)
        receive(url: api.dlFileURL(rel),
                key: "dl:" + rel,
                name: item.name,
                totalHint: item.size,
                folderURL: folderURL,
                notifyEnabled: notifyEnabled,
                auth: auth)
    }

    private func receive(url: URL, key: String, name: String, totalHint: Int64,
                         folderURL: URL, notifyEnabled: Bool, auth: String?) {
        guard !isActive(key) else { return }
        DebugLog.shared.i("Transfer", "받기 시작: \(name)")

        var entry = Active(id: key, name: name)
        entry.total = totalHint
        actives.append(entry)

        let safeName = Fmt.safeFilename(name)
        Task { [weak self] in
            var received: Int64 = 0
            do {
                let fm = FileManager.default
                try? folderURL.ensureDirectoryExists()
                let partial = folderURL.appendingPathComponent(safeName + ".part")
                let dest = folderURL.appendingPathComponent(safeName)

                if !fm.fileExists(atPath: partial.path) {
                    fm.createFile(atPath: partial.path, contents: nil)
                }
                if fm.fileExists(atPath: partial.path) {
                    received = (try? fm.attributesOfItem(atPath: partial.path)[.size] as? Int64) ?? 0
                } else {
                    throw APIError(code: "E-MAC-STOR-1003", message: "임시 파일 생성 실패")
                }

                var req = URLRequest(url: url)
                req.timeoutInterval = 20
                if let auth { req.setValue(auth, forHTTPHeaderField: "Authorization") }
                if received > 0 { req.setValue("bytes=\(received)-", forHTTPHeaderField: "Range") }

                let (stream, response) = try await URLSession.shared.bytes(for: req)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                DebugLog.shared.i("Transfer", "받기 응답 \(status): \(name) (이어받기 \(received)B)")

                if status == 416 {
                    // 이미 전부 받아둔 상태 → 완료 처리
                    if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
                    try fm.moveItem(at: partial, to: dest)
                    await self?.finish(key: key, ok: true, dest: dest.path, name: name, notifyEnabled: notifyEnabled)
                    return
                }
                guard status == 200 || status == 206 else {
                    throw APIError(code: status == 401 ? "E-MAC-AUTH-1004" : "E-MAC-NET-1002",
                                   message: "HTTP \(status)")
                }
                if status == 200 { received = 0 } // 이어받기 미지원 → 처음부터

                let handle = try FileHandle(forWritingTo: partial)
                defer { try? handle.close() }
                if status == 200 { try handle.truncate(atOffset: 0) } else { try handle.seekToEnd() }

                var buffer = Data()
                buffer.reserveCapacity(1 << 20)
                var lastUIUpdate = Date.distantPast

                for try await byte in stream {
                    buffer.append(byte)
                    if buffer.count >= 1 << 20 {
                        try handle.write(contentsOf: buffer)
                        received += Int64(buffer.count)
                        buffer.removeAll(keepingCapacity: true)
                        let now = Date()
                        if now.timeIntervalSince(lastUIUpdate) > 0.25 {
                            lastUIUpdate = now
                            await self?.updateProgress(key: key, received: received)
                        }
                    }
                }
                if !buffer.isEmpty {
                    try handle.write(contentsOf: buffer)
                    received += Int64(buffer.count)
                }
                try handle.close()

                if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
                try fm.moveItem(at: partial, to: dest)
                await self?.finish(key: key, ok: true, dest: dest.path, name: name, notifyEnabled: notifyEnabled)
            } catch {
                DebugLog.shared.e("E-MAC-STOR-1003", "받기 실패 \(name): \(error.localizedDescription)")
                await self?.finish(key: key, ok: false, dest: "", name: name, notifyEnabled: notifyEnabled)
            }
            _ = received
        }
    }

    @MainActor
    private func updateProgress(key: String, received: Int64) {
        guard let idx = actives.firstIndex(where: { $0.id == key }) else { return }
        actives[idx].received = received
        if actives[idx].total == 0 { actives[idx].total = max(received, 1) }
    }

    @MainActor
    private func finish(key: String, ok: Bool, dest: String, name: String, notifyEnabled: Bool) {
        guard let idx = actives.firstIndex(where: { $0.id == key }) else { return }
        actives[idx].finished = true
        actives[idx].failed = !ok
        actives[idx].destPath = dest
        if ok {
            Notify.send(title: "받기 완료", body: name, enabled: notifyEnabled)
            DebugLog.shared.i("Transfer", "받기 완료: \(name)")
        }
    }

    // MARK: - 업로드 (맥 → 안드로이드 서버)

    @MainActor
    func upload(fileURL: URL, api: RelayAPI, serverPath: String,
                notifyEnabled: Bool, auth: String?) {
        let name = fileURL.lastPathComponent
        let key = "up:" + UUID().uuidString.prefix(8).lowercased()
        guard !isActive(key) else { return }

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? Int64) ?? 0
        var entry = Active(id: key, name: name, direction: .upload)
        entry.total = fileSize
        actives.append(entry)
        DebugLog.shared.i("Transfer", "업로드 시작: \(name) (\(Fmt.bytes(fileSize)))")

        Task { [weak self] in
            do {
                let data = try Data(contentsOf: fileURL)
                guard let self else { return }
                try await api.storageUploadRaw(path: serverPath, name: name, data: data) { sent in
                    Task { @MainActor in
                        guard let idx = self.actives.firstIndex(where: { $0.id == key }) else { return }
                        self.actives[idx].received = sent
                    }
                }
                await self.finishUpload(key: key, ok: true, name: name, notifyEnabled: notifyEnabled)
            } catch {
                DebugLog.shared.e("E-MAC-NET-1002", "업로드 실패 \(name): \(error.localizedDescription)")
                self?.finishUpload(key: key, ok: false, name: name, notifyEnabled: notifyEnabled)
            }
        }
    }

    @MainActor
    private func finishUpload(key: String, ok: Bool, name: String, notifyEnabled: Bool) {
        guard let idx = actives.firstIndex(where: { $0.id == key }) else { return }
        actives[idx].finished = true
        actives[idx].failed = !ok
        if ok {
            Notify.send(title: "업로드 완료", body: name, enabled: notifyEnabled)
            DebugLog.shared.i("Transfer", "업로드 완료: \(name)")
        }
    }
}

extension URL {
    func ensureDirectoryExists() throws {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        if fm.fileExists(atPath: path, isDirectory: &isDir) {
            guard isDir.boolValue else {
                throw APIError(code: "E-MAC-STOR-1003", message: "폴더 자리에 파일이 있습니다: \(path)")
            }
            return
        }
        try fm.createDirectory(at: self, withIntermediateDirectories: true)
    }
}
