import XCTest
@testable import DroidRelayClient

final class ModelsTests: XCTestCase {
    func testJobDecoding() throws {
        let json = """
        {"id":"a1","url":"https://x.com/f.zip","filename":"f.zip","state":"RUNNING",
         "progress":0.75,"downloadedBytes":750,"totalBytes":1000,"speedBps":52,"errorMessage":null}
        """
        let job = try JSONDecoder().decode(Job.self, from: Data(json.utf8))
        XCTAssertEqual(job.id, "a1")
        XCTAssertEqual(job.state, "RUNNING")
        XCTAssertTrue(job.isRunning)
        XCTAssertFalse(job.isDone)
    }

    func testTorrentWithFiles() throws {
        let json = """
        [{"id":"t1","name":"Ubuntu","state":"DOWNLOADING","progress":0.45,
          "downloadSpeed":100,"uploadSpeed":5,"totalSize":200,"downloadedSize":90,
          "seeds":3,"peers":7,
          "files":[{"index":0,"path":"iso/ubuntu.iso","size":180,"progress":0.5,"selected":true}]}]
        """
        let list = try JSONDecoder().decode([Torrent].self, from: Data(json.utf8))
        XCTAssertEqual(list.count, 1)
        XCTAssertEqual(list[0].files?.count, 1)
        XCTAssertEqual(list[0].files?[0].path, "iso/ubuntu.iso")
        XCTAssertTrue(list[0].isActiveState)
    }

    func testStorageItemsMixed() throws {
        let json = """
        [{"name":"MyFolder","type":"dir","size":0,"count":3},
         {"name":"file.zip","type":"file","size":104857600,"count":0}]
        """
        let list = try JSONDecoder().decode([StorageItem].self, from: Data(json.utf8))
        XCTAssertTrue(list[0].isDir)
        XCTAssertFalse(list[1].isDir)
        XCTAssertEqual(Fmt.bytes(list[1].size), "100.0 MB")
    }

    func testServerInfoNullables() throws {
        let json = """
        {"ip":null,"port":8080,"version":"0.4","storageFree":123,
         "storageTotal":640,"running":2,"speedTotalBps":0}
        """
        let info = try JSONDecoder().decode(ServerInfo.self, from: Data(json.utf8))
        XCTAssertNil(info.ip)
        XCTAssertEqual(info.port, 8080)
        XCTAssertEqual(info.version, "0.4")
    }

    func testFormatting() {
        XCTAssertEqual(Fmt.bytes(0), "0 B")
        XCTAssertEqual(Fmt.bytes(512), "512 B")
        XCTAssertEqual(Fmt.bytes(104857600), "100.0 MB")
        XCTAssertEqual(Fmt.speed(2097152), "2.0 MB/s")
        XCTAssertEqual(Fmt.pct(0.456), "46%")
        XCTAssertEqual(Fmt.joinPath("", "a.txt"), "a.txt")
        XCTAssertEqual(Fmt.joinPath("sub", "b.txt"), "sub/b.txt")
        XCTAssertEqual(Fmt.safeFilename("a/b c.txt"), "a_b c.txt")
    }
}
