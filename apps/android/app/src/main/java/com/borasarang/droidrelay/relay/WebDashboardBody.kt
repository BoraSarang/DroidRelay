package com.borasarang.droidrelay.relay

/// 대시보드 **본문 마크업** 조각.
///
/// 원래 `WebDashboardHtml.kt` 한 파일(3041줄)에 CSS·마크업·JS 가 뒤섞여 있었다.
/// 관심사별로 셋을 갈랐다 — 이 파일은 `</style></head><body>` 이후 본문 요소만 담는다.
/// **내용은 한 글자도 바꾸지 않았다**(`WebAssets.dashboardHtml` 이 공개 계약).
internal object WebDashboardBody {
    val content: String by lazy {
        """</style></head><body>
<div class="wrap">
  <div class="hd"><h1>📡 DroidRelay</h1><div class="hd-acts">
    <button id="btnInfoMenu" class="ghost" onclick="toggleInfoMenu()" title="서버 상태 · 통계 (S)" aria-haspopup="true" aria-expanded="false" aria-controls="infoMenu">📊<i class="mm-dot" id="infoMenuDot" hidden></i></button>
    <!-- v0.41: 헤더 통합 메뉴 — 서버 설명(#info) + 트래픽 통계를 📊 아이콘 드롭다운으로.
         앵커는 `.hd-acts` 다.
         (1) `.wrap` 하단에 두면 position:absolute 가 초기 포함 블록(문서)을 잡아 화면 아래로 튀어나간다.
         (2) 버튼 44px짜리 래퍼에 두면 좁은 화면에서 좌측으로 넘친다(실측 390px 뷰포트 → left -155px).
             `right:0` 이 앵커의 우측 끝(=버튼 우측)에 걸리므로 화면 우측 끝에 붙여야 한다. -->
  <div class="mm" id="infoMenu" hidden>
    <div class="mm-sec">
      <div class="mm-h">📡 서버 상태<span class="mm-hint" id="infoMenuAt"></span></div>
      <div class="info" id="info">서버 정보 로딩 중…</div>
    </div>
    <div class="mm-sec">
      <div class="mm-h">📊 트래픽 통계</div>
      <div class="mm-sg">
        <div class="hint">일별 · 이번달 · 누적 업/다운로드 (서빙+토렌트 업로드 포함)</div>
        <div id="statsCards"></div>
      </div>
      <div class="mm-sg">
        <div class="sh">✨ 하이라이트</div>
        <div class="hint">최고 기록 · 평균 · 예측 · 비율 (400일 기준 연산)</div>
        <div id="statsHighlights" style="display:grid;grid-template-columns:repeat(2,1fr);gap:6px;margin-top:8px"></div>
      </div>
      <div class="mm-sg">
        <div class="sh">📈 최고속도 · 완료 · 기기</div>
        <div class="hint">일별 peak · 완료/실패 건수 · 피어 · 가동 · 저장공간 · 단절/스로틀</div>
        <div id="statsRecords" style="display:grid;grid-template-columns:repeat(2,1fr);gap:6px;margin-top:8px"></div>
      </div>
      <div class="mm-sg">
        <div class="sh">최근 30일 일별 그래프 <span class="hint" style="display:inline">■다운 ■업</span></div>
        <div id="statsChart"></div>
        <div class="hint" id="statsBreakdown" style="margin-top:8px"></div>
      </div>
    </div>
  </div>
    <button id="btnRefresh" class="ghost" onclick="manualRefresh()" title="새로고침 (R)">⟳</button><select id="themeSel" onchange="setTheme(this.value)" title="테마"><option value="toxic">⚡ Toxic</option><option value="midnight">🌌 Midnight</option><option value="navy">🌊 Navy</option></select></div></div>

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

    <!-- 비디오 분석 섹션 (접이식, 기본 접힘) -->
    <div class="sg" id="videoSection" style="margin:14px 0 10px;padding:14px;background:var(--surface);border:1px solid var(--line);border-radius:12px">
      <div class="sh coll-h" id="videoHead" onclick="toggleColl('videoBody','videoHead')"><span>🎬 비디오 분석 & 다운로드</span><span class="arrow">▶</span></div>
      <div class="coll-b" id="videoBody">
      <div class="row" style="margin-top:10px">
        <input id="vurl" type="url" placeholder="스트림 페이지 또는 m3u8/mpd 직접 주소" style="flex:1">
        <button class="ghost" onclick="analyzeVideo()">분석</button>
      </div>
      <div class="row" style="margin-top:6px">
        <input id="vref" type="url" placeholder="Referer (선택, 차단 사이트용)" style="flex:1">
        <input id="vcookie" type="password" placeholder="Cookie (선택, 저장 안 됨)" style="flex:1" autocomplete="off">
      </div>
      <div id="videoArea" style="margin-top:10px"></div>
      </div>
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
    <div id="tsearchSection">
      <div class="sh coll-h" id="tsearchHead" onclick="toggleColl('tsearchBody','tsearchHead','tsearch')" style="margin-top:14px"><span>🔍 토렌트 검색</span><span class="arrow">▶</span></div>
      <div class="coll-b" id="tsearchBody">
    <div class="row-torrent" style="margin-top:8px">
      <input id="tsearch" placeholder="토렌트 검색 (설정에서 Jackett/Prowlarr 입력)" style="flex:1">
      <button onclick="searchTorrents()">검색</button>
    </div>
    <div id="searchResults"></div>
      </div>
    </div>
    <div id="torrentInfo" style="padding:10px 16px;font-size:13px;color:var(--dim);border-bottom:1px solid var(--text)"></div>
    <div id="torrentList"></div>
    <div class="empty" id="torrentEmpty">토렌트 작업이 없습니다</div>
  </div>

  <!-- 보관함 탭 -->
  <div class="panel" id="panel-storage">
    <div style="display:flex;gap:12px;align-items:flex-start">
      <div id="treePanel"></div>
      <div class="storage-main">
        <div class="sg">
          <div class="sh">🗂 파일 관리</div>
          <div class="breadcrumb" id="breadcrumb"></div>
          <div class="toolbar" id="storageToolbar">
            <button class="ghost" onclick="createFolder()">📁 폴더 만들기</button>
            <button class="ghost" onclick="document.getElementById('uploadFile').click()">⬆ 파일 올리기</button>
            <span class="sb" id="uploadProgressLabel" style="flex-shrink:0;color:var(--accent2)"></span>
            <input type="file" id="uploadFile" multiple onchange="uploadFiles(this)">
            <button class="ghost" onclick="deleteSelected()" id="deleteBtn" style="display:none">🗑 삭제</button>
            <select id="storageSort" onchange="setStorageSort(this.value)" title="정렬 기준">
              <option value="dir">📁 디렉토리순</option>
              <option value="recent">🕐 최신순</option>
              <option value="name">🔤 이름순</option>
              <option value="size">💾 용량순</option>
            </select>
            <button class="ghost" onclick="toggleTrash()" style="margin-left:auto">🗑️ 휴지통</button>
          </div>
          <div class="toolbar" id="trashToolbar" style="display:none">
            <button class="ghost" onclick="toggleTrash()">← 보관함 돌아가기</button>
            <button class="ghost" id="trashEmptyBtn" onclick="emptyTrash()" style="color:var(--err);border-color:var(--danger)">🔥 비우기</button>
          </div>
        </div>
        <div class="sg">
          <div class="sh" id="storageListTitle">📄 폴더 내용</div>
          <div id="fileList"></div>
          <div class="empty" id="fileEmpty">비어 있습니다</div>
        </div>
      </div>
    </div>
  </div>

    <!-- 설정 탭 -->
  <div class="panel" id="panel-settings">
    <div class="settings-layout">
      <div class="settings-nav" id="settingsNav">
        <div class="settings-nav-title">설정</div>
        <div class="settings-nav-item active" onclick="switchSettingsSection('global')">⚡ 전역</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('download')">⬇ 다운로드</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('torrent')">🌊 토렌트</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('rss')">📡 RSS 피드</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('debrid')">☁️ Debrid</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('tunnel')">🔗 터널</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('guard')">🛡 가드</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('mcp')">🤖 MCP</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('schedule')">⏰ 스케줄</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('storage')">💾 스토리지</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('debug')">🐛 디버그</div>
        <div class="settings-nav-item" onclick="switchSettingsSection('reset')">↩ 기본값</div>
      </div>

      <div class="settings-content active" id="settings-global">
        <div class="sg">
          <div class="sh">⚡ 전역 속도 제한</div>
          <div class="sr">
            <div class="si">
              <div class="sl">다운로드 제한</div>
              <div class="sv">
                <input type="checkbox" id="dlSpeedEnabled" onchange="toggleSpeedLimit('dl')">
                <select id="maxDownloadMbps" disabled></select>
                <span id="maxDownloadLabel">3MB/s</span>
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
      </div>

      <div class="settings-content" id="settings-download">
        <div class="sg">
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
                <select id="speedLimitKbps"></select>
                <span id="speedLimitLabel">무제한</span>
              </div>
              <div class="sb">프리셋 단위 · 0 = 무제한 · 전역 제한과 별도 작동</div>
            </div>
          </div>
          <div class="ck" style="margin-top:12px">
            <input type="checkbox" id="notifications" checked>
            <label for="notifications">다운로드 알림 표시 (앱에서만 적용)</label>
          </div>
          <div class="sr" style="margin-top:12px">
            <div class="si">
              <div class="sl">보관함 쿼터</div>
              <div class="sv">
                <input type="range" id="storageQuotaGb" min="0" max="128" value="0" step="1">
                <span id="storageQuotaLabel">끔</span>
              </div>
              <div class="sb">GB 단위 · 0 = 끔 · 초과 시 오래된 파일부터 자동 휴지통</div>
            </div>
          </div>
          <div class="ck" style="margin-top:12px">
            <input type="checkbox" id="autoClassify">
            <label for="autoClassify">완료 파일 자동 분류 (영상/음악/문서 폴더)</label>
          </div>
          <div class="ck" style="margin-top:12px">
            <input type="checkbox" id="lowPowerMode">
            <label for="lowPowerMode">저전력 모드 (동시 1개 + little 클러스터 우선 — S22 서버폰용)</label>
          </div>
          <div class="ti">완료 후 동작</div>
          <div class="sv">
            <select id="completionAction">
              <option value="none">없음</option>
              <option value="stop_server">전체 완료 시 서버 정지</option>
            </select>
          </div>
          <div class="sb" style="margin-top:4px">모든 다운로드·토렌트가 끝나면 서버를 정지합니다 (시딩 중 제외)</div>
          <div class="ti">유휴 자동 정지 (분)</div>
          <div class="sv">
            <input type="number" id="idleTimeoutMin" min="0" max="480" value="30" style="max-width:100px">
          </div>
          <div class="sb" style="margin-top:4px">0 = 끔 · 작업·전송·토렌트 활동이 없으면 서버 정지 (시딩 중 제외)</div>
          <div class="ti">속도 스케줄 (요일+시간)</div>
          <div id="speedSchedList" class="sb"></div>
          <div class="fp" style="margin-top:6px;flex-wrap:wrap">
            <label class="sb">월<input type="checkbox" class="ssd" value="2" checked></label>
            <label class="sb">화<input type="checkbox" class="ssd" value="3" checked></label>
            <label class="sb">수<input type="checkbox" class="ssd" value="4" checked></label>
            <label class="sb">목<input type="checkbox" class="ssd" value="5" checked></label>
            <label class="sb">금<input type="checkbox" class="ssd" value="6" checked></label>
            <label class="sb">토<input type="checkbox" class="ssd" value="7"></label>
            <label class="sb">일<input type="checkbox" class="ssd" value="1"></label>
            <input type="time" id="ssStart" value="00:00" style="max-width:110px">
            <span class="sb">~</span>
            <input type="time" id="ssEnd" value="06:00" style="max-width:110px">
            <input type="number" id="ssDown" min="0" max="1000000" value="0" placeholder="다운KB/s" style="max-width:110px">
            <input type="number" id="ssUp" min="0" max="1000000" value="0" placeholder="업KB/s" style="max-width:100px">
            <button class="ghost sm" onclick="addSpeedWindow()">+ 추가</button>
          </div>
          <div class="sb" style="margin-top:4px">창 진입 시 전역 제한으로 적용, 이탈 시 수동값 복원 · 0 = 무제한 · 최대 10개</div>
        </div>
      </div>

      <div class="settings-content" id="settings-torrent">
        <div class="sg">
          <div class="sh">🌊 토렌트 설정</div>
          <div class="ti">속도</div>
          <div class="sr">
            <div class="si">
              <div class="sl">업로드 속도</div>
              <div class="sv">
                <input type="range" id="torrentUploadLimit" min="0" max="1024" value="512" step="32">
                <span id="torrentUploadLabel" style="color:#f96">512 KB/s</span>
              </div>
              <div class="sb">KB/s 단위 · 0 = 업로드 안 함 (피어 평판 저하 유의)</div>
            </div>
            <div class="si">
              <div class="sl">다운로드 속도</div>
              <div class="sv">
                <select id="torrentDownloadLimit"></select>
                <span id="torrentDownloadLabel">무제한</span>
              </div>
              <div class="sb">프리셋 단위 · 0 = 무제한 · 전역 제한과 별도 작동</div>
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
              <div class="sb">받은 양 대비 업로드 비율 도달 시 자동 일시정지 · 0 = 제한 없음</div>
            </div>
          </div>
          <div class="sr" style="margin-top:4px">
            <button class="ghost sm" onclick="minimizeUpload()">⬇ 업로드 최소화 (비율 0.5 + 업로드 32KB/s + DHT 끔)</button>
          </div>
          <div class="ti">고급</div>
          <div class="ck" style="gap:20px">
            <div class="ck"><input type="checkbox" id="torrentEnabled" checked><label for="torrentEnabled">토렌트 기능 (끄면 세션·포트·폴링 미기동)</label></div>
            <div class="ck"><input type="checkbox" id="torrentRemoveAfterMove"><label for="torrentRemoveAfterMove">완료 후 세션에서 제거 (시딩 안 함)</label></div>
          </div>
          <div class="ck" style="gap:20px">
            <div class="ck"><input type="checkbox" id="torrentDhtEnabled" checked><label for="torrentDhtEnabled">DHT (분산 해시 테이블)</label></div>
            <div class="ck"><input type="checkbox" id="torrentPexEnabled" checked><label for="torrentPexEnabled">PEX (피어 교환)</label></div>
            <div class="ck"><input type="checkbox" id="torrentSequentialDownload"><label for="torrentSequentialDownload">시퀀셜 다운로드 (스트리밍 프리뷰)</label></div>
          </div>
          <div class="sb" style="margin-top:4px">시퀀셜: 첫 번째 조각부터 순서대로 다운로드하여 재생 미리보기 지원</div>
          <div class="sb" style="margin-top:4px">PEX: 피어 탐색용으로 트래픽은 미미합니다. 끄면 DHT·트래커로만 피어를 찾습니다</div>
          <div class="ti">정체 torrent 회전</div>
          <div class="ck"><input type="checkbox" id="torrentStallEnabled" checked><label for="torrentStallEnabled">정체 감지 → 자동 일시정지 + 순서 맨뒤로</label></div>
          <div class="sr">
            <div class="si">
              <div class="sl">정체 기준</div>
              <div class="sv"><select id="torrentStallThresholdKbps"></select></div>
              <div class="sb">이 속도 이하(또는 시더 0)가 지속되면 정체로 봅니다</div>
            </div>
            <div class="si">
              <div class="sl">정체 지속 시간</div>
              <div class="sv"><select id="torrentStallTimeoutSec"></select></div>
              <div class="sb">이 시간이 지나면 다음 torrent가 받도록 자리를 넘깁니다 (최대 활성 수 범위)</div>
            </div>
          </div>
          <div class="ti">리슨 포트</div>
          <div class="ft">
            <input type="number" id="torrentListenPort" min="1024" max="65535" value="6881">
            <button class="ghost sm" onclick="randomizePort()">🎲 랜덤</button>
          </div>
          <div class="sb" style="margin-top:4px">변경 시 토렌트 엔진 재시작 필요</div>
          <div class="ti">저장 경로</div>
          <div class="fp">
            <input type="text" id="torrentSavePath" value="${com.borasarang.droidrelay.relay.StorageGuard.dlRoot.path}">
            <button class="ghost sm" onclick="testPath()">테스트</button>
          </div>
          <div id="pathTestResult" class="sb"></div>
          <div class="ti">토렌트 검색 (Jackett/Prowlarr)</div>
          <div class="ck"><input type="checkbox" id="searchEnabled"><label for="searchEnabled">검색 사용</label></div>
          <div class="fp" style="margin-top:6px">
            <input type="text" id="searchUrl" placeholder="http://서버:9117">
            <input type="text" id="searchApiKey" placeholder="API 키" style="max-width:180px">
            <button class="ghost sm" onclick="saveSearchSettings()">저장</button>
          </div>
          <div class="sb" style="margin-top:4px">Torznab 경로(/api/v2.0/...)는 자동 추가. 토렌트 탭 검색창이 활성화됩니다</div>
          <div class="ti">트래커 자동 동기</div>
          <div class="ck"><input type="checkbox" id="torrentTrackerSync" checked><label for="torrentTrackerSync">커뮤니티 트래커 목록 자동 동기 (24시간)</label></div>
          <div class="fp" style="margin-top:6px">
            <button class="ghost sm" onclick="refreshTrackers()">🔄 지금 동기화</button>
            <button class="ghost sm" onclick="probeTrackers()">📡 도달 측정</button>
            <span class="sb" id="trackerCountLabel"></span>
          </div>
        </div>
      </div>

      <div class="settings-content" id="settings-rss">
        <div class="sg">
          <div class="sh">📡 RSS/Atom 피드 자동 다운로드</div>
          <div class="sr">
            <div class="si">피드 URL</div>
            <div class="fp">
              <input type="text" id="rssFeedUrl" placeholder="https://example.com/feed.xml">
              <button class="ghost sm" onclick="addRssFeed()">+ 추가</button>
            </div>
          </div>
          <div class="sr">
            <div class="si">이름 (선택)</div>
            <div class="fp">
              <input type="text" id="rssFeedName" placeholder="피드 이름">
            </div>
          </div>
          <div class="sr">
            <div class="si">키워드 필터</div>
            <div class="fp">
              <input type="text" id="rssFilterKeyword" placeholder="제목에 포함될 키워드 (선택)">
            </div>
          </div>
          <div class="sr">
            <div class="si">정규식 필터</div>
            <div class="fp">
              <input type="text" id="rssFilterRegex" placeholder="정규식 패턴 (선택)">
            </div>
          </div>
          <div class="sb" style="margin-top:4px">15분마다 새 항목 확인 → 키워드/정규식 매칭 시 자동 다운로드</div>
          <div id="rssFeedList" class="sb" style="margin-top:8px"></div>
          <div class="rb" style="margin-top:8px">
            <button class="ghost sm" onclick="checkRssFeeds()">🔍 지금 확인</button>
          </div>
        </div>
      </div>

      <div class="settings-content" id="settings-debrid">
        <div class="sg">
          <div class="sh">☁️ Debrid 클라우드 다운로드</div>
          <div class="ck">
            <input type="checkbox" id="debridEnabled">
            <label for="debridEnabled">Debrid 활성화</label>
          </div>
          <div class="sb">클라우드 다운로드 서비스를 통해 고속 다운로드 지원</div>
          <div class="ti">제공자</div>
          <div class="rb">
            <button class="ghost" id="debridRealDebrid" onclick="setDebridProvider('REALDEBRID')">Real-Debrid</button>
            <button class="ghost" id="debridAllDebrid" onclick="setDebridProvider('ALLDEBRID')">AllDebrid</button>
            <button class="ghost" id="debridPremiumize" onclick="setDebridProvider('PREMIUMIZE')">Premiumize</button>
          </div>
          <div class="ti">API 키</div>
          <div class="fp">
            <input type="text" id="debridApiKey" placeholder="API 키 입력">
            <button class="ghost sm" onclick="saveDebridSettings()">저장</button>
          </div>
          <div class="sb">제공자 웹사이트에서 API 키를 발급받아 입력하세요</div>
          <div class="rb" style="margin-top:12px">
            <button class="ghost sm" onclick="checkDebridAccount()">🔍 계정 확인</button>
          </div>
          <div id="debridStatus" class="sb" style="margin-top:8px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-tunnel">
        <div class="sg">
          <div class="sh">🔗 터널 (외부 접속)</div>
          <div class="ck">
            <input type="checkbox" id="tunnelEnabled">
            <label for="tunnelEnabled">터널 사용</label>
          </div>
          <div class="sb">Tailscale/Cloudflare Tunnel로 기기를 외부 네트워크에서 접속 가능하게 합니다</div>
          <div class="ti">제공자</div>
          <div class="rb">
            <button class="ghost" id="tunnelTailscale" onclick="highlightTunnelProvider('TAILSCALE')">Tailscale</button>
            <button class="ghost" id="tunnelCloudflare" onclick="highlightTunnelProvider('CLOUDFLARE')">Cloudflare</button>
          </div>
          <div class="sb">Tailscale: 100.x.y.z IP 자동 감지 · Cloudflare: cloudflared 바이너리와 빠른 접속 설정 필요</div>
          <div class="rb" style="margin-top:12px">
            <button class="ghost sm" onclick="saveTunnelSettings()">저장</button>
            <button class="ghost sm" onclick="checkTunnelStatus()">🔍 현재 상태</button>
          </div>
          <div id="tunnelStatus" class="sb" style="margin-top:8px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-guard">
        <div class="sg">
          <div class="sh">🛡 가드 데몬 (서버 보호)</div>
          <div class="ck">
            <input type="checkbox" id="guardEnabled">
            <label for="guardEnabled">가드 데몬 활성화</label>
          </div>
          <div class="sb">열/배터리/스토리지 임계치 초과 시 자동 스로틀링</div>
          <div class="ti">임계치 설정</div>
          <div class="sr">
            <div class="si">
              <div class="sl">열 제한 (°C)</div>
              <div class="sv">
                <input type="range" id="guardThermalLimit" min="50" max="70" value="50" step="1">
                <span id="guardThermalLabel">50°C</span>
              </div>
              <div class="sb">이 온도 이상이면 다운로드 일시정지</div>
            </div>
            <div class="si">
              <div class="sl">배터리 제한 (%)</div>
              <div class="sv">
                <input type="range" id="guardBatteryLimit" min="5" max="50" value="20" step="5">
                <span id="guardBatteryLabel">20%</span>
              </div>
              <div class="sb">이 수준 이하면 다운로드 일시정지</div>
            </div>
            <div class="si">
              <div class="sl">스토리지 제한 (%)</div>
              <div class="sv">
                <input type="range" id="guardStorageLimit" min="50" max="99" value="90" step="1">
                <span id="guardStorageLabel">90%</span>
              </div>
              <div class="sb">이 수준 이상이면 다운로드 일시정지</div>
            </div>
          </div>
          <div class="ti">서버 제어 (v0.36)</div>
          <div class="ck">
            <input type="checkbox" id="httpsEnabled" checked>
            <label for="httpsEnabled">HTTPS 사용 (끔이면 HTTP 단일 동작)</label>
          </div>
          <div class="ck">
            <input type="checkbox" id="bootAutoStart" checked>
            <label for="bootAutoStart">재부팅 시 자동 시작</label>
          </div>
          <div class="ck">
            <input type="checkbox" id="launchAutoStart" checked>
            <label for="launchAutoStart">앱 실행 시 자동 시작</label>
          </div>
          <div class="ck">
            <input type="checkbox" id="webServerEnabled" checked>
            <label for="webServerEnabled">웹 UI 서버 사용 (끄면 다운로드만 계속)</label>
          </div>
          <div class="sb">포트 변경은 앱 설정 화면에서 (충돌 시 E-AND-SRV-0111). 서버 시작/정지는 홈 ServerCard·위젯·퀵타일에서.</div>
          <div class="rb" style="margin-top:8px">
            <button class="ghost sm" onclick="saveServerSettings()">서버 설정 저장</button>
          </div>
          <div class="ti">서버 안정성 (watchdog)</div>
          <div class="si">
            <div class="sl">헬스체크 주기 (초)</div>
            <div class="sv">
              <input type="number" id="watchdogIntervalSec" min="15" max="3600" value="60" style="width:80px">
              <span class="sb" style="display:inline">초마다 서버 상태 확인</span>
            </div>
            <div class="sb">서버가 응답하지 않으면 자동으로 재시작합니다. 시스템이 서버를 종료한 경우 복구됩니다.</div>
          </div>
          <div class="si">
            <label class="si" style="align-items:center;gap:8px">
              <input type="checkbox" id="forceHttpsRedirect">
              <span>HTTP 접속을 HTTPS로 강제 리다이렉트</span>
            </label>
            <div class="sb">꺼짐(기본): 모든 브라우저가 인증서 없이 HTTP로 접속. 켜짐: 웨일/사파리에서 HTTPS 인증서 신뢰 필요.</div>
          </div>
          <div class="rb" style="margin-top:12px">
            <button class="ghost sm" onclick="saveGuardSettings()">저장</button>
            <button class="ghost sm" onclick="checkGuardStatus()">🔍 현재 상태</button>
          </div>
          <div id="guardStatus" class="sb" style="margin-top:8px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-mcp">
        <div class="sg">
          <div class="sh">🤖 MCP 서버 권한</div>
          <div class="ck">
            <input type="checkbox" id="mcpPrivacyMode">
            <label for="mcpPrivacyMode">프라이버시 모드</label>
          </div>
          <div class="sb">프라이버시 모드 ON: 파일 읽기(file_read) 도구 비활성화</div>
          <div class="ti">도구별 활성화</div>
          <div class="ck"><input type="checkbox" id="mcpTool_file_list" checked><label for="mcpTool_file_list">file_list — 보관함 파일 목록</label></div>
          <div class="ck"><input type="checkbox" id="mcpTool_file_read" checked><label for="mcpTool_file_read">file_read — 파일 내용 읽기</label></div>
          <div class="ck"><input type="checkbox" id="mcpTool_download_add" checked><label for="mcpTool_download_add">download_add — 다운로드 추가</label></div>
          <div class="ck"><input type="checkbox" id="mcpTool_download_list" checked><label for="mcpTool_download_list">download_list — 다운로드 목록</label></div>
          <div class="ck"><input type="checkbox" id="mcpTool_download_control" checked><label for="mcpTool_download_control">download_control — 다운로드 제어</label></div>
          <div class="rb" style="margin-top:12px">
            <button class="ghost sm" onclick="saveMcpSettings()">저장</button>
          </div>
        </div>
      </div>

      <div class="settings-content" id="settings-schedule">
        <div class="sg">
          <div class="sh">⏰ 스케줄 다운로드</div>
          <div class="ck">
            <input type="checkbox" id="scheduleEnabled">
            <label for="scheduleEnabled">스케줄 활성화</label>
          </div>
          <div class="sb">조건 충족 시 대기 중인 다운로드 자동 실행</div>
          <div class="sr">
            <div class="si">
              <div class="sl">크론 표현식</div>
              <div class="sv"><input type="text" id="scheduleCron" placeholder="0 2 * * * (매일 새벽 2시)" style="flex:1"></div>
              <div class="sb">예: "0 2 * * *" = 매일 새벽 2시, "*/15 * * * *" = 15분 간격</div>
            </div>
            <div class="si">
              <div class="sl">Wi-Fi에서만 실행</div>
              <div class="sv"><input type="checkbox" id="scheduleWifiOnly" checked></div>
            </div>
            <div class="si">
              <div class="sl">충전 중일 때만 실행</div>
              <div class="sv"><input type="checkbox" id="scheduleChargingOnly"></div>
            </div>
            <div class="si">
              <div class="sl">신호 좋을 때만 실행 (시골 LTE)</div>
              <div class="sv"><input type="checkbox" id="signalGateEnabled"></div>
              <div class="sb">RSRP ≤ -110 또는 SINR ≤ 0이면 신규 시작·스케줄 보류 (해제: RSRP ≥ -100 그리고 SINR ≥ 3). 진행 중은 유지.</div>
            </div>
            <div class="si">
              <div class="sl">최소 배터리 (%)</div>
              <div class="sv">
                <input type="range" id="scheduleBatteryMin" min="5" max="100" value="30" step="5">
                <span id="scheduleBatteryLabel">30%</span>
              </div>
            </div>
          </div>
          <div class="rb" style="margin-top:12px">
            <button class="ghost sm" onclick="saveScheduleSettings()">저장</button>
          </div>
          <div id="scheduleStatus" class="sb" style="margin-top:8px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-storage">
        <div class="sg">
          <div class="sh">💾 스토리지</div>
          <div class="sb">외장 스토리지 / USB OTG 감지</div>
          <div class="rb" style="margin-top:8px">
            <button class="ghost sm" onclick="detectStorage()">🔍 감지</button>
          </div>
          <div id="storageList" class="sb" style="margin-top:8px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-debug">
        <div class="sg">
          <div class="sh">🐛 디버그 패널</div>
          <div class="sb">별도 창에서 실시간 로그를 모니터링합니다.</div>
          <div class="rb" style="margin-top:10px">
            <button class="ghost sm" onclick="window.open('/debug','_blank','width=820,height=600,scrollbars=1')">🐛 웹 디버그 창 열기</button>
          </div>
          <div class="ti" style="margin-top:14px">안드로이드 플로팅 오버레이</div>
          <div class="sb">메인 탭이 아닌 어느 화면에서든 디버그 로그를 볼 수 있습니다.</div>
          <div class="rb" style="margin-top:8px">
            <button class="ghost sm" id="overlayPermBtn" onclick="requestOverlayPerm()">플로팅 권한 부여</button>
            <button class="ghost sm" id="overlayToggleBtn" onclick="toggleOverlay()" style="display:none">오버레이 켜기</button>
          </div>
          <div id="overlayStatus" class="ss" style="margin-top:8px"></div>
          <div id="debugStatusInfo" class="ss" style="margin-top:10px"></div>
        </div>
      </div>

      <div class="settings-content" id="settings-reset">
        <div class="sg">
          <div class="sh">↩ 기본값 복원</div>
          <div class="rb">
            <button class="ghost" onclick="resetSettings('download')">↩ 다운로드 기본값</button>
            <button class="ghost" onclick="resetSettings('torrent')">↩ 토렌트 기본값</button>
            <button class="ghost" onclick="resetSettings('all')" style="margin-left:auto;color:var(--err);border-color:var(--danger)">🔄 전체 초기화</button>
          </div>
        </div>
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
      <div style="padding:20px;color:var(--dim);text-align:center">로딩 중…</div>
    </div>
  </div>
</div>"""
    }
}
