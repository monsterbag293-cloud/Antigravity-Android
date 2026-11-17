package dev.agentic.core.network.jetski

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream

enum class FrontendUiMode(val displayTitle: String) {
    IMPORTED_JETSKI_WEB("Original Jetski Web Frontend"),
    REMOTE_HOST("Remote Host"),
    NATIVE_JETBOX_FALLBACK("Clean-Room Native Jetbox Fallback")
}

data class FrontendHostState(
    val activeMode: FrontendUiMode = FrontendUiMode.NATIVE_JETBOX_FALLBACK,
    val hasValidBundle: Boolean = false,
    val bundleMetadata: JetskiBundleMetadata? = null,
    val remoteHostUrl: String? = null,
    val statusMessage: String = "No Jetski Web bundle imported. Running Clean-Room Native Jetbox Fallback.",
    val lastValidationError: String? = null
)

/**
 * Manages the active frontend UI mode (`IMPORTED_JETSKI_WEB`, `REMOTE_HOST`, or `NATIVE_JETBOX_FALLBACK`),
 * runtime bundle import/validation via [JetskiBundleLoader], and remote host URL configuration (Stage P0.5-D).
 */
class JetskiFrontendModeManager(
    private val bundleStorageFile: File,
    private val bundleLoader: JetskiBundleLoader = JetskiBundleLoader(),
    private val candidateDiscoveryFiles: List<File> = emptyList()
) {
    @Volatile
    private var loadedBundle: JetskiBundleLoader.LoadedJetskiBundle? = null

    private val _state = MutableStateFlow(FrontendHostState())
    val state: StateFlow<FrontendHostState> = _state.asStateFlow()

    fun currentBundle(): JetskiBundleLoader.LoadedJetskiBundle? = loadedBundle

    /**
     * Checks whether a validated bundle already exists in app storage or in any configured
     * local discovery paths. When found, automatically activates [FrontendUiMode.IMPORTED_JETSKI_WEB].
     */
    fun initializeFromStorage(): FrontendHostState {
        if (bundleStorageFile.exists() && bundleStorageFile.length() > 0) {
            val (res, bundle) = bundleLoader.validateAndOpen(bundleStorageFile)
            if (res is BundleValidationResult.Valid && bundle != null) {
                loadedBundle = bundle
                _state.value = FrontendHostState(
                    activeMode = FrontendUiMode.IMPORTED_JETSKI_WEB,
                    hasValidBundle = true,
                    bundleMetadata = res.metadata,
                    statusMessage = "Loaded Jetski Web bundle (${res.metadata.entryCount} assets, ${res.metadata.zipLength / 1024} KB)",
                    lastValidationError = null
                )
                return _state.value
            }
        }

        for (candidate in candidateDiscoveryFiles) {
            if (candidate.exists() && candidate.isFile && candidate.canRead()) {
                val (res, _) = candidate.inputStream().use { stream ->
                    bundleLoader.importBundleFromStream(stream, bundleStorageFile)
                }
                if (res is BundleValidationResult.Valid) {
                    val (reopenRes, reopenedBundle) = bundleLoader.validateAndOpen(bundleStorageFile)
                    if (reopenRes is BundleValidationResult.Valid && reopenedBundle != null) {
                        loadedBundle = reopenedBundle
                        _state.value = FrontendHostState(
                            activeMode = FrontendUiMode.IMPORTED_JETSKI_WEB,
                            hasValidBundle = true,
                            bundleMetadata = reopenRes.metadata,
                            statusMessage = "Auto-discovered Jetski Web bundle from ${candidate.name} (${reopenRes.metadata.entryCount} assets)",
                            lastValidationError = null
                        )
                        return _state.value
                    }
                }
            }
        }

        _state.value = FrontendHostState(
            activeMode = FrontendUiMode.NATIVE_JETBOX_FALLBACK,
            hasValidBundle = false,
            bundleMetadata = null,
            statusMessage = "No Jetski Web bundle imported. Import language_server.exe or jetski-web.zip to launch the original Antigravity frontend.",
            lastValidationError = null
        )
        return _state.value
    }

    /**
     * Imports a user-selected `language_server.exe` or `jetski-web.zip` stream (e.g. from Android SAF),
     * extracts only the embedded ZIP slice into [bundleStorageFile], validates all required assets,
     * and switches immediately to [FrontendUiMode.IMPORTED_JETSKI_WEB] on success.
     */
    fun importBundleFromStream(inputStream: InputStream, sourceLabel: String = "SAF"): BundleValidationResult {
        val (result, bundle) = bundleLoader.importBundleFromStream(inputStream, bundleStorageFile)
        when (result) {
            is BundleValidationResult.Valid -> {
                loadedBundle = bundle
                _state.update { current ->
                    current.copy(
                        activeMode = FrontendUiMode.IMPORTED_JETSKI_WEB,
                        hasValidBundle = true,
                        bundleMetadata = result.metadata,
                        statusMessage = "Imported Jetski Web bundle from $sourceLabel (${result.metadata.entryCount} assets, ${result.metadata.zipLength / 1024} KB)",
                        lastValidationError = null
                    )
                }
            }
            is BundleValidationResult.InvalidFormat -> {
                _state.update { current ->
                    current.copy(
                        lastValidationError = "Invalid bundle format: ${result.reason}"
                    )
                }
            }
            is BundleValidationResult.IncompleteBundle -> {
                _state.update { current ->
                    current.copy(
                        lastValidationError = "Incomplete bundle. Missing required assets: ${result.missingAssets.joinToString(", ")}"
                    )
                }
            }
            is BundleValidationResult.IncompatibleBundle -> {
                _state.update { current ->
                    current.copy(
                        lastValidationError = "Incompatible bundle: ${result.reason}"
                    )
                }
            }
        }
        return result
    }

    fun clearImportedBundle() {
        loadedBundle = null
        if (bundleStorageFile.exists()) {
            bundleStorageFile.delete()
        }
        _state.update { current ->
            val nextMode = if (!current.remoteHostUrl.isNullOrBlank() && current.activeMode == FrontendUiMode.REMOTE_HOST) {
                FrontendUiMode.REMOTE_HOST
            } else {
                FrontendUiMode.NATIVE_JETBOX_FALLBACK
            }
            current.copy(
                activeMode = nextMode,
                hasValidBundle = false,
                bundleMetadata = null,
                statusMessage = "Imported bundle removed. Active mode: ${nextMode.displayTitle}.",
                lastValidationError = null
            )
        }
    }

    fun activateRemoteHost(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            _state.update { current ->
                current.copy(
                    lastValidationError = "Remote Host URL must start with https:// or http://"
                )
            }
            return false
        }
        _state.update { current ->
            current.copy(
                activeMode = FrontendUiMode.REMOTE_HOST,
                remoteHostUrl = trimmed,
                statusMessage = "Connected to Remote Host ($trimmed)",
                lastValidationError = null
            )
        }
        return true
    }

    fun disableRemoteHost() {
        _state.update { current ->
            val nextMode = if (current.hasValidBundle) {
                FrontendUiMode.IMPORTED_JETSKI_WEB
            } else {
                FrontendUiMode.NATIVE_JETBOX_FALLBACK
            }
            current.copy(
                activeMode = nextMode,
                remoteHostUrl = null,
                statusMessage = "Remote Host disabled. Switched to ${nextMode.displayTitle}.",
                lastValidationError = null
            )
        }
    }

    fun switchToMode(targetMode: FrontendUiMode): Boolean {
        val current = _state.value
        return when (targetMode) {
            FrontendUiMode.IMPORTED_JETSKI_WEB -> {
                if (!current.hasValidBundle || loadedBundle == null) {
                    _state.update {
                        it.copy(lastValidationError = "Cannot switch to Original Jetski Web Frontend: No valid bundle imported yet.")
                    }
                    false
                } else {
                    _state.update {
                        it.copy(
                            activeMode = FrontendUiMode.IMPORTED_JETSKI_WEB,
                            statusMessage = "Active UI: Original Jetski Web Frontend (${current.bundleMetadata?.entryCount ?: 0} assets)",
                            lastValidationError = null
                        )
                    }
                    true
                }
            }
            FrontendUiMode.REMOTE_HOST -> {
                if (current.remoteHostUrl.isNullOrBlank()) {
                    _state.update {
                        it.copy(lastValidationError = "Cannot switch to Remote Host: Configure a valid Host URL first.")
                    }
                    false
                } else {
                    _state.update {
                        it.copy(
                            activeMode = FrontendUiMode.REMOTE_HOST,
                            statusMessage = "Active UI: Remote Host (${current.remoteHostUrl})",
                            lastValidationError = null
                        )
                    }
                    true
                }
            }
            FrontendUiMode.NATIVE_JETBOX_FALLBACK -> {
                _state.update {
                    it.copy(
                        activeMode = FrontendUiMode.NATIVE_JETBOX_FALLBACK,
                        statusMessage = "Active UI: Clean-Room Native Jetbox Fallback",
                        lastValidationError = null
                    )
                }
                true
            }
        }
    }
}
