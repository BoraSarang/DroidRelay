package com.borasarang.droidrelay.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import com.borasarang.droidrelay.R

class DebugOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wm: WindowManager? = null
    private var overlayView: TextView? = null
    private var scrollView: ScrollView? = null
    private var timerJob: kotlinx.coroutines.Job? = null
    private var paused = false
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        DebugLogger.i("DebugOverlay", "오버레이 서비스 시작")
        startInForeground()

        val tv = TextView(this).apply {
            setBackgroundColor(0xE60A1428.toInt())
            setTextColor(0xFF8FD8FF.toInt())
            textSize = 10f
            setPadding(16, 8, 16, 8)
            maxLines = 30
            setSingleLine(false)
            text = "디버그 로그 로딩 중..."
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        val params = layoutParams as WindowManager.LayoutParams
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        wm?.updateViewLayout(this, params)
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    else -> false
                }
            }
            setOnClickListener {
                paused = !paused
            }
        }

        val sv = ScrollView(this).apply { addView(tv) }
        scrollView = sv
        overlayView = tv

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        wm?.addView(sv, params)
        startPolling()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private fun startPolling() {
        timerJob = scope.launch {
            while (isActive) {
                if (!paused) {
                    val lines = DebugLogger.lines()
                    val apiLines = DebugLogger.apiLines()
                    val recent = (lines + apiLines).sortedByDescending {
                        it.substringAfter("[").substringBefore("]")
                    }.take(30)
                    val text = recent.joinToString("\n")
                    if (text != lastText) {
                        lastText = text
                        withContext(Dispatchers.Main) {
                            overlayView?.text = text
                            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    /** 포그라운드 등록 (RelayService의 relay_status 채널·아이콘 재사용) */
    private fun startInForeground() {
        val notif = Notification.Builder(this, "relay_status")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText("디버그 오버레이 로그 표시 중")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isRunning = false
        timerJob?.cancel()
        runCatching { wm?.removeView(scrollView) }
        DebugLogger.i("DebugOverlay", "오버레이 서비스 중지")
        super.onDestroy()
    }

    companion object {
        @Volatile var isRunning = false
            private set

        private const val NOTIF_ID = 2002

        fun hasPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (hasPermission(context)) {
                context.startForegroundService(Intent(context, DebugOverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DebugOverlayService::class.java))
        }
    }
}
