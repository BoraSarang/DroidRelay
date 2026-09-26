package com.borasarang.droidrelay.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.borasarang.droidrelay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i(TAG, "서비스 생성 시작")
        // 채널 생성이 최초 알림 게시보다 앞서야 Android 8+에서 무시되지 않음
        createChannel()
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
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            torrentEng.start()
            DebugLogger.i(TAG, "TorrentEngine 시작 완료")
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
                // 실행 중인 다운로드 일시정지
                JobsRepository.jobs.value.forEach { j ->
                    if (j.state == JobState.RUNNING) {
                        runCatching { engine.pause(j.id) }
                    }
                }
                // 토렌트도 함께 스로틀 (결함 #5)
                TorrentRepository.all().forEach { t ->
                    if (t.state == TorrentState.DOWNLOADING || t.state == TorrentState.SEEDING) {
                        runCatching { torrentEng.pause(t.id) }
                    }
                }
            } else {
                // 가드가 pause한 잡(PAUSED)과 실패 잡을 재개 — retryFailed만으로는 일시정지가 풀리지 않음
                runCatching { engine.retryFailed() }
                JobsRepository.jobs.value.forEach { j ->
                    if (j.state == JobState.PAUSED) {
                        runCatching { engine.resume(j.id) }
                    }
                }
                TorrentRepository.all().forEach { t ->
                    if (t.state == TorrentState.PAUSED) {
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
                        updateRunningNotification(s.port)
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
                        // 서버가 아예 없으면 새로 기동
                        DebugLogger.w(TAG, "watchdog: 서버가 없음 → 기동 시도")
                        healthyCount = 0
                        runCatching {
                            synchronized(serverLock) {
                                if (server == null) {
                                    val s2 = settingsRepo.firstBlocking()
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
                            notify(j.id.hashCode(), getString(R.string.notif_done_title), j.filename, done = true)
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
                                notify(j.id.hashCode(), getString(R.string.notif_fail_title), j.filename + (j.errorMessage?.let { " — $it" } ?: ""), done = false)
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

                // 완료 후 동작 (v0.24) — 전이 기반: 직전까지 활성이었는데 지금 유휴면 1회 실행
                val wasActive = last.values.any { it == JobState.RUNNING || it == JobState.QUEUED }
                val nowIdle = current.values.none { it == JobState.RUNNING || it == JobState.QUEUED } &&
                    TorrentRepository.all().none {
                        it.state == TorrentState.DOWNLOADING ||
                            it.state == TorrentState.FETCHING_METADATA ||
                            it.state == TorrentState.QUEUED
                    }
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
                    updateProgressNotification(jobs)
                }
            }
        }

        // ③ 신규 기기 승인 팝업 (T-112)
        DeviceGate.onRequest = { ip, resolve ->
            showDeviceGateNotification(ip, resolve)
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
                            notifyTorrent(t.id.hashCode(), "Torrent 완료", t.name)
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
                            notifyTorrent(t.id.hashCode() + 10000, "Torrent 실패", t.name + (t.errorMessage?.let { " — $it" } ?: ""))
                        }
                    }
                }
                lastTorrentStates = torrents.associate { it.id to it.state }
                // 진행바 실시간 갱신 (2초 스로틀) — HTTP 작업 없어도 토렌트 속도/진행 반영
                val nowT = System.currentTimeMillis()
                if (nowT - lastTorrentNotifUpdate >= NOTIF_THROTTLE_MS) {
                    lastTorrentNotifUpdate = nowT
                    runCatching { updateProgressNotification(JobsRepository.all()) }
                }
            }
        }
    }

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
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
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
            runCatching {
                val jobs = com.borasarang.droidrelay.relay.JobsRepository.all()
                JobsPersistence(appContext).save(jobs)
            }
            // Torrent 상태 저장
            runCatching { TorrentRepository.all().let { TorrentPersistence(appContext).save(it) } }
            DebugLogger.i(TAG, "백그라운드 정지·영구 저장 완료")
        }
        scope.cancel()
        DebugLogger.i(TAG, "서비스 종료 완료 — 즉시 반환")
        super.onDestroy()
    }

    /** 강제종료 직전 상태 저장 (onTaskRemoved = 사용자가 앱 스와이프/終了 시) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLogger.i(TAG, "onTaskRemoved → 즉시 영구 저장")
        val jobs = com.borasarang.droidrelay.relay.JobsRepository.all()
        JobsPersistence(applicationContext).save(jobs)
        TorrentRepository.all().let { TorrentPersistence(applicationContext).save(it) }
        // FGS 제거 누락 시 시스템에 의해 타임아웃 크래시 발생 — 스와이프 종료 시에도 명시적으로 해제 (E-AND-SRV-0110)
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        val active = jobs.any { it.state == JobState.RUNNING || it.state == JobState.QUEUED } ||
            runCatching {
                TorrentRepository.all().any {
                    it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA || it.state == TorrentState.QUEUED
                }
            }.getOrDefault(false)
        if (!active) {
            DebugLogger.i(TAG, "onTaskRemoved → 유휴 상태 stopSelf")
            stopSelf()
        } else {
            DebugLogger.i(TAG, "onTaskRemoved → 진행 중 작업 존재, 서비스 유지")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?) = null

    private fun startInForeground() {
        if (isForeground) return
        val notif = runningNotification(lanAddress(), activePort())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 15+ dataSync 6시간 제한으로 ForegroundServiceDidNotStopInTimeException 크래시
                // → API 34+는 specialUse로 승격 (LAN 릴레이 서버, 무제한)
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isForeground = true
            DebugLogger.i(TAG, "포그라운드 서비스 시작 완료")
        } catch (e: Exception) {
            // Android 12+ 백그라운드 시작 제한(ForegroundServiceStartNotAllowedException) 등 —
            // 무시해도 서비스는 백그라운드로 계속 동작하며, 이후 재시도 시 알림 복구된다.
            DebugLogger.w(TAG, "FGS 시작 거부 — 백그라운드 모드로 계속 동작: ${e.message} (E-AND-SRV-0101)")
        }
    }

    private fun baseNotif(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)

    private fun runningNotification(ip: String?, port: Int): Notification =
        baseNotif()
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(if (ip != null) "http://$ip:$port 접속 가능" else getString(R.string.notif_running_text))
            .setOngoing(true)
            .build()

    private fun updateRunningNotification(port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, runningNotification(lanAddress(), port))
    }

    /** 설정 반영 전(-1)에는 기본 HTTP 포트로 폴백 */
    private fun activePort(): Int =
        if (currentPort in SettingsConstraints.PORT_MIN..SettingsConstraints.PORT_MAX) currentPort
        else SettingsConstraints.DEFAULT_HTTP_PORT

    private fun updateProgressNotification(jobs: List<Job>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val running = jobs.filter { it.state == JobState.RUNNING }
        val torrents = runCatching { TorrentRepository.all() }.getOrDefault(emptyList())
        val tActive = torrents.filter {
            it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA
        }
        val seeding = torrents.filter { it.state == TorrentState.SEEDING }
        val ip = lanAddress()
        val httpSpeed = running.sumOf { it.speedBps }
        val tDownSpeed = tActive.sumOf { it.downloadSpeed }
        val tUpSpeed = (tActive + seeding).sumOf { it.uploadSpeed }

        val notif = if (running.isEmpty() && tActive.isEmpty()) {
            if (seeding.isNotEmpty()) {
                val first = seeding.maxByOrNull { it.uploadSpeed }!!
                baseNotif()
                    .setContentTitle("🌱 시딩 중 ${seeding.size}건 · ↑ ${tUpSpeed / 1024} KB/s")
                    .setContentText("${first.name} (${first.seeds}시드/${first.peers}피어)")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } else {
                baseNotif()
                    .setContentTitle(getString(R.string.notif_running_title))
                    .setContentText("http://$ip:${activePort()} · 대기 중인 다운로드 없음")
                    .setOngoing(true)
                    .build()
            }
        } else {
            val total = running.size + tActive.size
            val totalSpeed = httpSpeed + tDownSpeed
            val topHttp = running.maxByOrNull { it.progress }
            val topTorrent = tActive.maxByOrNull { it.progress }
            // 진행률 높은 쪽을 대표 표시 (동률이면 HTTP 우선)
            val useTorrent = topTorrent != null &&
                (topHttp == null || topTorrent.progress >= topHttp.progress)
            if (useTorrent) {
                val t = topTorrent!!
                val pct = (t.progress * 100).toInt().coerceIn(0, 100)
                val sizeText = if (t.totalSize > 0) {
                    " (${fmtBytes(t.downloadedSize)}/${fmtBytes(t.totalSize)})"
                } else {
                    ""
                }
                val stateText = if (t.state == TorrentState.FETCHING_METADATA) "메타데이터 받는 중" else "$pct%$sizeText"
                baseNotif()
                    .setContentTitle("🌊 ${total}건 다운로드 중 · ⚡ ${totalSpeed / 1024} KB/s")
                    .setContentText("${t.name} $stateText")
                    .setProgress(100, pct, t.totalSize <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } else {
                val first = topHttp!!
                val pct = (first.progress * 100).toInt().coerceIn(0, 100)
                baseNotif()
                    .setContentTitle("${total}건 다운로드 중 · ⚡ ${totalSpeed / 1024} KB/s")
                    .setContentText("${first.filename} $pct% (${fmtBytes(first.downloadedBytes)}${if (first.totalBytes > 0) "/" + fmtBytes(first.totalBytes) else ""})")
                    .setProgress(100, pct, first.totalBytes <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            }
        }
        nm.notify(NOTIF_ID, notif)
    }

    private fun showDeviceGateNotification(ip: String, resolve: (Boolean) -> Unit) {
        val allow = PendingIntent.getService(
            this, ip.hashCode(),
            Intent(this, RelayService::class.java).setAction(ACTION_ALLOW).putExtra(EXTRA_IP, ip),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getService(
            this, ("deny$ip").hashCode(),
            Intent(this, RelayService::class.java).setAction(ACTION_DENY).putExtra(EXTRA_IP, ip),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_GATE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("새 기기 접속 요청")
            .setContentText("$ip — 이 기기를 허용할까요?")
            .setAutoCancel(true)
            .addAction(0, "허용", allow)
            .addAction(0, "거부", deny)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(GATE_NOTIF_PREFIX + ip.hashCode(), notif)

        // 알림 액션 없이 무응답이면 대기 유지 (웹은 503 반환)
        scope.launch {
            delay(GATE_TIMEOUT_MS)
            runCatching { resolve(false) }
        }
    }

    private fun notify(id: Int, title: String, text: String, done: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 완료 알림 탭 → 보관함으로 이동 (v0.24)
        val openTab = PendingIntent.getActivity(
            this, 9000 + id,
            Intent(this, com.borasarang.droidrelay.MainActivity::class.java)
                .setAction(com.borasarang.droidrelay.MainActivity.ACTION_OPEN_TAB)
                .putExtra(com.borasarang.droidrelay.MainActivity.EXTRA_TAB, 2),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_RESULT_ID)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(if (done) openTab else null)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    private fun notifyTorrent(id: Int, title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 토렌트 완료 알림 탭 → 토렌트 탭으로 이동 (v0.24)
        val openTab = PendingIntent.getActivity(
            this, 9100 + id,
            Intent(this, com.borasarang.droidrelay.MainActivity::class.java)
                .setAction(com.borasarang.droidrelay.MainActivity.ACTION_OPEN_TAB)
                .putExtra(com.borasarang.droidrelay.MainActivity.EXTRA_TAB, 1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_TORRENT_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openTab)
            .setAutoCancel(true)
            .build()
        nm.notify(TORRENT_NOTIF_PREFIX + id, notif)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 서버 실행 상태 알림 — 배지 없음
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        // 다운로드 완료/실패 알림 — 배지 표시
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT_ID, "다운로드 결과", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
            },
        )
        // 기기 접속 승인 알림
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GATE_ID, "기기 접속 승인", NotificationManager.IMPORTANCE_HIGH),
        )
        // torrent 다운로드 알림
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TORRENT_ID, "Torrent 다운로드", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(true)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "relay_status"
        private const val CHANNEL_RESULT_ID = "relay_result"
        private const val CHANNEL_GATE_ID = "relay_gate"
        private const val CHANNEL_TORRENT_ID = "relay_torrent"
        private const val NOTIF_ID = 1001
        private const val TORRENT_NOTIF_PREFIX = 30000
        private const val GATE_TIMEOUT_MS = 60_000L
        private const val GATE_NOTIF_PREFIX = 20000
        private const val SAVE_DEBOUNCE_MS = 10_000L
        private const val NOTIF_THROTTLE_MS = 2_000L
        private const val WATCHDOG_FAIL_STREAK = 3
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

private fun fmtBytes(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}
