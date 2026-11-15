package dev.agentic.workspace

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.agentic.core.data.InMemoryConversationRepository
import dev.agentic.core.data.InMemoryProjectRepository
import dev.agentic.core.data.KeystoreSecretStorage
import dev.agentic.core.designsystem.AgenticTheme
import dev.agentic.core.network.GeminiModelProvider
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.LocalBackendLifecycleManager
import dev.agentic.core.network.hub.RemoteHubConnectionManager
import dev.agentic.core.network.hub.RemoteHubProxyHandler
import dev.agentic.core.network.jetski.JetskiAppConfig
import dev.agentic.core.network.jetski.JetskiFrontendModeManager
import dev.agentic.core.network.jetski.JetskiWebViewRequestInterceptor
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.workspace.ui.AppNavigationScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var pendingDeepLink: String? = null
        val deepLinkFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 4
        )
    }

    private val TAG = "MainActivity"
    private var backendLifecycleManager: LocalBackendLifecycleManager? = null
    private var dataSyncManager: dev.agentic.core.network.hub.HubDataSyncManager? = null
    @Volatile
    private var lastSyncedSessionKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure system navigation bar is pure black matching Antigravity theme
        window.navigationBarColor = android.graphics.Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val projectRepository = InMemoryProjectRepository()
        val conversationRepository = InMemoryConversationRepository()
        val secretStorage = KeystoreSecretStorage(applicationContext)

        val modelProvider = GeminiModelProvider(
            apiKeyProvider = { secretStorage.getApiKey("gemini") },
            allowDemoFallbackWhenKeyMissing = false
        )

        val csrfToken = "ag-android-${UUID.randomUUID()}"
        val workspaceRoot = File(filesDir, "workspace")

        val hubConnectionManager = RemoteHubConnectionManager()
        val hubProxyHandler = RemoteHubProxyHandler(hubConnectionManager)

        val lifecycleManager = LocalBackendLifecycleManager(
            context = applicationContext,
            hubConnectionManager = hubConnectionManager
        )
        backendLifecycleManager = lifecycleManager

        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = modelProvider,
            isAuthenticatedProvider = { secretStorage.hasApiKey("gemini") },
            csrfToken = csrfToken,
            workspaceRootDir = workspaceRoot,
            hubProxyHandler = hubProxyHandler,
            initialBackendMode = BackendMode.STANDALONE_IN_APP_BACKEND
        )

        val syncManager = dev.agentic.core.network.hub.HubDataSyncManager(
            hubConnectionManager = hubConnectionManager,
            projectRepository = projectRepository,
            conversationRepository = conversationRepository,
            rpcHandler = rpcHandler
        )
        dataSyncManager = syncManager

        // Monitor battery level for power-aware background sync deferral (Phase 4.2)
        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = registerReceiver(null, batteryFilter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            syncManager.updateBatteryState(batteryPct, isCharging)
        } catch (e: Exception) {
            Log.w(TAG, "Battery status check skipped: ${e.message}")
        }

        // Trigger data sync once per newly connected/authenticated Hub session
        lifecycleScope.launch {
            hubConnectionManager.connectionState.collect { state ->
                if (state is dev.agentic.core.network.hub.HubConnectionState.Connected) {
                    val sessionKey = "${state.config.host}:${state.config.port}:${state.status.userEmail.orEmpty()}"
                    if (sessionKey != lastSyncedSessionKey) {
                        lastSyncedSessionKey = sessionKey
                        Log.i(TAG, "Hub connected; triggering automated auth load data synchronization...")
                        triggerInitialSessionSync(syncManager)
                    }
                } else if (state is dev.agentic.core.network.hub.HubConnectionState.Disconnected) {
                    lastSyncedSessionKey = null
                }
            }
        }

        // Automatically start the standalone on-device backend on launch in Mode B
        lifecycleScope.launch(Dispatchers.IO) {
            if (rpcHandler.backendMode == BackendMode.STANDALONE_IN_APP_BACKEND) {
                Log.i(TAG, "Mode B active: auto-starting standalone on-device backend...")
                val result = lifecycleManager.startBackend()
                if (result.isSuccess) {
                    Log.i(TAG, "Backend started successfully; triggering immediate first-boot data sync")
                    triggerInitialSessionSync(syncManager)
                } else if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    Log.w(TAG, "Backend start failed (${err?.message}); automatically falling back to in-app Gemini handler")
                    rpcHandler.setBackendMode(BackendMode.LOCAL_GEMINI_FALLBACK)
                }
            }
        }

        // Monitor for Google OAuth authorization URLs and launch user browser automatically
        lifecycleScope.launch {
            lifecycleManager.latestAuthUrl.collect { authUrl ->
                if (!authUrl.isNullOrBlank()) {
                    Log.i(TAG, "Received Google OAuth authorization URL: $authUrl")
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(browserIntent)
                        Log.i(TAG, "Successfully launched browser for Google authentication")
                        lifecycleManager.clearAuthUrl()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch browser intent for authorization URL: ${e.message}", e)
                    }
                }
            }
        }

        val bundledFile = File(filesDir, "jetski-bundle.zip")
        val assetLen = runCatching { assets.openFd("jetski-bundle.zip").length }.getOrDefault(-1L)
        if (!bundledFile.exists() || bundledFile.length() == 0L || (assetLen > 0L && bundledFile.length() != assetLen)) {
            try {
                assets.open("jetski-bundle.zip").use { input ->
                    bundledFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Extracted bundled Jetski assets from APK to ${bundledFile.path} (${bundledFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Bundled jetski-bundle.zip not present in assets or extraction failed: ${e.message}")
            }
        }

        val candidateFiles = buildList {
            getExternalFilesDir(null)?.let { extDir ->
                add(File(extDir, "jetski-bundle.zip"))
                add(File(extDir, "jetski-web.zip"))
                add(File(extDir, "language_server.exe"))
            }
            add(File("/sdcard/Download/jetski-web.zip"))
            add(File("/sdcard/Download/language_server.exe"))
        }

        val modeManager = JetskiFrontendModeManager(
            bundleStorageFile = bundledFile,
            candidateDiscoveryFiles = candidateFiles
        ).also {
            it.initializeFromStorage()
            if (it.currentBundle() != null) {
                it.switchToMode(dev.agentic.core.network.jetski.FrontendUiMode.IMPORTED_JETSKI_WEB)
            }
        }

        val requestInterceptor = JetskiWebViewRequestInterceptor(
            bundleProvider = { modeManager.currentBundle() },
            rpcHandler = rpcHandler,
            appConfig = JetskiAppConfig(csrfToken = csrfToken)
        )

        setContent {
            AgenticTheme {
                AppNavigationScaffold(
                    projectRepository = projectRepository,
                    conversationRepository = conversationRepository,
                    secretStorage = secretStorage,
                    modeManager = modeManager,
                    requestInterceptor = requestInterceptor,
                    modelProvider = modelProvider,
                    hubConnectionManager = hubConnectionManager,
                    backendLifecycleManager = lifecycleManager,
                    rpcHandler = rpcHandler,
                    syncManager = syncManager
                )
            }
        }

        // Handle initial intent if launched via deep link
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val fullUri = uri.toString()
        Log.i(TAG, "Received deep-link URI: ${uri.scheme}://${uri.host}${uri.path}")
        if (uri.scheme.equals("antigravity", ignoreCase = true)) {
            val queryKeys = uri.queryParameterNames.joinToString(", ")
            Log.i(TAG, "Antigravity deep-link query parameter keys: $queryKeys")
            pendingDeepLink = fullUri
            deepLinkFlow.tryEmit(fullUri)
        }
    }

    override fun onResume() {
        super.onResume()
        // When returning to app (e.g. from browser authentication), refresh Hub status
        backendLifecycleManager?.let { mgr ->
            if (mgr.isRunning()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val status = mgr.refreshHubStatus()
                    if (status != null && !status.userName.isNullOrBlank()) {
                        val cfg = dataSyncManager?.let {
                            mgr.let { m -> (m.backendState.value as? LocalBackendLifecycleManager.LocalBackendState.Running)?.hubConfig }
                        }
                        val sessionKey = "${cfg?.host.orEmpty()}:${cfg?.port ?: 0}:${status.userEmail.orEmpty()}"
                        if (sessionKey != lastSyncedSessionKey) {
                            lastSyncedSessionKey = sessionKey
                            Log.i(TAG, "Authenticated user session active: ${status.userName} (${status.userEmail})")
                            dataSyncManager?.let { triggerInitialSessionSync(it) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun triggerInitialSessionSync(syncManager: dev.agentic.core.network.hub.HubDataSyncManager) {
        val firstSummary = syncManager.syncAll(force = false).getOrNull() ?: return
        if (firstSummary.genuineConversationsCount == 0 && firstSummary.genuineProjectsCount == 0) {
            Log.i(TAG, "Sync 1 returned 0 conversations & 0 real projects; running Sync 2 after settle delay")
            kotlinx.coroutines.delay(1800)
            syncManager.syncAll(force = true)
        } else {
            Log.i(
                TAG,
                "Sync 1 already loaded ${firstSummary.genuineConversationsCount} conversations and " +
                    "${firstSummary.genuineProjectsCount} projects; skipping Sync 2"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Log.i(TAG, "MainActivity is finishing; shutting down standalone backend to avoid orphans...")
            backendLifecycleManager?.stopBackend()
        }
    }
}
