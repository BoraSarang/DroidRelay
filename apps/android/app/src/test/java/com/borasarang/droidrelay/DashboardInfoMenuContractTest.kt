package com.borasarang.droidrelay

import com.borasarang.droidrelay.relay.WebAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대시보드 헤더 통합 메뉴 (v0.41) — 서버 설명 + 통계를 📊 아이콘 1개 드롭다운으로.
 *
 * 배경: 서버 설명 바(`.info`)는 상시 노출 1줄을 먹고, 통계는 5번째 탭으로 묶여 있어
 * 모바일(≤640px)에서 헤더·탭 줄이 2줄로 밀렸다. 둘을 헤더 우측 아이콘 하나로 접었다.
 *
 * 부수 목표: **드롭다운이 닫혀 있으면 통계 API 요청이 0건**이어야 한다.
 * v0.40 Phase 2 의 "대시보드 유휴 부하 0" 계약을 이 화면에서 지키는지가 본 테스트의 핵심.
 *
 * 이 테스트는 배포되는 HTML/JS 에 그 계약이 실제로 들어 있는지 고정한다.
 * (행동 검증은 `scripts/verify_dashboard_info_menu.js` — Node 로 실제 블록을 실행)
 */
class DashboardInfoMenuContractTest {

    private val html: String get() = WebAssets.dashboardHtml

    /** 탭/패널 블록만 잘라낸다 (설정 섹션의 switchTab 중복 정의를 오인하지 않도록) */
    private fun tabsBlock(): String {
        val start = html.indexOf("""<div class="tabs">""")
        assertTrue("탭 블록 시작점을 찾을 수 없음", start >= 0)
        val end = html.indexOf("""</div>""", html.indexOf("""switchTab('settings')""", start))
        assertTrue("탭 블록 끝을 찾을 수 없음", end >= 0)
        return html.substring(start, end + 6)
    }

    // ── 구조: 통계 탭 제거, 설명·통계는 드롭다운 한 곳으로 ──────

    @Test
    fun `탭은 네 개다`() {
        val b = tabsBlock()
        assertFalse("통계 탭이 남아 있다", b.contains("switchTab('stats')"))
        listOf("dl", "torrent", "storage", "settings").forEach {
            assertTrue("탭 $it 이 없다", b.contains("switchTab('$it')"))
        }
    }

    @Test
    fun `전용 통계 패널이 제거되었다`() {
        assertFalse("#panel-stats 가 남아 있다", html.contains("id=\"panel-stats\""))
        assertFalse("panel-stats 토글 분기가 남아 있다", html.contains("getElementById('panel-stats')"))
    }

    @Test
    fun `통계 탭 진입 분기가 제거되었다`() {
        assertFalse("switchTab 의 통계 분기가 남아 있다", html.contains("if(t==='stats')"))
    }

    @Test
    fun `통합 버튼은 형제 컨트롤과 같은 metrics 다`() {
        // v0.41 실측 결함: base 규칙에 min-height:44px 를 두면 데스크톱에서 이 버튼만
        // 34px 형제(#btnRefresh) 보다 10px 커져 헤더가 어긋났다(사파리에서 더 두드러짐).
        // 터치 타깃 44px 은 모바일 쿼리로만 준다.
        val base = html.indexOf("#btnInfoMenu{position:relative;")
        assertTrue("#btnInfoMenu base 규칙을 찾을 수 없음", base >= 0)
        val rule = html.substring(base, html.indexOf("}", base) + 1)
        assertFalse("base 규칙에 min-height 가 있다 — 데스크톱에서 형제보다 커진다", rule.contains("min-height"))
        assertTrue("형제 버튼(#btnRefresh)과 padding 이 다르다", rule.contains("padding:8px 13px"))
        assertTrue("형제 버튼과 line-height 가 다르다", rule.contains("line-height:1"))
        assertTrue(
            "이모지 세로 정렬을 엔진에 맡기고 있다 (Safari Apple Color Emoji line box 불일치)",
            rule.contains("align-items:center"),
        )
        assertTrue(
            "모바일 터치 타깃 44px 규칙이 없다",
            html.contains("#btnInfoMenu{padding:12px 16px;min-height:44px;min-width:44px}"),
        )
    }

