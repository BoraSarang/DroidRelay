package com.borasarang.droidrelay.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import com.borasarang.droidrelay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RelayService : Service() {

    private val TAG = "Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var server: RelayServer? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i(TAG, "서비스 생성 시작")
        createChannel()
        startInForeground()
        acquireWakeLock()

        server = RelayServer(applicationContext).also { it.start() }

        // 상태 전이 → 시스템 알림 (T-006)
        scope.launch {
            var last: Map<String, JobState> = emptyMap()
            JobsRepository.jobs.collectLatest { jobs ->
                val current = jobs.associate { it.id to it.state }
                jobs.forEach { j ->
                    if (last[j.id] == JobState.RUNNING && j.state == JobState.DONE) {
                        DebugLogger.i(TAG, "완료 알림 발행 '${j.filename}'")
                        notify(j, done = true)
                    }
                    if (last[j.id] != null && j.state == JobState.FAILED) {
                        DebugLogger.i(TAG, "실패 알림 발행 '${j.filename}' (${j.errorCode})")
                        notify(j, done = false)
                    }
                }
                last = current
            }
        }
        DebugLogger.i(TAG, "서비스 준비 완료 — 접속: http://${lanAddress() ?: "?"}:$PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.d(TAG, "onStartCommand startId=$startId → START_STICKY")
        return START_STICKY
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "서비스 종료 — 서버·WakeLock 해제")
        server?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidRelay::download").apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
        DebugLogger.d(TAG, "WakeLock 획득 (PARTIAL, ${WAKE_TIMEOUT_MS / 3600000}h)")
    }

    private fun startInForeground() {
        val notif = runningNotification(lanAddress())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun runningNotification(ip: String?): Notification {
        val text = if (ip != null) "http://$ip:$PORT 접속 가능" else getString(R.string.notif_running_text)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun notify(job: Job, done: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = getString(if (done) R.string.notif_done_title else R.string.notif_fail_title)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(title)
            .setContentText(job.filename + (job.errorMessage?.let { " — $it" } ?: ""))
            .setAutoCancel(true)
            .build()
        nm.notify(job.id.hashCode(), notif)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        DebugLogger.d(TAG, "알림 채널 생성/확인")
    }

    companion object {
        const val PORT = 8080
        private const val CHANNEL_ID = "relay_status"
        private const val NOTIF_ID = 1001
        private const val WAKE_TIMEOUT_MS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            DebugLogger.i("App", "RelayService 시작 요청")
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
