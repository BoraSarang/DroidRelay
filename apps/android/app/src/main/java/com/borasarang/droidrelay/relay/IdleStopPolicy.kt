package com.borasarang.droidrelay.relay

/**
 * 유휴 자동 정지 판정 — 순수 함수 (테스트 대상).
 *
 * 원칙: 사용자가 리스트에서 지우기 전까지 미완료 토렌트는 계속 시도한다.
 * 따라서 DONE이 아닌 토렌트가 하나라도 있으면 유휴가 아니다
 * (PAUSED/STALLED/FAILED 포함 — 엔진이 스스로 살려내거나 사용자가 지울 때까지.
 * 예전에는 PAUSED·STALLED·FAILED를 유휴로 쳐서, 규칙이 멈춘 토렌트가
 * 서버까지 자살시키는 영구 정지가 발생했다).
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
    if (torrentStates.any { it != TorrentState.DONE }) return false
    return true
}

/** 토렌트 절전 유효값 — 토렌트가 1건도 없으면 DHT/PEX/트래커동기를 세션에 적용하지 않는다.
 *  사용자 설정값은 그대로 두고 적용 시점에만 AND한다 (추가 시 즉시 복원). */
internal fun powerSaveEffective(userEnabled: Boolean, hasTorrents: Boolean): Boolean =
    userEnabled && hasTorrents
