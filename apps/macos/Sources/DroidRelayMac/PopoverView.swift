import SwiftUI
import DroidRelayCore

/// 메뉴바 팝오버 (목업 A).
/// - 폭 352px 고정, 항목 많으면 스크롤
/// - 하단에 서버 주소 + 자동 발견 상태 표시
struct PopoverView: View {
    @Bindable var model: AppModel
    var onSettings: () -> Void
    var onQuit: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().opacity(0.5)
            // ── 속도 그래프 ──
            // 메뉴바는 2줄 요약이고 **자세한 추이는 여기.** 그래프가 없으면 메뉴바가
            // 숫자만 반복해서 보여주는 셈이 된다.
            // DR_NO_GRAPH 로 그래프를 끄면 팝오버가 뜨는지 비교할 수 있다.
            if !model.visibleSpeedSources.isEmpty && ProcessInfo.processInfo.environment["DR_NO_GRAPH"] == nil {
                SpeedGraph(
                    sources: model.visibleSpeedSources,
                    droidDown: model.graphSeries(.droid, down: true),
                    droidUp: model.graphSeries(.droid, down: false),
                    deviceDown: model.deviceSpeedAvailable
                        ? model.graphSeries(.device, down: true) : [],
                    deviceUp: model.deviceSpeedAvailable
                        ? model.graphSeries(.device, down: false) : []
                )
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                // **폭을 명시한다.** Canvas 는 제안된 크기를 그대로 쓰는데, VStack 안에서
                // 제안 폭이 0 이면 그래프가 0 폭이 되고 팝오버 전체가 사라진다.
                .frame(width: 328)
                Divider().opacity(0.5)
            }
            tabBar
            Divider().opacity(0.5)
            // ── 확인 막대 ──
            // **삭제는 여기서 멈춘다.**
            //
            // ## 왜 시트가 아니라 팝오버 안인가
            //
            // 이 앱은 `.accessory` 라 **키 윈도우가 없다** → `.sheet` 가 뜨지 않는다
            // (PR #28 의 시트 8종이 전부 그랬다). 한 번 더 같은 실수를 반복하지 않는다.
            if let c = model.confirm {
                confirmBar(c)
            }
            content
            // **실패 배너** — 쓰기 동작이 조용히 실패할 때만 뜬다.
            //
            // 성공은 화면이 그대로 증거다(목록에서 사라졌고, 상태가 바뀌었다).
            // **아무것도 안 바뀌었는데 아무 말도 없으면** 사용자는 성공을 믿게 되고,
            // 그건 사실이 아니다. 실패만 눈에 띄게 남긴다.
            if !model.lastResult.isEmpty {
                resultBanner
            }
            Divider().opacity(0.5)
            stats
            Divider().opacity(0.5)
            footer
        }
        .frame(width: 352)
    }

    // MARK: - 탭바 (M3)

    private var tabBar: some View {
        HStack(spacing: 4) {
            ForEach(AppModel.Tab.allCases) { t in
                let on = model.selectedTab == t
                Button { Task { await model.selectTab(t) } } label: {
                    HStack(spacing: 4) {
                        Image(systemName: t.icon).font(.system(size: 10.5))
                        Text(t.title).font(.system(size: 11.5, weight: on ? .semibold : .regular))
                        if let n = badge(t), n > 0 {
                            Text("\(n)").font(.system(size: 9.5, weight: .bold))
                                .padding(.horizontal, 4).padding(.vertical, 1)
                                .background(Capsule().fill(on ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.25)))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
                    .background {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(on ? Color.accentColor.opacity(0.16) : .clear)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(t.title)
            }
        }
        .padding(.horizontal, 5)
        .padding(.vertical, 4)
    }

    /// 탭별 배지 — "이 탭에 진행 중이 몇 개나 있는가"
    ///
    /// **완료 잡은 세지 않는다.** 배지는 "지금 무언가 돌아가고 있나" 의 신호다.
    /// 여기까지 완료까지 세면, 다 받은 다음에도 숫자가 남아
    /// "뭐가 아직 도는 거지" 를 한 번 더 찾아게 된다.
    private func badge(_ t: AppModel.Tab) -> Int? {
        switch t {
        case .downloads: return model.ongoingJobs.isEmpty ? nil : model.ongoingJobs.count
        case .torrents: return model.activeTorrents.isEmpty ? nil : model.activeTorrents.count
        case .storage: return nil
        }
    }

    /// **쓰기 실패 배너.** 닫을 수 있다 — 방치하면 "이 메시지가 아직 유효한가"
    /// 를 알 수 없어서, 사용자가 스스로 지울 수 있게 한다.
    private var resultBanner: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 10))
                .foregroundStyle(.orange)
            Text(model.lastResult)
                .font(.system(size: 11))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Button {
                model.lastResult = ""
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.tertiary)
            }
            .buttonStyle(.plain)
            .help("닫기")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(width: 352)
        .background(Color.orange.opacity(0.12))
    }

    // MARK: - 확인 막대 (삭제 컨펌)

    /// **파괴 동작 확인 막대** — 팝오버 안에 직접 그린다.
    ///
    /// ## 왜 지금 만들어진가
    ///
    /// 이 화면을 만들며 **사용자 토렌트 2건을 지웠다.** 삭제 버튼이 확인 없이
    /// 곧바로 서버를 때렸고, 되돌릴 수 없었다.
    ///
    /// 검증하러 눌렀는데 진짜 데이터가 사라졌다 — **같은 일을 두 번 하면 안 된다.**
    /// 그래서 **모든 파괴 동작 앞에 반드시 이 막대가 선다.**
    ///
    /// ## 무엇을 보여주나
    ///
    /// - **대상 이름** (무엇을 지우는가)
    /// - **파일이 살아남는지** (되돌릴 수 있는가)
    /// - 되돌릴 수 있으면 "휴지통 이동" 처럼 **덜 무섭게** 부른다
    ///
    /// `ConfirmSpec` 가 이미 종류별로 문구를 정해 준다. 여기서는 그리기만 한다.
    private func confirmBar(_ c: AppModel.ConfirmRequest) -> some View {
        // `.background(...)` 는 `some View` 를 그대로 못 돌려준다 →
        // **명시적으로 `return`** 한다. 없으면 "타입을 알 수 없다" 는 컴파일 오류.
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 5) {
                Image(systemName: c.spec.isDestructive
                      ? "exclamationmark.triangle.fill" : "questionmark.circle.fill")
                    .font(.system(size: 10.5))
                    .foregroundStyle(c.spec.isDestructive ? Color.red : Color.accentColor)
                Text(c.spec.title)
                    .font(.system(size: 11.5, weight: .semibold))
                Spacer(minLength: 4)
            }
            // **문자열 해석을 하지 않는다.** `ConfirmSpec.attributedBody` 가
            // `(텍스트, 강조)` 조각을 이어 붙여 만든 결과다.
            //
            // 마크다운(`AttributedString(markdown:)`)을 썼다가 **두 번** 꼬였다:
            // ① `Text` 가 `**` 를 그대로 보여줬다 ② 파일명의 `*` `_` 가 eaten 됐다
            //    (`__agent_test__` → `agent_test`, 즉 **무엇을 지우는지가 틀어짐**)
            //
            // → **파일명은 원본 그대로** 표시하고, 강조만 구조로 받는다.
            Text(c.spec.attributedBody)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 6) {
                Spacer(minLength: 0)
                Button { model.cancelConfirm() } label: {
                    Text("취소").font(.system(size: 11))
                }
                .controlSize(.small)
                .keyboardShortcut(.cancelAction)
                .accessibilityLabel("취소, 아무것도 하지 않음")
                Button { model.performConfirm() } label: {
                    // **되돌릴 수 없으면 "삭제", 되돌릴 수 있으면 그에 맞는 동사.**
                    // 둘을 같은 라벨로 두면 사용자가 위험도를 구분할 수 없다.
                    Text(c.spec.isDestructive ? "삭제" : c.spec.title)
                        .font(.system(size: 11, weight: .semibold))
                }
                .controlSize(.small)
                .buttonStyle(.borderedProminent)
                .tint(c.spec.isDestructive ? Color.red : Color.accentColor)
                // **VoiceOver/자동 검증이 구분할 수 있게 이름을 분리한다.**
                .accessibilityLabel(c.spec.isDestructive
                                    ? "\(c.spec.title), 되돌릴 수 없음"
                                    : "\(c.spec.title), 되돌릴 수 있음")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(width: 352, alignment: .leading)
        // **빨간 배경 = 되돌릴 수 없다.** 사용자가 문구를 다 읽지 않아도 구분된다.
        .background((c.spec.isDestructive ? Color.red : Color.accentColor).opacity(0.10))
        Divider().opacity(0.5)
    }

    @ViewBuilder
    private var content: some View {
        switch model.selectedTab {
        case .downloads: jobList
        case .torrents: torrentList
        case .storage: storageList
        }
    }

    // MARK: - 헤더

    private var header: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 13))
                Text("DroidRelay").font(.system(size: 12.5, weight: .semibold))
                Spacer()
                if let d = model.lastUpdate {
                    Text(d, style: .relative)
                        .font(.system(size: 11))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 10)

            // **어디에 붙었는지 한 줄** (M-11).
            //
            // ## 왜 헤더 바로 아래인가
            //
            // "DroidRelay" 라는 제목만 보면 **어느 폰인지 알 수 없다.**
            // 사용자가 그대로 물은 게 "이게 내 폰이 맞아?" 다.
            // 제목과 **같은 줄에 붙어 있어야** "이 앱이 누구와 붙었는가" 가 한 번에 읽힌다.
            //
            // ## 왜 따로 빼지 않았나 — 옆에 붙이면 사라진다
            //
            // 오른쪽엔 **마지막 갱신 시각**(`방금 전`)이 이미 있다. 거기 붙이면
            // **두 정보가 한 줄에서 경쟁한다.** 어느 쪽이 움직이는지 눈에 띄고
            // 읽히는 쪽은 더 중요하므로 **고정된 줄로 분리**한다.
            connectionLine
        }
    }

    /// **탐색 결과 한 줄** — 주소 · 버전 · 붙은 방법을 말한다.
    private var connectionLine: some View {
        HStack(spacing: 5) {
            // **기호가 상태를 먼저 말한다** — 글자를 다 읽기 전에도
            // "연결됐다 / 안 됐다" 가 보이게 한다.
            Image(systemName: model.isConnecting ? "magnifyingglass" : dotIcon)
                .font(.system(size: 9))
                .foregroundStyle(dotColor)
            Text(model.connectionLine)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 13)
        .padding(.bottom, 7)
    }

    private var dotIcon: String {
        switch model.phase {
        case .connected: "circle.fill"
        case .discovering: "circle.dotted"
        case .failed: "exclamationmark.circle.fill"
        case .idle: "circle"
        }
    }

    /// **색은 글자와 같은 판단을 쓴다** — "연결됨" 인데 주황이면 사용자는 거짓말로 읽는다.
    private var dotColor: Color {
        switch model.phase {
        case .connected: .green
        case .discovering: .secondary
        case .failed: .orange
        case .idle: .secondary
        }
    }

    // MARK: - 인라인 추가 줄 (다운로드 · 토렌트)

    /// **입력줄 하나 — 다운로드/토렌트가 같은 규칙을 공유한다.**
    ///
    /// ## 왜 함수 하나로 두는가
    ///
    /// 처음엔 두 곳에 따로 만들려 했다. 그런데 그 순간 이미 이런 어긋남이 예고된다.
    /// 한쪽은 "비활성 조건"을 다르게 적고, **붙여넣기 안내가 한쪽에만 나온다.**
    ///
    /// → **규칙은 `AddInput` 한 곳, 화면은 이 함수 한 곳.** 차이가 생길 자리가 없다.
    ///
    /// ## 규칙
    ///
    /// | 규칙 | 이유 |
    /// |---|---|
    /// | `canSend == false` 면 버튼 비활성 | PR #28 "눌러도 아무 일 없다" 재발 방지 |
    /// | 여러 개 붙여넣기 가능 | 링크 5개를 하나씩 넣는 건 현실적이지 않다 |
    /// | Enter 로도 전송 | 클릭 이동 없이 끝나게 |
    /// | **실패하면 입력값 유지** | 지우면 다시 타이핑해야 한다 |
    /// | 성공하면 입력값만 비움 | 연속 추가 가능 |
    private func addBar(_ spec: AddBarSpec) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "plus.circle.fill")
                .font(.system(size: 11))
                .foregroundStyle(spec.canSend ? Color.accentColor : Color.secondary.opacity(0.5))
            TextField(spec.placeholder, text: spec.text)
                .textFieldStyle(.plain)
                .font(.system(size: 11))
                .onSubmit(spec.send)          // **Enter = 추가**
            Button(action: spec.send) {
                Text("추가").font(.system(size: 11, weight: .semibold))
            }
            .controlSize(.small)
            .buttonStyle(.borderedProminent)
            .disabled(!spec.canSend)
            // **자동 검증이 구분할 수 있게 이름을 분리한다.**
            // ("추가" 라는 이름이 화면에 몇 개 있는지 세면 알림이 아니라 구분의 문제다)
            //
            // **힌트는 줄마다 다르다.** 다운로드만 여러 개 붙여넣기가 되고
            // 토렌트는 `magnet:` 하나다. 같은 문구를 두 줄에 다 붙이면
            // **둘 중 하나가 거짓말**이 된다(실제로 그렇게 됐고 고쳤다).
            .accessibilityLabel("\(spec.accessibleName) 추가, \(spec.hint)")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.06))
        .overlay(alignment: .bottom) { Divider().opacity(0.4) }
    }

    /// **추가줄에 필요한 값들** — 뷰는 이것만 보고 그린다.
    struct AddBarSpec {
        let placeholder: String
        let accessibleName: String
        /// **붙여넣기 안내** — 줄마다 규칙이 다르므로 **각자 다르다.**
        /// 같은 문구를 두 줄에 다 쓰면 둘 중 하나는 거짓말이 된다.
        let hint: String
        let text: Binding<String>
        let canSend: Bool
        let send: () -> Void
    }

    // MARK: - 목록 (다운로드)

    private var jobList: some View {
        VStack(spacing: 0) {
            // **목록이 비어도 입력줄은 항상 있다.**
            // 없으면 "추가하려면 웹으로 가세요" 가 되는데, 그게 싫어서 만든 기능이다.
            addBar(AddBarSpec(
                placeholder: "URL 또는 파일 경로",
                accessibleName: "다운로드",
                hint: "붙여넣기하면 공백으로 구분해 여러 개 한 번에",
                text: $model.addUrlText,
                canSend: model.canAddUrl,
                send: { model.addDownloads() }
            ))
            if !model.finishedJobs.isEmpty { finishedToggle }
            ScrollView {
                LazyVStack(spacing: 2) {
                    if model.listedJobs.isEmpty {
                        empty("진행 중인 작업이 없습니다", tip: model.phase == .failed ? "설정에서 주소를 확인하세요" : "위에 주소를 넣어 추가하세요")
                    }
                    ForEach(model.listedJobs) { j in
                        JobRow(job: j,
                               onAction: { act in model.control(j.id, act) },
                               onDelete: { model.askJobDelete(j) },
                               onDownload: { model.openJobFile(j) },
                               onLimit: { bps in model.jobLimit(j.id, bps) })
                    }
                }
                .padding(5)
            }
            .frame(maxHeight: 300)
        }
    }

    /// **완료 잡 접기/펼치기.**
    ///
    /// ## 왜 서버를 안 건드나
    ///
    /// "완료만 지우기" 가 직관적이지만 **`DELETE /api/jobs/{id}` 는 받은 파일까지
    /// 지운다.** 목록을 정리하는 버튼이 파일을 지우는 버튼이 되면 안 되므로
    /// **화면에서만 접는다.** 값은 저장되므로 다음 실행에도 유지된다.
    ///
    /// ## 왜 `Button` 이지
    ///
    /// 처음엔 `HStack` + `onTapGesture` 로 만들었다. 그러면 **버튼이 아니라
    /// 정적 텍스트로 노출된다**(실측: `AXImage`/`AXStaticText` 에서 이름만 나옴).
    /// 눈에는 클릭되는 것처럼 보이지만 **VoiceOver 로는 눌릴 수 없다.**
    ///
    /// "누를 수 없어서 안 하는 것" 과 "누르는 법을 몰라서 안 하는 것" 은
    /// 밖에서 보기엔 똑같다. **진짜 `Button` 으로 만들어야 두 가지가 같아진다.**
    private var finishedToggle: some View {
        Button { model.showFinishedJobs.toggle() } label: {
            HStack(spacing: 4) {
                Image(systemName: model.showFinishedJobs ? "chevron.down" : "chevron.right")
                    .font(.system(size: 8, weight: .bold))
                Text(model.showFinishedJobs ? "완료 \(model.finishedJobs.count)건 접기" : "완료 \(model.finishedJobs.count)건 보기")
                    .font(.system(size: 10.5))
                Spacer()
            }
            .foregroundStyle(.secondary)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Color.secondary.opacity(0.04))
        .help(model.showFinishedJobs ? "완료된 작업을 접습니다 (서버는 건드리지 않습니다)" : "완료된 작업을 다시 펼칩니다")
    }

    // MARK: - 목록 (토렌트)

    private var torrentList: some View {
        VStack(spacing: 0) {
            addBar(AddBarSpec(
                placeholder: "magnet: 링크",
                accessibleName: "토렌트",
                // **다운로드 줄과 문구를 같게 두면 거짓말이다.**
                // 토렌트는 `AddInput.isMagnet` **하나만** 받는다 —
                // 두 개를 붙여넣으면 버튼이 영영 안 살아난다.
                hint: "magnet: 로 시작하는 링크 하나만",
                text: $model.addMagnetText,
                canSend: model.canAddMagnet,
                send: { model.addMagnet() }
            ))
            ScrollView {
                LazyVStack(spacing: 2) {
                    if model.torrents.isEmpty {
                        // **"웹에서 추가하세요" 는 이제 거짓말이다.** 앱에서 직접 된다.
                        empty("토렌트 목록이 없습니다", tip: "위에 magnet 링크를 넣어 추가하세요")
                    }
                    ForEach(model.torrents) { t in
                        TorrentRow(
                            torrent: t,
                            onAction: { act in model.torrentControl(t.id, act) },
                            onDelete: { model.askTorrentDelete(t) },
                            onLimit: { bps in model.torrentLimit(t.id, bps) }
                        )
                    }
                }
                .padding(5)
            }
            .frame(maxHeight: 300)
        }
    }

    // MARK: - 목록 (보관함)

    private var storageList: some View {
        VStack(spacing: 0) {
            storageToolbar
            Divider().opacity(0.4)
            ScrollView {
                LazyVStack(spacing: 1) {
                    if model.storage.isEmpty {
                        empty("보관함이 비어 있습니다", tip: nil)
                    }
                    // **편집창은 목록 위에 뜬다** — 시트가 아니라.
                    //
                    // 이 앱은 `.accessory` 활성화 정책이라 **키 윈도우가 없다.**
                    // 키 윈도우 없으면 `.sheet` 가 **조용히 뜨지 않는다.**
                    // (PR #28 의 8종 시트가 전부 그랬다 — 컴파일은 되고 아무 일도 안 일어남)
                    if let e = model.storageEdit {
                        storageEditBar(e)
                        Divider().opacity(0.4)
                    }
                    ForEach(model.storage) { e in
                        StorageRow(entry: e,
                                   onOpen: { model.storageEnter(e) },
                                   onPlay: { model.playStream(e) },
                                   onDownload: { model.downloadStorage(e) },
                                   onRename: { model.askConfirm(.init(kind: .rename(e))) },
                                   onMove: { model.askConfirm(.init(kind: .move(e))) },
                                   onTrash: { model.askStorageTrash(e) })
                    }
                }
                .padding(5)
            }
            .frame(maxHeight: 270)
            if !model.trash.isEmpty {
                Divider().opacity(0.4)
                trashList
            }
        }
    }

    /// **탐색 막대** — 위치 표시 + 위로 + 새 폴더.
    ///
    /// 하위 폴더를 보려면 `?path=` 로 다시 불러와야 한다. **어디에 있는지
    /// 안 보이면** 사용자는 "아무것도 안 되네" 로 오해한다.
    private var storageToolbar: some View {
        HStack(spacing: 6) {
            // **빵조각** — 항상 "보관함" 으로 시작한다. 이 조각이 있어야
            // 하위 폴더에서 루트로 올라갈 수 있다.
            ForEach(Array(model.storageCrumbs.enumerated()), id: \.offset) { i, c in
                if i > 0 {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(.tertiary)
                }
                let isHere = (i == model.storageCrumbs.count - 1)
                Button {
                    model.storagePath = c.path
                    model.storageEdit = nil
                    Task { await model.refresh() }
                } label: {
                    Text(c.label).font(.system(size: 11, weight: isHere ? .semibold : .regular))
                        .lineLimit(1)
                }
                .buttonStyle(.plain)
                // **현재 위치는 누를 수 없다** — 이미 거기다.
                // 그래도 눌리게 두면 "왜 안 바뀌지" 는 혼란이 생긴다.
                .disabled(isHere)
                .foregroundStyle(isHere ? Color.primary : Color.accentColor)
                // **탭 버튼과 라벨이 겹치면 안 된다.**
                //
                // 탭에도 "보관함" 이 있고 빵조각 첫 칸에도 "보관함" 이 있다.
                // 라벨만 보면 **같은 이름의 버튼 두 개**가 되고,
                // VoiceOver 사용자도 자동 검증도 둘을 구분할 수 없다.
                // → 빵조각에는 **목적을 붙여** 이름이 다르도록 한다.
                .accessibilityLabel(i == 0 ? "보관함 최상위로 가기" : c.label)
            }
            Spacer(minLength: 4)
            Button { model.beginMkdir() } label: {
                Image(systemName: "folder.badge.plus").font(.system(size: 11))
            }
            .buttonStyle(.plain)
            .help("새 폴더")
            Button { model.loadTrash() } label: {
                Image(systemName: "trash").font(.system(size: 11))
            }
            .buttonStyle(.plain)
            .help("휴지통")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
    }

    /// **인라인 편집창** — 대상 경로 + 입력 + 실행.
    ///
    /// `canRun == false` 면 실행 버튼이 **비활성**이다.
    /// (빈 입력으로 서버를 불렀다 422 받는 것보다 못 누르게 하는 게 낫다)
    private func storageEditBar(_ e: AppModel.StorageEdit) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 5) {
                Image(systemName: "pencil.line").font(.system(size: 10))
                Text("\(e.kind.rawValue): \(e.path)").font(.system(size: 10.5))
                    .lineLimit(1).truncationMode(.middle)
                Spacer()
                Button { model.storageEdit = nil } label: {
                    Image(systemName: "xmark").font(.system(size: 8, weight: .bold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tertiary)
                .help("취소")
            }
            .foregroundStyle(.secondary)

            HStack(spacing: 5) {
                // **파괴 동작(`purge`/`restore`)은 여기 올 수 없다.**
                // `Kind` 에서 아예 뺐기 때문에 컴파일러가 막는다.
                // (이 분기가 있었다는 건 컨펌 우회로가 코드에 남아 있었다는 뜻)
                if e.kind == .move {
                    TextField("목적지 폴더", text: binding(\.destDir))
                        .textFieldStyle(.roundedBorder).font(.system(size: 11))
                } else {
                    TextField(placeholder(e.kind), text: binding(\.input))
                        .textFieldStyle(.roundedBorder).font(.system(size: 11))
                }
                Button { model.runStorageEdit() } label: {
                    Text("실행").font(.system(size: 11))
                }
                // **반드시 `model` 에서 읽는다.**
                //
                // `e` 는 함수 진입 시점의 **값 복사본**이라 타이핑해도 갱신되지 않는다.
                // 여기서 `e.canRun` 을 읽으면 "실행" 버튼이 **영영 비활성**이다 —
                // PR #28 의 "실행 버튼이 안 눌린다" 버그의 정체.
                .disabled(!(model.storageEdit?.canRun ?? false))
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.10))
    }

    private func placeholder(_ k: AppModel.StorageEdit.Kind) -> String {
        k == .rename ? "새 이름" : "폴더 이름"
    }

    /// **`model.storageEdit` 에 직접 바인딩한다.**
    ///
    /// ## 왜 이렇게 하는가
    ///
    /// 이전 실패의 핵심: 대상만 `model` 에 심고 입력은 `@State` 로 따로 받아
    /// **둘이 어긋났다.** 화면의 "실행" 가능 여부가 화면에 있는 입력값을 보지 않고
    /// 복사본을 봤기 때문에 **영영 눌리지 않았다.**
    ///
    /// **`model` 의 값 하나만 읽고 쓴다** — 어긋날 여지가 원천적으로 없다.
    private func binding(_ k: WritableKeyPath<AppModel.StorageEdit, String>) -> Binding<String> {
        Binding(get: { model.storageEdit?[keyPath: k] ?? "" },
                set: { model.storageEdit?[keyPath: k] = $0 })
    }

    /// **휴지통** — 복원과 영구 삭제.
    ///
    /// "휴지통으로 보내기"만 있고 되돌릴 방법이 없으면 그건 삭제가 아니라
    /// **데이터를 잃게 하는 UI** 다. 둘 다 있어야 의미가 있다.
    ///
    /// **복원/영구삭제 모두 컨펌을 거친다.**
    /// - 복원: 파일이 **루트로 돌아온다**(서버가 원래 위치를 기억하지 않는다).
    ///   사용자가 모르고 누르면 엉뚱한 곳에 파일이 생긴다 → 확인이 필요하다.
    /// - 영구삭제: **되돌릴 수 없다** → 반드시 확인이 필요하다.
    private var trashList: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("휴지통 \(model.trash.count)개").font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.secondary)
            ForEach(model.trash) { t in
                HStack(spacing: 6) {
                    Image(systemName: t.isDirectory ? "folder.fill" : "doc.fill")
                        .font(.system(size: 9.5)).foregroundStyle(.tertiary)
                    Text(t.name).font(.system(size: 11)).lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 2)
                    // **복원도 컨펌을 거친다** — 서버가 원래 위치를 기억하지 않아
                    // 보관함 루트로 돌아온다. 사용자가 그걸 모르면 "왜 다른 데 있지" 한다.
                    Button {
                        model.askConfirm(.init(kind: .restore(t.name)))
                    } label: { Text("복원").font(.system(size: 10.5)) }
                        .buttonStyle(.plain).foregroundStyle(Color.accentColor)
                        .accessibilityLabel("\(t.name) 복원, 보관함 맨 위로 돌아옴")
                    Button {
                        model.askConfirm(.init(kind: .purge(t.name)))
                    } label: { Text("영구 삭제").font(.system(size: 10.5)) }
                        .buttonStyle(.plain).foregroundStyle(Color.red)
                        .accessibilityLabel("\(t.name) 영구 삭제, 되돌릴 수 없음")
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(maxHeight: 110)
    }

    private func empty(_ title: String, tip: String?) -> some View {
        VStack(spacing: 4) {
            Text(model.phase == .discovering && title.contains("서버") ? "서버를 찾는 중…" : title)
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
            if let tip {
                Text(tip).font(.system(size: 11))
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }

    // MARK: - 통계

    /// 탭마다 의미가 다르다 — 항상 "다운로드 진행/시딩"을 보여주면
    /// 토렌트·보관함 탭에서 엉뚱한 숫자가 떠 보인다.
    private var stats: some View {
        HStack(spacing: 0) {
            switch model.selectedTab {
            case .downloads:
                stat("진행", "\(model.ongoingJobs.count)")
                // **"시딩" 을 "완료" 로 바꾼다.**
                //
                // 잡의 상태는 서버 `JobState` 가 `QUEUED/RUNNING/PAUSED/DONE/
                // FAILED/CANCELED` 여섯 개뿐이다. **`SEEDING` 이 없다.**
                // 즉 여기서 "시딩" 숫자는 **항상 0** 이었고, 고장 난 자리를
                // 고장 난 채로 화면에 내보내고 있었다.
                //
                // 끝난 잡을 목록에 보여주기로 했으니 **그 개수**가 실제로
                // 알고 싶은 숫자가 됐다. 접기 줄에도 같은 수가 나온다.
                stat("완료", "\(model.finishedJobs.count)")
                stat("여유", model.storageFree > 0 ? RelayClient.format(bytes: model.storageFree) : "—")
            case .torrents:
                stat("진행", "\(model.activeTorrents.count)")
                stat("전체", "\(model.torrents.count)")
                stat("완료", "\(model.torrents.filter { $0.isDone }.count)")
            case .storage:
                stat("항목", "\(model.storage.count)")
                stat("폴더", "\(model.storage.filter { $0.isDirectory }.count)")
                stat("여유", model.storageFree > 0 ? RelayClient.format(bytes: model.storageFree) : "—")
            }
        }
        .padding(.vertical, 8)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.system(size: 10.5)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 12, weight: .semibold)).monospacedDigit()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 하단

    private var footer: some View {
        VStack(spacing: 0) {
            // 서버 주소 + 탐색 결과 — "어디에 붙어 있는지"를 언제나 보이게
            HStack(spacing: 6) {
                Image(systemName: serverIcon)
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                Text(model.server?.displayAddress ?? "연결 안 됨")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(strategyText)
                    .font(.system(size: 10.5))
                    .foregroundStyle(model.phase.isConnected ? Color.green : Color.secondary)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 7)

            Divider().opacity(0.4)

            VStack(spacing: 0) {
                row("대시보드 열기", "arrow.up.forward.app", hot: true) { model.openDashboard() }
                row("설정…", "gearshape", hot: false) { onSettings() }
                row("DroidRelay 종료", "power", hot: false, quit: true) { onQuit() }
            }
            .padding(4)
        }
    }

    private var serverIcon: String {
        switch model.phase {
        case .connected: return "checkmark.circle.fill"
        case .discovering: return "arrow.triangle.2.circlepath"
        default: return "exclamationmark.circle"
        }
    }

    private var strategyText: String {
        switch model.phase {
        case .connected(let s): return s.rawValue == "manual" ? "수동" : "자동 발견 \(s.displayName)"
        case .discovering: return "탐색 중"
        case .failed: return "실패"
        case .idle: return "대기"
        }
    }

    private func row(_ title: String, _ icon: String, hot: Bool,
                     quit: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 11.5)).frame(width: 16)
                Text(title).font(.system(size: 12.5))
                Spacer()
                if hot {
                    Text("⌘⇧D").font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(quit ? Color.red : Color.primary)
        .padding(.horizontal, quit ? 0 : 3)
    }
}

