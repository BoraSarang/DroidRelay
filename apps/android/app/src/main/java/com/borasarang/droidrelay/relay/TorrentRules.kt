package com.borasarang.droidrelay.relay

/// **토렌트 판정·경로·영속 서명 — 순수 로직.**
///
/// ## 왜 여기 있나
///
/// 원래 `TorrentEngine.kt` 안에 거대 클래스와 뒤섞여 있었다. 여기 있는 것들은
/// 상태를 갖지 않는다(입력 → 출력). 엔진 없이 테스트된다 — `SeedRatioTest` ·
/// `TorrentStallTest` · `GuardSuppressionTest` · `TorrentPersistSignatureTest`.

/** 시드 비율 도달 여부 — 순수 함수 (T-956) */
internal fun shouldPauseAtRatio(isSeeding: Boolean, uploaded: Long, downloaded: Long, limit: Float): Boolean {
    if (!isSeeding || limit <= 0 || downloaded <= 0) return false
    return uploaded.toDouble() / downloaded >= limit
}

/** 정체(스톨) 판정 — 순수 함수 (T-1050).
 *  - 끄기이면 항상 false
 *  - 기준 KB/s > 0 이면서 속도가 그 미만
 *  - 또는 연결된 시더가 0 (파실 물건이 아직 안 잡힘) */
internal fun isStalledTorrent(enabled: Boolean, rateBps: Long, thresholdKbps: Int, numSeeds: Int): Boolean {
    if (!enabled) return false
    val thresholdBps = thresholdKbps.toLong() * 1024
    return (thresholdBps > 0 && rateBps < thresholdBps) || numSeeds == 0
}

/** 정체 조건이 임계 시간만큼 유지됐는지 판정 — 순수 함수 (T-1050) */
internal fun stallTimedOut(sinceMs: Long, nowMs: Long, timeoutSec: Int): Boolean =
    timeoutSec > 0 && nowMs - sinceMs >= timeoutSec * 1000L

/** 무조건 시도 원칙: 미완료 PAUSED는 엔진이 스스로 살려낸다 — 순수 함수.
 *  - 시드비율로 멈춘 완료분(progress==1)은 제외 (다운로드는 끝난 것)
 *  - 그 외 PAUSED는 전부 재시도 대상 (누가 멈췄든 상관없다 — 멈추는 길은 삭제뿐) */
internal fun shouldAutoResume(state: TorrentState, progress: Float): Boolean =
    state == TorrentState.PAUSED && progress < 1f

/** 동일 infohash의 .torrent 중복 추가 방지 (결함 #9) */
class DuplicateTorrentException(val infoHash: String) : IllegalStateException("이미 다운로드 중인 토렌트입니다")

/** 가드 억제 유효값 — 사용자 설정 AND 보유 여부 AND 비억제 (T-1101, 순수 함수, 테스트 대상).
 *  억제 중에는 DHT/PEX/트래커동기를 세션 레벨에서만 끄고 설정값은 유지한다 (0건 절전과 동일 계약). */
internal fun guardEffective(userEnabled: Boolean, hasTorrents: Boolean, suppressed: Boolean): Boolean =
    userEnabled && hasTorrents && !suppressed

/** magnet 추가 직후, 메타데이터 수신 전까지 쓰는 이름 — 실제 파일명도 아니므로 보관함 이동을 건너뛴다 */
internal const val METADATA_PLACEHOLDER = "추출 중..."

/**
 * 토렌트 작업 디렉터리 — 보관함 경로 아래의 숨김 폴더 (v0.43, T-1085).
 *
 * 보관함과 **같은 파일시스템**이어야 `moveToStorage` 의 `renameTo` 가 한 번에 끝난다.
 * 루트 자체를 쓰면 진행 중 파일이 사용자에게 보이고 쿼터 정리 대상이 되어
 * 오래된 사용자 파일이 대신 휴지통으로 사라진다 — 그래서 숨김 하위 폴더를 쓴다.
 */
internal fun storageDirOf(storagePath: String): java.io.File =
    java.io.File(storagePath, StorageGuard.TORRENT_TMP_NAME)

/** 보관함 이동 실패 시 재시도 상한 (5초 폴링 × N회 = 무한 로그 방지) */
internal const val MAX_STORAGE_MOVE_ATTEMPTS = 3
/** 토렌트 상세 응답에 담는 피어 상한 — 전체 정렬 후 상위 N개만 (5000개 JNI 객체 직렬화 방지) */
internal const val PEER_DETAIL_CAP = 50

/** 상태 폴링 주기 — 핸들·목록 모두 비면 30초, 그 외 5초 (순수 함수, 테스트 대상) */
internal fun torrentPollIntervalMs(hasHandles: Boolean, hasJobs: Boolean): Long =
    if (!hasHandles && !hasJobs) 30_000L else 5_000L

/**
 * **실제로 파일에 기록되는 필드만**으로 만든 영속화 서명.
 *
 * 기존 `lastSavedSnapshot != TorrentRepository.all()` 비교는 TorrentJob 이 data class
 * 라 저장에 포함되지 않는 라이브 필드(seeds·peers·downloadSpeed·uploadSpeed·
 * torrentFileBytes)까지 비교했다. 이 값들은 5초 폴링마다 갱신되므로
 * "내용이 같다"는 가드가 영영 통과하지 못했고, 유휴 상태에서도 **바이트가 완전히
 * 동일한 torrents.json 을 10초마다 다시 썼다**
 * (실기기 로그로 확인 — `저장 완료 3건` 이 5~10초 간격으로 반복).
 *
 * TorrentPersistence.save 가 쓰는 필드와 1:1로 대응시켜야 한다.
 */
internal fun persistedSignature(torrents: List<TorrentJob>): String {
    val sb = StringBuilder(64 + torrents.size * 48)
    for (t in torrents) {
        sb.append(t.id).append('|')
            .append(t.infoHash).append('|')
            .append(t.name).append('|')
            .append(t.magnet).append('|')
            .append(t.state.name).append('|')
            .append(t.progress).append('|')
            .append(t.totalSize).append('|')
            .append(t.downloadedSize).append('|')
            .append(t.order).append('|')
            .append(t.uploadLimit).append('|')
            .append(t.downloadLimit).append('|')
            .append(t.savePath).append('|')
            .append(t.startedAt).append('|')
            .append(t.finishedAt).append('|')
            .append(t.files.size)
        for (f in t.files) {
            sb.append('#').append(f.index).append(':').append(f.path).append(':')
                .append(f.size).append(':').append(f.progress).append(':').append(f.selected)
        }
        sb.append(';')
    }
    return sb.toString()
}
