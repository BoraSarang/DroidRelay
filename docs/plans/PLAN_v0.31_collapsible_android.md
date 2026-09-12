# PLAN v0.31 — 접이식 섹션 (android/web)

> 다운로드 탭 비디오 분석 + 토렌트 탭 검색을 접이식으로. 기본 접힘, 상태 localStorage 기억.

## 목표
1. `🎬 비디오 분석 & 다운로드` 접이식 (기본 접힘, `dr_coll_video` 기억)
2. `🔍 토렌트 검색` 접이식 (기본 접힘, `dr_coll_tsearch` 기억) + 검색 사용 OFF 시 섹션 통째 숨김

## 범위
- `WebAssets.kt`만 (CSS `.coll-h/.coll-b` + `toggleColl/applyColl/refreshSearchVisibility` + HTML 래퍼)
- 토글 패턴은 테마(`setTheme/applyTheme`)와 동일 localStorage 방식
- 가시성 갱신점: 페이지 로드, `switchTab('torrent')`, `saveSearchSettings()` 직후

## 비범위
- Kotlin/서버/API 변경 없음, 에러코드 없음
- `analyzeVideo/searchTorrents` 등 기존 함수 수정 없음

## 검증 게이트
1. `node --check` JS + `theme-tokens.py` 잔여 0
2. `ktlintCheck` 본문 GREEN + `assembleDebug` + 재설치
3. headless 390/1280px: 기본접힘→펼침→새로고침 유지→검색OFF 숨김
4. TODO T-1003 + CHANGELOG + 세션 로그
