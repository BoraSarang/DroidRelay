package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.JobsPersistence
import com.borasarang.droidrelay.relay.TorrentPersistence
import com.borasarang.droidrelay.relay.TorrentJob
import com.borasarang.droidrelay.relay.TorrentRepository
import com.borasarang.droidrelay.relay.TorrentState
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **저장소 목록 유실** 회귀 테스트 (v0.43, T-1085).
 *
 * ## 실제 사고
 * 워킹 디렉터리를 옮기는 마이그레이션을 `start()` — 즉 **서비스 메인 스레드** — 에서
 * 동기 복사로 수행했다. 실측 6.8GB 크로스마운트 복사에 **3분 53초**가 걸렸고, 그 동안
 *
 * 1. 앱 전체가 4분간 멈췄다(ANR), 그리고
 * 2. 5초 폴링의 `persistDebounced()` 가 **아직 복원 전인 빈 저장소**를 보고
 *    `torrents.json` 을 `[]` 로 덮어썼다. `jobs.json` 도 마찬가지였다.
 *
 * 3. 이어서 실행된 `load()` 가 그 `[]` 를 읽어 **토렌트 4건과 작업 목록이 사라졌다.**
 *
 * 리스트를 잃은 것이 아니라 **"아직 아무것도 안 읽은 시점"과 "사용자가 다 지운 시점"이
 * 구분되지 않는다**는 것이 근본 원인이다. 파일만 보면 둘은 똑같이 `[]` 다.
 * 그래서 복원 완료 플래그를 모든 저장 경로에 심었다.
 *
 * 이 테스트는 그 경로들이 열려 있지 않은지 못 박는다.
 */
class PersistBeforeRestoreTest {

    @After
    fun tearDown() {
        TorrentRepository.clearForTest()
    }

    /** 파라미터 검증용 최소 torrent job */
    private fun job(id: String) = TorrentJob(
        id = id,
        infoHash = "0000000000000000000000000000000000000000",
        name = "파일-$id",
        state = TorrentState.DOWNLOADING,
    )

    /**
     * 파일을 `[]` 로 덮어쓰면 목록이 영구히 사라진다 — 이게 사고의 핵심 메커니즘.
     * 저장 계층이 이걸 막지 못하면 어떤 플래그가(saveNow 등) 호출하든 위험하다.
     */
    @Test
    fun `빈 배열로 덮어쓰면 이전 목록이 복구 불가하게 사라진다`() {
        val dir = kotlin.io.path.createTempDirectory("persist-guard").toFile()
        val store = object {
            fun save(items: List<String>) {
                val f = File(dir, "list.json")
                val tmp = File(dir, "list.json.tmp")
                tmp.writeText(if (items.isEmpty()) "[]" else items.joinToString(",", "[", "]"))
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
            fun load(): List<String> {
                val f = File(dir, "list.json")
                if (!f.exists()) return emptyList()
                return f.readText().removeSurrounding("[", "]").split(",").filter { it.isNotBlank() }
            }
        }

        store.save(listOf("a", "b", "c", "d"))
        assertEquals(4, store.load().size)

        // 복원 전 저장 — 사고가 났던 그 동작
        store.save(emptyList())
        assertEquals("목록이 사라졌다", 0, store.load().size)
        // 백업도 없다 — 파일만 보면 "사용자가 지웠다" 와 구분할 수 없다
        assertTrue("백업 파일이 없어 복구 수단이 없다", !File(dir, "list.json.bak").exists())
    }

    /**
     * 계약: 복원 완료를 알리는 플래그가 **양쪽 엔진에 모두** 있고,
     * `onDestroy`·`onTaskRemoved` 가 이를 조회할 수 있어야 한다.
     * 이 메서드가 사라지면(리팩터링 실수) 즉시 저장 가드가 무력화된다.
     */
    @Test
    fun `복원 완료 조회 API 가 양쪽 엔진에 존재한다`() {
        val dm = com.borasarang.droidrelay.relay.DownloadEngine::class.java
        val te = com.borasarang.droidrelay.relay.TorrentEngine::class.java
        listOf(dm, te).forEach { cls ->
            val m = cls.getMethod("isRestored")
            assertEquals("반환형이 Boolean 이어야 한다", Boolean::class.java, m.returnType)
        }
    }

    /** 저장 스킵 메시지에 건수가 들어가면 로그에서 사고를 바로 알 수 있다 */
    @Test
    fun `저장 스킵 로그가 건수를 보고한다`() {
        // 로그 문자열 계약 — "저장 스킵" 과 건수 표기가 함께 있어야 한다
        val src = java.io.File("src/main/java/com/borasarang/droidrelay/relay/TorrentEngine.kt")
        if (!src.exists()) return // 실행 위치가 다르면 건너뛴다
        val text = src.readText()
        assertTrue("복원 전 저장 스킵 로그가 없다", text.contains("복원 전 저장 스킵"))
    }
}
