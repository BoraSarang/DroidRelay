package com.borasarang.droidrelay.relay

object WebAssets {

    val dashboardHtml: String
        get() = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DroidRelay</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;background:#0A1428;color:#E6EEF8;font:15px/1.5 -apple-system,'Malgun Gothic',sans-serif}
  .wrap{max-width:760px;margin:0 auto;padding:20px}
  h1{font-size:20px;margin:0 0 4px} .sub{color:#8FA3BF;font-size:13px;margin-bottom:18px}
  .row{display:flex;gap:8px}
  input{flex:1;padding:11px 14px;border-radius:10px;border:1px solid #2A3B5C;background:#12203D;color:#fff;font-size:14px}
  button{padding:11px 18px;border-radius:10px;border:0;background:#2F80ED;color:#fff;font-weight:600;cursor:pointer}
  button.ghost{background:#22335433;border:1px solid #2A3B5C;color:#9FB4D4;padding:6px 12px;font-weight:500}
  .card{background:#101E3A;border:1px solid #22345A;border-radius:14px;padding:14px;margin-top:14px}
  .name{font-weight:600;word-break:break-all}
  .meta{color:#8FA3BF;font-size:12px;margin-top:2px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  .bar{height:8px;background:#1B2B4D;border-radius:99px;margin-top:8px;overflow:hidden}
  .fill{height:100%;background:linear-gradient(90deg,#2F80ED,#8FD8FF);border-radius:99px;width:0%}
  .badge{font-size:11px;padding:2px 8px;border-radius:99px;background:#22335A}
  .RUNNING{background:#123A63;color:#8FD8FF}.DONE{background:#12402F;color:#69E29B}.FAILED{background:#40191C;color:#FF8A93}.QUEUED{background:#22335A}.CANCELED{background:#333}
  a.dl{color:#69E29B;text-decoration:none;font-weight:600}
  .err{color:#FF8A93;font-size:12px;margin-top:4px}
  .empty{color:#55688C;text-align:center;padding:26px 0}
</style></head><body>
<div class="wrap">
  <h1>📡 DroidRelay</h1>
  <div class="sub">이 페이지에서 요청하면 휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다</div>
  <div class="row">
    <input id="url" placeholder="다운로드 URL 붙여넣기 (https://...)">
    <button onclick="add()">추가</button>
  </div>
  <div id="list"></div>
  <div class="empty" id="empty">아직 작업이 없습니다</div>
</div>
<script>
var prev={};
function esc(s){return (s||'').replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}
function fmt(n){if(n<1048576)return (n/1024).toFixed(0)+' KB';if(n<1073741824)return (n/1048576).toFixed(1)+' MB';return (n/1073741824).toFixed(2)+' GB';}
function label(st){return {QUEUED:'대기',RUNNING:'진행 중',DONE:'완료',FAILED:'실패',CANCELED:'취소됨'}[st]||st;}
function render(jobs){
  var el=document.getElementById('list');document.getElementById('empty').style.display=jobs.length?'none':'block';
  var h='';
  jobs.forEach(function(j){
    var pct=j.totalBytes>0?Math.round(j.progress*100):(j.state==='DONE'?100:0);
    var size=j.downloadedBytes?(fmt(j.downloadedBytes)+(j.totalBytes>0?' / '+fmt(j.totalBytes):'')):'';
    var err=j.errorMessage?'<div class="err">'+esc(j.errorMessage)+'</div>':'';
    var act=j.state==='DONE'?'<a class="dl" href="/file/'+j.id+'">⬇ 받기</a>':'';
    var cancel=(j.state==='RUNNING'||j.state==='QUEUED')?'<button class="ghost" onclick="cancelJob(\''+j.id+'\')">중단</button>':'<button class="ghost" onclick="removeJob(\''+j.id+'\')">삭제</button>';
    h+='<div class="card"><div style="display:flex;justify-content:space-between;gap:10px"><div style="min-width:0">'
      +'<div class="name">'+esc(j.filename)+'</div>'
      +'<div class="meta"><span class="badge '+j.state+'">'+label(j.state)+'</span><span>'+size+'</span><span>'+pct+'%</span></div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+err
      +'</div><div style="display:flex;flex-direction:column;gap:6px;align-items:flex-end">'+act+cancel+'</div></div></div>';
  });
  el.innerHTML=h;
}
function refresh(){fetch('/api/jobs').then(function(r){return r.json()}).then(render).catch(function(){});}
function add(){var u=document.getElementById('url').value.trim();if(!u)return;
  fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:u})})
  .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
  .then(function(){document.getElementById('url').value='';refresh()})
  .catch(function(e){alert('추가 실패: '+e)});}
function cancelJob(id){fetch('/api/jobs/'+id,{method:'DELETE'}).then(refresh);}
function removeJob(id){fetch('/api/jobs/'+id,{method:'DELETE'}).then(refresh);}
setInterval(refresh,1000);refresh();
</script></body></html>"""
}
