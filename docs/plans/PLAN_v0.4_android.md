# PLAN v0.4 — DroidRelay Torrent 클라이언트 추가

> 작성일: 2026-08-25 · 플랫폼: Android · 전제: v0.3 완료(커밋 2c09162)
> 라이브러리: libtorrent4j 2.1.0-39 (Maven Central)

## 1. 목표

1. Torrent 클라이언트 기능 추가 — magnet 링크 + .torrent 파일 다운로드
2. 4탭 내비게이션: 다운로드 / Torrent / 파일 / 설정
3. 웹 대시보드에서 torrent 관리
4. 대역폭 제어: 다운로드 무제한 / 업로드 0KB/s (기본)
5. GitHub Releases로 APK 배포

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 라이브러리 | libtorrent4j 2.1.0-39 (Maven Central) |
| 배포 | GitHub Releases |
| 탭 구조 | 4탭 (다운로드 / Torrent / 파일 / 설정) |
| 다운로드 폴더 | Download/DroidRelay/torrents |
| 업로드 기본값 | 0KB/s (off) |
| 대역폭 | 다운로드 unlimited / 업로드 선택적 |
| v1 범위 | magnet + .torrent + 파일 선택 + 폴더 지정 + 대역폭 |
| v2 후보 | 순차 다운로드, RSS, IP 필터, 트래커 관리 |

## 3. 아키텍처 변화

```
relay/
├─ TorrentEngine.kt          # libtorrent4j 세션 관리 (신규)
├─ TorrentRepository.kt      # TorrentJob 상태 관리 (신규)
├─ TorrentPersistence.kt     # torrent.json 저장/복원 (신규)
├─ DownloadEngine.kt         # 기존 (유지)
├─ RelayServer.kt            # +/api/torrents 엔드포인트 (수정)
├─ RelayService.kt           # +TorrentEngine 초기화 (수정)
├─ SettingsRepository.kt     # +Torrent 설정 필드 (수정)

ui/
├─ TorrentScreen.kt          # 신규: Torrent 탭
├─ TorrentDetailSheet.kt     # 토렌트 상세/파일선택 시트 (신규)
├─ DownloadsScreen.kt        # 기존 (유지)
├─ FilesScreen.kt            # 기존 (유지)
├─ SettingsScreen.kt         # +Torrent 설정 섹션 (수정)
├─ MainActivity.kt           # 4탭 내비게이션 (수정)
```

## 4. 구현 단계

### Phase 1: 기반 (T-301~T-304)

| 단계 | 내용 | 설명 |
|------|------|------|
| T-301 | PLAN 문서 + libtorrent4j 의존성 | build.gradle.kts + libs.versions.toml |
| T-302 | TorrentEngine 클래스 | libtorrent4j Session wrapping |
| T-303 | TorrentRepository | TorrentJob data class + StateFlow |
| T-304 | TorrentPersistence | torrent.json 저장/복원 |

### Phase 2: 기능 (T-305~T-308)

| 단계 | 내용 | 설명 |
|------|------|------|
| T-305 | magnet 링크 입력 | TorrentEngine.addMagnet() |
| T-306 | .torrent 파일 업로드 | ContentResolver → torrent 파일 |
| T-307 | torrent 정보 표시 | 이름/크기/파일목록/시드/피어 |
| T-308 | 대역폭 제한 | 설정 + 실시간 변경 |

### Phase 3: UI/UX (T-309~T-312)

| 단계 | 내용 | 설명 |
|------|------|------|
| T-309 | Torrent 탭 UI | 4탭 내비게이션 |
| T-310 | 설정 탭 Torrent 섹션 | 다운로드 폴더/업로드 속도/최대 활성/DHT/PEX |
| T-311 | 웹 대시보드 Torrent API | /api/torrents, /api/torrents/add |
| T-312 | Torrent 알림 | 진행률 + 완료 알림 |

### Phase 4: 검증/배포 (T-313~T-315)

| 단계 | 내용 | 설명 |
|------|------|------|
| T-313 | 테스트 + ktlint + 크래시 | TorrentEngine 단위 테스트 |
| T-314 | 스크린샷 + CHANGELOG + 커밋 | v0.4 릴리스 |
| T-315 | GitHub Release + APK | 자동 빌드 스크립트 |

