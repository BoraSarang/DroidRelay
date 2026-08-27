# PLAN v0.12 — 비디오 다운로드 (범용 스트림) (2026-08-27 · 갱신: 유튜브 제외)

> **중요 변경 (검증 후 결정)**: 초기 계획의 "유튜브"는 **기능 제외**됨. 2026 유튜브가 PO Token 강제 +
> 통신사 LTE NAT IP 평판 차단(`Sign in to confirm you're not a bot`)으로 NewPipeExtractor·ANDROID_VR 스푸핑
> 우회를 전부 적용해도 비로그인 추출이 IP 단위로 막혀 실사용 불가 → 관련 코드·의존성 제거.
> **범용 m3u8/mpd 스트림 다운로드만 제공**(원본 copy).

## 개요
웹 대시보드(웨일)에서 '안전한 다운로드' 이후 다음 단계 — **비디오 다운로드 기능** 추가.
- ~~유튜브: URL 붙여넣기 → 해상도/음성 선택 → 다운로드~~ → **제외** (2026 유튜브 안티봇/IP 차단)
- **범용 스트림**: 스트림이 담긴 웹페이지 URL 또는 직접 `.m3u8`/`.mpd` URL → 원본 copy 다운로드

## 사용자 확정 범위 (승인 완료 + 갱신)
| 항목 | 결정 |
|------|------|
| 배포 | Play 미배포 — GitHub/개인용 (GPL 의존성 허용) |
| 스트림 대상 | 범용 m3u8 (특정 사이트 한정 아님) |
| FFmpeg | 앱에 내장 (번들) |
| 유튜브 지원 | **제외** — m3u8/mpd 직접 주소 경로로만 안내 |
| 추출기/UI 방향 | StreamDetector(스니핑) + Maven Central FFmpegKit 유지 포크 + 웹 UI 우선 |

## 라이브러리 선정 (리서치 결과 · 갱신)
| 용도 | 선택 | 비고 |
|------|------|------|
| ~~유튜브 추출~~ | ~~NewPipeExtractor 0.26.5~~ | **제거** — v0.26.5가 최신이었고 그조차 2026 유튜브 PoToken/IP 차단에 실패 |
| FFmpeg | `dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.7` (Maven Central) | 원본 `arthenica/ffmpeg-kit`는 리타이어. `full` 변형은 **TLS 미포함**(`Protocol not found`) 확인 → **`https` 변형**으로 교체 |
| desugaring | `com.android.tools:desugar_jdk_libs_nio:2.1.5` | 유지 (일부 의존성 java.* 대응) |

### 배제 사유
- `FFmpegAndroid 0.3.2`(writingminds) · `arkanath/ApkPure` 계열 — 수년 미관리, SDK 35/16KB 미대응
- `evermind-zz/HlsDownloader` — 세그먼트 자체 파싱이라 DASH 머지/DRM/썸네일 복잡, FFmpeg 대비 이점 없음
- `arthenica/ffmpeg-kit-next` — 공식 후속이지만 **소스만 제공** (바이너리 없음)

## 아키텍처 — 통합 FFmpeg 파이프라인
```
[입력] 스트림(웹페이지/m3u8/mpd) ──► StreamDetector ──► m3u8/mpd URL (원본 copy)
                    └──────────────┬────────────────┘
                                   ▼
            VideoDownloadManager (FFmpegKit.executeAsync)
              - .m3u8: -i <m3u8>        -c copy -movflags +faststart <out>.mp4
              - .mpd : -i <mpd>         -c copy -movflags +faststart <out>.mp4
              - 진행률: Statistics(getSize) → Job.downloadedBytes · speedBps
              - 완료: 기존 DownloadEngine 방식대로 보관함(MediaStore) 게시
```

### 설계 포인트
1. ~~유튜브 조회: OkHttp Downloader → NewPipe → 포맷 목록~~ → **제거**. 분석 API는 StreamDetector 전용.
2. (통합) **머지 필요 없음** — 스트림은 원본 copy 단일 입력.
3. **스트림 감지**: 웹페이지 GET(브라우저 UA) → HTML에서 `.m3u8`/`.mpd` URL 정규식 스니핑 → 상대경로 절대화. **403/Cloudflare 차단 시 명확한 안내 + 직접 m3u8 URL 입력 경로 병행** (torrentsee 계열 확인: 봇 요청 403 → 이 경로 필수).
4. **취소**: FFmpegKit만의 cancel API 없음 → `FFmpegKit.cancel(session)`, Job 유실 시 프로세스 종료로 자동 정리.
5. ~~썸네일/메타: 유튜브 제목·썸네일~~ → 스트림은 페이지 `<title>`만 사용.

## Job/UI 통합
- `Job`에 `type: JobType`(HTTP/TORRENT/VIDEO) 추가 — 기본 `HTTP`라 기존 직렬화·복원 하위호환.
- VIDEO 잡: `downloadedBytes`=출력 파일 사이즈(주기 stat), `totalBytes`=-1(?) → 기존 "?" 표시 재사용, `speedBps` 갱신. 웹 다운로드 탭 카드에 `🎬` 배지.
- **웹 UI (1차·필수)**: 다운로드 탭 입력부에 비디오 섹션(URL + [분석]) → 정보: 재생포맷 없음 → 직접 [다운로드](파일명 설정). API: `POST /api/video/analyze`(스트림 URL/제목) · `POST /api/video/create`(잡 생성).
- **앱 Compose UI (2차)**: DownloadsScreen에 비디오 입력 추가 — 범위가 크면 후속 v0.12.1로 분리.

