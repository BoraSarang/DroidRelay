# 📡 DroidRelay

> 휴대폰(Android)이 직접 다운로드하는 내장 웹 서버 앱 — **외부 서버 없음**

DroidRelay는 Android 기기 안에서 동작하는 네이티브(Kotlin + Jetpack Compose) 다운로드 앱입니다. 폰에 내장된 HTTP 웹 서버를 통해 같은 네트워크의 브라우저/기기에서 URL을 등록하면, **폰 자체가 직접** 파일을 다운로드하고 보관함에 저장합니다. 느리고 자주 끊기는 환경에서도 **이어받기(resume)** 로 안정적으로 받아냅니다.

- **폰이 직접 다운로드** — 중간 서버 없이 기기 내부에서 처리
- **범용 스트림 지원** — m3u8(HLS) / MPD(DASH) / MP4·WebM·MOV 직접 URL
- **토렌트 지원** — magnet / `.torrent` (libtorrent4j)
- **이어받기** — 중단돼도 다시 연결해 이어받기
- **내장 웹 UI** — 브라우저로 접속해 조작 (모바일 레이아웃 지원)

---

## ✨ 주요 기능

| 기능 | 설명 |
|------|------|
| 🎬 스트림 다운로드 | m3u8(HLS)·MPD(DASH) 직접 주소 및 웹페이지 임베디드 영상 스니핑, **해상도 선택**, 진행률 표시 |
| 📹 직접 영상 | `.mp4/.webm/.mov` 직접 URL → 그대로 다운로드 |
| 🧲 토렌트 | magnet / `.torrent` 업로드, 시드·피어·속도 모니터링, 보관함 이동 |
| 📦 보관함 | 완료 파일을 MediaStore 보관함에 게시, 폴더 관리 |
| 🕸 내장 웹 서버 | 포트 8080, 브라우저용 대시보드 UI (HTTP 잡·토렌트·보관함·설정) |
| 🔄 이어받기 | 네트워크 단절 시 Range 기반 이어받기 재시도 |
| 🔐 보안 | WebAuth(HTTP Basic), 허용 IP 화이트리스트, HTTPS 옵션 |
| 🧠 스마트 가드 | 발열/배터리/저장공간 임계에 따른 다운로드 스로틀링 자동 대응 |
| 📅 스케줄 | 크론 기반 예약 다운로드 (Wi-Fi·충전·배터리 조건) |
| 📡 RSS | 피드 구독 → 자동 다운로드 |
| 🔔 웹훅 | 완료/실패 알림을 외부 URL로 전송 |
| 🐛 디버그 패널 | 인메모리 로그 뷰어 + 플로팅 오버레이 + 폴링 API 조회 |

> **참고**: YouTube/yt-dlp 지원은 2026년 PoToken·봇 가드 정책 변화로 v0.12.2에서 전면 제거했습니다. 대신 m3u8/mpd 직접 주소 또는 스트리밍 페이지 경로로 다운로드합니다.

---

## 🚀 빠른 시작

### 1. 설치
최신 서명 릴리즈 APK를 [Releases](https://github.com/BoraSarang/DroidRelay/releases)에서 받아 폰에 설치합니다.

```bash
adb install -r droidrelay-v0.13.3.apk
```

### 2. 실행
- 앱을 열어 **내장 서버 시작** (기본 포트 `8080`)
- 폰은 같은 네트워크(핫스팟 포함)에 연결되어 있어야 합니다.

### 3. 웹 UI 접속 (같은 네트워크)
폰 IP의 8080 포트로 접속합니다.

```
http://<폰-IP>:8080
```

**USB 연결(머신에서) — adb reverse:**
```bash
adb reverse tcp:8080 tcp:8080
# 그 다음 브라우저에서 http://localhost:8080
```

> 핫스팟 IP는 `swlan0` 인터페이스입니다. (One UI에서 핫스팟과 무선 디버깅 동시 불가 → USB adb 사용 권장)

### 4. 다운로드
- **스트림/영상/일반 URL**: 웹 UI "다운로드" 탭에 URL 입력 → (스트림이면 해상도 선택) → 다운로드
- **토렌트**: "토렌트" 탭에 magnet 또는 `.torrent` 파일 업로드

완료된 파일은 폰의 **보관함(MediaStore)** 에 저장됩니다.

---

## 🛠 개발

**스택**: Kotlin · Jetpack Compose Material 3 · Ktor Server(Netty) · OkHttp · FFmpegKit · libtorrent4j · ZXing

**레포 구조**
```
apps/android/   Android 앱 (Compose + 내장 Ktor 웹서버)
docs/           문서 (PLAN / TODO / CHANGELOG / 랜딩 페이지)
```

**빌드** (JDK는 Android Studio 내장 JBR)
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./build_and_run.sh debug android          # debug 빌드
./build_and_run.sh release android        # 서명 릴리즈 (keystore.properties 필요)
```

**테스트 / 린트**
```bash
./build_and_run.sh test android           # 단위 테스트
./build_and_run.sh lint android           # ktlint
```

**릴리즈 서명**: 릴리즈 빌드는 `apps/android/keystore.properties`(gitignore)의 릴리즈 keystore로 서명됩니다. keystore는 **백업 필수** — 분실 시 재생성 불가.

---

## 🕸 내장 웹 서버 API (일부)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/info` | 서버·저장공간·버전 정보 |
| GET/POST | `/api/jobs` | HTTP 다운로드 잡 목록 / 등록 |
| POST | `/api/jobs/{id}/{action}` | 시작·일시정지·재개·취소 |
| DELETE | `/api/jobs/{id}` | 잡 삭제 |
| GET/POST | `/api/torrents` | 토렌트 목록 / 추가 |
| POST | `/api/video/analyze` | 스트림 URL 분석 (m3u8/mpd/영상) |
| POST | `/api/video/create` | 비디오 다운로드 등록 |
| GET | `/api/settings` | 설정 조회 |
| GET | `/debug` | 디버그 패널 |

---

## ⚠️ 에러 코드

사용자 메시지는 `error_message_ko.json`으로 분리되어 있습니다. 형식: `E-AND-{CATEGORY}-{NUM4}` (예: `E-AND-DOWN-1003`, `E-AND-VID-0200`).

---

## 📄 라이선스

개인용 오픈소스 프로젝트입니다. 자세한 내용은 저장소를 참고하세요.

---

## 🗂 관련 문서
- [CHANGELOG](docs/CHANGELOG.md)
- [TODO / 진행 상황](docs/TODO.md)
- [랜딩 페이지](https://borasarang.github.io/DroidRelay/) (GitHub Pages)
