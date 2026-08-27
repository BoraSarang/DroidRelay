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
    private var server: RelayServer? = null
    private var currentPort: Int = -1
    private var notificationsOn = true
    private var networkMonitor: NetworkMonitor? = null
    private var torrentEngine: TorrentEngine? = null
    private var rssManager: RssFeedManager? = null
    private var guardDaemon: GuardDaemon? = null
    private var webhookManager: WebhookManager? = null
    private var tunnelManager: TunnelManager? = null
    private var schedulerManager: SchedulerManager? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i(TAG, "서비스 생성 시작")
        createChannel()
        startInForeground()

        val settingsRepo = SettingsRepository.get(applicationContext)
        val engine = RelayApp.get(applicationContext)
        val persistence = JobsPersistence(applicationContext)
        val torrentEng = RelayApp.getTorrent(applicationContext)
        torrentEngine = torrentEng
        DebugLogger.i(TAG, "초기화 시작 engine=${engine::class.simpleName} torrent=${torrentEng::class.simpleName}")

        // TorrentEngine 시작
        torrentEng.start()
        DebugLogger.i(TAG, "TorrentEngine 시작 완료")

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
        guard.onThrottleChange = { throttled, reason ->
            DebugLogger.i(TAG, "가드 상태 변경 throttled=$throttled reason=$reason")
            if (throttled) {
                // 실행 중인 다운로드 일시정지
                JobsRepository.jobs.value.forEach { j ->
                    if (j.state == JobState.RUNNING) {
                        engine.pause(j.id)
                    }
                }
            } else {
                // 대기 중인 다운로드 재개
                engine.retryFailed()
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

        // ① 설정 감시: 포트 변경 → 서버 재시작 / 알림 토글 / 서버 스냅샷 갱신
        scope.launch {
            var lastPort = -1
            settingsRepo.settings.collectLatest { s ->
                notificationsOn = s.notifications
                if (server == null) {
                    try {
                        server = RelayServer(applicationContext, s.port).also { it.updateSettings(s); it.start() }
                        val url = lanAddress()?.let { "http://$it:${s.port}" }
                        settingsRepo.updateServerState(ServerState(running = true, port = s.port, url = url))
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "서버 기동 실패: ${e.message}", e)
                        settingsRepo.updateServerState(ServerState(running = false, port = s.port, error = "서버 기동 실패: ${e.message}"))
                    }
                    lastPort = s.port
                } else {
                    server?.updateSettings(s)
                    if (s.port != lastPort) {
                        DebugLogger.i(TAG, "포트 변경 감지 $lastPort → ${s.port} — 서버 재시작")
                        server?.stop()
                        try {
                            server = RelayServer(applicationContext, s.port).also { it.updateSettings(s); it.start() }
                            val url = lanAddress()?.let { "http://$it:${s.port}" }
                            settingsRepo.updateServerState(ServerState(running = true, port = s.port, url = url))
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "서버 재시작 실패: ${e.message}", e)
                            settingsRepo.updateServerState(ServerState(running = false, port = s.port, error = "서버 재시작 실패: ${e.message}"))
                        }
                        lastPort = s.port
                        updateRunningNotification(s.port)
                    }
                }
            }
        }

        // ② 작업 상태 → 완료/실패 알림 + 진행바 갱신 (T-105)
        scope.launch {
            var lastNotifUpdate = 0L
            var lastSaveAt = 0L
            var last: Map<String, JobState> = emptyMap()
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
                        if (last[j.id] != null && j.state == JobState.FAILED) {
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

        // ⑤ torrent 완료/실패 알림
        scope.launch {
            var lastTorrentStates: Map<String, TorrentState> = emptyMap()
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
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        server?.stop()
        server = null
        networkMonitor?.unregister()
        torrentEngine?.stop()
        torrentEngine = null
        rssManager?.stop()
        rssManager = null
        guardDaemon?.stop()
        guardDaemon = null
        schedulerManager?.stop()
        schedulerManager = null
        DebugLogger.i(TAG, "서비스 종료 완료 — 영구 저장 실행")
        // 서버 상태 갱신
        SettingsRepository.get(applicationContext).updateServerState(ServerState(running = false, port = currentPort))
        // 강제종료/서비스 종료 시 즉시 영구 저장 (T-111)
        val jobs = com.borasarang.droidrelay.relay.JobsRepository.all()
        JobsPersistence(applicationContext).save(jobs)
        // Torrent 상태 저장
        TorrentRepository.all().let { TorrentPersistence(applicationContext).save(it) }
        scope.cancel()
        super.onDestroy()
    }

    /** 강제종료 직전 상태 저장 (onTaskRemoved = 사용자가 앱 스와이프/終了 시) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLogger.i(TAG, "onTaskRemoved → 즉시 영구 저장")
        val jobs = com.borasarang.droidrelay.relay.JobsRepository.all()
        JobsPersistence(applicationContext).save(jobs)
        TorrentRepository.all().let { TorrentPersistence(applicationContext).save(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?) = null

    private fun startInForeground() {
        val notif = runningNotification(lanAddress())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun baseNotif(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)

    private fun runningNotification(ip: String?): Notification =
        baseNotif()
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(if (ip != null) "http://$ip:$PORT 접속 가능" else getString(R.string.notif_running_text))
            .setOngoing(true)
            .build()

    private fun updateRunningNotification(port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, runningNotification(lanAddress()?.let { "$it:$port" }))
    }

    private fun updateProgressNotification(jobs: List<Job>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val running = jobs.filter { it.state == JobState.RUNNING }
        val ip = lanAddress()
        val totalSpeed = running.sumOf { it.speedBps }

        val notif = if (running.isEmpty()) {
            baseNotif()
                .setContentTitle(getString(R.string.notif_running_title))
                .setContentText("http://$ip:$PORT · 대기 중인 다운로드 없음")
                .setOngoing(true)
                .build()
        } else {
            val first = running.maxByOrNull { it.progress }!!
            val pct = (first.progress * 100).toInt().coerceIn(0, 100)
            baseNotif()
                .setContentTitle("${running.size}건 다운로드 중 · ⚡ ${totalSpeed / 1024} KB/s")
                .setContentText("${first.filename} $pct% (${fmtBytes(first.downloadedBytes)}${if (first.totalBytes > 0) "/" + fmtBytes(first.totalBytes) else ""})")
                .setProgress(100, pct, first.totalBytes <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
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
        val notif = Notification.Builder(this, CHANNEL_RESULT_ID)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    private fun notifyTorrent(id: Int, title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = Notification.Builder(this, CHANNEL_TORRENT_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
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
        const val PORT = 8080
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
        const val ACTION_ALLOW = "com.borasarang.droidrelay.ALLOW"
        const val ACTION_DENY = "com.borasarang.droidrelay.DENY"
        const val EXTRA_IP = "ip"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java))
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