## 5. TorrentEngine 설계

```kotlin
class TorrentEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val persistence: TorrentPersistence,
) {
    private var session: Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun addMagnet(magnet: String): TorrentJob
    fun addTorrentFile(bytes: ByteArray, filename: String): TorrentJob
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)
    fun getInfo(id: String): TorrentInfo?
    fun setUploadLimit(bytesPerSec: Long)
    fun setDownloadLimit(bytesPerSec: Long)
    fun shutdown()
}
```

## 6. TorrentRepository 설계

```kotlin
data class TorrentJob(
    val id: String,
    val infoHash: String,
    val name: String,
    val magnet: String?,
    val state: TorrentState, // QUEUED, FETCHING_METADATA, DOWNLOADING, SEEDING, PAUSED, FAILED, DONE
    val progress: Float,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val totalSize: Long,
    val downloadedSize: Long,
    val seeds: Int,
    val peers: Int,
    val uploadLimit: Long,   // bytes/sec, 0 = unlimited
    val downloadLimit: Long, // bytes/sec, 0 = unlimited
    val files: List<TorrentFile>,
    val savePath: String,
    val startedAt: Long,
    val finishedAt: Long,
)

data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
    val progress: Float,
    val selected: Boolean,
)
```

## 7. Torrent 설정 (SettingsScreen)

```
── Torrent ──
다운로드 폴더: [Download/DroidRelay/torrents]
기본 업로드 속도: [0 KB/s]
기본 다운로드 속도: [무제한]
최대 활성 torrent: [3개]
최대 시드 ratio: [2.0]
DHT: [ON]
PEX: [ON]
Listening 포트: [6881]
```

## 8. 웹 대시보드 API

| 엔드포인트 | 메서드 | 설명 |
|-----------|--------|------|
| /api/torrents | GET | torrent 목록 |
| /api/torrents/add | POST | magnet/.torrent 추가 |
| /api/torrents/{id} | GET | torrent 상세 |
| /api/torrents/{id}/pause | POST | torrent 일시정지 |
| /api/torrents/{id}/resume | POST | torrent 재개 |
| /api/torrents/{id}/files | GET | torrent 파일 목록 |
| /api/torrents/{id}/files | POST | 파일 선택 업데이트 |
| /api/torrents/{id} | DELETE | torrent 취소 |

## 9. 테스트 계획

| 테스트 | 내용 |
|--------|------|
| TC-TORR-001 | magnet 링크 입력 → 다운로드 시작 |
| TC-TORR-002 | .torrent 파일 업로드 → 다운로드 시작 |
| TC-TORR-003 | 파일 선택 → 선택한 파일만 다운로드 |
| TC-TORR-004 | 대역폭 제한 → 업로드 0KB/s 확인 |
| TC-TORR-005 | 정지/재개 → 상태 전이 확인 |
| TC-TORR-006 | 완료 → MediaStore 게시 확인 |
| TC-TORR-007 | 토렌트 취소 → 파일 삭제 확인 |
| TC-TORR-008 | 웹 대시보드 torrent API 호출 |
| TC-TORR-009 | torrent 상태 영구 저장/복원 |
| TC-TORR-010 | 크래시 0건 확인 |

## 10. 롤백

- git revert 단일 커밋 단위
- torrent.json 삭제 시 torrent 작업 초기화
- libtorrent4j 의존성 제거로 롤백 가능

## 11. 에러코드 추가

- E-AND-TORR-3001: torrent 추가 실패 (잘못된 magnet/.torrent)
- E-AND-TORR-3002: torrent 상태 전이 실패
- E-AND-TORR-3003: torrent 영구 저장 실패

## 12. 성능 예산

- TorrentEngine 초기화 ≤500ms
- torrent 상태 갱신 1초 간격
- 웹 대시보드 폴링 1초 간격
- 메모리: torrent 세션 ≤50MB
- 크기: APK ~20MB (libtorrent4j 네이티브 포함)