extension DiscoveryStrategy { var isConnected: Bool { self != .manual } }

private extension AppModel.Phase {
    var isConnected: Bool { if case .connected = self { true } else { false } }
}

// MARK: - 잡 행

struct JobRow: View {
    let job: RelayClient.Job
    let onAction: (String) -> Void
    /// **삭제 — `DELETE /api/jobs/{id}`**
    ///
    /// `onAction` 과 **다른 경로**다. 잡의 일시정지/재개는
    /// `POST /api/jobs/{id}/{action}` 이지만 삭제는 `DELETE /api/jobs/{id}` 다.
    /// 같은 클로저로 보내면 서버가 400 으로 거절한다(실측).
    var onDelete: () -> Void = {}
    /// **받기 — `GET /file/{id}` 를 브라우저로 연다** (웹 1174행)
    var onDownload: () -> Void = {}
    /// **속도 제한 — `POST /api/jobs/{id}/limit`** (B/s 값)
    var onLimit: (Int) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                Text(job.isVideo ? "🎬" : (job.isSeeding ? "🌊" : "📦"))
                    .font(.system(size: 11))
                    .frame(width: 14)
                Text(job.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 4)
                if job.uploadedBps > 0 {
                    Text("▲\(job.upText)").font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                } else {
                    Text(job.speedText).font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.cyan)
                }
            }

            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(Color.green).frame(width: g.size.width * CGFloat(job.progress) / 100)
                }
            }
            .frame(height: 4)

            HStack(spacing: 8) {
                Text(job.isSeeding ? "시딩" : "\(job.progress)%")
                    .foregroundStyle(.secondary)
                Spacer()
                if !job.etaText.isEmpty {
                    Text("남은 \(job.etaText)").foregroundStyle(.tertiary)
                }
            }
            .font(.system(size: 10.5))
            .monospacedDigit()

            // **버튼은 상태에 따라 달라진다** (웹 1176~1180행과 동일).
            //
            // 이전에는 "일시정지/취소"를 항상 둘 다 띄웠다. 그래서
            // **일시정지된 잡에 "일시정지"가 떠서 눌러도 아무 일도 안 일어나는**
            // 상태가 고정이었다. 재개 경로가 화면에 없었다.
            // 규칙은 `ActionRules` 한 곳에 있다 (테스트 15건이 웹과 같은지 고정).
            HStack(spacing: 5) {
                // **받기 — 웹 1174행.** `DONE` 에만 나온다.
                //
                // 완료 잡을 목록에 보여주기로 했으니 **받아갈 방법도 있어야 한다.**
                // 없으면 "완료 목록"은 "이름만 있고 손댈 수 없는 목록"이 되고,
                // 그건 목록이 아니라 알림이다.
                //
                // `DONE` 밖에서 누르면 서버가 400 "아직 완료되지 않았습니다" 다
                // (JobRoutes 240행) → **누를 수 있는데 안 되는 버튼**이 된다.
                if ActionRules.jobDownloadable(job.state) {
                    small("📥 받기", .primary) { onDownload() }
                }
                ForEach(actions, id: \.self) { a in
                    if a == .videoNotice {
                        // 비디오는 서버가 일시정지를 400 으로 거절한다 —
                        // 웹도 버튼 대신 이 문구를 넣는다(1177행)
                        Text(ActionRules.label(a))
                            .font(.system(size: 10.5))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                    } else {
                        let act = a.rawAction
                        small(ActionRules.label(a), .primary) { onAction(act) }
                    }
                }
                // **삭제 — 웹 1180행과 동일하게 항상 보인다.**
                //
                // 이전에 `onAction("cancel")` 을 보냈다. 그런데 잡 경로에는
                // `cancel` 이 **없다**(서버는 pause·resume 만 받는다) → 400 "지원 없는 동작".
                // 즉 이 버튼은 **한 번도 동작한 적이 없다.**
                // 잡 삭제는 `DELETE /api/jobs/{id}` 라 별도 경로가 필요하다.
                if ActionRules.jobDeletable(job.state) {
                    small("삭제", .red) { onDelete() }
                }
                Spacer(minLength: 0)
                // **속도 제한 — 프리셋 드롭다운** (웹 1181~1184행)
                //
                // 이전엔 "초당 바이트" 숫자를 직접 입력하게 했다. 1MB/s 를
                // 고르려면 `1048576` 을 손으로 넣어야 했다 → 사실상 못 쓰는 기능.
                // 웹의 `SPEED_PRESETS` 를 그대로 쓴다(테스트가 웹과 같음을 고정).
                if ActionRules.jobLimitEditable(state: job.state, type: job.typeRaw) {
                    speedLimitPicker
                }
            }
            .padding(.top, 1)
        }
        .padding(8)
    }

    /// 속도 제한 선택기.
    ///
    /// **완료/취소된 잡에는 없다** — 이미 끝났으니 바꿀 수 없다(웹 1181행).
    /// **비디오 잡에도 없다** — FFmpeg 파이프라인이라 제한이 먹지 않는다.
    private var speedLimitPicker: some View {
        Picker("", selection: Binding(
            get: { SpeedPresets.kbps(fromBps: job.maxDownBps) },
            set: { k in onLimit(SpeedPresets.bps(fromKbps: k)) }
        )) {
            ForEach(SpeedPresets.options(currentBps: job.maxDownBps), id: \.kbps) { o in
                Text(o.label).tag(o.kbps)
            }
        }
        .labelsHidden()
        .pickerStyle(.menu)
        .controlSize(.mini)
        .font(.system(size: 10.5))
        .frame(maxWidth: 96)
        .help("속도 제한")
    }

    private var actions: [ActionRules.JobAction] {
        ActionRules.jobActions(state: job.state, type: job.typeRaw)
    }

    private func small(_ t: String, _ c: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 10.5))
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(.quaternary, in: Capsule())
                .foregroundStyle(c)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 토렌트 행

