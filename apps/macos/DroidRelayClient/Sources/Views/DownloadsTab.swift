import SwiftUI

/// 다운로드 탭 — URL 추가 + 잡 목록 + 받기
struct DownloadsTab: View {
    @Environment(AppState.self) private var st
    @State private var urlInput = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                TextField("다운로드 URL 입력", text: $urlInput)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12))
                    .disabled(!st.isConnected)
                    .onSubmit { submit() }
                Button("추가", action: submit)
                    .disabled(!st.isConnected || urlInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)

            if st.jobs.isEmpty {
                EmptyHint(icon: "arrow.down.circle",
                          text: st.isConnected ? "URL을 추가하면 휴대폰이 대신 받아줍니다." : "서버에 연결하면 다운로드를 관리할 수 있어요.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(st.jobs) { job in
                            JobRow(job: job)
                        }
                        TransferRows()
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
                }
            }
        }
    }

    private func submit() {
        let u = urlInput
        guard !u.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        urlInput = ""
        Task { await st.addDownload(u) }
    }
}

struct JobRow: View {
    @Environment(AppState.self) private var st
    let job: Job

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(job.filename)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                StateBadge(state: job.state, error: job.errorMessage)
            }

            ProgressView(value: job.isDone ? 1 : max(job.progress, 0.02))
                .tint(progressColor)

            HStack(spacing: 8) {
                if job.isRunning {
                    Text("\(Fmt.pct(job.progress)) · \(Fmt.speed(job.speedBps))")
                        .foregroundStyle(.secondary)
                } else if job.state == "DONE" {
                    Text("완료 · \(Fmt.bytes(job.totalBytes))")
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(Fmt.pct(job.progress)) · \(Fmt.bytes(job.downloadedBytes))")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                rowButtons
            }
            .font(.caption)
        }
        .padding(9)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var rowButtons: some View {
        if job.isRunning {
            Button(job.state == "PAUSED" ? "재개" : "정지") { Task { await st.toggleJob(job) } }
                .buttonStyle(.link)
                .font(.caption)
        }
        if job.isDone && !st.transfers.isActive(job.id) {
            Button {
                st.receiveJob(job)
            } label: {
                Label("받기", systemImage: "arrow.down.to.line.compact")
            }
            .buttonStyle(.link)
            .font(.caption)
        }
        Button("삭제", role: .destructive) { Task { await st.removeJob(job) } }
            .buttonStyle(.link)
            .font(.caption)
    }

    private var progressColor: Color {
        switch job.state {
        case "RUNNING": return .blue
        case "DONE": return .green
        case "FAILED": return .red
        case "PAUSED": return .orange
        default: return .gray
        }
    }
}

/// 진행 중인 ⬇ 받기 항목 행들
struct TransferRows: View {
    @Environment(AppState.self) private var st

    var body: some View {
        ForEach(st.transfers.actives.filter { !$0.finished || $0.failed }) { t in
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Label(t.name, systemImage: t.failed ? "exclamationmark.triangle" : "arrow.down.circle.fill")
                        .font(.system(size: 12))
                        .lineLimit(1)
                    Spacer()
                    if !t.finished {
                        Text(t.total > 0
                             ? "\(Fmt.pct(Double(t.received) / Double(max(t.total, 1))))"
                             : Fmt.bytes(t.received))
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
                if t.failed {
                    Text("받기 실패 — 폴더 권한 또는 연결을 확인해 주세요.")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                if !t.finished {
                    ProgressView(value: t.total > 0 ? Double(t.received) / Double(max(t.total, 1)) : nil)
                        .tint(.teal)
                }
                if t.finished && !t.destPath.isEmpty {
                    HStack {
                        Spacer()
                        Button("Finder에서 보기") {
                            st.revealInFinder(t.destPath)
                        }
                        .buttonStyle(.link)
                        .font(.caption)
                    }
                }
            }
            .padding(9)
            .background(Color.teal.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
        }
    }
}

// MARK: - 공용 소품

struct StateBadge: View {
    let state: String
    var error: String?

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(bgColor.opacity(0.15), in: Capsule())
            .foregroundStyle(bgColor)
    }

    private var text: String {
        switch state {
        case "RUNNING": return "진행"
        case "QUEUED": return "대기"
        case "PAUSED": return "정지"
        case "DONE": return "완료"
        case "FAILED": return error ?? "실패"
        default: return state
        }
    }

    private var bgColor: Color {
        switch state {
        case "RUNNING": return .blue
        case "QUEUED": return .gray
        case "PAUSED": return .orange
        case "DONE": return .green
        case "FAILED": return .red
        default: return .gray
        }
    }
}

struct EmptyHint: View {
    let icon: String
    let text: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 34))
                .foregroundStyle(.tertiary)
            Text(text)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
