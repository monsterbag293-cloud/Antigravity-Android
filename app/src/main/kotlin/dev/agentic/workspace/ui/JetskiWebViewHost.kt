@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.agentic.workspace.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.agentic.core.network.jetski.JetskiWebViewRequestInterceptor
import dev.agentic.core.network.hub.LocalBackendLifecycleManager
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.imeAnimationTarget
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import java.io.ByteArrayInputStream

private const val JETBOX_DARK_BG_COLOR = -0x1000000 // 0xFF000000.toInt() OLED Pure Black

/**
 * Hosts the original Antigravity `Jetski Web` React 19 SPA inside an Android [WebView],
 * routing static assets (`index.html`, `main.js`, `jetbox.css`, `compiled_tailwind.css`),
 * Web Workers (`diff_worker.js`, `workers/shiki_worker.js`), and Connect-RPC requests
 * (`/exa.language_server_pb.LanguageServerService/<Method>`) in-process via [JetskiWebViewRequestInterceptor].
 *
 * Also supports connecting to an explicit Remote Host URL when `remoteHostUrl` is provided.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JetskiWebViewHost(
    requestInterceptor: JetskiWebViewRequestInterceptor,
    remoteHostUrl: String? = null,
    backendLifecycleManager: LocalBackendLifecycleManager? = null,
    rpcHandler: LanguageServerGrpcWebHandler? = null,
    onOpenHostControls: () -> Unit,
    onSwitchToFallbackMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val diagnostics by requestInterceptor.diagnostics.collectAsState()

    val targetUrl = remember(remoteHostUrl, requestInterceptor.virtualOrigin) {
        if (!remoteHostUrl.isNullOrBlank()) {
            remoteHostUrl.trim()
        } else {
            "${requestInterceptor.virtualOrigin}/?hostTheme=dark"
        }
    }

    var isPageLoading by remember { mutableStateOf(true) }
    var canNavigateBack by remember { mutableStateOf(false) }
    var loadErrorDescription by remember { mutableStateOf<String?>(null) }
    var decoratorActive by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var pendingFilePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFilePathCallback
        pendingFilePathCallback = null
        if (callback != null) {
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val clipData = data?.clipData
                when {
                    clipData != null && clipData.itemCount > 0 -> {
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }
    }

    var pendingSpeechStart by remember { mutableStateOf(false) }

    val speechIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()
            if (!text.isNullOrBlank()) {
                webViewRef?.evaluateJavascript("window.__insertRecognizedSpeech?.(${JSONObject.quote(text)});", null)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        if (req != null) {
            if (granted) {
                req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                req.deny()
            }
        }
        if (pendingSpeechStart) {
            pendingSpeechStart = false
            if (granted) {
                webViewRef?.evaluateJavascript("window.__ANTIGRAVITY_ANDROID_BRIDGE__?.startSpeechToText?.();", null)
            }
        }
    }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(JETBOX_DARK_BG_COLOR)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Keep false so window.innerWidth matches the phone's true CSS pixel width (< 640px),
                // activating Jetski Web's built-in mobile layout (HE() === true) and 100% / 100dvh sizing.
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = false
                }
            }

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun openNativeControls() {
                        post { onOpenHostControls() }
                    }

                    @JavascriptInterface
                    fun reportDecoratorReady(active: Boolean) {
                        post { decoratorActive = active }
                    }

                    @JavascriptInterface
                    fun listWorkspaceFolders(): String {
                        val root = dev.agentic.workspace.provider.AntigravityDocumentsProvider.resolveWorkspacesRoot(context)
                        val arr = JSONArray()
                        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { dir ->
                            val obj = org.json.JSONObject()
                            obj.put("name", dir.name)
                            obj.put("path", "/tmp/gemini_app/workspaces/${dir.name}")
                            arr.put(obj)
                        }
                        return arr.toString()
                    }

                    @JavascriptInterface
                    fun createWorkspaceFolder(name: String): String {
                        val sanitized = name.trim()
                            .replace(Regex("[/\\\\:*?\"<>|]+"), "-")
                            .trim('.', ' ')
                            .ifEmpty { "my-project" }
                        val root = dev.agentic.workspace.provider.AntigravityDocumentsProvider.resolveWorkspacesRoot(context)
                        val target = File(root, sanitized)
                        if (!target.exists()) target.mkdirs()
                        // Also mirror folder inside rootfs/tmp/gemini_app/workspaces if root is external
                        val internalRoot = File(context.filesDir, "rootfs/tmp/gemini_app/workspaces")
                        if (internalRoot.absolutePath != root.absolutePath) {
                            File(internalRoot, sanitized).apply { if (!exists()) mkdirs() }
                        }
                        return "/tmp/gemini_app/workspaces/$sanitized"
                    }

                    @JavascriptInterface
                    fun nativeStorageGetItems(): String {
                        val storageFile = File(context.filesDir, "app_storage.json")
                        return if (storageFile.exists()) {
                            try { storageFile.readText() } catch (_: Exception) { "{}" }
                        } else {
                            "{}"
                        }
                    }

                    @JavascriptInterface
                    fun nativeStorageUpdateItems(changesJson: String) {
                        try {
                            val storageFile = File(context.filesDir, "app_storage.json")
                            val currentObj = if (storageFile.exists()) {
                                try { JSONObject(storageFile.readText()) } catch (_: Exception) { JSONObject() }
                            } else {
                                JSONObject()
                            }
                            val changes = JSONObject(changesJson)
                            val keys = changes.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (changes.isNull(key)) {
                                    currentObj.remove(key)
                                } else {
                                    currentObj.put(key, changes.opt(key))
                                }
                            }
                            storageFile.writeText(currentObj.toString(2))
                        } catch (e: Exception) {
                            Log.w("JetskiWebViewHost", "Failed to update nativeStorage: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun sendNotification(title: String, body: String, silent: Boolean, payloadJson: String) {
                        try {
                            val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)
                            if (notificationManager != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val channel = NotificationChannel(
                                        "antigravity_notifications",
                                        "Antigravity Alerts",
                                        if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
                                    )
                                    notificationManager.createNotificationChannel(channel)
                                }
                                val intent = Intent(context, dev.agentic.workspace.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra("notification_payload", payloadJson)
                                }
                                val pendingIntent = PendingIntent.getActivity(
                                    context,
                                    0,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                val notification = NotificationCompat.Builder(context, "antigravity_notifications")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle(title)
                                    .setContentText(body)
                                    .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
                                    .setAutoCancel(true)
                                    .setContentIntent(pendingIntent)
                                    .build()
                                notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
                            }
                        } catch (e: Exception) {
                            Log.w("JetskiWebViewHost", "Notification send failed: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun openNotificationSettings() {
                        try {
                            val intent = Intent().apply {
                                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.w("JetskiWebViewHost", "Open notification settings failed: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun openExternal(url: String) {
                        try {
                            val uri = Uri.parse(url)
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                val scheme = uri.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") {
                                    // Launch as Chrome Custom Tab in the same task so OAuth returns cleanly to the app
                                    val bundle = android.os.Bundle().apply {
                                        putBinder("android.support.customtabs.extra.SESSION", null)
                                    }
                                    putExtras(bundle)
                                    putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", android.graphics.Color.BLACK)
                                    if (context !is Activity) {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                } else {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.w("JetskiWebViewHost", "Open external failed for $url: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun bringToFront() {
                        try {
                            val act = context as? Activity
                            val intent = Intent(context, act?.javaClass ?: Class.forName("dev.agentic.workspace.MainActivity")).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.w("JetskiWebViewHost", "bringToFront failed: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun revealInFilePicker(path: String) {
                        Log.i("JetskiWebViewHost", "revealInFilePicker: $path")
                        mainHandler.post {
                            try {
                                val docId = dev.agentic.workspace.provider.AntigravityDocumentsProvider.documentIdForPath(context, path)
                                val docUri = android.provider.DocumentsContract.buildDocumentUri(
                                    dev.agentic.workspace.provider.AntigravityDocumentsProvider.AUTHORITY,
                                    docId
                                )
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(docUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                if (viewIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(viewIntent)
                                    return@post
                                }
                                val browseRootIntent = Intent(Intent.ACTION_VIEW).apply {
                                    val rootUri = android.provider.DocumentsContract.buildRootUri(
                                        dev.agentic.workspace.provider.AntigravityDocumentsProvider.AUTHORITY,
                                        "antigravity_workspaces_root"
                                    )
                                    data = rootUri
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                if (browseRootIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(browseRootIntent)
                                    return@post
                                }
                                val downloadsIntent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(downloadsIntent)
                            } catch (e: Exception) {
                                Log.w("JetskiWebViewHost", "Failed to open Android Files app for $path: ${e.message}")
                                runCatching {
                                    val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                    context.startActivity(Intent.createChooser(fallback, "Open Files").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun updateActiveAgentCount(count: Int) {
                        Log.d("JetskiWebViewHost", "Active agent count updated: $count")
                    }

                    @JavascriptInterface
                    fun getStoredDeepLink(): String {
                        val pending = dev.agentic.workspace.MainActivity.pendingDeepLink
                        dev.agentic.workspace.MainActivity.pendingDeepLink = null
                        return pending ?: ""
                    }

                    @JavascriptInterface
                    fun getLogs(): String {
                        return "Antigravity Android Standalone Runtime"
                    }

                    @JavascriptInterface
                    fun navigateBrowser(tabKey: String, url: String) {
                        Log.i("JetskiWebViewHost", "navigateBrowser tab=$tabKey url=$url")
                        openExternal(url)
                    }

                    @JavascriptInterface
                    fun startSpeechToText() {
                        mainHandler.post {
                            try {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    pendingSpeechStart = true
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@post
                                }
                                val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                                }
                                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                                    Log.w("JetskiWebViewHost", "SpeechRecognizer service unavailable; launching system speech intent")
                                    speechIntentLauncher.launch(recognizerIntent)
                                    return@post
                                }
                                speechRecognizer?.destroy()
                                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                                speechRecognizer = recognizer
                                var insertedFinal = false
                                recognizer.setRecognitionListener(object : RecognitionListener {
                                    override fun onReadyForSpeech(params: Bundle?) {}
                                    override fun onBeginningOfSpeech() {}
                                    override fun onRmsChanged(rmsdB: Float) {}
                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                    override fun onEndOfSpeech() {}
                                    override fun onError(error: Int) {
                                        Log.w("JetskiWebViewHost", "SpeechRecognizer error: $error; resetting state")
                                        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                            runCatching { speechIntentLauncher.launch(recognizerIntent) }
                                        } else {
                                            this@apply.evaluateJavascript("window.__agyVoiceRecording = false;", null)
                                        }
                                    }
                                    override fun onResults(results: Bundle?) {
                                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        val text = matches?.firstOrNull()
                                        if (!text.isNullOrBlank()) {
                                            insertedFinal = true
                                            this@apply.evaluateJavascript("window.__insertRecognizedSpeech?.(${JSONObject.quote(text)});", null)
                                        } else {
                                            this@apply.evaluateJavascript("window.__agyVoiceRecording = false;", null)
                                        }
                                    }
                                    override fun onPartialResults(partialResults: Bundle?) {
                                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                        val text = matches?.firstOrNull()
                                        if (!text.isNullOrBlank() && !insertedFinal) {
                                            Log.d("JetskiWebViewHost", "Speech partial result: $text")
                                        }
                                    }
                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                })
                                recognizer.startListening(recognizerIntent)
                            } catch (e: Exception) {
                                Log.e("JetskiWebViewHost", "Failed to start speech recognition: ${e.message}", e)
                                this@apply.evaluateJavascript("window.__agyVoiceRecording = false;", null)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun stopSpeechToText() {
                        mainHandler.post {
                            try {
                                speechRecognizer?.stopListening()
                            } catch (e: Exception) {
                                Log.w("JetskiWebViewHost", "Error stopping speech recognition: ${e.message}")
                            }
                        }
                    }
                },
                "__ANTIGRAVITY_ANDROID_BRIDGE__"
            )
        }
    }

    val hubConnectionState = remember {
        androidx.compose.runtime.mutableStateOf(requestInterceptor.hubConnectionStateFlow?.value)
    }
    androidx.compose.runtime.LaunchedEffect(requestInterceptor) {
        val flow = requestInterceptor.hubConnectionStateFlow ?: return@LaunchedEffect
        flow.collect { hubConnectionState.value = it }
    }
    val currentBackendState by (backendLifecycleManager?.backendState
        ?: kotlinx.coroutines.flow.MutableStateFlow(LocalBackendLifecycleManager.LocalBackendState.Stopped)
    ).collectAsState()

    // Auto-fallback watchdog: If backend provisioning or starting errors out,
    // automatically switch the in-memory rpcHandler to LOCAL_GEMINI_FALLBACK mode so the app enters the UI seamlessly.
    var fallbackTriggered by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(currentBackendState) {
        if (currentBackendState is LocalBackendLifecycleManager.LocalBackendState.Error && !fallbackTriggered) {
            fallbackTriggered = true
            Log.w("JetskiWebViewHost", "Backend state entered Error: ${(currentBackendState as LocalBackendLifecycleManager.LocalBackendState.Error).message}. Auto-switching rpcHandler to LOCAL_GEMINI_FALLBACK.")
            rpcHandler?.setBackendMode(BackendMode.LOCAL_GEMINI_FALLBACK)
        }
    }

    val isHubReady = remember(hubConnectionState.value, remoteHostUrl, currentBackendState, fallbackTriggered) {
        remoteHostUrl != null || requestInterceptor.isHubConnected() || fallbackTriggered
    }
    val waitingHtml = remember {
        """
        <!doctype html>
        <html>
        <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;background:#000000;"></body>
        </html>
        """.trimIndent()
    }

    DisposableEffect(webView, requestInterceptor, targetUrl, isHubReady) {
        webViewRef = webView
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                // Only intercept virtual origin requests when not in explicit remote host mode
                if (!remoteHostUrl.isNullOrBlank()) {
                    return super.shouldInterceptRequest(view, request)
                }
                val intercepted = requestInterceptor.intercept(
                    url = req.url.toString(),
                    method = req.method ?: "GET",
                    headers = req.requestHeaders ?: emptyMap(),
                    rawBody = null
                ) ?: return super.shouldInterceptRequest(view, request)

                return WebResourceResponse(
                    intercepted.mimeType,
                    intercepted.encoding,
                    intercepted.statusCode,
                    intercepted.reasonPhrase,
                    intercepted.headers,
                    intercepted.openStream()
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageLoading = true
                loadErrorDescription = null
                canNavigateBack = view?.canGoBack() == true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoading = false
                canNavigateBack = view?.canGoBack() == true
                view?.evaluateJavascript(
                    "(function(){ return Boolean(window.__JETSKI_DECORATOR_ACTIVE__); })();"
                ) { result ->
                    decoratorActive = (result == "true")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isPageLoading = false
                    loadErrorDescription = error?.description?.toString()
                        ?: "Failed to load ${request.url}"
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                isPageLoading = false
                loadErrorDescription = "WebView renderer process terminated unexpectedly. Tap Reload to recover."
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                pendingFilePathCallback?.onReceiveValue(null)
                pendingFilePathCallback = filePathCallback
                return try {
                    val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    val acceptTypes = fileChooserParams?.acceptTypes
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    val baseIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (acceptTypes.size == 1 && acceptTypes[0].contains("/")) acceptTypes[0] else "*/*"
                        if (acceptTypes.size > 1) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                        }
                        if (allowMultiple) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    val chooser = fileChooserParams?.title?.let { title ->
                        Intent.createChooser(baseIntent, title)
                    } ?: Intent.createChooser(baseIntent, "Select File")
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (_: Exception) {
                    pendingFilePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) {
                    request.deny()
                    return
                }
                webView.post {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingPermissionRequest?.deny()
                        pendingPermissionRequest = request
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        if (isHubReady) {
            webView.loadUrl(targetUrl)
        } else {
            webView.loadDataWithBaseURL(null, waitingHtml, "text/html", "utf-8", null)
        }

        onDispose {
            webView.stopLoading()
        }
    }

    DisposableEffect(lifecycleOwner, webView, targetUrl) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                    // Re-query auth and state upon returning from external browser / auth flow
                    webView.evaluateJavascript(
                        "(function(){ if(window.dispatchEvent){ window.dispatchEvent(new CustomEvent('antigravity:auth-changed')); } })();",
                        null
                    )
                    if (remoteHostUrl != null || requestInterceptor.isHubConnected()) {
                        webView.postDelayed({
                            webView.evaluateJavascript(
                                "(function(){var r=document.getElementById('root'); return r ? r.childElementCount : -1;})()"
                            ) { count ->
                                val trimmed = count?.trim()
                                if (trimmed == "0" || trimmed == "-1") {
                                    webView.loadUrl(targetUrl)
                                }
                            }
                        }, 350L)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_DESTROY -> {
                    webView.stopLoading()
                    webView.destroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
        }
    }

    val activity = context as? android.app.Activity
    BackHandler(enabled = true) {
        if (webView.canGoBack()) {
            webView.goBack()
            canNavigateBack = webView.canGoBack()
        } else {
            activity?.moveTaskToBack(true)
        }
    }

    // Automatically refresh/sync data once Hub becomes ready
    androidx.compose.runtime.LaunchedEffect(isHubReady) {
        if (isHubReady) {
            webViewRef?.evaluateJavascript(
                "(function(){ if(window.dispatchEvent){ window.dispatchEvent(new CustomEvent('antigravity:auth-changed')); } })();",
                null
            )
        }
    }

    // Forward incoming antigravity:// deep links (e.g. customization OAuth callbacks) to Jetski Web
    androidx.compose.runtime.LaunchedEffect(webView) {
        dev.agentic.workspace.MainActivity.deepLinkFlow.collect { url ->
            if (url.isNotBlank()) {
                dev.agentic.workspace.MainActivity.pendingDeepLink = null
                webView.evaluateJavascript(
                    "window.__dispatchDeepLink?.(${JSONObject.quote(url)});" +
                        "window.dispatchEvent?.(new CustomEvent('antigravity:auth-changed'));",
                    null
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            .background(Color(0xFF000000))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Main WebView + Loading / Error Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color(0xFF000000))
        ) {
            AndroidView(
                factory = {
                    webView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    if (view.layoutParams?.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                        view.layoutParams?.height != ViewGroup.LayoutParams.MATCH_PARENT
                    ) {
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        view.requestLayout()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!isHubReady) {
                val googleSans = remember {
                    val tf = runCatching {
                        val file = java.io.File("/product/fonts/GoogleSans-Regular.ttf")
                        if (file.exists() && file.canRead()) android.graphics.Typeface.createFromFile(file)
                        else android.graphics.Typeface.create("google-sans", android.graphics.Typeface.NORMAL)
                    }.getOrNull()
                    if (tf != null) androidx.compose.ui.text.font.FontFamily(tf) else androidx.compose.ui.text.font.FontFamily.SansSerif
                }

                val (displayPercent, statusMessage, subMessage) = when (val s = currentBackendState) {
                    is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> {
                        val p = s.percent.coerceIn(1, 99)
                        val msg = if (s.message.isNotBlank()) s.message else "Preparing workspace..."
                        val sub = if (s.stage.isNotBlank()) "Setup • ${s.stage}" else "Extracting and verifying backend..."
                        Triple(p, msg, sub)
                    }
                    is LocalBackendLifecycleManager.LocalBackendState.Starting -> {
                        val msg = if (s.message.isNotBlank()) s.message else "Starting engine..."
                        Triple(92, msg, "Initializing local language server daemon...")
                    }
                    is LocalBackendLifecycleManager.LocalBackendState.Error -> {
                        Triple(92, "Connecting fallback engine...", "Notice: ${s.message.take(60)}")
                    }
                    else -> {
                        Triple(50, "Connecting workspace engine...", "Initializing services...")
                    }
                }

                // Smooth animated progress value
                val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = displayPercent / 100f,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                    label = "loadingProgress"
                )

                val estSec = when {
                    displayPercent < 40 -> 15
                    displayPercent < 75 -> 8
                    displayPercent < 90 -> 4
                    else -> 1
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = "ANTIGRAVITY",
                            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = googleSans,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)
                            ),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Loading...",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                                fontFamily = googleSans,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                            color = Color(0xFFE2E8F0)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF4285F4),
                            trackColor = Color(0xFF202124)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "$displayPercent% • Estimated wait: ~$estSec sec",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                                fontFamily = googleSans,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            ),
                            color = Color(0xFF9AA0A6)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = statusMessage,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                fontFamily = googleSans
                            ),
                            color = Color(0xFFBDC1C6),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (subMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = subMessage,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = googleSans
                                ),
                                color = Color(0xFF70757A),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else if (isPageLoading) {
                CircularProgressIndicator(
                    color = Color(0xFF007ACC),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            val errorMsg = loadErrorDescription ?: diagnostics.lastError
            if (errorMsg != null) {
                Surface(
                    color = Color(0xFF181818),
                    contentColor = Color(0xFFCCCCCC),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Host Error",
                            tint = Color(0xFFF14C4C)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Jetski Web Host Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFCCCCCC)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF999999)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    loadErrorDescription = null
                                    webViewRef?.loadUrl(targetUrl)
                                }
                            ) {
                                Text("Retry")
                            }
                            OutlinedButton(onClick = onSwitchToFallbackMode) {
                                Text("Switch to Native Fallback")
                            }
                        }
                    }
                }
            }
        }
    }
}