    // ── 드롭 대상 표시 (v0.41) ─────────────────────────────

    @Test
    fun `다운로드와 토렌트에도 사각 점선 드롭 태두리가 있다`() {
        // 보관함(.file-row.drop-target) 과 같은 시각 언어를 순서변경 카드에도 준다
        assertTrue(
            "카드에 사각 점선 태두리가 없다",
            html.contains(".card.drop-before,.card.drop-after{outline:2px dashed var(--accent);outline-offset:-2px;background:var(--sel)}"),
        )
        assertFalse("구식 box-shadow 만의 표시가 남아 있다", html.contains(".card.drop-before{box-shadow:"))
    }

    @Test
    fun `순서변경은 삽입 위치까지 구분해 보여준다`() {
        // 점선만으로는 위/아래 어디에 끼는지 알 수 없다 — 삽입선이 있어야 한다
        assertTrue("삽입선(before) 이 없다", html.contains(".card.drop-before::before{top:-2px}"))
        assertTrue("삽입선(after) 이 없다", html.contains(".card.drop-after::before{bottom:-2px}"))
        assertTrue(
            "삽입선 pseudo 요소가 없다",
            html.contains(".card.drop-before::before,.card.drop-after::before{content:'';position:absolute;"),
        )
        assertTrue("삽입선의 위치 기준이 없다", html.contains(".card{position:relative}"))
    }

    @Test
    fun `빈 공간 드롭은 목록 테두리로 안내한다`() {
        assertTrue(
            "빈 공간 표시 CSS 가 없다",
            html.contains("#list.drop-empty,#torrentList.drop-empty{outline:2px dashed var(--line2)"),
        )
        assertTrue(
            "dragover 가 drop-empty 를 토글하지 않는다",
            html.contains("el.classList.toggle('drop-empty',!over||over===window.__dragSrc)"),
        )
        assertTrue("정리 시 drop-empty 가 남는다", html.contains("el.classList.remove('drop-empty')"))
    }

    @Test
    fun `다운로드와 토렌트 목록이 모두 재바인딩된다`() {
        assertTrue("재바인딩 대상 목록이 아니다", html.contains("['list','torrentList'].forEach(function(cid){"))
        assertTrue(
            "다운로드 reorder API 가 없다",
            html.contains("cid==='list'?'/api/jobs/reorder':'/api/torrents/reorder'"),
        )
    }

