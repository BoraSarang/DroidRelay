package com.borasarang.droidrelay.relay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.borasarang.droidrelay.R
import com.borasarang.droidrelay.plugin.PluginContract
import com.borasarang.droidrelay.plugin.PluginLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.borasarang.droidrelay.relay.TorrentRepository
import com.borasarang.droidrelay.relay.TorrentState
import kotlinx.coroutines.launch

class RelayService : Service() {

    private val TAG = "Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var server: RelayServer? = null
    private val serverLock = Any()
    private var currentPort: Int = -1
    private var currentHttpsPort: Int = -1
    private var currentHttpsEnabled: Boolean = true
    private var notificationsOn = true
    private var networkMonitor: NetworkMonitor? = null
    private var torrentEngine: TorrentEngine? = null
    private var rssManager: RssFeedManager? = null
    private var guardDaemon: GuardDaemon? = null
    private var webhookManager: WebhookManager? = null
    private var tunnelManager: TunnelManager? = null
    private var schedulerManager: SchedulerManager? = null
    private var speedScheduleManager: SpeedScheduleManager? = null
    private var isForeground = false
    /** 알림 빌더 전담 — 생명주기와 분리 (Phase 2). 호출 순서·문구·ID 그대로 위임한다 */
    private val notifs by lazy { ServiceNotifications(this) }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i(TAG, "서비스 생성 시작")
        // 채널 생성이 최초 알림 게시보다 앞서야 Android 8+에서 무시되지 않음
        notifs.createChannel()
        // FGS 승격을 가장 먼저 시도 — startForegroundService fallback 경로의 5초 의무 창 확보
        startInForeground()

        val settingsRepo = SettingsRepository.get(applicationContext)
        val engine = RelayApp.get(applicationContext)
        val persistence = JobsPersistence(applicationContext)
        val torrentEng = RelayApp.getTorrent(applicationContext)
        torrentEngine = torrentEng
        DebugLogger.i(TAG, "초기화 시작 engine=${engine::class.simpleName} torrent=${torrentEng::class.simpleName}")

        // 설정 스키마 마이그레이션 (v0.22 Phase A) — 실패해도 기동 계속
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { settingsRepo.ensureMigrated() }

        // 트래픽 통계 원장 로드 (v0.37)
        runCatching { TrafficLedger.init(applicationContext) }
            .onFailure { DebugLogger.e(TAG, "트래픽 원장 로드 실패(무시하고 계속)", it) }
        // 통계 스냅샷 저장소 + 부트 기록 (v0.39 P2)
        runCatching {
            StatsSnapshots.init(applicationContext)
            StatsSnapshots.recordBoot()
        }.onFailure { DebugLogger.e(TAG, "스냅샷 저장소 로드 실패(무시하고 계속)", it) }

