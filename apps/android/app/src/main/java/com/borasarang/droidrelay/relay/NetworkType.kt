package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager

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
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
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
    return NetworkType.UNKNOWN
}
