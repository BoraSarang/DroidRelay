import Foundation

// MARK: - 쓰기 동작 (다운로드/토렌트 추가, 속도 제한, 보관함 조작)
//
// ## 왜 한 파일에 모았나
//
// 읽기(`jobs`/`torrents`/`storage`)는 상태를 가져오지만, 쓰기는 **모두 본문이 있고
// 실패 사유를 사람이 읽어야 한다.** 하나씩 흩어두면 UI 마다 오류 문구를 따로 만들어야 해서
// 서로 어긋난다. → `WriteResult` 하나로 통일한다.
//
// ## 서버는 이렇게 실패한다 (실측 기반)
// ```
// 422  E-AND-DOWN-1003: 유효한 http(s) URL이 아닙니다      → 본문 텍스트
// 409  {"error":"E-AND-DOWN-1006: …"}                        → JSON
// 404  없음
// ```
// **서버가 준 사유를 그대로 돌려준다.** 클라이언트가 다시 지어내면 사용자에게
// 진짜 원인이 가려진다.

extension RelayClient {

    // MARK: - 다운로드

    /// URL 로 다운로드를 추가한다.
    ///
    /// **서버가 `url` 만 받는다** — 파일명·저장 경로는 서버가 URL 에서 뽑는다.
    public func addDownload(url: String) async -> WriteResult {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard u.hasPrefix("http://") || u.hasPrefix("https://") else {
            // **서버에 보내지 않고 미리 막는다.** 서버가 422 + 긴 오류 문구를 돌려주는데
            // 빈 입력에서 그게 그대로 사용자에게 보인다.
            return .fail("http:// 또는 https:// 로 시작하는 주소를 입력하세요")
        }
        return await postJSON("api/jobs", ["url": u])
    }

    /// 작업별 다운로드 속도 제한. `bps == 0` 이면 무제한.
    ///
    /// **비디오 작업은 미지원** — 서버가 400 으로 거절한다. 그대로 전달한다.
    public func setJobLimit(id: String, maxDownBps: Int) async -> WriteResult {
        await postJSON("api/jobs/\(id)/limit", ["maxDownBps": maxDownBps])
    }

    // MARK: - 토렌트

