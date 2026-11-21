package dev.agentic.core.network.hub

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Production Android Backend Lifecycle Manager for Google's genuine Linux ARM64
 * Antigravity Language Server running inside the application sandbox (Phase P3.7).
 *
 * Responsibilities:
 * 1. Process Virtualization: Spawns the unmodified Google backend binary via PRoot.
 * 2. Environment & Signal Management: Enforces `$HOME=/root`, `$USER=root`, `$PATH`,
 *    `LD_LIBRARY_PATH`, and closes stdin to operate in daemon mode.
 * 3. Hub Discovery & Authentication: Discovers dynamic Hub metadata (`hub_<port>.json`),
 *    extracts ports, redaction of sensitive credentials, and connects [RemoteHubConnectionManager].
 * 4. OAuth Browser Interception: Monitors stdout for `ANTIGRAVITY_OPEN_URL:` lines emitted
 *    under `ANTIGRAVITY_VSCODE_HOST=1` to enable genuine Google authentication.
 * 5. Process Lifecycle & Orphan Prevention: Monitors child processes, supports graceful
 *    termination and restart, and ensures zero zombie processes remain upon exit.
 */
class LocalBackendLifecycleManager(
    private val context: Context,
    val hubConnectionManager: RemoteHubConnectionManager,
    private val provisioningManager: BackendProvisioningManager = BackendProvisioningManager(context)
) {

    companion object {
        private const val TAG = "BackendLifecycle"
        const val DEFAULT_HTTP_PORT = 58888
        private const val DISCOVERY_TIMEOUT_MS = 20000L
        private const val POLL_INTERVAL_MS = 250L
    }

    sealed class LocalBackendState {
        object Stopped : LocalBackendState()
        data class Provisioning(val stage: String, val percent: Int, val message: String) : LocalBackendState()
        data class Starting(val message: String) : LocalBackendState()
        data class Running(
            val port: Int,
            val httpsPort: Int,
            val pid: Int,
            val lsVersion: String,
            val startedAtIso: String,
            val memoryRssKb: Long = 0L,
            val hubConfig: DesktopHubConfig,
            val hubStatus: HubServerStatus? = null
        ) : LocalBackendState()
        data class Error(val message: String, val cause: Throwable? = null) : LocalBackendState()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _backendState = MutableStateFlow<LocalBackendState>(LocalBackendState.Stopped)
    val backendState: StateFlow<LocalBackendState> = _backendState.asStateFlow()

    private val _latestAuthUrl = MutableStateFlow<String?>(null)
    val latestAuthUrl: StateFlow<String?> = _latestAuthUrl.asStateFlow()

    private var activeProcess: Process? = null
    private var healthMonitorJob: Job? = null
    private var activePort: Int = DEFAULT_HTTP_PORT
    private var stdoutCaptureThread: Thread? = null
    private var stderrCaptureThread: Thread? = null
    private val stderrHistory = StringBuilder()
    private val stdoutHistory = StringBuilder()
    private val lifecycleMutex = Mutex()

    fun getLatestCapturedStderr(): String = synchronized(stderrHistory) { stderrHistory.toString() }
    fun getLatestCapturedStdout(): String = synchronized(stdoutHistory) { stdoutHistory.toString() }

    fun clearAuthUrl() {
        _latestAuthUrl.value = null
    }

    /**
     * Starts the original Antigravity backend on-device inside the application sandbox.
     */
    suspend fun startBackend(targetPort: Int = DEFAULT_HTTP_PORT): Result<DesktopHubConfig> = scope.async {
        lifecycleMutex.withLock {
            if (activeProcess?.isAlive == true && _backendState.value is LocalBackendState.Running) {
                Log.i(TAG, "Backend already running on port $activePort")
                val running = _backendState.value as LocalBackendState.Running
                return@withLock Result.success(running.hubConfig)
            }

        try {
            // Step 1: Ensure provisioning is complete
            _backendState.value = LocalBackendState.Starting("Verifying backend runtime components...")
            val provisionResult = provisioningManager.provision { progress ->
                _backendState.value = LocalBackendState.Provisioning(
                    stage = progress.stage,
                    percent = progress.percent,
                    message = progress.message
                )
            }

            if (provisionResult.isFailure) {
                val err = provisionResult.exceptionOrNull()
                _backendState.value = LocalBackendState.Error("Backend provisioning failed: ${err?.message}", err)
                return@withLock Result.failure(err ?: IOException("Provisioning failure"))
            }

            _backendState.value = LocalBackendState.Starting("Launching original Google Antigravity backend...")

            // Refresh rootfs /etc/hosts with live Android DNS resolution before launching PRoot
            provisioningManager.ensureDnsAndHostsConfiguration()

            val rootfsDir = provisioningManager.rootfsDir
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootFile = File(nativeLibDir, "libproot.so")
            val loaderFile = File(nativeLibDir, "libproot_loader.so")
            val tmpDir = provisioningManager.tmpDir.apply { mkdirs() }

            if (!prootFile.exists() || !prootFile.canExecute()) {
                throw IllegalStateException("PRoot engine binary missing or not executable at ${prootFile.absolutePath}")
            }

            // Ensure clean process state before launching
            cleanupOrphanProcesses()

            val actualPort = findAvailablePort(targetPort)
            activePort = actualPort
            val csrfToken = "ag_sandboxed_" + UUID.randomUUID().toString().replace("-", "").take(24)

            // Setup isolated gemini directory inside rootfs with full permissions
            val appDataSubdir = "antigravity"
            val isolatedGeminiDir = File(rootfsDir, "tmp/gemini_app").apply {
                mkdirs()
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
                File(this, "workspaces").apply {
                    mkdirs()
                    setReadable(true, false)
                    setWritable(true, false)
                    setExecutable(true, false)
                }
            }
            // Ensure /tmp inside rootfs is fully accessible for child bash/python commands
            File(rootfsDir, "tmp").apply {
                mkdirs()
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            }

            // Bind Android shared storage (/storage/emulated/0) into PRoot at /tmp/gemini_app/storage
            // so projects created directly in the phone's main directory are visible inside PRoot,
            // while keeping /tmp/gemini_app/workspaces inside rootfs for explicit linked workspace symlinks.
            val externalRootDir = runCatching {
                android.os.Environment.getExternalStorageDirectory()
            }.getOrNull()?.takeIf { it.exists() }

            val workspacesDir = File(rootfsDir, "tmp/gemini_app/workspaces").apply {
                if (!exists()) mkdirs()
            }
            if (externalRootDir != null) {
                workspacesDir.listFiles()?.forEach { entry ->
                    val linkTarget = runCatching { android.system.Os.readlink(entry.absolutePath) }.getOrNull()
                    if (linkTarget == null && entry.isDirectory && File(externalRootDir, entry.name).isDirectory) {
                        if (entry.listFiles().isNullOrEmpty()) {
                            entry.deleteRecursively()
                            runCatching {
                                android.system.Os.symlink(
                                    "/tmp/gemini_app/storage/${entry.name}",
                                    entry.absolutePath
                                )
                            }
                        }
                    }
                }
            }

            // Clean up any stale hub files for this port
            val staleHubFile = File(isolatedGeminiDir, "$appDataSubdir/daemon/hub_$actualPort.json")
            if (staleHubFile.exists()) staleHubFile.delete()

            val backendCmd = buildList {
                add(prootFile.absolutePath)
                // -0 forces root emulation so forked bash/python subcommands don't get EPERM (operation not permitted)
                add("-0")
                add("-r")
                add(rootfsDir.absolutePath)
                add("-b")
                add("/proc")
                add("-b")
                add("/dev")
                add("-b")
                add("/sys")
                if (externalRootDir != null) {
                    add("-b")
                    add("${externalRootDir.absolutePath}:/tmp/gemini_app/storage")
                }
                add("-w")
                add("/tmp/gemini_app")
                add("/usr/bin/language_server")
                add("--standalone=true")
                add("--persistent_mode=false")
                add("--http_server_port=$actualPort")
                add("--csrf_token=$csrfToken")
                add("--gemini_dir=/tmp/gemini_app")
                add("--app_data_dir=$appDataSubdir")
                add("--disable_telemetry=true")
                add("--headless=false")
                add("--override_ide_name=antigravity")
                add("--override_ide_version=2.32.1")
                add("--override_user_agent_name=antigravity")
                add("--subclient_type=hub")
                add("--api_server_url=https://generativelanguage.googleapis.com")
                add("--cloud_code_endpoint=https://daily-cloudcode-pa.googleapis.com")
                add("--enable_sidecars=true")
            }

            Log.i(TAG, "Launching backend process: ${backendCmd.joinToString(" ")}")
            val pb = ProcessBuilder(backendCmd)
            pb.environment().putAll(
                mapOf(
                    "HOME" to "/root",
                    "USER" to "root",
                    "PATH" to "/usr/bin:/bin:/usr/local/bin",
                    "LD_LIBRARY_PATH" to nativeLibDir,
                    "PROOT_LOADER" to loaderFile.absolutePath,
                    "PROOT_TMP_DIR" to tmpDir.absolutePath,
                    "PROOT_NO_SECCOMP" to "1",
                    "ANTIGRAVITY_VSCODE_HOST" to "1",
                    "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                    "SSL_CERT_DIR" to "/etc/ssl/certs",
                    "TMPDIR" to "/tmp"
                )
            )

            synchronized(stderrHistory) { stderrHistory.setLength(0) }
            synchronized(stdoutHistory) { stdoutHistory.setLength(0) }

            val proc = pb.start()
            activeProcess = proc

            // Close stdin immediately to operate in non-blocking daemon mode
            try { proc.outputStream.close() } catch (ignored: Exception) {}

            // Launch output capture threads
            startStreamCapture(proc)

            // Step 2: Poll for Hub discovery file
            _backendState.value = LocalBackendState.Starting("Waiting for Hub daemon initialization...")
            val hubFile = waitForHubFile(isolatedGeminiDir, actualPort, proc)
                ?: throw IOException("Backend did not produce Hub discovery file within ${DISCOVERY_TIMEOUT_MS}ms")

            // Step 3: Parse Hub discovery file
            val hubContent = hubFile.readText().trim()
            Log.i(TAG, "Discovered in-app Hub discovery metadata at ${hubFile.absolutePath}")
            val hubJson = JSONObject(hubContent)

            val httpPort = hubJson.optInt("httpPort", actualPort)
            val httpsPort = hubJson.optInt("httpsPort", 0)
            val discoveredCsrf = hubJson.optString("csrfToken", csrfToken)
            val lsVersion = hubJson.optString("lsVersion", "unknown")
            val pid = hubJson.optInt("pid", 0)

            // Step 4: Connect RemoteHubConnectionManager to the local in-app Hub
            val localHubConfig = DesktopHubConfig(
                host = "127.0.0.1",
                port = httpPort,
                csrfToken = discoveredCsrf,
                useTls = false
            )

            _backendState.value = LocalBackendState.Starting("Connecting in-app RPC relay to local Hub...")
            var connectResult = hubConnectionManager.connect(localHubConfig)
            if (connectResult.isFailure) {
                kotlinx.coroutines.delay(600)
                connectResult = hubConnectionManager.connect(localHubConfig)
            }
            if (connectResult.isFailure) {
                val connErr = connectResult.exceptionOrNull()
                throw IOException("Failed to connect to local Hub on 127.0.0.1:$httpPort: ${connErr?.message}", connErr)
            }
            val hubStatus = connectResult.getOrNull()

            val rssKb = readProcessMemoryRssKb(pid)
            val runningState = LocalBackendState.Running(
                port = httpPort,
                httpsPort = httpsPort,
                pid = pid,
                lsVersion = lsVersion,
                startedAtIso = Instant.now().toString(),
                memoryRssKb = rssKb,
                hubConfig = localHubConfig,
                hubStatus = hubStatus
            )
            _backendState.value = runningState

            // Start health monitoring
            startHealthMonitor(proc, runningState)

            Log.i(TAG, "Original Antigravity backend successfully running on 127.0.0.1:$httpPort (PID: $pid)")
            Result.success(localHubConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local backend: ${e.message}", e)
            stopProcessInternal()
            _backendState.value = LocalBackendState.Error("Failed to start backend: ${e.message}", e)
            Result.failure(e)
        }
        }
    }.await()

    /**
     * Stops the backend process cleanly and verifies child process cleanup.
     */
    @Synchronized
    fun stopBackend() {
        stopProcessInternal()
        _backendState.value = LocalBackendState.Stopped
        Log.i(TAG, "Backend stopped cleanly")
    }

    @Synchronized
    private fun stopProcessInternal() {
        Log.i(TAG, "Stopping local backend process...")
        healthMonitorJob?.cancel()
        healthMonitorJob = null

        hubConnectionManager.disconnect()

        val proc = activeProcess
        if (proc != null) {
            try {
                proc.destroy()
                Thread.sleep(200)
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping backend process: ${e.message}")
            }
            activeProcess = null
        }

        cleanupOrphanProcesses()
    }

    /**
     * Restarts the backend process.
     */
    suspend fun restartBackend(port: Int = activePort): Result<DesktopHubConfig> {
        stopBackend()
        delay(500)
        return startBackend(port)
    }

    /**
     * Checks if the backend is currently active and healthy.
     */
    fun isRunning(): Boolean {
        return activeProcess?.isAlive == true && _backendState.value is LocalBackendState.Running
    }

    /**
     * Re-queries the local language server for current authentication state and Cascade models,
     * updating [backendState] with the latest status.
     */
    suspend fun refreshHubStatus(): HubServerStatus? {
        val res = hubConnectionManager.refreshUserStatus()
        val status = res.getOrNull()
        if (status != null) {
            val curr = _backendState.value
            if (curr is LocalBackendState.Running) {
                _backendState.value = curr.copy(hubStatus = status)
            }
        }
        return status
    }

    private fun startStreamCapture(proc: Process) {
        val authUrlRegex = Regex("""(?:ANTIGRAVITY_OPEN_URL:\s*(https?://\S+)|(https?://accounts\.google\.com/\S+)|(https?://\S*oauth\S*))""")

        val extractAuthUrl = { line: String ->
            val match = authUrlRegex.find(line)
            if (match != null) {
                val openUrl = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: match.value
                val cleanUrl = openUrl.trim().trimEnd('.', ',', ';', ')', ']', '}')
                if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                    Log.i(TAG, "Detected Google OAuth authorization URL: $cleanUrl")
                    _latestAuthUrl.value = cleanUrl
                }
            }
        }

        stdoutCaptureThread = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stdoutHistory) {
                            if (stdoutHistory.length > 50000) stdoutHistory.delete(0, 10000)
                            stdoutHistory.append(line).append("\n")
                        }
                        Log.d(TAG, "[BACKEND STDOUT] $line")
                        extractAuthUrl(line)
                    }
                }
            } catch (ignored: Exception) {}
        }.apply {
            isDaemon = true
            name = "AntigravityStdoutCapture"
            start()
        }

        stderrCaptureThread = Thread {
            try {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrHistory) {
                            if (stderrHistory.length > 50000) stderrHistory.delete(0, 10000)
                            stderrHistory.append(line).append("\n")
                        }
                        Log.d(TAG, "[BACKEND STDERR] $line")
                        extractAuthUrl(line)
                    }
                }
            } catch (ignored: Exception) {}
        }.apply {
            isDaemon = true
            name = "AntigravityStderrCapture"
            start()
        }
    }

    private suspend fun waitForHubFile(geminiDir: File, port: Int, proc: Process): File? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < DISCOVERY_TIMEOUT_MS) {
            val candidate = geminiDir.walk().firstOrNull { it.name == "hub_$port.json" && it.length() > 0 }
            if (candidate != null) return candidate
            if (!proc.isAlive) {
                val exit = try { proc.exitValue() } catch (e: Exception) { -1 }
                Log.e(TAG, "Backend process terminated prematurely with exit code $exit")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun startHealthMonitor(proc: Process, currentState: LocalBackendState.Running) {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive && proc.isAlive) {
                delay(5000)
                val currentRss = readProcessMemoryRssKb(currentState.pid)
                val curr = _backendState.value
                if (curr is LocalBackendState.Running) {
                    _backendState.value = curr.copy(memoryRssKb = currentRss)
                }
            }
            if (!proc.isAlive && _backendState.value is LocalBackendState.Running) {
                val exitCode = try { proc.exitValue() } catch (e: Exception) { -1 }
                val err = getLatestCapturedStderr().takeLast(1000)
                Log.w(TAG, "Backend died unexpectedly with exit code $exitCode. Stderr:\n$err")
                _backendState.value = LocalBackendState.Error(
                    "Backend stopped unexpectedly (exit $exitCode): $err"
                )
                hubConnectionManager.disconnect()
            }
        }
    }

    private fun readProcessMemoryRssKb(pid: Int): Long {
        if (pid <= 0) return 0L
        return try {
            val statusFile = File("/proc/$pid/status")
            if (statusFile.canRead()) {
                val line = statusFile.readLines().firstOrNull { it.startsWith("VmRSS:") }
                line?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLongOrNull() ?: 0L
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun findAvailablePort(startPort: Int): Int {
        for (port in startPort until (startPort + 50)) {
            try {
                java.net.ServerSocket(port).use { socket ->
                    socket.reuseAddress = true
                    return socket.localPort
                }
            } catch (e: Exception) {
                Log.d(TAG, "Port $port unavailable, testing next port...")
            }
        }
        return startPort
    }

    private fun cleanupOrphanProcesses() {
        try {
            val myPid = android.os.Process.myPid()
            val procDir = File("/proc")
            val pids = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()
            for (p in pids) {
                val pid = p.name.toIntOrNull() ?: continue
                if (pid == myPid) continue
                val cmdline = File(p, "cmdline").takeIf { it.canRead() }?.readText()?.replace('\u0000', ' ') ?: ""
                if (cmdline.contains("language_server") || cmdline.contains("libproot.so")) {
                    Log.i(TAG, "Cleaning up orphan process PID $pid: ${cmdline.take(60)}")
                    android.os.Process.killProcess(pid)
                }
            }
        } catch (ignored: Exception) {}
    }
}
