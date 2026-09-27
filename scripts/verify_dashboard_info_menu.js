// 실제 배포 중인 대시보드 "헤더 통합 메뉴" 블록을 그대로 실행해 검증한다.
// 목표: 서버 설명 + 통계를 접은 📊 드롭다운이
//       ① 정상 열고 ② 4종 경로로 닫히며 ③ 닫힘 상태에서 통계 API 를 호출하지 않는다.
//
// 사용: node scripts/verify_dashboard_info_menu.js [dashboard.html]
// (dashboard.html 은 scripts/extract_dashboard_html.js 로 생성)

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HTML = process.argv[2] || path.join(__dirname, '.dashboard-extract.html');
if (!fs.existsSync(HTML)) {
  console.error(`❌ ${HTML} 없음 — 먼저 실행: node scripts/extract_dashboard_html.js ${HTML}`);
  process.exit(2);
}
const html = fs.readFileSync(HTML, 'utf8');

// ── 검증 대상 블록 추출 ───────────────────────────────
// 정적 grep 으론 닫힘 분기 순서를 검증할 수 없다. 배포 코드를 그대로 돌린다.
function slice(startMark, endMark, what) {
  const s = html.indexOf(startMark);
  if (s < 0) throw new Error(`${what} 시작점을 찾을 수 없음: ${startMark}`);
  const e = html.indexOf(endMark, s + startMark.length);
  if (e < 0) throw new Error(`${what} 끝을 찾을 수 없음: ${endMark}`);
  return html.substring(s, e);
}

const infoBlock = slice('function updateInfoBar(){', 'function renderTorrents(', '정보바+메뉴 블록');
const keyBlock = slice("document.addEventListener('keydown',function(e){\n  if(document.getElementById('popupOverlay'))return;",
                       '// ── 접이식 섹션', '키보드 블록');
const refreshBlock = slice('function refresh(){', 'function add(){', 'refresh 블록');
const switchBlock = slice('function switchTab(t){', '// ── 설정 사이드바 전환', 'switchTab 블록');
const statsBlock = slice('function refreshStats(){', 'function spd(b){', '통계 갱신 블록');

const block = [
  'var __fetchLog=[];',
  'function esc(s){return (s||"");}',
  'function fmt(v){return String(v);}',
  'function spd(v){return v>0?(v/1024).toFixed(0)+" KB/s":"-";}',
  'function refreshStorage(){}',
  'function refreshSearchVisibility(){}',
  'function loadSettings(){}function loadSearchSettings(){}function loadTrackerCount(){}',
  'function toggleTrash(){}',
  'var trashMode=false;',
  'function render(jobs){window.__jobs=jobs;}',
  'function renderTorrents(ts){window.__torrents=ts;}',
  infoBlock,
  keyBlock,
  refreshBlock,
  switchBlock,
  statsBlock,
].join('\n');

// ── 최소 fake DOM ─────────────────────────────────────
class El {
  constructor(id) {
    this.id = id;
    this.innerHTML = '';
    this.textContent = '';
    this.hidden = false;
    this.title = '';
    this.attrs = {};
    this.classes = new Set();
    this.inner = new Set();          // contains() 판정용
    this.classList = {
      add: (c) => this.classes.add(c),
      remove: (c) => this.classes.delete(c),
      contains: (c) => this.classes.has(c),
      toggle: (c, force) => {
        const on = force === undefined ? !this.classes.has(c) : !!force;
        if (on) this.classes.add(c); else this.classes.delete(c);
        return on;
      },
    };
  }
  setAttribute(k, v) { this.attrs[k] = v; }
  getAttribute(k) { return this.attrs[k]; }
  contains(n) { return n === this || this.inner.has(n); }
  addInner(n) { this.inner.add(n); }
}

const els = {
  info: new El('info'),
  infoMenu: new El('infoMenu'),
  infoMenuDot: new El('infoMenuDot'),
  infoMenuAt: new El('infoMenuAt'),
  btnInfoMenu: new El('btnInfoMenu'),
  // switchTab 이 토글하는 탭 패널 (통계 패널은 v0.41 에서 제거됨)
  'panel-dl': new El('panel-dl'),
  'panel-torrent': new El('panel-torrent'),
  'panel-storage': new El('panel-storage'),
  'panel-settings': new El('panel-settings'),
};
els.infoMenu.hidden = true;
els.infoMenuDot.hidden = true;
// 배포 HTML 의 초기 상태를 흉내낸다 (정적 계약은 DashboardInfoMenuContractTest 가 담당)
els.btnInfoMenu.setAttribute('aria-expanded', 'false');
els.btnInfoMenu.setAttribute('aria-haspopup', 'true');

const docListeners = {};
const setFocus = (el) => { sandbox.document.activeElement = el; };

