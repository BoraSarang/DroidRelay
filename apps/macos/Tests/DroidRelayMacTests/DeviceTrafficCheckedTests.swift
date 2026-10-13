import XCTest
@testable import DroidRelayCore

/// **`deviceTrafficChecked` 의 실패 구분** — 폴백이 의존하는 계약.
///
/// `sampleDeviceTraffic` 은 미지원일 때만 전체(`.all`)로 폴백한다.
/// 네트워크 장애까지 폴백하면 매초 2건씩 쏘게 되므로, 둘을 구분해야 한다.
/// 이 테스트가 그 구분을 고정한다.
final class DeviceTrafficCheckedTests: XCTestCase {

    /// 가짜 응답을 돌려주는 URLProtocol 스텁.
    final class StubState: @unchecked Sendable {
        private let lock = NSLock()
        private var _handler: ((URLRequest) -> (Data, Int))?
        var handler: ((URLRequest) -> (Data, Int))? {
            get { lock.lock(); defer { lock.unlock() }; return _handler }
            set { lock.lock(); defer { lock.unlock() }; _handler = newValue }
        }
    }
    final class StubURLProtocol: URLProtocol {
        static let state = StubState()
        static var handler: ((URLRequest) -> (Data, Int))? {
            get { state.handler }
            set { state.handler = newValue }
        }
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let (data, status) = Self.handler?(request)
                ?? (Data(), 500)
            let resp = HTTPURLResponse(
                url: request.url!, statusCode: status,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    private func client() -> RelayClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return RelayClient(
            base: URL(string: "http://127.0.0.1:3000")!,
            session: URLSession(configuration: config)
        )
    }

    private func json(_ s: String) -> Data { Data(s.utf8) }

    // MARK: - 미지원은 사유를 던진다 (폴백 트리거)

    /// `supported:false` 면 `TrafficScopeError.unsupported` + 서버 note.
    func test_미지원이면_사유를_던진다() async {
        StubURLProtocol.handler = { _ in
            (self.json(#"{"rxTotal":0,"txTotal":0,"supported":false,"scope":"external","note":"셀룰러 없음"}"#), 200)
        }
        do {
            _ = try await client().deviceTrafficChecked(scope: .external)
            XCTFail("미지원이면 throw 해야 한다")
        } catch let e as RelayClient.TrafficScopeError {
            XCTAssertEqual(e.errorDescription, "기기 트래픽 미지원 — 셀룰러 없음")
        } catch {
            XCTFail("TrafficScopeError 여야 한다: \(error)")
        }
    }

    /// 관대한 버전은 미지원을 `nil` 로 (화면은 열을 만들지 않는다).
    func test_관대버전은_미지원을_nil로() async {
        StubURLProtocol.handler = { _ in
            (self.json(#"{"rxTotal":0,"txTotal":0,"supported":false,"scope":"external"}"#), 200)
        }
        let got = await client().deviceTraffic(scope: .external)
        XCTAssertNil(got)
    }

    // MARK: - 정상은 값을 준다 (폴백 불필요)

    func test_지원이면_카운터와_범위를_준다() async throws {
        StubURLProtocol.handler = { _ in
            (self.json(#"{"rxTotal":100,"txTotal":200,"supported":true,"scope":"all"}"#), 200)
        }
        let got = try await client().deviceTrafficChecked(scope: .all)
        XCTAssertEqual(got.rxTotal, 100)
        XCTAssertEqual(got.txTotal, 200)
        XCTAssertEqual(got.scope, .all)
    }

    // MARK: - 네트워크 장애는 미지원이 아니다 (폴백 금지)

    /// 연결 자체가 안 되면 `cannotConnectToHost` — `TrafficScopeError` 가 아니다.
    func test_연결실패는_미지원_에러가_아니다() async {
        StubURLProtocol.handler = { _ in (Data(), 500) }
        do {
            _ = try await client().deviceTrafficChecked(scope: .external)
            XCTFail("파싱 실패면 throw 해야 한다")
        } catch is RelayClient.TrafficScopeError {
            XCTFail("연결 실패를 미지원으로 보면 매초 폴백 요청이 나간다")
        } catch {
            // 기대 경로 — cannotConnectToHost
        }
    }
}
