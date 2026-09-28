import Foundation

/// **잡(다운로드)과 토렌트에 공통인 "어떤 상태에서 무엇이 보인다" 규칙.**
///
/// ## 왜 이걸 만들었나
///
/// 웹 대시보드에는 이미 이 규칙이 **HTML 문자열로 흩어져** 있다:
/// ```
/// 1177행: if(j.type!=='video'&&j.state==='RUNNING') pause='<button>일시정지</button>';
/// 1178행: if(j.type!=='video'&&(j.state==='PAUSED'||j.state==='FAILED')) pause='<button>재개</button>';
/// 1325행: if(st==='DOWNLOADING'||st==='FETCHING_METADATA') pause='<button>일시정지</button>';
/// 1326행: if(st==='PAUSED'||st==='FAILED'||st==='STALLED'||st==='QUEUED') pause='<button>재개</button>';
/// ```
/// 메뉴바 앱이 **자기 멋대로 세 번째 규칙**을 만들면 사용자는
/// "웹에서는 일시정지가 나오는데 앱에서는 재개가 나온다" 는 혼란을 겪는다.
/// (이전 버전이 정확히 그랬다 — 일시정지/재개/삭제가 **항상 3개 다** 떴다)
///
/// → **상태 → 라벨 의 대응을 한 곳에 모은다.** 서버 상태 문자열을 그대로 키로 쓴다.
/// 웹이 어떤 상태 문자열을 쓰는지 모르면 여기서 추가하면 되고, 규칙이 하나뿐이다.
///
/// ## 규칙을 그대로 옮긴 이유
///
/// 축소 범위 결정의 핵심은 "웹에 맡긴다 / 네이티브가 한다" 의 선이다.
/// **웹이 이미 하는 것을 웹과 다르게 하면 그건 그냥 버그다.**
/// 상태별 버튼 조건을 웹과 같게 두면 사용자가 옮겨도 relearn이 필요 없다.
public enum ActionRules {

    // MARK: - 서버 상태 문자열
    //
    // **서버가 보내는 원본을 그대로 쓴다.** camelCase 로 변환하지 않는다 —
    // 변환하면 어느 상태가 빠졌는지 추적이 안 된다.
    public enum State: String {
        case queued = "QUEUED"
        case running = "RUNNING"
        case paused = "PAUSED"
        case stalled = "STALLED"
        case done = "DONE"
        case failed = "FAILED"
        case canceled = "CANCELED"
        // 토렌트 전용 (libtorrent)
        case adding = "ADDING"
        case metadata = "METADATA"
        case fetchingMetadata = "FETCHING_METADATA"
        case downloading = "DOWNLOADING"
        case seeding = "SEEDING"

        /// 웹 999행 `label()` 과 같은 표기
        public var korean: String {
            switch self {
            case .queued: "대기"
            case .running: "진행 중"
            case .paused: "일시정지"
            case .stalled: "정체"
            case .done: "완료"
            case .failed: "실패"
            case .canceled: "취소됨"
            case .adding: "추가 중"
            case .metadata: "메타데이터"
            case .fetchingMetadata: "메타데이터 추출 중"
            case .downloading: "진행 중"
            case .seeding: "시딩"
            }
        }
    }

    /// 잡은 RUNNING 이고 토렌트는 DOWNLOADING/FETCHING_METADATA 다.
    /// **웹이 상태를 다르게 다루는 이유** — 잡은 엔진이 RUNNING,
    /// 토렌트는 libtorrent 상태를 그대로 쓴다. 같은 "진행 중"이어 코드가 다르다.
    public static func isActive(_ s: String) -> Bool {
        [State.running.rawValue, State.downloading.rawValue,
         State.fetchingMetadata.rawValue, State.metadata.rawValue].contains(s)
    }

    /// 멈춰 있는 상태 — 재개를 보여줄 수 있다
    public static func isStopped(_ s: String) -> Bool {
        [State.paused.rawValue, State.failed.rawValue,
         State.stalled.rawValue, State.queued.rawValue].contains(s)
    }

