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
  h1{font-size:20px;margin:0 0 4px} .sub{color:#8FA3BF;font-size:13px;margin-bottom:14px}
  .info{display:flex;gap:14px;flex-wrap:wrap;color:#9FB4D4;font-size:12px;background:#101E3A;border:1px solid #22345A;
        border-radius:12px;padding:10px 14px;margin-bottom:16px}
  .row{display:flex;gap:8px}
  input[type=text],input[type=url]{flex:1;padding:11px 14px;border-radius:10px;border:1px solid #2A3B5C;background:#12203D;color:#fff;font-size:14px}
  button{padding:11px 18px;border-radius:10px;border:0;background:#2F80ED;color:#fff;font-weight:600;cursor:pointer}
  button.ghost{background:#22335433;border:1px solid #2A3B5C;color:#9FB4D4;padding:6px 12px;font-weight:500}
  button.sm{padding:5px 10px;font-size:12px;border-radius:8px}
  .card{background:#101E3A;border:1px solid #22345A;border-radius:14px;padding:14px;margin-top:14px}
  .name{font-weight:600;word-break:break-all}
  .meta{color:#8FA3BF;font-size:12px;margin-top:2px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  .bar{height:8px;background:#1B2B4D;border-radius:99px;margin-top:8px;overflow:hidden}
  .fill{height:100%;background:linear-gradient(90deg,#2F80ED,#8FD8FF);border-radius:99px;width:0%;transition:width .4s}
  .badge{font-size:11px;padding:2px 8px;border-radius:99px;background:#22335A}
  .RUNNING{background:#123A63;color:#8FD8FF}.DONE{background:#12402F;color:#69E29B}.FAILED{background:#40191C;color:#FF8A93}
  .QUEUED{background:#22335A}.PAUSED{background:#3A3312;color:#FFD59E}.CANCELED{background:#333}
  a.dl{color:#69E29B;text-decoration:none;font-weight:600}
  .err{color:#FF8A93;font-size:12px;margin-top:4px}
  .empty{color:#55688C;text-align:center;padding:26px 0}
  .speed{color:#8FD8FF;font-weight:600}
  .tabs{display:flex;gap:4px;margin-bottom:16px}
  .tab{flex:1;padding:10px;border-radius:10px;border:1px solid #2A3B5C;background:#101E3A;color:#8FA3BF;text-align:center;
       font-weight:600;font-size:14px;cursor:pointer;transition:all .15s}
  .tab.active{background:#2F80ED;border-color:#2F80ED;color:#fff}
  .panel{display:none}.panel.active{display:block}
  .file-icon{font-size:20px;margin-right:8px;flex-shrink:0}
  .file-meta{color:#8FA3BF;font-size:12px;margin-top:2px}
  input[type=file]{display:none}
  .breadcrumb{display:flex;align-items:center;gap:4px;margin-bottom:12px;color:#8FA3BF;font-size:13px;flex-wrap:wrap}
  .breadcrumb span{cursor:pointer;color:#8FD8FF}
  .breadcrumb span:hover{text-decoration:underline}
  .file-row{display:flex;align-items:center;gap:10px;padding:10px 14px;background:#101E3A;border:1px solid #22345A;border-radius:12px;margin-top:8px;
            cursor:pointer;transition:border-color .15s}
  .file-row:hover{border-color:#2F80ED}
  .file-row.selected{border-color:#2F80ED;background:#122A4D}
  .file-row .acts{margin-left:auto;display:flex;gap:6px;flex-shrink:0}
  .toolbar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
  .toolbar button{font-size:13px;padding:8px 14px}
</style></head><body>
<div class="wrap">
  <h1>📡 DroidRelay</h1>
  <div class="sub">이 페이지에서 요청하면 휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다</div>
  <div class="info" id="info">서버 정보 로딩 중…</div>

  <div class="tabs">
    <div class="tab active" onclick="switchTab('dl')">다운로드</div>
    <div class="tab" onclick="switchTab('torrent')">토렌트</div>
    <div class="tab" onclick="switchTab('storage')">보관함</div>
  </div>

  <!-- 다운로드 탭 -->
  <div class="panel active" id="panel-dl">
    <div class="row">
      <input id="url" type="url" placeholder="다운로드 URL 붙여넣기 (https://...)">
      <button onclick="add()">추가</button>
    </div>
    <div id="list"></div>
    <div class="empty" id="empty">아직 작업이 없습니다</div>
  </div>

  <!-- 토렌트 탭 -->
  <div class="panel" id="panel-torrent">
    <div class="row">
      <input id="magnet" placeholder="magnet:?xt=... 또는 .torrent 파일">
      <button onclick="addMagnet()">추가</button>
      <button class="ghost" onclick="document.getElementById('torrentFile').click()">파일</button>
    </div>
    <input type="file" id="torrentFile" accept=".torrent" onchange="uploadTorrent(this)">
    <div id="torrentList"></div>
    <div class="empty" id="torrentEmpty">토렌트 작업이 없습니다</div>
  </div>

  <!-- 보관함 탭 -->
  <div class="panel" id="panel-storage">
    <div class="breadcrumb" id="breadcrumb"></div>
    <div class="toolbar">
      <button class="ghost" onclick="createFolder()">📁 폴더 만들기</button>
      <button class="ghost" onclick="document.getElementById('uploadFile').click()">⬆ 파일 올리기</button>
      <input type="file" id="uploadFile" multiple onchange="uploadFiles(this)">
      <button class="ghost" onclick="pasteSelected()" id="pasteBtn" style="display:none">📋 붙여넣기</button>
      <button class="ghost" onclick="deleteSelected()" id="deleteBtn" style="display:none">🗑 삭제</button>
    </div>
    <div id="fileList"></div>
    <div class="empty" id="fileEmpty">비어 있습니다</div>
  </div>
</div>
<script>
var BASE='/sdcard/Download/DroidRelay';
var curPath='';
var selItem=null;
var cutItem=null;

function esc(s){return (s||'').replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}
function fmt(n){if(n==null||n<0)return '?';if(n<1048576)return (n/1024).toFixed(0)+' KB';if(n<1073741824)return (n/1048576).toFixed(1)+' MB';return (n/1073741824).toFixed(2)+' GB';}
function spd(bps){return bps>0?(bps/1024).toFixed(0)+' KB/s':'-';}
function label(st){return {QUEUED:'대기',RUNNING:'진행 중',PAUSED:'일시정지',DONE:'완료',FAILED:'실패',CANCELED:'취소됨',ADDING:'추가 중',METADATA:'메타데이터'}[st]||st;}
function fileIcon(name,isDir){
  if(isDir)return '📁';
  var ext=(name||'').split('.').pop().toLowerCase();
  var icons={mp4:'🎬',mkv:'🎬',avi:'🎬',mov:'🎬',mp3:'🎵',wav:'🎵',flac:'🎵',ogg:'🎵',
    jpg:'🖼️',jpeg:'🖼️',png:'🖼️',gif:'🖼️',webp:'🖼️',pdf:'📕',zip:'📦',rar:'📦',
    '7z':'📦',apk:'📱',exe:'💿',iso:'💿',txt:'📝',md:'📝',json:'📝',xml:'📝',torrent:'🔗'};
  return icons[ext]||'📄';
}
function switchTab(t){
  document.querySelectorAll('.tab').forEach(function(el,i){
    el.classList.toggle('active',(['dl','torrent','storage'])[i]===t);
  });
  document.getElementById('panel-dl').classList.toggle('active',t==='dl');
  document.getElementById('panel-torrent').classList.toggle('active',t==='torrent');
  document.getElementById('panel-storage').classList.toggle('active',t==='storage');
  if(t==='storage')refreshStorage();
}
function render(jobs){
  var el=document.getElementById('list');document.getElementById('empty').style.display=jobs.length?'none':'block';
  var totalSpeed=jobs.filter(function(j){return j.state==='RUNNING'}).reduce(function(a,j){return a+(j.speedBps||0)},0);
  var h='';
  jobs.forEach(function(j){
    var pct=j.totalBytes>0?Math.round(j.progress*100):(j.state==='DONE'?100:0);
    var size=j.downloadedBytes?(fmt(j.downloadedBytes)+(j.totalBytes>0?' / '+fmt(j.totalBytes):'')):'';
    var sp=j.state==='RUNNING'?'<span class="speed">'+spd(j.speedBps)+'</span>':'';
    var err=j.errorMessage?'<div class="err">'+esc(j.errorMessage)+'</div>':'';
    var act=j.state==='DONE'?'<a class="dl" href="/file/'+j.id+'">⬇ 받기</a>':'';
    var pause='';
    if(j.state==='RUNNING')pause='<button class="ghost" onclick="act(\''+j.id+'\',\'pause\')">일시정지</button>';
    if(j.state==='PAUSED'||j.state==='FAILED')pause='<button class="ghost" onclick="act(\''+j.id+'\',\'resume\')">재개</button>';
    var cancel=(j.state==='RUNNING'||j.state==='QUEUED')?'<button class="ghost" onclick="delJob(\''+j.id+'\')">중단</button>':'<button class="ghost" onclick="delJob(\''+j.id+'\')">삭제</button>';
    h+='<div class="card"><div style="display:flex;justify-content:space-between;gap:10px"><div style="min-width:0">'
      +'<div class="name">'+esc(j.filename)+'</div>'
      +'<div class="meta"><span class="badge '+j.state+'">'+label(j.state)+'</span><span>'+size+'</span><span>'+pct+'%</span>'+sp+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+err
      +'</div><div style="display:flex;flex-direction:column;gap:6px;align-items:flex-end">'+act+pause+cancel+'</div></div></div>';
  });
  el.innerHTML=h;
  document.getElementById('info').innerHTML='⚡ 총 속도 <b class="speed">'+spd(totalSpeed)+'</b>'
    +' &nbsp;·&nbsp; 진행 '+jobs.filter(function(j){return j.state==='RUNNING'}).length+'건'
    +' &nbsp;·&nbsp; 저장공간 여유 <b>'+(window.__info&&window.__info.storageFree!=null?fmt(window.__info.storageFree):'?')+'</b>'
    +(window.__info&&window.__info.storageTotal?(' / '+fmt(window.__info.storageTotal)):'');
}
function renderTorrents(ts){
  var el=document.getElementById('torrentList');document.getElementById('torrentEmpty').style.display=ts.length?'none':'block';
  var h='';
  ts.forEach(function(t){
    var pct=t.progress!=null?Math.round(t.progress*100):0;
    var size=t.totalBytes>0?(fmt(t.downloadedBytes)+' / '+fmt(t.totalBytes)):(t.downloadedBytes>0?fmt(t.downloadedBytes):'');
    var sp=t.state==='DOWNLOADING'?'<span class="speed">'+spd(t.downloadSpeed||0)+'</span>':'';
    var err=t.errorMessage?'<div class="err">'+esc(t.errorMessage)+'</div>':'';
    var st=t.state||'UNKNOWN';
    var badgeClass={DOWNLOADING:'RUNNING',SEEDING:'DONE',PAUSED:'PAUSED',ERROR:'FAILED',METADATA:'QUEUED',ADDING:'QUEUED'}[st]||'QUEUED';
    var pause='';
    if(st==='DOWNLOADING'||st==='SEEDING')pause='<button class="ghost" onclick="torrentAct(\''+t.id+'\',\'pause\')">일시정지</button>';
    if(st==='PAUSED')pause='<button class="ghost" onclick="torrentAct(\''+t.id+'\',\'resume\')">재개</button>';
    var del='<button class="ghost" onclick="torrentDel(\''+t.id+'\')">삭제</button>';
    h+='<div class="card"><div style="display:flex;justify-content:space-between;gap:10px"><div style="min-width:0">'
      +'<div class="name">'+esc(t.name||t.hash||'파일 불명')+'</div>'
      +'<div class="meta"><span class="badge '+badgeClass+'">'+label(st)+'</span><span>'+size+'</span><span>'+pct+'%</span>'+sp+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+err
      +'</div><div style="display:flex;flex-direction:column;gap:6px;align-items:flex-end">'+pause+del+'</div></div></div>';
  });
  el.innerHTML=h;
}
function renderStorage(items){
  var el=document.getElementById('fileList');document.getElementById('fileEmpty').style.display=items.length?'none':'block';
  var h='';
  items.forEach(function(f){
    var isDir=f.type==='dir';
    var key=curPath?curPath+'/'+f.name:f.name;
    var click=isDir?'openDir('+JSON.stringify(key)+')':'selectItem(this,'+JSON.stringify(f)+')';
    var cls=(selItem&&selItem.name===f.name)?'file-row selected':'file-row';
    h+='<div class="'+cls+'" onclick="'+click+'">'
      +'<span class="file-icon">'+fileIcon(f.name,isDir)+'</span>'
      +'<div style="min-width:0;flex:1"><div class="name">'+esc(f.name)+'</div>'
      +'<div class="file-meta">'+(isDir?(f.count+'개'):fmt(f.size))+'</div></div>'
      +'<div class="acts">';
    if(!isDir){
      h+='<a class="dl sm" href="/dl-file/'+encodeURIComponent(key)+'" onclick="event.stopPropagation()">⬇</a>';
    }
    h+='<button class="ghost sm" onclick="event.stopPropagation();renameItem('+JSON.stringify(f)+')">✏️</button>';
    if(!isDir){
      h+='<button class="ghost sm" onclick="event.stopPropagation();cutItemFn('+JSON.stringify(f)+')">✂️</button>';
    }
    h+='<button class="ghost sm" onclick="event.stopPropagation();delItem('+JSON.stringify(f)+')">🗑</button>';
    h+='</div></div>';
  });
  el.innerHTML=h;
  updateBreadcrumb();
  updateToolbar();
}
function updateBreadcrumb(){
  var parts=curPath?curPath.split('/'):[];
  var h='<span onclick="openDir(\'\')">📱 보관함</span>';
  var p='';
  parts.forEach(function(part){
    if(!part)return;
    p+=(p?'/':'')+part;
    (function(path){h+=' / <span onclick="openDir('+JSON.stringify(path)+')">'+esc(part)+'</span>'})(p);
  });
  document.getElementById('breadcrumb').innerHTML=h;
}
function updateToolbar(){
  document.getElementById('pasteBtn').style.display=cutItem?'inline-block':'none';
  document.getElementById('deleteBtn').style.display=selItem?'inline-block':'none';
}
function selectItem(el,f){
  document.querySelectorAll('.file-row').forEach(function(r){r.classList.remove('selected')});
  el.classList.add('selected');selItem=f;updateToolbar();
}
function openDir(path){curPath=path;selItem=null;cutItem=null;refreshStorage();}
function refreshStorage(){
  var p=curPath?('?path='+encodeURIComponent(curPath)):'';
  fetch('/api/storage'+p).then(function(r){return r.json()}).then(renderStorage).catch(function(){});
}
function createFolder(){
  var name=prompt('폴더 이름:');if(!name)return;
  fetch('/api/storage/mkdir',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({path:curPath,name:name})})
  .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);refreshStorage()}).catch(function(e){alert(e)});
}
function renameItem(f){
  var name=prompt('새 이름:',f.name);if(!name||name===f.name)return;
  var full=curPath?curPath+'/'+f.name:f.name;
  fetch('/api/storage/rename',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({from:full,to:name})})
  .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);refreshStorage()}).catch(function(e){alert(e)});
}
function delItem(f){
  if(!confirm('삭제: '+f.name+'?'))return;
  var full=curPath?curPath+'/'+f.name:f.name;
  fetch('/api/storage/delete',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({path:full})})
  .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);selItem=null;refreshStorage()}).catch(function(e){alert(e)});
}
function cutItemFn(f){cutItem=f;updateToolbar();}
function pasteSelected(){
  if(!cutItem)return;
  var from=curPath?cutItem.name:cutItem.name;
  fetch('/api/storage/move',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({from:from,to:curPath})})
  .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);cutItem=null;refreshStorage()}).catch(function(e){alert(e)});
}
function deleteSelected(){if(selItem)delItem(selItem);}
function uploadFiles(input){
  var files=input.files;if(!files.length)return;
  Array.from(files).forEach(function(file){
    var reader=new FileReader();
    reader.onload=function(){
      var b64=reader.result.split(',')[1];
      fetch('/api/storage/upload',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({path:curPath,name:file.name,data:b64})})
      .then(function(r){return r.json()}).then(function(){refreshStorage()}).catch(function(e){alert(e)});
    };
    reader.readAsDataURL(file);
  });
  input.value='';
}
function refresh(){
  fetch('/api/info').then(function(r){return r.json()}).then(function(i){window.__info=i}).catch(function(){});
  fetch('/api/jobs').then(function(r){return r.json()}).then(render).catch(function(){});
  fetch('/api/torrents').then(function(r){return r.json()}).then(renderTorrents).catch(function(){});
}
function add(){var u=document.getElementById('url').value.trim();if(!u)return;
  fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:u})})
  .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
  .then(function(){document.getElementById('url').value='';refresh()})
  .catch(function(e){alert('추가 실패: '+e)});}
function addMagnet(){var u=document.getElementById('magnet').value.trim();if(!u)return;
  fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:u})})
  .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
  .then(function(){document.getElementById('magnet').value='';refresh()})
  .catch(function(e){alert('추가 실패: '+e)});}
function uploadTorrent(input){
  var file=input.files[0];if(!file)return;
  var fd=new FormData();fd.append('file',file);
  fetch('/api/torrents/add',{method:'POST',body:fd})
  .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
  .then(function(){input.value='';refresh()})
  .catch(function(e){alert('추가 실패: '+e)});}
function act(id,a){fetch('/api/jobs/'+id+'/'+a,{method:'POST'}).then(refresh);}
function delJob(id){fetch('/api/jobs/'+id,{method:'DELETE'}).then(refresh);}
function torrentAct(id,a){fetch('/api/torrents/'+id+'/'+a,{method:'POST'}).then(refresh);}
function torrentDel(id){fetch('/api/torrents/'+id,{method:'DELETE'}).then(refresh);}
setInterval(refresh,1000);refresh();
</script></body></html>"""
}
