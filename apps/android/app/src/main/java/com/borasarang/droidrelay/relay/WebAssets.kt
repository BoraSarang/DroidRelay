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
  .row-torrent{display:flex;gap:8px;align-items:stretch}
  input[type=text],input[type=url]{flex:1;padding:11px 14px;border-radius:10px;border:1px solid #2A3B5C;background:#12203D;color:#fff;font-size:14px}
  button{padding:11px 18px;border-radius:10px;border:0;background:#2F80ED;color:#fff;font-weight:600;cursor:pointer;white-space:nowrap}
  button.ghost{background:#22335433;border:1px solid #2A3B5C;color:#9FB4D4;padding:6px 12px;font-weight:500}
  button.sm{padding:5px 10px;font-size:12px;border-radius:8px}
  .btn-dl{background:#12402F;border:1px solid #1A5C3A;color:#69E29B;padding:5px 10px;font-size:12px;border-radius:8px;font-weight:600;cursor:pointer;display:inline-flex;align-items:center;text-decoration:none}
  .card{background:#101E3A;border:1px solid #22345A;border-radius:14px;margin-top:14px;display:flex;align-items:stretch}
  .name{font-weight:600;word-break:break-all}
  .meta{color:#8FA3BF;font-size:12px;margin-top:2px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  .bar{height:10px;background:#1B2B4D;border-radius:99px;margin-top:8px;overflow:hidden}
  .fill{height:100%;background:linear-gradient(90deg,#2F80ED,#8FD8FF);border-radius:99px;width:0%;transition:width .4s}
  .badge{font-size:11px;padding:2px 8px;border-radius:99px;background:#22335A}
  .RUNNING{background:#123A63;color:#8FD8FF}.DONE{background:#12402F;color:#69E29B}.FAILED{background:#40191C;color:#FF8A93}
  .QUEUED{background:#22335A}.PAUSED{background:#3A3312;color:#FFD59E}.CANCELED{background:#333}
  .err{color:#FF8A93;font-size:12px;margin-top:4px}
  .empty{color:#55688C;text-align:center;padding:26px 0}
  .speed{color:#8FD8FF;font-weight:600}
  .eta{color:#8FA3BF;font-size:12px}
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
  #breadcrumb span.drop-target{outline:2px dashed #2F80ED;outline-offset:2px;border-radius:4px;background:#122A4D}
  .file-row{display:flex;align-items:center;gap:10px;padding:10px 14px;background:#101E3A;border:1px solid #22345A;border-radius:12px;margin-top:8px;
            cursor:pointer;transition:border-color .15s}
  .file-row:hover{border-color:#2F80ED}
  .file-row.selected{border-color:#2F80ED;background:#122A4D}
  .file-row[draggable=true]{cursor:grab}
  .file-row.dragging{opacity:.45}
  .file-row.drop-target{outline:2px dashed #2F80ED;outline-offset:-2px;background:#122A4D}
  .card,.file-row{user-select:none;-webkit-user-select:none}
  .card.dragging{opacity:.4}
  .card.drop-before{box-shadow:0 -3px 0 0 #2F80ED}
  .card.drop-after{box-shadow:0 3px 0 0 #2F80ED}
  .file-row .acts{margin-left:auto;display:flex;gap:6px;flex-shrink:0}
  .toolbar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
  .toolbar button{font-size:13px;padding:8px 14px}
  .drop-zone{border:2px dashed #2A3B5C;border-radius:12px;padding:24px;text-align:center;color:#55688C;margin-top:12px;transition:all .2s}
  .drop-zone.dragover{border-color:#2F80ED;background:#12203D;color:#8FD8FF}
  .modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:1000;justify-content:center;align-items:flex-start;padding:60px 20px;overflow-y:auto}
  .modal-overlay.show{display:flex}
  .modal{background:#181F2E;border:1px solid #2A3B5C;border-radius:14px;width:100%;max-width:600px;max-height:80vh;overflow-y:auto;box-shadow:0 20px 60px rgba(0,0,0,.5)}
  .modal-header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid #2A3B5C;position:sticky;top:0;background:#181F2E;z-index:1}
  .modal-header h3{margin:0;font-size:15px;color:#E3E8EF;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-close{background:none;border:none;color:#66788C;font-size:20px;cursor:pointer;padding:4px 8px;border-radius:6px;transition:all .15s}
  .modal-close:hover{background:#22335A;color:#E3E8EF}
  .modal-body{padding:16px 20px}
  .modal-section{margin-bottom:16px}
  .modal-section-title{font-size:12px;font-weight:700;color:#55688C;text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px}
  .modal-stat{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
  .modal-stat-item{background:#1A2540;border-radius:8px;padding:10px 12px}
  .modal-stat-label{font-size:11px;color:#55688C;margin-bottom:2px}
  .modal-stat-value{font-size:14px;font-weight:600;color:#E3E8EF}
  .modal-stat-value.green{color:#6a8}
  .modal-stat-value.red{color:#f86}
  .modal-stat-value.blue{color:#8FD8FF}
  .modal-peer-list{max-height:200px;overflow-y:auto}
  .modal-peer-row{display:flex;align-items:center;padding:6px 8px;border-radius:6px;font-size:12px;color:#A0AABB;gap:8px}
  .modal-peer-row:nth-child(odd){background:#1A2540}
  .modal-peer-ip{color:#8FD8FF;font-weight:600;min-width:120px}
  .modal-peer-client{color:#66788C;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-peer-speed{color:#6a8;min-width:70px;text-align:right}
  .modal-file-row{display:flex;align-items:center;padding:6px 8px;border-radius:6px;font-size:12px;color:#A0AABB;gap:8px}
  .modal-file-row:nth-child(odd){background:#1A2540}
  .modal-file-path{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-file-size{color:#55688C;min-width:60px;text-align:right}
  .modal-refresh{background:#22335A;border:1px solid #2A3B5C;color:#8FA3BF;padding:6px 12px;border-radius:8px;cursor:pointer;font-size:12px;transition:all .15s}
  .modal-refresh:hover{background:#2A3B5C;color:#E3E8EF}
  .card{cursor:pointer}
  #treePanel{width:172px;flex-shrink:0;background:#0D1830;border:1px solid #22345A;border-radius:12px;padding:8px;max-height:62vh;overflow-y:auto;font-size:13px}
  .tree-title{font-size:11px;font-weight:700;color:#55688C;text-transform:uppercase;letter-spacing:.5px;padding:2px 8px 6px}
  .tree-item{padding:5px 8px;border-radius:6px;cursor:pointer;color:#9FB4D4;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .tree-item:hover{background:#122A4D;color:#E6EEF8}
  .tree-item.active{background:#2F80ED;color:#fff;font-weight:600}
  .tree-children{padding-left:12px;border-left:1px solid #1B2B4D;margin-left:8px}
  .tree-empty{color:#55688C;font-size:12px;padding:4px 8px}
  /* ── 설정 탭 전용 ── */
  .sg{background:#101E3A;border:1px solid #22345A;border-radius:14px;padding:16px}
  .sh{font-size:14px;font-weight:700;color:#8FD8FF;padding-left:10px;border-left:3px solid #2F80ED;margin-bottom:14px}
  .sr{display:flex;flex-wrap:wrap;gap:16px}
  .si{flex:1;min-width:220px}
  .sl{display:block;margin-bottom:5px;font-size:12px;color:#8FA3BF;font-weight:500}
  .sv{display:flex;align-items:center;gap:8px}
  .sv span:last-child{min-width:60px;text-align:right;font-weight:600;font-size:13px}
  .sv input[type=range]{flex:1;accent-color:#2F80ED}
  .ck{display:flex;align-items:center;gap:8px}
  .ck input[type=checkbox]{width:18px;height:18px;accent-color:#2F80ED}
  .ck label{font-size:13px;color:#E6EEF8;cursor:pointer}
  .sb{font-size:11px;color:#55688C;margin-top:3px;line-height:1.4}
  .ti{font-weight:600;font-size:13px;color:#E6EEF8;margin:12px 0 6px}
  .ti:first-child{margin-top:0}
  .ss{font-size:12px;padding:8px 10px;border-radius:8px;background:#12203D;color:#55688C;margin-top:8px;line-height:1.5}
  .ft{display:flex;align-items:center;gap:8px}
  .ft input[type=number]{width:90px;padding:7px 10px;border-radius:8px;border:1px solid #2A3B5C;background:#12203D;color:#fff;font-size:13px}
  .fp{display:flex;gap:8px}
  .fp input[type=text]{flex:1;padding:8px 12px;border-radius:8px;border:1px solid #2A3B5C;background:#12203D;color:#fff;font-size:13px}
  .rb{display:flex;gap:8px;flex-wrap:wrap}
  .rb button{flex-shrink:0}
</style></head><body>
<div class="wrap">
  <h1>📡 DroidRelay</h1>
  <div class="sub">이 페이지에서 요청하면 휴대폰이 직접 다운로드합니다 · 끊겨도 이어받기됩니다</div>
  <div class="info" id="info">서버 정보 로딩 중…</div>

  <div class="tabs">
    <div class="tab active" onclick="switchTab('dl')">다운로드</div>
    <div class="tab" onclick="switchTab('torrent')">토렌트</div>
    <div class="tab" onclick="switchTab('storage')">보관함</div>
    <div class="tab" onclick="switchTab('settings')">설정</div>
  </div>

    <!-- 다운로드 탭 -->
  <div class="panel active" id="panel-dl">
    <div class="row">
      <input id="url" type="url" placeholder="다운로드 URL 붙여넣기 (여러 개 가능 · 줄바꿈/공백 구분)">
      <button onclick="add()">추가</button>
    </div>
    <div class="row" style="margin-top:8px">
      <input id="sha256" type="text" placeholder="SHA-256 (선택 · 64자리 16진수 — 단일 URL에만 적용, 완료 후 검증)" style="font-size:12px">
    </div>
    <div id="list"></div>
    <div class="empty" id="empty">아직 작업이 없습니다</div>
  </div>

  <!-- 토렌트 탭 -->
  <div class="panel" id="panel-torrent">
    <div class="row-torrent">
      <input id="magnet" placeholder="magnet:?xt=... 붙여넣기" style="flex:1">
      <button onclick="addMagnet()">추가</button>
      <button class="ghost" onclick="document.getElementById('torrentFile').click()">파일</button>
    </div>
    <input type="file" id="torrentFile" accept=".torrent" onchange="uploadTorrent(this)">
    <div class="drop-zone" id="torrentDrop">
      또는 .torrent 파일을 여기에 드래그하세요
    </div>
    <div id="torrentInfo" style="padding:10px 16px;font-size:13px;color:#66788C;border-bottom:1px solid #E3E8EF"></div>
    <div id="torrentList"></div>
    <div class="empty" id="torrentEmpty">토렌트 작업이 없습니다</div>
  </div>

  <!-- 보관함 탭 -->
  <div class="panel" id="panel-storage">
    <div style="display:flex;gap:12px;align-items:flex-start">
      <div id="treePanel"></div>
      <div style="flex:1;min-width:0">
        <div class="breadcrumb" id="breadcrumb"></div>
        <div class="toolbar" id="storageToolbar">
          <button class="ghost" onclick="createFolder()">📁 폴더 만들기</button>
          <button class="ghost" onclick="document.getElementById('uploadFile').click()">⬆ 파일 올리기</button>
          <input type="file" id="uploadFile" multiple onchange="uploadFiles(this)">
          <button class="ghost" onclick="deleteSelected()" id="deleteBtn" style="display:none">🗑 삭제</button>
          <button class="ghost" onclick="toggleTrash()" style="margin-left:auto">🗑️ 휴지통</button>
        </div>
        <div class="toolbar" id="trashToolbar" style="display:none">
          <button class="ghost" onclick="toggleTrash()">← 보관함 돌아가기</button>
          <button class="ghost" id="trashEmptyBtn" onclick="emptyTrash()" style="color:#FF8A93;border-color:#40191C">🔥 비우기</button>
        </div>
        <div id="fileList"></div>
        <div class="empty" id="fileEmpty">비어 있습니다</div>
      </div>
    </div>
  </div>

    <!-- 설정 탭 -->
  <div class="panel" id="panel-settings">
    <div class="sg" style="margin-bottom:16px">
      <div class="sh">⚡ 전역 속도 제한</div>
      <div class="sr">
        <div class="si">
          <div class="sl">다운로드 제한</div>
          <div class="sv">
            <input type="checkbox" id="dlSpeedEnabled" onchange="toggleSpeedLimit('dl')">
            <input type="range" id="maxDownloadMbps" min="1" max="10" value="3" step="1" disabled>
            <span id="maxDownloadLabel">3 Mbps</span>
          </div>
        </div>
        <div class="si">
          <div class="sl">업로드 제한</div>
          <div class="sv">
            <input type="checkbox" id="ulSpeedEnabled" onchange="toggleSpeedLimit('ul')">
            <input type="range" id="maxUploadMbps" min="1" max="10" value="3" step="1" disabled>
            <span id="maxUploadLabel" style="color:#f96">3 Mbps</span>
          </div>
        </div>
      </div>
      <div id="speedLimitStatus" class="ss"></div>
    </div>

    <div class="sg" style="margin-bottom:16px">
      <div class="sh">⬇ 다운로드 설정</div>
      <div class="sr">
        <div class="si">
          <div class="sl">동시 다운로드 수</div>
          <div class="sv">
            <input type="range" id="concurrency" min="1" max="4" value="2" step="1">
            <span id="concurrencyLabel">2</span>
          </div>
        </div>
        <div class="si">
          <div class="sl">앱 레벨 속도 제한</div>
          <div class="sv">
            <input type="range" id="speedLimitKbps" min="0" max="2048" value="0" step="128">
            <span id="speedLimitLabel">무제한</span>
          </div>
          <div class="sb">KB/s 단위 · 0 = 무제한 · 전역 제한과 별도 작동</div>
        </div>
      </div>
      <div class="ck" style="margin-top:12px">
        <input type="checkbox" id="notifications" checked>
        <label for="notifications">다운로드 알림 표시 (앱에서만 적용)</label>
      </div>
    </div>

    <div class="sg" style="margin-bottom:16px">
      <div class="sh">🌊 토렌트 설정</div>
      <div class="ti">속도</div>
      <div class="sr">
        <div class="si">
          <div class="sl">업로드 속도</div>
          <div class="sv">
            <input type="range" id="torrentUploadLimit" min="0" max="1024" value="512" step="64">
            <span id="torrentUploadLabel" style="color:#f96">512 KB/s</span>
          </div>
          <div class="sb">KB/s 단위 · 0 = 업로드 안 함 (피어 평판 저하 유의)</div>
        </div>
        <div class="si">
          <div class="sl">다운로드 속도</div>
          <div class="sv">
            <input type="range" id="torrentDownloadLimit" min="0" max="20480" value="0" step="1024">
            <span id="torrentDownloadLabel">무제한</span>
          </div>
          <div class="sb">KB/s 단위 · 0 = 무제한 · 전역 제한과 별도 작동</div>
        </div>
      </div>
      <div class="ti">연결</div>
      <div class="sr">
        <div class="si">
          <div class="sl">최대 활성 토렌트 수</div>
          <div class="sv">
            <input type="range" id="torrentMaxActive" min="1" max="10" value="3" step="1">
            <span id="torrentMaxActiveLabel">3</span>
          </div>
        </div>
        <div class="si">
          <div class="sl">최대 시드 비율</div>
          <div class="sv">
            <input type="range" id="torrentSeedRatio" min="0" max="10" value="2" step="0.1">
            <span id="torrentSeedRatioLabel">2.0</span>
          </div>
        </div>
      </div>
      <div class="ti">고급</div>
      <div class="ck" style="gap:20px">
        <div class="ck"><input type="checkbox" id="torrentDhtEnabled" checked><label for="torrentDhtEnabled">DHT (분산 해시 테이블)</label></div>
        <div class="ck"><input type="checkbox" id="torrentPexEnabled" checked><label for="torrentPexEnabled">PEX (피어 교환)</label></div>
      </div>
      <div class="ti">리슨 포트</div>
      <div class="ft">
        <input type="number" id="torrentListenPort" min="1024" max="65535" value="6881">
        <button class="ghost sm" onclick="randomizePort()">🎲 랜덤</button>
      </div>
      <div class="sb" style="margin-top:4px">변경 시 토렌트 엔진 재시작 필요</div>
      <div class="ti">저장 경로</div>
      <div class="fp">
        <input type="text" id="torrentSavePath" value="/sdcard/Download/DroidRelay">
        <button class="ghost sm" onclick="testPath()">테스트</button>
      </div>
      <div id="pathTestResult" class="sb"></div>
    </div>

    <div class="sg">
      <div class="sh">↩ 기본값 복원</div>
      <div class="rb">
        <button class="ghost" onclick="resetSettings('download')">↩ 다운로드 기본값</button>
        <button class="ghost" onclick="resetSettings('torrent')">↩ 토렌트 기본값</button>
        <button class="ghost" onclick="resetSettings('all')" style="margin-left:auto;color:#FF8A93;border-color:#40191C">🔄 전체 초기화</button>
      </div>
    </div>
  </div>
</div>
<div class="modal-overlay" id="torrentModal">
  <div class="modal">
    <div class="modal-header">
      <h3 id="tmTitle">토렌트 상세</h3>
      <button class="modal-close" onclick="closeTorrentModal()">✕</button>
    </div>
    <div class="modal-body" id="tmBody">
      <div style="padding:20px;color:#55688C;text-align:center">로딩 중…</div>
    </div>
  </div>
</div>
<script>
var BASE='/sdcard/Download/DroidRelay';
var curPath='';
var selItem=null;
var curTab='dl';
var trashMode=false;
window.__dragActiveAt=0;
function dragActive(){return window.__dragActive&&(Date.now()-window.__dragActiveAt)<5000;}
window.onerror=function(msg,src,line){var t=document.createElement('div');t.textContent='⚠ JS 오류: '+msg+' @'+line;t.style.cssText='position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#5c1a1a;color:#ff8a93;padding:8px 14px;border-radius:8px;font-size:12px;z-index:99999';document.body.appendChild(t);setTimeout(function(){t.remove()},6000);};

function esc(s){return (s||'').replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}
function showDlToast(msg){var t=document.createElement('div');t.textContent=msg||'다운로드 요청 했습니다';t.style.cssText='position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1a5c3a;color:#69e29b;padding:10px 20px;border-radius:8px;font-size:14px;z-index:9999;opacity:1;transition:opacity 1.5s';document.body.appendChild(t);setTimeout(function(){t.style.opacity='0'},1500);setTimeout(function(){t.remove()},3000);}
function fmt(n){if(n==null||n<0)return '?';if(n<1048576)return (n/1024).toFixed(0)+' KB';if(n<1073741824)return (n/1048576).toFixed(1)+' MB';return (n/1073741824).toFixed(2)+' GB';}
function spd(bps){return bps>0?(bps/1024).toFixed(0)+' KB/s':'-';}
function label(st){return {QUEUED:'대기',RUNNING:'진행 중',PAUSED:'일시정지',DONE:'완료',FAILED:'실패',CANCELED:'취소됨',ADDING:'추가 중',METADATA:'메타데이터',FETCHING_METADATA:'메타데이터 추출 중',DOWNLOADING:'진행 중',SEEDING:'시딩'}[st]||st;}
function fmtDuration(sec){
  if(sec==null||sec<0||!isFinite(sec))return'';
  sec=Math.round(sec);
  if(sec<60)return sec+'초';
  var h=Math.floor(sec/3600);var m=Math.floor((sec%3600)/60);var s=sec%60;
  if(h>0)return h+'시간 '+m+'분';
  return m+'분 '+s+'초';
}
function fileIcon(name,isDir){
  if(isDir)return '📁';
  var ext=(name||'').split('.').pop().toLowerCase();
  var icons={mp4:'🎬',mkv:'🎬',avi:'🎬',mov:'🎬',mp3:'🎵',wav:'🎵',flac:'🎵',ogg:'🎵',
    jpg:'🖼️',jpeg:'🖼️',png:'🖼️',gif:'🖼️',webp:'🖼️',pdf:'📕',zip:'📦',rar:'📦',
    '7z':'📦',apk:'📱',exe:'💿',iso:'💿',txt:'📝',md:'📝',json:'📝',xml:'📝',torrent:'🔗'};
  return icons[ext]||'📄';
}
function switchTab(t){
  curTab=t;
  if(trashMode&&t!=='storage')toggleTrash();
  document.querySelectorAll('.tab').forEach(function(el,i){
    el.classList.toggle('active',(['dl','torrent','storage'])[i]===t);
  });
  document.getElementById('panel-dl').classList.toggle('active',t==='dl');
  document.getElementById('panel-torrent').classList.toggle('active',t==='torrent');
  document.getElementById('panel-storage').classList.toggle('active',t==='storage');
  if(t==='storage')refreshStorage();
}
function render(jobs){
  if(dragActive())return;
  var el=document.getElementById('list');document.getElementById('empty').style.display=jobs.length?'none':'block';
  var totalSpeed=jobs.filter(function(j){return j.state==='RUNNING'}).reduce(function(a,j){return a+(j.speedBps||0)},0);
  var h='';
  var now=Date.now();
  jobs.forEach(function(j){
    var pct=Math.min(100,Math.max(0,j.totalBytes>0?Math.round(j.progress*100):(j.state==='DONE'?100:0)));
    var size=j.downloadedBytes?(fmt(j.downloadedBytes)+(j.totalBytes>0?' / '+fmt(j.totalBytes):'')):'';
    var sp=j.state==='RUNNING'?'<span class="speed">'+spd(j.speedBps)+'</span>':'';
    var eta='';
    if(j.state==='RUNNING'&&j.speedBps>0){
      var parts=[];
      if(j.startedAt>0){var elapsed=(now-j.startedAt)/1000;parts.push(fmtDuration(elapsed)+' 경과');}
      if(j.totalBytes>0&&j.downloadedBytes<j.totalBytes){var remain=(j.totalBytes-j.downloadedBytes)/j.speedBps;parts.push(fmtDuration(remain)+' 남음');}
      if(parts.length>0)eta='<span class="eta"> · '+parts.join(' · ')+'</span>';
    }
    var err=j.errorMessage?'<div class="err">'+esc(j.errorMessage)+'</div>':'';
    var vbadge='';
    if(j.verified)vbadge='<span class="badge DONE">✓ 검증됨</span>';
    else if(j.hasChecksum&&j.state==='RUNNING')vbadge='<span class="badge QUEUED">해시 검증 예정</span>';
    var act=j.state==='DONE'?'<a class="btn-dl" href="/file/'+j.id+'" download onclick="showDlToast()">📥 받기</a>':'';
    var pause='';
    if(j.state==='RUNNING')pause='<button class="ghost" onclick="act(\''+j.id+'\',\'pause\')">일시정지</button>';
    if(j.state==='PAUSED'||j.state==='FAILED')pause='<button class="ghost" onclick="act(\''+j.id+'\',\'resume\')">재개</button>';
    var cancel='<button class="ghost" onclick="if(confirm(\'이 항목을 삭제하시겠습니까?\'))delJob(\''+j.id+'\')">삭제</button>';
    h+='<div class="card" draggable="true" data-id="'+j.id+'">'
      +'<div style="flex:1;min-width:0;padding:14px">'
      +'<div class="name">'+esc(j.filename)+'</div>'
      +'<div class="meta"><span class="badge '+j.state+'">'+label(j.state)+'</span>'+vbadge+'<span>'+size+'</span><span>'+pct+'%</span>'+sp+eta+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+err
      +'</div><div style="display:flex;flex-direction:column;gap:6px;align-items:flex-end;padding:14px 14px 14px 0">'+act+pause+cancel+'</div></div>';
  });
  if(window.__lastJobsH!==h){window.__lastJobsH=h;el.innerHTML=h;}
  document.getElementById('info').innerHTML='<span>⚡ 총 속도 <b class="speed">'+spd(totalSpeed)+'</b></span>'
    +'<span>· 진행 '+jobs.filter(function(j){return j.state==='RUNNING'}).length+'건</span>'
    +'<span>· 저장공간 여유 <b>'+(window.__info&&window.__info.storageFree!=null?fmt(window.__info.storageFree):'?')+'</b>'
    +(window.__info&&window.__info.storageTotal?(' / '+fmt(window.__info.storageTotal)):'')+'</span>'
    +(window.__info&&window.__info.version?('<span style="margin-left:auto;color:#55688C">v'+window.__info.version+'</span>'):'');
}
function renderTorrents(ts){
  if(dragActive())return;
  var el=document.getElementById('torrentList');document.getElementById('torrentEmpty').style.display=ts.length?'none':'block';
  var h='';
  var now=Date.now();
  ts.forEach(function(t){
    var pct=Math.min(100,Math.max(0,t.progress!=null?Math.round(t.progress*100):0));
    var size=t.totalSize>0?(fmt(t.downloadedSize)+' / '+fmt(t.totalSize)):(t.downloadedSize>0?fmt(t.downloadedSize):'');
    var sp=t.state==='DOWNLOADING'||t.state==='SEEDING'?'<span class="speed">'+spd(t.downloadSpeed||0)+'</span>':'';
    var up='';
    if(t.uploadSpeed>0)up='<span style="color:#f96;font-size:12px;margin-left:6px">▲'+spd(t.uploadSpeed)+'</span>';
    var eta='';
    if(t.state==='DOWNLOADING'&&t.downloadSpeed>0){
      var parts=[];
      if(t.startedAt>0){var elapsed=(now-t.startedAt)/1000;parts.push(fmtDuration(elapsed)+' 경과');}
      if(t.totalSize>0&&t.downloadedSize<t.totalSize){var remain=(t.totalSize-t.downloadedSize)/t.downloadSpeed;parts.push(fmtDuration(remain)+' 남음');}
      if(parts.length>0)eta='<span class="eta"> · '+parts.join(' · ')+'</span>';
    }
    var seedInfo='';
    if(t.seeds>0||t.peers>0){
      seedInfo='<span style="color:#6a8;font-size:12px">▲'+(t.seeds||0)+'</span> <span style="color:#f86;font-size:12px">▼'+(t.peers||0)+'</span>';
    }
    var filePath='';
    if(t.files&&t.files.length>0){
      filePath='<div style="font-size:11px;color:#667;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(t.files[0].path)+'</div>';
    }
    var err=t.errorMessage?'<div class="err">'+esc(t.errorMessage)+'</div>':'';
    var st=t.state||'UNKNOWN';
    var badgeClass={DOWNLOADING:'RUNNING',SEEDING:'DONE',PAUSED:'PAUSED',ERROR:'FAILED',FETCHING_METADATA:'QUEUED',METADATA:'QUEUED',ADDING:'QUEUED'}[st]||'QUEUED';
    var pause='';
    if(st==='DOWNLOADING'||st==='SEEDING')pause='<button class="ghost" onclick="torrentAct(\''+t.id+'\',\'pause\')">일시정지</button>';
    if(st==='PAUSED')pause='<button class="ghost" onclick="torrentAct(\''+t.id+'\',\'resume\')">재개</button>';
    var del='<button class="ghost" onclick="torrentDel(\''+t.id+'\')">삭제</button>';
    h+='<div class="card" draggable="true" data-id="'+t.id+'">'
      +'<div style="flex:1;min-width:0;padding:14px">'
      +'<div class="name">'+esc(t.name||t.hash||'파일 불명')+'</div>'
      +'<div class="meta"><span class="badge '+badgeClass+'">'+label(st)+'</span><span>'+size+'</span><span>'+pct+'%</span>'+sp+up+eta+(seedInfo?' '+seedInfo:'')+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+filePath+err
      +'</div><div style="display:flex;flex-direction:column;gap:6px;align-items:flex-end;padding:14px 14px 14px 0">'+pause+del+'</div></div>';
  });
  if(window.__lastTorrentsH!==h){window.__lastTorrentsH=h;el.innerHTML=h;}
  var totalDown=ts.reduce(function(a,t){return a+(t.downloadSpeed||0);},0);
  var totalUp=ts.reduce(function(a,t){return a+(t.uploadSpeed||0);},0);
  var active=ts.filter(function(t){return t.state==='DOWNLOADING'||t.state==='SEEDING'||t.state==='FETCHING_METADATA';}).length;
  var info=document.getElementById('torrentInfo');
  if(info)info.innerHTML='<span>⚡ 다운 <b class="speed">'+spd(totalDown)+'</b></span>'
    +'<span style="margin-left:12px">▲ 업로드 <b style="color:#f96">'+spd(totalUp)+'</b></span>'
    +'<span style="margin-left:12px">· 진행 '+active+'건</span>'
    +'<span style="margin-left:12px">· 시드 <b style="color:#6a8">'+ts.reduce(function(a,t){return a+(t.seeds||0);},0)+'</b>'
    +' 피어 <b style="color:#f86">'+ts.reduce(function(a,t){return a+(t.peers||0);},0)+'</b></span>';
}
function renderStorage(items){
  if(dragActive())return;
  var el=document.getElementById('fileList');document.getElementById('fileEmpty').style.display=items.length?'none':'block';
  var h='';
  items.forEach(function(f){
    var isDir=f.type==='dir';
    var key=curPath?curPath+'/'+f.name:f.name;
    var cls=(selItem&&selItem.name===f.name)?'file-row selected':'file-row';
    var dateStr=f.modified?new Date(f.modified).toLocaleDateString('ko-KR',{year:'numeric',month:'2-digit',day:'2-digit'}):'';
    h+='<div class="'+cls+'" draggable="true" data-type="'+f.type+'" data-key="'+esc(key)+'" data-name="'+esc(f.name)+'">'
      +'<span class="file-icon">'+fileIcon(f.name,isDir)+'</span>'
      +'<div style="min-width:0;flex:1"><div class="name">'+esc(f.name)+'</div>'
      +'<div class="file-meta">'+(isDir?(f.count+'개'):(fmt(f.size)+(dateStr?' · '+dateStr:'')))+'</div></div>'
      +'<div class="acts">';
    if(!isDir){
      h+='<a class="btn-dl" href="/dl-file/'+encodeURIComponent(key)+'" download onclick="event.stopPropagation();showDlToast()">📥</a>';
    }
    h+='<button class="ghost sm" data-act="rename">✏️</button>';
    h+='<button class="ghost sm" data-act="del">🗑</button>';
    h+='</div></div>';
  });
  if(window.__lastStorageH!==h){window.__lastStorageH=h;el.innerHTML=h;}
  updateBreadcrumb();
  updateToolbar();
}
function updateBreadcrumb(){
  var parts=curPath?curPath.split('/'):[];
  var h='<span data-path="" onclick="openDir(\'\')">📱 보관함</span>';
  var p='';
  parts.forEach(function(part){
    if(!part)return;
    p+=(p?'/':'')+part;
    (function(path){h+=' / <span data-path="'+esc(path)+'" onclick="openDir('+JSON.stringify(path)+')">'+esc(part)+'</span>'})(p);
  });
  document.getElementById('breadcrumb').innerHTML=h;
}
(function(){
  var bc=document.getElementById('breadcrumb');
  if(!bc||bc.__delegated)return;bc.__delegated=true;
  function clearMarks(){document.querySelectorAll('#breadcrumb .drop-target').forEach(function(s){s.classList.remove('drop-target')});}
  bc.addEventListener('dragover',function(e){
    if(!window.__dragKey)return;
    var span=e.target.closest('[data-path]');if(!span)return;
    e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';
    clearMarks();
    span.classList.add('drop-target');
  });
  bc.addEventListener('dragleave',function(e){
    if(e.target===bc||!e.relatedTarget||!bc.contains(e.relatedTarget))clearMarks();
  });
  bc.addEventListener('drop',function(e){
    e.preventDefault();
    var src=window.__dragKey;
    var span=e.target.closest('[data-path]');
    clearMarks();
    if(!src||!span)return;
    var targetPath=span.dataset.path;
    var srcDir=src.indexOf('/')>=0?src.slice(0,src.lastIndexOf('/')):'';
    if(srcDir===targetPath)return;
    var name=src.slice(src.lastIndexOf('/')+1);
    if(targetPath===src||targetPath.indexOf(src+'/')===0)return;
    fetch('/api/storage/move',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({from:src,to:targetPath})})
      .then(function(res){return res.json()}).then(function(d){
        if(d.error){alert(d.error)}else{showDlToast('이동했습니다 → '+(targetPath||'📱 보관함'))}
        openDir(targetPath)})
      .catch(function(err){alert(err)});
  });
})();
function updateToolbar(){
  document.getElementById('deleteBtn').style.display=selItem?'inline-block':'none';
}
function selectItem(el,f){
  document.querySelectorAll('.file-row').forEach(function(r){r.classList.remove('selected')});
  el.classList.add('selected');selItem=f;updateToolbar();
}
function openDir(path){curPath=path;selItem=null;window.__lastStorageH=null;refreshStorage();}
(function(){
  var list=document.getElementById('fileList');
  if(!list||list.__delegated)return;list.__delegated=true;
  function rowOf(t){return t&&t.closest?t.closest('.file-row'):null;}
  function rowData(row){return{name:row.dataset.name,type:row.dataset.type,key:row.dataset.key};}
  function clearDropMarks(){document.querySelectorAll('#fileList .drop-target').forEach(function(r){r.classList.remove('drop-target')});}
  list.addEventListener('click',function(e){
    var btn=e.target.closest('[data-act]');
    if(btn){
      e.stopPropagation();
      var row=rowOf(btn);if(!row)return;
      var f=rowData(row),a=btn.dataset.act;
      if(a==='rename')renameItem(f);else if(a==='del')delItem(f);
      return;
    }
    if(e.target.closest('a.btn-dl'))return;
    var r=rowOf(e.target);if(!r)return;
    var f=rowData(r);
    if(f.type==='dir')openDir(f.key);else selectItem(r,f);
  });
  list.addEventListener('dragstart',function(e){
    var r=rowOf(e.target);if(!r)return;
    window.__dragActive=true;window.__dragActiveAt=Date.now();window.__dragKey=r.dataset.key;
    r.classList.add('dragging');
    if(e.dataTransfer){e.dataTransfer.setData('text/plain',r.dataset.key);e.dataTransfer.effectAllowed='move';}
  });
  list.addEventListener('dragover',function(e){
    if(!window.__dragKey)return;
    if(!e.dataTransfer)return;
    // 드롭 커서 항상 활성 — drop 이벤트 보장, 유효성은 drop에서 판정
    e.preventDefault();
    e.dataTransfer.dropEffect='move';
    var r=rowOf(e.target);
    clearDropMarks();
    if(!r||r.dataset.type!=='dir')return;
    var k=r.dataset.key,src=window.__dragKey;
    if(k===src||k.indexOf(src+'/')===0)return;
    r.classList.add('drop-target');
  });
  list.addEventListener('dragleave',function(e){
    if(e.target===list)clearDropMarks();
  });
  list.addEventListener('drop',function(e){
    var src=window.__dragKey;
    var r=rowOf(e.target);
    clearDropMarks();
    if(!src){showDlToast('드롭됨 — 드래그 정보 없음(재시도)');return;}
    e.preventDefault();
    if(!r||r.dataset.type!=='dir'){showDlToast('폴더 위에 놓아 주세요');return;}
    var k=r.dataset.key;
    if(k===src){showDlToast('같은 위치입니다');return;}
    if(k.indexOf(src+'/')===0){showDlToast('자기 하위 폴더로는 이동할 수 없습니다');return;}
    fetch('/api/storage/move',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({from:src,to:k})})
      .then(function(res){return res.json()}).then(function(d){
        if(d.error){alert(d.error)}else{showDlToast('이동했습니다 → '+r.dataset.name)}
        refreshStorage()})
      .catch(function(err){alert(err)});
  });
  list.addEventListener('dragend',function(){
    window.__dragActive=false;window.__dragKey=null;
    clearDropMarks();
    document.querySelectorAll('#fileList .dragging').forEach(function(r){r.classList.remove('dragging')});
    refreshStorage();
  });
})();
function refreshStorage(){
  refreshTree();
  if(trashMode){
    fetch('/api/storage/trash').then(function(r){return r.json()}).then(renderTrash).catch(function(){});
    return;
  }
  var p=curPath?('?path='+encodeURIComponent(curPath)):'';
  fetch('/api/storage'+p).then(function(r){return r.json()}).then(renderStorage).catch(function(){});
}
function refreshTree(){
  fetch('/api/storage/tree').then(function(r){return r.json()}).then(function(tree){
    var h='<div class="tree-title">폴더</div>';
    h+='<div class="tree-item'+(curPath===''?' active':'')+'" data-path="">📱 보관함</div>';
    h+='<div class="tree-children">'+treeNodes(tree)+'</div>';
    var el=document.getElementById('treePanel');
    if(el.__html!==h){el.__html=h;el.innerHTML=h;}
  }).catch(function(){});
}
function treeNodes(nodes){
  var h='';
  nodes.forEach(function(n){
    h+='<div class="tree-item'+(curPath===n.path?' active':'')+'" data-path="'+esc(n.path)+'">📁 '+esc(n.name)+'</div>';
    if(n.children&&n.children.length>0)h+='<div class="tree-children">'+treeNodes(n.children)+'</div>';
  });
  return h;
}
(function(){
  var tp=document.getElementById('treePanel');
  if(!tp||tp.__delegated)return;tp.__delegated=true;
  tp.addEventListener('click',function(e){
    var item=e.target.closest('.tree-item');if(!item)return;
    if(trashMode)toggleTrash();
    openDir(item.dataset.path);
  });
})();
function toggleTrash(){
  trashMode=!trashMode;
  document.getElementById('storageToolbar').style.display=trashMode?'none':'flex';
  document.getElementById('trashToolbar').style.display=trashMode?'flex':'none';
  selItem=null;window.__lastStorageH=null;
  refreshStorage();
}
function renderTrash(items){
  var el=document.getElementById('fileList');document.getElementById('fileEmpty').style.display=items.length?'none':'block';
  document.getElementById('trashEmptyBtn').style.display=items.length?'inline-block':'none';
  document.getElementById('breadcrumb').innerHTML='<span>🗑️ 휴지통</span><span style="color:#55688C"> · 삭제된 항목은 복구할 수 있습니다</span>';
  var h='';
  items.forEach(function(f){
    var isDir=f.type==='dir';
    var dateStr=f.modified?new Date(f.modified).toLocaleDateString('ko-KR',{year:'numeric',month:'2-digit',day:'2-digit'}):'';
    h+='<div class="file-row" data-name="'+esc(f.name)+'">'
      +'<span class="file-icon">'+(isDir?'📁':'🗑️')+'</span>'
      +'<div style="min-width:0;flex:1"><div class="name">'+esc(f.name)+'</div>'
      +'<div class="file-meta">'+(isDir?'폴더':fmt(f.size))+(dateStr?' · '+dateStr:'')+'</div></div>'
      +'<div class="acts">'
      +'<button class="ghost sm" onclick="restoreItem(this.closest(\'.file-row\').dataset.name)">♻️ 복구</button>'
      +'<button class="ghost sm" style="color:#FF8A93" onclick="purgeItem(this.closest(\'.file-row\').dataset.name)">🔥 영구삭제</button>'
      +'</div></div>';
  });
  el.innerHTML=h;
}
function restoreItem(name){
  fetch('/api/storage/trash/restore',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({name:name})})
  .then(function(r){return r.json()}).then(function(d){
    if(d.error){alert(d.error)}else{showDlToast('복구됨: '+name+' → 보관함 루트')}
    refreshStorage()}).catch(function(e){alert(e)});
}
function purgeItem(name){
  if(!confirm('영구 삭제: '+name+'\n복구할 수 없습니다.'))return;
  fetch('/api/storage/trash/purge',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({name:name})})
  .then(function(r){return r.json()}).then(function(d){
    if(d.error){alert(d.error)}else{showDlToast('영구삭제됨: '+name)}
    refreshStorage()}).catch(function(e){alert(e)});
}
function emptyTrash(){
  if(!confirm('휴지통 전체를 비웁니다.\n모든 항목이 영구 삭제되며 복구할 수 없습니다.'))return;
  fetch('/api/storage/trash/purge',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})
  .then(function(r){return r.json()}).then(function(d){
    if(d.error){alert(d.error)}else{showDlToast('휴지통을 비웠습니다')}
    refreshStorage()}).catch(function(e){alert(e)});
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
  if(!confirm('휴지통으로 이동: '+f.name+'?'))return;
  var full=curPath?curPath+'/'+f.name:f.name;
  fetch('/api/storage/delete',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({path:full})})
  .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);selItem=null;refreshStorage()}).catch(function(e){alert(e)});
}
function deleteSelected(){if(selItem)delItem(selItem);}
function uploadFiles(input){
  var files=input.files;if(!files.length)return;
  Array.from(files).forEach(function(file){
    var fd=new FormData();
    fd.append('file',file,file.name);
    fd.append('path',curPath);
    fetch('/api/storage/upload',{method:'POST',body:fd})
    .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
    .then(function(){showDlToast('업로드 완료: '+file.name);refreshStorage()})
    .catch(function(e){alert('업로드 실패: '+e)});
  });
  input.value='';
}
function refresh(){
  fetch('/api/info').then(function(r){return r.json()}).then(function(i){window.__info=i}).catch(function(){});
  fetch('/api/jobs').then(function(r){return r.json()}).then(render).catch(function(){});
  fetch('/api/torrents').then(function(r){return r.json()}).then(renderTorrents).catch(function(){});
  if(curTab==='storage')refreshStorage();
}
function add(){
  var raw=document.getElementById('url').value.trim();if(!raw)return;
  var urls=raw.split(/[\s,]+/).filter(function(u){return /^https?:\/\//i.test(u);});
  if(!urls.length){alert('유효한 http(s) URL이 없습니다');return;}
  var sha=document.getElementById('sha256').value.trim();
  var shaOk=/^[0-9a-fA-F]{64}$/.test(sha)?sha:null;
  if(sha&&!shaOk){alert('SHA-256은 64자리 16진수여야 합니다');return;}
  if(sha&&urls.length>1){showDlToast('체크섬은 단일 URL에만 적용 — 이번엔 미적용');}
  var ok=0,fail=0,done=0;
  urls.forEach(function(u){
    var body={url:u};
    if(shaOk&&urls.length===1)body.sha256=shaOk;
    fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){if(r.ok)ok++;else fail++;})
    .catch(function(){fail++;})
    .then(function(){
      if(++done===urls.length){
        document.getElementById('url').value='';
        if(urls.length===1)document.getElementById('sha256').value='';
        showDlToast(ok+'건 추가'+(fail>0?' · 실패 '+fail+'건':''));
        refresh();
      }
    });
  });
}
function addMagnet(){var u=document.getElementById('magnet').value.trim();if(!u)return;
  fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({magnet:u})})
  .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
  .then(function(){document.getElementById('magnet').value='';showDlToast('토렌트 추가됨');refresh()})
  .catch(function(e){alert('추가 실패: '+e)});}
function uploadTorrent(input){
  var file=input.files[0];if(!file)return;
  var reader=new FileReader();
  reader.onload=function(){
    var b64=reader.result.split(',')[1];
    fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({torrentFileBase64:b64,filename:file.name})})
    .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
    .then(function(){input.value='';showDlToast('토렌트 추가됨');refresh()})
    .catch(function(e){alert('추가 실패: '+e)});
  };
  reader.readAsDataURL(file);
}
function act(id,a){fetch('/api/jobs/'+id+'/'+a,{method:'POST'}).then(refresh);}
function delJob(id){fetch('/api/jobs/'+id,{method:'DELETE'}).then(refresh);}
function torrentAct(id,a){fetch('/api/torrents/'+id+'/'+a,{method:'POST'}).then(refresh);}
function torrentDel(id){fetch('/api/torrents/'+id,{method:'DELETE'}).then(refresh);}
(function(){
  ['list','torrentList'].forEach(function(cid){
    var el=document.getElementById(cid);
    if(!el||el.__reorderBound)return;el.__reorderBound=true;
    var api=cid==='list'?'/api/jobs/reorder':'/api/torrents/reorder';
    function cards(){return [].slice.call(el.querySelectorAll('.card'));}
    function clear(){cards().forEach(function(c){c.classList.remove('dragging','drop-before','drop-after')});}
    el.addEventListener('dragstart',function(e){
      var c=e.target.closest?e.target.closest('.card'):null;if(!c)return;
      window.__dragActive=true;window.__dragActiveAt=Date.now();window.__dragId=c.dataset.id;window.__dragSrc=c;window.__dropInfo=null;
      if(e.dataTransfer){e.dataTransfer.setData('text/plain',c.dataset.id);e.dataTransfer.effectAllowed='move';}
      requestAnimationFrame(function(){c.classList.add('dragging')});
    });
    el.addEventListener('dragover',function(e){
      if(!window.__dragId)return;
      if(!e.dataTransfer)return;
      // 드롭 커서 항상 활성 — drop 이벤트 보장
      e.preventDefault();
      e.dataTransfer.dropEffect='move';
      var over=e.target.closest?e.target.closest('.card'):null;
      cards().forEach(function(c){c.classList.remove('drop-before','drop-after')});
      if(!over||over===window.__dragSrc)return;
      var rc=over.getBoundingClientRect();
      var before=(e.clientY-rc.top)<rc.height/2;
      over.classList.add(before?'drop-before':'drop-after');
      window.__dropInfo={id:over.dataset.id,before:before};
    });
    el.addEventListener('drop',function(e){
      e.preventDefault();
      var info=window.__dropInfo,id=window.__dragId;
      clear();
      if(!id)return;
      if(!info||!info.id){showDlToast('다른 카드 위에 놓아 주세요');return;}
      if(info.id===id){showDlToast('같은 위치입니다');return;}
      var ids=cards().map(function(c){return c.dataset.id}).filter(function(x){return x!==id});
      var pos=ids.indexOf(info.id);
      if(pos<0)return;
      pos+=(info.before?0:1);
      fetch(api,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id,newOrder:pos})})
        .then(function(res){return res.text()}).then(function(){showDlToast('순서 변경됨');refresh()});
    });
    el.addEventListener('dragend',function(){
      window.__dragActive=false;window.__dragId=null;window.__dragSrc=null;window.__dropInfo=null;
      clear();
      refresh();
    });
  });
})();
(function(){
  var dz=document.getElementById('torrentDrop');
  if(!dz)return;
  dz.addEventListener('dragover',function(e){e.preventDefault();dz.classList.add('dragover');});
  dz.addEventListener('dragleave',function(){dz.classList.remove('dragover');});
  dz.addEventListener('drop',function(e){
    e.preventDefault();dz.classList.remove('dragover');
    var files=e.dataTransfer.files;if(!files.length)return;
    var count=0;
    Array.from(files).forEach(function(file){
      if(!file.name.endsWith('.torrent')){showDlToast('.torrent 파일만 지원됩니다');return;}
      count++;
      var reader=new FileReader();
      reader.onload=function(){
        var b64=reader.result.split(',')[1];
        fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({torrentFileBase64:b64,filename:file.name})})
        .then(function(r){if(!r.ok)return r.text().then(function(t){throw t});return r.json()})
        .then(function(){showDlToast('토렌트 추가됨: '+file.name);refresh()})
        .catch(function(e){showDlToast('추가 실패: '+e)});
      };
      reader.readAsDataURL(file);
    });
    if(count===0)showDlToast('.torrent 파일만 지원됩니다');
  });
})();
// ── 실시간 갱신: SSE 우선(T-701), 실패 시 1초 폴링 폴백 ──
var __pollTimer=null;
function setPoll(ms){if(__pollTimer)clearInterval(__pollTimer);__pollTimer=setInterval(refresh,ms);}
(function(){
  refresh();
  if(!window.EventSource){setPoll(1000);return;}
  var es=null;
  function connect(){
    try{es=new EventSource('/api/events');}catch(e){setPoll(1000);return;}
    es.onopen=function(){setPoll(10000);};   // SSE 연결 시 백업 폴링 완화
    es.onmessage=function(){refresh();};
    es.onerror=function(){
      if(es){es.close();es=null;}
      setPoll(1000);                          // 끊김 → 1초 폴링
      setTimeout(connect,5000);               // 5초 후 재시도
    };
  }
  connect();
})();

// ── 토렌트 상세 모달 ──
function openTorrentModal(id){
  var overlay=document.getElementById('torrentModal');
  overlay.classList.add('show');
  loadTorrentDetail(id);
  // 3초마다 자동 새로고침
  overlay._interval=setInterval(function(){loadTorrentDetail(id);},3000);
}
function closeTorrentModal(){
  var overlay=document.getElementById('torrentModal');
  overlay.classList.remove('show');
  if(overlay._interval)clearInterval(overlay._interval);
}
function loadTorrentDetail(id){
  fetch('/api/torrents/'+id)
    .then(function(r){return r.json();})
    .then(function(d){
      var pct=Math.min(100,Math.max(0,Math.round((d.progress||0)*100)));
      var totalDown=fmt(d.totalSize||0);
      var dlDone=fmt(d.downloadedSize||0);
      document.getElementById('tmTitle').textContent=d.name||d.id;
      var h='';
      // 상태 통계
      h+='<div class="modal-stat">';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">상태</div><div class="modal-stat-value">'+label(d.state||'UNKNOWN')+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">진행률</div><div class="modal-stat-value blue">'+pct+'%</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">크기</div><div class="modal-stat-value">'+dlDone+' / '+totalDown+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">다운로드</div><div class="modal-stat-value blue">'+spd(d.downloadSpeed||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">업로드</div><div class="modal-stat-value" style="color:#f96">'+spd(d.uploadSpeed||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">피스</div><div class="modal-stat-value">'+(d.numPieces||0)+'</div></div>';
      h+='</div>';
      // 시드/피어 스웜 정보
      h+='<div class="modal-section"><div class="modal-section-title">스웜 정보</div>';
      h+='<div class="modal-stat">';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">스웜 시드</div><div class="modal-stat-value green">'+(d.numComplete||d.listSeeds||d.seeds||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">스웜 릭처</div><div class="modal-stat-value red">'+(d.numIncomplete||d.listPeers||d.peers||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">연결 피어</div><div class="modal-stat-value">'+(d.connectedPeers||[]).length+'</div></div>';
      h+='</div></div>';
      // 트래커
      if(d.currentTracker){
        h+='<div class="modal-section"><div class="modal-section-title">트래커</div>';
        h+='<div style="font-size:12px;color:#A0AABB;word-break:break-all">'+esc(d.currentTracker)+'</div></div>';
      }
      // 연결된 피어 목록
      var peers=d.connectedPeers||[];
      if(peers.length>0){
        h+='<div class="modal-section"><div class="modal-section-title">연결된 피어 ('+peers.length+')</div>';
        h+='<div class="modal-peer-list">';
        peers.forEach(function(p){
          h+='<div class="modal-peer-row">';
          h+='<span class="modal-peer-ip">'+esc(p.ip||'?')+'</span>';
          h+='<span class="modal-peer-client">'+esc(p.client||'')+'</span>';
          h+='<span class="modal-peer-speed">▼'+spd(p.downSpeed||0)+'</span>';
          h+='<span class="modal-peer-speed" style="color:#f96">▲'+spd(p.upSpeed||0)+'</span>';
          h+='</div>';
        });
        h+='</div></div>';
      }
      // 파일 목록
      var files=d.files||[];
      if(files.length>0){
        h+='<div class="modal-section"><div class="modal-section-title">파일 ('+files.length+')</div>';
        files.forEach(function(f){
          var fpct=Math.round((f.progress||0)*100);
          h+='<div class="modal-file-row">';
          h+='<span class="modal-file-path">'+esc(f.path)+'</span>';
          h+='<span class="modal-file-size">'+fmt(f.size||0)+'</span>';
          h+='<span class="modal-stat-value blue" style="min-width:35px;text-align:right">'+fpct+'%</span>';
          h+='</div>';
        });
        h+='</div>';
      }
      document.getElementById('tmBody').innerHTML=h;
    })
    .catch(function(e){document.getElementById('tmBody').innerHTML='<div style="color:#f86;padding:20px">로딩 실패: '+esc(e.message)+'</div>';});
}

// 카드 클릭 이벤트 위임 (토렌트 카드)
document.addEventListener('click',function(e){
  var card=e.target.closest('#torrentList .card');
  if(!card)return;
  if(e.target.closest('button'))return;
  var id=card.getAttribute('data-id');
  if(id)openTorrentModal(id);
});
// 오버레이 클릭시 닫기
document.addEventListener('click',function(e){
  if(e.target.classList.contains('modal-overlay'))closeTorrentModal();
});

function switchTab(t){
  curTab=t;
  if(trashMode&&t!=='storage')toggleTrash();
  document.querySelectorAll('.tab').forEach(function(el,i){
    el.classList.toggle('active',(['dl','torrent','storage','settings'])[i]===t);
  });
  document.getElementById('panel-dl').classList.toggle('active',t==='dl');
  document.getElementById('panel-torrent').classList.toggle('active',t==='torrent');
  document.getElementById('panel-storage').classList.toggle('active',t==='storage');
  document.getElementById('panel-settings').classList.toggle('active',t==='settings');
  if(t==='storage')refreshStorage();
  if(t==='settings')loadSettings();
}

// ── 설정 로드/저장 ──
function loadSettings(){
  Promise.all([
    fetch('/api/settings/speed-limit').then(function(r){return r.json();}),
    fetch('/api/settings/download').then(function(r){return r.json();}),
    fetch('/api/settings/torrent').then(function(r){return r.json();})
  ]).then(function(res){
    var sl=res[0], dl=res[1], tr=res[2];
    // 전역 속도 제한
    document.getElementById('dlSpeedEnabled').checked=sl.maxDownloadBps>0;
    document.getElementById('maxDownloadMbps').disabled=sl.maxDownloadBps<=0;
    document.getElementById('maxDownloadMbps').value=Math.round(sl.maxDownloadBps/1048576)||3;
    document.getElementById('maxDownloadLabel').textContent=(Math.round(sl.maxDownloadBps/1048576)||3)+' Mbps';
    document.getElementById('ulSpeedEnabled').checked=sl.maxUploadBps>0;
    document.getElementById('maxUploadMbps').disabled=sl.maxUploadBps<=0;
    document.getElementById('maxUploadMbps').value=Math.round(sl.maxUploadBps/1048576)||3;
    document.getElementById('maxUploadLabel').textContent=(Math.round(sl.maxUploadBps/1048576)||3)+' Mbps';
    updateSpeedLimitStatus(sl);
    // 다운로드 설정
    document.getElementById('concurrency').value=dl.concurrency||2;
    document.getElementById('concurrencyLabel').textContent=dl.concurrency||2;
    document.getElementById('speedLimitKbps').value=dl.speedLimitKbps||0;
    document.getElementById('speedLimitLabel').textContent=(dl.speedLimitKbps||0)>0?dl.speedLimitKbps+' KB/s':'무제한';
    document.getElementById('notifications').checked=dl.notifications!==false;
    // 토렌트 설정
    document.getElementById('torrentUploadLimit').value=tr.torrentUploadLimit||512;
    document.getElementById('torrentUploadLabel').textContent=(tr.torrentUploadLimit||512)+' KB/s';
    document.getElementById('torrentDownloadLimit').value=tr.torrentDownloadLimit||0;
    document.getElementById('torrentDownloadLabel').textContent=(tr.torrentDownloadLimit||0)>0?tr.torrentDownloadLimit+' KB/s':'무제한';
    document.getElementById('torrentMaxActive').value=tr.torrentMaxActive||3;
    document.getElementById('torrentMaxActiveLabel').textContent=tr.torrentMaxActive||3;
    document.getElementById('torrentSeedRatio').value=tr.torrentSeedRatio||2.0;
    document.getElementById('torrentSeedRatioLabel').textContent=(tr.torrentSeedRatio||2.0).toFixed(1);
    document.getElementById('torrentDhtEnabled').checked=tr.torrentDhtEnabled!==false;
    document.getElementById('torrentPexEnabled').checked=tr.torrentPexEnabled!==false;
    document.getElementById('torrentListenPort').value=tr.torrentListenPort||6881;
    document.getElementById('torrentSavePath').value=tr.torrentSavePath||'/sdcard/Download/DroidRelay';
    document.getElementById('pathTestResult').textContent='';
  }).catch(function(e){
    console.error('설정 로드 실패',e);
  });
}

function updateSpeedLimitStatus(sl){
  var el=document.getElementById('speedLimitStatus');
  if(!el)return;
  if(sl.maxDownloadBps<=0&&sl.maxUploadBps<=0){
    el.innerHTML='<span style="color:#55688C">전역 속도 제한: 비활성화 (무제한)</span>';
  }else{
    var parts=[];
    if(sl.maxDownloadBps>0)parts.push('다운로드 '+fmt(sl.maxDownloadBps)+'/s');
    if(sl.maxUploadBps>0)parts.push('업로드 '+fmt(sl.maxUploadBps)+'/s');
    el.innerHTML='<span style="color:#69E29B">전역 속도 제한 적용 중: '+parts.join(' · ')+'</span>';
  }
}

function toggleSpeedLimit(type){
  var enabled, input, label;
  if(type==='dl'){
    enabled=document.getElementById('dlSpeedEnabled').checked;
    input=document.getElementById('maxDownloadMbps');
    label=document.getElementById('maxDownloadLabel');
  }else{
    enabled=document.getElementById('ulSpeedEnabled').checked;
    input=document.getElementById('maxUploadMbps');
    label=document.getElementById('maxUploadLabel');
  }
  input.disabled=!enabled;
  if(enabled){
    var mbps=parseInt(input.value)||3;
    var bps=mbps*1048576;
    saveSpeedLimit(type==='dl'?bps:0, type==='ul'?bps:0);
  }else{
    saveSpeedLimit(type==='dl'?0:undefined, type==='ul'?0:undefined);
  }
}

function onSpeedLimitChange(type){
  var input, label, mbps, bps;
  if(type==='dl'){
    input=document.getElementById('maxDownloadMbps');
    label=document.getElementById('maxDownloadLabel');
  }else{
    input=document.getElementById('maxUploadMbps');
    label=document.getElementById('maxUploadLabel');
  }
  mbps=parseInt(input.value)||3;
  bps=mbps*1048576;
  label.textContent=mbps+' Mbps';
  saveSpeedLimit(type==='dl'?bps:undefined, type==='ul'?bps:undefined);
}

function saveSpeedLimit(dl, ul){
  var body={};
  if(dl!==undefined)body.maxDownloadBps=dl;
  if(ul!==undefined)body.maxUploadBps=ul;
  fetch('/api/settings/speed-limit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){return r.json();})
    .then(function(d){if(d.ok)loadSettings();else alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}

function saveDownloadSettings(){
  var body={
    concurrency:parseInt(document.getElementById('concurrency').value)||2,
    speedLimitKbps:parseInt(document.getElementById('speedLimitKbps').value)||0,
    notifications:document.getElementById('notifications').checked
  };
  document.getElementById('concurrencyLabel').textContent=body.concurrency;
  document.getElementById('speedLimitLabel').textContent=body.speedLimitKbps>0?body.speedLimitKbps+' KB/s':'무제한';
  fetch('/api/settings/download',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){return r.json();})
    .then(function(d){if(!d.ok)alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}

function saveTorrentSettings(){
  var body={
    torrentUploadLimit:parseInt(document.getElementById('torrentUploadLimit').value)||512,
    torrentDownloadLimit:parseInt(document.getElementById('torrentDownloadLimit').value)||0,
    torrentMaxActive:parseInt(document.getElementById('torrentMaxActive').value)||3,
    torrentSeedRatio:parseFloat(document.getElementById('torrentSeedRatio').value)||2.0,
    torrentDhtEnabled:document.getElementById('torrentDhtEnabled').checked,
    torrentPexEnabled:document.getElementById('torrentPexEnabled').checked,
    torrentListenPort:parseInt(document.getElementById('torrentListenPort').value)||6881,
    torrentSavePath:document.getElementById('torrentSavePath').value.trim()
  };
  document.getElementById('torrentUploadLabel').textContent=body.torrentUploadLimit+' KB/s';
  document.getElementById('torrentDownloadLabel').textContent=body.torrentDownloadLimit>0?body.torrentDownloadLimit+' KB/s':'무제한';
  document.getElementById('torrentMaxActiveLabel').textContent=body.torrentMaxActive;
  document.getElementById('torrentSeedRatioLabel').textContent=body.torrentSeedRatio.toFixed(1);
  fetch('/api/settings/torrent',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){return r.json();})
    .then(function(d){if(d.ok){showDlToast('토렌트 설정 저장됨');}else alert('저장 실패: '+(d.error||''));})
    .catch(function(e){alert('저장 실패: '+e);});
}

function randomizePort(){
  var port=49152+Math.floor(Math.random()*16384);
  document.getElementById('torrentListenPort').value=port;
  saveTorrentSettings();
}

function testPath(){
  var path=document.getElementById('torrentSavePath').value.trim();
  if(!path){alert('경로를 입력하세요');return;}
  var el=document.getElementById('pathTestResult');
  el.textContent='테스트 중...';el.style.color='#8FD8FF';
  fetch('/api/storage/test-path',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:path})})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){
        el.textContent='✓ 쓰기 가능';el.style.color='#69E29B';
      }else{
        el.textContent='✗ 쓰기 불가: '+(d.error||'알 수 없는 오류');el.style.color='#FF8A93';
      }
    })
    .catch(function(e){el.textContent='✗ 테스트 실패: '+e;el.style.color='#FF8A93';});
}

function resetSettings(category){
  if(!confirm((category==='all'?'전체 설정을':'\''+category+'\''+' 설정을')+' 기본값으로 초기화하시겠습니까?\n(전역 속도 제한은 초기화되지 않습니다)'))return;
  fetch('/api/settings/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({category:category})})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){
        showDlToast('기본값 복원됨');
        loadSettings();
      }else alert('실패');
    })
    .catch(function(e){alert('실패: '+e);});
}

// 슬라이더 변경 이벤트 바인딩 (초기화 시 한 번만)
(function(){
  var s=document.getElementById('concurrency');
  if(s)s.addEventListener('input',function(){document.getElementById('concurrencyLabel').textContent=this.value;});
  s=document.getElementById('speedLimitKbps');
  if(s)s.addEventListener('input',function(){document.getElementById('speedLimitLabel').textContent=this.value>0?this.value+' KB/s':'무제한';});
  s=document.getElementById('torrentUploadLimit');
  if(s)s.addEventListener('input',function(){document.getElementById('torrentUploadLabel').textContent=this.value+' KB/s';});
  s=document.getElementById('torrentDownloadLimit');
  if(s)s.addEventListener('input',function(){document.getElementById('torrentDownloadLabel').textContent=this.value>0?this.value+' KB/s':'무제한';});
  s=document.getElementById('torrentMaxActive');
  if(s)s.addEventListener('input',function(){document.getElementById('torrentMaxActiveLabel').textContent=this.value;});
  s=document.getElementById('torrentSeedRatio');
  if(s)s.addEventListener('input',function(){document.getElementById('torrentSeedRatioLabel').textContent=parseFloat(this.value).toFixed(1);});
  // 저장 버튼이 없으므로 입력 시 자동 저장 (디바운스)
  var debounceTimers={};
  function autoSave(key,fn){
    return function(){
      clearTimeout(debounceTimers[key]);
      debounceTimers[key]=setTimeout(fn,500);
    };
  }
  document.getElementById('concurrency')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('speedLimitKbps')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('notifications')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('torrentUploadLimit')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentDownloadLimit')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentMaxActive')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentSeedRatio')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentDhtEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentPexEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentListenPort')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentSavePath')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
})();
</script></body></html>"""
}