    /// 더 이상 손댈 수 없는 상태
    public static func isFinished(_ s: String) -> Bool {
        [State.done.rawValue, State.canceled.rawValue].contains(s)
    }

    /// **아직 끝나지 않은 잡인가** — 배지와 "진행" 숫자를 세는 기준.
    ///
    /// ## 왜 이름이 중요했나
    ///
    /// 원래 이름은 `isListable`("목록에 뜨는가") 이었다. 하지만 **완료 잡도
    /// 목록에 보여야 한다** 고 정해져(웹과 동일) 이 이름이 **거짓말**이 되었다.
    /// 거짓말인 이름은 나중에 "이 필터로 목록을 그린다" 는 코드를 다시 쓴다.
    ///
    /// 그래서 목적을 이름에 그대로 넣었다. **"그릴 것인가" 가 아니라
    /// "끝났는가" 다.**
    ///
    /// ## 왜 `QUEUED` 도 포함인가
    ///
    /// 원래는 `RUNNING`/`STALLED` 뿐이었다. 그 결과 **다운로드를 추가하자마자
    /// 잡이 화면에서 사라졌다** — 서버가 받는 즉시 `QUEUED` 로 시작하는데
    /// 그것이 필터에 없었기 때문이다.
    ///
    /// 사용자가 하는 일이 "추가했는데 안 보인다 → 실패한 줄 안다" 다.
    /// **다만 이 값은 `isRunning`(속도 합산용) 과 목적이 다르다.**
    /// 대기 중인 잡은 아직 네트워크를 쓰지 않으므로 속도 합산에는 넣으면 안 된다.
    public static func isUnfinished(_ s: String) -> Bool {
        !isFinished(s)
    }

    // MARK: - 잡(다운로드)

    /// 잡 행에 **어떤 동작이 나오는지** — 웹 1176~1185행과 동일.
    ///
    /// - `video` 타입은 일시정지/재개를 지원하지 않는다(서버가 400 으로 거절).
    ///   웹도 그 자리에 설명 텍스트를 넣는다. **버튼을 보여주면 실패하는 동작이 된다.**
    public enum JobAction: Equatable {
        case pause, resume, retry
        /// 비디오 작업 — 웹 1177행: "FFmpeg → 삭제로 취소"
        case videoNotice
        /// 실패한 비디오 재시도
        case videoRetry

        /// 라벨 → **`POST /api/jobs/{id}/{action}` 의 경로 조각.**
        ///
        /// 이 경로에는 **`cancel` 이 없다**(서버 JobRoutes 109행: pause·resume·limit·
        /// files 만 분기, 그 외는 400 "지원 없는 동작"). 잡 삭제는 별도의
        /// `DELETE /api/jobs/{id}` 다.
        public var rawAction: String {
            switch self {
            case .pause: "pause"
            case .resume: "resume"
            case .retry: "retry"
            case .videoNotice: "pause"
            case .videoRetry: "retry"
            }
        }
    }

    // MARK: - 잡 (다운로드)

    public static func jobActions(state raw: String, type: String?) -> [JobAction] {
        let isVideo = type == "video"
        var out: [JobAction] = []
        if raw == State.running.rawValue {
            // 웹 1177행 — 비디오는 버튼 대신 안내 텍스트
            if isVideo { out.append(.videoNotice) } else { out.append(.pause) }
        }
        if isVideo, raw == State.failed.rawValue {
            out.append(.videoRetry)
        } else if !isVideo, raw == State.paused.rawValue || raw == State.failed.rawValue {
            out.append(.resume)
        }
        return out
    }

    /// **잡 삭제를 보일지** — 웹 1180행: 상태와 무관하게 항상 있다.
    public static func jobDeletable(_ s: String) -> Bool { true }

