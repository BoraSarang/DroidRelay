package com.borasarang.droidrelay.plugin

/**
 * T-1095 — adb 플러그인 계약 v1 순수 로직 (Android 의존 없음, 단위 테스트 대상).
 *
 * 계약 원천: `docs/PLUGIN_CONTRACT.md` v1.
 * 형식 문자열을 바꾸면 [PluginContractTest]가 깨진다 — 필드 순서·키 변경은 계약 버전 올림.
 */
object PluginContract {

    const val ACTION_PROBE = "com.borasarang.droidrelay.PLUGIN_PROBE"
    const val PLUGIN_VERSION = 1
    const val LOG_TAG = "DroidRelay"

    /** 계약 액션 4종 — 순서 고정 (프로브 응답 문자열 순서) */
    val PLUGIN_ACTIONS: List<String> = listOf(
        "server_status",
        "server_control",
        "download_add",
        "torrent_add",
    )

    // MainActivity 인텐트 엑스트라
    const val EXTRA_SERVER = "droidrelay.server"
    const val EXTRA_DOWNLOAD_ADD = "droidrelay.download_add"
    const val EXTRA_TORRENT_ADD = "droidrelay.torrent_add"

    const val SERVER_OP_START = "start"
    const val SERVER_OP_STOP = "stop"
    const val SERVER_OP_STATUS = "status"

    // 에러 코드 (error_message_ko.json 등록)
    const val ERR_REFUSED = "E-AND-PLG-0001"
    const val ERR_INVALID_INPUT = "E-AND-PLG-0002"
    const val ERR_EXEC_FAILED = "E-AND-PLG-0003"

    const val REFUSED_LINE = "[REMOTE] 거부됨 (연동 OFF)"

    data class Probe(
        val version: Int,
        val actions: List<String>,
        val allowed: Boolean,
    )

    /** 프로브 응답 한 줄 — 릴리즈 logcat 가시성이 필요해 호출 측은 직접 Log 출력 */
    fun buildProbeLine(allowed: Boolean): String =
        "[PLUGIN] version=$PLUGIN_VERSION " +
            "actions=${PLUGIN_ACTIONS.joinToString(",")} " +
            "logTag=$LOG_TAG " +
            "allowed=$allowed"

    fun parseProbeLine(line: String): Probe? {
        val body = line.substringAfter("[PLUGIN]", missingDelimiterValue = "") .trim()
        if (body.isEmpty()) return null
        val version = intField(body, "version=") ?: return null
        val allowedStr = stringField(body, "allowed=")?.lowercase() ?: return null
        val allowed = when (allowedStr) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        val actionsRaw = stringField(body, "actions=") ?: ""
        val actions = actionsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return Probe(version = version, actions = actions, allowed = allowed)
    }

    /** 이 프로브와 연동해도 되는가 — 버전 일치 + 액션 교집합 */
    fun supports(probe: Probe): Boolean {
        if (probe.version != PLUGIN_VERSION) return false
        return probe.actions.intersect(PLUGIN_ACTIONS.toSet()).isNotEmpty()
    }

    // ── [REMOTE] 결과 줄 ──

    fun remoteServerStatus(running: Boolean, ip: String?, port: Int, version: String): String =
        "[REMOTE] action=server_status ok=true running=$running ip=${ip ?: "-"} port=$port version=$version"

    fun remoteServerControl(op: String, running: Boolean): String =
        "[REMOTE] action=server_control ok=true op=$op running=$running"

    fun remoteDownloadAdd(id: String, duplicate: Boolean): String =
        "[REMOTE] action=download_add ok=true id=$id duplicate=$duplicate"

    fun remoteTorrentAdd(id: String, duplicate: Boolean): String =
        "[REMOTE] action=torrent_add ok=true id=$id duplicate=$duplicate"

    fun remoteFailure(action: String, errorCode: String, note: String): String =
        "[REMOTE] action=$action ok=false errorCode=$errorCode note=$note"

    // ── [EVENT] 제공자발 소식 ──

    fun eventDownloadComplete(id: String, filename: String, bytes: Long): String =
        "[EVENT] type=download_complete id=$id filename=$filename bytes=$bytes"

    fun eventDownloadFailed(id: String, filename: String, errorCode: String): String =
        "[EVENT] type=download_failed id=$id filename=$filename errorCode=$errorCode"

    fun eventTorrentComplete(id: String, name: String): String =
        "[EVENT] type=torrent_complete id=$id name=$name"

    fun eventTorrentFailed(id: String, name: String, errorCode: String): String =
        "[EVENT] type=torrent_failed id=$id name=$name errorCode=$errorCode"

    // ── 필드 추출 (`key=value`, 값은 공백·콤마·대괄호 전까지) ──

    private fun intField(text: String, key: String): Int? =
        stringField(text, key)?.toIntOrNull()

    private fun stringField(text: String, key: String): String? {
        val idx = text.indexOf(key)
        if (idx < 0) return null
        val after = text.substring(idx + key.length)
        var end = after.length
        // actions 값은 콤마 구분 목록이라 콤마에서 끊지 않는다
        val stopAtComma = key != "actions="
        for (i in after.indices) {
            val c = after[i]
            if (c.isWhitespace() || c == ']' || c == '[' || (stopAtComma && c == ',')) {
                end = i
                break
            }
        }
        val v = after.substring(0, end).trim()
        return v.ifEmpty { null }
    }
}
