// 배포되는 대시보드 HTML 을 Kotlin 소스에서 그대로 뽑아낸다.
// 사용: node scripts/extract_dashboard_html.js <out.html>
// (WebDashboardHtml.content 는 리터럴 + BASE 보간 1곳이라 그대로 치환하면 된다)

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(
  ROOT,
  'apps/android/app/src/main/java/com/borasarang/droidrelay/relay/WebDashboardHtml.kt',
);

const out = process.argv[2];
if (!out) {
  console.error('usage: node scripts/extract_dashboard_html.js <out.html>');
  process.exit(1);
}

const src = fs.readFileSync(SRC, 'utf8');
const marker = 'val content: String by lazy {';
const start = src.indexOf(marker);
if (start < 0) throw new Error('content 블록 시작점을 찾을 수 없음');

// Kotlin raw string 의 """ ... """ 를 찾는다
const open = src.indexOf('"""', start) + 3;
const close = src.indexOf('"""', open);
if (open < 3 || close < 0) throw new Error('raw string 닫힘을 찾을 수 없음');

let html = src.substring(open, close);
// Kotlin 문자열 보간 `${com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path}`
html = html.replace(/\$\{[^}]*\}/g, '/storage/emulated/0/Download/DroidRelay');

fs.writeFileSync(out, html, 'utf8');
console.log(`✅ ${path.relative(ROOT, out)} (${html.length} bytes)`);
