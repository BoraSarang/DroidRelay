# PLAN v0.1 — DroidRelay (Android)

> 작성일: 2026-08-25 · 플랫폼: Android (S22, One UI 8.0 / Android 16)
> 작성자: BoRaSaRang + ox-alpha

## 1. 개요

불안정한 LTE 테더링 환경에서 대용량 파일 다운로드를 안정화하기 위한 안드로이드 릴레이 서버 앱.
폰(S22)이 직접 인터넷에서 파일을 받고(이어받기 강건), 테더링 LAN에 연결된 맥·iPad는
브라우저만으로 폰의 서버에 접속해 다운로드를 지시·감시·수령한다.

```
[맥/iPad] ──"URL 받아줘"──▶ [S22 DroidRelay :8080] ──LTE──▶ 인터넷(HF 등)
    ▲                            │ 포그라운드 서비스 + 이어받기 엔진
    └──── 완료 파일 전송 (핫스팟 LAN, Range 지원) ◀──┘
```

## 2. 결정 사항

| 항목 | 결정 | 근거 |
|------|------|------|
| 프로젝트명/위치 | DroidRelay @ ~/Documents/Apps/DroidRelay | 사용자 선택 |
| 언어/UI | Kotlin + Jetpack Compose Material 3 | AGENTS.md 네이티브 원칙 |
| 내장 서버 | Ktor (CIO) + 수동 Range 구현 | 코틀린 통일 |
| 다운로드 엔진 | OkHttp 4.12.0 + Range 이어받기 | 캐시 존재 |
| 외부 UI | **웹 대시보드** (앱이 HTML 한 장 서빙) | 맥/iPad 무설치 요구 |
| 배포 | USB adb (무선 디버깅은 One UI 정책상 핫스팟 동시 불가) | 환경 확정 |
| 번들ID | com.borasarang.droidrelay | AGENTS.md 8장 컨벤션 |

### 버전 고정 (Gradle 캐시 히트 극대화)

Gradle 9.5.0 · AGP 9.3.1 · Kotlin 2.3.20 · Compose BOM 2025.05.01 · OkHttp 4.12.0 · Ktor 최신 안정版(미캐시)

## 3. 아키텍처

```
app/src/main/java/com/borasarang/droidrelay/
├─ MainActivity.kt            # MD3 화면: 주소+QR 카드, 작업 목록, URL 추가 FAB
├─ relay/
│  ├─ JobsRepository.kt       # 작업 상태 단일 저장소 (StateFlow)
│  ├─ DownloadEngine.kt       # OkHttp 큐 실행기 (동시 2개, Range 이어받기)
│  ├─ RelayService.kt         # 포그라운드 서비스 + WakeLock
│  ├─ RelayServer.kt          # Ktor 라우팅: 웹 대시보드 + API + 파일(Range)
│  └─ WebAssets.kt            # 대시보드 HTML 문자열 제공
└─ ui/theme/                  # MD3 테마
```

### API 규약

| Method | Path | 기능 |
|--------|------|------|
| GET | `/` | 웹 대시보드 (HTML+JS, 한국어) |
| GET | `/api/jobs` | 작업 목록 JSON (id/url/filename/state/progress) |
| POST | `/api/jobs` | `{url}` 추가 → job id 반환 |
| DELETE | `/api/jobs/{id}` | 취소/삭제 |
| GET | `/file/{id}` | 완료 파일 스트리밍 (`Accept-Ranges`, `206 Partial Content` 지원) |

상태 머신: `QUEUED → RUNNING(progress%) → DONE / FAILED(reason) / CANCELED`

## 4. 구현 단계

- T-001 Gradle 스캐폴드 (settings/build/libs.versions.toml/manifest 권한)
- T-002 JobsRepository + DownloadEngine (Range 이어받기, 동시성 제어)
- T-003 RelayServer (API + Range 파일 서빙) + 웹 대시보드 HTML
- T-004 RelayService (포그라운드 알림 연동)
- T-005 Compose MD3 UI (주소/QR 카드, 목록, 추가 입력)
- T-006 완료/실패 시스템 알림
- T-007 단위 테스트 (큐 상태머신, Range 파서) + ktlint
- T-008 실전 검증 (맥 curl/Safari → HF gguf 소형 파일 → 이어받기 확인)

## 5. 테스트 계획

- TC-U-001 큐: 중복 URL 거부, 취소 후 재추가
- TC-U-002 이어받기: 부분 파일 존재 시 Content-Range 이어받기 검증
- TC-SRV-001 맥 Safari에서 대시보드 접속·작업 추가
- TC-SRV-002 curl -C - 로 폰서버에서 중단 재개
- 디바이스: S22 실험기 (USB adb 설치), 핫스팟 LAN 경유

## 6. 롤백 계획

git revert · 앱 제거(adb uninstall com.borasarang.droidrelay) · Downloads/DroidRelay 임시파일 삭제.
DB 없음(파일 기반 상태)이라 마이그레이션 부담 zero.

## 7. 성능 예산 (AGENTS.md 7.5 Android 준용)

Cold Start ≤2s · 메모리 ≤250MB · 다운로드 중 UI 60fps(StateFlow 버퍼링) · 서버 응답 <50ms(LAN)

## 8. 에러코드 (error_message_ko 매핑 예정)

E-AND-DOWN-1001 네트워크 단절(자동 재시도 3회 후 FAILED)
E-AND-DOWN-1002 저장공간 부족
E-AND-DOWN-1003 URL 무효/지원 불가
E-AND-DOWN-1004 포트 충돌(8080 점유)

## 9. 제약·비고

- USB 테더링: 불가 확정 (사용자 환경) — 고려 대상에서 제외
- 무선 디버깅: One UI가 핫스팟 동시 허용 안 함 → USB adb로 개발
- 요금제: 모두다 맘껏 11GB+ — 대용량 다운로드는 고속 데이터 소모 유의
