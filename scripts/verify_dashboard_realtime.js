// 실제 배포 중인 대시보드 "실시간 갱신" 블록을 그대로 실행해 검증한다.
// 목표: 탭이 숨겨지면 SSE·타이머가 정리되고, 복귀하면 재연결되며,
//       재연결이 지수 백오프를 쓴다.

const fs = require('fs');
const vm = require('vm');

const block = fs.readFileSync(process.argv[2], 'utf8');

let esInstances = [];
let intervals = new Map();
let nextIntervalId = 0;
let timeouts = [];
let refreshCount = 0;

class FakeEventSource {
  constructor(url) {
    this.url = url; this.closed = false;
    this.onopen = null; this.onmessage = null; this.onerror = null;
    esInstances.push(this);
  }
  close() { this.closed = true; }
  emitOpen() { this.onopen && this.onopen(); }
  emitMessage() { this.onmessage && this.onmessage(); }
  emitError() { this.onerror && this.onerror(); }
}

const docListeners = {};
const winListeners = {};
const hiddenState = { v: false };

const sandbox = {
  document: {
    get hidden() { return hiddenState.v; },
    addEventListener: (k, fn) => { (docListeners[k] = docListeners[k] || []).push(fn); },
  },
  // 코드가 `window.EventSource` 로 지원 여부를 확인하고 `new EventSource()` 로 생성하므로
  // 양쪽 모두 노출해야 한다.
  EventSource: FakeEventSource,
  window: {
    EventSource: FakeEventSource,
    addEventListener: (k, fn) => { (winListeners[k] = winListeners[k] || []).push(fn); },
  },
  setInterval: (fn, ms) => {
    // 단조 증가 ID — map.size 로 만들면 clear 후 같은 ID 가 재사용되어
    // "해제됐는지"를 구분할 수 없는 스텐 버그가 생긴다.
    nextIntervalId += 1;
    intervals.set(nextIntervalId, ms);
    return nextIntervalId;
  },
  clearInterval: (id) => { intervals.delete(id); },
  setTimeout: (fn, ms) => { const t = { fn, ms, id: timeouts.length }; timeouts.push(t); return t.id; },
  clearTimeout: (id) => { timeouts = timeouts.filter(t => t.id !== id); },
  refresh: () => { refreshCount++; },
  console,
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(block, sandbox, { timeout: 5000 });

// ── 검증 ─────────────────────────────────────────────
const R = [];
const ok = (n, c, e = '') => R.push({ n, pass: !!c, e });
const pollMs = () => [...intervals.values()];
const fire = (arr) => arr.forEach(fn => fn());

// 1. 초기 상태: EventSource 없음 + EventSource 지원 확인
ok('초기 EventSource 1개 생성', esInstances.length === 1, `n=${esInstances.length}`);
ok('초기 백업 폴링 없음 (SSE 대기)', intervals.size === 0,
   `타이머=${intervals.size}`);

// 2. 연결 성공 → 폴링 10초 완화
esInstances[0].emitOpen();
ok('SSE open → 폴링 10초', pollMs().includes(10000), pollMs().join(','));

// 3. tick → refresh
const b0 = refreshCount;
esInstances[0].emitMessage();
ok('tick 수신 → refresh 1회', refreshCount - b0 === 1, `+${refreshCount - b0}`);

// 4. ══ 핵심 ══ hidden → 전부 정리
esInstances[0].emitOpen();               // 확실히 연결된 상태로
const esN = esInstances.length;
const ivN = intervals.size;
hiddenState.v = true;
fire(docListeners['visibilitychange']);
ok('hidden: EventSource close()', esInstances[esN - 1].closed === true);
ok('hidden: 폴링 타이머 전부 해제', intervals.size === 0, `before=${ivN} after=${intervals.size}`);
ok('hidden: 재연결 시도 안 함', esInstances.length === esN, `n=${esInstances.length}`);
ok('hidden: 재조회 안 함', refreshCount === b0 + 1, `refresh=${refreshCount}`);

// 5. hidden 상태에서 connect() 직접 호출해도 무시되어야 함
const esN2 = esInstances.length;
sandbox.__test_connect && sandbox.__test_connect();
ok('hidden: connect() 직접 호출도 무시', esInstances.length === esN2, `n=${esInstances.length}`);

// 6. visible → 재연결 + 재조회
hiddenState.v = false;
const b1 = refreshCount;
const esN3 = esInstances.length;
fire(docListeners['visibilitychange']);
ok('visible: refresh 재호출', refreshCount > b1, `+${refreshCount - b1}`);
ok('visible: EventSource 재생성', esInstances.length > esN3,
   `before=${esN3} after=${esInstances.length}`);
// connect() 는 연결이 열려야 폴링을 설치한다 (onopen 전까지는 SSE 가 곧 tick 을 준다)
ok('visible: onopen 전에는 폴링 없음 (SSE 대기)', intervals.size === 0, `타이머=${intervals.size}`);
esInstances[esInstances.length - 1].emitOpen();
ok('visible: onopen 후 폴링 10초 설치', pollMs().includes(10000), pollMs().join(','));

// 7. pagehide → 정리
esInstances[esInstances.length - 1].emitOpen();
const esN4 = esInstances.length;
fire(winListeners['pagehide']);
ok('pagehide: EventSource close()', esInstances[esN4 - 1].closed === true);
ok('pagehide: 타이머 해제', intervals.size === 0, `남은=${intervals.size}`);

// 8. onerror → 지수 백오프 (고정 5초가 아님), 1회차엔 ~1초
esInstances[esInstances.length - 1].emitOpen();
timeouts = [];
esInstances[esInstances.length - 1].emitError();
const w1 = timeouts.length ? timeouts[timeouts.length - 1].ms : null;
ok('onerror: 재연결 예약됨', w1 !== null, `wait=${w1}ms`);
ok('onerror: 고정 5000ms 아님 (1회차 백오프)', w1 !== 5000 && w1 >= 1000 && w1 <= 1600, `wait=${w1}ms`);
ok('onerror: 폴링 1초로 강화', pollMs().includes(1000), pollMs().join(','));

// 연속 실패 시 백오프가 커지는지
const waits = [];
for (let i = 0; i < 4; i++) {
  timeouts = [];
  const cur = esInstances[esInstances.length - 1];
  cur.emitError();
  if (timeouts.length) waits.push(timeouts[timeouts.length - 1].ms);
}
const growing = waits.length >= 3 && waits[1] > waits[0] && waits[2] > waits[1];
ok('onerror 반복: 지수 증가', growing, waits.join(' → '));
const capped = waits.every(w => w <= 61500);
ok('onerror 반복: 상한 60초 respected', capped, `max=${Math.max(...waits)}ms`);

console.log('\n=== 대시보드 실시간 갱신 — 실제 배포 코드 검증 ===');
let pass = 0;
for (const r of R) {
  console.log(`  ${r.pass ? '✅' : '❌'} ${r.n}${r.e ? '  [' + r.e + ']' : ''}`);
  if (r.pass) pass++;
}
console.log(`\n${pass}/${R.length} 통과`);
process.exit(pass === R.length ? 0 : 1);
