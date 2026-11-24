package dev.agentic.workspace.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.agentic.core.data.ConversationRepository
import dev.agentic.core.data.KeystoreSecretStorage
import dev.agentic.core.data.ProjectRepository
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.HubConnectionState
import dev.agentic.core.network.hub.LocalBackendLifecycleManager
import dev.agentic.core.network.hub.RemoteHubConnectionManager
import dev.agentic.core.network.jetski.FrontendUiMode
import dev.agentic.core.network.jetski.JetskiFrontendModeManager
import dev.agentic.core.network.jetski.JetskiWebViewRequestInterceptor
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.feature.agentmanager.AgentManagerScreen
import dev.agentic.feature.browser.BrowserScreen
import dev.agentic.feature.conversations.ConversationsScreen
import dev.agentic.feature.files.FilesScreen
import dev.agentic.feature.projects.ProjectsScreen
import dev.agentic.feature.settings.SettingsScreen
import dev.agentic.feature.terminal.TerminalScreen
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Chat : Screen("chat", "Chat", Icons.Default.AutoAwesome)
    data object Projects : Screen("projects", "Projects", Icons.Default.FolderOpen)
    data object Files : Screen("files", "Files", Icons.Default.Code)
    data object Agent : Screen("agent", "Agent", Icons.Default.SmartToy)
    data object Terminal : Screen("terminal", "Terminal", Icons.Default.Terminal)
    data object Browser : Screen("browser", "Browser", Icons.Default.Language)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(
    Screen.Chat,
    Screen.Projects,
    Screen.Files,
    Screen.Agent,
    Screen.Terminal,
    Screen.Settings
)

