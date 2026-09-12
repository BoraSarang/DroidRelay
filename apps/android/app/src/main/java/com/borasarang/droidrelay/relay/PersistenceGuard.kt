package com.borasarang.droidrelay.relay

import java.io.File

/** 저장소 무결성 가드 (v0.22 Phase A, Motrix db_guard 차용).
 *
 * - jobs.json / torrents.json 손상 시 원본을 .bak으로 보존하고 빈 목록으로 복구
 * - 자동 삭제 금지, 초기화 전 반드시 백업
 * - 시크릿 마스킹은 진단 번들용으로만 제공 (영속에 사용 금지)
 */
object PersistenceGuard {
    const val JOBS_SCHEMA_VERSION = 1
    const val TORRENTS_SCHEMA_VERSION = 1

    /** 손상 파일을 `<name>.bak`으로 보존. 성공 시 백업 File, 실패 시 null */
    fun backupCorrupt(file: File): File? {
        return try {
            if (!file.exists()) return null
            val bak = File(file.parentFile, "${file.name}.bak")
            file.copyTo(bak, overwrite = true)
            DebugLogger.w("Persist", "손상 파일 백업 ${file.name} → ${bak.name} (E-AND-DOWN-2003)")
            bak
        } catch (e: Exception) {
            DebugLogger.e("Persist", "손상 파일 백업 실패 ${file.name}", e)
            null
        }
    }

    /** 진단 번들용 설정 마스킹 — 비밀번호·API키·시크릿은 "***" */
    fun maskSecrets(s: AppSettings): Map<String, Any?> = mapOf(
        "configVersion" to s.configVersion,
        "port" to s.port,
        "themeMode" to s.themeMode.name,
        "concurrency" to s.concurrency,
        "autoStart" to s.autoStart,
        "notifications" to s.notifications,
        "webAuthEnabled" to s.webAuthEnabled,
        "webUser" to s.webUser,
        "webPassword" to "***",
        "accessScope" to s.accessScope.name,
        "torrentMaxActive" to s.torrentMaxActive,
        "torrentDhtEnabled" to s.torrentDhtEnabled,
        "guardEnabled" to s.guardEnabled,
        "webhookEnabled" to s.webhookEnabled,
        "webhookUrl" to s.webhookUrl,
        "webhookSecret" to "***",
        "tunnelEnabled" to s.tunnelEnabled,
        "tunnelProvider" to s.tunnelProvider,
        "mcpPrivacyMode" to s.mcpPrivacyMode,
        "scheduleEnabled" to s.scheduleEnabled,
        "scheduleCron" to s.scheduleCron,
        "watchdogIntervalSec" to s.watchdogIntervalSec,
        "forceHttpsRedirect" to s.forceHttpsRedirect,
        "storageQuotaGb" to s.storageQuotaGb,
        "autoClassify" to s.autoClassify,
        "searchEnabled" to s.searchEnabled,
        "searchUrl" to s.searchUrl,
        "searchApiKey" to "***",
        "debridEnabled" to s.debridEnabled,
        "debridProvider" to s.debridProvider,
        "debridApiKey" to "***",
        "guestEnabled" to s.guestEnabled,
    )
}
