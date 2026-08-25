# Changelog

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