    /// **완료 잡의 파일을 내려받을 수 있는가** — 웹 1174행.
    /// ```javascript
    /// var act = j.state==='DONE' ? '<a href="/file/'+j.id+'" download>📥 받기</a>' : '';
    /// ```
    ///
    /// ## 왜 `DONE` 뿐인가
    ///
    /// 서버 `GET /file/{id}` 는 **`state != DONE` 이면 400 "아직 완료되지 않았습니다"**
    /// 로 거절한다(JobRoutes 240행). 버튼을 넓게 두면 **누를 수 있는데 안 되는**
    /// 버튼이 된다 — 앱에서 이미 한 번 겪은 종류의 버그다.
    public static func jobDownloadable(_ s: String) -> Bool {
        s == State.done.rawValue
    }

    /// 잡 행의 속도 제한 선택기를 **보여줄지** — 웹 1181행과 동일.
    ///
    /// 완료/취소된 잡은 이미 끝났으니 제한을 바꿀 수 없다.
    public static func jobLimitEditable(state raw: String, type: String?) -> Bool {
        type != "video" && !isFinished(raw)
    }

    // MARK: - 토렌트

    /// 토렌트 행의 동작 — 웹 1325~1326행과 동일.
    public static func torrentActions(state raw: String) -> [JobAction] {
        if raw == State.downloading.rawValue || raw == State.fetchingMetadata.rawValue {
            return [.pause]
        }
        if raw == State.paused.rawValue || raw == State.failed.rawValue
            || raw == State.stalled.rawValue || raw == State.queued.rawValue {
            return [.resume]
        }
        return []
    }

    /// **토렌트 속도 제한 선택기를 보일지** — 웹 1330행은 **상태와 무관하게 항상** 넣는다.
    ///
    /// ```javascript
    /// var tlimit='<select ... onchange="setTorrentLimit(...)">'+presetOptionsHtml(...)+'</select>';
    /// h+='...<div class="card-acts">'+tlimit+pause+del+'</div>'   // 조건 없음
    /// ```
    ///
    /// 잡과 **다르다**(잡은 `state!=='DONE'&&state!=='CANCELED'` 인 경우만 표시).
    /// 웹이 둘을 다르게 판단했으므로 그대로 따른다 — 같은 걸 다르게 하면 버그다.
    public static func torrentLimitEditable(state raw: String) -> Bool { true }

    /// **시드/피어를 표시할지** — 웹 1313행: 둘 다 0 이면 아예 안 그린다.
    /// `0 0` 을 보여주면 "이 토렌트는 아무도 안 본다" 와
    /// "정보가 아직 없다" 를 구분할 수 없어서 오해를 부른다.
    public static func torrentShowsPeers(seeds: Int, peers: Int) -> Bool {
        seeds > 0 || peers > 0
    }

    /// **ETA 를 계산할 수 있는가** — 웹 1306행.
    ///
    /// `state==='DOWNLOADING' && downloadSpeed>0` 이어야 한다.
    /// 속도가 0 일 때 남은 시간을 계산하면 `0 으로 나눠서 무한대` 가 되고,
    /// 그걸 그대로 "남은 ∞" 로 보여주면 버그처럼 보인다.
    public static func torrentShowsEta(state raw: String, downloadBps: Int) -> Bool {
        raw == State.downloading.rawValue && downloadBps > 0
    }

    /// **토렌트 삭제 버튼을 보일지** — 웹 1329행: 상태와 무관하게 항상.
    public static func torrentDeletable(state raw: String) -> Bool { true }

    /// 라벨 — 웹 버튼 텍스트와 **완전히 같은 문자열**
    public static func label(_ a: JobAction) -> String {
        switch a {
        case .pause: "일시정지"
        case .resume: "재개"
        case .retry: "재시도"
        case .videoNotice: "FFmpeg → 삭제로 취소"
        case .videoRetry: "재시도"
        }
    }

