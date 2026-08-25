# PLAN v0.1 — DroidRelayClient (macOS)

작성일: 2026-08-25 · 플랫폼: macOS 14+ (Sonoma) · 담당: BoRaSaRang

## 개요

DroidRelay Android 서버(휴대폰)를 맥에서 제어하는 네이티브 메뉴바 클라이언트.
웹 대시보드가 하는 모든 동작(다운로드·토렌트·보관함)을 네이티브 앱으로 수행한다.

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 앱 형태 | **메뉴바 앱** (MenuBarExtra .window) | 상시 백그라운드 유틸리티 성격, Dock 없이 빠른 접근 |
| UI | SwiftUI 순수 (MenuBarExtra) | AGENTS.macos 12.4 규칙 — AppKit 직접 사용 금지 |
| 최소 OS | macOS 14.0 | @Observable, MenuBarExtra window 스타일 |
| 번들ID | com.borasarang.DroidRelayClient | 컨벤션 |
| 프로젝트 생성 | xcodegen (project.yml) | 시스템에 설치 확인됨 |
| 빌드 | ./build_and_run.sh debug macos → xcodebuild → ~/Applications 복사 | 9.1 규칙 |
| 서버 발견 | 서브넷 /24 동시 포트 스캔(8080, 0.4s 타임아웃) + 마지막 주소 재시도 | Android 서버가 mDNS 미광고이므로 TCP 프로브 채택 |

## 아키텍처

```
DroidRelayClient.app
├── DroidRelayClientApp      @main — MenuBarExtra(window) + Settings + Logs Window
├── AppState                 @Observable — 연결상태/폴링/스캔 머신
├── RelayAPI                 URLSession REST 클라이언트 (BasicAuth, async/await)
├── ServerDiscovery          /24 스캔 TaskGroup, GET /api/info 검증
├── TransferManager          ⬇ 받기 — Range 이어받기, resumeData 영구저장
├── Models                   Job/Torrent/TorrentFile/StorageItem/ServerInfo
├── DebugLog                 os.Logger 래핑 + 순환버퍼(디버그 패널용)
└── Views/
    ├── PopoverView          탭 컨테이너 (다운로드/토렌트/보관함)
    ├── ConnectionBar        🟢/🔴 상태 + 주소수동입력 + 재스캔
    ├── DownloadsTab         목록+추가+pause/resume/삭제/받기
    ├── TorrentsTab          magnet 입력+.torrent 파일추가+제어
    └── StorageTab           폴더탐색+mkdir/rename/delete/move/upload/받기
```

## API 매핑 (RelayServer.kt 18종 전부)

| 기능 | 엔드포인트 |
|------|-----------|
| 서버 정보/검증 | GET /api/info |
| 다운로드 목록·추가·제어·삭제 | GET·POST /api/jobs, POST /api/jobs/{id}/{pause,resume}, DELETE |
| 토렌트 목록·추가(magnet/base64)·제어·삭제 | /api/torrents/* |
| 보관함 목록/생성/변경/삭제/이동/업로드 | /api/storage* |
| 파일 받기(Range 이어받기) | GET /file/{id} (206), GET /dl-file/{name...} |
| 웹 대시보드 열기 | GET / (Safari 기본브라우저) |

## 구현 단계

- [x] T-401 project.yml(xcodegen) + 디렉터리 골격
- [x] T-402 Models + RelayAPI (18 엔드포인트)
- [x] T-403 DebugLog (순환버퍼 포함)
- [x] T-404 ServerDiscovery (/24 스캔)
- [x] T-405 AppState (폴링 1s, 자동재연결)
- [x] T-406 TransferManager (Range 이어받기 + 완료 알림)
- [x] T-407 MenuBarExtra 팝오버 + 3탭 UI
- [x] T-408 Settings (주소/자동스캔/저장폴더/BasicAuth/로그인실행/속도표시)
- [x] T-409 앱 아이콘 생성 (CoreGraphics 스크립트 → icns)
- [x] T-410 XCTest (Models 파싱, Discovery URL구성, Range 헤더)
- [x] T-411 build_and_run.sh macos 확장 + ~/Applications 배치
- [x] T-412 CHANGELOG + 세션로그 + 커밋

## 설정 항목 (사용자 요청 반영)

| 설정 | 기본값 | 비고 |
|------|--------|------|
| 서버 주소 | 자동발견 | 수동 IP:port 입력 지원 |
| 실행 시 자동 스캔 | ON | 실패 시 "서버가 켜져 있는지 확인" 안내 |
| 저장 폴더 | ~/Downloads/DroidRelay | 받기 대상 폴더 (NSOpenPanel 선택) |
| Basic Auth | OFF | 사용자명/비밀번호 Keychain 저장 |
| 로그인 시 실행 | OFF | SMAppService.mainApp |
| 메뉴바 속도 표시 | ON | "↓ 2.5MB/s" 텍스트 |
| 웹으로 보기 버튼 | 상시 | 기본 브라우저로 대시보드 열기 |

## 에러 처리

| 코드 | 상황 | 사용자 메시지 |
|------|------|--------------|
| E-MAC-NET-1001 | 스캔 실패/연결 끊김 | "서버가 켜져 있는지 확인해 주세요. 주소는 설정에서 수동 입력할 수 있어요." |
| E-MAC-NET-1002 | HTTP 오류(4xx/5xx) | 원문 상태코드 + 짧은 설명 |
| E-MAC-STOR-1003 | 받기 실패(디스크 등) | "파일 저장에 실패했습니다. 저장 폴더 권한을 확인해 주세요." |
| E-MAC-AUTH-1004 | 인증 실패 401 | "인증이 필요합니다. 사용자명/비밀번호를 확인해 주세요." |

## 테스트 계획 (14.4)

- XCTest: `xcodebuild test` — Models JSON 디코딩, RangeParser, Discovery 후보 생성, 파일명 추출
- 빌드 게이트: `./build_and_run.sh debug macos` 성공 + 디버그 패널(Cmd+Shift+D) 로그 확인
- 실기기 검증: S22 서버 기동 후 스캔→연결→URL추가→받기 사용자 확인 (사용자 위임)

## 롤백

git revert + `rm -rf ~/Applications/DroidRelayClient.app` + DerivedData 정리.

## 성능 예산

Cold Start ≤1.5s, 메모리 ≤300MB, 팝오버 표시 ≤400ms. 폴링 1s는 URLSession 경량 GET.
