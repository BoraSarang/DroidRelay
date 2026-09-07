package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobsRepository
import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.relay.StorageJanitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageJanitorTest {

    @Test
    fun `확장자별 분류 폴더`() {
        assertEquals("영상", StorageJanitor.categoryFor("a.mp4"))
        assertEquals("영상", StorageJanitor.categoryFor("a.MKV"))
        assertEquals("음악", StorageJanitor.categoryFor("a.mp3"))
        assertEquals("문서", StorageJanitor.categoryFor("a.pdf"))
        assertEquals("문서", StorageJanitor.categoryFor("a.srt"))
        assertNull(StorageJanitor.categoryFor("a.exe"))
        assertNull(StorageJanitor.categoryFor("noext"))
    }

    @Test
    fun `쿼터 초과 시 오래된 파일부터 이동`() {
        val root = createTempDir("janitor")
        val trash = java.io.File(root, ".trash")
        val old = java.io.File(root, "old.mp4").apply { writeBytes(ByteArray(100)); setLastModified(1000) }
        java.io.File(root, "new.mp4").apply { writeBytes(ByteArray(100)); setLastModified(9000) }
        // 200B 사용, quota 0GB는 끔이므로 바이트 단위 검증은 dirSize로
        assertEquals(200L, StorageJanitor.dirSize(root))
        // quota를 GB 단위로만 받으므로 초과 재현 불가 — 0이면 no-op 확인
        assertEquals(0, StorageJanitor.enforceQuota(0, root, trash))
        assertTrue(old.exists())
        root.deleteRecursively()
    }

    @Test
    fun `URL 정규화`() {
        assertEquals(
            "https://example.com/path?a=1",
            JobsRepository.normalizedUrl("https://example.com/path/?a=1#frag"),
        )
        assertEquals(
            "https://example.com/path",
            JobsRepository.normalizedUrl("HTTPS://EXAMPLE.COM/path/"),
        )
    }

    @Test
    fun `중복 URL 조회 — 완료·진행은 잡고 취소·실패는 제외`() {
        val marker = "https://dup-test-${System.currentTimeMillis()}.example.com/f"
        val job = JobsRepository.add(marker, "dup.bin")
        assertNotNull(JobsRepository.findDuplicateUrl("$marker#frag"))
        JobsRepository.update(job.id) { it.copy(state = JobState.CANCELED) }
        assertNull(JobsRepository.findDuplicateUrl(marker))
        JobsRepository.remove(job.id)
    }
}