@Composable
fun AppNavigationScaffold(
    projectRepository: ProjectRepository,
    conversationRepository: ConversationRepository,
    secretStorage: KeystoreSecretStorage,
    modeManager: JetskiFrontendModeManager,
    requestInterceptor: JetskiWebViewRequestInterceptor,
    modelProvider: ModelProvider,
    hubConnectionManager: RemoteHubConnectionManager? = null,
    backendLifecycleManager: LocalBackendLifecycleManager? = null,
    rpcHandler: LanguageServerGrpcWebHandler? = null,
    syncManager: dev.agentic.core.network.hub.HubDataSyncManager? = null,
    provisioningManager: dev.agentic.core.network.hub.BackendProvisioningManager? = null,
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hostState by modeManager.state.collectAsState()
    val hubConnectionState by (hubConnectionManager?.connectionState ?: kotlinx.coroutines.flow.MutableStateFlow(HubConnectionState.Disconnected)).collectAsState()
    val localBackendState by (backendLifecycleManager?.backendState ?: kotlinx.coroutines.flow.MutableStateFlow(LocalBackendLifecycleManager.LocalBackendState.Stopped)).collectAsState()
    val qemuCompatEnabled by (backendLifecycleManager?.qemuModeEnabled ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val latestAuthUrl by (backendLifecycleManager?.latestAuthUrl ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()

    val bundlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    modeManager.importBundleFromStream(
                        inputStream = stream,
                        sourceLabel = uri.lastPathSegment ?: "SAF Document"
                    )
                }
            }
        }
    }

    val triggerImportPicker: () -> Unit = {
        bundlePickerLauncher.launch(arrayOf("*/*"))
    }

    val pendingRouteNavigation = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    var showHostSettingsDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    var setupSkipped by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    var setupCompleted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            provisioningManager?.isProvisioned() == true && hostState.hasValidBundle
        )
    }

    if (provisioningManager != null &&
        !setupSkipped &&
        !setupCompleted &&
        hostState.activeMode != FrontendUiMode.REMOTE_HOST &&
        (!provisioningManager.isProvisioned() || !hostState.hasValidBundle)
    ) {
        SetupWizardScreen(
            provisioningManager = provisioningManager,
            modeManager = modeManager,
            backendLifecycleManager = backendLifecycleManager,
            rpcHandler = rpcHandler,
            onSetupComplete = { setupCompleted = true },
            onSkipToFallback = { setupSkipped = true }
        )
        return
    }

    if (localBackendState is LocalBackendLifecycleManager.LocalBackendState.Provisioning ||
        localBackendState is LocalBackendLifecycleManager.LocalBackendState.Starting
    ) {
        SettingThingsUpLoadingScreen(state = localBackendState)
        return
    }

    // When Original Jetski Web Frontend or Remote Host is active, render JetskiWebViewHost as the primary UI
    if (hostState.activeMode == FrontendUiMode.IMPORTED_JETSKI_WEB ||
        hostState.activeMode == FrontendUiMode.REMOTE_HOST
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            JetskiWebViewHost(
                requestInterceptor = requestInterceptor,
                remoteHostUrl = if (hostState.activeMode == FrontendUiMode.REMOTE_HOST) {
                    hostState.remoteHostUrl
                } else {
                    null
                },
                backendLifecycleManager = backendLifecycleManager,
                rpcHandler = rpcHandler,
                onOpenHostControls = {
                    showHostSettingsDialog = true
                },
                onSwitchToFallbackMode = {
                    pendingRouteNavigation.value = null
                    modeManager.switchToMode(FrontendUiMode.NATIVE_JETBOX_FALLBACK)
                }
            )

            if (showHostSettingsDialog) {
                Dialog(
                    onDismissRequest = { showHostSettingsDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SettingsScreen(
                            onClose = { showHostSettingsDialog = false },
                            secretStorage = secretStorage,
                            activeUiModeLabel = hostState.activeMode.displayTitle,
                            hasValidBundle = hostState.hasValidBundle,
                            bundleStatusText = hostState.statusMessage,
                            bundleErrorText = hostState.lastValidationError,
                            remoteHostUrl = hostState.remoteHostUrl,
                            backendMode = rpcHandler?.backendMode ?: BackendMode.STANDALONE_IN_APP_BACKEND,
                            hubConnectionState = hubConnectionState,
                            localBackendState = localBackendState,
                            latestAuthUrl = latestAuthUrl,
                            qemuCompatEnabled = qemuCompatEnabled,
                            onQemuCompatChange = { enabled ->
                                backendLifecycleManager?.setQemuCompatibilityEnabled(enabled)
                                if (backendLifecycleManager?.isRunning() == true) {
                                    coroutineScope.launch {
                                        backendLifecycleManager.restartBackend()
                                    }
                                }
                            },
                            onBackendModeChange = { mode ->
                                rpcHandler?.setBackendMode(mode)
                                if (mode == BackendMode.STANDALONE_IN_APP_BACKEND && backendLifecycleManager?.isRunning() != true) {
                                    coroutineScope.launch {
                                        backendLifecycleManager?.startBackend()
                                    }
                                }
                            },
                            onStartLocalBackend = {
                                coroutineScope.launch {
                                    backendLifecycleManager?.startBackend()
                                }
                            },
                            onStopLocalBackend = {
                                backendLifecycleManager?.stopBackend()
                            },
                            onRestartLocalBackend = {
                                coroutineScope.launch {
                                    backendLifecycleManager?.restartBackend()
                                }
                            },
                            onSignInGoogle = {
                                coroutineScope.launch {
                                    val res = hubConnectionManager?.login("{\"isGcpTos\": true}")
                                    android.util.Log.i("AppNavigation", "Login RPC result: $res")
                                    syncManager?.syncAll()
                                }
                            },
                            onRefreshAccountStatus = {
                                coroutineScope.launch {
                                    val status = backendLifecycleManager?.refreshHubStatus()
                                    android.util.Log.i("AppNavigation", "Refreshed account status: ${status?.userName} (${status?.userEmail})")
                                    syncManager?.syncAll()
                                }
                            },
                            onOpenAuthUrl = { url ->
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("AppNavigation", "Failed to launch browser: ${e.message}")
                                }
                            },
                            onConnectDesktopHub = { config ->
                                coroutineScope.launch {
                                    hubConnectionManager?.connect(config)
                                }
                            },
                            onDisconnectDesktopHub = {
                                hubConnectionManager?.disconnect()
                            },
                            onImportBundleClick = triggerImportPicker,
                            onLaunchJetskiWebClick = {
                                showHostSettingsDialog = false
                                modeManager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB)
                            },
                            onClearBundleClick = {
                                modeManager.clearImportedBundle()
                            },
                            onConnectRemoteHost = { url ->
                                modeManager.activateRemoteHost(url)
                            },
                            onDisableRemoteHost = {
                                modeManager.disableRemoteHost()
                            }
                        )
                    }
                }
            }
        }
        return
    }

    androidx.compose.runtime.LaunchedEffect(pendingRouteNavigation.value) {
        val route = pendingRouteNavigation.value ?: return@LaunchedEffect
        while (runCatching { navController.graph }.isFailure) {
            kotlinx.coroutines.yield()
        }
        pendingRouteNavigation.value = null
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    // Clean-Room Native Jetbox Fallback UI Mode
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = {
            Surface(
                color = Color(0xFF161616),
                contentColor = Color(0xFFCCCCCC),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "UI Mode: ${hostState.activeMode.displayTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF007ACC),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = hostState.lastValidationError ?: hostState.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hostState.lastValidationError != null) {
                                Color(0xFFF14C4C)
                            } else {
                                Color(0xFF888888)
                            },
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hostState.hasValidBundle) {
                            Button(
                                onClick = {
                                    modeManager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB)
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Jetski Web")
                            }
                        } else {
                            OutlinedButton(onClick = triggerImportPicker) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import Bundle")
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                bottomNavItems.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Chat.route) {
                ConversationsScreen(
                    conversationRepository = conversationRepository,
                    onImportBundleClick = triggerImportPicker,
                    onSyncClick = if (syncManager != null) {
                        {
                            coroutineScope.launch {
                                syncManager.syncAll()
                            }
                        }
                    } else null,
                    onSendPrompt = { promptText ->
                        withContext(Dispatchers.IO) {
                            try {
                                val resp = modelProvider.streamCompletion(
                                    request = ModelRequest(
                                        modelId = "gemini-3.8-flash",
                                        messages = listOf(
                                            Message(
                                                id = "user-msg",
                                                conversationId = "conv-welcome",
                                                role = Role.USER,
                                                content = promptText
                                            )
                                        )
                                    ),
                                    tools = emptyList(),
                                    onDelta = {}
                                )
                                resp.text
                            } catch (e: Exception) {
                                "Authentication or Model Provider Error: ${e.message}. Configure your Gemini API key in Settings."
                            }
                        }
                    }
                )
            }
            composable(Screen.Projects.route) {
                ProjectsScreen(projectRepository = projectRepository)
            }
            composable(Screen.Files.route) {
                FilesScreen()
            }
            composable(Screen.Agent.route) {
                AgentManagerScreen()
            }
            composable(Screen.Terminal.route) {
                TerminalScreen(isHostConnected = hostState.activeMode == FrontendUiMode.REMOTE_HOST)
            }
            composable(Screen.Browser.route) {
                BrowserScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    secretStorage = secretStorage,
                    activeUiModeLabel = hostState.activeMode.displayTitle,
                    hasValidBundle = hostState.hasValidBundle,
                    bundleStatusText = hostState.statusMessage,
                    bundleErrorText = hostState.lastValidationError,
                    remoteHostUrl = hostState.remoteHostUrl,
                    backendMode = rpcHandler?.backendMode ?: BackendMode.STANDALONE_IN_APP_BACKEND,
                    hubConnectionState = hubConnectionState,
                    localBackendState = localBackendState,
                    latestAuthUrl = latestAuthUrl,
                    qemuCompatEnabled = qemuCompatEnabled,
                    onQemuCompatChange = { enabled ->
                        backendLifecycleManager?.setQemuCompatibilityEnabled(enabled)
                        if (backendLifecycleManager?.isRunning() == true) {
                            coroutineScope.launch {
                                backendLifecycleManager.restartBackend()
                            }
                        }
                    },
                    onBackendModeChange = { mode ->
                        rpcHandler?.setBackendMode(mode)
                        if (mode == BackendMode.STANDALONE_IN_APP_BACKEND && backendLifecycleManager?.isRunning() != true) {
                            coroutineScope.launch {
                                backendLifecycleManager?.startBackend()
                            }
                        }
                    },
                    onStartLocalBackend = {
                        coroutineScope.launch {
                            backendLifecycleManager?.startBackend()
                        }
                    },
                    onStopLocalBackend = {
                        backendLifecycleManager?.stopBackend()
                    },
                    onRestartLocalBackend = {
                        coroutineScope.launch {
                            backendLifecycleManager?.restartBackend()
                        }
                    },
                    onSignInGoogle = {
                        coroutineScope.launch {
                            val res = hubConnectionManager?.login("{\"isGcpTos\": true}")
                            android.util.Log.i("AppNavigation", "Login RPC result: $res")
                            syncManager?.syncAll()
                        }
                    },
                    onRefreshAccountStatus = {
                        coroutineScope.launch {
                            val status = backendLifecycleManager?.refreshHubStatus()
                            android.util.Log.i("AppNavigation", "Refreshed account status: ${status?.userName} (${status?.userEmail})")
                            syncManager?.syncAll()
                        }
                    },
                    onOpenAuthUrl = { url ->
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("AppNavigation", "Failed to launch browser: ${e.message}")
                        }
                    },
                    onConnectDesktopHub = { config ->
                        coroutineScope.launch {
                            hubConnectionManager?.connect(config)
                        }
                    },
                    onDisconnectDesktopHub = {
                        hubConnectionManager?.disconnect()
                    },
                    onImportBundleClick = triggerImportPicker,
                    onLaunchJetskiWebClick = {
                        modeManager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB)
                    },
                    onClearBundleClick = {
                        modeManager.clearImportedBundle()
                    },
                    onConnectRemoteHost = { url ->
                        modeManager.activateRemoteHost(url)
                    },
                    onDisableRemoteHost = {
                        modeManager.disableRemoteHost()
                    }
                )
            }
        }
    }
}