        // TorrentEngine 시작 (네이티브 세션 초기화 — 메인스레드 I/O 차단 방지)
        // 마스터 토글이 꺼져 있으면 세션·포트·폴링 자체를 띄우지 않는다
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (settingsRepo.firstBlocking().torrentEnabled) {
                torrentEng.start()
                DebugLogger.i(TAG, "TorrentEngine 시작 완료")
            } else {
                DebugLogger.i(TAG, "TorrentEngine 시작 스킵 (토렌트 기능 꺼짐)")
            }
        }

        // 토렌트 마스터 토글 감시 — OFF면 정지+파기(재ON 시 새 인스턴스), ON이면 기동
        scope.launch {
            settingsRepo.settings
                .map { it.torrentEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) {
                        val eng = torrentEngine
                        if (eng != null) {
                            runCatching { eng.stop() }
                            torrentEngine = null
                            RelayApp.resetTorrent()
                            DebugLogger.i(TAG, "토렌트 기능 OFF → 세션 정지·파기")
                        }
                    } else {
                        if (torrentEngine == null) {
                            val eng = RelayApp.getTorrent(applicationContext)
                            torrentEngine = eng
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                eng.start()
                                DebugLogger.i(TAG, "토렌트 기능 ON → 세션 시작")
                            }
                        }
                    }
                }
        }

        // RSS 피드 매니저 시작
        val rssManager = RssFeedManager(applicationContext)
        this.rssManager = rssManager
        rssManager.start()
        DebugLogger.i(TAG, "RSS 피드 매니저 시작 완료")

        // 가드 데몬 시작 (Phase 2.4)
        val guard = GuardDaemon(applicationContext, settingsRepo)
        guardDaemon = guard
        guard.start()
        DebugLogger.i(TAG, "가드 데몬 시작 완료")

        // 가드 상태 변경 시 다운로드 일시정지/재개
        // 이 콜백은 GuardDaemon 의 IO 스코프에서 호출되고 호출부에 예외 처리가 없다 —
        // 항목 하나가 throw 하면 프로세스 사망이므로 항목별로 격리한다.
        guard.onThrottleChange = { throttled, reason ->
            DebugLogger.i(TAG, "가드 상태 변경 throttled=$throttled reason=$reason")
            if (throttled) {
                // 실행 중인 다운로드 일시정지 (비디오(FFmpeg) 제외 — 아래에서 가드용 중단 처리)
                JobsRepository.jobs.value.forEach { j ->
                    if (j.state == JobState.RUNNING && j.type != "video") {
                        runCatching { engine.pause(j.id) }
                    }
                }
                // 비디오(FFmpeg)는 일시정지 미지원이라 가드용 FAILED로 중단 (재시도 가능)
                JobsRepository.jobs.value.forEach { j ->
                    if (j.type == "video" && (j.state == JobState.RUNNING || j.state == JobState.QUEUED)) {
                        runCatching { RelayApp.getVideo(applicationContext).cancelForGuard(j.id, reason) }
                    }
                }
                // 토렌트도 함께 스로틀 (결함 #5) + 가드 억제 진입 (T-1101):
                // 업로드 최소·DHT/PEX 정지로 모뎀 TX를 내려 발열 사이클을 끊는다.
                // 무조건 시도 원칙: 토렌트를 멈추지는 않는다 — 멈추는 길은 삭제뿐.
                // 억제 중에도 다운로드는 계속되고, 해제되면 속도가 복귀한다.
                runCatching { torrentEng.setGuardSuppressed(true) }
            } else {
                // 가드가 pause한 잡(PAUSED)과 실패 잡을 재개 — retryFailed만으로는 일시정지가 풀리지 않음
                runCatching { engine.retryFailed() }
                JobsRepository.jobs.value.forEach { j ->
                    if (j.state == JobState.PAUSED) {
                        runCatching { engine.resume(j.id) }
                    }
                }
                // 가드 억제 해제 + staggered resume (T-1101):
                // 일괄 재개하면 DHT·트래커·피어 재접속이 동시에 터져 모뎀에 즉시 과부하가 걸린다.
                // 3초 간격으로 깨워 thundering herd를 방지한다.
                runCatching { torrentEng.setGuardSuppressed(false) }
                scope.launch {
                    TorrentRepository.all()
                        .filter { shouldAutoResume(it.state, it.progress) }
                        .forEachIndexed { i, t ->
                            if (i > 0) delay(GUARD_RESUME_STAGGER_MS)
                            runCatching { torrentEng.resume(t.id) }
                        }
                }
            }
        }

        // 웹훅 매니저 시작 (Phase 2.2)
        val webhook = WebhookManager(applicationContext)
        webhookManager = webhook
        DebugLogger.i(TAG, "웹훅 매니저 시작 완료")

        // 터널 매니저 시작 (Phase 2.3)
        val tunnel = TunnelManager(applicationContext)
        tunnelManager = tunnel
        DebugLogger.i(TAG, "터널 매니저 시작 완료")

        // ⓪ 터널 설정 감시 — 이전에는 TunnelManager.start() 호출이 어디에도 없어
        // "터널 사용" 스위치와 프로바이더 선택이 저장만 되고 아무 일도 일어나지 않았다.
        // 이제 설정을 실제로 구독해 켜면 시작·끄면 중지한다.
        scope.launch {
            settingsRepo.settings
                .map { it.tunnelEnabled to it.tunnelProvider }
                .distinctUntilChanged()
                .collect { (enabled, provider) ->
                    if (!enabled) {
                        runCatching { tunnel.stop() }
                        DebugLogger.i(TAG, "터널 비활성 — 중지됨")
                    } else {
                        val r = tunnel.start(settingsRepo.firstBlocking())
                        if (r.success) {
                            DebugLogger.i(TAG, "터널 시작 성공 provider=$provider ${r.message}")
                        } else {
                            // 바이너리 미설치 등으로 실패 — 스위치는 켜진 채 유지하고 사유를 로그로 남긴다
                            DebugLogger.w(TAG, "터널 시작 실패 provider=$provider: ${r.message}")
                        }
                    }
                }
        }

        // 스케줄러 시작 (Phase 3)
        val scheduler = SchedulerManager(applicationContext)
        schedulerManager = scheduler
        scheduler.start(settingsRepo)
        DebugLogger.i(TAG, "스케줄러 시작 완료")

        // 속도 스케줄 시작 (v0.24) — 가드 스로틀 공유
        val speedSched = SpeedScheduleManager(applicationContext) { guardDaemon?.isThrottled == true }
        speedScheduleManager = speedSched
        speedSched.start()
        DebugLogger.i(TAG, "속도 스케줄 시작 완료")

        // ① 설정 감시: 포트/HTTPS 변경 → 서버 재시작 / 알림 토글 / 서버 스냅샷 갱신
        scope.launch {
            var lastPorts = Triple(-1, -1, true)
            settingsRepo.settings.collectLatest { s ->
                notificationsOn = s.notifications
                currentPort = s.port
                currentHttpsPort = s.httpsPort
                currentHttpsEnabled = s.httpsEnabled
                if (server == null) {
                    // 웹 UI 끔 모드: 서비스(다운로드 엔진·토렌트·스케줄러)는 유지, Ktor만 미기동
                    if (!s.webServerEnabled) {
                        settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, url = null))
                        lastPorts = Triple(s.port, s.httpsPort, s.httpsEnabled)
                        return@collectLatest
                    }
                    synchronized(serverLock) {
                        if (server == null) {
                            try {
                                val rs = RelayServer(applicationContext, s.port, s.httpsPort)
                                rs.updateSettings(s)
                                if (rs.start()) {
                                    server = rs
                                    val url = lanAddress()?.let { "http://$it:${s.port}" }
                                    settingsRepo.updateServerState(ServerState(running = true, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, url = url))
                                } else {
                                    settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, error = "서버 기동 실패"))
                                }
                            } catch (e: Exception) {
                                DebugLogger.e(TAG, "서버 기동 실패: ${e.message}", e)
                                settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, error = "서버 기동 실패: ${e.message}"))
                            }
                        }
                    }
                    lastPorts = Triple(s.port, s.httpsPort, s.httpsEnabled)
                } else {
                    // 웹 UI를 끄면 기동 중인 Ktor를 내리고 상태만 갱신 (다운로드 엔진은 유지)
                    if (!s.webServerEnabled) {
                        DebugLogger.i(TAG, "웹 UI 끔 설정 — Ktor 정지, 다운로드 서비스 유지")
                        synchronized(serverLock) {
                            server?.stop()
                            server = null
                            settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, url = null))
                        }
                        lastPorts = Triple(s.port, s.httpsPort, s.httpsEnabled)
                        notifs.updateRunningNotification(s.port)
                        return@collectLatest
                    }
                    server?.updateSettings(s)
                    if (s.port != lastPorts.first || s.httpsPort != lastPorts.second || s.httpsEnabled != lastPorts.third) {
                        DebugLogger.i(TAG, "[FEATURE] HTTPS 설정 변경 감지 ${lastPorts.first}/${lastPorts.second}/${lastPorts.third} → ${s.port}/${s.httpsPort}/${s.httpsEnabled} — 서버 재시작")
                        synchronized(serverLock) {
                            server?.stop()
                            try {
                                val rs = RelayServer(applicationContext, s.port, s.httpsPort)
                                rs.updateSettings(s)
                                if (rs.start()) {
                                    server = rs
                                    val url = lanAddress()?.let { "http://$it:${s.port}" }
                                    settingsRepo.updateServerState(ServerState(running = true, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, url = url))
                                } else {
                                    server = null
                                    settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, error = "서버 재시작 실패"))
                                }
                            } catch (e: Exception) {
                                DebugLogger.e(TAG, "서버 재시작 실패: ${e.message}", e)
                                settingsRepo.updateServerState(ServerState(running = false, port = s.port, httpsPort = s.httpsPort, httpsEnabled = s.httpsEnabled, error = "서버 재시작 실패: ${e.message}"))
                            }
                        }
                        lastPorts = Triple(s.port, s.httpsPort, s.httpsEnabled)
                        notifs.updateRunningNotification(s.port)
                    }
                }
            }
        }

        // ② watchdog — 서버 헬스체크 주기 수행 (이슈 1의 24시간 안정성)
        scope.launch {
            var lastInterval = -1
            var healthyCount = 0
            var failStreak = 0
            settingsRepo.settings.collectLatest { s ->
                val intervalMs = s.watchdogIntervalSec * 1000L
                while (true) {
                    delay(intervalMs)
                    if (s.watchdogIntervalSec != lastInterval) {
                        lastInterval = s.watchdogIntervalSec
                        DebugLogger.i(TAG, "watchdog 주기 ${s.watchdogIntervalSec}초 시작")
                    }
                    val current = server
                    if (current == null) {
                        // 웹 UI 끔 모드면 watchdog도 기동하지 않음 (다운로드 서비스는 정상 상태)
                        if (!settingsRepo.firstBlocking().webServerEnabled) continue
                        // 서버가 아예 없으면 새로 기동
                        DebugLogger.w(TAG, "watchdog: 서버가 없음 → 기동 시도")
                        healthyCount = 0
                        runCatching {
                            synchronized(serverLock) {
                                if (server == null) {
                                    val s2 = settingsRepo.firstBlocking()
                                    if (!s2.webServerEnabled) {
                                        settingsRepo.updateServerState(ServerState(running = false, port = s2.port, httpsPort = s2.httpsPort, httpsEnabled = s2.httpsEnabled, url = null))
                                    } else {
                                        val rs = RelayServer(applicationContext, s2.port, s2.httpsPort)
                                        rs.updateSettings(s2)
                                        if (rs.start()) {
                                            server = rs
                                            val url = lanAddress()?.let { "http://$it:${s2.port}" }
                                            settingsRepo.updateServerState(ServerState(running = true, port = s2.port, httpsPort = s2.httpsPort, httpsEnabled = s2.httpsEnabled, url = url))
                                        } else {
                                            settingsRepo.updateServerState(ServerState(running = false, port = s2.port, httpsPort = s2.httpsPort, httpsEnabled = s2.httpsEnabled, error = "서버 기동 실패"))
                                        }
                                    }
                                }
                            }
                        }.onFailure { e ->
                            DebugLogger.e(TAG, "watchdog 서버 기동 실패: ${e.message}")
                        }
                        continue
                    }
                    if (current.isHealthy()) {
                        healthyCount++
                        failStreak = 0
                        if (healthyCount == 1) DebugLogger.d(TAG, "watchdog: 서버 정상")
                    } else {
                        healthyCount = 0
                        failStreak++
                        // 대용량 전송 중에는 헬스체크가 밀릴 수 있음 — 재시작하면 전송이 끊기므로 연기
                        val active = TransferTracker.count
                        if (active > 0) {
                            DebugLogger.w(TAG, "watchdog: 서버 무응답이나 전송 중(${active}건) → 재시작 연기")
                            continue
                        }
                        // 1회성 지터에 재시작하지 않음 — 연속 실패 시에만
                        if (failStreak < WATCHDOG_FAIL_STREAK) {
                            DebugLogger.w(TAG, "watchdog: 서버 무응답 ${failStreak}회째 → ${WATCHDOG_FAIL_STREAK}회 연속 시 재시작")
                            continue
                        }
                        failStreak = 0
                        DebugLogger.w(TAG, "watchdog: 서버 무응답 ${WATCHDOG_FAIL_STREAK}회 연속 → 재시작")
                        // 재시작은 그 자체로 실패할 수 있다(FFmpeg 세션 cancel 등). 예외가 튀면
                        // 이 while 루프 — 즉 재시작을 담당하는 watchdog 자체가 죽는다.
                        runCatching { current.restart() }
                            .onFailure { DebugLogger.e(TAG, "watchdog 재시작 실패: ${it.message}") }
                    }
                }
            }
        }

        // ②-2 유휴 자동 정지 — 작업·전송·토렌트 활동이 N분 없으면 서비스 자정지 (절전).
        // completionAction(stop_server)이 "활성→유휴 전이" 시점에 1회 끊는다면, 이것은 시간 기반이다.
        // 시딩 중에는 절대 정지하지 않는다 (isIdleForAutoStop) — 의도한 공유가 조용히 깨지면 안 된다.
        scope.launch {
            var idleSince: Long? = null
            while (true) {
                delay(IDLE_CHECK_MS)
                val s = runCatching { settingsRepo.firstBlocking() }.getOrNull() ?: continue
                val timeoutMin = s.idleTimeoutMin
                if (timeoutMin <= 0) {
                    idleSince = null
                    continue
                }
                // 예약 다운로드가 걸려 있으면 정지하지 않는다 —
                // 반복 재등록(scheduleNext)은 이 서비스의 SchedulerManager가 담당하므로,
                // 정지하면 cron 1회 발화 뒤 예약이 끊긴다. 발화 자체는 JobScheduler가 살리지만 반복이 안 된다.
                if (s.scheduleEnabled && CronParser.isValid(s.scheduleCron)) {
                    idleSince = null
                    continue
                }
                if (isIdleNow()) {
                    val now = System.currentTimeMillis()
                    if (idleSince == null) idleSince = now
                    if (now - idleSince >= timeoutMin * 60_000L) {
                        DebugLogger.i(TAG, "유휴 ${timeoutMin}분 경과 → 서비스 자동 정지")
                        stop(applicationContext)
                        break
                    }
                } else {
                    idleSince = null
                }
            }
        }

        // ② 작업 상태 → 완료/실패 알림 + 진행바 갱신 (T-105)
        scope.launch {
            var lastNotifUpdate = 0L
            var lastSaveAt = 0L
            var last: Map<String, JobState> = emptyMap()
            val lastFailReason = HashMap<String, String>()
            JobsRepository.jobs.collectLatest { jobs ->
                // 영구 저장 디바운스 10초 (T-842) — 상태 전이 시 즉시, 진행률 갱신은 10초 간격
                val now = System.currentTimeMillis()
                val current = jobs.associate { it.id to it.state }
                if (current.any { (id, st) -> last[id] != st } || now - lastSaveAt >= SAVE_DEBOUNCE_MS) {
                    lastSaveAt = now
                    persistence.save(jobs)
                }

                if (notificationsOn) {
                    jobs.forEach { j ->
                        if (last[j.id] == JobState.RUNNING && j.state == JobState.DONE) {
                            DebugLogger.i(TAG, "완료 알림 '${j.filename}'")
                            notifs.notify(j.id.hashCode(), getString(R.string.notif_done_title), j.filename, done = true)
                            // T-1095 플러그인 이벤트 (연동 허용 시, 알림 전이와 같은 지점)
                            scope.launch {
                                val allowed = runCatching { settingsRepo.settings.first().pluginAllowed }.getOrDefault(true)
                                if (allowed) PluginLog.event(PluginContract.eventDownloadComplete(j.id, j.filename, j.downloadedBytes))
                            }
                            // 웹훅 콜백 전송 (Phase 2.2)
                            scope.launch {
                                val s = try { settingsRepo.settings.first() } catch (_: Exception) { return@launch }
                                webhookManager?.send("download_complete", org.json.JSONObject().apply {
                                    put("id", j.id); put("filename", j.filename); put("url", j.url)
                                    put("downloadedBytes", j.downloadedBytes); put("totalBytes", j.totalBytes)
                                }, s)
                            }
                        }
                        val prevState = last[j.id]
                        if (prevState != null && prevState != JobState.FAILED && j.state == JobState.FAILED) {
                            // 같은 원인(에러코드+메시지)의 재발신은 1회만 — 상태 재발행/재시도로 인한 알림 폭주 방지
                            val reason = "${j.errorCode}|${j.errorMessage}"
                            if (lastFailReason[j.id] != reason) {
                                lastFailReason[j.id] = reason
                                DebugLogger.i(TAG, "실패 알림 '${j.filename}' (${j.errorCode})")
                                notifs.notify(j.id.hashCode(), getString(R.string.notif_fail_title), j.filename + (j.errorMessage?.let { " — $it" } ?: ""), done = false)
                                // T-1095 플러그인 이벤트 (동일 원인 1회만 — 알림 dedup과 같은 지점)
                                scope.launch {
                                    val allowed = runCatching { settingsRepo.settings.first().pluginAllowed }.getOrDefault(true)
                                    if (allowed) PluginLog.event(PluginContract.eventDownloadFailed(j.id, j.filename, j.errorCode ?: "-"))
                                }
                                // 웹훅 콜백 전송 (Phase 2.2)
                                scope.launch {
                                    val s = try { settingsRepo.settings.first() } catch (_: Exception) { return@launch }
                                    webhookManager?.send("download_failed", org.json.JSONObject().apply {
                                        put("id", j.id); put("filename", j.filename); put("url", j.url)
                                        put("errorCode", j.errorCode ?: ""); put("errorMessage", j.errorMessage ?: "")
                                    }, s)
                                }
                            }
                        }
                    }
                }

                // 완료 후 동작 (v0.24) — 전이 기반: 직전까지 활성이었는데 지금 유휴면 1회 실행.
                // 무조건 시도 원칙: DONE이 아닌 토렌트가 남아있으면 "전체 완료"가 아니다.
                val wasActive = last.values.any { it == JobState.RUNNING || it == JobState.QUEUED }
                val nowIdle = current.values.none { it == JobState.RUNNING || it == JobState.QUEUED } &&
                    TorrentRepository.all().all { it.state == TorrentState.DONE }
                if (wasActive && nowIdle) {
                    val action = runCatching { settingsRepo.firstBlocking().completionAction }.getOrDefault("none")
                    if (action == SettingsConstraints.COMPLETION_ACTION_STOP_SERVER) {
                        DebugLogger.i(TAG, "[FEATURE] 전체 완료 → 서버 정지 (completionAction)")
                        stop(applicationContext)
                    }
                }
                last = current

                // 진행바 실시간 갱신 (2초 스로틀)
                if (now - lastNotifUpdate >= NOTIF_THROTTLE_MS) {
                    lastNotifUpdate = now
                    notifs.updateProgressNotification(jobs, activePort())
                }
            }
        }

        // ③ 신규 기기 승인 팝업 (T-112)
        DeviceGate.onRequest = { ip, resolve ->
            notifs.showDeviceGateNotification(ip, resolve, scope)
        }

        // ④ 네트워크 복구 시 FAILED 작업 자동 재시도
        networkMonitor = NetworkMonitor(applicationContext) { engine.retryFailed() }
        networkMonitor?.register()

        // ⑤ torrent 완료/실패 알림 + 진행바 갱신 (토렌트 단독 진행 시 HTTP 플로우가 안 돌므로 여기서도 갱신)
        scope.launch {
            var lastTorrentStates: Map<String, TorrentState> = emptyMap()
            var lastTorrentNotifUpdate = 0L
            TorrentRepository.torrents.collectLatest { torrents ->
                if (notificationsOn) {
                    torrents.forEach { t ->
                        val prev = lastTorrentStates[t.id]
                        if (prev == TorrentState.DOWNLOADING && t.state == TorrentState.DONE) {
                            DebugLogger.i(TAG, "torrent 완료 알림 '${t.name}'")
                            notifs.notifyTorrent(t.id.hashCode(), "Torrent 완료", t.name)
                            // T-1095 플러그인 이벤트 (연동 허용 시)
                            scope.launch {
                                val allowed = runCatching { settingsRepo.settings.first().pluginAllowed }.getOrDefault(true)
                                if (allowed) PluginLog.event(PluginContract.eventTorrentComplete(t.id, t.name))
                            }
                            // 웹훅 콜백 전송 (Phase 2.2)
                            scope.launch {
                                val s = try { settingsRepo.settings.first() } catch (_: Exception) { return@launch }
                                webhookManager?.send("torrent_complete", org.json.JSONObject().apply {
                                    put("id", t.id); put("name", t.name); put("magnet", t.magnet ?: "")
                                    put("savePath", t.savePath)
                                }, s)
                            }
                        }
                        if (prev != null && prev != TorrentState.FAILED && t.state == TorrentState.FAILED) {
                            DebugLogger.i(TAG, "torrent 실패 알림 '${t.name}'")
                            notifs.notifyTorrent(t.id.hashCode() + 10000, "Torrent 실패", t.name + (t.errorMessage?.let { " — $it" } ?: ""))
                            // T-1095 플러그인 이벤트 (연동 허용 시)
                            scope.launch {
                                val allowed = runCatching { settingsRepo.settings.first().pluginAllowed }.getOrDefault(true)
                                if (allowed) PluginLog.event(PluginContract.eventTorrentFailed(t.id, t.name, t.errorMessage ?: "-"))
                            }
                        }
                    }
                }
                lastTorrentStates = torrents.associate { it.id to it.state }
                // 진행바 실시간 갱신 (2초 스로틀) — HTTP 작업 없어도 토렌트 속도/진행 반영
                val nowT = System.currentTimeMillis()
                if (nowT - lastTorrentNotifUpdate >= NOTIF_THROTTLE_MS) {
                    lastTorrentNotifUpdate = nowT
                    runCatching { notifs.updateProgressNotification(JobsRepository.all(), activePort()) }
                }
            }
        }
    }

    /**
     * 유휴 여부 — HTTP/비디오 작업·토렌트 활동·진행 중 전송이 모두 없으면 true.
     * 판정식은 순수 함수 [isIdleForAutoStop] 에 있어 단위 테스트된다.
     */
    private fun isIdleNow(): Boolean = isIdleForAutoStop(
        jobStates = JobsRepository.all().map { it.state },
        torrentStates = TorrentRepository.all().map { it.state },
        activeTransfers = TransferTracker.count,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 재시작/재실행 시 FGS 허용 타이밍이면 알림 복구 (이미 포그라운드면 no-op)
        startInForeground()
        when (intent?.action) {
            ACTION_ALLOW -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
                scope.launch { SettingsRepository.get(this@RelayService).addAllowedIp(ip) }
                DeviceGate.resolve(ip, allowed = true)
            }
            ACTION_DENY -> DeviceGate.resolve(ip(intent), allowed = false)
        }
        return START_STICKY
    }

    private fun ip(intent: Intent?) = intent?.getStringExtra(EXTRA_IP) ?: ""

    override fun onDestroy() {
        DebugLogger.i(TAG, "서비스 종료 시작 — 컴포넌트 정리")
        DeviceGate.onRequest = null
        DeviceGate.clearSession()
        // FGS로 승격된 경우 반드시 제거 — 누락 시 ForegroundServiceDidNotStopInTimeException(E-AND-SRV-0110)
        stopForegroundIfNeeded()
        // 무거운 정지·영구 저장 전부 백그라운드로 — onDestroy가 메인스레드에서
        // 시스템 FGS 정지 타임아웃(약 10초)을 넘기면 ForegroundServiceDidNotStopInTimeException 크래시
        val serverToStop = server.also { server = null }
        val torrentToStop = torrentEngine.also { torrentEngine = null }
        val networkToUnregister = networkMonitor.also { networkMonitor = null }
        val tunnelToStop = tunnelManager.also { tunnelManager = null }
        val rssToStop = rssManager.also { rssManager = null }
        val guardToStop = guardDaemon.also { guardDaemon = null }
        val schedulerToStop = schedulerManager.also { schedulerManager = null }
        val speedScheduleToStop = speedScheduleManager.also { speedScheduleManager = null }
        val appContext = applicationContext
        val port = currentPort
        val httpsPort = currentHttpsPort
        val httpsOn = currentHttpsEnabled
        kotlin.concurrent.thread(isDaemon = true, name = "RelayService-stop") {
            runCatching { serverToStop?.stop() }
                .onFailure { DebugLogger.e(TAG, "서버 백그라운드 정지 실패(무시)", it) }
            runCatching { torrentToStop?.stop() }
                .onFailure { DebugLogger.e(TAG, "토렌트 백그라운드 정지 실패(무시)", it) }
            // stop()은 공용 scope를 cancel하므로 인스턴스 재사용 시 폴링·복원이 전부 증발한다 (v0.50.1).
            // 마스터 토글 OFF 경로와 동일하게 파기 → 다음 기동은 새 인스턴스·새 scope로 생성된다.
            runCatching { RelayApp.resetTorrent() }
            DebugLogger.i(TAG, "토렌트 인스턴스 파기 — 다음 기동 시 재생성")
            runCatching { networkToUnregister?.unregister() }
                .onFailure { DebugLogger.e(TAG, "네트워크 모니터 해제 실패(무시)", it) }
            runCatching { tunnelToStop?.stop() }
            runCatching { rssToStop?.stop() }
            runCatching { guardToStop?.stop() }
            runCatching { schedulerToStop?.stop() }
            runCatching { speedScheduleToStop?.stop() }
            // 서버 상태 갱신
            runCatching {
                SettingsRepository.get(appContext).updateServerState(
                    ServerState(running = false, port = port, httpsPort = httpsPort, httpsEnabled = httpsOn)
                )
            }
            // 트래픽 통계 원장 저장 (v0.37)
            runCatching { TrafficLedger.flush() }
            // 강제종료/서비스 종료 시 즉시 영구 저장 (T-111)
            // 복원 전 저장 금지 (T-1085) — 복원 전엔 저장소가 비어 있어
            // "원본 N건" 이 "[]" 로 덮여써지면 목록이 영구히 사라진다(실제로 그랬다).
            // Torrent 상태 저장 — resetTorrent()로 인스턴스가 파기되므로 복원 여부는 미리 캡처 (v0.50.1)
            val torrentRestored = torrentToStop?.isRestored() == true
            runCatching { persistJobs("복원 전 종료") }
            runCatching { persistTorrents("복원 전 종료", torrentRestored) }
            DebugLogger.i(TAG, "백그라운드 정지·영구 저장 완료")
        }
        scope.cancel()
        DebugLogger.i(TAG, "서비스 종료 완료 — 즉시 반환")
        super.onDestroy()
    }

    /** 강제종료 직전 상태 저장 (onTaskRemoved = 사용자가 앱 스와이프 종료 시) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLogger.i(TAG, "onTaskRemoved → 즉시 영구 저장")
        persistJobs("복원 전 스와이프 종료")
        persistTorrents("복원 전 스와이프 종료", RelayApp.torrentEngine?.isRestored() == true)
        // FGS 제거 누락 시 시스템에 의해 타임아웃 크래시 발생 — 스와이프 종료 시에도 명시적으로 해제 (E-AND-SRV-0110)
        stopForegroundIfNeeded()
        // 무조건 시도 원칙: DONE이 아닌 토렌트가 남아있으면 유휴가 아니므로 서비스 유지.
        // (PAUSED·STALLED·FAILED도 대기 작업이다 — isIdleForAutoStop 단일 기준으로 판정)
        val idle = isIdleForAutoStop(
            jobStates = JobsRepository.all().map { it.state },
            torrentStates = runCatching { TorrentRepository.all().map { it.state } }.getOrDefault(emptyList()),
            activeTransfers = TransferTracker.count,
        )
        if (idle) {
            DebugLogger.i(TAG, "onTaskRemoved → 유휴 상태 stopSelf")
            stopSelf()
        } else {
            DebugLogger.i(TAG, "onTaskRemoved → 진행 중 작업 존재, 서비스 유지")
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 강제종료 내성 영속 — onDestroy·onTaskRemoved 공통 (T-111/T-1085). 순서·가드·문구 태그만 다르다 */
    private fun persistJobs(tag: String) {
        val jobs = JobsRepository.all()
        if (RelayApp.engine?.isRestored() == true) {
            JobsPersistence(applicationContext).save(jobs)
        } else {
            DebugLogger.w(TAG, "$tag — jobs.json 저장 스킵 (${jobs.size}건)")
        }
    }

    private fun persistTorrents(tag: String, torrentRestored: Boolean) {
        val ts = TorrentRepository.all()
        if (torrentRestored) {
            TorrentPersistence(applicationContext).save(ts)
        } else {
            DebugLogger.w(TAG, "$tag — torrents.json 저장 스킵 (${ts.size}건)")
        }
    }

    /** FGS 해제 누락 시 시스템 타임아웃 크래시 (E-AND-SRV-0110) — 종료 경로 공통 */
    private fun stopForegroundIfNeeded() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    override fun onBind(intent: Intent?) = null

    /** 설정 반영 전(-1)에는 기본 HTTP 포트로 폴백 */
    private fun activePort(): Int =
        if (currentPort in SettingsConstraints.PORT_MIN..SettingsConstraints.PORT_MAX) currentPort
        else SettingsConstraints.DEFAULT_HTTP_PORT

    private fun startInForeground() {
        if (isForeground) return
        val notif = notifs.runningNotification(lanAddress(), activePort())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 15+ dataSync 6시간 제한으로 ForegroundServiceDidNotStopInTimeException 크래시
                // → API 34+는 specialUse로 승격 (LAN 릴레이 서버, 무제한)
                startForeground(ServiceNotifications.NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ServiceNotifications.NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ServiceNotifications.NOTIF_ID, notif)
            }
            isForeground = true
            DebugLogger.i(TAG, "포그라운드 서비스 시작 완료")
        } catch (e: Exception) {
            // Android 12+ 백그라운드 시작 제한(ForegroundServiceStartNotAllowedException) 등 —
            // 무시해도 서비스는 백그라운드로 계속 동작하며, 이후 재시도 시 알림 복구된다.
            DebugLogger.w(TAG, "FGS 시작 거부 — 백그라운드 모드로 계속 동작: ${e.message} (E-AND-SRV-0101)")
        }
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 10_000L
        private const val NOTIF_THROTTLE_MS = 2_000L
        private const val WATCHDOG_FAIL_STREAK = 3
        /** 유휴 모니터 틱 — 60초마다 판정 (배터리 영향 무시 수준) */
        private const val IDLE_CHECK_MS = 60_000L
        /** 가드 해제 후 토렌트 staggered resume 간격 (T-1101) */
        private const val GUARD_RESUME_STAGGER_MS = 3_000L
        const val ACTION_ALLOW = "com.borasarang.droidrelay.ALLOW"
        const val ACTION_DENY = "com.borasarang.droidrelay.DENY"
        const val EXTRA_IP = "ip"

        fun start(context: Context) {
            // 주의: startForegroundService()는 5초 내 startForeground() 성공이 의무다.
            // 실패(백그라운드 시작 제한) 시 시스템이 ForegroundServiceDidNotStartInTimeException으로
            // 앱 전체를 죽이므로, 기본은 일반 startService()로 시작해
            // onStartCommand에서 기회적으로 startForeground()로 승격한다 (의무 타이머 없음).
            try {
                context.startService(Intent(context, RelayService::class.java))
            } catch (e: IllegalStateException) {
                // API 26+ 백그라운드 start 제한 — FGS 경유 재시도 (허용 시점만 호출되므로 안전)
                DebugLogger.w("RelayService", "startService 거부 — startForegroundService 재시도: ${e.message} (E-AND-SRV-0102)")
                runCatching { context.startForegroundService(Intent(context, RelayService::class.java)) }
                    .onFailure { DebugLogger.e("RelayService", "startForegroundService 실패: ${it.message}", it) }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
