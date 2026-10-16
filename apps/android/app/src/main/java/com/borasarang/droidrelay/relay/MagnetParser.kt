package com.borasarang.droidrelay.relay

/// **magnet URI 파싱·정화 — 순수 로직.**
///
/// ## 왜 여기 있나
///
/// 원래 `TorrentEngine.kt`(1,931줄) 안에 거대 클래스와 뒤섞여 있었다. 이 함수들은
/// 안드로이드·libtorrent 없이 테스트된다(`MagnetInfoHashTest`) — 그렇다면 엔진
/// 파일 안에 있을 이유가 없다.
///
/// ## 왜 중요한가
///
/// `sanitizeMagnetDn` 은 **크래시 방어**다: dn 꼬리가 `%E3` 처럼 잘리면 libtorrent
/// `parse_magnet_uri` 를 거쳐 `NewStringUTF` 에서 SIGABRT 를 낸다(실측 2건,
/// poison job 영속 → 기동 크래시 루프). **어떤 `session.download` 보다 먼저**
/// 이 함수를 거쳐야 한다.

/** magnet URI의 SHA-1 infohash(hex 40자) 그룹 캡처 */
private val magnetHexRegex = Regex("urn:btih:([0-9a-fA-F]{40})")
/** magnet URI의 SHA-1 infohash(base32 32자, padding 없음) 그룹 캡처 */
private val magnetBase32Regex = Regex("urn:btih:([A-Za-z2-7]{32})")

/** base32 infohash(32자) → hex(40자) — RFC 4648. 잘못된 문자면 null */
internal fun base32ToHex(s: String): String? {
    if (s.length != 32) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0
    var value = 0
    val sb = StringBuilder(40)
    for (c in s.uppercase()) {
        val idx = alphabet.indexOf(c)
        if (idx < 0) return null
        value = (value shl 5) or idx
        bits += 5
        if (bits >= 8) {
            bits -= 8
            sb.append(((value shr bits) and 0xFF).toString(16).padStart(2, '0'))
        }
    }
    return if (sb.length == 40) sb.toString() else null
}

/** magnet URI에서 infohash(SHA-1 hex 40자) 추출 — hex 40자 / base32 32자 모두 지원. 없으면 null. */
internal fun magnetInfoHash(magnet: String): String? {
    magnetHexRegex.find(magnet)?.groupValues?.get(1)?.let { return it.lowercase() }
    magnetBase32Regex.find(magnet)?.groupValues?.get(1)?.let { base32ToHex(it) }?.let { return it }
    return null
}

/** magnet 입력 길이 상한 — 수백 개 트래커가 붙은 비정상 링크의 JNI 파서 과부하 방지 */
internal const val MAX_MAGNET_LENGTH = 4096

/** magnet 입력 검증 — trim + 길이 상한 + infohash(xt=urn:btih:) 필수.
 *  통과하면 trim된 magnet을 반환, 실패하면 IllegalArgumentException (E-AND-DOWN-1003 문구).
 *  JNI(parse_magnet_uri)에 들어가기 전純 Kotlin에서 걸러내야 네이티브 크래시·독성 job 영속을 막는다. */
internal fun validateMagnet(rawMagnet: String): String {
    val magnet = rawMagnet.trim()
    require(magnet.startsWith("magnet:")) { "주소가 올바르지 않거나 지원하지 않는 파일입니다. URL을 확인해 주세요." }
    require(magnet.length <= MAX_MAGNET_LENGTH) { "마그넷 링크가 너무 깁니다. URL을 확인해 주세요." }
    require(magnetInfoHash(magnet) != null) { "주소가 올바르지 않거나 지원하지 않는 파일입니다. URL을 확인해 주세요." }
    return magnet
}

private val dnParamRegex = Regex("dn=([^&]*)")

/** magnet dn(표시 이름) 정화 — 잘린 퍼센트 이스케이프가 깨진 UTF-8이 되는 것을 복구한다.
 *
 *  실측 크래시(S22, tombstone 2건): dn 꼬리가 `%E3` 처럼 UTF-8 선행 바이트 단독으로 끝나면
 *  libtorrent의 parse_magnet_uri가 그 바이트를 add_torrent_params.name에 그대로 넣고,
 *  SessionManager.download()가 getName()으로 읽는 순간 NewStringUTF가
 *  `input is not valid Modified UTF-8` 로 프로세스를 abort(SIGABRT)시킨다.
 *  자바 catch로 잡을 수 없고, poison job이 영속되어 복원 때마다 재발(= 기동 크래시 루프)하므로
 *  **어떤 session.download 호출보다 먼저** 이 함수를 거쳐야 한다. dn은 표시용이라 꼬리 절단이 안전하다. */
internal fun sanitizeMagnetDn(magnet: String): String {
    if (!magnet.contains("dn=")) return magnet
    return dnParamRegex.replace(magnet) { m -> "dn=" + sanitizeDnValue(m.groupValues[1]) }
}

/** dn 값 복구 — 완성된 부분은 바이트 단위로 손대지 않고(의미 보존) 깨진 꼬리만 잘라낸다. */
internal fun sanitizeDnValue(enc: String): String {
    var s = enc
    while (true) {
        // 값 끝의 미완성 이스케이프("%" / "%H") 제거 — "%E3" 같은 완성형은 여기서 안 잘림
        s = s.replace(Regex("%(?:[0-9A-Fa-f])?$"), "")
        if (s.isEmpty() || dnValueUtf8Valid(s)) return s
        // 마지막 1바이트 제거 (완성형 %XX면 3문자, 아니면 1문자) 후 재시도 — 반드시 짧아지므로 종료 보장
        s = if (s.length >= 3 && s[s.length - 3] == '%' &&
            s[s.length - 2].digitToIntOrNull(16) != null && s[s.length - 1].digitToIntOrNull(16) != null
        ) {
            s.dropLast(3)
        } else {
            s.dropLast(1)
        }
    }
}

/** dn 값을 %XX 디코드한 바이트열이 엄격한 UTF-8인지 — 형식 어긋난 %는 문자 그대로 취급 */
private fun dnValueUtf8Valid(enc: String): Boolean {
    val out = java.io.ByteArrayOutputStream(enc.length)
    var i = 0
    while (i < enc.length) {
        val c = enc[i]
        if (c == '%' && i + 2 <= enc.lastIndex) {
            val v1 = enc[i + 1].digitToIntOrNull(16)
            val v2 = enc[i + 2].digitToIntOrNull(16)
            if (v1 != null && v2 != null) {
                out.write((v1 shl 4) or v2)
                i += 3
                continue
            }
        }
        val cp = enc.codePointAt(i)
        out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(cp)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
        true
    } catch (_: java.nio.charset.CharacterCodingException) {
        false
    }
}
