import XCTest
@testable import DroidRelayCore

/// SSE 파서 — 서버(`/api/events`) 가 보내는 형식을 그대로 다뤄야 한다.
final class SSEParserTests: XCTestCase {

    private func feed(_ s: String) -> [String] {
        var p = SSEParser()
        return p.consume(Data(s.utf8))
    }

    func test_단일_이벤트() {
        XCTAssertEqual(feed("data: {\"type\":\"tick\"}\n\n"), ["{\"type\":\"tick\"}"])
    }

    /// keep-alive 주석(`:`) 은 무시해야 한다 — 무시 안 하면 tick 으로 오인한다.
    func test_주석은_무시한다() {
        XCTAssertEqual(feed(": ping\n\n"), [])
        XCTAssertEqual(feed(": ping\ndata: x\n\n"), ["x"])
    }

    /// `id:` / `retry:` / `event:` 필드는 버린다. `data:` 만 모은다.
    func test_비_data_필드는_버린다() {
        let out = feed("event: message\nid: 7\nretry: 3000\ndata: body\n\n")
        XCTAssertEqual(out, ["body"])
    }

    /// 데이터가 여러 줄이면 개행으로 이어붙인다 (SSE 규격).
    func test_다중_줄_데이터는_개행으로_연결된다() {
        XCTAssertEqual(feed("data: a\ndata: b\ndata: c\n\n"), ["a\nb\nc"])
    }

    /// \r\n 개행도 처리해야 한다. 서버/프록시 구현에 따라 온다.
    func test_CRLF_개행() {
        XCTAssertEqual(feed("data: x\r\n\r\n"), ["x"])
    }

    /// 청크가 줄 경계에서 끊겨 온다 — TCP 는 메시지 경계를 보존하지 않는다.
    func test_청크_나눠짐() {
        var p = SSEParser()
        XCTAssertEqual(p.consume(Data("data: hel".utf8)), [])
        XCTAssertEqual(p.consume(Data("lo\n".utf8)), [])
        XCTAssertEqual(p.consume(Data("\n".utf8)), ["hello"])
    }

    /// 스트림이 빈 줄 없이 끝나면 마지막 이벤트가 묻힌다 — flush 로 회수해야 한다.
    func test_flush로_미완성_이벤트를_회수한다() {
        var p = SSEParser()
        XCTAssertEqual(p.consume(Data("data: tail".utf8)), [])
        XCTAssertEqual(p.flush(), ["tail"])
        XCTAssertEqual(p.flush(), [])      // 두 번 회수는 비어 있어야 한다
    }

    func test_flush는_버퍼가_비면_빈_배열() {
        var p = SSEParser()
        _ = p.consume(Data("data: a\n\n".utf8))
        XCTAssertEqual(p.flush(), [])
    }

    /// **`URLSession.AsyncBytes.lines` 는 빈 줄을 버린다.** SSE 프레임은
    /// "data: 값" 다음에 빈 줄로 끝나므로 lines 로 읽으면 종료 신호를 잃어
    /// 이벤트가 영영 완성되지 않는다. curl 은 `data: tick` 가 오는데
    /// Swift 쪽은 0건이었다 — 진단 모드가 잡은 실제 버그.
    /// 그래서 바이트를 직접 먹어야 한다.
    func test_바이트_단위로_먹으면_빈줄이_프레임을_끝낸다() {
        var p = SSEParser()
        XCTAssertEqual(p.consumeByte(UInt8(ascii: "d")), [])
        for b in Array("ata: tick".utf8) { _ = p.consumeByte(b) }
        _ = p.consumeByte(0x0A)          // 첫 줄 끝 — 아직 이벤트 미완성
        XCTAssertEqual(p.consumeByte(0x0A), ["tick"])   // 빈 줄 → 완성
    }

    /// 실제 서버(`/api/events`) 가 보내는 바이트열 그대로
    func test_서버_실제_바이트열() {
        var p = SSEParser()
        var got: [String] = []
        for b in Array("data: tick\n\ndata: beat\n\n".utf8) { got += p.consumeByte(b) }
        XCTAssertEqual(got, ["tick", "beat"])
    }

    func test_value_앞뒤_공백은_제거된다() {
        XCTAssertEqual(feed("data:   spaced  \n\n"), ["spaced"])
    }
}

final class DiscoveryModelTests: XCTestCase {

    func test_포트_후보는_랜덤_포트를_고려한다() {
        // DroidRelay 은 포트를 바꿀 수 있다 — 3000 만 있으면 못 찾는다
        XCTAssertEqual(PortCandidate.all.first, 3000)
        XCTAssertGreaterThan(PortCandidate.all.count, 1)
        XCTAssertTrue(PortCandidate.all.contains(8443), "HTTPS 포트도 후보여야 한다")
    }

    func test_전략_표시명() {
        XCTAssertEqual(DiscoveryStrategy.cached.displayName, "저장된 주소")
        XCTAssertEqual(DiscoveryStrategy.gateway.displayName, "게이트웨이")
        XCTAssertEqual(DiscoveryStrategy.subnetScan.displayName, "네트워크 검색")
        XCTAssertEqual(DiscoveryStrategy.manual.displayName, "수동 입력")
    }

    func test_주소_조합() {
        let s = ServerInfo(host: "10.38.120.211", port: 3000, version: "0.42.0")
        XCTAssertEqual(s.displayAddress, "10.38.120.211:3000")
        XCTAssertEqual(s.baseURL.absoluteString, "http://10.38.120.211:3000")
    }
}