    @Test
    fun `드롭다운은 헤더 액션 그룹 안에 앵커되어 있다`() {
        // v0.41 실제 결함 2건 (둘 다 브라우저 실측으로 발견 — 정적 grep 으로는 안 잡힌다):
        //  1. .mm 을 .wrap 하단에 두면 position:absolute 의 기준이 초기 포함 블록(문서)이 되어
        //     화면 아래(뷰포트 높이 + 8px)로 튀어나간다.
        //  2. 버튼만 감싼 44px 래퍼를 앵커로 쓰면 좁은 화면에서 좌측으로 넘친다
        //     (실측 390px 뷰포트 → dropdown left = -155px).
        //     `right:0` 이 앵커의 우측 끝(=버튼 우측)에 걸리므로 앵커는 액션 그룹 전체여야 한다.
        val acts = html.indexOf("""<div class="hd-acts">""")
        val menu = html.indexOf("""id="infoMenu"""")
        assertTrue(".hd-acts 를 찾을 수 없음", acts >= 0)
        assertTrue("#infoMenu 을 찾을 수 없음", menu >= 0)
        assertTrue("#infoMenu 이 .hd-acts 보다 앞에 있다", menu > acts)
        val between = html.substring(acts, menu)
        assertTrue("버튼이 .hd-acts 밖이다", between.contains("""id="btnInfoMenu""""))
        assertEquals(
            "드롭다운이 .hd-acts 의 직계 자식이 아니다 (버튼 뒤에 래퍼 요소가 끼어 있음)",
            0,
            Regex("</div>").findAll(between).count(),
        )
        assertTrue(
            ".hd-acts 에 position:relative 가 없다",
            html.contains("gap:8px;margin-left:auto;position:relative}"),
        )
        assertFalse("버튼만 감싼 .mm-wrap 앵커가 남아 있다", html.contains("mm-wrap"))
    }

    @Test
    fun `서버 설명과 통계 DOM 이 드롭다운 안에 있다`() {
        val menu = html.indexOf("""id="infoMenu"""")
        assertTrue("#infoMenu 을 찾을 수 없음", menu >= 0)
        val menuEnd = html.indexOf("</div>\n</div>", menu)
        assertTrue("#infoMenu 닫힘을 찾을 수 없음", menuEnd > menu)
        val inner = html.substring(menu, menuEnd)
        listOf("id=\"info\"", "id=\"statsCards\"", "id=\"statsHighlights\"", "id=\"statsRecords\"", "id=\"statsChart\"", "id=\"statsBreakdown\"")
            .forEach { assertTrue("$it 가 드롭다운 밖에 있다", inner.contains(it)) }
    }

    @Test
    fun `헤더에 통합 아이콘 버튼이 있고 접근성 속성이 있다`() {
        assertTrue("📊 통합 버튼이 없다", html.contains("id=\"btnInfoMenu\""))
        assertTrue("토글 핸들러가 연결되지 않았다", html.contains("onclick=\"toggleInfoMenu()\""))
        assertTrue("aria-haspopup 이 없다", html.contains("aria-haspopup=\"true\""))
        assertTrue("aria-expanded 초기값이 없다", html.contains("aria-expanded=\"false\""))
        assertTrue("aria-controls 가 없다", html.contains("aria-controls=\"infoMenu\""))
    }

    @Test
    fun `드롭다운이 우측 정렬되고 스크롤 가능해야 한다`() {
        assertTrue("우측 정렬이 없다", html.contains(".mm{position:absolute;"))
        assertTrue("폭 상한이 없다", html.contains("width:min(520px,calc(100vw - 24px))"))
        assertTrue("높이 상한·내부 스크롤이 없다", html.contains("max-height:min(78vh,720px);overflow-y:auto"))
        assertTrue("모바일 스크롤 연동 차단이 없다", html.contains("overscroll-behavior:contain"))
    }

    @Test
    fun `헤더 액션 그룹은 줄바꿈돼도 우측에 남는다`() {
        // v0.41 실제 결함: 모바일에서 .hd 가 줄바꿈되면 두 번째 줄에 .hd-acts 혼자 남아
        // space-between 이 좌측 정렬로 떨어진다. 그러면 우측 정렬 드롭다운이 화면 밖으로 넘친다
        // (실측: 390px 뷰포트에서 dropdown left = -155px).
        assertTrue(".hd-acts 에 margin-left:auto 가 없다", html.contains("gap:8px;margin-left:auto;position:relative}"))
    }

    // ── 닫기 UX 4종 ──────────────────────────────────────────

    @Test
    fun `바깥 클릭으로 닫는다`() {
        assertTrue("바깥 클릭 닫기 리스너가 없다", html.contains("document.addEventListener('click',function(e){\n  if(!window.__infoMenuOpen)return;"))
        assertTrue("contains 판정이 없다 (토글과 충돌)", html.contains("m.contains(e.target)"))
        assertTrue("버튼 예외 처리가 없다", html.contains("b.contains(e.target)"))
    }

    @Test
    fun `Esc 으로 닫는다`() {
        val idx = html.indexOf("if(e.key==='Escape'){closeInfoMenu();return;}")
        assertTrue("Esc 닫기 분기가 없다", idx >= 0)
        // 입력 요소 커서 가드보다 **먼저** 와야 한다 — 가드 뒤면 입력 중 Esc 이 먹지 않는다
        val guard = html.indexOf("if(t&&(t.tagName==='INPUT'||t.tagName==='SELECT'||t.tagName==='TEXTAREA'))return;", idx)
        assertTrue("입력 요소 가드를 찾을 수 없음", guard > idx)
    }

    @Test
    fun `탭 전환과 S 단축키로 토글한다`() {
        val sw = html.indexOf("function switchTab(t){")
        assertTrue("switchTab 을 찾을 수 없음", sw >= 0)
        val head = html.substring(sw, sw + 200)
        assertTrue("switchTab 이 통합 메뉴를 닫지 않는다", head.contains("closeInfoMenu()"))
        assertTrue("S 단축키가 없다", html.contains("if(e.key==='s'||e.key==='S'){toggleInfoMenu();}"))
    }

    @Test
    fun `열 때만 통계를 1회 갱신한다`() {
        val open = html.indexOf("function openInfoMenu(){")
        assertTrue("openInfoMenu 을 찾을 수 없음", open >= 0)
        val body = html.substring(open, html.indexOf("function closeInfoMenu", open))
        assertTrue("열 때 refreshStats 호출이 없다", body.contains("refreshStats()"))
        assertTrue("열 때 updateInfoBar 호출이 없다 (설명이 비어 보인다)", body.contains("updateInfoBar()"))
    }

    // ── 부하 계약: 닫힘 = 통계 요청 0 ─────────────────────────

    @Test
    fun `닫힘 상태에서는 통계를 갱신하지 않는다`() {
        assertFalse("구식 curTab==='stats' 게이트가 남아 있다", html.contains("curTab==='stats'"))
        val idx = html.indexOf("if(window.__infoMenuOpen&&Date.now()-__statsAt>15000)refreshStats();")
        assertTrue("드롭다운 열림 게이트가 없다", idx >= 0)
    }

    @Test
    fun `드롭다운 초기 상태는 닫혀 있고 aria 와 일치한다`() {
        assertTrue("#infoMenu 에 hidden 이 없다", html.contains("""<div class="mm" id="infoMenu" hidden>"""))
        assertTrue(".mm[hidden] CSS 가드 없다", html.contains(".mm[hidden]{display:none}"))
    }

    // ── 유휴 DOM 쓰기 계약 (v0.40 Phase 2 계승) ───────────────

    @Test
    fun `설명 바는 내용이 바뀔 때만 DOM 을 쓴다`() {
        val idx = html.indexOf("if(window.__infoHtml!==html){")
        assertTrue("__infoHtml 문자열 캐시 가드가 없다 (매 tick innerHTML 쓰기)", idx >= 0)
    }

    @Test
    fun `상태 배지가 활성·스로틀 상태를 구분한다`() {
        val idx = html.indexOf("function updateInfoBadge(")
        assertTrue("updateInfoBadge 를 찾을 수 없음", idx >= 0)
        val body = html.substring(idx, html.indexOf("// ── 헤더 통합 메뉴", idx))
        assertTrue("스로틀 배지 분기가 없다", body.contains("dot.classList.toggle('warn',throttled)"))
        assertTrue("유휴 상태 배지 숨김 분기가 없다", body.contains("dot.hidden=!busy&&!throttled;"))
        assertTrue("배지를 updateInfoBar 에서 호출하지 않는다", html.contains("updateInfoBadge(running.length,tActive.length,gs,"))
    }

    // ── 회귀: 기존 기능 계약이 유지되는가 ──────────────────────

    @Test
    fun `정보 바 CSS 클래스는 다른 UI 에서 재사용되므로 유지된다`() {
        // 비디오 분석 진행 박스 6곳이 class="info" 를 공용 박스로 쓴다
        assertTrue(".info 클래스 정의가 사라졌다", html.contains(".info{display:flex;"))
        assertTrue("비디오 진행 박스가 .info 를 쓰지 않는다", html.contains("""area.innerHTML='<div class="info">다운로드 시작 중…</div>'"""))
    }

    @Test
    fun `기존 실시간 갱신 계약 블록은 그대로다`() {
        // T-701/Phase 2 계약 — 통합 메뉴 변경이 이 블록을 건드리면 안 된다
        val start = html.indexOf("var __pollTimer=null;")
        assertTrue("실시간 갱신 블록이 없다", start >= 0)
        val end = html.indexOf("if(!document.hidden)connect();", start)
        val b = html.substring(start, end)
        assertTrue("visibilitychange 가드 제거", b.contains("addEventListener('visibilitychange'"))
        assertTrue("pagehide 가드 제거", b.contains("addEventListener('pagehide',closeStream)"))
    }
}
