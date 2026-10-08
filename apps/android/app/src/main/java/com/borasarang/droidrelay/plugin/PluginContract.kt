package com.borasarang.droidrelay.plugin

/**
 * T-1096 — Plugin SDK v2 순수 로직 (Android 의존 없음, 단위 테스트 대상).
 *
 * 규칙 원천: RelayConsole `docs/PLUGIN_SDK.md` v2.
 * 형식 문자열을 바꾸면 [PluginContractTest]가 깨진다 — 순서 변경·改名·삭제는 계약 버전 올림.
 */
object PluginContract {

    const val ACTION_PROBE = "com.borasarang.droidrelay.PLUGIN_PROBE"
    const val ACTION_INVOKE = "com.borasarang.droidrelay.PLUGIN_ACTION"
    const val PLUGIN_VERSION = 2
    const val LOG_TAG = "DroidRelay"
    const val PROVIDER_AUTHORITY = "com.borasarang.droidrelay.plugin"

    /** 계약 액션 4종 — 순서 고정 (프로브 응답 문자열 순서) */
    val PLUGIN_ACTIONS: List<String> = listOf(
        "server_status",
        "server_control",
        "download_add",
        "torrent_add",
    )

    // 브로드캐스트 호출 엑스트라 (§4.1: --es cmd <id> [--es arg <값>])
    const val EXTRA_CMD = "cmd"
    const val EXTRA_ARG = "arg"

    const val SERVER_OP_START = "start"
    const val SERVER_OP_STOP = "stop"

    // 에러 코드 (error_message_ko.json 등록, SDK §5.1 E-*-PLG-* 적합)
    const val ERR_REFUSED = "E-AND-PLG-0001"
    const val ERR_INVALID_INPUT = "E-AND-PLG-0002"
    const val ERR_EXEC_FAILED = "E-AND-PLG-0003"

    const val REFUSED_LINE = "[REMOTE] 거부됨 (연동 OFF)"

    // EVENT 레벨 (§6 — 소비자는 타입별 매핑표 없이 심각도를 정한다)
    const val LEVEL_INFO = "info"
    const val LEVEL_WARNING = "warning"

    data class Probe(
        val version: Int,
        val actions: List<String>,
        val allowed: Boolean,
        val appVersion: String,
    )

    /** 프로브 응답 한 줄 — 릴리즈 logcat 가시성이 필요해 호출 측은 직접 Log 출력 */
    fun buildProbeLine(allowed: Boolean, appVersion: String): String =
        "[PLUGIN] version=$PLUGIN_VERSION " +
            "actions=${PLUGIN_ACTIONS.joinToString(",")} " +
            "logTag=$LOG_TAG " +
            "allowed=$allowed " +
            "appVersion=$appVersion"

    fun parseProbeLine(line: String): Probe? {
        val body = line.substringAfter("[PLUGIN]", missingDelimiterValue = "").trim()
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
        val appVersion = stringField(body, "appVersion=") ?: return null
        return Probe(version = version, actions = actions, allowed = allowed, appVersion = appVersion)
    }

    /** 이 프로브와 연동해도 되는가 — 버전 일치 + 액션 교집합 */
    fun supports(probe: Probe): Boolean {
        if (probe.version != PLUGIN_VERSION) return false
        return probe.actions.intersect(PLUGIN_ACTIONS.toSet()).isNotEmpty()
    }

    // ── [REMOTE] 결과 줄 (§5: action=·ok= 필수, 실패는 errorCode= + 꼬리 note=) ──

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

    // ── [EVENT] 제공자발 소식 (§6: type=·id=·level= 필수) ──

    fun eventDownloadComplete(id: String, filename: String, bytes: Long): String =
        "[EVENT] type=download_complete id=$id level=$LEVEL_INFO filename=${token(filename)} bytes=$bytes"

    fun eventDownloadFailed(id: String, filename: String, errorCode: String): String =
        "[EVENT] type=download_failed id=$id level=$LEVEL_WARNING filename=${token(filename)} errorCode=$errorCode"

    fun eventTorrentComplete(id: String, name: String): String =
        "[EVENT] type=torrent_complete id=$id level=$LEVEL_INFO name=${token(name)}"

    fun eventTorrentFailed(id: String, name: String, errorCode: String): String =
        "[EVENT] type=torrent_failed id=$id level=$LEVEL_WARNING name=${token(name)} errorCode=$errorCode"

    /**
     * 표시 토큰 — §5.2 값 규칙(값은 공백 전까지)을 지키기 위한 sanitize.
     * 파일명은 공백을 포함할 수 있어 그대로 두면 파서가 잘라낸다.
     * 실제 파일명은 앱 내부 그대로, 로그 표시용만 `_`로 치환한다.
     */
    fun token(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("_").ifEmpty { "-" }

    // ── actionsJson (§3 L2 핵심 — 소비자 제네릭 렌더러 입력, 고정 문자열만) ──

    fun actionsJson(): String {
        fun entry(id: String, title: String, kind: String, hint: String?, cmd: String, withArg: Boolean): String {
            val hintPart = if (hint != null) ",\"hint\":\"$hint\"" else ""
            val argPart = if (withArg) ",\"arg\":\"\$input\"" else ""
            return "{\"id\":\"$id\",\"title\":\"$title\",\"kind\":\"$kind\"$hintPart," +
                "\"invoke\":{\"via\":\"broadcast\",\"action\":\"$ACTION_INVOKE\"," +
                "\"extra\":{\"cmd\":\"$cmd\"$argPart}}}"
        }
        return "[" +
            entry("server_status", "서버 상태 조회", "button", null, "server_status", false) + "," +
            entry("server_control", "서버 시작/정지", "text", "start|stop", "server_control", true) + "," +
            entry("download_add", "URL 추가", "text", "https://…", "download_add", true) + "," +
            entry("torrent_add", "magnet 추가", "text", "magnet:…", "torrent_add", true) +
            "]"
    }

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
