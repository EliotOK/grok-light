package com.eliotok.groklight

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Timestamp of the last onPause; feeds StateBus.backgroundMs. */
    private var pausedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Edge-to-edge on Android 15/16 draws under the system bars. Without
                // this the toolbar sits behind the status bar and the user cannot
                // reach "换链接" — the exact white-screen dead end reported on device.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                ) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        pausedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        StateBus.backgroundMs.value =
            if (pausedAt == 0L) 0L else System.currentTimeMillis() - pausedAt
    }

    /** Pair from a tapped mirror link (VIEW) or a shared link (SEND). */
    private fun handleIncomingIntent(intent: Intent?) {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        Prefs.normalizeMirrorUrl(candidate, Prefs.lanHost(this))?.let {
            Prefs.setRemoteUrl(this, it)
            StateBus.remoteUrl.value = it
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val remoteUrl by StateBus.remoteUrl.collectAsState()
    val status by StateBus.status.collectAsState()
    val scope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf<Updater.UpdateUi>(Updater.UpdateUi.Hidden) }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            updateUi = Updater.UpdateUi.Checking
            val result = Updater.check()
            Prefs.setLastUpdateCheck(context, System.currentTimeMillis())
            updateUi = when (result) {
                is Updater.CheckResult.Available ->
                    Updater.UpdateUi.Available(result.release, result.expectedSha256)
                is Updater.CheckResult.UpToDate ->
                    if (manual) Updater.UpdateUi.UpToDate else Updater.UpdateUi.Hidden
                is Updater.CheckResult.Error ->
                    if (manual) Updater.UpdateUi.Error(result.message) else Updater.UpdateUi.Hidden
            }
        }
    }

    fun startDownload() {
        val current = updateUi as? Updater.UpdateUi.Available ?: return
        scope.launch {
            if (!Updater.canInstall(context)) {
                Updater.requestInstallPermission(context)
                updateUi = Updater.UpdateUi.Error(
                    "请在接下来的页面允许「安装未知应用」，返回后重新点「下载并安装」。"
                )
                return@launch
            }
            updateUi = Updater.UpdateUi.Progress(0)
            try {
                val file = Updater.downloadAndVerify(
                    context, current.release, current.expectedSha256,
                ) { percent -> updateUi = Updater.UpdateUi.Progress(percent) }
                Updater.launchInstall(context, file)
                updateUi = Updater.UpdateUi.Hidden
            } catch (e: Exception) {
                updateUi = Updater.UpdateUi.Error(e.message ?: "更新失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        StateBus.remoteUrl.value = Prefs.remoteUrl(context)
        // 启动时静默检查一次更新（每 24 小时至多一次），有新版才弹窗
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(context) > 24 * 3_600_000L) {
            checkUpdate(manual = false)
        }
    }

    if (remoteUrl.isEmpty()) {
        PairScreen(
            onSave = { url ->
                Prefs.setRemoteUrl(context, url)
                StateBus.remoteUrl.value = url
            },
            onCheckUpdate = { checkUpdate(manual = true) },
        )
    } else {
        WebViewScreen(
            url = remoteUrl,
            status = status,
            onUpdateCheck = { checkUpdate(manual = true) },
            onClearLink = {
                Prefs.setRemoteUrl(context, "")
                StateBus.remoteUrl.value = ""
                context.stopService(Intent(context, KeepAliveService::class.java))
            },
        )
    }

    UpdateDialog(
        ui = updateUi,
        onDismiss = { updateUi = Updater.UpdateUi.Hidden },
        onDownload = { startDownload() },
    )
}

@Composable
private fun PairScreen(onSave: (String) -> Unit, onCheckUpdate: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var lanHost by remember { mutableStateOf(Prefs.lanHost(context)) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Grok Light", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "把电脑端 Grok App「远程控制」里的镜像链接粘贴到这里，" +
                "一次配对，之后由本应用托管该页面并在后台保活。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = "" },
            label = { Text("http://<电脑IP>:<端口>/t/…") },
            isError = error.isNotEmpty(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = lanHost,
            onValueChange = { lanHost = it; error = "" },
            label = { Text("电脑局域网 IP（看 ipconfig 的以太网 / WLAN）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：面板给的主机若是 198.18.x.x、127.0.0.1 这类地址，说明它挑错了网卡" +
                "（常见于开着 Clash / Mihomo 的 TUN），此时必须填电脑真实的局域网 IP。" +
                "另外，桌面端重启后端口和令牌都可能变化，链接要重新复制。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val normalized = Prefs.normalizeMirrorUrl(text, lanHost)
                val bogus = Prefs.bogusHostOf(text)
                when {
                    normalized != null -> {
                        Prefs.setLanHost(context, lanHost)
                        onSave(normalized)
                    }
                    bogus != null -> error = "地址里的 $bogus 手机到不了，请填电脑的局域网 IP"
                    else -> error = "没识别到镜像链接：应形如 http://电脑IP:63867/t/…"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("配对并连接") }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimization(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("申请忽略电池优化（建议）") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
            Text("检查更新（当前 v${BuildConfig.VERSION_NAME}）")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "链接（含访问令牌）只保存在本机，不会上传。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName),
            )
        )
    } catch (_: ActivityNotFoundException) {
        // 部分厂商 ROM 不支持该入口，忽略即可
    }
}

@Composable
private fun WebViewScreen(
    url: String,
    status: String,
    onUpdateCheck: () -> Unit,
    onClearLink: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val recreateTick by StateBus.recreateTick.collectAsState()
    val backgroundMs by StateBus.backgroundMs.collectAsState()
    var keepOn by remember { mutableStateOf(Prefs.keepScreenOn(context)) }
    var progress by remember { mutableIntStateOf(100) }
    var menuOpen by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pendingFilePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFilePathCallback
        pendingFilePathCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        val data = result.data
        callback.onReceiveValue(
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            } else {
                null
            },
        )
    }
    val onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit =
        { callback, params ->
            pendingFilePathCallback?.onReceiveValue(null)
            pendingFilePathCallback = callback
            try {
                fileChooserLauncher.launch(params.createIntent())
            } catch (_: ActivityNotFoundException) {
                pendingFilePathCallback = null
                callback.onReceiveValue(null)
            }
        }

    LaunchedEffect(Unit) { KeepAliveService.start(context) }

    // Foreground return: when the page WebSocket is no longer OPEN, reload without
    // cache so the phone never keeps showing a frozen previous scene.
    LaunchedEffect(backgroundMs) {
        if (backgroundMs < 15_000L) return@LaunchedEffect
        val wv = webViewRef.value ?: return@LaunchedEffect
        wv.evaluateJavascript(WS_STATE_JS) { result ->
            val state = result?.trim('"')?.substringBefore(':') ?: "unknown"
            if (state != "open") hardReload(wv)
        }
    }

    LaunchedEffect(keepOn) {
        Prefs.setKeepScreenOn(context, keepOn)
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text("常亮", style = MaterialTheme.typography.labelMedium)
            Switch(checked = keepOn, onCheckedChange = { keepOn = it })
            TextButton(onClick = { webViewRef.value?.let { hardReload(it) } }) { Text("刷新") }
            TextButton(onClick = onUpdateCheck) { Text("更新") }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("强制重新同步") },
                    onClick = { menuOpen = false; webViewRef.value?.let { hardReload(it) } },
                )
                DropdownMenuItem(
                    text = { Text("换链接") },
                    onClick = { menuOpen = false; onClearLink() },
                )
                DropdownMenuItem(
                    text = { Text("申请忽略电池优化") },
                    onClick = { menuOpen = false; requestIgnoreBatteryOptimization(context) },
                )
            }
        }
        if (progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val failure = loadError
        if (failure != null) {
            // Dead-end guard: a rotated token or a changed LAN port used to leave a
            // blank page with no visible control to pair again.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("连接不上镜像主机", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                Text(url, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        loadError = null
                        StateBus.recreateTick.value++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onClearLink, modifier = Modifier.fillMaxWidth()) {
                    Text("换链接（重新配对）")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onUpdateCheck, modifier = Modifier.fillMaxWidth()) {
                    Text("检查更新")
                }
            }
        } else {
            // recreateTick 变化（渲染进程崩溃 / 手动重试）时强制重建 WebView
            key(recreateTick) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        createWebView(
                            ctx = ctx,
                            url = url,
                            onProgress = { progress = it },
                            onFileChooser = onFileChooser,
                            onLoadError = { loadError = it },
                        ).also { webViewRef.value = it }
                    },
                )
            }
        }
    }

    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else activity?.finish()
    }
}

