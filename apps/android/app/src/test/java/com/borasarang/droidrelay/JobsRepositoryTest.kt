package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobState
import com.borasarang.droidrelay.relay.JobsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsRepositoryTest {

    @Test
    fun `정상 파일명 URL은 그대로 유지된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://huggingface.co/Qwen/Model/resolve/main/model.gguf?download=true",
        )
        assertEquals("model.gguf", name)
    }

    @Test
    fun `쿼리스트링은 제거된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://example.com/path/photo.jpg?token=abc&dl=1",
        )
        assertEquals("photo.jpg", name)
    }

    @Test
    fun `범용 이름은 호스트와 시각으로 대체된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://speed.cloudflare.com/__down?bytes=10000000",
        )
        assertTrue("범용 폴백 형식이어야 함: $name", name.startsWith("file-speed.cloudflare.com-"))
    }

    @Test
    fun `확장자 없는 세그먼트도 대체된다`() {
        val name = JobsRepository.filenameFromUrl("https://example.org/files/abc123")
        assertTrue(name.startsWith("file-example.org-"))
    }

    @Test
    fun `URL디코딩이 적용된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://example.com/%EB%AA%A8%EB%8D%B8.qgguf",
        )
        assertEquals("모델.qgguf", name)
    }

    @Test
    fun `response-content-disposition 쿼리 파일명이 우선된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://release-assets.githubusercontent.com/github-production-release-asset/15634981/e898f508-0ae3-4c2c-b04a-40d1fc069418?sp=r&response-content-disposition=attachment%3B%20filename%3DGodot_v4.7.2-stable_export_templates.tpz&response-content-type=application%2Foctet-stream",
        )
        assertEquals("Godot_v4.7.2-stable_export_templates.tpz", name)
    }

    @Test
    fun `일반 filename 쿼리도 확장자 있을 때 우선된다`() {
        val name = JobsRepository.filenameFromUrl(
            "https://example.org/files/abc123?token=zzz&filename=report-2026.pdf",
        )
        assertEquals("report-2026.pdf", name)
    }

    @Test
    fun `폴백 이름은 헤더 파일명으로 교정 후보가 된다`() {
        val fixed = JobsRepository.correctedWithHeader(
            "file-ts.githubusercontent.com-0913-120000",
            "attachment; filename=\"Godot_v4.7.2-stable_export_templates.tpz\"",
        )
        assertEquals("Godot_v4.7.2-stable_export_templates.tpz", fixed)
    }

    @Test
    fun `정상 이름은 헤더가 있어도 교정하지 않는다`() {
        val fixed = JobsRepository.correctedWithHeader(
            "model.gguf",
            "attachment; filename=\"other.bin\"",
        )
        assertNull(fixed)
    }

    @Test
    fun `추가_조회_삭제 흐름이 동작한다`() {
        val before = JobsRepository.all().size
        val job = JobsRepository.add("https://test.local/a.bin", "a.bin")
        assertNotNull(JobsRepository.get(job.id))
        assertEquals(before + 1, JobsRepository.all().size)

        JobsRepository.remove(job.id)
        assertNull(JobsRepository.get(job.id))
        assertEquals(before, JobsRepository.all().size)
    }

    @Test
    fun `같은 파일명은 번호가 붙어 유니크해진다`() {
        val a = JobsRepository.add("https://t.local/x/dup.bin", "dup.bin")
        val b = JobsRepository.add("https://t.local/y/dup.bin", "dup.bin")
        assertNotEquals(a.filename, b.filename)
        assertTrue(b.filename.startsWith("dup-") && b.filename.endsWith(".bin"))
        JobsRepository.remove(a.id); JobsRepository.remove(b.id)
    }

    @Test
    fun `업데이트가 상태를 반영한다`() {
        val job = JobsRepository.add("https://t.local/z.bin", "z.bin")
        JobsRepository.update(job.id) { it.copy(state = JobState.RUNNING, progress = 0.5f) }
        assertEquals(JobState.RUNNING, JobsRepository.get(job.id)?.state)
        assertEquals(0.5f, JobsRepository.get(job.id)?.progress ?: -1f)
        JobsRepository.remove(job.id)
    }
}
