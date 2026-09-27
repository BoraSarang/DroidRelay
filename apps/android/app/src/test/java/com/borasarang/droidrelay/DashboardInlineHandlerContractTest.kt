package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대시보드 인라인 핸들러 — HTML 속성 조작(SyntaxError) 계약.
 *
 * 배경: 브레드크럼이 `onclick="openDir(" + JSON.stringify(path) + ")"` 로 값을 끼우면
 * `JSON.stringify` 가 낸 큰따옴표가 **큰따옴표로 감싼 속성**을 중간에서 닫아 버린다.
 * 실제 파싱 결과는 `onclick="openDir("` 이었고, 사용자가 상위 폴더("M") 를 누를 때
 * `⚠ JS 오류: SyntaxError: Unexpected token '}'` 가 떴다. 브레드크럼 전체가 죽는다.
 *
 * 근본 규칙: **인라인 핸들러에 사용자 값을 넣지 않는다.**
 * 값이 필요하면 위임 리스너 + `data-*` 속성(`esc` 적용) 또는 `jsArg()` 를 쓴다.
 */
class DashboardInlineHandlerContractTest {

    private val html: String get() = WebAssets.dashboardHtml

    /**
     * 배포 HTML 전체를 훑어 인라인 핸들러 중 **동적으로 값이 끼어드는 지뢰**를 찾는다.
     * 큰따옴표로 감싼 속성 안에 홀수따옴표 JS 문자열이 들어가 있으면 파서가 잘릴 수 있다.
     * 서버가 발급한 id 같은 안전한 값(`' + j.id + '` 형태)만 통과시킨다.
     */
    private fun riskyInlineHandlers(): List<String> {
        val out = mutableListOf<String>()
        val re = Regex("""on(click|change|input)="([^"]*)"""")
        // 사용자 자유 텍스트로 오인될 수 있는 변수 — 이건 인라인에 절대 넣으면 안 된다
        val freeText = Regex("""^($FREE_TEXT_VAR)(\.|$)""")
        for (m in re.findAll(html)) {
            val body = m.groupValues[2]
            // R1 — 홀수따옴표 = 속성이 잘린 흔적
            val singleQuotes = body.count { it == '\'' }
            if (singleQuotes % 2 == 1) out += "R1 홀수따옴표 → " + m.value
            // R2 — 자유 텍스트/직렬화 결과가 이스케이프 없이 인라인에 삽입됨
            for (i in Regex("""\+([^"'()]{1,40})\+""").findAll(body)) {
                val expr = i.groupValues[1].trim()
                if (expr.contains("JSON.stringify")) { out += "R2 JSON.stringify → " + m.value; continue }
                if (freeText.containsMatchIn(expr) && !expr.startsWith("jsArg")) {
                    out += "R2 자유텍스트($expr) → " + m.value
                }
            }
        }
        return out
    }

    private companion object {
        /** 자유 텍스트 변수명 (인라인 값으로 넘기면 위험한 것) */
        const val FREE_TEXT_VAR = "v|url|uri|name|path|title|label|q|s|str|val|key"
    }

    // ── 보고된 버그 ────────────────────────────────────────

    @Test
    fun `브레드크럼은 인라인 핸들러를 쓰지 않는다`() {
        val start = html.indexOf("function updateBreadcrumb(){")
        assertTrue("updateBreadcrumb 을 찾을 수 없음", start >= 0)
        // 함수 본문은 열림 중괄호로 닫히고 다음 줄바꿈 직후 끝난다
        val end = html.indexOf("\n}", start)
        assertTrue("updateBreadcrumb 의 끝을 찾을 수 없음", end > start)
        // 주석에 단어 `onclick` 이 들어 있어도 코드가 그것을 쓰는 게 아니다
        val body = html.substring(start, end)
            .lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertFalse("브레드크럼이 onclick 을 다시 인라인으로 넣었다", body.contains("onclick="))
        assertFalse("JSON.stringify 결과를 속성에 직접 끼운다", body.contains("JSON.stringify"))
        assertTrue("data-path 로 위임해야 한다", body.contains("data-path="))
    }

    @Test
    fun `브레드크럼 클릭은 위임 리스너로 이동한다`() {
        assertTrue(
            "위임 click 리스너가 없다",
            html.contains("bc.addEventListener('click',function(e){"),
        )
        assertTrue("dataset.path 로 이동해야 한다", html.contains("openDir(span.dataset.path||'')"))
    }

    @Test
    fun `드롭 후 잔여 click 이 이동을 중복 실행하지 않는다`() {
        assertTrue(
            "drop 후 click 억제가 없다",
            html.contains("suppressClickUntil=Date.now()+400;"),
        )
    }

    // ── 근본 규칙 ──────────────────────────────────────────

    @Test
    fun `인라인 핸들러 어디에도 값 조작이 새어나가지 않는다`() {
        val risky = riskyInlineHandlers()
        assertTrue("위험한 인라인 핸들러가 있다:\n" + risky.joinToString("\n"), risky.isEmpty())
    }

    @Test
    fun `jsArg 헬퍼가 값 조작을 모두 막는다`() {
        val start = html.indexOf("function jsArg(s){")
        assertTrue("jsArg 헬퍼가 없다", start >= 0)
        val body = html.substring(start, html.indexOf("}", start) + 1)
        listOf("""replace(/\\/g,'\\\\')""", """replace(/'/g,"\\'")""", """&quot;""", """&lt;""", """&amp;""")
            .forEach { assertTrue("jsArg 에 $it 이 없다", body.contains(it)) }
    }

    @Test
    fun `사용자 값이 인라인에 들어가면 jsArg 를 쓴다`() {
        // 비디오 URL(사용자 입력)이 createVideo / retryVideo 로 인라인 전달된다
        assertFalse(
            "createVideo 가 원본 URL 을 그대로 인라인에 넣는다",
            html.contains("""createVideo(\''+v.replace("""),
        )
        assertTrue("createVideo 가 jsArg 를 쓰지 않는다", html.contains("""createVideo(\''+jsArg(v)+'\')"""))
        assertTrue("retryVideo 가 jsArg 를 쓰지 않는다", html.contains("""retryVideo(\''+jsArg(j.url)+'\')"""))
    }
}
