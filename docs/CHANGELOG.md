# Changelog

## [0.5.0] - 2026-08-25

### Added [macos]
- **macOS 네이티브 클라이언트**: DroidRelayClient 메뉴바 앱 (MenuBarExtra .window)
- **서버 자동 스캔**: 실행 시 LAN /24 서브넷 동시 프로브 (128 동시, 0.7초 타임아웃)
- **수동 서버 입력**: IP:port 직접 입력으로 연결 (설정에 저장)
- **다운로드 관리**: URL 추가 → 잡 목록 → 진행률/속도 표시 → 일시정지/재개/삭제
- **⬇ 받기**: 완료된 파일을 로컬 저장 폴더로 스트리밍 (Range 이어받기, .part 임시파일)
- **토렌트 관리**: magnet 링크 입력 + .torrent 파일 추가 → 목록 표시 + 제어
- **보관함 탐색**: 서버 /sdcard/Download/DroidRelay 폴더/파일 브라우징
- **보관함 관리**: 폴더 생성, 이름 변경, 잘라내기/붙여넣기(이동), 삭제, 파일 업로드
- **설정**: 저장 폴더 지정, 자동 스캔 토글, Basic Auth 인증, 로그인 시 실행(SMAppService), 메뉴바 속도 표시
- **디버그 로그 창**: os.Logger + 500줄 순환 버퍼, 전체 복사
- **앱 아이콘**: CoreGraphics + iconutil 생성 (다운로드 화살표 디자인)
- **테스트**: Models JSON 디코딩 + Discovery URL 생성 + 포맷 계산 등 10건

## [0.4.0] - 2026-08-25

### Added [android]
- **Torrent 클라이언트**: libtorrent4j 기반 torrent 다운로드 엔진 추가
- **magnet 링크 지원**: magnet:?xt=... 직접 입력 → 다운로드
- **.torrent 파일 지원**: 파일 선택 → torrent 추가
- **4탭 내비게이션**: 다운로드 / Torrent / 파일 / 설정
- **Torrent 탭 UI**: torrent 목록, 상태 표시, 일시정지/재개/삭제
- **대역폭 제한**: 다운로드 무제한 / 업로드 0KB/s (기본, 설정에서 변경 가능)
- **DHT/PEX**: 분산 해시 테이블 + 피어 교환 지원
- **웹 대시보드 Torrent API**: /api/torrents, /api/torrents/add, /api/torrents/{id}/*, DELETE
- **Torrent 알림**: torrent 완료/실패 시 알림 (별도 채널)
- **Torrent 설정**: 업로드/다운로드 속도, 최대 활성 torrent, 시드 ratio, DHT/PEX 설정
- **Torrent 상태 영구 저장**: torrent.json 기반 저장/복원
- **GitHub Releases**: APK 자동 빌드/배포

## [0.3.0] - 2026-08-25

### Added [android]
- **네트워크 복구 자동 재시작**: ConnectivityManager 감지 → FAILED 작업 자동 재시도 (에어플레인 모드/네트워크 끊김 후 자동 복구)
- **클라이언트 접속 범위 설정**: 같은 핫스팟만 (기본) / 암호만 있으면 / 승인만 — 설정 화면에서 선택
- **시작/완료 시간 표시**: Job.startedAt/finishedAt 필드 + Persist 저장
- **네트워크 타입 실시간 표시**: Wi-Fi / LTE / 5G / 3G / 2G 감지 → 서버 카드에 표시 (5초 폴링)
- **서버 주소 공유**: 공유 버튼 → QR 이미지+텍스트 Intent → 카카오톡/문자/이메일 등 모든 앱으로 전송
- **확장자별 아이콘·색상**: 🎬비디오(빨강) 🎵음악(보라) 🖼이미지(파랑) 📄문서(주황) 📦압축(초록) ⚙실행(회색)
- **파일 이름변경**: 수정 아이콘 → 다이얼로그 → MediaStore 업데이트
- **삭제 확인 다이얼로그**: 삭제 전 AlertDialog 확인 → MediaStore 삭제
- **배지 제어**: 서버실행 알림은 배지 OFF, 다운로드 완료/실패 알림은 배지 ON

### Changed [android]
- AndroidManifest.xml: ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, READ_PHONE_STATE 권한 추가
- FileProvider 설정 (QR 이미지 공유용 cache 디렉토리)
- 알림 채널 분리: relay_status (배지 OFF) + relay_result (배지 ON)

## [0.2.0] - 2026-08-25
- MD3 디자인 시스템·설정·보안·다운로드 매니저 고도화 (커밋 5956cfc)

## [0.1.0] - 2026-08-25
- 초판 (커밋 7574486)