@Composable
fun SettingThingsUpLoadingScreen(
    state: LocalBackendLifecycleManager.LocalBackendState
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        val googleSans = androidx.compose.runtime.remember {
            val tf = runCatching {
                val file = java.io.File("/product/fonts/GoogleSans-Regular.ttf")
                if (file.exists() && file.canRead()) android.graphics.Typeface.createFromFile(file)
                else android.graphics.Typeface.create("google-sans", android.graphics.Typeface.NORMAL)
            }.getOrNull()
            if (tf != null) androidx.compose.ui.text.font.FontFamily(tf) else androidx.compose.ui.text.font.FontFamily.SansSerif
        }

        val percent = when (state) {
            is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> state.percent.coerceIn(0, 100)
            is LocalBackendLifecycleManager.LocalBackendState.Starting -> 90
            else -> 100
        }

        val estimatedWaitSec = when {
            percent < 30 -> 15
            percent < 70 -> 8
            percent < 90 -> 4
            else -> 2
        }

        val stageMessage = when (state) {
            is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> {
                if (state.stage.isNotBlank()) "Preparing workspace • ${state.stage}" else "Preparing workspace..."
            }
            is LocalBackendLifecycleManager.LocalBackendState.Starting -> "Starting engine..."
            else -> "Finalizing..."
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = "ANTIGRAVITY",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = googleSans,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Loading...",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = googleSans,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color(0xFFE2E8F0)
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFF4285F4),
                trackColor = Color(0xFF202124)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "$percent% • Estimated wait: ~$estimatedWaitSec sec",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = googleSans,
                    fontWeight = FontWeight.Medium
                ),
                color = Color(0xFF9AA0A6)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stageMessage,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = googleSans
                ),
                color = Color(0xFF70757A),
                textAlign = TextAlign.Center
            )
        }
    }
}
