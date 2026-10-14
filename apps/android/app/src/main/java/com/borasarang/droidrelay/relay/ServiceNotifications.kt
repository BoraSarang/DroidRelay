package com.borasarang.droidrelay.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.borasarang.droidrelay.MainActivity
import com.borasarang.droidrelay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 포그라운드·진행·승인·결과 알림 전담 (Phase 2 — RelayService에서 분리).
 *
 * 왜 클래스인가: 알림 빌더 8종 + 채널 4종이 서비스 생명주기(엔진·watchdog·팬아웃)와
 * 한 파일에 있어 932행이 됐다. 빌더는 순수하게 Context만 필요하므로 통째로 옮긴다.
 * 호출 순서·문구·ID·스로틀은 그대로 — 동작 변경 없음. 상태 판단·저장·PluginLog는
 * RelayService에 남는다.
 */
internal class ServiceNotifications(private val context: Context) {

    fun createChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 서버 실행 상태 알림 — 배지 없음
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
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

    private fun baseNotif(): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)

    fun runningNotification(ip: String?, port: Int): Notification =
        baseNotif()
            .setContentTitle(context.getString(R.string.notif_running_title))
            .setContentText(if (ip != null) "http://$ip:$port 접속 가능" else context.getString(R.string.notif_running_text))
            .setOngoing(true)
            .build()

    fun updateRunningNotification(port: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, runningNotification(lanAddress(), port))
    }

    /** 설정 반영 전(-1) 포트 폴백은 호출자가 activePort() 로 해결해 전달한다 */
    fun updateProgressNotification(jobs: List<Job>, port: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                    .setContentTitle(context.getString(R.string.notif_running_title))
                    .setContentText("http://$ip:$port · 대기 중인 다운로드 없음")
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

    fun showDeviceGateNotification(ip: String, resolve: (Boolean) -> Unit, scope: CoroutineScope) {
        val allow = PendingIntent.getService(
            context, ip.hashCode(),
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_ALLOW).putExtra(RelayService.EXTRA_IP, ip),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getService(
            context, ("deny$ip").hashCode(),
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_DENY).putExtra(RelayService.EXTRA_IP, ip),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(context, CHANNEL_GATE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("새 기기 접속 요청")
            .setContentText("$ip — 이 기기를 허용할까요?")
            .setAutoCancel(true)
            .addAction(0, "허용", allow)
            .addAction(0, "거부", deny)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(GATE_NOTIF_PREFIX + ip.hashCode(), notif)

        // 알림 액션 없이 무응답이면 대기 유지 (웹은 503 반환)
        scope.launch {
            delay(GATE_TIMEOUT_MS)
            runCatching { resolve(false) }
        }
    }

    fun notify(id: Int, title: String, text: String, done: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 완료 알림 탭 → 보관함으로 이동 (v0.24) — 탭 순서: 0상태 1다운로드 2토렌트 3보관함 4설정 5통계
        val openTab = PendingIntent.getActivity(
            context, 9000 + id,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TAB)
                .putExtra(MainActivity.EXTRA_TAB, 3),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(context, CHANNEL_RESULT_ID)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(if (done) openTab else null)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    fun notifyTorrent(id: Int, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 토렌트 완료 알림 탭 → 토렌트 탭으로 이동 (v0.24) — 탭 순서: 0상태 1다운로드 2토렌트 3보관함 4설정 5통계
        val openTab = PendingIntent.getActivity(
            context, 9100 + id,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TAB)
                .putExtra(MainActivity.EXTRA_TAB, 2),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(context, CHANNEL_TORRENT_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openTab)
            .setAutoCancel(true)
            .build()
        nm.notify(TORRENT_NOTIF_PREFIX + id, notif)
    }

    companion object {
        const val CHANNEL_ID = "relay_status"
        const val CHANNEL_RESULT_ID = "relay_result"
        const val CHANNEL_GATE_ID = "relay_gate"
        const val CHANNEL_TORRENT_ID = "relay_torrent"
        const val NOTIF_ID = 1001
        const val TORRENT_NOTIF_PREFIX = 30000
        const val GATE_TIMEOUT_MS = 60_000L
        const val GATE_NOTIF_PREFIX = 20000
    }
}

private fun fmtBytes(n: Long): String = when {
    n < 1_048_576 -> "${n / 1024} KB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}
