package com.borasarang.droidrelay.relay

internal object WebDebugHtml {
    val content: String by lazy {
        """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/favicon-32.png">
<link rel="icon" type="image/png" sizes="16x16" href="/favicon-16.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" content="#0A1428">
<title>DroidRelay — Debug</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;background:var(--bg);color:var(--text);font:13px/1.5 'SF Mono','Menlo','Consolas',monospace;height:100vh;display:flex;flex-direction:column}
  .toolbar{background:var(--surface);border-bottom:1px solid var(--line);padding:8px 12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;flex-shrink:0}
  .toolbar h3{margin:0;font-size:14px;color:var(--accent2);white-space:nowrap}
  .toolbar .sep{width:1px;height:20px;background:var(--line)}
  .tabs{display:flex;gap:2px}
  .tab{padding:4px 10px;border-radius:6px;cursor:pointer;font-size:12px;color:var(--muted);background:transparent;border:none;transition:all .15s}
  .tab:hover{background:var(--surface2);color:var(--text)}
  .tab.active{background:var(--accent);color:var(--on-accent)}
  .filter{display:flex;align-items:center;gap:6px;margin-left:auto}
  .filter select,.filter input{background:var(--surface2);border:1px solid var(--line2);color:var(--text);border-radius:6px;padding:4px 8px;font-size:12px}
  .filter input{width:120px}
  .filter select{width:80px}
  .btn{padding:4px 10px;border-radius:6px;border:1px solid var(--line2);background:var(--line);color:var(--muted);cursor:pointer;font-size:12px;transition:all .15s}
  .btn:hover{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}
  .btn.red{border-color:var(--danger);color:var(--err)}
  .btn.red:hover{background:var(--danger);color:var(--err)}
  .stats{font-size:11px;color:var(--dim);white-space:nowrap}
  .log-area{flex:1;overflow-y:auto;padding:8px 12px;font-size:12px;line-height:1.6;scroll-behavior:smooth}
  .log-line{white-space:pre-wrap;word-break:break-all}
  .log-line.D{color:var(--dim)}
  .log-line.I{color:var(--accent2)}
  .log-line.W{color:var(--warn)}
  .log-line.E{color:var(--err)}
  .log-line.API{color:var(--ok)}
  .pause-overlay{position:fixed;inset:0;background:rgba(0,0,0,.4);display:none;align-items:center;justify-content:center;z-index:999}
  .pause-overlay.show{display:flex}
  .pause-text{background:var(--surface);border:1px solid var(--line2);border-radius:12px;padding:16px 24px;font-size:16px;color:var(--warn)}
</style></head><body>
<div class="toolbar">
  <h3>DroidRelay Debug</h3>
  <div class="sep"></div>
  <div class="tabs">
    <button class="tab active" onclick="switchLogTab('log')">로그</button>
    <button class="tab" onclick="switchLogTab('api')">API 호출</button>
  </div>
  <div class="filter">
    <select id="logLevel" onchange="applyFilter()">
      <option value="">전체</option>
      <option value="E">E</option>
      <option value="W">W</option>
      <option value="I">I</option>
      <option value="D">D</option>
      <option value="API">API</option>
    </select>
    <input id="logFilter" type="text" placeholder="검색..." oninput="applyFilter()">
    <button class="btn" id="pauseBtn" onclick="togglePause()">일시정지</button>
    <button class="btn red" onclick="clearLogs()">클리어</button>
    <button class="btn" onclick="copyLogs()">복사</button>
    <button class="btn" onclick="exportLogs()">내보내기</button>
  </div>
  <div class="stats" id="stats">-</div>
</div>
<div class="log-area" id="logArea"></div>
<div class="pause-overlay" id="pauseOverlay"><div class="pause-text">일시정지됨</div></div>
<script>
var curTab='log',paused=false,allLines=[],apiLines=[],timer=null;
function switchLogTab(t){
  curTab=t;
  document.querySelectorAll('.tab').forEach(function(el){el.classList.toggle('active',el.textContent.indexOf(t==='log'?'로그':'API')>=0);});
  applyFilter();
}
function togglePause(){
  paused=!paused;
  document.getElementById('pauseBtn').textContent=paused?'재개':'일시정지';
  document.getElementById('pauseOverlay').classList.toggle('show',paused);
}
function applyFilter(){renderLines();}
function renderLines(){
  var lines=curTab==='log'?allLines:apiLines;
  var lv=document.getElementById('logLevel').value;
  var q=document.getElementById('logFilter').value.toLowerCase();
  var el=document.getElementById('logArea');
  var html='';
  var count=0;
  for(var i=0;i<lines.length;i++){
    var ln=lines[i];
    if(lv && ln.indexOf('['+lv+']')===-1 && ln.indexOf(']')!==ln.indexOf('['+lv)) continue;
    if(q && ln.toLowerCase().indexOf(q)===-1) continue;
    count++;
    var cls='I';
    if(ln.indexOf('[E]')>=0)cls='E';
    else if(ln.indexOf('[W]')>=0)cls='W';
    else if(ln.indexOf('[D]')>=0)cls='D';
    else if(ln.indexOf('[API]')>=0)cls='API';
    html+='<div class="log-line '+cls+'">'+escLn(ln)+'</div>';
  }
  el.innerHTML=html;
  document.getElementById('stats').textContent=count+'건';
  if(!paused) el.scrollTop=el.scrollHeight;
}
function escLn(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;');}
function fetchLogs(){
  var url=curTab==='log'?'/api/debug/logs?limit=300':'/api/debug/api-calls?limit=200';
  fetch(url).then(function(r){return r.json();}).then(function(d){
    var src=curTab==='log'?(d.lines||[]):(d.calls||[]);
    if(curTab==='log') allLines=src; else apiLines=src;
    document.getElementById('stats').textContent=(curTab==='log'?d.count:apiLines.length)+'건 / 총 '+(curTab==='log'?d.total:d.count);
    renderLines();
  }).catch(function(){});
}
function clearLogs(){
  fetch('/api/debug/clear',{method:'POST'}).then(function(){
    allLines=[];apiLines=[];renderLines();
  });
}
function copyLogs(){
  var lines=curTab==='log'?allLines:apiLines;
  var lv=document.getElementById('logLevel').value;
  var q=document.getElementById('logFilter').value.toLowerCase();
  var out=[];
  for(var i=0;i<lines.length;i++){
    var ln=lines[i];
    if(lv && ln.indexOf('['+lv+']')===-1) continue;
    if(q && ln.toLowerCase().indexOf(q)===-1) continue;
    out.push(ln);
  }
  if(navigator.clipboard) navigator.clipboard.writeText(out.join('\n'));
}
function exportLogs(){
  var lines=curTab==='log'?allLines:apiLines;
  var blob=new Blob([lines.join('\n')],{type:'text/plain'});
  var a=document.createElement('a');a.href=URL.createObjectURL(blob);
  a.download='droidrelay-debug-'+new Date().toISOString().slice(0,19).replace(/:/g,'-')+'.txt';
  a.click();
}
function poll(){fetchLogs();timer=setTimeout(poll,1000);}
poll();
</script></body></html>"""
    }
}
