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

    private val TAG = "MainActivity"
    private var backendLifecycleManager: LocalBackendLifecycleManager? = null
    private var dataSyncManager: dev.agentic.core.network.hub.HubDataSyncManager? = null
    @Volatile
    private var lastSyncedSessionKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                        syncManager.syncAll()
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
                lifecycleManager.startBackend()
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
            bundleStorageFile = File(filesDir, "jetski-bundle.zip"),
            candidateDiscoveryFiles = candidateFiles
        ).also {
            it.initializeFromStorage()
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
        Log.i(TAG, "Received deep-link URI: ${uri.scheme}://${uri.host}${uri.path}")
        if (uri.scheme.equals("antigravity", ignoreCase = true)) {
            val queryKeys = uri.queryParameterNames.joinToString(", ")
            Log.i(TAG, "Antigravity deep-link query parameter keys: $queryKeys")
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
                            dataSyncManager?.syncAll()
                        }
                    }
                }
            }
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
