import XCTest
@testable import DroidRelayCore

/// **Range 이어받기 다운로더** — 맥 앱 `받기` 가 브라우저 대신 직접 받게 되면서
/// 생긴 핵심 로직이다. 여기서 고정하지 않으면 **"32MB 로 완료"** 고장이 조용히
/// 되살아난다(그 고장은 화면상 "완료" 로 보여서 발견이 늦다).
///
/// ## 왜 가짜 서버를 끼우나
///
/// 진짜 서버(폰) 없이도 다음을 검증해야 한다:
/// 슬라이스 이어붙이기(바이트 일치) · 이어받기 시작점 · 요청과 다른 지점 거절 ·
/// `Range` 무시 서버에서 **자르지 않고 실패** · 416 · 재시도 · 취소 후 `.part` 보존.
final class RangeDownloadTests: XCTestCase {

    // MARK: - Content-Range

    func test_ContentRange_파싱() {
        let cr = ContentRange.parse("bytes 0-33554431/19343747520")
        XCTAssertEqual(cr?.start, 0)
        XCTAssertEqual(cr?.end, 33554431)
        XCTAssertEqual(cr?.total, 19343747520)

        // 전체 크기를 모르는 서버(`*`) — 그래도 구간은 읽는다.
        let star = ContentRange.parse("bytes 100-199/*")
        XCTAssertEqual(star?.start, 100)
        XCTAssertEqual(star?.end, 199)
        XCTAssertNil(star?.total)

        // 대문자 헤더도 받는다(프록시가 바꿔 보낼 수 있다).
        XCTAssertEqual(ContentRange.parse("BYTES 0-9/10")?.total, 10)
    }

    func test_ContentRange_형식이_어긋나면_nil() {
        XCTAssertNil(ContentRange.parse(nil))
        XCTAssertNil(ContentRange.parse(""))
        XCTAssertNil(ContentRange.parse("items 0-9/10"))
        XCTAssertNil(ContentRange.parse("bytes 0-9"))          // `/` 없음
        XCTAssertNil(ContentRange.parse("bytes 9-0/10"))       // 뒤집힘
        XCTAssertNil(ContentRange.parse("bytes a-b/10"))       // 숫자 아님
        XCTAssertNil(ContentRange.parse("bytes 0-9/abc"))      // 전체가 숫자 아님
    }

    // MARK: - 슬라이스 이어붙이기

