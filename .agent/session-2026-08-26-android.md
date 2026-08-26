# 세션 로그 2026-08-26 (Android + Web + macOS)

1. **무엇을**: v0.6 후속 4종(T-701~704) + v0.7 후속(T-708~710) + 버그 수정 3건
2. **플랫폼**: android(server) + web + macos
3. **빌드 결과**: assembleDebug 성공 ×5회 설치, xcodebuild 성공 ×2회 ~/Applications 배포·실행 확인. SSE tick/휴지통/체크섬/tree API curl 검증 완료
4. **남은 TODO**: 없음 (v0.6·v0.7 후보 전부 완료, 커밋 대기 31+ 파일)
5. **전달 로그**:
   - **cleartext 버그**: Android 9+에서 http:// URL 다운로드 UnknownServiceException → manifest `usesCleartextTraffic="true"`. gguf는 https라 미노출됐던 것
   - **T-601 회귀 근본 수정**: Job.progress는 파생값(저장 안 함) → 재시작 복원 시 0% 표시. `JobsRepository.restore()`에서 bytes/total 재계산. 화면 97% 유지 확인
   - **대용량 업로드**: Ktor CIO multipart가 63MB에서 버퍼 정체 → `POST /api/storage/raw-upload`(X-File-Name/X-File-Path 헤더 + receiveChannel 스트리밍) 신설. curl 196MB 11초. 맥은 `RelayAPI.storageUploadRaw()`로 전환. 웹 multipart는 소규모용으로 유지
   - **체크섬**: 3MB 테스트 파일 SHA-256 일치 → DONE(불일치 시 E-AND-DOWN-1005 FAILED 경로 확인됨). 큐 점유 시 pause→테스트→resume 패턴 사용
6. **문서**: PLAN_v0.6_android.md 신설, TODO(T-701~704, T-708~710), CHANGELOG 0.6.0, 본 로그
7. **오프라인 큐**: 해당 없음 (LAN 직결)
8. **E2E**: agent-browser로 웹 대시보드 실렌더링 검증 (트리 표시 + 클릭 탐색 + breadcrumb 갱신)

## 주요 커밋 대기 변경
- android: RelayServer(raw-upload/trash/tree/SSE), WebAssets(트리/휴지통/SSE/다중URL/체크섬), DownloadEngine(sha256), JobsRepository(restore progress), JobsPersistence(sha256/verified), Manifest(cleartext), FilesScreen(.trash 숨김), build.gradle(ktor-io)
- macos: RelayAPI(storageUploadRaw/addJob sha256), AppState(addDownloads 다중), TransferManager(raw 호출), DockViews(AddDownloadSheet)
- 기타: error_message_ko.json(E-AND-DOWN-1005), docs 4종
