package dev.agentic.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.data.KeystoreSecretStorage
import dev.agentic.core.designsystem.AgenticAppBar
import dev.agentic.core.designsystem.BrandError
import dev.agentic.core.designsystem.BrandPrimary
import dev.agentic.core.designsystem.BrandSuccess
import dev.agentic.core.designsystem.StatusBadge
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.DesktopHubConfig
import dev.agentic.core.network.hub.HubConnectionState
import dev.agentic.core.network.hub.HubServerStatus
import dev.agentic.core.network.hub.LocalBackendLifecycleManager

@Composable
fun SettingsScreen(
    secretStorage: KeystoreSecretStorage? = null,
    activeUiModeLabel: String = "Clean-Room Native Jetbox Fallback",
    hasValidBundle: Boolean = false,
    bundleStatusText: String = "No Jetski Web bundle imported.",
    bundleErrorText: String? = null,
    remoteHostUrl: String? = null,
    backendMode: BackendMode = BackendMode.STANDALONE_IN_APP_BACKEND,
    hubConnectionState: HubConnectionState = HubConnectionState.Disconnected,
    localBackendState: LocalBackendLifecycleManager.LocalBackendState = LocalBackendLifecycleManager.LocalBackendState.Stopped,
    latestAuthUrl: String? = null,
    onBackendModeChange: ((BackendMode) -> Unit)? = null,
    onStartLocalBackend: (() -> Unit)? = null,
    onStopLocalBackend: (() -> Unit)? = null,
    onRestartLocalBackend: (() -> Unit)? = null,
    onSignInGoogle: (() -> Unit)? = null,
    onRefreshAccountStatus: (() -> Unit)? = null,
    onOpenAuthUrl: ((String) -> Unit)? = null,
    onConnectDesktopHub: ((DesktopHubConfig) -> Unit)? = null,
    onDisconnectDesktopHub: (() -> Unit)? = null,
    onImportBundleClick: (() -> Unit)? = null,
    onLaunchJetskiWebClick: (() -> Unit)? = null,
    onClearBundleClick: (() -> Unit)? = null,
    onConnectRemoteHost: ((String) -> Unit)? = null,
    onDisableRemoteHost: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    var apiKeyInput by remember {
        mutableStateOf(secretStorage?.getApiKey("gemini") ?: "")
    }
    var keySavedMessage by remember { mutableStateOf<String?>(null) }
    var showApiKey by remember { mutableStateOf(false) }

    var selectedProvider by remember { mutableStateOf("gemini") }
    var selectedAutonomy by remember { mutableStateOf("supervised") }

    var hubHost by remember { mutableStateOf("10.0.2.2") }
    var hubPort by remember { mutableStateOf("57734") }
    var hubCsrfToken by remember { mutableStateOf("") }
    var hubUseTls by remember { mutableStateOf(false) }
    var showHubCsrf by remember { mutableStateOf(false) }

    var hostEnabled by remember(remoteHostUrl) { mutableStateOf(!remoteHostUrl.isNullOrBlank()) }
    var hostAddress by remember(remoteHostUrl) {
        mutableStateOf(remoteHostUrl ?: "https://10.0.2.2:9090")
    }
    var pairingCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            AgenticAppBar(
                title = "Host & Application Settings",
                actions = {
                    StatusBadge(
                        text = if (hasValidBundle) "Jetski Ready" else "Fallback UI",
                        color = if (hasValidBundle) BrandSuccess else BrandPrimary
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stage P0.5-D: Jetski Web Frontend & Bundle Controls (ADR-05)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = BrandPrimary
                        )
                        Text(
                            text = "Jetski Web Frontend & Bundle Controls (ADR-05)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Active UI Mode: $activeUiModeLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = bundleStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!bundleErrorText.isNullOrBlank()) {
                        Text(
                            text = bundleErrorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandError,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onImportBundleClick?.invoke() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Bundle (.exe / .zip)")
                        }

                        if (hasValidBundle && onClearBundleClick != null) {
                            OutlinedButton(onClick = { onClearBundleClick.invoke() }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }

                    if (hasValidBundle && onLaunchJetskiWebClick != null) {
                        Button(
                            onClick = { onLaunchJetskiWebClick.invoke() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Launch Original Jetski Web Frontend")
                        }
                    }
                }
            }

            // Model Provider & BYOK Section (ADR-02)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = BrandPrimary
                        )
                        Text(
                            text = "Model Provider & Credentials",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Bring-Your-Own-Key (BYOK). Keys are encrypted on-device via Android Keystore hardware security (ADR-02) and never exposed to WebView JavaScript. NOTE: This is an independent credential for the local Gemini Fallback engine and does NOT fabricate or authenticate a genuine Google/Antigravity account session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedProvider == "gemini",
                                onClick = { selectedProvider = "gemini" }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Google Gemini (Public API)")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedProvider == "custom",
                                onClick = { selectedProvider = "custom" }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Local / Custom Endpoint (Ollama / vLLM)")
                        }
                    }

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            keySavedMessage = null
                        },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                secretStorage?.saveApiKey("gemini", apiKeyInput.trim())
                                keySavedMessage = "Key saved securely to Keystore"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Key")
                        }

                        OutlinedButton(
                            onClick = {
                                secretStorage?.clearApiKey("gemini")
                                apiKeyInput = ""
                                keySavedMessage = "Key cleared"
                            }
                        ) {
                            Text("Clear")
                        }
                    }

                    if (keySavedMessage != null) {
                        Text(
                            text = keySavedMessage!!,
                            color = BrandSuccess,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Autonomy Policy (ADR-01)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = BrandPrimary
                        )
                        Text(
                            text = "Agent Autonomy & Risk Policy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Controls whether file modifications and commands require human approval before execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedAutonomy == "supervised",
                                onClick = { selectedAutonomy = "supervised" }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Supervised (Recommended)", fontWeight = FontWeight.Medium)
                                Text(
                                    "High-risk operations (write, edit) require confirmation",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedAutonomy == "readonly",
                                onClick = { selectedAutonomy = "readonly" }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Read-Only Inspection", fontWeight = FontWeight.Medium)
                                Text(
                                    "Agent cannot create or modify files",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Phase P3: Backend Architecture & Desktop Hub Pairing
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = BrandPrimary
                        )
                        Text(
                            text = "Backend Execution Architecture (Phase P3)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Select the active backend engine. Standalone Mode B runs Google's unmodified ARM64 backend on-device inside this APK. Desktop Hub relays to your desktop installation. Gemini Fallback provides an in-process fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = backendMode == BackendMode.STANDALONE_IN_APP_BACKEND,
                                onClick = { onBackendModeChange?.invoke(BackendMode.STANDALONE_IN_APP_BACKEND) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Standalone In-App Backend (Mode B, Primary)", fontWeight = FontWeight.SemiBold, color = BrandPrimary)
                                Text(
                                    "Runs the genuine Google Antigravity language server directly on-device inside this Android app via PRoot virtualization. No desktop host required.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = backendMode == BackendMode.ORIGINAL_DESKTOP_HUB,
                                onClick = { onBackendModeChange?.invoke(BackendMode.ORIGINAL_DESKTOP_HUB) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Original Desktop Hub (Mode A, Paired)", fontWeight = FontWeight.Medium)
                                Text(
                                    "Relays to desktop language_server.exe with genuine Google account, prompt credits, and models over Wi-Fi/LAN.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = backendMode == BackendMode.LOCAL_GEMINI_FALLBACK,
                                onClick = { onBackendModeChange?.invoke(BackendMode.LOCAL_GEMINI_FALLBACK) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Local Gemini Agent Engine (Fallback)", fontWeight = FontWeight.Medium)
                                Text(
                                    "In-process Android agent engine using BYOK Gemini API key from Keystore and sandboxed local tools.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Standalone Mode B Controls & Status
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "On-Device Backend Status (Mode B)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                when (localBackendState) {
                                    is LocalBackendLifecycleManager.LocalBackendState.Running -> {
                                        StatusBadge(text = "Running (PID ${localBackendState.pid})", color = BrandSuccess)
                                    }
                                    is LocalBackendLifecycleManager.LocalBackendState.Starting,
                                    is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> {
                                        StatusBadge(text = "Starting", color = BrandPrimary)
                                    }
                                    is LocalBackendLifecycleManager.LocalBackendState.Error -> {
                                        StatusBadge(text = "Error", color = BrandError)
                                    }
                                    is LocalBackendLifecycleManager.LocalBackendState.Stopped -> {
                                        StatusBadge(text = "Stopped", color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }

                            when (localBackendState) {
                                is LocalBackendLifecycleManager.LocalBackendState.Running -> {
                                    Text(
                                        text = "Loopback Port: ${localBackendState.port} (HTTPS: ${localBackendState.httpsPort}) | LS Version: ${localBackendState.lsVersion}",
                                        fontSize = 12.sp
                                    )
                                    if (localBackendState.memoryRssKb > 0) {
                                        Text(
                                            text = "Memory RSS: ${localBackendState.memoryRssKb / 1024} MB",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val status = localBackendState.hubStatus
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (status != null && !status.userName.isNullOrBlank()) {
                                                Text("Authenticated Account: ${status.userName} (${status.userEmail ?: ""})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandSuccess)
                                                if (!status.planName.isNullOrBlank()) {
                                                    Text("Plan: ${status.planName} ${status.teamsTier ?: ""}", fontSize = 11.sp, color = BrandPrimary)
                                                }
                                            } else {
                                                Text("Authentication: Not signed in", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        IconButton(onClick = { onRefreshAccountStatus?.invoke() }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh account status")
                                        }
                                    }
                                    if (status != null && status.clientModelLabels.isNotEmpty()) {
                                        Text("Available Models (${status.availableModelsCount}): ${status.clientModelLabels.take(3).joinToString(", ")}...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { onSignInGoogle?.invoke() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Sign in with Google")
                                        }

                                        OutlinedButton(
                                            onClick = { onRestartLocalBackend?.invoke() }
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                        }

                                        OutlinedButton(
                                            onClick = { onStopLocalBackend?.invoke() }
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Stop")
                                        }
                                    }
                                }
                                is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> {
                                    Text(
                                        text = "${localBackendState.stage}: ${localBackendState.message}",
                                        fontSize = 12.sp,
                                        color = BrandPrimary
                                    )
                                    LinearProgressIndicator(
                                        progress = { (localBackendState.percent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                is LocalBackendLifecycleManager.LocalBackendState.Starting -> {
                                    Text(
                                        text = localBackendState.message,
                                        fontSize = 12.sp,
                                        color = BrandPrimary
                                    )
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                is LocalBackendLifecycleManager.LocalBackendState.Error -> {
                                    Text(
                                        text = localBackendState.message,
                                        fontSize = 12.sp,
                                        color = BrandError
                                    )
                                    Button(
                                        onClick = { onStartLocalBackend?.invoke() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry Launching Backend")
                                    }
                                }
                                is LocalBackendLifecycleManager.LocalBackendState.Stopped -> {
                                    Text(
                                        text = "The genuine language server is stopped. Click Start to launch it in the local PRoot environment.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { onStartLocalBackend?.invoke() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Start Standalone Backend")
                                    }
                                }
                            }

                            // OAuth URL Detection Banner
                            if (!latestAuthUrl.isNullOrBlank()) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Google Account Authorization Ready",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "Complete Google Sign-in in your mobile browser to link your Antigravity account:",
                                            fontSize = 11.sp
                                        )
                                        Button(
                                            onClick = { onOpenAuthUrl?.invoke(latestAuthUrl) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Authorize in Browser")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Desktop Antigravity Hub Pairing",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Discovery metadata is located at ~/.gemini/antigravity/daemon/hub_<port>.json on your desktop machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hubHost,
                            onValueChange = { hubHost = it },
                            label = { Text("Desktop Host / IP") },
                            placeholder = { Text("10.0.2.2 or 127.0.0.1") },
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = hubPort,
                            onValueChange = { hubPort = it },
                            label = { Text("Port") },
                            placeholder = { Text("57734") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = hubUseTls,
                            onCheckedChange = { hubUseTls = it }
                        )
                        Text(
                            text = if (hubUseTls) "HTTPS (Port 57733 with TLS)" else "HTTP (Port 57734, Direct Loopback/LAN)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedTextField(
                        value = hubCsrfToken,
                        onValueChange = { hubCsrfToken = it },
                        label = { Text("Desktop CSRF Token (from hub_<port>.json)") },
                        placeholder = { Text("UUID token") },
                        visualTransformation = if (showHubCsrf) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showHubCsrf = !showHubCsrf }) {
                                Icon(
                                    imageVector = if (showHubCsrf) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle token visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val portInt = hubPort.trim().toIntOrNull() ?: if (hubUseTls) 57733 else 57734
                                onConnectDesktopHub?.invoke(
                                    DesktopHubConfig(
                                        host = hubHost.trim(),
                                        port = portInt,
                                        csrfToken = hubCsrfToken.trim(),
                                        useTls = hubUseTls
                                    )
                                )
                            },
                            enabled = hubCsrfToken.isNotBlank() && hubConnectionState !is HubConnectionState.Connected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pair with Desktop Hub")
                        }

                        if (hubConnectionState is HubConnectionState.Connected) {
                            OutlinedButton(
                                onClick = { onDisconnectDesktopHub?.invoke() }
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }

                    // Connection State Badge & Details
                    when (hubConnectionState) {
                        is HubConnectionState.Connected -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        StatusBadge(text = "Desktop Hub Paired", color = BrandSuccess)
                                        Text(
                                            text = hubConnectionState.config.baseUrl,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    val status = hubConnectionState.status
                                    if (!status.userName.isNullOrBlank()) {
                                        Text("User: ${status.userName} (${status.userEmail ?: ""})", fontSize = 12.sp)
                                    }
                                    Text("Plan: ${status.planName ?: "Pro"} (${status.teamsTier ?: "TEAMS_TIER_PRO"})", fontSize = 12.sp)
                                    status.availablePromptCredits?.let {
                                        Text("Prompt Credits: $it", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandPrimary)
                                    }
                                    if (status.clientModelLabels.isNotEmpty()) {
                                        Text("Models (${status.availableModelsCount}): ${status.clientModelLabels.take(3).joinToString(", ")}...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        is HubConnectionState.Connecting -> {
                            Text(
                                text = "Connecting to Desktop Hub at ${hubHost}:${hubPort}...",
                                color = BrandPrimary,
                                fontSize = 12.sp
                            )
                        }
                        is HubConnectionState.Error -> {
                            Text(
                                text = "Pairing Error: ${hubConnectionState.message}",
                                color = BrandError,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        is HubConnectionState.Disconnected -> {
                            Text(
                                text = "Status: Disconnected. Pair with your desktop daemon to enable genuine Antigravity agent features.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // About Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Antigravity Android Port (Option C Hybrid)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Version 0.5.0 (Phase P0.5 — Jetski Web + Native LanguageServerService)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Original React 19 Jetski Web frontend host with in-process Kotlin Connect-RPC backend and Clean-Room Jetbox Fallback.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
