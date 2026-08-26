# 세션 로그 2026-08-25 (Android + Web + macOS)

1. **무엇을**: T-501~T-512 긴급 버그 수정 + UX 개편 + 보안 강화 + 리팩토링
2. **플랫폼**: android(주) + web + macos
3. **빌드 결과**: assembleDebug 성공 ×4회 설치, xcodebuild 성공 ×3회 ~/Applications 배포. PERF/CACHE 로그 정상 (다운로드 454KB/s 진행 중)
4. **남은 TODO**: v0.6 후속 (SSE, 다중URL, 휴지통, 체크섬)
5. **전달 로그**:
   - 다운로드 115% 원인 = DownloadEngine.kt `curBytes = offset + raf.filePointer` 이중 카운트 → `raf.filePointer` 단독으로 수정. **파일 데이터는 무손상** (seek+append 정확)
   - 보관함 파일 소실 사고 = 서버 move 자기참조 copyTo(절단)+삭제 → rename 우선 + 크기검증 후 삭제로 재발 방지. 소실 파일 mmproj (사용자 재다운로드 완료)
   - 맥 폴더생성/업로드 무반응 = WKWebView 델리게이트 미구현 → WKUIDelegate 4종 구현
6. **문서**: TODO.md(T-501~512), 세션로그 본 파일
7. **오프라인 큐**: 해당 없음 (LAN 직결 서버)
8. **E2E/k6**: 해당 없음 — 사용자 실기기 테스트로 대체 (카드 드래그 OK, 이어받기 OK, 재시도 사유 표시 OK)

## 주요 커밋 대기 변경
- android: DownloadEngine/TorrentEngine/SettingsScreen/SettingsRepository/RelayServer/WebAssets
- macos: AppDelegate(WKUIDelegate+도크)/DockViews.swift(신규)/TransferManager