struct TorrentRow: View {
    let torrent: Torrent
    let onAction: (String) -> Void
    /// **삭제 — `DELETE /api/torrents/{id}`** (잡과 같은 이유로 별도 경로)
    var onDelete: () -> Void = {}
    /// **속도 제한 — `POST /api/torrents/{id}/limit`** (B/s)
    var onLimit: (Int) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                Image(systemName: torrent.isDone ? "checkmark.circle.fill" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 10.5))
                    .foregroundStyle(torrent.isDone ? Color.green : Color.accentColor)
                Text(torrent.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                if torrent.uploadBps > 0 {
                    Text("▲\(torrent.upText)").font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                } else {
                    Text(torrent.speedText).font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.cyan)
                }
            }

            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(torrent.isDone ? Color.green : Color.accentColor)
                        .frame(width: g.size.width * CGFloat(torrent.progress))
                }
            }
            .frame(height: 4)

            HStack(spacing: 8) {
                Text(torrent.stateLabel).foregroundStyle(.secondary)
                Text(torrent.sizeText).foregroundStyle(.tertiary)
                Spacer()
                // **시드/피어 — 웹 1313행과 동일: 둘 다 0 이면 아예 그리지 않는다.**
                if ActionRules.torrentShowsPeers(seeds: torrent.seeds, peers: torrent.peers) {
                    Text("▲\(torrent.seeds) ▼\(torrent.peers)").foregroundStyle(.tertiary)
                }
            }
            .font(.system(size: 10.5))
            .monospacedDigit()
            .lineLimit(1)

            // **ETA — 웹 1306행: `DOWNLOADING` + 속도 > 0 일 때만.**
            //
            // 속도가 0 인데 계산하면 "남은 0초" 또는 무한대가 나온다.
            // 일시정지한 토렌트에 "남은 12분" 이 떠 있으면 사실이 아니다.
            if ActionRules.torrentShowsEta(state: torrent.state, downloadBps: torrent.downloadBps) {
                Text(etaText)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.tertiary)
                    .monospacedDigit()
                    .lineLimit(1)
            }

            if !torrent.errorMessage.isEmpty {
                Text(torrent.errorMessage)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }

            // **버튼은 상태에 따라 달라진다** (웹 1325~1329행과 동일).
            //
            // 이전에는 일시정지/재개/삭제를 **항상 3개 다** 띄웠다.
            // → "다운로드 중" 인데 "재개" 가 떠서 눌러도 아무 일도 안 되는 상태.
            HStack(spacing: 5) {
                ForEach(actions, id: \.self) { a in
                    small(ActionRules.label(a), .primary) { onAction(a.rawAction) }
                }
                if ActionRules.torrentDeletable(state: torrent.state) {
                    small("삭제", .red) { onDelete() }
                }
                Spacer(minLength: 0)
                // **속도 제한 — 웹 1330행: 토렌트는 상태와 무관하게 항상**
                if ActionRules.torrentLimitEditable(state: torrent.state) {
                    speedLimitPicker
                }
            }
            .padding(.top, 1)
        }
        .padding(8)
    }

    private var actions: [ActionRules.JobAction] {
        ActionRules.torrentActions(state: torrent.state)
    }

    /// **남은 시간.** 계산 불가하면 빈 문자열 — 그러면 아예 안 보인다.
    ///
    /// `etaText` 가 "∞" 같은 값을 내놓으면 화면에 "남은 ∞" 가 찍힌다.
    /// **표시할 수 없으면 표시하지 않는 것**이 정답이다.
    private var etaText: String {
        let remain = torrent.totalSize - torrent.downloadedSize
        guard torrent.downloadBps > 0, remain > 0 else { return "" }
        let sec = remain / torrent.downloadBps
        if sec < 60 { return "남은 \(sec)초" }
        if sec < 3600 { return "남은 \(sec / 60)분" }
        return "남은 \(sec / 3600)시간 \(sec % 3600 / 60)분"
    }

    private var speedLimitPicker: some View {
        Picker("", selection: Binding(
            get: { SpeedPresets.kbps(fromBps: torrent.maxDownBps) },
            set: { k in onLimit(SpeedPresets.bps(fromKbps: k)) }
        )) {
            ForEach(SpeedPresets.options(currentBps: torrent.maxDownBps), id: \.kbps) { o in
                Text(o.label).tag(o.kbps)
            }
        }
        .labelsHidden()
        .pickerStyle(.menu)
        .controlSize(.mini)
        .font(.system(size: 10.5))
        .frame(maxWidth: 96)
        .help("속도 제한")
    }

    private func small(_ t: String, _ c: Color, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 10.5))
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(.quaternary, in: Capsule())
                .foregroundStyle(c)
        }
        .buttonStyle(.plain)
    }
}

