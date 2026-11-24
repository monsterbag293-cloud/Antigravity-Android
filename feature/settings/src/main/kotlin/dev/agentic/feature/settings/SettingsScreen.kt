package dev.agentic.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.data.KeystoreSecretStorage
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.DesktopHubConfig
import dev.agentic.core.network.hub.HubConnectionState
import dev.agentic.core.network.hub.LocalBackendLifecycleManager
import java.io.File

private val AgBackground = Color(0xFF101010)
private val AgCard = Color(0xFF191919)
private val AgCardBorder = Color(0xFF222222)
private val AgMutedSurface = Color(0xFF151515)
private val AgSecondary = Color(0xFF272727)
private val AgForeground = Color(0xFFCCCCCC)
private val AgMutedText = Color(0xFF888888)
private val AgSubtleText = Color(0xFF6E6E6E)
private val AgAccentBlue = Color(0xFF4285F4)
private val AgSuccess = Color(0xFF4EC9B0)
private val AgError = Color(0xFFDE5555)

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
    qemuCompatEnabled: Boolean = false,
    onQemuCompatChange: ((Boolean) -> Unit)? = null,
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
    onDisableRemoteHost: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    val googleSans = remember {
        val tf = runCatching {
            val file = File("/product/fonts/GoogleSans-Regular.ttf")
            if (file.exists() && file.canRead()) android.graphics.Typeface.createFromFile(file)
            else android.graphics.Typeface.create("google-sans", android.graphics.Typeface.NORMAL)
        }.getOrNull()
        if (tf != null) FontFamily(tf) else FontFamily.SansSerif
    }

    var apiKeyInput by remember {
        mutableStateOf(secretStorage?.getApiKey("gemini") ?: "")
    }
    var keySavedMessage by remember { mutableStateOf<String?>(null) }
    var showApiKey by remember { mutableStateOf(false) }

    var hubHost by remember { mutableStateOf("10.0.2.2") }
    var hubPort by remember { mutableStateOf("57734") }
    var hubCsrfToken by remember { mutableStateOf("") }
    var hubUseTls by remember { mutableStateOf(false) }
    var showHubCsrf by remember { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AgAccentBlue,
        unfocusedBorderColor = AgCardBorder,
        focusedLabelColor = AgAccentBlue,
        unfocusedLabelColor = AgMutedText,
        focusedTextColor = Color.White,
        unfocusedTextColor = AgForeground,
        cursorColor = AgAccentBlue
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Google-themed minimal top header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Host Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = googleSans,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                    Text(
                        text = activeUiModeLabel,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                        color = AgMutedText
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (badgeText, badgeColor) = when (localBackendState) {
                        is LocalBackendLifecycleManager.LocalBackendState.Running -> "Running" to AgSuccess
                        is LocalBackendLifecycleManager.LocalBackendState.Starting,
                        is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> "Starting" to AgAccentBlue
                        is LocalBackendLifecycleManager.LocalBackendState.Error -> "Error" to AgError
                        is LocalBackendLifecycleManager.LocalBackendState.Stopped -> "Stopped" to AgMutedText
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = googleSans,
                                fontWeight = FontWeight.Medium
                            ),
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    if (onClose != null) {
                        Surface(
                            shape = CircleShape,
                            color = AgSecondary,
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { onClose() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = AgForeground,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = AgCardBorder, thickness = 1.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Card 1: On-Device Runtime & Account
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = AgCard,
                    border = BorderStroke(1.dp, AgCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = AgAccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Runtime & Account",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = googleSans,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White
                                )
                            }

                            if (localBackendState is LocalBackendLifecycleManager.LocalBackendState.Running &&
                                onRefreshAccountStatus != null
                            ) {
                                IconButton(
                                    onClick = { onRefreshAccountStatus.invoke() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh status",
                                        tint = AgMutedText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        when (localBackendState) {
                            is LocalBackendLifecycleManager.LocalBackendState.Running -> {
                                val status = localBackendState.hubStatus
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AgMutedSurface,
                                    border = BorderStroke(1.dp, AgCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (status != null && !status.userName.isNullOrBlank()) {
                                            Text(
                                                text = "${status.userName}${if (!status.userEmail.isNullOrBlank()) " (${status.userEmail})" else ""}",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = googleSans,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = Color.White
                                            )
                                            if (!status.planName.isNullOrBlank()) {
                                                Text(
                                                    text = "Plan: ${status.planName}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                                    color = AgSuccess
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Not signed in to Google",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                                color = AgMutedText
                                            )
                                        }

                                        val memStr = if (localBackendState.memoryRssKb > 0) {
                                            " • ${localBackendState.memoryRssKb / 1024} MB RAM"
                                        } else ""
                                        Text(
                                            text = "Port ${localBackendState.port} • v${localBackendState.lsVersion}$memStr",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = googleSans),
                                            color = AgSubtleText
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (status == null || status.userName.isNullOrBlank()) {
                                        Button(
                                            onClick = { onSignInGoogle?.invoke() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AgAccentBlue,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Sign in", fontFamily = googleSans)
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { onRestartLocalBackend?.invoke() },
                                        border = BorderStroke(1.dp, AgCardBorder),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AgForeground),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Restart", fontFamily = googleSans)
                                    }

                                    OutlinedButton(
                                        onClick = { onStopLocalBackend?.invoke() },
                                        border = BorderStroke(1.dp, AgCardBorder),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AgError),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Stop", fontFamily = googleSans)
                                    }
                                }
                            }

                            is LocalBackendLifecycleManager.LocalBackendState.Provisioning -> {
                                Text(
                                    text = "${localBackendState.stage}: ${localBackendState.message}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                    color = AgAccentBlue
                                )
                                LinearProgressIndicator(
                                    progress = { (localBackendState.percent / 100f).coerceIn(0f, 1f) },
                                    color = AgAccentBlue,
                                    trackColor = AgSecondary,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            is LocalBackendLifecycleManager.LocalBackendState.Starting -> {
                                Text(
                                    text = localBackendState.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                    color = AgAccentBlue
                                )
                                LinearProgressIndicator(
                                    color = AgAccentBlue,
                                    trackColor = AgSecondary,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            is LocalBackendLifecycleManager.LocalBackendState.Error -> {
                                Text(
                                    text = localBackendState.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                    color = AgError
                                )
                                Button(
                                    onClick = { onStartLocalBackend?.invoke() },
                                    colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry Backend", fontFamily = googleSans)
                                }
                            }

                            is LocalBackendLifecycleManager.LocalBackendState.Stopped -> {
                                Text(
                                    text = "On-device language server is stopped.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                    color = AgMutedText
                                )
                                Button(
                                    onClick = { onStartLocalBackend?.invoke() },
                                    colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Start Backend", fontFamily = googleSans)
                                }
                            }
                        }

                        if (!latestAuthUrl.isNullOrBlank()) {
                            Button(
                                onClick = { onOpenAuthUrl?.invoke(latestAuthUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Authorize Google Account in Browser", fontFamily = googleSans)
                            }
                        }

                        if (onQemuCompatChange != null) {
                            HorizontalDivider(color = AgCardBorder, thickness = 1.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "ARMv8.0 Compatibility (QEMU)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = googleSans,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Emulates LSE atomics for older ARM64 CPUs.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                        color = AgMutedText
                                    )
                                }
                                Switch(
                                    checked = qemuCompatEnabled,
                                    onCheckedChange = { onQemuCompatChange.invoke(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = AgAccentBlue,
                                        uncheckedThumbColor = AgMutedText,
                                        uncheckedTrackColor = AgSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                // Card 2: Execution Mode
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = AgCard,
                    border = BorderStroke(1.dp, AgCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = AgAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Backend Mode",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White
                            )
                        }

                        val modes = listOf(
                            Triple(BackendMode.STANDALONE_IN_APP_BACKEND, "On-Device Standalone", "Runs directly on Android"),
                            Triple(BackendMode.ORIGINAL_DESKTOP_HUB, "Desktop Hub", "Relay to desktop over Wi-Fi/LAN"),
                            Triple(BackendMode.LOCAL_GEMINI_FALLBACK, "Gemini API (BYOK)", "Direct Gemini API key fallback")
                        )

                        modes.forEach { (mode, title, subtitle) ->
                            val selected = backendMode == mode
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBackendModeChange?.invoke(mode) }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onBackendModeChange?.invoke(mode) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AgAccentBlue,
                                        unselectedColor = AgMutedText
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = googleSans,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = if (selected) Color.White else AgForeground
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                        color = AgMutedText
                                    )
                                }
                            }
                        }

                        if (backendMode == BackendMode.ORIGINAL_DESKTOP_HUB) {
                            HorizontalDivider(color = AgCardBorder, thickness = 1.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = hubHost,
                                    onValueChange = { hubHost = it },
                                    label = { Text("Host / IP") },
                                    colors = textFieldColors,
                                    modifier = Modifier.weight(2f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = hubPort,
                                    onValueChange = { hubPort = it },
                                    label = { Text("Port") },
                                    colors = textFieldColors,
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            OutlinedTextField(
                                value = hubCsrfToken,
                                onValueChange = { hubCsrfToken = it },
                                label = { Text("CSRF Token") },
                                colors = textFieldColors,
                                visualTransformation = if (showHubCsrf) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showHubCsrf = !showHubCsrf }) {
                                        Icon(
                                            imageVector = if (showHubCsrf) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle token visibility",
                                            tint = AgMutedText
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
                                    colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Pair Hub", fontFamily = googleSans)
                                }
                                if (hubConnectionState is HubConnectionState.Connected) {
                                    OutlinedButton(
                                        onClick = { onDisconnectDesktopHub?.invoke() },
                                        border = BorderStroke(1.dp, AgCardBorder),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Disconnect", fontFamily = googleSans, color = AgForeground)
                                    }
                                }
                            }
                        }

                        if (backendMode == BackendMode.LOCAL_GEMINI_FALLBACK) {
                            HorizontalDivider(color = AgCardBorder, thickness = 1.dp)
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = {
                                    apiKeyInput = it
                                    keySavedMessage = null
                                },
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AIzaSy...") },
                                colors = textFieldColors,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle key visibility",
                                            tint = AgMutedText
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
                                        keySavedMessage = "Key saved"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save Key", fontFamily = googleSans)
                                }
                                OutlinedButton(
                                    onClick = {
                                        secretStorage?.clearApiKey("gemini")
                                        apiKeyInput = ""
                                        keySavedMessage = "Key cleared"
                                    },
                                    border = BorderStroke(1.dp, AgCardBorder),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Clear", fontFamily = googleSans, color = AgForeground)
                                }
                            }
                            if (keySavedMessage != null) {
                                Text(
                                    text = keySavedMessage!!,
                                    color = AgSuccess,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans)
                                )
                            }
                        }
                    }
                }

                // Card 3: Web Bundle
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = AgCard,
                    border = BorderStroke(1.dp, AgCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = AgAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Web Frontend Bundle",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White
                            )
                        }

                        Text(
                            text = bundleStatusText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                            color = AgMutedText
                        )

                        if (!bundleErrorText.isNullOrBlank()) {
                            Text(
                                text = bundleErrorText,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                color = AgError
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onImportBundleClick?.invoke() },
                                border = BorderStroke(1.dp, AgCardBorder),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AgForeground),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import Bundle", fontFamily = googleSans)
                            }

                            if (hasValidBundle && onClearBundleClick != null) {
                                OutlinedButton(
                                    onClick = { onClearBundleClick.invoke() },
                                    border = BorderStroke(1.dp, AgCardBorder),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AgError)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear", fontFamily = googleSans)
                                }
                            }
                        }

                        if (onClose == null && hasValidBundle && onLaunchJetskiWebClick != null) {
                            Button(
                                onClick = { onLaunchJetskiWebClick.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = AgAccentBlue, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Jetski Web", fontFamily = googleSans)
                            }
                        }
                    }
                }

                Text(
                    text = "Antigravity for Android • v1.1.0",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = googleSans),
                    color = AgSubtleText,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}
