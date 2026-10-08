import XCTest
@testable import DroidRelayCore

final class ScanRegressionTests: XCTestCase {
    func test_뒤쪽호스트도_찾는다_211() async {
        let d = ServerDiscovery()
        let hosts = Subnet.scanHosts(ipv4: "10.38.120.193", netmask: "255.255.255.0")
        XCTAssertEqual(hosts.count, 254)
        let target = "http://10.38.120.211:3000"
        let found = await d.scan(hosts, ports: [3000], limit: 128) { url in
            guard url.absoluteString == target else { return nil }
            return ServerInfo(host: "10.38.120.211", port: 3000, version: "x")
        }
        XCTAssertEqual(found?.host, "10.38.120.211")
    }

    func test_없으면_전부훑는다() async {
        let d = ServerDiscovery()
        let hosts = (1...254).map { "10.0.0.\($0)" }
        let seen = LockedSet()
        let found = await d.scan(hosts, ports: [3000], limit: 32) { url -> ServerInfo? in
            await seen.add(url.absoluteString)
            return nil
        }
        XCTAssertNil(found)
        let n = await seen.count
        XCTAssertEqual(n, 254, "254개 전부 두드려야 한다, 실제 \(n)개")
    }
}

private actor LockedSet {
    private var s = Set<String>()
    func add(_ v: String) { s.insert(v) }
    var count: Int { s.count }
}
