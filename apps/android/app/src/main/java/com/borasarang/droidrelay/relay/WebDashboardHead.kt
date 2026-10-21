package com.borasarang.droidrelay.relay

/// 대시보드 **head + CSS** 조각.
///
/// 원래 `WebDashboardHtml.kt` 한 파일(3041줄)에 CSS·마크업·JS 가 뒤섞여 있었다.
/// 관심사별로 셋을 갈랐다 — 이 파일은 `<head>` 와 `<style>` 만 담는다.
/// **내용은 한 글자도 바꾸지 않았다**(`WebAssets.dashboardHtml` 이 공개 계약).
internal object WebDashboardHead {
    val content: String by lazy {
        """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/favicon-32.png">
<link rel="icon" type="image/png" sizes="16x16" href="/favicon-16.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" content="#F6FBF7">
<title>DroidRelay</title>
<style>
  /* ── v0.59 DroidRelay 토큰 (라이트 기본 · 다크는 data-theme) ──
     시드 #006B56. 이름은 기존 대시보드 변수(--bg/--surface/--accent…)를 유지해
     마크업·스크립트 계약을 건드리지 않는다. 단일 출처: docs/mockups/redesign-v059/README.md */
  :root{color-scheme:light;
    --bg:#F6FBF7;--surface:#FFFFFF;--surface2:#F0F5F0;--track:#DEE4DE;--line:#BFC9C3;--line2:#A9B5AF;
    --text:#171D1A;--muted:#3F4945;--dim:#6F7974;--accent:#006B56;--on-accent:#FFFFFF;--accent2:#006B56;
    --accentbg:#7FF8D8;--ok:#006B56;--okbg:#CCE8DF;--err:#BA1A1A;--danger:#FFDAD6;--warn:#7C5800;
    --sel:#EAEFEA;--glow:transparent}
  body[data-theme=dark]{color-scheme:dark;
    --bg:#0E1513;--surface:#171D1B;--surface2:#1B211F;--track:#303634;--line:#3F4945;--line2:#4A544F;
    --text:#DDE4DF;--muted:#BFC9C3;--dim:#89938E;--accent:#62DCBE;--on-accent:#00382D;--accent2:#62DCBE;
    --accentbg:#005141;--ok:#62DCBE;--okbg:#005141;--err:#FFB4AB;--danger:#93000A;--warn:#F5BF48;
    --sel:#252B29;--glow:transparent}
  body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 -apple-system,'Malgun Gothic',sans-serif}
  .wrap{max-width:1180px;margin:0 auto;padding:20px;display:grid;grid-template-columns:168px minmax(0,1fr);
        gap:8px 22px;align-items:start}
  .wrap>.hd{grid-column:1/-1}
  h1{font-size:22px;margin:0;letter-spacing:-.02em;font-weight:700}
  .hd{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:14px}
  /* margin-left:auto — v0.41: 모바일에서 .hd 가 줄바꿈되면 두 번째 줄에 .hd-acts 혼자
     남고 space-between 은 한 줄짜리 flex line 을 오른쪽으로 밀지 않는다(좌측 정렬).
     그러면 우측 정렬 드롭다운이 화면 왼쪽 밖으로 넘친다.
     position:relative — 드롭다운(.mm) 의 위치 기준. 버튼만 감싸는 44px 래퍼를 쓰면
     좁은 화면에서 좌측으로 넘치므로 액션 그룹 전체를 앵커로 삼는다. */
  .hd-acts{display:flex;align-items:center;gap:8px;margin-left:auto;position:relative}
  #btnRefresh{font-size:16px;padding:8px 13px;line-height:1}
  #btnRefresh.spin{animation:spin .8s linear}
  @keyframes spin{to{transform:rotate(360deg)}}
  #themeSel{-webkit-appearance:none;appearance:none;background-color:var(--surface2);border:1px solid var(--line2);color:var(--text);border-radius:10px;
    padding:8px 28px 8px 10px;font-size:13px;line-height:1.4;outline:none;cursor:pointer;
    background-image:url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M1 1l4 4 4-4' fill='none' stroke='%239AA7BD' stroke-width='1.6' stroke-linecap='round'/%3E%3C/svg%3E");
    background-repeat:no-repeat;background-position:right 10px center}
  #themeSel:focus{border-color:var(--accent)}
  #themeSel option{background-color:var(--surface2);color:var(--text)}
  .fill{background:linear-gradient(90deg,var(--accent),var(--accent2),var(--accent));background-size:200% 100%;
    animation:flow 2.5s linear infinite}
  @keyframes flow{to{background-position:-200% 0}}
  .info{display:flex;align-items:center;justify-content:space-between;gap:14px;color:var(--muted);font-size:12px;background:var(--surface);border:1px solid var(--line);
        border-radius:12px;padding:10px 14px;margin-bottom:16px}
  .info-left{display:flex;align-items:center;gap:16px;flex:1;min-width:0;white-space:nowrap;overflow:hidden}
  .info-right{display:flex;align-items:center;gap:12px;flex-shrink:0;white-space:nowrap}
  .info-item b{color:var(--accent2);font-weight:600}
  .info-item.speed{color:var(--accent2);font-weight:600}.info-item.speed b{color:var(--ok)}
  .info-item.idle{color:var(--dim)}
  .info-item.temp-ok b{color:var(--ok)}.info-item.temp-warn b{color:var(--err)}
  .info-item.temp-off{color:var(--dim)}
  .info-item .throttle-badge{color:var(--err);font-weight:700}
  /* ── 헤더 통합 메뉴 (v0.41): 서버 설명 + 통계를 📊 아이콘 1개로 접는다 ── */
  .mm{position:absolute;top:calc(100% + 8px);right:0;z-index:60;box-sizing:border-box;
    width:min(520px,calc(100vw - 24px));max-height:min(78vh,720px);overflow-y:auto;overscroll-behavior:contain;
    background:var(--surface);border:1px solid var(--line2);border-radius:14px;
    box-shadow:0 16px 48px rgba(0,0,0,.55);padding:12px 14px;text-align:left}
  .mm[hidden]{display:none}
  .mm-h{font-size:11px;font-weight:700;color:var(--dim);text-transform:uppercase;
    letter-spacing:.5px;margin:2px 0 8px;display:flex;align-items:center;gap:6px}
  .mm-h .mm-hint{margin-left:auto;font-weight:500;text-transform:none;letter-spacing:0;font-size:11px}
  .mm-sec+.mm-sec{margin-top:14px;padding-top:12px;border-top:1px solid var(--line)}
  .mm-sec .info{margin-bottom:0}
  .mm-sg{background:var(--surface2);border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin-top:8px}
  .mm-sg .sh{font-size:13px;padding-left:8px;margin-bottom:6px}
  .mm-sg .sb{font-size:11px}
  .mm-sg .hint{font-size:11px;color:var(--dim);line-height:1.5;margin-top:2px}
  /* v0.41: #btnRefresh 와 **똑같은 metrics** 로 맞춘다 (padding 8px 13px · line-height 1).
     min-height:44px 를 base 에 두면 데스크톱에서 이 버튼만 34px 형제보다 10px 커져
     헤더가 어긋난다(사파리에서 더 두드러짐) — 터치 타깃 44px 은 모바일 쿼리로만 준다.
     display:flex + center 로 이모지 세로 정렬을 엔진에 의존하지 않게 한다
     (Safari 는 Apple Color Emoji 의 line box 가 텍스트 글리프와 달라 ⊙ 리듬이 어긋난다). */
  #btnInfoMenu{position:relative;display:flex;align-items:center;justify-content:center;
    min-width:40px;padding:8px 13px;font-size:16px;line-height:1}
  #btnInfoMenu.on{background:var(--accentbg);border-color:var(--accent);color:var(--accent2)}
  .mm-dot{position:absolute;top:5px;right:5px;width:7px;height:7px;border-radius:99px;background:var(--ok);
    box-shadow:0 0 0 2px var(--surface);pointer-events:none}
  .mm-dot.warn{background:var(--err)}
  .mm-dot[hidden]{display:none}
  /* 드롭다운 폭(520px)에 맞춘 통계 밀도 — 3열 고정 해제 후 auto-fit */
  #infoMenu #statsCards{grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px}
  #infoMenu #statsChart svg{min-width:420px}
  .row{display:flex;gap:8px}
  .row-torrent{display:flex;gap:8px;align-items:stretch}
  input[type=text],input[type=url]{flex:1;padding:11px 14px;border-radius:10px;border:1px solid var(--line2);background:var(--surface2);color:var(--text);font-size:14px}
  button{padding:11px 18px;border-radius:10px;border:0;background:var(--accent);color:var(--on-accent);font-weight:600;cursor:pointer;white-space:nowrap}
  button.ghost{background:#22335433;border:1px solid var(--line2);color:var(--muted);padding:6px 12px;font-weight:500}
  button.sm{padding:5px 10px;font-size:12px;border-radius:8px}
  .btn-dl{background:var(--okbg);border:1px solid #1A5C3A;color:var(--ok);padding:5px 10px;font-size:12px;border-radius:8px;font-weight:600;cursor:pointer;display:inline-flex;align-items:center;text-decoration:none}
  .card-acts{display:flex;flex-direction:column;gap:6px;width:112px;flex-shrink:0;padding:14px}
  .card-acts .ghost,.card-acts .btn-dl{box-sizing:border-box;width:100%;font-size:12px;padding:7px 0;justify-content:center}
  .card-acts select.ghost{text-align:center;appearance:auto}
  .file-row .acts a.btn-dl,.file-row .acts .ghost{box-sizing:border-box;width:34px;padding:5px 0;justify-content:center;text-align:center}
  .card{background:var(--surface);border:1px solid var(--line);border-radius:14px;margin-top:14px;display:flex;align-items:stretch}
  .name{font-weight:600;word-break:break-all}
  .meta{color:var(--muted);font-size:12px;margin-top:2px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  .bar{height:10px;background:var(--track);border-radius:99px;margin-top:8px;overflow:hidden}
  .fill{height:100%;background:linear-gradient(90deg,var(--accent),var(--accent2));border-radius:99px;width:0%;transition:width .4s}
  .badge{font-size:11px;padding:2px 8px;border-radius:99px;background:var(--line);display:inline-flex;align-items:center}
  .RUNNING{background:var(--accentbg);color:var(--accent2)}.DONE{background:var(--okbg);color:var(--ok)}.FAILED{background:var(--danger);color:var(--err)}
  .QUEUED{background:var(--line)}.PAUSED{background:#3A3312;color:var(--warn)}.CANCELED{background:#333}
  .STALLED{background:#1B2C3F;color:#7FB6E8}
  .badge.video{background:#0A3A2F;color:#6FE3C4}
  .badges{display:inline-flex;gap:6px;align-items:center}
  .overlay{position:fixed;inset:0;background:rgba(5,10,20,.62);backdrop-filter:blur(2px);display:flex;align-items:center;justify-content:center;z-index:9998}
  .popup{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:18px;max-width:340px;width:calc(100% - 40px);box-shadow:0 12px 40px rgba(0,0,0,.5)}
  .popup h3{margin:0 0 6px;font-size:15px;color:var(--text)}
  .popup .msg{color:var(--muted);font-size:13px;margin:0 0 14px;white-space:pre-line;word-break:break-word}
  .popup.wide{max-width:640px}
  .popup video{width:100%;max-height:70vh;background:#000;border-radius:8px}
  .popup input{width:100%;box-sizing:border-box;padding:9px 10px;border-radius:8px;border:1px solid var(--line2);background:var(--surface);color:var(--text);font-size:14px;margin:0 0 14px;outline:none}
  .popup input:focus{border-color:var(--accent)}
  .popup .pbtns{display:flex;gap:8px;justify-content:flex-end}
  .popup .pbtns button{padding:7px 16px;border-radius:8px;border:1px solid var(--line2);background:var(--surface);color:var(--text);font-size:13px;cursor:pointer}
  .popup .pbtns button.ok{background:var(--accent);border-color:var(--accent);color:var(--on-accent);font-weight:600}
  .popup .pbtns button.danger{background:var(--danger);border-color:var(--danger);color:var(--err);font-weight:600}
  .vcard{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:12px;margin-top:10px}
  .err{color:var(--err);font-size:12px;margin-top:4px}
  .empty{color:var(--dim);text-align:center;padding:26px 0}
  .speed{color:var(--accent2);font-weight:600}
  .eta{color:var(--muted);font-size:12px}
  .tabs{grid-column:1;grid-row:2;display:flex;flex-direction:column;gap:4px;margin:0;position:sticky;top:20px}
  .tab{padding:11px 14px;border-radius:14px;border:1px solid transparent;background:transparent;color:var(--muted);text-align:left;
       font-weight:600;font-size:14px;cursor:pointer;transition:background .15s,color .15s}
  .tab:hover{background:var(--surface2)}
  .tab.active{background:var(--accentbg);border-color:transparent;color:var(--text)}
  .panel{display:none;grid-column:2;grid-row:2;min-width:0}.panel.active{display:block}
  .file-icon{font-size:20px;margin-right:8px;flex-shrink:0}
  .file-thumb{width:40px;height:40px;object-fit:cover;border-radius:8px;margin-right:8px;flex:none;background:var(--bg)}
  .file-meta{color:var(--muted);font-size:12px;margin-top:2px}
  input[type=file]{display:none}
  .breadcrumb{display:flex;align-items:center;gap:4px;margin-bottom:12px;color:var(--muted);font-size:13px;flex-wrap:wrap}
  .breadcrumb span{cursor:pointer;color:var(--accent2)}
  .breadcrumb span:hover{text-decoration:underline}
  #breadcrumb span.drop-target{outline:2px dashed var(--accent);outline-offset:2px;border-radius:4px;background:var(--sel)}
  .file-row{display:flex;align-items:center;gap:10px;padding:10px 14px;background:var(--surface);border:1px solid var(--line);border-radius:12px;margin-top:8px;
            cursor:pointer;transition:border-color .15s}
  .file-row:hover{border-color:var(--accent)}
  .file-row.selected{border-color:var(--accent);background:var(--sel)}
  .file-row[draggable=true]{cursor:grab}
  .file-row.dragging{opacity:.45}
  .file-row.drop-target{outline:2px dashed var(--accent);outline-offset:-2px;background:var(--sel)}
  .card,.file-row{user-select:none;-webkit-user-select:none}
  .card{position:relative}
  .card.dragging{opacity:.4}
  /* ── 드롭 대상 표시 (v0.41) ──
     보관함은 "이 폴더로 이동"이라 행 전체가 목적지 → 사각 점선만으로 충분했다.
     다운로드·토렌트는 "순서 변경"이라 위/아래 어디에 끼워 넣는지까지 알려야 해서
     사각 점선(어느 카드) + 굵은 삽입선(어느 위치) 을 함께 쓴다. */
  .card.drop-before,.card.drop-after{outline:2px dashed var(--accent);outline-offset:-2px;background:var(--sel)}
  .card.drop-before::before,.card.drop-after::before{content:'';position:absolute;left:8px;right:8px;height:3px;
    border-radius:99px;background:var(--accent2);box-shadow:0 0 8px var(--accent2)}
  .card.drop-before::before{top:-2px}
  .card.drop-after::before{bottom:-2px}
  /* 카드 바깥 빈 공간에 떨궈면 순서변경이 안 된다 — 그 사실을 미리 보여준다 */
  #list.drop-empty,#torrentList.drop-empty{outline:2px dashed var(--line2);outline-offset:-4px;border-radius:16px}
  .file-row .acts{margin-left:auto;display:flex;gap:6px;flex-shrink:0}
  .toolbar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
  .toolbar button{font-size:13px;padding:8px 14px}
  #storageSort{-webkit-appearance:none;appearance:none;flex-shrink:0;background-color:var(--surface2);border:1px solid var(--line2);color:var(--text);border-radius:10px;
    padding:8px 26px 8px 10px;font-size:13px;line-height:1.4;outline:none;cursor:pointer;
    background-image:url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M1 1l4 4 4-4' fill='none' stroke='%239AA7BD' stroke-width='1.6' stroke-linecap='round'/%3E%3C/svg%3E");
    background-repeat:no-repeat;background-position:right 9px center}
  #storageSort:focus{border-color:var(--accent)}
  #storageSort option{background-color:var(--surface2);color:var(--text)}
  .drop-zone{border:2px dashed var(--line2);border-radius:12px;padding:24px;text-align:center;color:var(--dim);margin-top:12px;transition:all .2s}
  .drop-zone.dragover{border-color:var(--accent);background:var(--surface2);color:var(--accent2)}
  .modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:1000;justify-content:center;align-items:flex-start;padding:60px 20px;overflow-y:auto}
  .modal-overlay.show{display:flex}
  .modal{background:var(--surface);border:1px solid var(--line2);border-radius:14px;width:100%;max-width:600px;max-height:80vh;overflow-y:auto;box-shadow:0 20px 60px rgba(0,0,0,.5)}
  .modal-header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid var(--line2);position:sticky;top:0;background:var(--surface);z-index:1}
  .modal-header h3{margin:0;font-size:15px;color:var(--text);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-close{background:none;border:none;color:var(--dim);font-size:20px;cursor:pointer;padding:4px 8px;border-radius:6px;transition:all .15s}
  .modal-close:hover{background:var(--line);color:var(--text)}
  .modal-body{padding:16px 20px}
  .modal-section{margin-bottom:16px}
  .modal-section-title{font-size:12px;font-weight:700;color:var(--dim);text-transform:uppercase;letter-spacing:.5px;margin-bottom:8px}
  .modal-stat{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
  .modal-stat-item{background:var(--surface2);border-radius:8px;padding:10px 12px}
  .modal-stat-label{font-size:11px;color:var(--dim);margin-bottom:2px}
  .modal-stat-value{font-size:14px;font-weight:600;color:var(--text)}
  .modal-stat-value.green{color:#6a8}
  .modal-stat-value.red{color:#f86}
  .modal-stat-value.blue{color:var(--accent2)}
  .modal-peer-list{max-height:200px;overflow-y:auto}
  .modal-peer-row{display:flex;align-items:center;padding:6px 8px;border-radius:6px;font-size:12px;color:var(--dim);gap:8px}
  .modal-peer-row:nth-child(odd){background:var(--surface2)}
  .modal-peer-ip{color:var(--accent2);font-weight:600;min-width:120px}
  .modal-peer-client{color:var(--dim);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-peer-speed{color:#6a8;min-width:70px;text-align:right}
  .modal-file-row{display:flex;align-items:center;padding:6px 8px;border-radius:6px;font-size:12px;color:var(--dim);gap:8px}
  .modal-file-row:nth-child(odd){background:var(--surface2)}
  .modal-file-path{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .modal-file-size{color:var(--dim);min-width:60px;text-align:right}
  .modal-refresh{background:var(--line);border:1px solid var(--line2);color:var(--muted);padding:6px 12px;border-radius:8px;cursor:pointer;font-size:12px;transition:all .15s}
  .modal-refresh:hover{background:var(--line2);color:var(--text)}
  .card{cursor:pointer}
  #treePanel{width:172px;flex-shrink:0;background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:8px;max-height:62vh;overflow-y:auto;font-size:13px}
  .tree-title{font-size:11px;font-weight:700;color:var(--dim);text-transform:uppercase;letter-spacing:.5px;padding:2px 8px 6px}
  .tree-item{padding:5px 8px;border-radius:6px;cursor:pointer;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .tree-item:hover{background:var(--sel);color:var(--text)}
  .tree-item.active{background:var(--accent);color:var(--on-accent);font-weight:600}
  .tree-children{padding-left:12px;border-left:1px solid var(--track);margin-left:8px}
  .tree-empty{color:var(--dim);font-size:12px;padding:4px 8px}
  /* ── 보관함 콘텐츠 (설정 스타일 통일) ── */
  .storage-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:12px}
  .storage-main .breadcrumb{margin-bottom:8px}
  .storage-main .toolbar{margin-bottom:0}
  #fileList .file-row{border-radius:6px;border:1px solid transparent;background:transparent;padding:10px 10px;margin-top:0;border-bottom:1px solid var(--track)}
  #fileList .file-row:last-child{border-bottom-color:transparent}
  #fileList .file-row:hover{border-color:transparent;background:var(--sel)}
  #fileList .file-row.selected{background:var(--sel);box-shadow:inset 3px 0 0 var(--accent)}
  #panel-storage .empty{padding:22px 8px 6px}
  /* ── 설정 탭 전용 ── */
  .sg{background:var(--surface);border:1px solid var(--line);border-radius:14px;padding:16px}
  .sh{font-size:14px;font-weight:700;color:var(--accent2);padding-left:10px;border-left:3px solid var(--accent);margin-bottom:14px}
  .coll-h{cursor:pointer;user-select:none;-webkit-user-select:none;display:flex;align-items:center;justify-content:space-between}
  .coll-h .arrow{display:inline-block;transition:transform .15s;color:var(--muted);font-size:12px}
  .coll-h.open .arrow{transform:rotate(90deg)}
  .coll-b{display:none}
  .sr{display:flex;flex-wrap:wrap;gap:16px}
  .si{flex:1;min-width:220px}
  .sl{display:block;margin-bottom:5px;font-size:12px;color:var(--muted);font-weight:500}
  .sv{display:flex;align-items:center;gap:8px}
  .sv span:last-child{min-width:60px;text-align:right;font-weight:600;font-size:13px}
  .sv input[type=range]{flex:1;accent-color:var(--accent)}
  .ck{display:flex;align-items:center;gap:8px}
  .ck input[type=checkbox]{width:18px;height:18px;accent-color:var(--accent)}
  .ck label{font-size:13px;color:var(--text);cursor:pointer}
  .sb{font-size:11px;color:var(--dim);margin-top:3px;line-height:1.4}
  .ti{font-weight:600;font-size:13px;color:var(--text);margin:12px 0 6px}
  .ti:first-child{margin-top:0}
  .ss{font-size:12px;padding:8px 10px;border-radius:8px;background:var(--surface2);color:var(--dim);margin-top:8px;line-height:1.5}
  .ft{display:flex;align-items:center;gap:8px}
  .ft input[type=number]{width:90px;padding:7px 10px;border-radius:8px;border:1px solid var(--line2);background:var(--surface2);color:var(--text);font-size:13px}
  .fp{display:flex;gap:8px}
  .fp input[type=text]{flex:1;padding:8px 12px;border-radius:8px;border:1px solid var(--line2);background:var(--surface2);color:var(--text);font-size:13px}
  .rb{display:flex;gap:8px;flex-wrap:wrap}
  .rb button{flex-shrink:0}
  /* ── 설정 사이드바 ── */
  .settings-layout{display:flex;gap:12px;align-items:flex-start}
  .settings-nav{width:140px;flex-shrink:0;background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:8px;font-size:13px}
  .settings-nav-title{font-size:11px;font-weight:700;color:var(--dim);text-transform:uppercase;letter-spacing:.5px;padding:2px 8px 6px}
  .settings-nav-item{padding:6px 10px;border-radius:6px;cursor:pointer;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;transition:all .15s}
  .settings-nav-item:hover{background:var(--sel);color:var(--text)}
  .settings-nav-item.active{background:var(--accent);color:var(--on-accent);font-weight:600}
  .settings-content{flex:1;min-width:0;display:none}
  .settings-content.active{display:block}
  /* ── 통계 탭 전용 (v0.37.1: 3열 grid, .card 간섭 분리) ── */
  #statsCards{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}
  .stats-card{min-width:0;background:var(--surface2);border:1px solid var(--line);border-radius:10px;padding:10px 12px;overflow:hidden}
  .stats-card .s-title{font-weight:700;font-size:13px;color:var(--accent2);margin-bottom:4px}
  .stats-card .s-total{font-weight:700;font-size:13px;line-height:1.6;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .stats-card .s-sub{font-size:11px;color:var(--muted);line-height:1.55;word-break:keep-all;overflow-wrap:anywhere;white-space:normal}
  #statsChart{overflow-x:auto}
  #statsChart svg{display:block;min-width:520px;max-width:100%}
  #statsBreakdown{word-break:keep-all;overflow-wrap:anywhere;white-space:normal;line-height:1.6}
  /* ── 모바일 대응 (640px 이하) ── */
  @media (max-width:640px){
    .wrap{min-width:0;padding:12px}
    h1{font-size:18px}
    .info{flex-direction:column;align-items:stretch;gap:8px}
    .info-left,.info-right{flex-wrap:wrap;gap:8px 14px;white-space:normal;overflow:visible}
    .card{flex-direction:column}
    .card-acts{width:100%;box-sizing:border-box;flex-direction:row;gap:6px;padding:0 14px 14px}
    .card-acts .ghost,.card-acts .btn-dl{flex:1;width:auto;padding:8px 0}
    .name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;word-break:normal;max-width:100%}
    #panel-storage>div{flex-direction:column}
    #treePanel{display:none}
    .storage-main{width:100%}
    .settings-layout{flex-direction:column}
    .settings-nav{width:100%;display:flex;flex-wrap:nowrap;overflow-x:auto;gap:6px;padding:0 12px 6px;box-sizing:border-box;-webkit-overflow-scrolling:touch}
    .settings-nav .settings-nav-title{display:none}
    .settings-nav .settings-nav-item{flex-shrink:0;white-space:nowrap}
    .settings-content{width:100%}
    .si{min-width:0}
    .sr{flex-direction:column;gap:12px}
    .row,.row-torrent{flex-wrap:wrap}
    .row input,.row-torrent input{flex:1 1 100%!important;min-width:0}
    .row-torrent button{flex:1}
    #panel-dl>.row{flex-wrap:nowrap}
    #panel-dl>.row input{flex:1 1 auto!important;min-width:0}
    .row button{flex-shrink:0}
    .tabs{position:fixed;left:0;right:0;bottom:0;z-index:55;flex-direction:row;gap:0;
      background:var(--surface);border-top:1px solid var(--line);padding:6px 8px calc(6px + env(safe-area-inset-bottom));top:auto}
    .tab{flex:1;text-align:center;padding:10px 4px;font-size:12.5px;border-radius:12px}
    .wrap{display:block;padding-bottom:92px}
    .hd{flex-wrap:wrap;margin-bottom:12px}
    #btnRefresh{padding:12px 16px;min-height:44px}
    #btnInfoMenu{padding:12px 16px;min-height:44px;min-width:44px}
    #themeSel{min-height:44px;max-width:130px}
    body[data-theme=toxic] h1,body[data-theme=midnight] h1{text-shadow:none}
    body[data-theme=toxic] .card,body[data-theme=toxic] .file-row,
    body[data-theme=midnight] .card,body[data-theme=midnight] .file-row,
    body[data-theme=toxic] .tab.active,body[data-theme=midnight] .tab.active{box-shadow:none}
    .fill{animation:none}
    .fp{flex-wrap:wrap}
    .fp input[type=text]{flex:1 1 100%;min-width:0}
    .sv input[type=range]{min-width:0}
    .ck{flex-wrap:wrap}
    .modal-stat{grid-template-columns:repeat(2,1fr)}
    #statsCards{grid-template-columns:repeat(3,1fr);gap:6px}
    #infoMenu #statsCards{grid-template-columns:repeat(auto-fit,minmax(118px,1fr));gap:6px}
    .mm{width:calc(100vw - 24px);max-height:72vh;padding:10px 12px}
    #infoMenu #statsChart svg{min-width:380px}
    .mm-sec+.mm-sec{margin-top:12px;padding-top:10px}
    .stats-card{padding:8px}
    .stats-card .s-title{font-size:12px}
    .stats-card .s-total{font-size:12px}
    .stats-card .s-sub{font-size:10px}
    select{max-width:100%}
  }"""
    }
}
