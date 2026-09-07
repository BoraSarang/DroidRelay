package com.borasarang.droidrelay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.borasarang.droidrelay.relay.ServerToggle

/** 홈 화면 서버 토글 위젯 (T-953) */
class ServerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ServerToggle.ACTION_TOGGLE) {
            ServerToggle.toggle(context)
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(
                android.content.ComponentName(context, ServerWidget::class.java),
            ).forEach { updateWidget(context, manager, it) }
        }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val running = ServerToggle.isRunning(context)
            val views = RemoteViews(context.packageName, R.layout.widget_server)
            views.setTextViewText(R.id.widget_status, if (running) "● 실행 중" else "○ 정지됨")
            views.setTextViewText(R.id.widget_toggle, if (running) "정지" else "시작")
            val intent = Intent(context, ServerWidget::class.java).setAction(ServerToggle.ACTION_TOGGLE)
            views.setOnClickPendingIntent(
                R.id.widget_toggle,
                PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(id, views)
        }
    }
}
