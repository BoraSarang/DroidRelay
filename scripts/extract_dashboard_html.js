// 배포되는 대시보드 HTML 을 Kotlin 소스에서 그대로 뽑아낸다.
// 사용: node scripts/extract_dashboard_html.js <out.html>
// WebDashboardHtml.content 와 동일하게 Head + "\n" + Body + "\n" + Script 를 잇는다.
// (문자열 보간은 StorageGuard.dlRoot 경로로 치환 — 로컬 미리보기용)

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC_DIR = path.join(
  ROOT,
  'apps/android/app/src/main/java/com/borasarang/droidrelay/relay',
);
const PARTS = ['WebDashboardHead.kt', 'WebDashboardBody.kt', 'WebDashboardScript.kt'];

const out = process.argv[2];
if (!out) {
  console.error('usage: node scripts/extract_dashboard_html.js <out.html>');
  process.exit(1);
}

function rawContent(file) {
  const src = fs.readFileSync(path.join(SRC_DIR, file), 'utf8');
  const marker = 'val content: String by lazy {';
  const start = src.indexOf(marker);
  if (start < 0) throw new Error(`${file}: content 블록 시작점을 찾을 수 없음`);

  const open = src.indexOf('"""', start) + 3;
  const close = src.lastIndexOf('"""');
  if (open < 3 || close < open) throw new Error(`${file}: raw string 닫힘을 찾을 수 없음`);
  return src.substring(open, close);
}

let html = PARTS.map(rawContent).join('\n');
// Kotlin 문자열 보간 `${com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path}`
html = html.replace(/\$\{[^}]*\}/g, '/storage/emulated/0/Download/DroidRelay');

fs.writeFileSync(out, html, 'utf8');
console.log(`✅ ${path.relative(ROOT, out)} (${html.length} bytes)`);
