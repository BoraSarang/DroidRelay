# PLAN v0.57 — 보관함 숨김 디렉터리 노출 차단 (android)

> 크기: **S**(버그픽스) · 사용자 제보(2026-10-17): *"보관함에 .torrent .trash 노출되면 안 되는데"*

## 1. 실측 재현 (S22 = 192.168.86.49)

| 표면 | 결과 | 근거 |
|------|------|------|
| **MCP `file_list` / `storage_list`** | `.torrents`·`.trash` **그대로 노출** | `/mcp` `tools/call file_list` 응답 실측 |
| **앱 "보관함" 탭 (`FilesScreen`)** | `.torrents` 노출 | 루트 필터가 `it.name == ".trash"` 이름 하드코딩 |
| `/api/storage`·`/api/storage/tree`(웹·맥) | 정상(숨김) | `StorageGuard.isHidden` 사용 |
| WebDAV `/dav` PROPFIND | 정상(숨김) | 동일 |
| `StorageJanitor`(쿼터·분류) | 정상(숨김) | 동일 |

원인: 숨김 판정(`StorageGuard.isHidden` = `.trash` + `.torrents`)을 **쓰지 않는 표면이 2곳** 있었다.
여기에 MCP `file_read` 는 `File(dlRoot, path)` 로 접두사만 검사해 `.torrents/*.torrent`·`.trash/*` **읽기**도 가능했다.

## 2. 범위 (동작 불변·숨김 일관성)

1. `StorageGuard.visibleChildren(dir)` 신설 — 숨김 제외 + 폴더 우선·이름순 (공통 판정 단일화)
2. `McpServer.fileList()` → 헬퍼 사용 (`file_list`·`storage_list` 동시 해결)
3. `McpServer.fileRead()` → `StorageGuard.storageFile(path)` (숨김 + canonical 이중 검사)
4. `StorageRoutes GET /api/storage` → 헬퍼 사용(중복 제거, 판정 동일)
5. `FilesScreen` 목록·`dirSizeOf` → 헬퍼/`isHidden` (앱 보관함 탭·폴더 용량 합계)

## 3. 검증 계획

- 단위: `TorrentStorageMoveContractTest` 픽스 2건 (숨김 제외·정렬 계약, 숨김 하위 제외) → `./build_and_run.sh test android`
- 정적: `./build_and_run.sh lint android`
- 실기: 수정 APK 재설치 후 `/mcp` `file_list` · 앱 보관함 탭에서 `.torrents`·`.trash` **미노출** 확인 (사용자 승인 필요)

## 4. 롤백

브랜치 `fix/android-hidden-dirs` 단독 커밋. 되돌리려면 `git revert` (서버 재시작 필요).