    /// magnet 링크 또는 .torrent URL 로 토렌트를 추가한다.
    ///
    /// **세 가지 입력 형태를 서버가 받는다** — `magnet` / `torrentUrl` /
    /// `torrentFileBase64`. 클라이언트는 앞의 둘만 쓴다(파일 업로드는 웹 대시보드 영역).
    /// magnet 앞이 6자 이상이면 magnet 로 판정해 JSON 형태를 바꿔 보낸다.
    public func addTorrent(magnetOrURL input: String) async -> WriteResult {
        let s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return .fail("주소 또는 magnet 링크를 입력하세요") }
        if s.hasPrefix("magnet:") {
            return await postJSON("api/torrents/add", ["magnet": s])
        }
        if s.hasPrefix("http://") || s.hasPrefix("https://") {
            return await postJSON("api/torrents/add", ["torrentUrl": s])
        }
        // magnet 가 아니면서 URL 도 아니면 — magnet 를 붙여 한 번 더 시도한다.
        // 사용자가 scheme 을 까먹고 붙여넣는 경우가 많다(실사용 패턴).
        return await postJSON("api/torrents/add", ["magnet": "magnet:?xt=urn:btih:\(s)"])
    }

    /// 토렌트 상세 — **시드/피어 정보의 유일한 출처.**
    ///
    /// 목록(`/api/torrents`)에는 `seeds`/`peers` 라는 **DB 저장값**만 있다.
    /// 지금 몇 명이 붙어 있는지는 `GET /api/torrents/{id}` 만 안다.
    public func torrentDetail(id: String) async -> TorrentDetail? {
        guard let o = await getJSON("api/torrents/\(id)") else { return nil }
        return TorrentDetail(json: o)
    }

    /// 토렌트별 다운로드 속도 제한. `maxDownBps == 0` 이면 무제한.
    public func setTorrentLimit(id: String, maxDownBps: Int) async -> WriteResult {
        await postJSON("api/torrents/\(id)/limit", ["maxDownBps": maxDownBps])
    }

    /// 토렌트 안의 파일 선택(체크) — 순차 다운로드에 쓰인다.
    public func setFileSelection(id: String, selected: [Int]) async -> WriteResult {
        await postJSON("api/torrents/\(id)/select", ["selected": selected])
    }

    // MARK: - 보관함

    /// 항목 이동 — `from`(상대경로) 을 `to`(폴더명) 아래로.
    public func storageMove(from: String, to: String) async -> WriteResult {
        await postJSON("api/storage/move", ["from": from, "to": to])
    }

    /// 항목 이름 변경.
    public func storageRename(from: String, to: String) async -> WriteResult {
        await postJSON("api/storage/rename", ["from": from, "to": to])
    }

    /// **휴지통으로 보내기** — 즉시 지우지 않는다.
    ///
    /// `trash` 엔드포인트가 아니라 **`delete` 에 `trash: true`** 를 실어 보낸다
    /// (서버가 그렇게 받는다).
    public func storageTrash(path: String) async -> WriteResult {
        await postJSON("api/storage/delete", ["path": path, "trash": true])
    }

    /// 즉시 삭제 — 휴지통을 거치지 않는다.
    public func storageDelete(path: String) async -> WriteResult {
        await postJSON("api/storage/delete", ["path": path, "trash": false])
    }

    /// 하위 폴더 생성.
    public func storageMkdir(path: String, name: String) async -> WriteResult {
        await postJSON("api/storage/mkdir", ["path": path, "name": name])
    }

    /// 휴지통 목록.
    ///
    /// **배열 파싱 함수를 써야 한다** — `getJSON` 은 사전 전용이라
    /// 서버가 준 배열을 `nil` 로 삼킨다(→ 목록이 영영 비어 보인다). 실측 함정.
    public func storageTrashList() async -> [StorageTrashItem] {
        let arr = await getJSONArray("api/storage/trash")
        return arr.compactMap(StorageTrashItem.init(json:))
    }

    /// 휴지통에서 복원.
    public func storageRestore(name: String) async -> WriteResult {
        await postJSON("api/storage/trash/restore", ["name": name])
    }

    /// 휴지통에서 영구 삭제.
    public func storagePurge(name: String) async -> WriteResult {
        await postJSON("api/storage/trash/purge", ["name": name])
    }

    /// **보관함 항목을 Mac 으로 내려받기 위한 공유 링크**를 만든다.
    ///
    /// **파일을 직접 내려받는 엔드포인트가 없다.** 웹 대시보드도 `/api/share` 로 링크를
    /// 만들어 브라우저로 연다 — 서버가 파일을 직접 스트림하지 않는다. 따라서 Mac 에서도
    /// 같은 방식(링크를 브라우저로 열기)이 유일하게 일관된 경로다.
    public func storageShareLink(path: String, hours: Int = 24) async -> String? {
        guard let o = await getJSON("api/share", method: "POST",
                                    body: ["path": path, "hours": hours])
        else { return nil }
        return o["token"] as? String
    }
}

/// 휴지통 항목
public struct StorageTrashItem: Sendable, Equatable, Identifiable {
    public var name: String
    public var size: Int
    public var isDirectory: Bool
    public var deletedAt: String
    public var id: String { name }

    public init?(json o: [String: Any]) {
        guard let n = o["name"] as? String, !n.isEmpty else { return nil }
        self.name = n
        self.size = RelayClient.int(o["size"])
        self.isDirectory = (o["isDirectory"] as? NSNumber)?.boolValue
            ?? ((o["type"] as? String) == "dir")
        self.deletedAt = o["deletedAt"] as? String ?? ""
    }
}
