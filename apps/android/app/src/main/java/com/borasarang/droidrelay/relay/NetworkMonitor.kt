package com.borasarang.droidrelay.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 네트워크 상태 변화 감지 → 복구 시 FAILED 작업 자동 재시도.
 *
 * 사용:
 *   val monitor = NetworkMonitor(context) { recovered -> engine.retryFailed() }
 *   monitor.register()
 *   ...
 *   monitor.unregister()
 */
class NetworkMonitor(
    private val context: Context,
    private val onRecovered: () -> Unit,
) {
    private val TAG = "NetMon"
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    /** 네트워크 연결 복구 시 호출 */
    private var wasLost = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            DebugLogger.i(TAG, "네트워크 연결 복구 → FAILED 작업 재시도")
            if (wasLost) {
                wasLost = false
                runCatching { StatsSnapshots.recordNet(true) }
            }
            onRecovered()
        }
        override fun onLost(network: Network) {
            DebugLogger.w(TAG, "네트워크 연결 끊김")
            if (!wasLost) {
                wasLost = true
                runCatching { StatsSnapshots.recordNet(false) }
            }
        }
    }

    fun register() {
        if (registered) return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Service.onCreate 에서 throw 하면 치명적. uid 당 콜백 등록 수 상한 초과(SecurityException) 등 방어.
        runCatching { cm.registerNetworkCallback(req, callback) }
            .onFailure {
                registered = false
                DebugLogger.w(TAG, "네트워크 모니터 등록 실패 — 복구 시 재시도 재개 생략: ${it.message}")
                return
            }
        registered = true
        DebugLogger.d(TAG, "네트워크 모니터 등록 완료")
    }

    fun unregister() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        DebugLogger.d(TAG, "네트워크 모니터 해제")
    }
}
