package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.SettingsConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 설정 값 상한 (Phase 3).
 *
 * 배경: 서버 측 상한이 없어 API 직접 호출로 임의 값이 저장되었다.
 * ThrottleInterceptor 토큰 버킷이 `limit*2` / `limit*elapsedMs` 를 계산하는데
 * 값이 Long 범위를 넘으면 음수로 포화되어 토큰이 음수로 고정되고
 * 그 뒤로는 take() 가 절대로 sleep 하지 않아 다운로드 제한이 조용히失效한다.
 */
class SettingsClampTest {

    @Test
    fun `BPS 상한 적용 — 토큰 버킷 오버플로 방지`() {
        assertEquals(0L, SettingsConstraints.clampBps(0L))
        assertEquals(1L, SettingsConstraints.clampBps(1L))
        assertEquals(3_145_728L, SettingsConstraints.clampBps(3_145_728L))
        // Long 최댓값 — 그대로면 limit*2 가 음수로 포화된다
        assertEquals(SettingsConstraints.MAX_BPS, SettingsConstraints.clampBps(Long.MAX_VALUE))
        assertEquals(SettingsConstraints.MAX_BPS, SettingsConstraints.clampBps(SettingsConstraints.MAX_BPS + 1))
    }

    @Test
    fun `BPS 음수는 0 - 무제한 또는 끔 - 으로 정규화`() {
        assertEquals(0L, SettingsConstraints.clampBps(-1L))
        assertEquals(0L, SettingsConstraints.clampBps(Long.MIN_VALUE))
    }

    @Test
    fun `상한 적용 후 두 배 연산이 음수로 넘어가지 않음`() {
        val clamped = SettingsConstraints.clampBps(Long.MAX_VALUE)
        // 토큰 버킷의 `tokens.coerceAtMost(limit * 2)` 경로
        assertTrue("limit*2 오버플로", clamped * 2 > 0)
    }

    @Test
    fun `토렌트 속도 상한은 문서화된 값과 일치`() {
        // 0 = 끔/무제한 이므로 하한 0 은 유지되어야 한다
        assertEquals(0, SettingsConstraints.TORRENT_UPLOAD_MIN)
        assertEquals(0, SettingsConstraints.TORRENT_DOWNLOAD_MIN)
        assertEquals(1024, SettingsConstraints.TORRENT_UPLOAD_MAX)
        assertEquals(20480, SettingsConstraints.TORRENT_DOWNLOAD_MAX)
    }

    @Test
    fun `시드 부재 대기 상한`() {
        assertEquals(0, SettingsConstraints.TORRENT_MIN_SEED_WAIT_MAX.coerceAtMost(0))
        assertTrue(SettingsConstraints.TORRENT_MIN_SEED_WAIT_MAX in 1..86400)
    }

    @Test
    fun `토렌트 최대 활성 범위는 라벨과 일치`() {
        assertEquals(1, SettingsConstraints.TORRENT_MAX_ACTIVE_MIN)
        assertEquals(10, SettingsConstraints.TORRENT_MAX_ACTIVE_MAX)
        assertTrue(
            SettingsConstraints.DEFAULT_TORRENT_MAX_ACTIVE in
                SettingsConstraints.TORRENT_MAX_ACTIVE_MIN..SettingsConstraints.TORRENT_MAX_ACTIVE_MAX,
        )
    }
}
