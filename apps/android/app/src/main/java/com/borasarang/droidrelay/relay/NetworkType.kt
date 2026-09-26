package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkType(val icon: String, val label: String) {
    WIFI("📶", "Wi-Fi"),
    LTE("📱", "LTE"),
    NR("📡", "5G"),
    HSPA("📱", "3G"),
    EDGE("📱", "2G"),
    OFFLINE("🚫", " 오프라인"),
    UNKNOWN("❓", "알 수 없음"),
}

fun currentNetworkType(context: Context): NetworkType {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return NetworkType.OFFLINE
    val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE

    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkType.WIFI
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return cellularType(context)
    return NetworkType.UNKNOWN
}

/** 셀룰러 세대 판정 — 통신사 서비스 binder 호출이 여기 한 곳에만 남는다 */
private fun cellularType(context: Context): NetworkType {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return NetworkType.LTE
    return try {
        if (Build.VERSION.SDK_INT >= 30) {
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.NR
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.LTE
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkType.HSPA
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_GSM -> NetworkType.EDGE
                else -> NetworkType.LTE
            }
        } else {
            @Suppress("DEPRECATION")
            when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.NR
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.LTE
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkType.HSPA
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_GSM -> NetworkType.EDGE
                else -> NetworkType.LTE
            }
        }
    } catch (_: SecurityException) {
        NetworkType.LTE
    }
}

/**
 * 네트워크 타입을 푸시 방식으로 구독.
 * UI 가 5초 폴링으로 currentNetworkType 을 반복 호출하면 (KTX) 매번
 * getNetworkCapabilities + tm.dataNetworkType(통신사 서비스 binder 왕복)이 메인 스레드에서 돈다.
 * ConnectivityManager.NetworkCallback 은 이 정보를 이미 이벤트로 뿌린다.
 * 최초값을 즉시 방출하므로 별도 초기 폴링이 필요 없다.
 */
fun networkTypeFlow(context: Context): Flow<NetworkType> = callbackFlow {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) {
        trySend(currentNetworkType(context))
        awaitClose { }
        return@callbackFlow
    }
    trySend(currentNetworkType(context))
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(currentNetworkType(context))
        }
        override fun onLost(network: Network) {
            trySend(currentNetworkType(context))
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Wi-Fi ⇄ LTE 전환, 5G 폴백 등 트랜스포트 변경이 여기서 온다
            trySend(networkTypeOf(context, caps))
        }
    }
    val req = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    // 등록 실패(uid당 콜백 상한 등) 시에도 마지막 방출값은 이미 있으므로 예외만 삼킨다
    runCatching { cm.registerNetworkCallback(req, cb) }
    awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
}.distinctUntilChanged()

/** 이미 확보한 capabilities 로 타입 판정 — 네트워크 조회 불필요 */
private fun networkTypeOf(context: Context, caps: NetworkCapabilities): NetworkType {
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkType.WIFI
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return cellularType(context)
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetworkType.UNKNOWN
    return NetworkType.UNKNOWN
}