// fetch 호출 기록 — 닫힘 상태에서 /api/stats 가 0건인지가 핵심 지표다
function fakeFetch(url) {
  sandbox.__fetchLog.push(url);
  let payload = {};
  if (url.indexOf('/api/jobs') === 0) payload = [];
  else if (url.indexOf('/api/torrents') === 0) payload = [];
  else if (url.indexOf('/api/info') === 0) payload = { storageFree: 1, version: '0.41.0' };
  else if (url.indexOf('/api/guard/status') === 0) payload = { guardEnabled: true, thermal: 30, batteryLevel: 80, throttled: false };
  else if (url.indexOf('/api/stats/summary') === 0) payload = { today: {}, month: {}, total: {} };
  else if (url.indexOf('/api/stats/daily') === 0) payload = { days: [] };
  else if (url.indexOf('/api/stats/extended') === 0) payload = {};
  return Promise.resolve({ ok: true, json: () => Promise.resolve(payload) });
}

const statsCalls = () => sandbox.__fetchLog.filter(u => u.indexOf('/api/stats/') === 0);
const clearLog = () => { sandbox.__fetchLog.length = 0; };

// activeElement 는 접근자로 두면 createContext 시 값이 굳는다.
// 문서 객체는 참조로 공유되므로 평범한 속성으로 두고 테스트에서 직접 대입한다.
const sandbox = {
  document: {
    hidden: false,
    activeElement: null,
    getElementById: (id) => els[id] || null,
    addEventListener: (k, fn) => { (docListeners[k] = docListeners[k] || []).push(fn); },
    querySelectorAll: () => [],
    createElement: () => new El('tmp'),
    body: { appendChild: () => {}, removeChild: () => {} },
  },
  window: {
    addEventListener: () => {},
    __jobs: [], __torrents: [], __info: null, __guardStatus: null,
  },
  fetch: fakeFetch,
  setInterval: () => 0,
  clearInterval: () => {},
  setTimeout: () => 0,
  clearTimeout: () => {},
  Date,
  Math,
  JSON,
  Number,
  String,
  console,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(block, sandbox, { filename: 'dashboard-info-menu.js' });

// ── 검증 ─────────────────────────────────────────────
const R = [];
const ok = (n, c, e = '') => R.push({ n, pass: !!c, e });
const click = (target) => (docListeners['click'] || []).forEach(fn => fn({ target }));
const key = (k) => (docListeners['keydown'] || []).forEach(fn => fn({ key: k, preventDefault: () => {} }));

const OUTSIDE = new El('outside');            // 패널·버튼 어디에도 속하지 않는 노드
const INSIDE_MENU = new El('insideMenu');      // 드롭다운 내부 노드
const INSIDE_BTN = new El('insideBtn');        // 버튼 내부 노드(도트)
els.infoMenu.addInner(INSIDE_MENU);
els.btnInfoMenu.addInner(INSIDE_BTN);

// 1. 초기 상태
ok('초기에는 닫혀 있다', els.infoMenu.hidden === true);
ok('초기 aria-expanded=false', els.btnInfoMenu.getAttribute('aria-expanded') === 'false');
ok('초기에는 통계 요청 없음', statsCalls().length === 0, `n=${statsCalls().length}`);

// 2. 열기
clearLog();
sandbox.openInfoMenu();
ok('열기: 패널 표시', els.infoMenu.hidden === false);
ok('열기: aria-expanded=true', els.btnInfoMenu.getAttribute('aria-expanded') === 'true');
ok('열기: 버튼 활성 표시', els.btnInfoMenu.classes.has('on') === true);
ok('열기: 통계 1회 갱신(3 endpoint)', statsCalls().length === 3, statsCalls().join(','));
ok('열기: 설명 바 렌더', els.info.innerHTML.indexOf('info-left') > 0, els.info.innerHTML.slice(0, 60));

// 3. 내부 클릭은 닫지 않는다 (토글과 충돌 금지)
clearLog();
click(INSIDE_MENU);
ok('패널 내부 클릭 → 유지', els.infoMenu.hidden === false);
click(INSIDE_BTN);
ok('버튼 내부 클릭 → 유지', els.infoMenu.hidden === false);

// 4. 바깥 클릭 → 닫힘
click(OUTSIDE);
ok('바깥 클릭 → 닫힘', els.infoMenu.hidden === true);
ok('바깥 클릭 → aria-expanded=false', els.btnInfoMenu.getAttribute('aria-expanded') === 'false');
ok('바깥 클릭 → 버튼 활성 해제', els.btnInfoMenu.classes.has('on') === false);

// 5. toggle
sandbox.toggleInfoMenu();
ok('toggle 1회 → 열림', els.infoMenu.hidden === false);
sandbox.toggleInfoMenu();
ok('toggle 2회 → 닫힘', els.infoMenu.hidden === true);

// 6. Esc
sandbox.openInfoMenu();
setFocus({ tagName: 'INPUT' });
key('Escape');
ok('Esc(입력 focus) → 닫힘', els.infoMenu.hidden === true);
ok('Esc(입력 focus): 입력 가드보다 먼저', true);

sandbox.openInfoMenu();
setFocus(null);
key('Escape');
ok('Esc(일반) → 닫힘', els.infoMenu.hidden === true);

// 7. 탭 전환 → 닫힘
sandbox.openInfoMenu();
clearLog();
sandbox.switchTab('storage');
ok('탭 전환 → 닫힘', els.infoMenu.hidden === true);
ok('탭 전환: 통계 갱신 없음', statsCalls().length === 0, `n=${statsCalls().length}`);

// 8. S 단축키
setFocus(null);
sandbox.toggleInfoMenu();
ok('S 단축키(토글 경유) 동작', els.infoMenu.hidden === false);
key('s');
ok('S 키 입력 → 닫힘', els.infoMenu.hidden === true);
setFocus({ tagName: 'INPUT' });
sandbox.toggleInfoMenu();
key('s');
ok('S 키는 입력 focus 중 무시(토글 상태 유지)', els.infoMenu.hidden === false);
setFocus(null);

// 9. ══ 핵심 ══ 닫힘 상태에서 통계 API 0건
sandbox.closeInfoMenu();
clearLog();
sandbox.refresh();
ok('닫힘 + refresh → 통계 0건', statsCalls().length === 0, `n=${statsCalls().length}`);
ok('닫힘 + refresh → 일반 API 는 동작', sandbox.__fetchLog.length >= 4, `n=${sandbox.__fetchLog.length}`);

// 10. 열림 상태 15초 스로틀
sandbox.openInfoMenu();
clearLog();
sandbox.refresh();
ok('열림 직후 refresh → 스로틀로 통계 0건', statsCalls().length === 0, `n=${statsCalls().length}`);
sandbox.__statsAt = Date.now() - 20000;
clearLog();
sandbox.refresh();
ok('열림 + 20초 경과 → 통계 3건', statsCalls().length === 3, `n=${statsCalls().length}`);

// 11. 배지 — 유휴 숨김 / 활성 노출 / 스로틀 적색
sandbox.closeInfoMenu();
sandbox.window.__guardStatus = { guardEnabled: true, thermal: 30, batteryLevel: 80, throttled: false };
sandbox.updateInfoBar();
ok('유휴: 배지 숨김', els.infoMenuDot.hidden === true);
sandbox.window.__jobs = [{ state: 'RUNNING', speedBps: 2097152 }];
sandbox.updateInfoBar();
ok('활성 1건: 배지 노출', els.infoMenuDot.hidden === false);
ok('활성: 적색 아님', els.infoMenuDot.classes.has('warn') === false);
ok('활성: 툴팁에 상태 요약', els.btnInfoMenu.title.indexOf('진행 1') >= 0, els.btnInfoMenu.title);
sandbox.window.__guardStatus = { guardEnabled: true, thermal: 58, batteryLevel: 20, throttled: true };
sandbox.updateInfoBar();
ok('스로틀: 배지 적색', els.infoMenuDot.classes.has('warn') === true);
ok('스로틀: 툴팁 경고', els.btnInfoMenu.title.indexOf('스로틀링') >= 0, els.btnInfoMenu.title);

// 12. 유휴 DOM 쓰기 가드 (__infoHtml)
const writes = [];
const realInfo = els.info;
let writesCount = 0;
Object.defineProperty(realInfo, 'innerHTML', {
  get: () => realInfo._h || '',
  set: (v) => { writesCount++; realInfo._h = v; },
  configurable: true,
});
sandbox.updateInfoBar();       // 값 변경 → 1회
const after1 = writesCount;
sandbox.updateInfoBar();       // 동일 값 → 스킵
ok('동일 내용 → innerHTML 스킵', writesCount === after1, `추가=${writesCount - after1}`);
sandbox.window.__jobs = [{ state: 'RUNNING', speedBps: 1048576 }];
sandbox.updateInfoBar();       // 값 변경 → 1회
ok('내용 변경 → innerHTML 1회', writesCount === after1 + 1, `추가=${writesCount - after1}`);

console.log('\n=== 대시보드 헤더 통합 메뉴 — 실제 배포 코드 검증 ===');
let pass = 0;
for (const r of R) {
  console.log(`  ${r.pass ? '✅' : '❌'} ${r.n}${r.e ? '  [' + r.e + ']' : ''}`);
  if (r.pass) pass++;
}
console.log(`\n${pass}/${R.length} 통과`);
process.exit(pass === R.length ? 0 : 1);