@Composable
private fun UpdateDialog(
    ui: Updater.UpdateUi,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    when (ui) {
        is Updater.UpdateUi.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新") },
            text = { Text("正在检查新版本…") },
            confirmButton = {},
        )
        is Updater.UpdateUi.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 v${ui.release.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!ui.release.notes.isNullOrBlank()) {
                        Text(ui.release.notes.take(500))
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (ui.expectedSha256 != null) "已获取 SHA-256 校验值，安装前会校验完整性。"
                        else "该版本未提供校验文件，将只校验包名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDownload) { Text("下载并安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )
        is Updater.UpdateUi.Progress -> AlertDialog(
            onDismissRequest = { /* 下载中不允许关闭，避免半途误触 */ },
            title = { Text("正在下载更新") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { ui.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${ui.percent}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is Updater.UpdateUi.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新") },
            text = { Text(ui.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
        )
        is Updater.UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前 v${BuildConfig.VERSION_NAME} 已是最新。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好的") } },
        )
        Updater.UpdateUi.Hidden -> {}
    }
}

/** Page probe: remembers whether the mirror page WebSocket is still open. */
private const val WS_PROBE_JS = """
(function(){
  if (window.__glProbe) return;
  window.__glProbe = { state: 'unknown', lastMsg: Date.now(), closes: 0 };
  var Native = window.WebSocket;
  if (!Native) return;
  function Patched(url, protocols) {
    var ws = (protocols === undefined) ? new Native(url) : new Native(url, protocols);
    try {
      window.__glProbe.state = 'open';
      ws.addEventListener('open', function(){ window.__glProbe.state = 'open'; });
      ws.addEventListener('message', function(){ window.__glProbe.lastMsg = Date.now(); });
      ws.addEventListener('close', function(){ window.__glProbe.closes++; window.__glProbe.state = 'closed'; });
      ws.addEventListener('error', function(){ window.__glProbe.state = 'error'; });
    } catch (e) {}
    return ws;
  }
  Patched.prototype = Native.prototype;
  try { Patched.CONNECTING = Native.CONNECTING; Patched.OPEN = Native.OPEN; Patched.CLOSING = Native.CLOSING; Patched.CLOSED = Native.CLOSED; } catch (e) {}
  try { window.WebSocket = Patched; } catch (e) {}
})();
"""

/** Reads the probe: open / closed / error / unknown. */
private const val WS_STATE_JS =
    "(function(){try{var p=window.__glProbe;if(!p)return 'unknown';return p.state;}catch(e){return 'unknown';}})()"

/** Reload without cache: the mirror page is live state, never a stale snapshot. */
private fun hardReload(webView: WebView) {
    webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    webView.reload()
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: Context,
    url: String,
    onProgress: (Int) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    onLoadError: (String) -> Unit,
): WebView {
    val homeUri = Uri.parse(url)
    val homeHost = homeUri.host
    val homePort = homeUri.port

    return WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        // The mirror page is plain http on the LAN; keep ws/wss usable.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                view?.evaluateJavascript(WS_PROBE_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(WS_PROBE_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                // Keep the mirror page (and its media route) in-app; send the rest out.
                val sameMirror = target.host != null && target.host == homeHost &&
                    (target.port == homePort || target.port == -1)
                if (sameMirror) return false
                return try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, target))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                onLoadError("网络错误：${error.description}")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val code = response.statusCode
                if (code == 401 || code == 403) {
                    onLoadError(
                        "链接已失效（HTTP $code）。桌面端重启后端口与令牌都可能变化，" +
                            "请到「远程控制」面板重新复制链接。",
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                WebViewHolder.reset()
                StateBus.recreateTick.value++
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                onFileChooser(filePathCallback, fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // getUserMedia needs a secure context, so this only fires for https
                // mirror links; grant only when the OS already allowed the mic.
                val wantsAudio =
                    request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                val granted =
                    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                if (wantsAudio && granted) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                }
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult,
            ): Boolean {
                AlertDialog.Builder(ctx)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult,
            ): Boolean {
                AlertDialog.Builder(ctx)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        // Downloads (chat images/attachments) go to the system downloader; the token
        // lives in the URL path, so it needs no session.
        setDownloadListener { downloadUrl, _, _, _, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(downloadUrl, null, null),
                    )
                }
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            } catch (_: Exception) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }

        loadUrl(url)
        WebViewHolder.webView = this
        WebViewHolder.lastLoaded = url
    }
}