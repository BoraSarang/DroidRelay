import XCTest
@testable import DroidRelayClient

final class DiscoveryAndAPITests: XCTestCase {
    func testCandidatesFullSubnet() {
        let urls = ServerDiscovery.candidates(for: "192.168.0.42", port: 8080)
        XCTAssertEqual(urls.count, 254)
        XCTAssertEqual(urls[0].absoluteString, "http://192.168.0.1:8080")
        XCTAssertEqual(urls[253].absoluteString, "http://192.168.0.254:8080")
    }

    func testCandidatesInvalidInput() {
        XCTAssertTrue(ServerDiscovery.candidates(for: "localhost", port: 8080).isEmpty)
        XCTAssertTrue(ServerDiscovery.candidates(for: "999.1.2.3", port: 8080).isEmpty)
        XCTAssertTrue(ServerDiscovery.candidates(for: "10.0.0", port: 8080).isEmpty)
    }

    func testLocalAddressesExcludesLoopback() {
        let ips = ServerDiscovery.localAddresses()
        XCTAssertFalse(ips.contains("127.0.0.1"))
    }

    func testNormalizeAddress() {
        XCTAssertEqual(AppState.normalize(" 192.168.0.5:8080 "), "http://192.168.0.5:8080")
        XCTAssertEqual(AppState.normalize("http://192.168.0.5:8080/"), "http://192.168.0.5:8080")
        XCTAssertEqual(AppState.normalize("https://relay.example.com"), "https://relay.example.com")
        XCTAssertEqual(AppState.normalize(""), "")
    }

    func testAPIURLBuilders() throws {
        let base = try XCTUnwrap(URL(string: "http://192.168.0.5:8080"))

        let info = RelayAPI.url(base, "api/info")
        XCTAssertEqual(info.absoluteString, "http://192.168.0.5:8080/api/info")

        let jobs = RelayAPI.url(base, "api/jobs")
        XCTAssertEqual(jobs.absoluteString, "http://192.168.0.5:8080/api/jobs")

        let storageEmpty = RelayAPI.url(base, "api/storage")
        XCTAssertEqual(storageEmpty.absoluteString, "http://192.168.0.5:8080/api/storage")

        let storagePath = RelayAPI.url(base, "api/storage", query: ["path": "sub dir"])
        XCTAssertEqual(storagePath.absoluteString, "http://192.168.0.5:8080/api/storage?path=sub%20dir")

        let api = RelayAPI(baseURL: base)
        let dl = api.dlFileURL("folder/file.zip")
        XCTAssertEqual(dl.absoluteString, "http://192.168.0.5:8080/dl-file/folder/file.zip")

        let file = api.fileURL(id: "abc-123")
        XCTAssertEqual(file.absoluteString, "http://192.168.0.5:8080/file/abc-123")
    }
}