/// 보관함 한 줄.
///
/// **행을 누르면 폴더면 내려가고, 오른쪽 메뉴에서 조작한다.**
///
/// ## 왜 조작은 행 전체에 걸지 않나
///
/// 352pt 폭에 이름·크기·날짜·버튼 3개를 다 넣으면 이름이 두 글자만 보인다.
/// 그리고 **파괴 동작(휴지통)을 한 번의 클릭으로 단추에 걸어두면 안 된다** —
/// 사고의 대가가 크다. 그래서 조작은 메뉴로 숨기고, `누르면 들어가기` 만 직접 누른다.
///
/// **단, 이름 영역은 좁혀 두지 않았다** — 사용자가 누르는 곳이 반응 없는 곳이 되면
/// 그게 더 큰 문제다. 이름·아이콘·크기 어느 쪽을 눌러도 폴더로 들어간다.
struct StorageRow: View {
    let entry: StorageEntry
    /// **폴더를 열기** — 파일이면 호출되지 않는다.
    let onOpen: () -> Void
    /// **실시간 재생** — `▶` 가 뜰 때만 눌린다.
    let onPlay: () -> Void
    /// **Mac 으로 내려받기** — `받기` 가 뜰 때만 눌린다.
    let onDownload: () -> Void
    let onRename: () -> Void
    let onMove: () -> Void
    let onTrash: () -> Void

