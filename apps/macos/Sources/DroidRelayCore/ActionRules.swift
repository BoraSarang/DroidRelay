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

    /// **목록 행을 만들어야 하는 상태인가.**
    ///
    /// ## 왜 별도 판정인가
    ///
    /// `isActive`(속도 합산) 과 `isListable`(화면에 표시) 은 **목적이 다르다.**
    /// - 속도 합산: 지금 실제로 네트워크를 쓰는가 → `RUNNING`/`DOWNLOADING` 만
    /// - 목록 표시: 사용자가 알아야 하는가 → **`QUEUED` 도 포함**
    ///
    /// `QUEUED` 를 빼면 **다운로드를 추가한 직후 잡이 화면에서 사라진다.**
    /// 서버가 받는 즉시 `QUEUED` 로 시작하기 때문에, 사용자가 보는 건
    /// "추가했는데 안 보임" 이고 실패로 오해한다. (실제 버그였다)
    public static func isListable(_ s: String) -> Bool {
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
}
