package com.borasarang.droidrelay.relay

import android.content.Context

/**
 * 신호 게이트 — "신호 좋을 때만" 다운로드 큐를 돌린다 (T-1102, 시골 LTE).
 *
 * 히스테리시스 필수 — 단일 임계면 signalDrop 70회/3일 환경에서 진입/해제가 발진한다.
 * - 정지(enter): RSRP ≤ -110 dBm 또는 SINR ≤ 0 dB (모뎀이 최대 출력으로 버티는 구간)
 * - 해제(clear): RSRP ≥ -100 dBm 그리고 SINR ≥ 3 dB (실측 회복폭 7~13dB라 도달 가능)
 * - 측정 불가(null): fail-open — 막지 않는다. 권한 없는 기기에서 큐가 영영 서면 안 된다.
 *
 * 판정식 [decide]는 순수 함수 (단위 테스트 대상). 상태(held)는 [SignalGateCache]가 유지한다.
 */
object SignalGate {
    const val HOLD_RSRP_DBM = -110
    const val HOLD_SINR_DB = 0
    const val RELEASE_RSRP_DBM = -100
    const val RELEASE_SINR_DB = 3

    /**
     * @param held 현재 홀드 상태 (true=큐 정지 중)
     * @return 다음 홀드 상태 (true=정지 유지·진입, false=실행 허용)
     */
    fun decide(rsrpDbm: Int?, sinrDb: Int?, held: Boolean): Boolean {
        if (rsrpDbm == null) return false
        if (!held) {
            if (rsrpDbm <= HOLD_RSRP_DBM) return true
            if (sinrDb != null && sinrDb <= HOLD_SINR_DB) return true
            return false
        }
        val rsrpOk = rsrpDbm >= RELEASE_RSRP_DBM
        val sinrOk = sinrDb == null || sinrDb >= RELEASE_SINR_DB
        return !(rsrpOk && sinrOk)
    }
}

/**
 * 신호 스냅샷 공유 캐시 (30초 TTL).
 * tryStart()는 enqueue·재시도마다 불리므로 매번 binder를 왕복하면 안 된다.
 * 홀드 상태도 여기서 유지해 진입/해제 히스테리시스가 이어진다.
 */
object SignalGateCache {
    private const val TTL_MS = 30_000L

    @Volatile private var cached: SignalInfo? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var held = false
    @Volatile var lastInfo: SignalInfo? = null
        private set

    /** true=큐 정지 (신호 나쁨). 측정 불가면 false. */
    fun shouldHold(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val info = cached?.takeIf { now - cachedAt < TTL_MS }
            ?: runCatching { SignalMonitor(context).snapshot() }.getOrDefault(SignalInfo()).also {
                cached = it
                cachedAt = now
            }
        lastInfo = info
        held = SignalGate.decide(info.rsrpDbm, info.sinrDb, held)
        return held
    }

    fun isHeld(): Boolean = held

    /** 테스트 격리용 */
    fun resetForTest() {
        cached = null
        cachedAt = 0L
        held = false
        lastInfo = null
    }
}
