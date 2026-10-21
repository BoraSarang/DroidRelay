package com.borasarang.droidrelay

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.NettyTuning
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.ui.DebugPanelContent
import com.borasarang.droidrelay.ui.DownloadsScreen
import com.borasarang.droidrelay.ui.FilesScreen
import com.borasarang.droidrelay.ui.MoreScreen
import com.borasarang.droidrelay.ui.SettingsScreen
import com.borasarang.droidrelay.ui.StatsScreen
import com.borasarang.droidrelay.ui.StatusScreen
import com.borasarang.droidrelay.ui.TorrentScreen
import com.borasarang.droidrelay.ui.theme.DroidRelayTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var lastOfferedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NettyTuning.apply()
        enableEdgeToEdge()
        DebugLogger.i("UI", "앱 실행 onCreate")
        handleSharedIntent(intent)
        handleOpenTab(intent)

        val settingsRepo = SettingsRepository.get(this)
        // 블로킹 I/O 제거 — DataStore 첫 로드는 코루틴에서 비동기 처리
        lifecycleScope.launch {
            val initial = settingsRepo.settings.first()
            // v0.36: 앱 실행 자동시작 토글 존중 (OFF면 서버 미기동)
            if (initial.launchAutoStart) {
                RelayService.start(this@MainActivity)
            } else {
                DebugLogger.i("UI", "앱 실행 자동시작 꺼짐 — 서버 미기동")
            }
        }
        requestNotificationPermission()
        requestStoragePermission()

        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = com.borasarang.droidrelay.relay.AppSettings())
            DroidRelayTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                RootApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 무조건 시도 원칙: 프로세스 생존 상태로 앱에 복귀하면 onCreate가 안 돌아서
        // 죽은 서버가 다시 안 살아나는 함정이 있었다. 포그라운드 복귀 때마다 확인한다.
        lifecycleScope.launch {
            val repo = SettingsRepository.get(this@MainActivity)
            if (repo.settings.first().launchAutoStart && !repo.serverState.first().running) {
                DebugLogger.i("UI", "포그라운드 복귀 — 서버 미기동이라 자동 시작")
                RelayService.start(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        handleOpenTab(intent)
    }

    /** 공유 받기: ACTION_SEND 텍스트에서 URL/magnet 추출 (T-941) */
    private fun handleSharedIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        val match = Regex("""(magnet:\?\S+|https?://\S+)""").find(text)?.value
            ?.trimEnd('.', ',', ')', ']', '>', '"', '\'') ?: return
        if (match.startsWith("magnet:")) {
            DebugLogger.i("UI", "[FEATURE] 공유 받기 magnet")
            pendingSharedMagnet = match
        } else {
            DebugLogger.i("UI", "[FEATURE] 공유 받기 url=${match.take(90)}")
            pendingSharedUrl = match
        }
        signalPending()
    }

    @Deprecated("Deprecated in Java")
    override fun onResume() {
        super.onResume()
        offerClipboardUrlIfNeeded()
    }

    private fun offerClipboardUrlIfNeeded() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return
        if ((text.startsWith("http://") || text.startsWith("https://")) && text != lastOfferedUrl) {
            lastOfferedUrl = text
            DebugLogger.d("UI", "클립보드 URL 감지: $text")
            pendingClipUrl = text
            signalPending()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                DebugLogger.i("UI", "알림 권한 결과 granted=$granted")
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            DebugLogger.i("UI", "저장소 권한 요청 MANAGE_EXTERNAL_STORAGE")
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_TAB = "com.borasarang.droidrelay.OPEN_TAB"
        const val EXTRA_TAB = "tab"
        @Volatile var pendingClipUrl: String? = null
        @Volatile var pendingSharedUrl: String? = null
        @Volatile var pendingSharedMagnet: String? = null
        @Volatile var pendingOpenTab: Int? = null

        /**
         * 위 pending* 필드가 채워졌음을 알리는 신호.
         * 이전 구현은 RootApp 에서 1.5초 주기 while 루프가 이 네 필드를 폴링했다 —
         * 사용자 이벤트로만 채워지는 값을 분당 40회 메인 스레드 웨이크업으로 감시했다.
         * 이제 작성 측이 직접 emit 하고 UI 는 collect 한다.
         */
        val pendingSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
            replay = 1,
            extraBufferCapacity = 8,
        )

        private fun signalPending() {
            pendingSignal.tryEmit(Unit)
        }
    }

    /** 알림 탭 → 지정 탭으로 이동 (v0.24, singleTop이므로 onNewIntent 경유) */
    private fun handleOpenTab(intent: android.content.Intent?) {
        if (intent?.action != ACTION_OPEN_TAB) return
        val tab = intent.getIntExtra(EXTRA_TAB, -1)
        if (tab in 0..5) {
            pendingOpenTab = tab
            signalPending()
            DebugLogger.i("UI", "[FEATURE] 알림 탭 이동 tab=$tab")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 보조 화면 — 하단 내비 5개에서 밀려난 통계·설정 (뒤로가기로 복귀)
    var secondary by rememberSaveable { mutableStateOf<String?>(null) }
    var showDebug by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = com.borasarang.droidrelay.relay.RelayApp.get(context)
    val tabTitles = listOf("DroidRelay", "받기", "토렌트", "보관함", "더보기")

    fun goPrimary(target: Int) {
        tab = target.coerceIn(0, 4)
        secondary = null
    }

    fun goSecondary(target: String) {
        secondary = target
    }

    // 클립보드 URL 감지 제안 (T-110) + 공유 받기 (T-941) + 알림 탭 이동 (v0.24)
    // 1.5초 폴링 대신 이벤트 구독 — pending* 가 채워질 때만 깨어난다.
    // ⚠️ 알림/위젯은 여전히 0..5 인덱스를 보낸다. 4=설정, 5=통계 → 보조 화면으로 매핑.
    LaunchedEffect(Unit) {
        MainActivity.pendingSignal.collect {
            MainActivity.pendingOpenTab?.let { target ->
                MainActivity.pendingOpenTab = null
                when (target) {
                    4 -> goSecondary("settings")
                    5 -> goSecondary("stats")
                    in 0..3 -> goPrimary(target)
                }
            }
            MainActivity.pendingClipUrl?.let { url ->
                MainActivity.pendingClipUrl = null
                val result = snackbar.showSnackbar(
                    message = "복사한 URL을 다운로드할까요?",
                    actionLabel = "추가",
                    withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    DebugLogger.i("UI", "클립보드 제안 수락 → $url")
                    if (com.borasarang.droidrelay.relay.JobsRepository.findDuplicateUrl(url) != null) {
                        scope.launch { snackbar.showSnackbar("이미 등록된 다운로드입니다") }
                    } else {
                        runCatching { engine.enqueue(url) }
                            .onFailure { scope.launch { snackbar.showSnackbar("다운로드 추가 실패: ${it.message}") } }
                    }
                }
            }
            MainActivity.pendingSharedUrl?.let { url ->
                MainActivity.pendingSharedUrl = null
                if (com.borasarang.droidrelay.relay.JobsRepository.findDuplicateUrl(url) != null) {
                    scope.launch { snackbar.showSnackbar("이미 등록된 다운로드입니다") }
                } else {
                    runCatching { engine.enqueue(url) }
                        .onSuccess { scope.launch { snackbar.showSnackbar("공유받은 URL을 다운로드에 추가했습니다") } }
                        .onFailure { scope.launch { snackbar.showSnackbar("다운로드 추가 실패: ${it.message}") } }
                }
            }
            MainActivity.pendingSharedMagnet?.let { magnet ->
                MainActivity.pendingSharedMagnet = null
                val torrentEngine = com.borasarang.droidrelay.relay.RelayApp.getTorrent(context)
                if (torrentEngine.isDuplicateMagnet(magnet)) {
                    goPrimary(2)
                    scope.launch { snackbar.showSnackbar("이미 등록된 토렌트입니다") }
                } else {
                    runCatching { torrentEngine.addMagnet(magnet) }
                        .onSuccess { goPrimary(2) }
                        .onFailure { e ->
                            DebugLogger.e("UI", "공유 magnet 추가 실패: ${e.message}")
                            scope.launch { snackbar.showSnackbar("magnet 추가 실패: ${e.message}") }
                        }
                }
            }
        }
    }

    BackHandler(enabled = secondary != null) { secondary = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    when (secondary) {
                        "stats" -> Text("통계")
                        "settings" -> Text("설정")
                        else -> Text(
                            tabTitles[tab],
                            style = if (tab == 0) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                navigationIcon = {
                    if (secondary != null) {
                        IconButton(onClick = { secondary = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showDebug = true
                        DebugLogger.i("UI", "디버그 패널 열림 (${DebugLogger.count()}줄)")
                    }) {
                        Icon(Icons.Outlined.BugReport, "디버그 로그 패널")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // M3 권장 3~5개. 통계·설정은 "더보기"로 이동.
            if (secondary == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { goPrimary(0) },
                        icon = { Icon(Icons.Outlined.Dns, null) },
                        label = { Text("홈") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { goPrimary(1) },
                        icon = { Icon(Icons.Outlined.Download, null) },
                        label = { Text("받기") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { goPrimary(2) },
                        icon = { Icon(Icons.Outlined.CloudDownload, null) },
                        label = { Text("토렌트") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { goPrimary(3) },
                        icon = { Icon(Icons.Outlined.Folder, null) },
                        label = { Text("보관함") },
                    )
                    NavigationBarItem(
                        selected = tab == 4,
                        onClick = { goPrimary(4) },
                        icon = { Icon(Icons.Outlined.MoreHoriz, null) },
                        label = { Text("더보기") },
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding(),
        ) {
            when (secondary) {
                "stats" -> StatsScreen()
                "settings" -> SettingsScreen(onPortChanged = {})
                else -> when (tab) {
                    0 -> StatusScreen(
                        onCopyAddress = { addr ->
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(addr))
                            scope.launch { snackbar.showSnackbar("주소 복사됨") }
                            DebugLogger.d("UI", "주소 복사 → $addr")
                        },
                    )
                    1 -> DownloadsScreen()
                    2 -> TorrentScreen(onShowSnack = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
                    3 -> FilesScreen(onShowSnack = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
                    else -> MoreScreen(
                        onOpenStats = { goSecondary("stats") },
                        onOpenSettings = { goSecondary("settings") },
                        onOpenDebug = { showDebug = true },
                    )
                }
            }
        }
    }

    if (showDebug) {
        ModalBottomSheet(
            onDismissRequest = {
                showDebug = false
                DebugLogger.d("UI", "디버그 패널 닫힘")
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DebugPanelContent()
        }
    }
}
