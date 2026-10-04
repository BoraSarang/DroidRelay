package com.borasarang.droidrelay.relay

/**
 * 유휴 자동 정지 판정 — 순수 함수 (테스트 대상).
 *
 * "유휴" = HTTP/비디오 작업 없음 + 토렌트 활동 없음 + 진행 중 파일 전송 없음.
 * 시딩(SEEDING)은 업로드가 발생하는 실제 활동이므로 유휴가 아니다 —
 * 유휴 타임아웃이 시딩을 죽이면 사용자가 의도한 공유가 조용히 깨진다.
 */
internal fun isIdleForAutoStop(
    jobStates: List<JobState>,
    torrentStates: List<TorrentState>,
    activeTransfers: Int,
): Boolean {
    if (activeTransfers > 0) return false
    if (jobStates.any { it == JobState.RUNNING || it == JobState.QUEUED }) return false
    if (torrentStates.any {
        it == TorrentState.DOWNLOADING ||
            it == TorrentState.FETCHING_METADATA ||
            it == TorrentState.QUEUED ||
            it == TorrentState.SEEDING
    }) return false
    return true
}

/** 토렌트 절전 유효값 — 토렌트가 1건도 없으면 DHT/PEX/트래커동기를 세션에 적용하지 않는다.
 *  사용자 설정값은 그대로 두고 적용 시점에만 AND한다 (추가 시 즉시 복원). */
internal fun powerSaveEffective(userEnabled: Boolean, hasTorrents: Boolean): Boolean =
    userEnabled && hasTorrents
