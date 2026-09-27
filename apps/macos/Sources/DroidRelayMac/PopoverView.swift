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
            list
            Divider().opacity(0.5)
            stats
            Divider().opacity(0.5)
            footer
        }
        .frame(width: 352)
    }

    // MARK: - 헤더

    private var header: some View {
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
    }

    // MARK: - 목록

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                if model.activeJobs.isEmpty && model.seedingJobs.isEmpty {
                    empty
                }
                ForEach(model.activeJobs) { j in
                    JobRow(job: j) { act in model.control(j.id, act) }
                }
                ForEach(model.seedingJobs) { j in
                    JobRow(job: j) { act in model.control(j.id, act) }
                }
            }
            .padding(5)
        }
        .frame(maxHeight: 300)
    }

    private var empty: some View {
        VStack(spacing: 4) {
            Text(model.phase == .discovering ? "서버를 찾는 중…" : "진행 중인 작업이 없습니다")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
            if model.phase == .failed {
                Text("설정에서 주소를 확인하세요").font(.system(size: 11))
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
    }

    // MARK: - 통계

    private var stats: some View {
        HStack(spacing: 0) {
            stat("진행", "\(model.activeJobs.count)")
            stat("시딩", "\(model.seedingJobs.count)")
            stat("여유", model.storageFree > 0 ? RelayClient.format(bytes: model.storageFree) : "—")
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

            HStack(spacing: 5) {
                if job.isSeeding {
                    small("시딩 중지", .orange) { onAction("cancel") }
                } else {
                    small("일시정지", .primary) { onAction("pause") }
                    small("취소", .red) { onAction("cancel") }
                }
            }
            .padding(.top, 1)
        }
        .padding(8)
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