    /// **핵심 테스트** — 조각을 이어 붙인 결과가 원본과 **바이트 단위로 같아야** 한다.
    func test_슬라이스를_이어붙이면_원본과_바이트가_같다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)      // 8바이트씩 13조각(마지막 4)
        let part = dir.appendingPathComponent("out.bin.part")

        let outcome = try await RangeDownloader.run(fetcher: server, partURL: part)

        XCTAssertEqual(outcome.bytes, 100)
        XCTAssertEqual(outcome.total, 100)
        XCTAssertEqual(try Data(contentsOf: part), server.body)
        XCTAssertEqual(server.requests, [0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96])
    }

    /// **이어받기** — 남아 있는 `.part` 의 크기가 곧 시작점이다(앱을 껐다 켜도).
    func test_남은_part_크기부터_이어받는다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        let part = dir.appendingPathComponent("out.bin.part")
        try server.body.prefix(30).write(to: part)           // 30바이트까지 받아 둔 상태

        let outcome = try await RangeDownloader.run(fetcher: server, partURL: part)

        XCTAssertEqual(server.requests.first, 30, "이미 받은 30바이트를 다시 받으면 안 된다")
        XCTAssertEqual(outcome.bytes, 100)
        XCTAssertEqual(try Data(contentsOf: part), server.body)
    }

    /// **요청한 지점과 다른 지점을 주면 거절한다.** 그냥 이어 붙이면 파일이 조용히 어긋난다.
    func test_요청한_지점과_다르면_실패한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.shiftStart = 5                                 // 0을 요청했는데 5라고 답한다
        let part = dir.appendingPathComponent("out.bin.part")

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part)
            XCTFail("어긋난 지점을 받아들였다")
        } catch let e as DownloadError {
            XCTAssertEqual(e, .rangeNotHonored(from: 0, got: ContentRange(start: 5, end: 7, total: 100)))
        }
    }

    /// **206 인데 `Content-Range` 가 없으면 멈춘다** — 이어받을 근거가 없다.
    func test_ContentRange_없는_206_은_실패한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.omitContentRange = true
        let part = dir.appendingPathComponent("out.bin.part")

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part)
            XCTFail("근거 없는 206 을 받아들였다")
        } catch let e as DownloadError {
            XCTAssertEqual(e, .missingContentRange(from: 0))
        }
    }

    /// **`Range` 를 무시하는 서버 = 원래 고장의 조건.**
    /// 32MB 만 저장해 놓고 "완료" 라 하지 않고 **실패를 말한다.**
    func test_Range_무시하는_서버는_자르지_않고_실패한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.ignoreRange = true
        server.truncateFullBody = true                        // 전체라고 해놓고 8바이트만 준다
        let part = dir.appendingPathComponent("out.bin.part")

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part)
            XCTFail("잘린 파일을 완료로 받아들였다 — 이게 원래 고장이다")
        } catch let e as DownloadError {
            XCTAssertEqual(e, .noRangeSupport)
        }
    }

    /// 작은 파일을 `Range` 없이 통째로 주는 서버는 **정상 처리**여야 한다.
    func test_한번에_전체를_주는_서버도_성공한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 20, cap: 8)
        server.ignoreRange = true                             // 200 + 전체 20바이트
        let part = dir.appendingPathComponent("out.bin.part")

        let outcome = try await RangeDownloader.run(fetcher: server, partURL: part)

        XCTAssertEqual(outcome.bytes, 20)
        XCTAssertEqual(outcome.total, 20)
        XCTAssertEqual(try Data(contentsOf: part), server.body)
    }

    /// 전체 크기를 알려주지 않는 서버에서도 **짧아진 조각**을 끝으로 본다.
    func test_전체_크기를_모르는_서버도_끝을_안다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.omitTotal = true                               // `bytes a-b/*`
        let part = dir.appendingPathComponent("out.bin.part")

        let outcome = try await RangeDownloader.run(fetcher: server, partURL: part)

        XCTAssertEqual(outcome.bytes, 100)
        XCTAssertNil(outcome.total)
        XCTAssertEqual(try Data(contentsOf: part), server.body)
    }

    // MARK: - 416

    func test_416_이고_이미_전체면_완료로_본다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        let part = dir.appendingPathComponent("out.bin.part")
        try server.body.write(to: part)                       // 이미 다 받아 둔 상태(재개 직후)

        let outcome = try await RangeDownloader.run(fetcher: server, partURL: part)

        XCTAssertEqual(outcome.bytes, 100)
        XCTAssertEqual(outcome.total, 100)
        XCTAssertEqual(try Data(contentsOf: part), server.body)
    }

    func test_416_인데_덜_받았으면_실패한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.totalOverride = 200                            // 서버가 보는 전체는 200
        let part = dir.appendingPathComponent("out.bin.part")
        try server.body.write(to: part)                       // 우리는 100만 가지고 있다

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part)
            XCTFail("덜 받았는데 완료로 봤다")
        } catch let e as DownloadError {
            XCTAssertEqual(e, .unsatisfiable(at: 100))
        }
    }

    // MARK: - 재시도 (실측 근거: 폰 Doze 로 전송이 멈췄다)

    func test_일시오류는_같은_지점에서_재시도한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 16, cap: 8)
        server.failAt = [8: 2]                                // 두 번째 조각에서 2번 실패
        let part = dir.appendingPathComponent("out.bin.part")
        let retries = Recorder<Int>()

        let outcome = try await RangeDownloader.run(
            fetcher: server,
            partURL: part,
            retryDelay: .zero,
            onRetry: { retries.add($0) }
        )

        XCTAssertEqual(outcome.bytes, 16)
        XCTAssertEqual(server.requests, [0, 8, 8, 8], "실패한 지점을 그대로 다시 요청해야 한다")
        XCTAssertEqual(retries.all, [1, 2])
        XCTAssertEqual(try Data(contentsOf: part), server.body)
    }

    func test_재시도_한도를_넘으면_실패한다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 16, cap: 8)
        server.failAt = [0: 99]                               // 계속 실패
        let part = dir.appendingPathComponent("out.bin.part")

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part, retryDelay: .zero)
            XCTFail("계속 실패하는데 성공했다")
        } catch {
            XCTAssertFalse(DownloadError.isCancellation(error))
        }
        XCTAssertEqual(server.requests.count, 4, "처음 1회 + 재시도 3회")
    }

    /// **취소하면 받은 만큼이 `.part` 에 남는다** — 다시 누르면 그 지점부터 이어받는다.
    func test_취소해도_받은_조각은_남는다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        server.cancelAt = [16]                                // 세 번째 조각에서 취소
        let part = dir.appendingPathComponent("out.bin.part")

        do {
            _ = try await RangeDownloader.run(fetcher: server, partURL: part, retryDelay: .zero)
            XCTFail("취소됐어야 한다")
        } catch {
            XCTAssertTrue(DownloadError.isCancellation(error), "취소는 실패가 아니다")
        }
        XCTAssertEqual(try Data(contentsOf: part), server.body.prefix(16),
                       "받아 둔 16바이트가 남아야 이어받기가 된다")
    }

    func test_취소_판정은_CancellationError_와_URLSession_취소를_모두_본다() {
        XCTAssertTrue(DownloadError.isCancellation(CancellationError()))
        XCTAssertTrue(DownloadError.isCancellation(URLError(.cancelled)))
        XCTAssertFalse(DownloadError.isCancellation(URLError(.timedOut)))
        XCTAssertFalse(DownloadError.isCancellation(DownloadError.noRangeSupport))
    }

    // MARK: - 진행률

    func test_진행률이_누적값으로_올라간다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        let part = dir.appendingPathComponent("out.bin.part")
        let seen = Recorder<(Int64, Int64?)>()

        _ = try await RangeDownloader.run(fetcher: server, partURL: part) { received, total in
            seen.add((received, total))
        }

        let values = seen.all
        XCTAssertEqual(values.first?.0, 0, "시작은 0부터 — 남은 .part 가 있으면 그 크기부터")
        XCTAssertEqual(values.last?.0, 100)
        XCTAssertEqual(values.last?.1, 100)
        XCTAssertEqual(values.map(\.0), values.map(\.0).sorted(), "진행률은 줄어들면 안 된다")
    }

    func test_진행률_문구() {
        XCTAssertEqual(DownloadProgress(received: 7_600_000_000, total: 18_000_000_000).percentText, "42%")
        XCTAssertEqual(DownloadProgress(received: 0, total: nil).percentText,
                       RelayClient.format(bytes: 0), "전체를 모르면 % 를 지어내지 않는다")
        XCTAssertEqual(DownloadProgress(received: 10, total: 0).fraction, nil)
        let done = DownloadProgress(received: 100, total: 100)
        XCTAssertEqual(done.percentText, "100%")
        XCTAssertTrue(done.isComplete)
        XCTAssertEqual(DownloadProgress(received: 150, total: 100).fraction, 1,
                       "초과는 100% 로 눌러 둔다 — 150% 는 화면에서 거짓 신호다")
        XCTAssertEqual(DownloadProgress(received: 150, total: 100).percentText, "100%")
    }

    // MARK: - 파일 하나 받기 (이름·.part·크기 검증)

    func test_받으면_part_가_사라지고_완성_이름이_남는다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)

        let file = try await RangeDownload.download(fetcher: server, into: dir, fileName: "model.gguf")

        XCTAssertEqual(file.lastPathComponent, "model.gguf")
        XCTAssertEqual(try Data(contentsOf: file), server.body)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path + ".part"))
    }

    func test_같은_이름이_있으면_덮어쓰지_않는다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 100, cap: 8)
        try Data("옛 파일".utf8).write(to: dir.appendingPathComponent("model.gguf"))

        let file = try await RangeDownload.download(fetcher: server, into: dir, fileName: "model.gguf")

        XCTAssertEqual(file.lastPathComponent, "model (1).gguf")
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("model.gguf")), Data("옛 파일".utf8),
                       "기존 파일은 손대지 않는다")
    }

    func test_이름에_경로가_있어도_저장_폴더를_벗어나지_않는다() async throws {
        let dir = try makeTempDir()
        let server = FakeSliceServer(size: 8, cap: 8)

        let file = try await RangeDownload.download(fetcher: server, into: dir, fileName: "../../escape.bin")

        XCTAssertEqual(file.deletingLastPathComponent().standardizedFileURL, dir.standardizedFileURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    func test_이름_정리() {
        XCTAssertEqual(DownloadNaming.sanitize("a/b:c.bin"), "a_b_c.bin")
        XCTAssertEqual(DownloadNaming.sanitize("  "), "download")
        XCTAssertEqual(DownloadNaming.sanitize(".."), "download")
        XCTAssertEqual(DownloadNaming.sanitize("정상 이름.gguf"), "정상 이름.gguf")
    }

    func test_이름_중복_회피는_Finder_관습을_따른다() {
        let taken: Set<String> = ["a.bin", "a (1).bin", "noext"]
        XCTAssertEqual(DownloadNaming.unique("a.bin") { taken.contains($0) }, "a (2).bin")
        XCTAssertEqual(DownloadNaming.unique("b.bin") { taken.contains($0) }, "b.bin")
        XCTAssertEqual(DownloadNaming.unique("noext") { taken.contains($0) }, "noext (1)")
    }

    /// 임시 폴더를 만들고 테스트가 끝나면 지운다.
    private func makeTempDir() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("droidrelay-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
}

// MARK: - 테스트용 가짜 서버

/// **진짜 파일 조각을 만들어 주는 가짜 서버.**
///
/// 서버 동작(`serveFile`)을 그대로 흉내 낸다 — `cap` 바이트씩 206 + `Content-Range`.
/// 실패 주입(`failAt`)·취소 주입(`cancelAt`)·고장 모드(`ignoreRange`)로
/// **정상 경로 밖**을 검증한다.
///
/// 테스트는 이 객체를 한 작업에서만 순차로 쓰므로 `@unchecked Sendable` 로 둔다.
final class FakeSliceServer: @unchecked Sendable, SliceFetching {
    let body: Data
    let cap: Int

    /// 요청된 offset 기록 — 재시도·이어받기 검증에 쓴다.
    var requests: [Int64] = []
    /// offset → 남은 실패 횟수.
    var failAt: [Int64: Int] = [:]
    /// 이 offset 요청에서 취소를 흉내낸다.
    var cancelAt: Set<Int64> = []
    /// `Content-Range` 의 start 를 속인다(요청과 다른 지점).
    var shiftStart = 0
    var omitContentRange = false
    /// `bytes a-b/*` — 전체 크기를 알려주지 않는다.
    var omitTotal = false
    /// `Range` 를 무시하고 200 으로 답한다.
    var ignoreRange = false
    /// 200 이면서 **일부만** 보낸다(고장난 서버).
    var truncateFullBody = false
    /// 서버가 말하는 전체 크기(기본은 실제 크기).
    var totalOverride: Int64?

    init(size: Int, cap: Int = 8) {
        self.body = Data((0..<size).map { UInt8($0 % 251) })
        self.cap = cap
    }

    func slice(from offset: Int64) async throws -> SliceResponse {
        requests.append(offset)
        if cancelAt.contains(offset) { throw CancellationError() }
        if let left = failAt[offset], left > 0 {
            failAt[offset] = left - 1
            throw URLError(.timedOut)
        }

        let realTotal = Int64(body.count)

        if ignoreRange {
            let sent: Data = truncateFullBody ? Data(body.prefix(cap)) : body
            return SliceResponse(statusCode: 200,
                                 contentRange: nil,
                                 contentLength: realTotal,
                                 bodyFile: try spill(sent),
                                 bodyLength: Int64(sent.count))
        }

        if offset >= realTotal {
            return SliceResponse(statusCode: 416,
                                 contentRange: ContentRange(start: 0, end: 0, total: totalOverride ?? realTotal),
                                 contentLength: 0,
                                 bodyFile: try spill(Data()),
                                 bodyLength: 0)
        }

        let start = Int(offset)
        let upper = min(body.count, start + cap)
        let chunk = body.subdata(in: start..<upper)
        let range: ContentRange? = omitContentRange ? nil : ContentRange(
            start: Int64(start + shiftStart),
            end: Int64(upper - 1),
            total: omitTotal ? nil : (totalOverride ?? realTotal)
        )
        return SliceResponse(statusCode: 206,
                             contentRange: range,
                             contentLength: Int64(chunk.count),
                             bodyFile: try spill(chunk),
                             bodyLength: Int64(chunk.count))
    }

    private func spill(_ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("fake-slice-\(UUID().uuidString)")
        try data.write(to: url)
        return url
    }
}

/// `@Sendable` 콜백에서 모은 값을 안전하게 꺼낸다.
final class Recorder<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [T] = []

    func add(_ value: T) {
        lock.lock()
        defer { lock.unlock() }
        items.append(value)
    }

    var all: [T] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}
