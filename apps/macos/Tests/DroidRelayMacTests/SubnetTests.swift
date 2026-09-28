import XCTest
@testable import DroidRelayCore

/// 서브넷 계산 — `/24` 캡이 실제로 동작하는가.
///
/// 캡이 없으면 `/16` 에 붙은 Mac 이 6만 5천 개 IP 를 두드린다.
final class SubnetTests: XCTestCase {

    func test_slash24_에서_네트워크와_브로드캐스트를_제외한다() {
        let hosts = Subnet.scanHosts(ipv4: "10.38.120.193", netmask: "255.255.255.0")
        XCTAssertEqual(hosts.count, 254)
        XCTAssertFalse(hosts.contains("10.38.120.0"))
        XCTAssertFalse(hosts.contains("10.38.120.255"))
        XCTAssertTrue(hosts.contains("10.38.120.211"))   // 실측에서 폰(=게이트웨이)이었던 주소
        XCTAssertEqual(hosts.first, "10.38.120.1")
        XCTAssertEqual(hosts.last, "10.38.120.254")
    }

    /// 접두사가 클수록 서브넷이 작아진다 — `min` 으로 쓰면 /16 이 남아 65,534개가 된다.
    /// 이걸 캐치한 게 순수 로직을 라이브러리 타깃으로 뺀 이유다.
    func test_slash16은_로컬IP의_slash24로_캡된다() {
        let hosts = Subnet.scanHosts(ipv4: "10.38.120.193", netmask: "255.255.0.0")
        XCTAssertEqual(hosts.count, 254)
        XCTAssertEqual(hosts.first, "10.38.120.1")
        XCTAssertEqual(hosts.last, "10.38.120.254")
    }

    /// /16 에 붙어 있어도 로컬 IP 가 속한 /24 만 스캔한다.
    func test_로컬IP의_octet을_따른다() {
        let hosts = Subnet.scanHosts(ipv4: "192.168.7.1", netmask: "255.255.0.0")
        XCTAssertEqual(hosts.count, 254)
        XCTAssertEqual(hosts.first, "192.168.7.1")
        XCTAssertEqual(hosts.last, "192.168.7.254")
    }

    /// /25 는 /24 보다 좁으므로 그대로 좁게 유지된다.
    /// 192.168.7.130 이 속한 대역은 192.168.7.128/25 → .129~.254
    func test_더_좁은_마스크는_그대로_확대하지_않는다() {
        let hosts = Subnet.scanHosts(ipv4: "192.168.7.130", netmask: "255.255.255.128")
        XCTAssertEqual(hosts.count, 126)
        XCTAssertEqual(hosts.first, "192.168.7.129")
        XCTAssertEqual(hosts.last, "192.168.7.254")
    }

    func test_잘못된_입력은_빈_목록() {
        XCTAssertTrue(Subnet.scanHosts(ipv4: "nonsense", netmask: "255.255.255.0").isEmpty)
        XCTAssertTrue(Subnet.scanHosts(ipv4: "10.0.0.1", netmask: "999.0.0.0").isEmpty)
        XCTAssertTrue(Subnet.scanHosts(ipv4: "10.0.0", netmask: "255.255.255.0").isEmpty)
        XCTAssertTrue(Subnet.scanHosts(ipv4: "10.0.0.256", netmask: "255.255.255.0").isEmpty)
    }

    func test_32비트_마스크는_호스트_0개() {
        XCTAssertTrue(Subnet.scanHosts(ipv4: "10.0.0.1", netmask: "255.255.255.255").isEmpty)
    }
}

final class IPv4Tests: XCTestCase {

    func test_접두사_계산() {
        XCTAssertEqual(IPv4("255.255.255.0")!.prefix, 24)
        XCTAssertEqual(IPv4("255.255.0.0")!.prefix, 16)
        XCTAssertEqual(IPv4("255.0.0.0")!.prefix, 8)
        XCTAssertEqual(IPv4("0.0.0.0")!.prefix, 0)
        XCTAssertEqual(IPv4("255.255.255.252")!.prefix, 30)
    }

    func test_마스킹() {
        let ip = IPv4("10.38.120.193")!
        let m24 = IPv4("255.255.255.0")!
        XCTAssertEqual(ip.masked(by: m24).description, "10.38.120.0")
        XCTAssertEqual(ip.masked(by: IPv4("255.255.0.0")!).description, "10.38.0.0")
    }

    /// withPrefix 는 **기존 값과 무관한 새 마스크**를 만들어야 한다.
    /// `masked(by:)` 로 구현하면 255.255.0.0 & 255.255.255.0 = 255.255.0.0 이라
    /// 접두사가 줄지 않는다 — 실제로 이 버그로 /16 스캔(65,534개)이 그대로 남았다.
    func test_접두사로_마스크_만들기는_기존값을_무시한다() {
        XCTAssertEqual(IPv4("255.255.0.0")!.withPrefix(24).description, "255.255.255.0")
        XCTAssertEqual(IPv4("255.255.255.255")!.withPrefix(16).description, "255.255.0.0")
        XCTAssertEqual(IPv4("0.0.0.0")!.withPrefix(8).description, "255.0.0.0")
        XCTAssertEqual(IPv4("0.0.0.0")!.withPrefix(0).intValue, 0)
    }

    func test_정수_왕복() {
        let ip = IPv4("192.168.100.42")!
        XCTAssertEqual(IPv4(intValue: ip.intValue), ip)
    }

    func test_잘못된_문자열은_nil() {
        XCTAssertNil(IPv4("10.0.0"))
        XCTAssertNil(IPv4("10.0.0.0.0"))
        XCTAssertNil(IPv4("a.b.c.d"))
        XCTAssertNil(IPv4("10.0.0.300"))
        XCTAssertNil(IPv4(""))
    }
}