    /// **▶ 가 뜰 파일인가** — `mp4`·`mp3` 만 (IINA 우선, 없으면 브라우저).
    private var playable: Bool { ActionRules.isStreamPlayable(entry.name) }

    /// 아이콘 + 이름 + 크기/날짜
    private var entryLabel: some View {
        HStack(spacing: 7) {
            Image(systemName: entry.isDirectory ? "folder.fill" : mediaIcon)
                .font(.system(size: 10.5))
                .foregroundStyle(entry.isDirectory ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.name)
                    .font(.system(size: 12.5))
                    .lineLimit(1).truncationMode(.middle)
                HStack(spacing: 6) {
                    Text(entry.sizeText)
                    if !entry.modifiedText.isEmpty { Text(entry.modifiedText) }
                }
                .font(.system(size: 10.5))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    /// **재생되는 파일은 영화 아이콘** — ▶ 가 있는 줄임을 모양도로 알린다.
    private var mediaIcon: String { playable ? "play.rectangle.fill" : "doc.fill" }

    var body: some View {
        HStack(spacing: 7) {
            // **폴더는 이름·크기·날짜 어느 쪽을 눌러도 들어간다.**
            //
            // ## 왜 이름을 버튼으로 만들었나
            //
            // 원래는 오른쪽의 작은 `▸` **하나만** 버튼이었다. 폴더 이름과 아이콘은
            // 그냥 글자였다. 그래서 **사용자가 자연스럽게 누르는 곳(폴더 이름)이
            // 아무 일도 안 일어나는** 상태가 되었다.
            //
            // 실제로 확인했다 — 폴더 이름은 `AXStaticText` 로 노출되고
            // `▸` 만 `AXButton` 이었다. "누르면 들어가는데 왜 안 들어가나" 의 정체.
            //
            // 파일 탐색기의 관습대로 **이름을 누르면 들어간다** 를 지킨다.
            //
            // ## 왜 파일의 이름은 버튼이 아닌가
            //
            // 처음엔 `.disabled(!isDirectory)` 로 파일의 이름도 버튼으로 뒀다.
            // 그러면 **누를 수 있어 보이면서 실제로는 못 누르는** 줄이
            // AX 에 "[비활성]" 으로 남는다. 누르는 데 실패하는 버튼은
            // 없는 것보다 나쁘다. 그래서 **파일의 이름은 글자 그대로** 둔다 —
            // 동작은 오른쪽 `▶`/`받기` 가 맡는다.
            if entry.isDirectory {
                Button(action: onOpen) { entryLabel }
                    .buttonStyle(.plain)
                    .help("열기")
            } else {
                entryLabel
            }

            if entry.isDirectory {
                // **폴더는 열기가 유일한 기본 동작.** 모양(▸)도 같이 줘서
                // "누를 수 있다" 를 미리 알린다 — 안 그러면 클릭 가능한 줄로 안 보인다.
                Button(action: onOpen) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .help("열기")
            } else if playable {
                // **진짜 재생되는 파일에만 ▶ 를 띄운다** — `mp4`·`mp3` 뿐.
                //
                // `.mkv` `.avi` `.dmg` 에 ▶ 를 주면 사용자가 눌렀을 때
                // 재생 앱이 **검은 화면**을 낸다 → "앱이 고장났다" 는 인상.
                // 누를 수 있는데 안 되는 버튼은 없는 것보다 나쁘다.
                Button(action: onPlay) {
                    Image(systemName: "play.fill")
                        .font(.system(size: 9.5))
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .help("재생 — IINA 로 실시간으로 봅니다(없으면 브라우저)")

                // **재생할 수 있는 파일도 반드시 받아야 할 수 있다.**
                //
                // ## 왜 ▶ 만으로는 부족했나
                //
                // 처음엔 ▶ 만 뒀다. 그랬더니 **"보관함에 있는 동영상을 내 Mac 에
                // 가져올 수 없다"** 는 말이 성립했다. 스트리밍은 보기만 가능하고
                // 파일은 서버 안에 남는다. 오프라인으로 보존할 방법이 없다.
                //
                // 더 어이없는 건, **재생 못하는 `.mkv` 에는 `받기` 가 있는데
                // 재생하는 `.mp4` 에는 없다는** 반대 상태가 됐다.
                // 잘 되는 파일만 못 받는 셈이다.
                //
                // → **재생과 받기를 나란히 둔다.** 둘 다 "가져오기" 라 같은 급의 동작이다.
                Button(action: onDownload) {
                    Text("받기")
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .help("Mac 으로 내려받기")
            } else {
                // **재생 못 하는 형식은 ▶ 대신 받기만 둔다.**
                //
                // 내려받으면 로컬 파일이 되고 **사용자 기본 프로그램(IINA) 이 열어 준다.**
                // 막다른 길이 없도록 — ▶ 는 진짜 재생되는 파일에만.
                // (`m4v` `mov` `webm` 도 여기 걸린다. 재생은 되지만 ▶ 는 두지 않는다.)
                Button(action: onDownload) {
                    Text("받기")
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.plain)
                .help("Mac 으로 내려받기")
            }

            Menu {
                // **가져오기(재생·받기)를 맨 위에 둔다.**
                //
                // 이 메뉴는 "조작" 이라는 한마디로 이름이 붙어 있고, 원래는
                // 이름 변경 / 이동 / 휴지통 — **전부 보관함 안을 고치는 동작**이었다.
                // 여기에 재생과 받기를 아래에 붙이면, **무해한 "가져오기" 가
                // 가장 위험한 "휴지통" 바로 아래에 나란히 놓인다.** 순서가 틀렸다.
                //
                // 그리고 재생 가능한 파일은 행에 ▶ 가 있어서 **메뉴의 재생은 중복**이다.
                // 중복은 의도했다 — **▶ 를 눌러 재생만 하고, 저장은 못 하는 상태를
                // 만들지 않으려는 것**이 목적이다. 동영상도 `받기` 로 Mac 에 저장돼야 한다.
                //
                // 재생 불가 형식은 행에 `받기` 가 이미 있으므로 메뉴에 다시 넣지 않는다.
                if playable {
                    Button { onPlay() } label: { Label("재생", systemImage: "play.fill") }
                    Button { onDownload() } label: { Label("받기", systemImage: "arrow.down.circle") }
                    Divider()
                }

                Button { onRename() } label: { Label("이름 변경", systemImage: "pencil") }
                Button { onMove() } label: { Label("이동", systemImage: "folder") }
                Divider()
                // **가장 위험한 조작이므로 맨 아래에 두고 빨갛다.**
                Button { onTrash() } label: { Label("휴지통", systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.tertiary)
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help("조작")
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}
