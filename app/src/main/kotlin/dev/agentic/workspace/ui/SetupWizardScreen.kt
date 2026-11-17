package dev.agentic.workspace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.BackendProvisioningManager
import dev.agentic.core.network.hub.LocalBackendLifecycleManager
import dev.agentic.core.network.jetski.BundleValidationResult
import dev.agentic.core.network.jetski.FrontendUiMode
import dev.agentic.core.network.jetski.JetskiFrontendModeManager
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// Clean-room Antigravity Jetbox UI tokens matching compiled_tailwind.css
private val AgBackground = Color(0xFF101010)
private val AgCard = Color(0xFF191919)
private val AgCardBorder = Color(0xFF222222)
private val AgMutedSurface = Color(0xFF151515)
private val AgSecondary = Color(0xFF272727)
private val AgForeground = Color(0xFFCCCCCC)
private val AgMutedText = Color(0xFF888888)
private val AgSubtleText = Color(0xFF6E6E6E)
private val AgPrimaryBlue = Color(0xFF007ACC)
private val AgAccentBlue = Color(0xFF4285F4)
private val AgSuccess = Color(0xFF4EC9B0)
private val AgError = Color(0xFFDE5555)

@Composable
fun SetupWizardScreen(
    provisioningManager: BackendProvisioningManager,
    modeManager: JetskiFrontendModeManager,
    backendLifecycleManager: LocalBackendLifecycleManager?,
    rpcHandler: LanguageServerGrpcWebHandler?,
    onSetupComplete: () -> Unit,
    onSkipToFallback: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var latestRelease by remember { mutableStateOf<BackendProvisioningManager.ResolvedRelease?>(null) }
    var isResolvingRelease by remember { mutableStateOf(true) }

    var isWorking by remember { mutableStateOf(false) }
    var progressStage by remember { mutableStateOf("") }
    var progressPercent by remember { mutableIntStateOf(0) }
    var progressMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showCustomKeySection by remember { mutableStateOf(false) }
    var customKeyInput by remember { mutableStateOf("") }
    var showLicenses by remember { mutableStateOf(false) }

    val googleSans = remember {
        val tf = runCatching {
            val file = File("/product/fonts/GoogleSans-Regular.ttf")
            if (file.exists() && file.canRead()) android.graphics.Typeface.createFromFile(file)
            else android.graphics.Typeface.create("google-sans", android.graphics.Typeface.NORMAL)
        }.getOrNull()
        if (tf != null) FontFamily(tf) else FontFamily.SansSerif
    }

    LaunchedEffect(Unit) {
        isResolvingRelease = true
        latestRelease = runCatching { provisioningManager.resolveLatestRelease() }.getOrNull()
        isResolvingRelease = false
    }

    suspend fun finalizeProvisionedBinary(binaryFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                progressStage = "Extracting UI"
                progressPercent = 96
                progressMessage = "Extracting embedded workspace frontend..."
            }
            val res = binaryFile.inputStream().use { stream ->
                modeManager.importBundleFromStream(stream, binaryFile.name)
            }
            if (res !is BundleValidationResult.Valid) {
                return@withContext Result.failure(
                    IllegalStateException("Failed to extract valid web UI from binary: $res")
                )
            }
            modeManager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB)
            rpcHandler?.setBackendMode(BackendMode.STANDALONE_IN_APP_BACKEND)

            withContext(Dispatchers.Main) {
                progressStage = "Starting Engine"
                progressPercent = 99
                progressMessage = "Starting local language server..."
            }
            backendLifecycleManager?.startBackend()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun launchDownloadFlow(urlOverride: String? = null, sha256Override: String? = null) {
        if (isWorking) return
        isWorking = true
        errorMessage = null
        progressStage = "Preparing"
        progressPercent = 2
        progressMessage = "Initializing workspace setup..."

        scope.launch {
            val res = provisioningManager.provision(
                downloadUrlOverride = urlOverride,
                expectedSha256 = sha256Override,
                forceRedownload = !urlOverride.isNullOrBlank()
            ) { prog ->
                scope.launch(Dispatchers.Main) {
                    progressStage = prog.stage
                    progressPercent = prog.percent.coerceIn(0, 95)
                    progressMessage = prog.message
                }
            }
            if (res.isSuccess) {
                val fin = finalizeProvisionedBinary(res.getOrThrow())
                if (fin.isSuccess) {
                    isWorking = false
                    onSetupComplete()
                } else {
                    isWorking = false
                    errorMessage = fin.exceptionOrNull()?.message ?: "Failed to finalize workspace."
                }
            } else {
                isWorking = false
                errorMessage = res.exceptionOrNull()?.message ?: "Download failed."
            }
        }
    }

    val binaryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null || isWorking) return@rememberLauncherForActivityResult
        isWorking = true
        errorMessage = null
        progressStage = "Importing"
        progressPercent = 15
        progressMessage = "Reading selected file..."

        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    provisioningManager.importBinaryFromStream(inp) { prog ->
                        scope.launch(Dispatchers.Main) {
                            progressStage = prog.stage
                            progressPercent = prog.percent.coerceIn(0, 95)
                            progressMessage = prog.message
                        }
                    }
                } ?: Result.failure(IllegalStateException("Unable to open selected file."))
            }.getOrElse { Result.failure(it) }

            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val fin = finalizeProvisionedBinary(res.getOrThrow())
                    isWorking = false
                    if (fin.isSuccess) {
                        onSetupComplete()
                    } else {
                        errorMessage = fin.exceptionOrNull()?.message ?: "Failed to extract UI bundle."
                    }
                } else {
                    isWorking = false
                    errorMessage = res.exceptionOrNull()?.message ?: "Binary import failed."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Clean-room geometric Antigravity arch emblem
            AntigravityArchEmblem(modifier = Modifier.size(60.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ANTIGRAVITY",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = googleSans,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Workspace Setup",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = googleSans,
                    fontWeight = FontWeight.Medium
                ),
                color = AgForeground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Bring your own official runtime or fetch the latest Linux ARM64 release directly from Google.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                color = AgMutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            // Primary Card: Fetch Latest Official Runtime
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = AgCard,
                border = BorderStroke(1.dp, AgCardBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = AgAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Official Upstream Runtime",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White
                            )
                        }

                        val versionLabel = when {
                            isResolvingRelease -> "Checking..."
                            latestRelease != null -> "v${latestRelease!!.version}"
                            else -> "Latest"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AgSecondary)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = versionLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = if (latestRelease?.fromLiveIndex == true) AgSuccess else AgMutedText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Downloads the latest Linux ARM64 language server directly from Google's distribution server (~226 MB), verifies architecture & embedded web UI integrity, and sets up your local workspace.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = googleSans,
                            lineHeight = 18.sp
                        ),
                        color = AgMutedText
                    )

                    if (isWorking) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AgAccentBlue,
                            trackColor = AgMutedSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (progressMessage.isNotBlank()) progressMessage else progressStage,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = googleSans),
                                color = AgForeground,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$progressPercent%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = AgAccentBlue
                            )
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AgError.copy(alpha = 0.14f))
                                .border(1.dp, AgError.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                color = AgError
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { launchDownloadFlow() },
                        enabled = !isWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AgPrimaryBlue,
                            contentColor = Color.White,
                            disabledContainerColor = AgSecondary,
                            disabledContentColor = AgSubtleText
                        )
                    ) {
                        Text(
                            text = if (isWorking) "Installing..." else "Download & Install Latest",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = googleSans,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary Card: Bring Your Own Binary (Local File)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = AgCard,
                border = BorderStroke(1.dp, AgCardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = AgForeground,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bring Your Own Binary (Local File)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = googleSans,
                                fontWeight = FontWeight.Medium
                            ),
                            color = AgForeground
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Already have language_server-linux-arm64 on your device? Select it directly.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                        color = AgMutedText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { binaryPickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AgCardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AgMutedSurface,
                            contentColor = AgForeground
                        )
                    ) {
                        Text(
                            text = "Select Local Binary",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = googleSans)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tertiary Card: Custom URL / Binary Key
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = AgCard,
                border = BorderStroke(1.dp, AgCardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomKeySection = !showCustomKeySection },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = AgMutedText,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Custom URL or Binary Key",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = googleSans,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = AgForeground
                            )
                        }
                        Icon(
                            imageVector = if (showCustomKeySection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = AgMutedText
                        )
                    }

                    AnimatedVisibility(visible = showCustomKeySection) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                text = "Paste an HTTPS download URL or a JSON binary key {\"url\":\"...\",\"sha256\":\"...\"}.",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = googleSans),
                                color = AgMutedText
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customKeyInput,
                                onValueChange = { customKeyInput = it },
                                placeholder = {
                                    Text(
                                        "https://... or {\"url\":..., \"sha256\":...}",
                                        color = AgSubtleText,
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AgPrimaryBlue,
                                    unfocusedBorderColor = AgCardBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = AgForeground,
                                    focusedContainerColor = AgMutedSurface,
                                    unfocusedContainerColor = AgMutedSurface
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val (parsedUrl, parsedSha) = parseBinaryKeyInput(customKeyInput)
                                    if (parsedUrl.isNullOrBlank()) {
                                        errorMessage = "Enter a valid http(s) URL or JSON binary key containing \"url\"."
                                    } else {
                                        launchDownloadFlow(urlOverride = parsedUrl, sha256Override = parsedSha)
                                    }
                                },
                                enabled = !isWorking && customKeyInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AgSecondary,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Install from Key / URL", fontFamily = googleSans)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onSkipToFallback,
                enabled = !isWorking
            ) {
                Text(
                    text = "Continue without runtime (Remote Hub / Fallback)",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = googleSans),
                    color = AgMutedText
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Unofficial disclaimer & open-source notices
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { showLicenses = !showLicenses }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = AgSubtleText,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Unofficial compatibility container • Open-Source Notices",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = googleSans),
                    color = AgSubtleText
                )
            }

            AnimatedVisibility(visible = showLicenses) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = AgMutedSurface,
                    border = BorderStroke(1.dp, AgCardBorder)
                ) {
                    Text(
                        text = "This open-source Android host is unaffiliated with Google LLC and contains no proprietary Google binaries. " +
                            "When triggered by the user, binaries are downloaded directly from Google's public distribution servers or imported locally.\n\n" +
                            "Bundled open-source components: PRoot (GPL-2.0), talloc (LGPL-3.0), android-shmem (Apache-2.0), Debian ARM64 base userspace.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = googleSans,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = AgMutedText,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

private fun parseBinaryKeyInput(raw: String): Pair<String?, String?> {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
        return runCatching {
            val obj = JSONObject(trimmed)
            val url = obj.optString("url", "").takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val sha = obj.optString("sha256", "").takeIf { it.isNotBlank() }
            url to sha
        }.getOrDefault(null to null)
    }
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed to null
    } else {
        null to null
    }
}

@Composable
private fun AntigravityArchEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.86f)
            cubicTo(
                w * 0.30f, h * 0.62f,
                w * 0.40f, h * 0.14f,
                w * 0.50f, h * 0.14f
            )
            cubicTo(
                w * 0.60f, h * 0.14f,
                w * 0.70f, h * 0.62f,
                w * 0.84f, h * 0.86f
            )
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(Color.White, Color(0xFF8AB4F8)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            style = Stroke(
                width = w * 0.14f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
