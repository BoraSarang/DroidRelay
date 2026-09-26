package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.TorrentFile
import com.borasarang.droidrelay.relay.TorrentJob
import com.borasarang.droidrelay.relay.TorrentState
import com.borasarang.droidrelay.relay.persistedSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 토렌트 영속화 저장 판정 (기기 검증으로 발견, 기존 T-845 가드 무효).
 *
 * 증상: 유휴 상태(토렌트 3건 전부 QUEUED)에서도 `저장 완료 3건` 로그가
 * 5~10초 간격으로 반복 → 바이트가 완전히 동일한 torrents.json 을 계속 다시 쓴다.
 *
 * 원인: `lastSavedSnapshot != TorrentRepository.all()` 은 TorrentJob(=data class) 전체를
 * 비교하므로, 파일에 기록되지 않는 라이브 필드(seeds·peers·downloadSpeed·uploadSpeed)가
 * 5초 폴링마다 흔들리면 "내용이 같다"는 가드가 영영 통과하지 못한다.
 *
 * 해결: TorrentPersistence.save 가 실제로 쓰는 필드만으로 서명을 만든다.
 */
class TorrentPersistSignatureTest {

    private fun job(
        id: String = "t1",
        state: TorrentState = TorrentState.QUEUED,
        seeds: Int = 0,
        peers: Int = 0,
        downloadSpeed: Long = 0L,
        uploadSpeed: Long = 0L,
        progress: Float = 0f,
        downloadedSize: Long = 0L,
        order: Int = 0,
        files: List<TorrentFile> = emptyList(),
    ) = TorrentJob(
        id = id,
        infoHash = "hash-$id",
        name = "name-$id",
        state = state,
        seeds = seeds,
        peers = peers,
        downloadSpeed = downloadSpeed,
        uploadSpeed = uploadSpeed,
        progress = progress,
        downloadedSize = downloadedSize,
        order = order,
        files = files,
    )

    @Test
    fun `라이브 필드만 변하면 서명은 동일 - 불필요한 저장 없음`() {
        val a = persistedSignature(listOf(job(seeds = 10, peers = 5, downloadSpeed = 999_999)))
        val b = persistedSignature(listOf(job(seeds = 0, peers = 0, downloadSpeed = 0)))
        assertEquals("저장 안 되는 필드 변화는 저장 판정에 영향이 없어야 함", a, b)
    }

    @Test
    fun `유휴 상태에서 서명 안정`() {
        val a = persistedSignature(listOf(job(id = "1"), job(id = "2"), job(id = "3")))
        val b = persistedSignature(listOf(job(id = "1"), job(id = "2"), job(id = "3")))
        assertEquals(a, b)
    }

    @Test
    fun `상태 전이는 감지`() {
        val a = persistedSignature(listOf(job(state = TorrentState.DOWNLOADING)))
        val b = persistedSignature(listOf(job(state = TorrentState.PAUSED)))
        assertNotEquals(a, b)
    }

    @Test
    fun `진행률·다운로드 크기 변화는 감지 - 이들은 실제로 저장된다`() {
        val a = persistedSignature(listOf(job(progress = 0.1f, downloadedSize = 1000)))
        val b = persistedSignature(listOf(job(progress = 0.2f, downloadedSize = 2000)))
        assertNotEquals(a, b)
    }

    @Test
    fun `순서 변화는 감지 - 대기열 순서가 영속된다`() {
        val a = persistedSignature(listOf(job(id = "1", order = 0), job(id = "2", order = 1)))
        val b = persistedSignature(listOf(job(id = "2", order = 0), job(id = "1", order = 1)))
        assertNotEquals(a, b)
    }

    @Test
    fun `파일 선택·진행 변화는 감지 - files 도 저장된다`() {
        val f0 = TorrentFile(0, "a.mkv", 100L, 0f, selected = false)
        val f1 = TorrentFile(0, "a.mkv", 100L, 0f, selected = true)
        val a = persistedSignature(listOf(job(files = listOf(f0))))
        val b = persistedSignature(listOf(job(files = listOf(f1))))
        assertNotEquals("파일 선택은 영속화 대상", a, b)
    }

    @Test
    fun `파일 개수 변화는 감지`() {
        val a = persistedSignature(listOf(job(files = listOf(TorrentFile(0, "a", 1L, 0f, false)))))
        val b = persistedSignature(
            listOf(
                job(
                    files = listOf(
                        TorrentFile(0, "a", 1L, 0f, false),
                        TorrentFile(1, "b", 1L, 0f, false),
                    ),
                ),
            ),
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `서로 다른 id 는 구분된다`() {
        assertNotEquals(
            persistedSignature(listOf(job(id = "a"))),
            persistedSignature(listOf(job(id = "b"))),
        )
    }

    @Test
    fun `필드 경계가 모호하지 않아야 한다 - 구분자가 필드값에 섞여도 충돌 없음`() {
        // id="a;b" 와 두 항목 (a, b) 이 같은 서명을 만들면 안 된다
        assertNotEquals(
            persistedSignature(listOf(job(id = "a;b"))),
            persistedSignature(listOf(job(id = "a"), job(id = "b"))),
        )
    }
}
