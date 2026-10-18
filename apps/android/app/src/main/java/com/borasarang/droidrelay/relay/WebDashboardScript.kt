package com.borasarang.droidrelay.relay

/// 대시보드 **스크립트** 조각.
///
/// 원래 `WebDashboardHtml.kt` 한 파일(3041줄)에 CSS·마크업·JS 가 뒤섞여 있었다.
/// 관심사별로 셋을 갈랐다 — 이 파일은 `<script>` 안의 JS 만 담는다.
/// **내용은 한 글자도 바꾸지 않았다**(`WebAssets.dashboardHtml` 이 공개 계약).
internal object WebDashboardScript {
    val content: String by lazy {
        """<script>
var BASE='${com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path}';
var curPath='';
var selItem=null;
var curTab='dl';
var trashMode=false;
var storageSort='dir';
window.__dragActiveAt=0;
function dragActive(){return window.__dragActive&&(Date.now()-window.__dragActiveAt)<5000;}
window.onerror=function(msg,src,line){var t=document.createElement('div');t.textContent='⚠ JS 오류: '+msg+' @'+line;t.style.cssText='position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#5c1a1a;color:var(--err);padding:8px 14px;border-radius:8px;font-size:12px;z-index:99999';document.body.appendChild(t);setTimeout(function(){t.remove()},6000);};

function esc(s){return (s||'').replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
// 인라인 핸들러용 — 큰따옴표 속성 안에 넣을 단일따옴표 JS 문자열 리터럴.
// \ ' 는 JS 이스케이프하고 " < > & 는 엔티티로 바꿔 HTML 속성 파서가 잘리지 않게 한다.
// (브레드크럼 버그: 값을 속성 안에 그대로 끼우면 `onclick="openDir("` 까지 잘려
//  SyntaxError 가 났다 — 사용자 값은 반드시 이 헬퍼로만 인라인에 넣는다)
function jsArg(s){
  return String(s==null?'':s).replace(/\\/g,'\\\\').replace(/'/g,"\\'")
    .replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}
function num(v,d){v=parseFloat(v);return isFinite(v)?v:d;}
function kblabel(v,d,zero){v=(v!=null?v:d);return v===0?zero:v+' KB/s';}
// ── 속도 제한 프리셋 (T-1050) — 앱 SpeedLimits.KBPS와 같은 목록이 단일 진실 ──
var SPEED_PRESETS=[0,256,512,1024,2048,5120,10240,20480];
function kbpsName(k){return k<=0?'무제한':(k<1024?k+'KB/s':(k/1024)+'MB/s');}
// unitScale: 1 = option value를 KB/s로, 1024 = B/s로 저장
function fillSpeedSelect(el,curKbps,unitScale){
  if(!el)return;
  var cur=Number(curKbps)||0, list=SPEED_PRESETS.slice();
  if(list.indexOf(cur)<0)list.push(cur);
  el.innerHTML=list.map(function(k){
    return '<option value="'+(k*(unitScale||1))+'"'+(k===cur?' selected':'')+'>'+kbpsName(k)+'</option>';
  }).join('');
}
function speedSelectKbps(el,unitScale){return Math.round(num(el.value,0)/(unitScale||1));}
// 현재 B/s 값을 프리셋 option HTML로 (제한 셀렉트 공용)
function presetOptionsHtml(curBps){
  var cur=Number(curBps)||0, kbps=cur>0?Math.ceil(cur/1024):0;
  var list=SPEED_PRESETS.slice();
  if(list.indexOf(kbps)<0)list.push(kbps);
  return list.map(function(k){
    return '<option value="'+(k*1024)+'"'+(k===kbps?' selected':'')+'>'+kbpsName(k)+'</option>';
  }).join('');
}
function fillSelect(id,values,cur,unit){
  var el=document.getElementById(id);if(!el)return;
  var list=values.slice(), c=Number(cur);
  if(list.indexOf(c)<0)list.push(c);
  el.innerHTML=list.map(function(v){
    return '<option value="'+v+'"'+(v===c?' selected':'')+'>'+v+(unit||'')+'</option>';
  }).join('');
}
function apiGet(p){return fetch(p).then(function(r){return r.json();});}
function apiPost(p,b){return fetch(p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b||{})}).then(function(r){return r.json();});}
function showDlToast(msg){var t=document.createElement('div');t.textContent=msg||'다운로드 요청 했습니다';t.style.cssText='position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1a5c3a;color:#69e29b;padding:10px 20px;border-radius:8px;font-size:14px;z-index:9999;opacity:1;transition:opacity 1.5s';document.body.appendChild(t);setTimeout(function(){t.style.opacity='0'},1500);setTimeout(function(){t.remove()},3000);}
function fmt(n){if(n==null||n<0)return '?';if(n<1048576)return (n/1024).toFixed(0)+' KB';if(n<1073741824)return (n/1048576).toFixed(1)+' MB';return (n/1073741824).toFixed(2)+' GB';}
function spd(bps){return bps>0?(bps/1024).toFixed(0)+' KB/s':'-';}
// ── Range 루프 다운로더 (T-1128) ─────────────────────────────────
// serveFile 은 응답당 32MB 로 자른다(MAX_SERVE_SLICE, A90 OOM 방지). 브라우저 다운로드
// 매니저는 이 206 을 완료로 저장해 **18GB 파일이 32MB 로 잘려 "완료"** 로 나온다(실측).
// → 서버 무변경, 브라우저가 직접 Range 로 이어받는다.
// 보안 컨텍스트(HTTPS/localhost)면 File System Access 로 디스크 연속 기록(메모리 무관,
// 18GB 가능). HTTP LAN 이면 showSaveFilePicker 가 없어 Blob 폴백 — 256MB 까지만 가능
// (그 이상은 브라우저가 파일 쓰기 저장소를 못 열어 대안 안내 — 무음 절단 금지).
var DL_MEM_MAX=268435456;
function dlProgressCard(name,onCancel){
  var c=document.createElement('div');
  var n=document.querySelectorAll('.dlbar').length;
  c.className='dlbar';
  c.style.cssText='position:fixed;bottom:'+(n*46)+'px;left:0;right:0;background:#12233a;color:#9ecbff;padding:10px 16px;font-size:13px;z-index:10001;display:flex;gap:12px;align-items:center;border-top:1px solid #24405f';
  var nm=document.createElement('span');
  nm.textContent=name||'';
  nm.style.cssText='flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
  var info=document.createElement('span');
  info.style.cssText='white-space:nowrap';
  var btn=document.createElement('button');
  btn.className='ghost sm';
  btn.textContent='취소';
  btn.onclick=onCancel;
  c.appendChild(nm);c.appendChild(info);c.appendChild(btn);
  document.body.appendChild(c);
  return {
    update:function(got,total){
      info.textContent=fmt(got)+(total>0?' / '+fmt(total)+' ('+Math.round(got/total*100)+'%)':'');
    },
    done:function(ok,msg){
      info.textContent=ok?'완료':(msg||'실패');
      setTimeout(function(){c.remove()},ok?2000:8000);
    }
  };
}
function dlRange(url,name){
  if(window.showSaveFilePicker&&window.isSecureContext){
    window.showSaveFilePicker({suggestedName:name||'download'})
      .then(function(h){return h.createWritable();})
      .then(function(w){dlLoop(url,name,w);})
      .catch(function(e){
        if(e&&e.name==='AbortError')return; // 사용자가 저장 위치 선택만 취소
        showDlToast('저장 위치 선택 실패: '+(e.message||e));
      });
    return;
  }
  dlLoop(url,name,null);
}
function dlLoop(url,name,writable){
  var ctrl=new AbortController();
  var mem=writable?null:[];
  function abortWritable(){if(writable)writable.abort().catch(function(){});}
  var bar=dlProgressCard(name,function(){
    ctrl.abort();
    abortWritable();
    bar.done(false,'취소됨 — 받은 부분은 버려집니다');
  });
  var offset=0,total=0,tries=0;
  function fail(msg){abortWritable();bar.done(false,msg);}
  function finish(){
    if(writable){
      writable.close().then(function(){bar.done(true);})
        .catch(function(e){fail('저장 실패: '+(e.message||e));});
      return;
    }
    var blob=new Blob(mem,{type:'application/octet-stream'});
    mem=null;
    var a=document.createElement('a');
    a.href=URL.createObjectURL(blob);
    a.download=name||'download';
    document.body.appendChild(a);a.click();a.remove();
    setTimeout(function(){URL.revokeObjectURL(a.href);},10000);
    bar.done(true);
  }
  function step(){
    fetch(url,{headers:{'Range':'bytes='+offset+'-'},signal:ctrl.signal})
      .then(function(r){
        if(!r.ok&&r.status!==206)throw new Error('HTTP '+r.status);
        var cr=r.headers.get('Content-Range');
        if(cr){var m=/\/(\d+)/.exec(cr);if(m)total=parseInt(m[1],10);}
        if(!writable&&total>DL_MEM_MAX){r.body.cancel().catch(function(){});throw new Error('TOO_BIG');}
        var reader=r.body.getReader();
        function pump(){
          return reader.read().then(function(res){
            if(res.done)return null;
            offset+=res.value.byteLength;
            if(!writable&&offset>DL_MEM_MAX)throw new Error('TOO_BIG');
            bar.update(offset,total);
            if(writable)return writable.write(res.value).then(pump);
            mem.push(res.value);return pump();
          });
        }
        return pump();
      })
      .then(function(){
        if(total>0&&offset<total){tries=0;return step();} // 다음 슬라이스 이어받기
        finish();
      })
      .catch(function(e){
        if(e&&e.name==='AbortError')return; // 취소는 카드가 이미 알림
        if(e&&e.message==='TOO_BIG'){
          fail('이 브라우저로는 '+fmt(total)+' 파일을 디스크에 저장할 수 없습니다 — 앱 설정에서 HTTPS 켜기 또는 맥 앱·Finder(DAV)로 받기');
          return;
        }
        if(++tries<=3){showDlToast('중단 — 3초 후 이어받기 재시도 ('+tries+'/3)');setTimeout(step,3000);return;}
        fail('다운로드 실패: '+(e.message||e)+' ('+fmt(offset)+'까지 받음)');
      });
  }
  step();
}
function label(st){return {QUEUED:'대기',RUNNING:'진행 중',PAUSED:'일시정지',STALLED:'정체',DONE:'완료',FAILED:'실패',CANCELED:'취소됨',ADDING:'추가 중',METADATA:'메타데이터',FETCHING_METADATA:'메타데이터 추출 중',DOWNLOADING:'진행 중',SEEDING:'시딩'}[st]||st;}
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
// switchTab/switchSettingsSection 정의는 하단 설정 섹션에 단일화 (중복 제거, T-936)
var __videoState=null;
function videoErr(msg){
  document.getElementById('videoArea').innerHTML='<div class="err">'+esc(msg||'오류')+'</div>';
}
function showVideoBlocked(){
  var _a=document.getElementById('videoArea');
  if(_a)_a.innerHTML='<div class="info" style="margin-top:6px;border:1px solid var(--warn);border-radius:8px;padding:8px 10px;background:rgba(255,170,0,.08)">💡 해당 사이트는 폰/앱(비브라우저) 접근을 차단하고 있습니다.</div>';
}
function isVideoBlocked(m){return (m||'').indexOf('E-AND-VID-0206')>=0;}
function videoExtra(){
  var r=document.getElementById('vref');var c=document.getElementById('vcookie');
  var o={};
  var rv=r?r.value.trim():'';var cv=c?c.value.trim():'';
  if(rv)o.referer=rv;if(cv)o.cookie=cv;
  return o;
}
function clearVideoExtra(){
  var r=document.getElementById('vref');if(r)r.value='';
  var c=document.getElementById('vcookie');if(c)c.value='';
}
function analyzeVideo(){
  var v=document.getElementById('vurl').value.trim();
  if(!v){videoErr('스트림/동영상 URL을 입력해 주세요');return;}
  var area=document.getElementById('videoArea');
  area.innerHTML='<div class="info">주소 확인 중… (동영상/스트림 스니핑)</div>';
  var body={url:v};var ex=videoExtra();if(ex.referer)body.referer=ex.referer;if(ex.cookie)body.cookie=ex.cookie;
  fetch('/api/video/analyze',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(function(r){
    if(!r.ok)return r.text().then(function(t){throw new Error(t);});
    return r.json();
  }).then(function(j){
    __videoState=j;
    var kindTxt=j.kind==='mp4'?'MP4 직접 동영상':j.kind==='direct'||j.direct?'직접 스트림 주소':'페이지에서 동영상/스트림 감지';
    var qs=j.qualities||[];
    var selOpts='';
    for(var i=0;i<qs.length;i++){selOpts+='<option value="'+i+'"'+(i===0?' selected':'')+'>'+esc(qs[i].label)+'</option>';}
    window.__videoQIdx=qs.length>1?0:-1;
    var h='<div class="vcard" style="margin-top:8px"><div class="name">'+esc(j.title||v)+'</div>'
      +'<div class="meta">'+kindTxt+'</div>'
      +'<div style="margin-top:8px;display:flex;flex-wrap:wrap;gap:6px;align-items:center">'
      +(qs.length>1?('<select id="vq" onchange="window.__videoQIdx=Number(this.value)" style="flex:0 0 auto">'+selOpts+'</select>'):'')
      +'<button class="ghost sm" onclick="copyVideoUrl()" style="flex:0 0 auto">📋 주소 복사</button>';
    var baseName=(j.direct?((j.streamUrl||v).split('/').pop().split('?')[0].split('#')[0].replace(/\.[^.]+$/,'')||'video'):((j.title||'video').replace(/[\\/:*?"<>|]/g,'_'))).slice(0,60)||'video';
    h+='<input id="vname" type="text" value="'+esc(baseName)+'.mp4" style="flex:1 1 160px;min-width:140px;box-sizing:border-box">'
      +'<button onclick="createVideo(\''+jsArg(v)+'\')" style="flex:0 0 auto">▶ 다운로드</button>'
      +'</div></div>';
    area.innerHTML=h;
    refresh();
  }).catch(function(e){
    var _m=e.message||e;
    if(isVideoBlocked(_m)){showVideoBlocked();return;}
    videoErr('분석 실패: '+_m);
  });
}
function copyVideoUrl(){
  var u=currentVideoUrl();
  if(!u){showDlToast('복사할 주소가 없습니다');return;}
  var ta=document.createElement('textarea');
  ta.value=u;ta.style.position='fixed';ta.style.opacity='0';
  document.body.appendChild(ta);ta.select();ta.setSelectionRange(0,u.length);
  var ok=false;
  try{ok=document.execCommand('copy');}catch(e){}
  ta.remove();
  if(ok){showDlToast('주소 복사됨');}
  else if(navigator.clipboard&&window.isSecureContext){
    navigator.clipboard.writeText(u).then(function(){showDlToast('주소 복사됨');}).catch(function(){prompt('복사가 안 되면 수동으로 복사하세요:',u);});
  }else{
    prompt('복사가 안 되면 수동으로 복사하세요:',u);
  }
}
function clearVideoUrlInput(){
  var u=document.getElementById('vurl');if(u)u.value='';
  window.__videoState=null;
  window.__videoQIdx=-1;
}
function setJobLimit(id,bps){
  apiPost('/api/jobs/'+id+'/limit',{maxDownBps:Number(bps)})
    .then(function(d){if(d.ok){showDlToast('작업 속도 제한 적용됨');refresh();}else alert('적용 실패');})
    .catch(function(e){alert('적용 실패: '+e);});
}
function currentVideoUrl(){
  var j=__videoState||{};if(!j)return'';
  var idx=window.__videoQIdx;
  var qs=j.qualities||[];
  if(qs.length>1&&idx>=0&&idx<qs.length)return qs[idx].url||j.streamUrl||'';
  return j.streamUrl||'';
}
function retryVideo(v){
  // FAILED video 잡 재시도 — 원본 url로 재분석+재다운로드 (VideoApi.create가 재분석 포함)
  var area=document.getElementById('videoArea');
  if(area)area.innerHTML='<div class="info">재다운로드 시작 중…</div>';
  fetch('/api/video/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:v,streamUrl:'',filename:''})}).then(function(r){
    if(!r.ok)return r.text().then(function(t){throw new Error(t);});
    return r.json();
  }).then(function(){
    if(area){area.innerHTML='<div class="info" style="color:var(--ok)">✓ 재다운로드 시작됨 — 목록에서 확인</div>';setTimeout(function(){area.innerHTML='';},3000);}
    clearVideoUrlInput();clearVideoExtra();
    refresh();
  }).catch(function(e){
    var _m=e.message||e;
    if(isVideoBlocked(_m)){showVideoBlocked();return;}
    if(area)videoErr('재시도 실패: '+_m);
  });
}
function createVideo(v){
  var j=__videoState||{};
  var nameEl=document.getElementById('vname');
  var body={url:v,streamUrl:currentVideoUrl(),filename:(nameEl?nameEl.value.trim():'')};
  var ex=videoExtra();if(ex.referer)body.referer=ex.referer;if(ex.cookie)body.cookie=ex.cookie;
  var area=document.getElementById('videoArea');
  area.innerHTML='<div class="info">다운로드 시작 중…</div>';
  fetch('/api/video/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(function(r){
    if(!r.ok)return r.text().then(function(t){throw new Error(t);});
    return r.json();
  }).then(function(){
    area.innerHTML='<div class="info" style="color:var(--ok)">✓ 다운로드 시작됨 — 위 목록에서 진행률 확인</div>';
    setTimeout(function(){ area.innerHTML=''; }, 3000);
    clearVideoUrlInput();clearVideoExtra();
    refresh();
  }).catch(function(e){
    var _m=e.message||e;
    if(isVideoBlocked(_m)){showVideoBlocked();return;}
    videoErr('다운로드 시작 실패: '+_m);
  });
}
function render(jobs){
  if(dragActive())return;
  var el=document.getElementById('list');document.getElementById('empty').style.display=jobs.length?'none':'block';
  var h='';
  var now=Date.now();
  jobs.forEach(function(j){
    var isVideo=j.type==='video';
    var pct;
    if(isVideo)pct=Math.round(j.progress*100);
    else pct=j.totalBytes>0?Math.round(j.progress*100):(j.state==='DONE'?100:0);
    pct=Math.min(100,Math.max(0,pct));
    var size=j.downloadedBytes?(fmt(j.downloadedBytes)+(j.totalBytes>0?' / '+fmt(j.totalBytes):'')):'';
    if(isVideo&&j.segmentsTotal>0)size=(size?size+' · ':'')+'세그먼트 '+(j.segmentsDone||0)+'/'+j.segmentsTotal+'개';
    var sp=j.state==='RUNNING'?'<span class="speed">'+spd(j.speedBps)+'</span>':'';
    var eta='';
    if(j.state==='RUNNING'&&(j.speedBps>0||(isVideo&&(j.segmentsDone>0||j.downloadedBytes>0)))){
      var parts=[];
      var elapsed=(j.startedAt>0)?(now-j.startedAt)/1000:0;
      if(j.startedAt>0)parts.push(fmtDuration(elapsed)+' 경과');
      if(isVideo&&j.totalDurationMs>0&&j.progress>0&&j.progress<1&&elapsed>0){
        var doneMs=j.progress*j.totalDurationMs;var rate=doneMs/elapsed;
        if(rate>0){var rem=(j.totalDurationMs-doneMs)/rate;parts.push(fmtDuration(rem)+' 남음');}
      }else if(isVideo&&j.segmentsTotal>0&&j.segmentsDone>0&&j.segmentsDone<j.segmentsTotal&&elapsed>0){
        var segRate=j.segmentsDone/elapsed;
        if(segRate>0)parts.push(fmtDuration((j.segmentsTotal-j.segmentsDone)/segRate)+' 남음');
      }else if(j.speedBps>0&&j.totalBytes>0&&j.downloadedBytes<j.totalBytes){
        var remain=(j.totalBytes-j.downloadedBytes)/j.speedBps;parts.push(fmtDuration(remain)+' 남음');
      }
      if(parts.length>0)eta='<span class="eta"> · '+parts.join(' · ')+'</span>';
    }
    var err=j.errorMessage?'<div class="err">'+esc(j.errorMessage)+'</div>':'';
    var vbadge='';
    if(j.type==='video')vbadge='<span class="badge video">🎬 비디오</span>';
    var act=j.state==='DONE'?'<a class="btn-dl" href="/file/'+j.id+'" download="'+esc(j.filename)+'" onclick="event.preventDefault();dlRange(this.href,this.download)">📥 받기</a>':'';
    var pause='';
    if(j.type!=='video'&&j.state==='RUNNING')pause='<button class="ghost" onclick="act(\''+j.id+'\',\'pause\')">일시정지</button>';
    if(j.type==='video'&&j.state==='RUNNING')pause='<span class="eta" style="align-self:center">FFmpeg → 삭제로 취소</span>';
    if(j.type!=='video'&&(j.state==='PAUSED'||j.state==='FAILED'))pause='<button class="ghost" onclick="act(\''+j.id+'\',\'resume\')">재개</button>';
    if(j.type==='video'&&j.state==='FAILED')pause='<button class="ghost" onclick="retryVideo(\''+jsArg(j.url)+'\')">재시도</button>';
    var cancel='<button class="ghost" onclick="delJobConfirm(\''+j.id+'\')">삭제</button>';
    var limit='';
    if(j.type!=='video'&&j.state!=='DONE'&&j.state!=='CANCELED'){
      var cur=j.maxDownBps||0;
      limit='<select class="ghost" onchange="setJobLimit(\''+j.id+'\',this.value)">'
        +presetOptionsHtml(cur)+'</select>';
    }
    h+='<div class="card" draggable="true" data-id="'+j.id+'">'
      +'<div style="flex:1;min-width:0;padding:14px">'
      +'<div class="name">'+esc(j.filename)+'</div>'
      +'<div class="meta"><span class="badges"><span class="badge '+j.state+'">'+label(j.state)+'</span>'+vbadge+'</span><span>'+size+'</span><span>'+pct+'%</span>'+sp+eta+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+err
      +'</div><div class="card-acts">'+act+pause+limit+cancel+'</div></div>';
  });
  // 틱 리렌더 중 사용자 입력 보호 (T-998) — select/체크박스 조작 중엔 목록 교체 skip
  if(!uiBusy(el)&&window.__lastJobsH!==h){window.__lastJobsH=h;el.innerHTML=h;}
  window.__jobs=jobs;
  updateInfoBar();
}
function updateInfoBar(){
  var jobs=window.__jobs||[];
  var torrents=window.__torrents||[];
  var running=jobs.filter(function(j){return j.state==='RUNNING'});
  var tActive=torrents.filter(function(t){return t.state==='DOWNLOADING'||t.state==='SEEDING'||t.state==='FETCHING_METADATA';});
  var totalSpeed=running.reduce(function(a,j){return a+(j.speedBps||0);},0);
  var tDown=tActive.reduce(function(a,t){return a+(t.downloadSpeed||0);},0);
  var tUp=tActive.reduce(function(a,t){return a+(t.uploadSpeed||0);},0);
  var left='<div class="info-left">'
    +'<span class="info-item">다운로드 <b>'+running.length+'</b>건</span>';
  if(running.length>0||tActive.length>0){
    left+='<span class="info-item speed">총 속도 <b>'+spd(totalSpeed+tDown)+'</b></span>';
    if(tUp>0)left+='<span class="info-item speed" style="color:#f96">▲ '+spd(tUp)+'</span>';
    if(tActive.length>0)left+='<span class="info-item">토렌트 <b>'+tActive.length+'</b>건</span>';
    document.title='DroidRelay : ▲'+spd(tUp)+' ▼'+spd(totalSpeed+tDown);
  }else{
    left+='<span class="info-item idle">대기중...</span>';
    document.title='DroidRelay';
  }
  left+='</div>';
  var info=window.__info;
  var storageTxt='?';
  if(info&&info.storageFree!=null)storageTxt=fmt(info.storageFree)+(info.storageTotal?' / '+fmt(info.storageTotal):'');
  var gs=window.__guardStatus;
  var right='<div class="info-right">'
    +'<span class="info-item">저장공간 여유 <b>'+storageTxt+'</b></span>';
  if(gs&&gs.guardEnabled){
    var cls=gs.throttled?'temp-warn':'temp-ok';
    right+='<span class="info-item '+cls+'">온도 <b>'+gs.thermal+'°C</b> · 배터리 <b>'+(gs.batteryLevel>=0?gs.batteryLevel+'%':'?')+'</b>'
      +(gs.throttled?' <span class="throttle-badge">⚠ 스로틀링</span>':'')+'</span>';
  }else{
    right+='<span class="info-item temp-off">가드 끔</span>';
  }
  if(info&&info.version)right+='<span class="info-item" style="color:var(--dim)">v'+info.version+'</span>';
  right+='</div>';
  // v0.41: 드롭다운이 닫혀 있어도 매 tick innerHTML 을 쓰면 유휴 DOM 쓰기가 된다.
  // 조립 결과가 이전과 같으면 건너뛴다.
  var html=left+right;
  if(window.__infoHtml!==html){
    window.__infoHtml=html;
    var el=document.getElementById('info');
    if(el)el.innerHTML=html;
  }
  updateInfoBadge(running.length,tActive.length,gs,spd(totalSpeed+tDown));
}
// ── 상태 배지 (v0.41) — 📊 버튼 우상단 도트 + 툴팁 요약 ──
function updateInfoBadge(running,tActive,gs,downSpd){
  var dot=document.getElementById('infoMenuDot');
  var btn=document.getElementById('btnInfoMenu');
  var busy=running>0||tActive>0;
  var throttled=!!(gs&&gs.guardEnabled&&gs.throttled);
  if(dot){
    dot.hidden=!busy&&!throttled;
    dot.classList.toggle('warn',throttled);
  }
  if(btn){
    var tip='서버 상태 · 통계 (S)';
    if(throttled)tip='⚠ 스로틀링 — 서버 상태 · 통계 (S)';
    else if(busy)tip='진행 '+running+' · 토렌트 '+tActive+' · '+downSpd+' — 서버 상태 · 통계 (S)';
    else tip='대기중 — 서버 상태 · 통계 (S)';
    btn.title=tip;
  }
  var at=document.getElementById('infoMenuAt');
  if(at&&window.__infoMenuOpen){
    var d=new Date();
    at.textContent=('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2);
  }
}
// ── 헤더 통합 메뉴 (v0.41) — 서버 설명 + 통계를 📊 드롭다운 1개로 ──
var __infoMenuOpen=false,__statsAt=0;
function openInfoMenu(){
  var m=document.getElementById('infoMenu');if(!m)return;
  m.hidden=false;window.__infoMenuOpen=true;
  var b=document.getElementById('btnInfoMenu');
  if(b){b.classList.add('on');b.setAttribute('aria-expanded','true');}
  updateInfoBar();
  refreshStats();
}
function closeInfoMenu(){
  var m=document.getElementById('infoMenu');if(!m||!window.__infoMenuOpen)return;
  m.hidden=true;window.__infoMenuOpen=false;
  var b=document.getElementById('btnInfoMenu');
  if(b){b.classList.remove('on');b.setAttribute('aria-expanded','false');}
  var at=document.getElementById('infoMenuAt');if(at)at.textContent='';
}
function toggleInfoMenu(){if(window.__infoMenuOpen)closeInfoMenu();else openInfoMenu();}
// 바깥 클릭 시 닫기 — 버튼/패널 내부는 contains 로 판정해 토글과 충돌하지 않는다
document.addEventListener('click',function(e){
  if(!window.__infoMenuOpen)return;
  var m=document.getElementById('infoMenu');
  if(m&&m.contains(e.target))return;
  var b=document.getElementById('btnInfoMenu');
  if(b&&b.contains(e.target))return;
  closeInfoMenu();
});
function renderTorrents(ts){
  if(dragActive())return;
  window.__torrents=ts;
  var el=document.getElementById('torrentList');document.getElementById('torrentEmpty').style.display=ts.length?'none':'block';
  var h='';
  var now=Date.now();
  ts.forEach(function(t){
    var pct=Math.min(100,Math.max(0,t.progress!=null?Math.round(t.progress*100):0));
    var size=t.totalSize>0?(fmt(t.downloadedSize)+' / '+fmt(t.totalSize)):(t.downloadedSize>0?fmt(t.downloadedSize):'');
    var sp=(t.state==='DOWNLOADING'||t.state==='SEEDING'||t.state==='FETCHING_METADATA')?'<span class="speed">'+spd(t.downloadSpeed||0)+'</span>':'';
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
    var badgeClass={DOWNLOADING:'RUNNING',SEEDING:'DONE',DONE:'DONE',PAUSED:'PAUSED',STALLED:'STALLED',ERROR:'FAILED',FAILED:'FAILED',FETCHING_METADATA:'QUEUED',METADATA:'QUEUED',ADDING:'QUEUED'}[st]||'QUEUED';
    var pause='';
    if(st==='PAUSED'||st==='FAILED'||st==='STALLED'||st==='QUEUED')pause='<button class="ghost" onclick="torrentAct(\''+t.id+'\',\'resume\')">재개</button>';
    var del='<button class="ghost" onclick="torrentDelConfirm(\''+t.id+'\')">삭제</button>';
    var tlimit='<select class="ghost" onchange="setTorrentLimit(\''+t.id+'\',this.value)" title="torrent 다운로드 제한">'
      +presetOptionsHtml(t.maxDownBps||0)+'</select>';
    h+='<div class="card" draggable="true" data-id="'+t.id+'">'
      +'<div style="flex:1;min-width:0;padding:14px">'
      +'<div class="name">'+esc(t.name||t.hash||'파일 불명')+'</div>'
      +'<div class="meta"><span class="badge '+badgeClass+'">'+label(st)+'</span><span>'+size+'</span><span>'+pct+'%</span>'+sp+up+eta+(seedInfo?' '+seedInfo:'')+'</div>'
      +'<div class="bar"><div class="fill" style="width:'+pct+'%"></div></div>'+filePath+err
      +'</div><div class="card-acts">'+tlimit+pause+del+'</div></div>';
  });
  // 틱 리렌더 중 select 조작 보호 (T-998)
  if(!uiBusy(el)&&window.__lastTorrentsH!==h){window.__lastTorrentsH=h;el.innerHTML=h;}
  var totalDown=ts.reduce(function(a,t){return a+(t.downloadSpeed||0);},0);
  var totalUp=ts.reduce(function(a,t){return a+(t.uploadSpeed||0);},0);
  var active=ts.filter(function(t){return t.state==='DOWNLOADING'||t.state==='SEEDING'||t.state==='FETCHING_METADATA';}).length;
  var info=document.getElementById('torrentInfo');
  if(info)info.innerHTML='<span>⚡ 다운 <b class="speed">'+spd(totalDown)+'</b></span>'
    +'<span style="margin-left:12px">▲ 업로드 <b style="color:#f96">'+spd(totalUp)+'</b></span>'
    +'<span style="margin-left:12px">· 진행 '+active+'건</span>'
    +'<span style="margin-left:12px">· 시드 <b style="color:#6a8">'+ts.reduce(function(a,t){return a+(t.seeds||0);},0)+'</b>'
    +' 피어 <b style="color:#f86">'+ts.reduce(function(a,t){return a+(t.peers||0);},0)+'</b></span>';
  updateInfoBar();
}
function isPlayable(name){return /\.(mp4|m4v|webm|mov|mkv|ogv|mp3|m4a|ogg|oga|wav|flac)$/i.test(name||'');}
function playMedia(key,name){
  var dlBase=location.protocol+'//'+location.hostname+':'+location.port;
  var url=dlBase+'/stream/'+encodeURIComponent(key);
  makeOverlay().innerHTML='<div class="popup wide"><h3>'+esc(name)+'</h3>'
    +'<video controls autoplay preload="metadata" src="'+url+'"></video>'
    +'<div class="pbtns"><button type="button" onclick="closePopup()">닫기</button></div></div>';
}
// 만료 공유 링크 발급 (T-951)
function shareFile(key){
  var o=makeOverlay();
  o.innerHTML='<div class="popup"><h3>공유 링크</h3>'
    +'<p class="msg">링크 유지 시간을 선택해 주세요 (만료 후 자동 무효)</p>'
    +'<select id="shareHours" style="width:100%;box-sizing:border-box;padding:9px 10px;border-radius:8px;border:1px solid var(--line2);background:var(--surface);color:var(--text);font-size:14px;margin:0 0 14px;outline:none">'
    +'<option value="1">1시간</option>'
    +'<option value="6">6시간</option>'
    +'<option value="24" selected>24시간 (1일)</option>'
    +'<option value="72">72시간 (3일)</option>'
    +'<option value="168">168시간 (7일)</option>'
    +'<option value="720">720시간 (30일)</option>'
    +'</select>'
    +'<div class="pbtns"><button type="button" onclick="closePopup()">취소</button>'
    +'<button type="button" class="ok" id="shareOk">발급</button></div></div>';
  document.getElementById('shareOk').onclick=function(){
    var h=parseInt(document.getElementById('shareHours').value,10);
    closePopup();
    apiPost('/api/share',{path:key,hours:h}).then(function(d){
      if(d&&d.url){
        var abs=location.protocol+'//'+location.hostname+':'+location.port+d.url;
        promptPopup('공유 링크 발급됨 ('+h+'시간 유효)','복사해서 공유하세요',abs,function(){});
      }else alert('발급 실패: '+((d&&d.error)||''));
    }).catch(function(){alert('발급 실패');});
  };
}
function isThumbable(name){return /\.(mp4|mkv|webm|mov|avi|m4v|ogv|ts)$/i.test(name||'');}
// 보관함 정렬 (클라이언트 사이드) — 이름순만 폴더/파일 혼합, 나머지는 폴더 위 분리
function sortStorageItems(items){
  var arr=items.slice();
  function byName(a,b){var x=a.name||'',y=b.name||'';return x<y?-1:(x>y?1:0);}
  if(storageSort==='name')return arr.sort(byName);
  var dirs=arr.filter(function(f){return f.type==='dir';});
  var files=arr.filter(function(f){return f.type!=='dir';});
  if(storageSort==='recent'){
    dirs.sort(function(a,b){return (b.modified||0)-(a.modified||0);});
    files.sort(function(a,b){return (b.modified||0)-(a.modified||0);});
  }else if(storageSort==='size'){
    dirs.sort(function(a,b){return (b.size||0)-(a.size||0);});
    files.sort(function(a,b){return (b.size||0)-(a.size||0);});
  }else{
    dirs.sort(byName);files.sort(byName);
  }
  return dirs.concat(files);
}
function setStorageSort(v){storageSort=v||'dir';refreshStorage();}
function renderStorage(items){  if(dragActive())return;
  items=sortStorageItems(items);
  var el=document.getElementById('fileList');document.getElementById('fileEmpty').style.display=items.length?'none':'block';
  document.getElementById('storageListTitle').textContent='📄 폴더 내용';
  var h='';
var dlBase=location.protocol+'//'+location.hostname+':'+location.port;
    items.forEach(function(f){
      var isDir=f.type==='dir';
      var key=curPath?curPath+'/'+f.name:f.name;
      var cls=(selItem&&selItem.name===f.name)?'file-row selected':'file-row';
      var dateStr=f.modified?new Date(f.modified).toLocaleDateString('ko-KR',{year:'numeric',month:'2-digit',day:'2-digit'}):'';
      h+='<div class="'+cls+'" draggable="true" data-type="'+f.type+'" data-key="'+esc(key)+'" data-name="'+esc(f.name)+'">'
        +(isThumbable(f.name)&&!isDir?'<img class="file-thumb" loading="lazy" src="'+dlBase+'/thumb/'+encodeURIComponent(key)+'" onerror="this.remove()" alt="">':'')
        +'<span class="file-icon">'+fileIcon(f.name,isDir)+'</span>'
        +'<div style="min-width:0;flex:1"><div class="name">'+esc(f.name)+'</div>'
        +'<div class="file-meta">'+(isDir
            ?(f.count+'개'+(dateStr?' · '+dateStr:''))
            :(fmt(f.size)+(dateStr?' · '+dateStr:'')))+'</div></div>'
        +'<div class="acts">';
      if(!isDir){
        if(isPlayable(f.name))h+='<button class="ghost sm" data-play="'+esc(key)+'" data-name="'+esc(f.name)+'" onclick="event.stopPropagation();playMedia(this.dataset.play,this.dataset.name)">▶</button>';
        h+='<a class="btn-dl" href="'+dlBase+'/dl-file/'+encodeURIComponent(key)+'" download="'+esc(f.name)+'" onclick="event.stopPropagation();event.preventDefault();dlRange(this.href,this.download)">📥</a>';
        h+='<button class="ghost sm" data-share="'+esc(key)+'" onclick="event.stopPropagation();shareFile(this.dataset.share)">🔗</button>';
      }else{
        h+='<a class="btn-dl" href="'+dlBase+'/dl-folder/'+encodeURIComponent(key)+'" download="'+esc(f.name)+'.zip" onclick="event.stopPropagation();showDlToast()">📦</a>';
      }
    h+='<button class="ghost sm" data-act="rename">✏️</button>';
    h+='<button class="ghost sm" data-act="del">🗑</button>';
    h+='</div></div>';
  });
  if(window.__lastStorageH!==h){window.__lastStorageH=h;el.innerHTML=h;}
  updateBreadcrumb();
  updateToolbar();
  if(typeof clearUploadProgress==='function')clearUploadProgress();
}
function updateBreadcrumb(){
  var parts=curPath?curPath.split('/'):[];
  // v0.41: onclick 인라인 핸들러를 쓰면 안 된다. 경로를 JSON.stringify 로 큰따옴표 속성에
  // 끼워 넣는 순간 HTML 파서가 `onclick="openDir("` 까지 잘라 버려
  // SyntaxError: Unexpected token '}' 가 나고, 남은 조각이 새 액자처럼 붙어 보인다.
  // data-path 만 심고 아래 위임 리스너가 처리한다 (따옴표 포함 경로도 안전).
  var h='<span data-path="">📱 보관함</span>';
  var p='';
  parts.forEach(function(part){
    if(!part)return;
    p+=(p?'/':'')+part;
    h+=' / <span data-path="'+esc(p)+'">'+esc(part)+'</span>';
  });
  document.getElementById('breadcrumb').innerHTML=h;
}
(function(){
  var bc=document.getElementById('breadcrumb');
  if(!bc||bc.__delegated)return;bc.__delegated=true;
  var suppressClickUntil=0;
  function clearMarks(){document.querySelectorAll('#breadcrumb .drop-target').forEach(function(s){s.classList.remove('drop-target')});}
  // 상위 폴더 이동 — v0.41: 인라인 onclick 대신 위임 (따옴표 포함 경로까지 안전)
  bc.addEventListener('click',function(e){
    if(Date.now()<suppressClickUntil)return;
    var span=e.target.closest('[data-path]');
    if(!span||!bc.contains(span))return;
    openDir(span.dataset.path||'');
  });
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
    suppressClickUntil=Date.now()+400;   // 드롭 직후 잔여 click 이 위임 이동을 중복 실행하지 않게
    var src=window.__dragKey;
    var span=e.target.closest('[data-path]');
    clearMarks();
    if(!src||!span)return;
    var targetPath=span.dataset.path;
    var srcDir=src.indexOf('/')>=0?src.slice(0,src.lastIndexOf('/')):'';
    if(srcDir===targetPath)return;
    var name=src.slice(src.lastIndexOf('/')+1);
    if(targetPath===src||targetPath.indexOf(src+'/')===0)return;
    // v0.41: 이름 충돌 확인은 storageMove 가 담당 (직접 fetch 하면 가드를 우회한다)
    storageMove(src,targetPath,function(){openDir(targetPath)});
  });
})();
// ── 보관함 이동 (v0.41, T-1072) ──
// 서버는 renameTo(2) 로 이동하므로 대상이 있으면 조용히 덮어쓴다. overwrite 가 없으면
// conflict 로 보고하고, 여기서 사용자에게 확인받은 뒤 overwrite=true 로 재요청한다.
// 브레드크럼 드롭 · 목록 드롭 두 곳이 이 함수를 공유한다 (가드 우회 경로 차단).
function storageMove(from,to,onDone){
  function post(overwrite){
    return fetch('/api/storage/move',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({from:from,to:to,overwrite:!!overwrite})})
      .then(function(res){return res.json()})
      .then(function(d){
        if(d&&d.conflict){
          var what=d.isDir?'폴더':'파일';
          // fmt() 는 1KB 미만을 "0 KB" 로 만들므로 이 자리에선 바이트를 보여준다
          var sizeTxt=d.isDir?'':' · '+(d.size<1024?d.size+' B':fmt(d.size));
          confirmPopup('같은 이름이 이미 있습니다',
            '목적지에 「'+d.name+'」'+sizeTxt+'\n('+what+' · '+new Date(d.modified||Date.now()).toLocaleString('ko-KR')+' 수정)\n\n'+
            '덮어쓰면 이 '+what+'은 사라지고 되돌릴 수 없습니다.',
            function(){post(true);},true,'덮어쓰기');
          return;   // 확인 전에는 목록을 다시 그리지 않는다 (아직 아무것도 안 바뀌었음)
        }
        if(d&&d.error){alert(d.error)}
        else{showDlToast('이동했습니다 → '+(to||'📱 보관함'))}
        if(onDone)onDone();
      })
      .catch(function(err){alert(err)});
  }
  post(false);
}
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
    // v0.41: 이름 충돌 확인은 storageMove 가 담당 (직접 fetch 하면 가드를 우회한다)
    storageMove(src,k,refreshStorage);
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
  document.getElementById('storageListTitle').textContent='🗑️ 휴지통';
  document.getElementById('trashEmptyBtn').style.display=items.length?'inline-block':'none';
  document.getElementById('breadcrumb').innerHTML='<span>🗑️ 휴지통</span><span style="color:var(--dim)"> · 삭제된 항목은 복구할 수 있습니다</span>';
  var h='';
  items.forEach(function(f){
    var isDir=f.type==='dir';
    var dateStr=f.modified?new Date(f.modified).toLocaleDateString('ko-KR',{year:'numeric',month:'2-digit',day:'2-digit'}):'';
    h+='<div class="file-row" data-name="'+esc(f.name)+'">'
      +'<span class="file-icon">'+(isDir?'📁':'🗑️')+'</span>'
      +'<div style="min-width:0;flex:1"><div class="name">'+esc(f.name)+'</div>'
      +'<div class="file-meta">'+(isDir?'폴더':fmt(f.size))+(dateStr?' · '+dateStr:'')+'</div></div>'
      +'<div class="acts">'
      +'<button class="ghost sm" style="width:auto;padding:5px 10px" onclick="restoreItem(this.closest(\'.file-row\').dataset.name)">♻️ 복구</button>'
      +'<button class="ghost sm" style="width:auto;padding:5px 10px;color:var(--err)" onclick="purgeItem(this.closest(\'.file-row\').dataset.name)">🔥 영구삭제</button>'
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
var __popupOk=null;
function makeOverlay(){var o=document.createElement('div');o.className='overlay';o.id='popupOverlay';o.onclick=function(e){if(e.target===o)closePopup();};document.body.appendChild(o);return o;}
function closePopup(){var o=document.getElementById('popupOverlay');if(o)o.remove();__popupOk=null;return false;}
document.addEventListener('keydown',function(e){
  if(!document.getElementById('popupOverlay'))return;
  if(e.key==='Enter'&&__popupOk){e.preventDefault();e.stopPropagation();var f=__popupOk;__popupOk=null;f();}
  else if(e.key==='Escape')closePopup();
});
// v0.41 (T-1072): okLabel — 파괴적 동작의 확인 버튼은 "확인" 이 아니라 행동을 말해야 한다
function confirmPopup(title,msg,onOk,danger,okLabel){
  makeOverlay().innerHTML='<div class="popup"><h3>'+esc(title)+'</h3><p class="msg">'+esc(msg)+'</p><div class="pbtns">'
    +'<button type="button" onclick="closePopup()">취소</button>'
    +'<button type="button" class="'+(danger?'danger':'ok')+'" id="popupOk" autofocus>'+esc(okLabel||'확인')+'</button></div></div>';
  document.getElementById('popupOk').onclick=function(){closePopup();if(onOk)onOk();};
  __popupOk=function(){closePopup();if(onOk)onOk();};
  return false;
}
function promptPopup(title,placeholder,initial,onOk){
  makeOverlay().innerHTML='<div class="popup"><h3>'+esc(title)+'</h3>'
    +'<input id="popupInput" type="text" placeholder="'+esc(placeholder||'')+'" value="'+esc(initial||'')+'" maxlength="80">'
    +'<div class="pbtns"><button type="button" onclick="closePopup()">취소</button>'
    +'<button type="button" class="ok" id="popupOk">확인</button></div></div>';
  function go(){var v=document.getElementById('popupInput').value.trim();if(!v){return;}closePopup();onOk(v);}
  document.getElementById('popupOk').onclick=go;
  document.getElementById('popupInput').onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();e.stopPropagation();go();}if(e.key==='Escape')closePopup();};
  __popupOk=go;
  setTimeout(function(){var i=document.getElementById('popupInput');if(i){i.focus();i.select();}},10);
  return false;
}
function purgeItem(name){
  confirmPopup('영구 삭제','「'+esc(name)+'」을(를) 영구 삭제합니다.\n복구할 수 없습니다.',function(){
    fetch('/api/storage/trash/purge',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({name:name})})
    .then(function(r){return r.json()}).then(function(d){
      if(d.error){alert(d.error)}else{showDlToast('영구삭제됨: '+name)}
      refreshStorage()}).catch(function(e){alert(e)});
  },true);
}
function emptyTrash(){
  confirmPopup('휴지통 비우기','휴지통 전체를 비웁니다.\n모든 항목이 영구 삭제되며 복구할 수 없습니다.',function(){
    fetch('/api/storage/trash/purge',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})
    .then(function(r){return r.json()}).then(function(d){
      if(d.error){alert(d.error)}else{showDlToast('휴지통을 비웠습니다')}
      refreshStorage()}).catch(function(e){alert(e)});
  },true);
}
function createFolder(){
  promptPopup('새 폴더','폴더 이름','',function(name){
    fetch('/api/storage/mkdir',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({path:curPath,name:name})})
    .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);refreshStorage()}).catch(function(e){alert(e)});
  });
}
function renameItem(f){
  promptPopup('이름 변경','새 이름',f.name,function(name){
    if(!name||name===f.name)return;
    var full=curPath?curPath+'/'+f.name:f.name;
    fetch('/api/storage/rename',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({from:full,to:name})})
    .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);refreshStorage()}).catch(function(e){alert(e)});
  });
}
function delItem(f){
  confirmPopup('휴지통으로 이동','「'+esc(f.name)+'」을(를) 휴지통으로 이동할까요?',function(){
    var full=curPath?curPath+'/'+f.name:f.name;
    fetch('/api/storage/delete',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({path:full})})
    .then(function(r){return r.json()}).then(function(d){if(d.error)alert(d.error);selItem=null;refreshStorage()}).catch(function(e){alert(e)});
  });
}
function deleteSelected(){if(selItem)delItem(selItem);}
function delJobConfirm(id){confirmPopup('항목 삭제','이 다운로드 항목을 삭제하시겠습니까?',function(){delJob(id);});}
function uploadFiles(input){
  var files=input.files;if(!files.length)return;
  var label=document.getElementById('uploadProgressLabel');
  var arr=Array.from(files);
  var done=0, ok=0, fail=0;
  window.__uploadBusy=true;
  function show(t){if(label)label.textContent=t;}
  function next(){
    if(done>=arr.length){
      window.__uploadBusy=false;
      setTimeout(function(){
        show(ok>0&&fail===0?('업로드 완료 '+ok+'건'):(fail>0?('완료 '+ok+'건 · 실패 '+fail+'건'):''));
        if(ok>0)refreshStorage();
      },300);
      input.value='';
      return;
    }
    var file=arr[done];done++;
    show('업로드 중: '+file.name+' (0%)');
    var xhr=new XMLHttpRequest();
    xhr.open('POST','/api/storage/raw-upload',true);
    xhr.setRequestHeader('Content-Type','application/octet-stream');
    xhr.setRequestHeader('X-File-Name',encodeURIComponent(file.name));
    xhr.setRequestHeader('X-File-Path',encodeURIComponent(curPath||''));
    xhr.upload.onprogress=function(e){
      if(e.lengthComputable){
        var pct=Math.round(e.loaded/e.total*100);
        show('업로드 중: '+file.name+' ('+pct+'%)');
      }
    };
    xhr.onload=function(){
      if(xhr.status>=200&&xhr.status<300){ok++;show('업로드 완료: '+file.name);}
      else{fail++;show('업로드 실패: '+file.name);}
      next();
    };
    xhr.onerror=function(){fail++;show('업로드 실패: '+file.name);next();};
    xhr.send(file);
  }
  next();
}
function clearUploadProgress(){if(window.__uploadBusy)return;var l=document.getElementById('uploadProgressLabel');if(l)l.textContent='';}
function refresh(){
  fetch('/api/info').then(function(r){return r.json()}).then(function(i){window.__info=i}).catch(function(){});
  fetch('/api/jobs').then(function(r){return r.json()}).then(render).catch(function(){});
  fetch('/api/torrents').then(function(r){return r.json()}).then(renderTorrents).catch(function(){});
  window.__wantsRenderInfo=true;
  fetch('/api/guard/status').then(function(r){return r.json()}).then(function(g){
    window.__guardStatus=g;
    if(window.__wantsRenderInfo){window.__wantsRenderInfo=false;updateInfoBar();}
  }).catch(function(){window.__guardStatus=null;window.__wantsRenderInfo=false;updateInfoBar();});
  if(curTab==='storage')refreshStorage();
  // v0.41: 통계는 드롭다운을 열어둔 동안에만 갱신 (닫힘 = 통계 API 0건).
  // SSE 는 변화 있을 때만 tick + 15초 beat 이므로 여기도 15초로 스로틀해 배수를 막는다.
  if(window.__infoMenuOpen&&Date.now()-__statsAt>15000)refreshStats();
}
function add(){
  var raw=document.getElementById('url').value.trim();if(!raw)return;
  var urls=raw.split(/[\s,]+/).filter(function(u){return /^https?:\/\//i.test(u);});
  if(!urls.length){alert('유효한 http(s) URL이 없습니다');return;}
  var ok=0,fail=0,done=0;
  urls.forEach(function(u){
    var body={url:u};
    fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){if(r.ok)ok++;else fail++;})
    .catch(function(){fail++;})
    .then(function(){
      if(++done===urls.length){
        document.getElementById('url').value='';
        showDlToast(ok+'건 추가'+(fail>0?' · 실패 '+fail+'건':''));
        refresh();
      }
    });
  });
}
function addMagnet(){var u=document.getElementById('magnet').value.trim();if(!u)return;
  fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({magnet:u})})
  .then(function(r){
    return r.text().then(function(t){
      if(!r.ok){
        try{var j=JSON.parse(t);throw (j&&j.error)?j.error:('HTTP '+r.status);}catch(e){throw (e&&e.message)?e.message:e;}
      }
      return t;
    });
  })
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
function torrentAct(id,a){fetch('/api/torrents/'+id+'/'+a,{method:'POST'}).then(function(r){
  if(r.ok){refresh();return;}
  r.text().then(function(t){var m=t;try{m=JSON.parse(t).error||t;}catch(e){}alert(m);refresh();});
});}
// torrent 개별 다운로드 제한 (T-1050) — 응답은 text("ok")라 json 파싱 회피
function setTorrentLimit(id,bps){
  fetch('/api/torrents/'+id+'/limit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({maxDownBps:Number(bps)||0})})
    .then(function(){showDlToast('torrent 속도 제한 적용됨');refresh();})
    .catch(function(e){alert('적용 실패: '+e);});
}
function searchTorrents(){
  var q=document.getElementById('tsearch').value.trim();if(!q)return;
  document.getElementById('searchResults').innerHTML='<div style="padding:8px 16px;color:var(--muted)">검색 중...</div>';
  fetch('/api/search?q='+encodeURIComponent(q)).then(function(r){return r.json();}).then(function(d){
    var el=document.getElementById('searchResults');
    if(d&&d.error){el.innerHTML='<div style="padding:8px 16px;color:var(--err)">'+esc(d.error)+'</div>';return;}
    if(!d||!d.length){el.innerHTML='<div style="padding:8px 16px;color:var(--muted)">결과 없음</div>';return;}
    var h='';
    d.forEach(function(r,i){
      h+='<div class="card" style="margin:8px 16px"><div style="min-width:0;flex:1"><div class="name">'+esc(r.title)+'</div>'
        +'<div class="file-meta">'+fmt(r.size||0)+' · 시드 '+(r.seeders||0)+' · '+(r.indexer||'')+'</div></div>'
        +'<div class="card-acts"><button class="btn-dl" onclick="addSearchResult('+i+')">받기</button></div></div>';
    });
    el.innerHTML=h;
    window.__searchResults=d;
  }).catch(function(e){document.getElementById('searchResults').innerHTML='<div style="padding:8px 16px;color:var(--err)">검색 실패</div>';});
}
function addSearchResult(i){
  var r=(window.__searchResults||[])[i];if(!r)return;
  var body=r.magnet?{magnet:r.magnet}:{torrentUrl:r.url};
  fetch('/api/torrents/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
  .then(function(res){if(!res.ok)return res.text().then(function(t){throw t});return res.json()})
  .then(function(){showDlToast('토렌트 추가됨');refresh()})
  .catch(function(e){alert('추가 실패: '+e)});
}
function torrentDel(id){fetch('/api/torrents/'+id,{method:'DELETE'}).then(refresh);}
function torrentDelConfirm(id){
  var t=null;
  (window.__torrents||[]).forEach(function(x){if(x.id===id)t=x;});
  var name=t?(t.name||'이름 없음'):'토렌트';
  var incomplete=t&&(t.state==='DOWNLOADING'||t.state==='FETCHING_METADATA'||t.state==='PAUSED'||t.state==='FAILED');
  var msg='「'+name+'」 torrent를 목록에서 삭제할까요?'+(incomplete?'\n다운로드 중이던 파일도 함께 삭제됩니다.':'');
  confirmPopup('토렌트 삭제',msg,function(){torrentDel(id);},true);
}
(function(){
  ['list','torrentList'].forEach(function(cid){
    var el=document.getElementById(cid);
    if(!el||el.__reorderBound)return;el.__reorderBound=true;
    var api=cid==='list'?'/api/jobs/reorder':'/api/torrents/reorder';
    function cards(){return [].slice.call(el.querySelectorAll('.card'));}
    function clear(){cards().forEach(function(c){c.classList.remove('dragging','drop-before','drop-after')});el.classList.remove('drop-empty');}
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
      // 카드가 없는 빈 공간 위면 순서변경이 불가능 — 목록 테두리로 그 사실을 알린다
      el.classList.toggle('drop-empty',!over||over===window.__dragSrc);
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
// 서버가 변경이 있을 때만 tick 을 보내므로 기본 폴링은 10초 백업으로 완화.
// 탭이 백그라운드로 가면 EventSource 는 브라우저가 throttle 하지 않으므로
// visibilitychange 에서 연결·타이머를 모두 정리해 서버 측 부하를 0 으로 만든다.
var __pollTimer=null;
function setPoll(ms){
  if(__pollTimer)clearInterval(__pollTimer);
  __pollTimer=ms?setInterval(refresh,ms):null;
}
var __es=null,__rcTimer=null,__rcCount=0;
function closeStream(){
  if(__es){try{__es.close();}catch(e){}__es=null;}
  if(__rcTimer){clearTimeout(__rcTimer);__rcTimer=null;}
  setPoll(0);
}
(function(){
  if(!window.EventSource){if(!document.hidden)setPoll(1000);return;}
  function connect(){
    if(document.hidden||__es)return;
    try{__es=new EventSource('/api/events');}catch(e){scheduleReconnect();return;}
    __es.onopen=function(){__rcCount=0;setPoll(10000);};   // SSE 연결 시 백업 폴링 완화
    __es.onmessage=function(){refresh();};
    __es.onerror=function(){
      if(__es){try{__es.close();}catch(e){}__es=null;}
      if(document.hidden)return;
      setPoll(1000);                          // 끊김 → 1초 폴링
      scheduleReconnect();
    };
  }
  // 지수 백오프 + 지터. 고정 5초 재시도였을 때 서버 재시작 순간 전 탭이 동시에 12회/분 시도했다.
  // 이전 시도 타이머를 반드시 지우고 기존 es 를 닫아 고아 연결(서버 측 SSE 코루틴 누수)을 막는다.
  function scheduleReconnect(){
    if(__rcTimer)clearTimeout(__rcTimer);
    var wait=Math.min(60000,1000*Math.pow(2,__rcCount))+Math.random()*500;
    __rcCount=Math.min(__rcCount+1,6);
    __rcTimer=setTimeout(function(){__rcTimer=null;connect();},wait);
  }
  document.addEventListener('visibilitychange',function(){
    if(document.hidden){closeStream();}
    else{refresh();connect();}
  });
  window.addEventListener('pagehide',closeStream);
  if(!document.hidden)connect();
})();

// ── 헤더: 수동 새로고침 + 테마 (v0.27) ──
// 틱 리렌더 중 입력 보호 헬퍼 (T-998) — el 안에서 select/체크박스 조작 중이면 true
function uiBusy(el){
  if(!el)return false;
  var ae=document.activeElement;
  return !!(ae&&(ae.tagName==='SELECT'||(ae.tagName==='INPUT'&&ae.type==='checkbox'))&&el.contains(ae));
}
function manualRefresh(){
  var b=document.getElementById('btnRefresh');
  if(b){b.classList.remove('spin');void b.offsetWidth;b.classList.add('spin');
    setTimeout(function(){b.classList.remove('spin');},850);}
  refresh();
}
document.addEventListener('keydown',function(e){
  if(document.getElementById('popupOverlay'))return;
  // v0.41: Esc 은 입력 요소에 커서가 있어도 통합 메뉴를 닫아야 한다 (가드보다 먼저)
  if(e.key==='Escape'){closeInfoMenu();return;}
  var t=document.activeElement;
  if(t&&(t.tagName==='INPUT'||t.tagName==='SELECT'||t.tagName==='TEXTAREA'))return;
  if(e.key==='r'||e.key==='R'){manualRefresh();}
  if(e.key==='s'||e.key==='S'){toggleInfoMenu();}
});
function setTheme(t){
  try{localStorage.setItem('dr_theme',t);}catch(e){}
  applyTheme();
}

// ── 접이식 섹션 (T-1003, 기본 접힘 + localStorage 기억 — 비디오 분석은 예외로 항상 접힘) ──
function setColl(bodyId,headId,open){
  var b=document.getElementById(bodyId);var h=document.getElementById(headId);
  if(b)b.style.display=open?'block':'none';
  if(h)h.classList.toggle('open',open);
}
function toggleColl(bodyId,headId,key){
  var b=document.getElementById(bodyId);
  var open=!b||b.style.display==='none';
  if(key){try{localStorage.setItem('dr_coll_'+key,open?'1':'0');}catch(e){}}
  setColl(bodyId,headId,open);
}
function applyColl(bodyId,headId,key){
  var open=false;
  try{open=localStorage.getItem('dr_coll_'+key)==='1';}catch(e){}
  setColl(bodyId,headId,open);
}
applyColl('tsearchBody','tsearchHead','tsearch');
// 비디오 분석: 저장값과 무관하게 항상 접힘 시작 (요청 — 열림 상태는 저장하지 않음)
setColl('videoBody','videoHead',false);
function refreshSearchVisibility(){
  fetch('/api/settings/search').then(function(r){return r.json();}).then(function(s){
    var on=s.searchEnabled===true;
    var sec=document.getElementById('tsearchSection');
    if(sec)sec.style.display=on?'block':'none';
  }).catch(function(){});
}
refreshSearchVisibility();
function applyTheme(){
  var t='toxic';
  try{t=localStorage.getItem('dr_theme')||'toxic';}catch(e){}
  if(t!=='toxic'&&t!=='midnight'&&t!=='navy')t='toxic';
  document.body.dataset.theme=t;
  var s=document.getElementById('themeSel');
  if(s)s.value=t;
}
applyTheme();

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
      // 오류 사유 (필드 없으면 표시 없음)
      if(d.errorMessage)h+='<div class="err">'+esc(d.errorMessage)+'</div>';
      // 시드/피어 스웜 정보
      h+='<div class="modal-section"><div class="modal-section-title">스웜 정보</div>';
      h+='<div class="modal-stat">';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">스웜 시드</div><div class="modal-stat-value green">'+(d.numComplete||d.listSeeds||d.seeds||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">스웜 릭처</div><div class="modal-stat-value red">'+(d.numIncomplete||d.listPeers||d.peers||0)+'</div></div>';
      h+='<div class="modal-stat-item"><div class="modal-stat-label">연결 피어</div><div class="modal-stat-value">'+(d.connectedPeersTotal!=null?d.connectedPeersTotal:(d.connectedPeers||[]).length)+'</div></div>';
      h+='</div></div>';
      // 트래커
      if(d.currentTracker){
        h+='<div class="modal-section"><div class="modal-section-title">트래커</div>';
        h+='<div style="font-size:12px;color:var(--dim);word-break:break-all">'+esc(d.currentTracker)+'</div></div>';
      }
      // 연결된 피어 목록
      var peers=d.connectedPeers||[];
      if(peers.length>0){
        h+='<div class="modal-section"><div class="modal-section-title">연결된 피어 ('+peers.length+')</div>';
        h+='<div class="modal-peer-list">';
        peers.forEach(function(p){
          var ppct=Math.round((p.progress!=null?p.progress:0)*100);
          ppct=Math.min(100,Math.max(0,ppct));
          var isSeed=ppct>=100;
          var seedStyle=isSeed?' style="color:var(--ok);font-weight:600"':'';
          h+='<div class="modal-peer-row">';
          h+='<span class="modal-peer-ip">'+esc(p.ip||'?')+'</span>';
          h+='<span class="modal-peer-client">'+esc(p.client||'')+'</span>';
          h+='<span class="modal-peer-speed"'+seedStyle+'>'+ppct+'%</span>';
          h+='<span class="modal-peer-speed">▼'+spd(p.downSpeed||0)+'</span>';
          h+='<span class="modal-peer-speed" style="color:#f96">▲'+spd(p.upSpeed||0)+'</span>';
          h+='</div>';
        });
        h+='</div></div>';
      }
      // 파일 목록 (체크박스 선택 → 즉시 적용, T-942)
      var files=d.files||[];
      if(files.length>0){
        h+='<div class="modal-section"><div class="modal-section-title">파일 ('+files.length+')</div>';
        files.forEach(function(f){
          var fpct=Math.round((f.progress||0)*100);
          h+='<div class="modal-file-row">';
          h+='<input type="checkbox" data-fidx="'+f.index+'"'+(f.selected===false?'':' checked')+' onchange="saveTorrentFiles(\''+id+'\')" style="flex:none">';
          h+='<span class="modal-file-path">'+esc(f.path)+'</span>';
          h+='<span class="modal-file-size">'+fmt(f.size||0)+'</span>';
          h+='<span class="modal-stat-value blue" style="min-width:35px;text-align:right">'+fpct+'%</span>';
          h+='</div>';
        });
        h+='</div>';
      }
      // 체크박스 조작 중엔 모달 교체 skip (T-998) — 조작 끝나면 다음 틱에 반영
      var tm=document.getElementById('tmBody');
      if(!uiBusy(tm))tm.innerHTML=h;
    })
    .catch(function(e){document.getElementById('tmBody').innerHTML='<div style="color:#f86;padding:20px">로딩 실패: '+esc(e.message)+'</div>';});
}

// 토렌트 파일 선택 즉시 적용 (T-942)
function saveTorrentFiles(id){
  var boxes=document.querySelectorAll('#tmBody input[type=checkbox][data-fidx]');
  var sel=[];
  boxes.forEach(function(b){if(b.checked)sel.push(parseInt(b.getAttribute('data-fidx'),10));});
  fetch('/api/torrents/'+id+'/files',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({selected:sel})})
  .then(function(r){if(!r.ok)showDlToast('파일 선택 실패');});
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
  closeInfoMenu();   // v0.41: 탭 전환 시 통합 메뉴 닫기
  if(trashMode&&t!=='storage')toggleTrash();
  document.querySelectorAll('.tab').forEach(function(el,i){
    el.classList.toggle('active',(['dl','torrent','storage','settings'])[i]===t);
  });
  document.getElementById('panel-dl').classList.toggle('active',t==='dl');
  document.getElementById('panel-torrent').classList.toggle('active',t==='torrent');
  document.getElementById('panel-storage').classList.toggle('active',t==='storage');
  document.getElementById('panel-settings').classList.toggle('active',t==='settings');
  if(t==='storage')refreshStorage();
  if(t==='torrent')refreshSearchVisibility();
  if(t==='settings'){loadSettings();loadSearchSettings();loadTrackerCount();}
}

// ── 설정 사이드바 전환 ──
function switchSettingsSection(s){
  document.querySelectorAll('.settings-nav-item').forEach(function(el,i){
    el.classList.toggle('active',(['global','download','torrent','rss','debrid','tunnel','guard','mcp','schedule','storage','debug','reset'])[i]===s);
  });
  document.querySelectorAll('.settings-content').forEach(function(el){
    el.classList.remove('active');
  });
  var target=document.getElementById('settings-'+s);
  if(target)target.classList.add('active');
}

// ── 설정 로드/저장 ──
function loadSettings(){
  Promise.all([
    fetch('/api/settings/speed-limit').then(function(r){return r.json();}),
    fetch('/api/settings/download').then(function(r){return r.json();}),
    fetch('/api/settings/torrent').then(function(r){return r.json();}),
    fetch('/api/settings/debrid').then(function(r){return r.json();}),
    fetch('/api/settings/tunnel').then(function(r){return r.json();}),
    fetch('/api/settings/guard').then(function(r){return r.json();}),
    fetch('/api/settings/mcp').then(function(r){return r.json();}),
    fetch('/api/settings/schedule').then(function(r){return r.json();}),
    fetch('/api/settings/server').then(function(r){return r.json();})
  ]).then(function(res){
    var sl=res[0], dl=res[1], tr=res[2], db=res[3], tn=res[4], gd=res[5], mc=res[6], sch=res[7], sv=res[8];
    // 전역 속도 제한
    document.getElementById('dlSpeedEnabled').checked=sl.maxDownloadBps>0;
    document.getElementById('maxDownloadMbps').disabled=sl.maxDownloadBps<=0;
    fillSpeedSelect(document.getElementById('maxDownloadMbps'), sl.maxDownloadBps>0?Math.ceil(sl.maxDownloadBps/1024):3072, 1024);
    document.getElementById('maxDownloadLabel').textContent=kbpsName(sl.maxDownloadBps>0?Math.ceil(sl.maxDownloadBps/1024):3072);
    document.getElementById('ulSpeedEnabled').checked=sl.maxUploadBps>0;
    document.getElementById('maxUploadMbps').disabled=sl.maxUploadBps<=0;
    document.getElementById('maxUploadMbps').value=sl.maxUploadBps!=null?Math.round(sl.maxUploadBps/1048576):3;
    document.getElementById('maxUploadLabel').textContent=(sl.maxUploadBps!=null?Math.round(sl.maxUploadBps/1048576):3)+' Mbps';
    updateSpeedLimitStatus(sl);
    // 다운로드 설정
    document.getElementById('concurrency').value=dl.concurrency!=null?dl.concurrency:2;
    document.getElementById('concurrencyLabel').textContent=dl.concurrency!=null?dl.concurrency:2;
    fillSpeedSelect(document.getElementById('speedLimitKbps'), dl.speedLimitKbps!=null?dl.speedLimitKbps:0, 1024);
    document.getElementById('speedLimitLabel').textContent=kbpsName(dl.speedLimitKbps||0);
    document.getElementById('notifications').checked=dl.notifications!==false;
    document.getElementById('storageQuotaGb').value=dl.storageQuotaGb!=null?dl.storageQuotaGb:0;
    document.getElementById('storageQuotaLabel').textContent=dl.storageQuotaGb>0?dl.storageQuotaGb+'GB':'끔';
    document.getElementById('autoClassify').checked=dl.autoClassify===true;
    document.getElementById('lowPowerMode').checked=dl.lowPowerMode===true;
    document.getElementById('completionAction').value=dl.completionAction||'stop_server';
    document.getElementById('idleTimeoutMin').value=dl.idleTimeoutMin!=null?dl.idleTimeoutMin:30;
    loadSpeedSchedule();
    // 토렌트 설정
    document.getElementById('torrentUploadLimit').value=tr.torrentUploadLimit!=null?tr.torrentUploadLimit:512;
    document.getElementById('torrentUploadLabel').textContent=kblabel(tr.torrentUploadLimit,512,'끔');
    fillSpeedSelect(document.getElementById('torrentDownloadLimit'), tr.torrentDownloadLimit!=null?tr.torrentDownloadLimit:0, 1024);
    document.getElementById('torrentDownloadLabel').textContent=kbpsName(tr.torrentDownloadLimit||0);
    document.getElementById('torrentStallEnabled').checked=tr.torrentStallEnabled!==false;
    fillSelect('torrentStallThresholdKbps',[1,2,4,8,16,32,64,128],tr.torrentStallThresholdKbps!=null?tr.torrentStallThresholdKbps:2,'KB/s');
    fillSelect('torrentStallTimeoutSec',[30,60,120,300,600,1800],tr.torrentStallTimeoutSec!=null?tr.torrentStallTimeoutSec:60,'초');
    document.getElementById('torrentMaxActive').value=tr.torrentMaxActive!=null?tr.torrentMaxActive:3;
    document.getElementById('torrentMaxActiveLabel').textContent=tr.torrentMaxActive!=null?tr.torrentMaxActive:3;
    document.getElementById('torrentSeedRatio').value=tr.torrentSeedRatio!=null?tr.torrentSeedRatio:2.0;
    document.getElementById('torrentSeedRatioLabel').textContent=(tr.torrentSeedRatio!=null?tr.torrentSeedRatio:2.0).toFixed(1);
    document.getElementById('torrentDhtEnabled').checked=tr.torrentDhtEnabled!==false;
    document.getElementById('torrentEnabled').checked=tr.torrentEnabled!==false;
    document.getElementById('torrentRemoveAfterMove').checked=tr.torrentRemoveAfterMove===true;
    document.getElementById('torrentPexEnabled').checked=tr.torrentPexEnabled!==false;
    document.getElementById('torrentSequentialDownload').checked=tr.torrentSequentialDownload===true;
    document.getElementById('torrentTrackerSync').checked=tr.torrentTrackerSync!==false;
    document.getElementById('torrentListenPort').value=tr.torrentListenPort!=null?tr.torrentListenPort:6881;
    document.getElementById('torrentSavePath').value=tr.torrentSavePath||'${com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path}';
    document.getElementById('pathTestResult').textContent='';
    // Debrid 설정
    document.getElementById('debridEnabled').checked=db.debridEnabled===true;
    document.getElementById('debridApiKey').value=db.debridApiKey||'';
    highlightDebridProvider(db.debridProvider||'');
    // 터널 설정
    document.getElementById('tunnelEnabled').checked=tn.tunnelEnabled===true;
    tunnelSelectedProvider=tn.tunnelProvider||'';
    if(tunnelSelectedProvider)highlightTunnelProvider(tunnelSelectedProvider);
    // 가드 설정
    document.getElementById('guardEnabled').checked=gd.guardEnabled===true;
    document.getElementById('guardThermalLimit').value=gd.guardThermalLimit!=null?gd.guardThermalLimit:50;
    document.getElementById('guardThermalLabel').textContent=(gd.guardThermalLimit!=null?gd.guardThermalLimit:50)+'°C';
    document.getElementById('guardBatteryLimit').value=gd.guardBatteryLimit!=null?gd.guardBatteryLimit:20;
    document.getElementById('guardBatteryLabel').textContent=(gd.guardBatteryLimit!=null?gd.guardBatteryLimit:20)+'%';
    document.getElementById('guardStorageLimit').value=gd.guardStorageLimit!=null?gd.guardStorageLimit:90;
    document.getElementById('guardStorageLabel').textContent=(gd.guardStorageLimit!=null?gd.guardStorageLimit:90)+'%';
    document.getElementById('watchdogIntervalSec').value=gd.watchdogIntervalSec!=null?gd.watchdogIntervalSec:60;
    document.getElementById('forceHttpsRedirect').checked=gd.forceHttpsRedirect===true;
    // 서버 제어 (v0.36)
    document.getElementById('httpsEnabled').checked=sv.httpsEnabled!==false;
    document.getElementById('bootAutoStart').checked=sv.bootAutoStart!==false;
    document.getElementById('launchAutoStart').checked=sv.launchAutoStart!==false;
    document.getElementById('webServerEnabled').checked=sv.webServerEnabled!==false;
    // MCP 설정
    document.getElementById('mcpPrivacyMode').checked=mc.mcpPrivacyMode===true;
    var disabled=mc.mcpToolsDisabled||[];
    ['file_list','file_read','download_add','download_list','download_control'].forEach(function(t){
      document.getElementById('mcpTool_'+t).checked=disabled.indexOf(t)===-1;
    });
    // 스케줄 설정
    document.getElementById('scheduleEnabled').checked=sch.scheduleEnabled===true;
    document.getElementById('scheduleCron').value=sch.scheduleCron||'';
    document.getElementById('scheduleWifiOnly').checked=sch.scheduleWifiOnly!==false;
    document.getElementById('scheduleChargingOnly').checked=sch.scheduleChargingOnly===true;
    document.getElementById('signalGateEnabled').checked=sch.signalGateEnabled===true;
    document.getElementById('scheduleBatteryMin').value=sch.scheduleBatteryMin!=null?sch.scheduleBatteryMin:30;
    document.getElementById('scheduleBatteryLabel').textContent=(sch.scheduleBatteryMin!=null?sch.scheduleBatteryMin:30)+'%';
    if(sch.scheduleCron){
      var el=document.getElementById('scheduleStatus');
      el.textContent='크론: "'+sch.scheduleCron+'"'+(sch.cronValid?' ✓':' ✗');
      el.style.color=sch.cronValid?'var(--ok)':'var(--err)';
    }
    loadRssFeeds();
    // 디버그 상태
    fetch('/api/debug/status').then(function(r){return r.json();}).then(function(d){
      var el=document.getElementById('debugStatusInfo');
      if(el) el.innerHTML='로그: <b>'+d.logCount+'</b>건 / API 호출: <b>'+d.apiCallCount+'</b>건 / '
        +'최대: '+d.maxLines+'줄 / v'+d.version+' / uptime '+fmtDuration(d.uptime/1000);
    }).catch(function(){});
    checkOverlay();
  }).catch(function(e){
    console.error('설정 로드 실패',e);
  });
}

function updateSpeedLimitStatus(sl){
  var el=document.getElementById('speedLimitStatus');
  if(!el)return;
  if(sl.maxDownloadBps<=0&&sl.maxUploadBps<=0){
    el.innerHTML='<span style="color:var(--dim)">전역 속도 제한: 비활성화 (무제한)</span>';
  }else{
    var parts=[];
    if(sl.maxDownloadBps>0)parts.push('다운로드 '+fmt(sl.maxDownloadBps)+'/s');
    if(sl.maxUploadBps>0)parts.push('업로드 '+fmt(sl.maxUploadBps)+'/s');
    el.innerHTML='<span style="color:var(--ok)">전역 속도 제한 적용 중: '+parts.join(' · ')+'</span>';
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
    var bps=speedInputBps(type,input);
    // 한쪽만 보낸다 — 이전엔 반대편에 0을 함께 보내서
    // "다운로드 제한 켜기"가 업로드 제한까지 0(끔)으로 덮어썼다.
    saveSpeedLimit(type==='dl'?bps:undefined, type==='ul'?bps:undefined);
  }else{
    saveSpeedLimit(type==='dl'?0:undefined, type==='ul'?0:undefined);
  }
}

// 다운로드는 B/s 프리셋 select, 업로드는 Mbps range 유지
function speedInputBps(type,input){
  var v=parseInt(input.value,10);
  if(type==='ul')return (v||3)*1048576;
  return v||3145728;
}

function onSpeedLimitChange(type){
  var input, label, bps;
  if(type==='dl'){
    input=document.getElementById('maxDownloadMbps');
    label=document.getElementById('maxDownloadLabel');
    bps=parseInt(input.value,10)||3145728;
    label.textContent=input.selectedIndex>=0?input.options[input.selectedIndex].text:kbpsName(Math.ceil(bps/1024));
  }else{
    input=document.getElementById('maxUploadMbps');
    label=document.getElementById('maxUploadLabel');
    var mbps=parseInt(input.value,10)||3;
    bps=mbps*1048576;
    label.textContent=mbps+' Mbps';
  }
  saveSpeedLimit(type==='dl'?bps:undefined, type==='ul'?bps:undefined);
}

function saveSpeedLimit(dl, ul){
  var body={};
  if(dl!==undefined)body.maxDownloadBps=dl;
  if(ul!==undefined)body.maxUploadBps=ul;
  apiPost('/api/settings/speed-limit',body)
    .then(function(d){if(d.ok)loadSettings();else alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}

function saveDownloadSettings(){
  var body={
    concurrency:num(document.getElementById('concurrency').value,2),
    speedLimitKbps:speedSelectKbps(document.getElementById('speedLimitKbps'),1024),
    notifications:document.getElementById('notifications').checked,
    storageQuotaGb:num(document.getElementById('storageQuotaGb').value,0),
    autoClassify:document.getElementById('autoClassify').checked,
    completionAction:document.getElementById('completionAction').value||'none',
    idleTimeoutMin:num(document.getElementById('idleTimeoutMin').value,30),
    lowPowerMode:document.getElementById('lowPowerMode').checked
  };
  document.getElementById('concurrencyLabel').textContent=body.concurrency;
  document.getElementById('speedLimitLabel').textContent=kblabel(body.speedLimitKbps,0,'무제한');
  document.getElementById('storageQuotaLabel').textContent=body.storageQuotaGb>0?body.storageQuotaGb+'GB':'끔';
  apiPost('/api/settings/download',body)
    .then(function(d){if(!d.ok)alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}

// 속도 스케줄 (v0.24) — 목록은 서버가 단일 진실, 로컬 캐시 없음
var __speedWindows=[];
var __dayNames={1:'일',2:'월',3:'화',4:'수',5:'목',6:'금',7:'토'};
function ssToMin(v){var p=(v||'').split(':');return (parseInt(p[0],10)||0)*60+(parseInt(p[1],10)||0);}
function ssToTime(m){var h=Math.floor(m/60),mm=m%60;return (h<10?'0':'')+h+':'+(mm<10?'0':'')+mm;}
function loadSpeedSchedule(){
  fetch('/api/settings/speed-schedule').then(function(r){return r.json();}).then(function(j){
    __speedWindows=j.windows||[];
    renderSpeedSchedule();
  }).catch(function(){});
}
function renderSpeedSchedule(){
  var el=document.getElementById('speedSchedList');
  if(!el)return;
  if(!__speedWindows.length){el.textContent='등록된 스케줄 없음';return;}
  var h='';
  for(var i=0;i<__speedWindows.length;i++){
    var w=__speedWindows[i];
    var days=(w.days||[]).map(function(d){return __dayNames[d]||d;}).join(',');
    h+='<div>'+esc(w.id.slice(-6))+' · '+esc(days)+' '+ssToTime(w.startMin)+'~'+ssToTime(w.endMin)
      +' · ↓'+w.downKbps+'KB/s ↑'+w.upKbps+'KB/s'
      +' <button class="ghost sm" onclick="toggleSpeedWindow('+i+')">'+(w.enabled?'끄기':'켜기')+'</button>'
      +' <button class="ghost sm" onclick="deleteSpeedWindow('+i+')">삭제</button></div>';
  }
  el.innerHTML=h;
}
function saveSpeedSchedule(){
  apiPost('/api/settings/speed-schedule',{windows:__speedWindows})
    .then(function(d){if(d.ok){showDlToast('스케줄 저장됨');renderSpeedSchedule();}else alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}
function addSpeedWindow(){
  var days=[];
  var cbs=document.querySelectorAll('.ssd:checked');
  for(var i=0;i<cbs.length;i++)days.push(parseInt(cbs[i].value,10));
  if(!days.length){alert('요일을 선택하세요');return;}
  var w={id:'w'+Date.now(),enabled:true,days:days,
    startMin:ssToMin(document.getElementById('ssStart').value),
    endMin:ssToMin(document.getElementById('ssEnd').value),
    downKbps:num(document.getElementById('ssDown').value,0),
    upKbps:num(document.getElementById('ssUp').value,0)};
  if(w.startMin<0||w.startMin>1439||w.endMin<0||w.endMin>1439){alert('시간이 올바르지 않습니다');return;}
  if(__speedWindows.length>=10){alert('최대 10개까지 등록 가능합니다');return;}
  __speedWindows.push(w);
  saveSpeedSchedule();
}
function toggleSpeedWindow(i){
  if(!__speedWindows[i])return;
  __speedWindows[i].enabled=!__speedWindows[i].enabled;
  saveSpeedSchedule();
}
function deleteSpeedWindow(i){
  if(!__speedWindows[i])return;
  __speedWindows.splice(i,1);
  saveSpeedSchedule();
}

function saveTorrentSettings(){
  var body={
    torrentUploadLimit:num(document.getElementById('torrentUploadLimit').value,512),
    torrentDownloadLimit:speedSelectKbps(document.getElementById('torrentDownloadLimit'),1024),
    torrentStallEnabled:document.getElementById('torrentStallEnabled').checked,
    torrentStallThresholdKbps:num(document.getElementById('torrentStallThresholdKbps').value,2),
    torrentStallTimeoutSec:num(document.getElementById('torrentStallTimeoutSec').value,60),
    torrentMaxActive:num(document.getElementById('torrentMaxActive').value,3),
    torrentSeedRatio:num(document.getElementById('torrentSeedRatio').value,2.0),
    torrentDhtEnabled:document.getElementById('torrentDhtEnabled').checked,
    torrentEnabled:document.getElementById('torrentEnabled').checked,
    torrentRemoveAfterMove:document.getElementById('torrentRemoveAfterMove').checked,
    torrentPexEnabled:document.getElementById('torrentPexEnabled').checked,
    torrentSequentialDownload:document.getElementById('torrentSequentialDownload').checked,
    torrentTrackerSync:document.getElementById('torrentTrackerSync').checked,
    torrentListenPort:num(document.getElementById('torrentListenPort').value,6881),
    torrentSavePath:document.getElementById('torrentSavePath').value.trim()
  };
  document.getElementById('torrentUploadLabel').textContent=kblabel(body.torrentUploadLimit,512,'끔');
  document.getElementById('torrentDownloadLabel').textContent=kbpsName(body.torrentDownloadLimit);
  document.getElementById('torrentMaxActiveLabel').textContent=body.torrentMaxActive;
  document.getElementById('torrentSeedRatioLabel').textContent=body.torrentSeedRatio.toFixed(1);
  apiPost('/api/settings/torrent',body)
    .then(function(d){if(d.ok){showDlToast('토렌트 설정 저장됨');}else alert('저장 실패: '+(d.error||''));})
    .catch(function(e){alert('저장 실패: '+e);});
}

function saveSearchSettings(){
  var body={
    searchEnabled:document.getElementById('searchEnabled').checked,
    searchUrl:document.getElementById('searchUrl').value.trim(),
    searchApiKey:document.getElementById('searchApiKey').value.trim()
  };
  apiPost('/api/settings/search',body)
    .then(function(d){
      showDlToast(d.ok?'검색 설정 저장됨':'저장 실패');
      var sec=document.getElementById('tsearchSection');
      if(sec)sec.style.display=body.searchEnabled?'block':'none';
    })
    .catch(function(e){alert('저장 실패: '+e);});
}
function loadSearchSettings(){
  fetch('/api/settings/search').then(function(r){return r.json();}).then(function(s){
    document.getElementById('searchEnabled').checked=s.searchEnabled===true;
    document.getElementById('searchUrl').value=s.searchUrl||'';
    document.getElementById('searchApiKey').value=s.searchApiKey||'';
  }).catch(function(){});
}

function randomizePort(){
  var port=49152+Math.floor(Math.random()*16384);
  document.getElementById('torrentListenPort').value=port;
  saveTorrentSettings();
}

function refreshTrackers(){
  var el=document.getElementById('trackerCountLabel');
  if(el)el.textContent='동기화 중…';
  fetch('/api/torrents/trackers/refresh',{method:'POST'}).then(function(r){return r.json();}).then(function(j){
    if(el)el.textContent='트래커 '+(j.count||0)+'개 동기화됨';
    showDlToast('트래커 '+(j.count||0)+'개 동기화됨');
    probeTrackers();
  }).catch(function(e){
    if(el)el.textContent='동기화 실패';
    alert('동기화 실패: '+e);
  });
}
function loadTrackerCount(){
  fetch('/api/torrents/trackers').then(function(r){return r.json();}).then(function(j){
    var el=document.getElementById('trackerCountLabel');
    if(el)el.textContent='트래커 '+(j.count||0)+'개'+(j.probing?' (측정 중…)':' (도달 '+(j.probeOk||0)+'개)');
  }).catch(function(){});
}
function probeTrackers(){
  var el=document.getElementById('trackerCountLabel');
  if(el)el.textContent='도달성 측정 중…';
  fetch('/api/torrents/trackers/probe',{method:'POST'}).then(function(r){return r.json();}).then(function(){
    setTimeout(loadTrackerCount,5000);
  }).catch(function(e){alert('측정 시작 실패: '+e);});
}

// 업로드 최소화 프리셋 (T-959)
function minimizeUpload(){
  document.getElementById('torrentSeedRatio').value=0.5;
  document.getElementById('torrentSeedRatioLabel').textContent='0.5';
  document.getElementById('torrentUploadLimit').value=32;
  document.getElementById('torrentUploadLabel').textContent='32 KB/s';
  document.getElementById('torrentDhtEnabled').checked=false;
  saveTorrentSettings();
}

function testPath(){
  var path=document.getElementById('torrentSavePath').value.trim();
  if(!path){alert('경로를 입력하세요');return;}
  var el=document.getElementById('pathTestResult');
  el.textContent='테스트 중...';el.style.color='var(--accent2)';
  fetch('/api/storage/test-path',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:path})})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){
        el.textContent='✓ 쓰기 가능';el.style.color='var(--ok)';
      }else{
        el.textContent='✗ 쓰기 불가: '+(d.error||'알 수 없는 오류');el.style.color='var(--err)';
      }
    })
    .catch(function(e){el.textContent='✗ 테스트 실패: '+e;el.style.color='var(--err)';});
}

function loadRssFeeds(){
  fetch('/api/rss').then(function(r){return r.json();}).then(function(arr){
    var el=document.getElementById('rssFeedList');
    if(!arr||arr.length===0){el.innerHTML='<span style="color:var(--dim)">등록된 피드 없음</span>';return;}
    var html='';
    arr.forEach(function(f){
      var status=f.error?'<span style="color:var(--err)">⚠ '+f.error+'</span>':'<span style="color:var(--ok)">✓</span>';
      var lastCheck=f.lastCheckedAt>0?new Date(f.lastCheckedAt).toLocaleString():'확인 전';
      html+='<div style="display:flex;align-items:center;gap:8px;padding:8px 0;border-bottom:1px solid var(--line)">';
      html+='<div style="flex:1;min-width:0">';
      html+='<div style="font-size:13px;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis">'+f.name+'</div>';
      html+='<div style="font-size:11px;color:var(--dim);white-space:nowrap;overflow:hidden;text-overflow:ellipsis">'+f.url+'</div>';
      html+='<div style="font-size:11px;color:var(--dim)">마지막: '+lastCheck+' | 항목: '+f.lastItemCount+'</div>';
      html+='</div>';
      html+=status;
      html+=' <button class="ghost sm" onclick="deleteRssFeed(\''+f.id+'\')" style="color:var(--err);border:none;padding:2px 6px">✕</button>';
      html+='</div>';
    });
    el.innerHTML=html;
  }).catch(function(e){console.error('RSS 로드 실패',e);});
}

function addRssFeed(){
  var url=document.getElementById('rssFeedUrl').value.trim();
  if(!url){alert('URL을 입력하세요');return;}
  var body={
    url:url,
    name:document.getElementById('rssFeedName').value.trim(),
    filterKeyword:document.getElementById('rssFilterKeyword').value.trim(),
    filterRegex:document.getElementById('rssFilterRegex').value.trim(),
    autoDownload:true
  };
  fetch('/api/rss',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){
        showDlToast('피드 추가됨');
        document.getElementById('rssFeedUrl').value='';
        document.getElementById('rssFeedName').value='';
        document.getElementById('rssFilterKeyword').value='';
        document.getElementById('rssFilterRegex').value='';
        loadRssFeeds();
      }else alert('실패: '+(d.error||''));
    })
    .catch(function(e){alert('실패: '+e);});
}

function deleteRssFeed(id){
  confirmPopup('피드 삭제','이 피드를 삭제하시겠습니까?',function(){
    fetch('/api/rss/'+id,{method:'DELETE'})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.ok){showDlToast('피드 삭제됨');loadRssFeeds();}
      })
      .catch(function(e){alert('실패: '+e);});
  },true);
}

function checkRssFeeds(){
  showDlToast('RSS 피드 확인 중...');
  fetch('/api/rss/0/check',{method:'POST'})
    .then(function(r){return r.json();})
    .then(function(d){if(d.ok)loadRssFeeds();})
    .catch(function(e){console.error('RSS 확인 실패',e);});
}

function resetSettings(category){
  var label=category==='all'?'전체 설정':'「'+category+'」 설정';
  confirmPopup('설정 초기화',label+'을(를) 기본값으로 초기화하시겠습니까?\n(전역 속도 제한은 초기화되지 않습니다)',function(){
    fetch('/api/settings/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({category:category})})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.ok){
          showDlToast('기본값 복원됨');
          loadSettings();
        }else alert('실패');
      })
      .catch(function(e){alert('실패: '+e);});
  },true);
}

// 슬라이더 변경 이벤트 바인딩 (초기화 시 한 번만)
(function(){
  var s=document.getElementById('concurrency');
  if(s)s.addEventListener('input',function(){document.getElementById('concurrencyLabel').textContent=this.value;});
  s=document.getElementById('speedLimitKbps');
  if(s)s.addEventListener('change',function(){document.getElementById('speedLimitLabel').textContent=this.selectedIndex>=0?this.options[this.selectedIndex].text:'무제한';});
  s=document.getElementById('storageQuotaGb');
  if(s)s.addEventListener('input',function(){document.getElementById('storageQuotaLabel').textContent=this.value>0?this.value+'GB':'끔';});
  s=document.getElementById('torrentUploadLimit');
  if(s)s.addEventListener('input',function(){document.getElementById('torrentUploadLabel').textContent=this.value+' KB/s';});
  s=document.getElementById('torrentDownloadLimit');
  if(s)s.addEventListener('change',function(){document.getElementById('torrentDownloadLabel').textContent=this.selectedIndex>=0?this.options[this.selectedIndex].text:'무제한';});
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
  document.getElementById('maxUploadMbps')?.addEventListener('input',function(){document.getElementById('maxUploadLabel').textContent=this.value+' Mbps';});
  document.getElementById('maxDownloadMbps')?.addEventListener('change',autoSave('sl',function(){onSpeedLimitChange('dl');}));
  document.getElementById('maxUploadMbps')?.addEventListener('change',autoSave('sl',function(){onSpeedLimitChange('ul');}));
  document.getElementById('concurrency')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('speedLimitKbps')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('notifications')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('storageQuotaGb')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('autoClassify')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('lowPowerMode')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('completionAction')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('idleTimeoutMin')?.addEventListener('change',autoSave('dl',saveDownloadSettings));
  document.getElementById('torrentUploadLimit')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentDownloadLimit')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentMaxActive')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentSeedRatio')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentDhtEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentRemoveAfterMove')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentPexEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentSequentialDownload')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentTrackerSync')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentListenPort')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentSavePath')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentStallEnabled')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentStallThresholdKbps')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('torrentStallTimeoutSec')?.addEventListener('change',autoSave('tr',saveTorrentSettings));
  document.getElementById('guardThermalLimit')?.addEventListener('input',function(){document.getElementById('guardThermalLabel').textContent=this.value+'°C';});
  document.getElementById('guardBatteryLimit')?.addEventListener('input',function(){document.getElementById('guardBatteryLabel').textContent=this.value+'%';});
  document.getElementById('guardStorageLimit')?.addEventListener('input',function(){document.getElementById('guardStorageLabel').textContent=this.value+'%';});
  document.getElementById('guardEnabled')?.addEventListener('change',autoSave('gd',saveGuardSettings));
  document.getElementById('guardThermalLimit')?.addEventListener('change',autoSave('gd',saveGuardSettings));
  document.getElementById('guardBatteryLimit')?.addEventListener('change',autoSave('gd',saveGuardSettings));
  document.getElementById('guardStorageLimit')?.addEventListener('change',autoSave('gd',saveGuardSettings));
  document.getElementById('debridEnabled')?.addEventListener('change',autoSave('db',saveDebridSettings));
})();

// ── Debrid 설정 ──
function highlightDebridProvider(provider){
  ['REALDEBRID','ALLDEBRID','PREMIUMIZE'].forEach(function(p){
    var el=document.getElementById('debrid'+p.charAt(0)+p.slice(1).toLowerCase());
    if(el){el.style.borderColor=p===provider?'var(--accent)':'var(--line2)';el.style.background=p===provider?'var(--sel)':'transparent';}
  });
}
function setDebridProvider(provider){
  highlightDebridProvider(provider);
  apiPost('/api/settings/debrid',{debridProvider:provider})
    .then(function(d){if(d.ok)showDlToast('제공자 설정됨: '+provider);})
    .catch(function(e){alert('설정 실패: '+e);});
}
function saveDebridSettings(){
  var body={
    debridEnabled:document.getElementById('debridEnabled').checked,
    debridApiKey:document.getElementById('debridApiKey').value.trim()
  };
  apiPost('/api/settings/debrid',body)
    .then(function(d){if(d.ok)showDlToast('Debrid 설정 저장됨');else alert('저장 실패: '+(d.error||''));})
    .catch(function(e){alert('저장 실패: '+e);});
}
function checkDebridAccount(){
  var el=document.getElementById('debridStatus');
  el.textContent='계정 확인 중...';el.style.color='var(--accent2)';
  fetch('/api/debrid/check',{method:'POST'})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){
        el.innerHTML='<span style="color:var(--ok)">✓ 연결됨</span> — '+(d.username||d.email||'알 수 없음')+(d.premium?' <span class="badge DONE">프리미엄</span>':'');
      }else{
        el.innerHTML='<span style="color:var(--err)">✗ '+esc(d.error||'확인 실패')+'</span>';
      }
    })
    .catch(function(e){el.innerHTML='<span style="color:var(--err)">✗ 오류: '+esc(e.message)+'</span>';});
}

// ── 터널 설정 ──
var tunnelSelectedProvider='';
function highlightTunnelProvider(provider){
  tunnelSelectedProvider=provider;
  ['TAILSCALE','CLOUDFLARE'].forEach(function(p){
    var el=document.getElementById('tunnel'+p.charAt(0)+p.slice(1).toLowerCase());
    if(el){el.style.borderColor=p===provider?'var(--accent)':'var(--line2)';el.style.background=p===provider?'var(--sel)':'transparent';}
  });
}
function saveTunnelSettings(){
  var body={
    tunnelEnabled:document.getElementById('tunnelEnabled').checked,
    tunnelProvider:tunnelSelectedProvider
  };
  apiPost('/api/settings/tunnel',body)
    .then(function(d){if(d.ok){showDlToast('터널 설정 저장됨');checkTunnelStatus();}else alert('저장 실패: '+(d.error||''));})
    .catch(function(e){alert('저장 실패: '+e);});
}
function checkTunnelStatus(){
  var el=document.getElementById('tunnelStatus');
  el.textContent='상태 확인 중...';el.style.color='var(--accent2)';
  fetch('/api/tunnel/status')
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.connected){
        el.innerHTML='<span style="color:var(--ok)">✓ 연결됨</span> — IP <b>'+esc(d.ip)+'</b> · 접속 주소 <a href="'+esc(d.url)+'" target="_blank">'+esc(d.url)+'</a>';
      }else{
        el.innerHTML='<span style="color:var(--err)">✗ '+esc(d.reason||'연결 안 됨')+'</span>';
      }
    })
    .catch(function(e){el.innerHTML='<span style="color:var(--err)">✗ 오류: '+esc(e.message)+'</span>';});
}

// ── 서버 제어 (v0.36) ──
function saveServerSettings(){
  var body={
    httpsEnabled:document.getElementById('httpsEnabled').checked,
    bootAutoStart:document.getElementById('bootAutoStart').checked,
    launchAutoStart:document.getElementById('launchAutoStart').checked,
    webServerEnabled:document.getElementById('webServerEnabled').checked
  };
  apiPost('/api/settings/server',body)
    .then(function(d){if(d.ok)showDlToast('서버 설정 저장됨');else alert('저장 실패: '+(d.error||''));})
    .catch(function(e){alert('저장 실패: '+e);});
}

// ── 트래픽 통계 (v0.37.1, 3열 grid + 자체 SVG) ──
function statsCard(title,b){
  return '<div class="stats-card">'
    +'<div class="s-title">'+title+'</div>'
    +'<div class="s-total">⬇ '+fmt(b.down)+'</div>'
    +'<div class="s-total">⬆ '+fmt(b.up)+'</div>'
    +'<div class="s-sub">받기 HTTP '+fmt(b.downHttp)+'<br>비디오 '+fmt(b.downVideo)+'<br>토렌트 '+fmt(b.downTorrent)+'</div>'
    +'<div class="s-sub">보내기 서빙 '+fmt(b.upServe)+'<br>토렌트 '+fmt(b.upTorrent)+'</div>'
    +'</div>';
}
function refreshStats(){
  __statsAt=Date.now();
  var summary=null;
  fetch('/api/stats/summary').then(function(r){return r.json();}).then(function(s){
    summary=s;
    document.getElementById('statsCards').innerHTML=statsCard('오늘',s.today)+statsCard('이번달',s.month)+statsCard('누적',s.total);
    if(window._statsDays)renderStatsHighlights(window._statsDays,s);
  }).catch(function(){});
  fetch('/api/stats/daily?days=400').then(function(r){return r.json();}).then(function(d){
    var days=d.days||[];
    window._statsDays=days;
    renderStatsChart(days.slice(-30));
    if(summary)renderStatsHighlights(days,summary);
    renderStatsRecords(days,summary);
  }).catch(function(){});
  fetch('/api/stats/extended').then(function(r){return r.json();}).then(function(e){
    window._statsExt=e;renderStatsRecords(window._statsDays||[],summary);
  }).catch(function(){});
}
function spd(b){return fmt(b)+'/s';}
function hlItem(label,value){
  return '<div class="stats-card"><div class="s-title">'+esc(label)+'</div><div class="s-total">'+value+'</div></div>';
}
function renderStatsRecords(days,s){
  var el=document.getElementById('statsRecords');
  if(!el)return;
  var peakD=0,peakU=0,peakDay='—';(days||[]).forEach(function(d){
    if((d.maxDownBps||0)>peakD){peakD=d.maxDownBps;peakDay=d.date;}
    if((d.maxUpBps||0)>peakU)peakU=d.maxUpBps;
  });
  var done=s&&s.total?((s.total.done||0)+'건(H '+(s.total.doneHttp||0)+' · V '+(s.total.doneVideo||0)+' · T '+(s.total.doneTorrent||0)+')'):'—';
  var fail=s&&s.total?((s.total.failCount||0)+'건'):'—';
  var e=window._statsExt||{};
  var peers=e.peers?('현재 '+e.peers.seeds+'시드/'+e.peers.peers+'피어<br>피크 '+e.peers.peakSeeds+'/'+e.peers.peakPeers):'—';
  var up=e.uptime?('부팅 '+e.uptime.bootCount+'회'):('가동 —');
  var stor=e.storage?('보관함 '+fmt(e.storage.dirSize)+'<br>쿼터정리 누적 '+e.storage.quotaMovedTotal+'개'):'—';
  var net=e.net?('단절 '+e.net.lossCount+'회'):'—';
  var thr=e.throttle?('스로틀 '+e.throttle.throttleCount+'회'):'—';
  el.innerHTML=
    hlItem('최고 다운로드 속도',peakD>0?spd(peakD)+' ('+esc(peakDay)+')':'—')
    +hlItem('최고 업로드 속도',peakU>0?spd(peakU):'—')
    +hlItem('완료 건수',done)
    +hlItem('실패 건수',fail)
    +hlItem('피어',peers)
    +hlItem('가동',up)
    +hlItem('저장공간',stor)
    +hlItem('네트워크/가드',net+' · '+thr);
}
function renderStatsHighlights(days,s){
  var el=document.getElementById('statsHighlights');
  if(!el)return;
  var best=null;(days||[]).forEach(function(d){if(d.down>0&&(!best||d.down>best.down))best=d;});
  var top3=(days||[]).filter(function(d){return d.down>0;}).sort(function(a,b){return b.down-a.down;}).slice(0,3);
  var last7=(days||[]).slice(-7);
  var avg7=last7.length?Math.round(last7.reduce(function(a,d){return a+d.down;},0)/last7.length):0;
  var now=new Date(),elapsed=now.getDate(),dim=new Date(now.getFullYear(),now.getMonth()+1,0).getDate();
  var forecast=s&&s.month?Math.round(s.month.down/elapsed*dim):0;
  var share=(s&&s.total&&s.total.down>0)?(s.total.up/s.total.down*100).toFixed(1)+'%':'—';
  var tratio=(s&&s.total&&s.total.downTorrent>0)?(s.total.upTorrent/s.total.downTorrent*100).toFixed(1)+'%':'—';
  var cur=0;for(var i=days.length-1;i>=0;i--){if(days[i].down>0)cur++;else break;}
  var longest=0,run=0;days.forEach(function(d){if(d.down>0){run++;if(run>longest)longest=run;}else run=0;});
  var active30=days.slice(-30).filter(function(d){return d.down+d.up>0;}).length;
  var td=s&&s.total?s.total.down:0;
  var mix='—';
  if(td>0){mix='H '+Math.round(s.total.downHttp/td*100)+'% · V '+Math.round(s.total.downVideo/td*100)+'% · T '+Math.round(s.total.downTorrent/td*100)+'%';}
  el.innerHTML=
    hlItem('최다 다운로드일',best?esc(best.date)+' · '+fmt(best.down):'—')
    +hlItem('Top3',top3.length?top3.map(function(d){return esc(d.date.slice(5))+' '+fmt(d.down);}).join('<br>'):'—')
    +hlItem('주간평균 / 월예측',fmt(avg7)+' / 약 '+fmt(forecast))
    +hlItem('공유비율 전체/토렌트',share+' / '+tratio)
    +hlItem('연속 / 최장 / 활성30일',cur+'일 / '+longest+'일 / '+active30+'일')
    +hlItem('타입 비중',mix);
}
function renderStatsChart(days){
  var el=document.getElementById('statsChart');
  if(!days.length){el.innerHTML='<div class="empty">기록 없음</div>';return;}
  var max=1;days.forEach(function(d){max=Math.max(max,d.down,d.up);});
  var W=days.length*24+56,H=170,base=H-22,bw=8;
  var h='<svg viewBox="0 0 '+W+' '+H+'" width="'+W+'" height="'+H+'">';
  days.forEach(function(d,i){
    var x=52+i*24;
    var dh=Math.round((d.down/max)*(base-8)),uh=Math.round((d.up/max)*(base-8));
    h+='<rect x="'+x+'" y="'+(base-dh)+'" width="'+bw+'" height="'+dh+'" fill="var(--accent)"><title>'+esc(d.date)+' 받기 '+fmt(d.down)+'</title></rect>';
    h+='<rect x="'+(x+bw+1)+'" y="'+(base-uh)+'" width="'+bw+'" height="'+uh+'" fill="#f96"><title>'+esc(d.date)+' 보내기 '+fmt(d.up)+'</title></rect>';
    if(i%7===0||i===days.length-1)h+='<text x="'+(x+bw)+'" y="'+(H-6)+'" font-size="9" fill="var(--dim)" text-anchor="middle">'+esc(d.date.slice(5))+'</text>';
  });
  h+='<line x1="48" y1="8" x2="48" y2="'+base+'" stroke="var(--line)"/><line x1="48" y1="'+base+'" x2="'+W+'" y2="'+base+'" stroke="var(--line)"/>';
  h+='<text x="2" y="16" font-size="9" fill="var(--dim)">'+fmt(max)+'</text></svg>';
  el.innerHTML=h;
  var t=days[days.length-1];
  document.getElementById('statsBreakdown').textContent='최근일('+t.date+') 받기 HTTP '+fmt(t.downHttp)+' · 비디오 '+fmt(t.downVideo)+' · 토렌트 '+fmt(t.downTorrent)+' / 보내기 서빙 '+fmt(t.upServe)+' · 토렌트 '+fmt(t.upTorrent);
}

// ── 가드 데몬 설정 ──
function saveGuardSettings(){
  var watchdogSec=num(document.getElementById('watchdogIntervalSec').value,60);
  if(watchdogSec<15)watchdogSec=15;
  if(watchdogSec>3600)watchdogSec=3600;
  var body={
    guardEnabled:document.getElementById('guardEnabled').checked,
    guardThermalLimit:num(document.getElementById('guardThermalLimit').value,50),
    guardBatteryLimit:num(document.getElementById('guardBatteryLimit').value,20),
    guardStorageLimit:num(document.getElementById('guardStorageLimit').value,90),
    watchdogIntervalSec:watchdogSec,
    forceHttpsRedirect:document.getElementById('forceHttpsRedirect').checked
  };
  document.getElementById('guardThermalLabel').textContent=body.guardThermalLimit+'°C';
  document.getElementById('guardBatteryLabel').textContent=body.guardBatteryLimit+'%';
  document.getElementById('guardStorageLabel').textContent=body.guardStorageLimit+'%';
  apiPost('/api/settings/guard',body)
    .then(function(d){if(d.ok)showDlToast('가드 설정 저장됨');else alert('저장 실패');})
    .catch(function(e){alert('저장 실패: '+e);});
}
function checkGuardStatus(){
  var el=document.getElementById('guardStatus');
  el.textContent='상태 확인 중...';el.style.color='var(--accent2)';
  fetch('/api/guard/status')
    .then(function(r){return r.json();})
    .then(function(d){
      var parts=[];
      parts.push('열: '+d.thermal+'°C / '+d.thermalLimit+'°C');
      parts.push('배터리: '+(d.batteryLevel>=0?d.batteryLevel+'%':'확인 불가')+' / '+d.batteryLimit+'%');
      parts.push('스토리지: '+d.storageUsed+'% / '+d.storageLimit+'%');
      var color=d.throttled?'var(--err)':'var(--ok)';
      var status=d.throttled?'⚠ 스로틀링 발동 중':'✓ 정상';
      el.innerHTML='<span style="color:'+color+'">'+status+'</span><br>'+parts.join(' · ');
    })
    .catch(function(e){el.innerHTML='<span style="color:var(--err)">✗ 오류: '+esc(e.message)+'</span>';});
}

function saveMcpSettings(){
  var disabled=[];
  ['file_list','file_read','download_add','download_list','download_control'].forEach(function(t){
    if(!document.getElementById('mcpTool_'+t).checked) disabled.push(t);
  });
  var body={
    mcpPrivacyMode:document.getElementById('mcpPrivacyMode').checked,
    mcpToolsDisabled:disabled
  };
  apiPost('/api/settings/mcp',body)
    .then(function(d){if(d.ok) showDlToast('MCP 권한 저장 완료');})
    .catch(function(e){showDlToast('저장 실패: '+e.message);});
}

function saveScheduleSettings(){
  var body={
    scheduleEnabled:document.getElementById('scheduleEnabled').checked,
    scheduleCron:document.getElementById('scheduleCron').value.trim(),
    scheduleWifiOnly:document.getElementById('scheduleWifiOnly').checked,
    scheduleChargingOnly:document.getElementById('scheduleChargingOnly').checked,
    signalGateEnabled:document.getElementById('signalGateEnabled').checked,
    scheduleBatteryMin:num(document.getElementById('scheduleBatteryMin').value,30)
  };
  apiPost('/api/settings/schedule',body)
    .then(function(d){
      if(d.ok){
        showDlToast('스케줄 저장 완료');
        var el=document.getElementById('scheduleStatus');
        if(body.scheduleCron){
          el.textContent='다음 실행: 크론 "'+body.scheduleCron+'"';
          el.style.color='var(--accent2)';
        }else{
          el.textContent='크론 미설정';
          el.style.color='var(--muted)';
        }
      }
    })
    .catch(function(e){showDlToast('저장 실패: '+e.message);});
}

function detectStorage(){
  var el=document.getElementById('storageList');
  el.textContent='감지 중...';el.style.color='var(--accent2)';
  fetch('/api/storage/external')
    .then(function(r){return r.json();})
    .then(function(d){
      if(!d.storages||d.storages.length===0){
        el.innerHTML='<span style="color:var(--muted)">외장 스토리지 없음</span>';
        return;
      }
      var html='';
      d.storages.forEach(function(s){
        var freeGB=(s.freeBytes/1073741824).toFixed(1);
        var totalGB=(s.totalBytes/1073741824).toFixed(1);
        html+='<div style="margin:6px 0;padding:8px 12px;background:var(--track);border-radius:8px">';
        html+='<b>'+esc(s.label)+'</b> <span style="color:var(--muted)">'+esc(s.path)+'</span>';
        html+='<br>여유: '+freeGB+'GB / '+totalGB+'GB';
        if(s.isExternal) html+=' <span style="color:var(--ok)">외장</span>';
        html+='</div>';
      });
      if(d.bestPath) html+='<div style="margin-top:8px;color:var(--ok)">권장 경로: '+esc(d.bestPath)+'</div>';
      el.innerHTML=html;
    })
    .catch(function(e){el.innerHTML='<span style="color:var(--err)">✗ 오류: '+esc(e.message)+'</span>';});
}

// 디버그 오버레이
var overlayRunning=false;
function requestOverlayPerm(){
  fetch('/api/debug/overlay').then(function(r){return r.json();}).then(function(d){
    if(d.hasPermission){
      document.getElementById('overlayStatus').innerHTML='<span style="color:var(--ok)">✓ 권한 있음</span>';
      document.getElementById('overlayPermBtn').style.display='none';
      document.getElementById('overlayToggleBtn').style.display='';
    }else{
      document.getElementById('overlayStatus').innerHTML='<span style="color:var(--err)">권한이 없습니다. 안드로이드 설정에서 "다른 앱 위에 표시"를 허용해주세요.</span>';
    }
  }).catch(function(){});
}
function toggleOverlay(){
  fetch('/api/debug/overlay/toggle',{method:'POST'}).then(function(r){return r.json();}).then(function(d){
    overlayRunning=d.running;
    document.getElementById('overlayToggleBtn').textContent=overlayRunning?'오버레이 끄기':'오버레이 켜기';
    document.getElementById('overlayStatus').innerHTML='<span style="color:var(--ok)">'+(overlayRunning?' 활성화됨':' 중지됨')+'</span>';
  }).catch(function(){});
}
function checkOverlay(){
  fetch('/api/debug/overlay').then(function(r){return r.json();}).then(function(d){
    if(d.hasPermission){
      document.getElementById('overlayStatus').innerHTML='<span style="color:var(--ok)">✓ 권한 있음</span>';
      document.getElementById('overlayPermBtn').style.display='none';
      document.getElementById('overlayToggleBtn').style.display='';
    }else{
      document.getElementById('overlayStatus').innerHTML='<span style="color:var(--muted)">권한 필요</span>';
    }
  }).catch(function(){});
}
</script></body></html>"""
    }
}