    /// 라벨 → **서버가 받는 `{action}` 경로 조각.**
    ///
    /// 라벨("삭제")과 경로("cancel")가 다른 유일한 케이스가 이것이다 —
    /// 서버 `JobAction` 이 `cancel` 이기 때문이다(웹 1180행이 같은 값을 보낸다).
    /// UI 는 **이 변환을 거치지 않고 라벨을 그대로 경로로 쓰면 안 된다.**
    public static func rawAction(_ a: JobAction) -> String { a.rawAction }

    // MARK: - 보관함

    /// **▶ (실시간 재생) 을 띄워도 되는 확장자 — 2026-09-29 에 `mp4`·`mp3` 두 개로 확정.**
    ///
    /// ## 이 목록이 왜 이렇게 좁은가
    ///
    /// 처음엔 **브라우저가 실제로 재생하는 것**과 **서버가 Content-Type 을 주는 것**의
    /// 교집합을 기계적으로 골랐다. `mp4` `m4v` `mov` `webm` 네 개가 남았다.
    ///
    /// 그런데 M-17 에서 **재생 앱이 IINA 로 바뀌었다.** 그 뒤 이 목록은 두 겹으로
    /// 거짓말이 되었다.
    ///
    /// 1. **"브라우저"가 아니다** — IINA 가 먼저다. 브라우저는 폴백일 뿐이다.
    /// 2. **"Video" 도 아니다** — `mp3` 는 영상 파일이 아니니까.
    ///
    /// 그래서 **이름을 바꿨고, 목록도 손으로 줄였다** — `mp4` 와 `mp3` 만 남겼다.
    ///
    /// ## 왜 기계적으로 넓히지 않고 두 개로 막았나
    ///
    /// `m4v` `mov` `webm` 은 **기술적으로 재생된다.** 그런데 없어져도 아무 방법이
    /// 사라지지 않는다. **모든 파일에 `받기` 가 있기 때문이다**(M-16 에서 확정).
    /// 받으면 로컬 파일이 되고 그때는 사용자의 기본 프로그램이 열어 준다.
    ///
    /// 즉 **넓힐 이유가 "못 하는 것" 이 아니라 "편리할 것" 인데**, 편리함을 위해
    /// ▶ 를 늘리면 **버튼이 늘어난다.** 사용자가 이미 아는 두 개의 확장자만
    /// 재생되는 것으로 남기는 편이 **예측 가능하다.**
    ///
    /// > **넓히는 것은 되돌리기 쉽고, 좁히는 것은 되돌리기 어렵다.**
    /// > 실수로 넣었다가 빼면 그 사이에 생긴 "이건 되는데 이건 안 되네" 의 혼란은 남는다.
    /// > 처음부터 최소로 시작해 **증거가 생길 때만** 넓힌다.
    private static let streamPlayable: Set<String> = ["mp4", "mp3"]

    /// **이 파일에 ▶ (실시간 재생) 를 띄워야 하는가.**
    ///
    /// ## 왜 목록 밖에는 ▶ 를 안 띄우는가 — "누를 수 있는데 안 되는 버튼"
    ///
    /// `.mkv` 에 ▶ 를 띄우고 사용자가 눌렀다면: 재생 앱이 **검은 화면**을 낸다.
    /// 사용자는 **"앱이 고장났다"** 고 생각한다.
    ///
    /// 누를 수 있는데 안 되는 버튼은 **아예 없는 것보다 나쁘다**(앱에서 이미 한 번 겪은 종류).
    /// 그래서 **재생 불가 형식에는 ▶ 를 띄우지 않고 `받기` 를 대신 둔다.**
    ///
    /// ## 판정은 대소문자를 무시한다
    ///
    /// `HOME.MP4` 도 재생된다. 서버와 Finder 가 대소문자를 구분하지 않으므로
    /// **사용자 쪽에서만 다른 파일로 취급되면 안 된다.**
    ///
    /// - Note: `playableVideo` 가 아니다. `mp3` 도 이 목록에 있다.
    public static func isStreamPlayable(_ fileName: String) -> Bool {
        let ext = (fileName as NSString).pathExtension.lowercased()
        return streamPlayable.contains(ext)
    }
}
