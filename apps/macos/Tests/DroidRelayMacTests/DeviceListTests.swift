import XCTest
@testable import DroidRelayCore

final class DeviceListTests: XCTestCase {
    func test_전부모은다_2대() async {
        let d = ServerDiscovery()
        let hosts = ["10.0.0.2", "10.0.0.3", "10.0.0.4"]
        let found = await d.scanAll(hosts, ports: [3000], limit: 8) { url in
            guard url.absoluteString == "http://10.0.0.2:3000"
                || url.absoluteString == "http://10.0.0.4:3000" else { return nil }
            return ServerInfo(host: url.host() ?? "", port: 3000, version: "v")
        }
        let addrs = found.map(\.displayAddress).sorted()
        XCTAssertEqual(addrs, ["10.0.0.2:3000", "10.0.0.4:3000"])
    }

    func test_없으면_빈목록() async {
        let d = ServerDiscovery()
        let found = await d.scanAll(["10.0.0.2"], ports: [3000]) { _ in nil }
        XCTAssertTrue(found.isEmpty)
    }

    func test_빈입력은_빈목록() async {
        let d = ServerDiscovery()
        let a = await d.scanAll([], ports: [3000]) { _ in nil }
        let b = await d.scanAll(["10.0.0.2"], ports: []) { _ in nil }
        XCTAssertTrue(a.isEmpty && b.isEmpty)
    }

    func test_중복제거_정렬() {
        let r = ServerDiscovery.merged([
            .init(info: .init(host: "10.0.0.4", port: 3000, version: "a"), strategy: .subnetScan, elapsed: 0),
            .init(info: .init(host: "10.0.0.2", port: 3000, version: "b"), strategy: .gateway, elapsed: 0),
            .init(info: .init(host: "10.0.0.4", port: 3000, version: "a"), strategy: .cached, elapsed: 0),
        ])
        XCTAssertEqual(r.map(\.info.displayAddress), ["10.0.0.2:3000", "10.0.0.4:3000"])
        // **첫 등장 전략 유지** — 같은 주소가 세 경로에서 잡혀도 1줄이다
        XCTAssertEqual(r.first?.strategy, .gateway)
    }

    // MARK: - 기기 이름 파싱 (`/api/info` → `ServerInfo`)

    func test_서버이름_우선() {
        let i = RelayClient.info(from: ["port": 3000, "version": "0.50.1",
                                        "deviceName": "내 S22", "deviceModel": "SM-S901N"],
                                 base: URL(string: "http://10.0.0.2:3000")!)
        XCTAssertEqual(i?.deviceName, "내 S22")
    }

    func test_이름없으면_모델명() {
        let i = RelayClient.info(from: ["port": 3000, "version": "0.50.1",
                                        "deviceModel": "SM-A908N"],
                                 base: URL(string: "http://10.0.0.3:3000")!)
        XCTAssertEqual(i?.deviceName, "SM-A908N")
    }

    func test_둘다없으면_빈이름() {
        let i = RelayClient.info(from: ["port": 3000, "version": "0.50.1"],
                                 base: URL(string: "http://10.0.0.3:3000")!)
        XCTAssertEqual(i?.deviceName, "")
    }

    func test_포트버전없으면_nil() {
        XCTAssertNil(RelayClient.info(from: ["deviceName": "S22"],
                                      base: URL(string: "http://10.0.0.3:3000")!))
    }

    // MARK: - 별명 (`DeviceAliases`)

    func test_별명이_최우선() {
        let info = ServerInfo(host: "10.0.0.2", port: 3000, version: "v", deviceName: "서버이름")
        XCTAssertEqual(DeviceAliases(map: ["10.0.0.2:3000": "S22"]).name(for: info), "S22")
        XCTAssertEqual(DeviceAliases().name(for: info), "서버이름")
        XCTAssertEqual(DeviceAliases().name(for: ServerInfo(host: "h", port: 1, version: "v")), "")
    }

    func test_빈별명은_지우기다() {
        var a = DeviceAliases(map: ["x": "S22"])
        a.set("   ", for: "x")
        XCTAssertEqual(a.map["x"], nil)
        a.set("A90", for: "y")
        XCTAssertEqual(a.map["y"], "A90")
    }

    func test_별명_저장불러오기() {
        let d = UserDefaults(suiteName: "test.alias.\(UUID().uuidString)")!
        DeviceAliases(map: ["10.0.0.2:3000": "S22"]).save(d)
        XCTAssertEqual(DeviceAliases.load(d).map["10.0.0.2:3000"], "S22")
    }

    // MARK: - 헤더 한 줄 (`DiscoveryBadge.line` + 기기명)

    func test_기기명이_앞에붙는다() {
        let s = DiscoveryBadge.line(strategy: .gateway, address: "10.0.0.2:3000",
                                    version: "0.50.1", isManual: false, deviceName: "S22")
        XCTAssertTrue(s.hasPrefix("S22 · "), "이름이 주소보다 먼저 — 실제: \(s)")
        XCTAssertTrue(s.contains("10.0.0.2:3000"))
    }

    func test_빈기기명은_예전그대로() {
        let s = DiscoveryBadge.line(strategy: .gateway, address: "10.0.0.2:3000",
                                    version: "0.50.1", isManual: false, deviceName: "")
        XCTAssertTrue(s.hasPrefix("10.0.0.2:3000"), "이름 없으면 주소부터 — 실제: \(s)")
    }
}
