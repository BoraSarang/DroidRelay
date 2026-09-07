# PLAN T-939 — 폴더 다운로드 속도 개선 (android)

> 소규모 수정 3분 초안. 목표(Goal): 폴더 통째로 모아서 1개 `.zip`으로 다운로드 — 흐름 유지, 속도만 개선.

## 원인
- `StorageRoutes.kt` `/dl-folder/`가 `ZipOutputStream` 기본값(DEFLATED 레벨 6)으로 영상(mp4 등 기압축 파일)까지 재압축 → 폰 CPU 병목. 용량은 안 줄고 시간만 증가.
- 64KB 버퍼 + `BufferedOutputStream` 없음, `contentLength` 없는 chunked라 체감 저하.

## 결정
- `zip.setLevel(0)` (DEFLATED+레벨 0 패스스루). STORED는 size/crc 사전 계산(2회 읽기) 필요라 실시간 스트리밍에 부적합 → 제외.
- 버퍼 64KB → 256KB + `BufferedOutputStream` 래핑.
- 계측 로그 2줄: 시작(폴더명) / 완료(파일 N개·원본합계 MB·소요 s·MB/s).
- 버튼/파일명(`폴더명.zip`)/내부 구조 변경 없음. tar·임시캐시·차등압축은 범위 밖.

## 검증
- `ktlintCheck` + `testDebugUnitTest` + `assembleDebug` GREEN.
- 실기기 600MB급 영상 폴더로 before/after 시간 + 압축 해제 무결성 확인.
