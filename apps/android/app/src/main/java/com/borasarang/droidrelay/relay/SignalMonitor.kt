package com.borasarang.droidrelay.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager

/**
 * 접속 상태 스냅샷 (통신사 · RAT · RSRP/RSRQ/SINR).
 * 권한 미허용(READ_PHONE_STATE·위치)/미지원 기기에서는 null 항목으로 조용히 축소된다 —
 * 게이트는 null을 fail-open(막지 않음)으로 처리하므로 권한 없이도 안전하다.
 * (SpotShift SignalMonitor와 동일 계약 — 시골 LTE 신호 게이트용)
 */
data class SignalInfo(
    val carrier: String? = null,
    val rat: String? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
) {
    fun display(): String? {
        val parts = mutableListOf<String>()
        carrier?.let { parts += it }
        rat?.let { parts += it }
        rsrpDbm?.let { parts += "RSRP $it" }
        rsrqDb?.let { parts += "RSRQ $it" }
        sinrDb?.let { parts += "SINR $it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

class SignalMonitor(private val context: Context) {

    fun snapshot(): SignalInfo = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
            ?: return SignalInfo()
        val carrier = runCatching { tm.networkOperatorName?.takeIf { it.isNotBlank() } }
            .getOrNull()
            ?: runCatching { tm.simOperatorName?.takeIf { it.isNotBlank() } }.getOrNull()
        val rat = runCatching { ratName(tm.dataNetworkType) }.getOrNull()
            ?: cellularFallback()
        var rsrp: Int? = null
        var rsrq: Int? = null
        var sinr: Int? = null
        runCatching {
            tm.signalStrength?.cellSignalStrengths?.forEach {
                when (it) {
                    is CellSignalStrengthLte -> {
                        rsrp = it.rsrp.takeIfValid()
                        rsrq = it.rsrq.takeIfValid()
                        sinr = it.rssnr.takeIfValid()
                    }
                    is CellSignalStrengthNr -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            rsrp = it.ssRsrp.takeIfValid() ?: rsrp
                            rsrq = it.ssRsrq.takeIfValid() ?: rsrq
                            sinr = it.ssSinr.takeIfValid() ?: sinr
                        }
                    }
                }
            }
        }
        SignalInfo(carrier, rat, rsrp, rsrq, sinr)
    }.getOrDefault(SignalInfo())

    private fun Int.takeIfValid(): Int? =
        if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this

    private fun ratName(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> null
    }

    private fun cellularFallback(): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "셀룰러" else null
    }.getOrNull()
}