## 마일스톤 (T-857 ~ T-864 · 상태 갱신)
| ID | 내용 | 상태 |
|----|------|------|
| T-857 | PLAN 작성 | ✅ |
| T-858 | 의존성: NewPipe+desugar+ffmpeg 검증 · 빌드 게이트 | ✅ (ffmpeg https로 교체 포함) |
| T-859 | ~~유튜브 추출기~~ | ❌ **취소** — 2026 유튜브 IP/기능 차단으로 제외 → **v0.12.1 yt-dlp 서버 방식으로 재도입** (T-867~869) |
| T-860 | VideoDownloadManager: FFmpeg 실행·진행률·취소·MediaStore 게시 + Job(type) 통합 | ✅ |
| T-861 | StreamDetector: 웹페이지 스니핑 + 직접 .m3u8/.mpd 입력 허용 | ✅ |
| T-862 | API 2종 + 웹 UI(분석→다운로드) | ✅ |
| T-863 | 앱 Compose UI | ⏸ 후속으로 연기 |
| T-864 | 실기기 E2E(m3u8 원본 copy) · CHANGELOG · error_message_ko.json · AI_MODELS · 세션 로그 · 커밋 | 🔄 진행 중 |

## 위험/제한 (명시)
| 항목 | 대응 |
|------|------|
| DRM(Widevine/FairPlay) | 실행 실패 시 `E-AND-VID-0300` 차단 안내 (재생 불가 콘텐츠는 다운로드 불가가 정상) |
| ~~유튜브 (PoToken·IP 평판)~~ | **기능 제외** — NewPipeExtractor 0.26.5 최신으로도 2026 유튜브 PoToken + LTE NAT IP 평판 차단(visitor_id 주입, ANDROID_VR 스푸핑까지 시도)에 실패. m3u8/mpd 직접 경로 안내. **v0.12.1(y-t-dlp 서버) 시도 결과**: 분석/직링크 생성은 서버에서 성공(23 formats), 다운로드는 서버 IP가 googlevideo 403로 차단 — **yt-dlp 서버는 평판 좋은 IP(클라우드/고정 IP)에서 운영** 필요. `/proxy` 스트리밍 프록시 구현 완료 |
| m3u8 토큰 만료(세션 한정) | 분석→다운로드 지연 최소화, 실패 시 재분석 유도 메시지 |
| 스트림 페이지 Cloudflare 403 | 직접 m3u8 URL 경로로 안내 (터널 미통과는 범위外) |
| FFmpeg 네트워크 스로틀 미적용 | 전역 속도제한은 기존 HTTP 잡에만 유효 — 비디오는 제한 없음 |
| APK 크기 증가 (네이티브 ~수십 MB) | 개인용 무방, CHANGELOG에 기록 |
| 테스트 소스 | Mux test-streams HLS로 E2E 검증. Apple devstreaming은 이제 S3 AccessDenied(부적합) |

## 검증
- `./build_and_run.sh debug android` (ktlint + assembleDebug) → 실기기(S22) 설치 ✅
- FFmpeg 스모크(`-version`) 로그 확인 ✅
- ~~유튜브 머지 다운로드~~ → 제외
- **m3u8 E2E ✅**: Mux HLS(`url_0/193039199_mp4_h264_aac_hd_7`) 분석(kind:stream) → 생성(잡 `mtbvvj2q622`) → RUNNING 진행·속도 리포트 162.37MB → DONE → `Download/DroidRelay/mux_hls_test.mp4` 게시 → ffprobe 무결성(mov,mp4, duration 634.6s, 170,260,672B)
- 유튜브 제거 후 회귀 스모크 ✅: 재설치 → analyze→create(RUNNING 22MB 진행)→DELETE 취소 → REMOVED_OK
- DRM/무효 URL 에러코드 표면 (error_message_ko.json 매핑)

## 에러코드 (E-AND-VID-XXXX · 최종)
| 코드 | 의미 |
|------|------|
| E-AND-VID-0100 | 비디오 분석 실패 (네트워크 오류) |
| E-AND-VID-0101 | 지원하지 않는 URL |
| E-AND-VID-0200 | 스트림 미검출 (웹페이지에서 m3u8/mpd 없음) |
| E-AND-VID-0201 | FFmpeg 시작 실패 (네이티브 로드 오류) |
| E-AND-VID-0202 | FFmpeg 실행 오류 (403/코덱/스트림 깨짐) |
| E-AND-VID-0300 | DRM 보호 콘텐츠 (다운로드 불가) |
| E-AND-VID-0400 | 사용자 취소 |
| E-AND-VID-0401 | 앱 재시작 후 잡 미재개 (스테일 정리) |

## 라이선스
- FFmpegKit 유지 포크 `https` 변형 LGPL-3.0 — **개인·비배포** 용도로 수용. NewPipeExtractor GPL-3.0은 제거됨.