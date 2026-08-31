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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
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
import androidx.core.app.ActivityCompat
import com.borasarang.droidrelay.relay.DebugLogger
import com.borasarang.droidrelay.relay.RelayService
import com.borasarang.droidrelay.relay.SettingsRepository
import com.borasarang.droidrelay.ui.DebugPanelContent
import com.borasarang.droidrelay.ui.DownloadsScreen
import com.borasarang.droidrelay.ui.FilesScreen
import com.borasarang.droidrelay.ui.SettingsScreen
import com.borasarang.droidrelay.ui.TorrentScreen
import com.borasarang.droidrelay.ui.theme.DroidRelayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var lastOfferedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugLogger.i("UI", "앱 실행 onCreate")

        val settingsRepo = SettingsRepository.get(this)
        val initial = settingsRepo.firstBlocking()
        // MVP: 서버 미기동 방지 위해 항상 기동 (autoStart 토글 반영은 후속)
        RelayService.start(this)
        requestNotificationPermission()
        requestStoragePermission()

        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = initial)
            DroidRelayTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                RootApp()
            }
        }
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
        @Volatile var pendingClipUrl: String? = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootApp() {
    var tab by remember { mutableIntStateOf(0) }
    var showDebug by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = com.borasarang.droidrelay.relay.RelayApp.get(context)
    val tabTitles = listOf("다운로드", "토렌트", "보관함", "설정")

    // 클립보드 URL 감지 제안 (T-110)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
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
                    engine.enqueue(url)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tabTitles[tab]) },
                actions = {
                    IconButton(onClick = {
                        showDebug = true
                        DebugLogger.i("UI", "디버그 패널 열림 (${DebugLogger.count()}줄)")
                    }) {
                        Icon(Icons.Filled.BugReport, "디버그 로그 패널")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Download, null) },
                    label = { Text("다운로드") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.CloudDownload, null) },
                    label = { Text("토렌트") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    label = { Text("보관함") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("설정") },
                )
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when (tab) {
                0 -> DownloadsScreen(
                    onCopyAddress = { addr ->
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(addr))
                        scope.launch { snackbar.showSnackbar("주소 복사됨") }
                        DebugLogger.d("UI", "주소 복사 → $addr")
                    },
                )
                1 -> TorrentScreen(onShowSnack = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
                2 -> FilesScreen(onShowSnack = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
                else -> SettingsScreen(onPortChanged = {})
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
