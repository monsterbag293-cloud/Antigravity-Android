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
            provisioningManager.ensureRuntimeEnvironmentGuide()
            // Provision Node.js (for swarm plugin) and Git in background if missing on existing installs
            scope.launch(Dispatchers.IO) {
                provisioningManager.ensureNodeProvisioned()
                provisioningManager.ensureGitProvisioned()
                provisioningManager.ensureRuntimeEnvironmentGuide()
            }

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
                            val mainDir = File(externalRootDir, "${entry.name}/main")
                            val legacyNestedDir = File(externalRootDir, "${entry.name}/${entry.name}")
                            val guestRel = when {
                                mainDir.isDirectory -> "${entry.name}/main"
                                legacyNestedDir.isDirectory -> "${entry.name}/${entry.name}"
                                else -> entry.name
                            }
                            runCatching {
                                android.system.Os.symlink(
                                    "/tmp/gemini_app/storage/$guestRel",
                                    entry.absolutePath
                                )
                            }
                        }
                    }
                }
            }
            importLinkedProjectChatsBeforeStart(rootfsDir, externalRootDir)
            startProjectChatSyncLoop(rootfsDir, externalRootDir)

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

            // Immediately disable Linux terminal sandbox in language_server so PRoot run_command & automations never hit EPERM
            runCatching {
                hubConnectionManager.executeUnaryRpc(
                    "/exa.language_server_pb.LanguageServerService/JetboxWriteState",
                    """{"userConfig":{"userSettings":{"enableTerminalSandbox":false,"cascadeAutoExecutionPolicy":3}}}"""
                )
            }
            // Migrate any orphaned scratch sidecars to config/sidecars and install persistent agentapi wrapper
            migrateScratchSidecars(isolatedGeminiDir)
            installPersistentAgentApiWrapper(isolatedGeminiDir)

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

    private fun migrateScratchSidecars(isolatedGeminiDir: File) {
        try {
            val scratchSidecars = File(isolatedGeminiDir, "antigravity/scratch/sidecars")
            val configSidecars = File(isolatedGeminiDir, "config/sidecars").apply { mkdirs() }
            scratchSidecars.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val srcJson = File(dir, "sidecar.json")
                val destDir = File(configSidecars, dir.name)
                val destJson = File(destDir, "sidecar.json")
                if (srcJson.isFile && !destJson.exists()) {
                    destDir.mkdirs()
                    srcJson.copyTo(destJson, overwrite = false)
                    Log.i(TAG, "Migrated automation sidecar ${dir.name} to config/sidecars")
                }
            }
        } catch (_: Exception) {}
    }

    private fun installPersistentAgentApiWrapper(isolatedGeminiDir: File) {
        scope.launch(Dispatchers.IO) {
            try {
                val rootfsDir = isolatedGeminiDir.parentFile?.parentFile ?: return@launch
                val agentApiFile = File(rootfsDir, "usr/local/bin/agentapi-persistent")
                val current = if (agentApiFile.isFile) agentApiFile.readText() else ""
                if (!current.contains("AGY_PERSISTENT_CONV_WRAPPER")) {
                    agentApiFile.parentFile?.mkdirs()
                    agentApiFile.writeText(
                        """#!/bin/sh
# AGY_PERSISTENT_CONV_WRAPPER: Reuse automation conversations across runs
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
LS="/usr/bin/language_server"
if [ "@@1" = "new-conversation" ]; then
  MAP_DIR="/tmp/gemini_app/antigravity/sidecar_data/.conv_map"
  CONV_DIR="/tmp/gemini_app/antigravity/conversations"
  mkdir -p "@@MAP_DIR"
  SID_NAME="@@(basename "@@PWD")"
  if [ "@@SID_NAME" = "/" ] || [ "@@SID_NAME" = "root" ] || [ -z "@@SID_NAME" ]; then
    SID_NAME="default"
  fi
  MAP_FILE="@@MAP_DIR/@@SID_NAME"
  if [ -f "@@MAP_FILE" ]; then
    CID="@@(cat "@@MAP_FILE" | tr -d '\r\n')"
    if [ -n "@@CID" ] && [ -f "@@CONV_DIR/@@CID.db" ]; then
      TITLE=""
      PROMPT=""
      AFTER_SEP=0
      shift
      for A in "@@@"; do
        if [ "@@AFTER_SEP" = "1" ]; then
          PROMPT="@@PROMPT@@A"
        elif [ "@@A" = "--" ]; then
          AFTER_SEP=1
        elif [ "@@{A#--title=}" != "@@A" ]; then
          TITLE="@@A"
        elif [ "@@{A#--}" = "@@A" ]; then
          PROMPT="@@PROMPT@@A"
        fi
      done
      if [ -n "@@PROMPT" ]; then
        if [ -n "@@TITLE" ]; then
          "@@LS" agentapi send-message "@@TITLE" "@@CID" "@@PROMPT" && exit 0
        else
          "@@LS" agentapi send-message "@@CID" "@@PROMPT" && exit 0
        fi
      fi
    fi
  fi
  OUT="@@("@@LS" agentapi "@@@")"
  RC=@@?
  printf '%s\n' "@@OUT"
  NEW_CID="@@(printf '%s' "@@OUT" | sed -n 's/.*"conversationId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
  if [ -n "@@NEW_CID" ]; then
    printf '%s' "@@NEW_CID" > "@@MAP_FILE"
  fi
  exit @@RC
fi
exec "@@LS" agentapi "@@@"
""".replace("@@@", "$@").replace("@@", "$")
                    )
                    agentApiFile.setExecutable(true, false)
                    Log.i(TAG, "Installed persistent conversation agentapi wrapper at ${agentApiFile.absolutePath}")
                }
                rewriteScheduleSidecarsToPersistentAgentApi(isolatedGeminiDir)
            } catch (_: Exception) {}
        }
    }

    private fun rewriteScheduleSidecarsToPersistentAgentApi(isolatedGeminiDir: File) {
        try {
            val sidecarsDir = File(isolatedGeminiDir, "config/sidecars")
            val entries = sidecarsDir.listFiles() ?: return
            for (dir in entries) {
                if (!dir.isDirectory) continue
                val jsonFile = File(dir, "sidecar.json")
                if (!jsonFile.isFile) continue
                val text = jsonFile.readText()
                if (!text.contains("\"schedule\"") || text.contains("/usr/local/bin/agentapi-persistent")) continue
                val json = org.json.JSONObject(text)
                if (json.optString("builtin") != "schedule") continue
                val args = json.optJSONArray("args") ?: continue
                if (args.length() >= 2 && args.optString(1) == "agentapi") {
                    args.put(1, "/usr/local/bin/agentapi-persistent")
                    jsonFile.writeText(json.toString(2) + "\n")
                    Log.i(TAG, "Updated schedule sidecar ${dir.name} to use /usr/local/bin/agentapi-persistent")
                }
            }
        } catch (_: Exception) {}
    }

    private var chatSyncJob: kotlinx.coroutines.Job? = null

    /**
     * Scans all linked workspace folders' `chats/` directories before starting `language_server`
     * so any newly added `.db`/`.pb` files are copied into `antigravity/conversations/` and indexed.
     */
    private fun importLinkedProjectChatsBeforeStart(rootfsDir: File, externalRootDir: File?) {
        if (externalRootDir == null) return
        val workspacesDir = File(rootfsDir, "tmp/gemini_app/workspaces")
        val agyRoot = File(rootfsDir, "tmp/gemini_app/antigravity")
        val convDestDir = File(agyRoot, "conversations").apply { if (!exists()) mkdirs() }
        val brainDestRoot = File(agyRoot, "brain").apply { if (!exists()) mkdirs() }

        val outerDirs = linkedSetOf<File>()
        workspacesDir.listFiles()?.forEach { entry ->
            val linkTarget = runCatching { android.system.Os.readlink(entry.absolutePath) }.getOrNull()
            if (linkTarget != null && linkTarget.startsWith("/tmp/gemini_app/storage/")) {
                val rel = linkTarget.removePrefix("/tmp/gemini_app/storage/").trimStart('/')
                val resolved = File(externalRootDir, rel)
                val outer = if (resolved.name == "main") resolved.parentFile else resolved
                if (outer != null && outer.isDirectory) outerDirs.add(outer)
            }
            val primaryOuter = File(externalRootDir, entry.name)
            if (primaryOuter.isDirectory) outerDirs.add(primaryOuter)
            val downloadsOuter = File(externalRootDir, "Download/Antigravity/workspaces/${entry.name}")
            if (downloadsOuter.isDirectory) outerDirs.add(downloadsOuter)
        }

        for (outerDir in outerDirs) {
            val chatsDir = File(outerDir, "chats")
            if (!chatsDir.isDirectory) continue
            chatsDir.listFiles()?.forEach { chatFolder ->
                if (!chatFolder.isDirectory) return@forEach
                chatFolder.listFiles()?.forEach { entry ->
                    if (entry.isFile && (entry.extension == "db" || entry.extension == "pb")) {
                        val destDb = File(convDestDir, entry.name)
                        if (!destDb.exists()) {
                            runCatching { entry.copyTo(destDb, overwrite = false) }
                        }
                        listOf("-wal", "-shm").forEach { suffix ->
                            val auxSrc = File(chatFolder, "${entry.name}$suffix")
                            val auxDest = File(convDestDir, "${entry.name}$suffix")
                            if (auxSrc.isFile && !auxDest.exists()) {
                                runCatching { auxSrc.copyTo(auxDest, overwrite = false) }
                            }
                        }
                        val chatId = entry.nameWithoutExtension
                        val brainSrc = File(chatFolder, "brain")
                        if (brainSrc.isDirectory) {
                            val brainDest = File(brainDestRoot, chatId).apply { if (!exists()) mkdirs() }
                            brainSrc.listFiles()?.forEach { bf ->
                                if (bf.isFile) {
                                    val destBf = File(brainDest, bf.name)
                                    if (!destBf.exists()) {
                                        runCatching { bf.copyTo(destBf, overwrite = false) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startProjectChatSyncLoop(rootfsDir: File, externalRootDir: File?) {
        chatSyncJob?.cancel()
        if (externalRootDir == null) return
        val convDir = File(rootfsDir, "tmp/gemini_app/antigravity/conversations")
        val summariesDb = File(rootfsDir, "tmp/gemini_app/antigravity/conversation_summaries.db")
        val brainRoot = File(rootfsDir, "tmp/gemini_app/antigravity/brain")
        val wsRegex = Regex("/tmp/gemini_app/(?:workspaces|storage)/([^/\"'\\s\\x00]+)")
        val syncedStamps = mutableMapOf<String, Long>()
        chatSyncJob = scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                if (_backendState.value is LocalBackendState.Stopped || _backendState.value is LocalBackendState.Error) {
                    break
                }
                try {
                    rewriteScheduleSidecarsToPersistentAgentApi(File(rootfsDir, "tmp/gemini_app"))
                    val dbFiles = convDir.listFiles { f -> f.isFile && (f.extension == "db" || f.extension == "pb") } ?: continue
                    val summaryMeta = readConversationSummaryMap(summariesDb)
                    for (dbFile in dbFiles) {
                        val chatId = dbFile.nameWithoutExtension
                        val walFile = File(convDir, "${dbFile.name}-wal")
                        val stamp = dbFile.lastModified() xor (dbFile.length() shl 16) xor
                            (if (walFile.exists()) walFile.lastModified() xor walFile.length() else 0L)
                        if (syncedStamps[chatId] == stamp) continue

                        val meta = summaryMeta[chatId]
                        val projectName = meta?.projectName ?: run {
                            val content = dbFile.readBytes().toString(Charsets.ISO_8859_1)
                            wsRegex.find(content)?.groupValues?.get(1)?.let { raw ->
                                runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                            }
                        } ?: continue

                        val primaryOuter = File(externalRootDir, projectName)
                        val downloadsOuter = File(externalRootDir, "Download/Antigravity/workspaces/$projectName")
                        val projectOuter = when {
                            primaryOuter.isDirectory -> primaryOuter
                            downloadsOuter.isDirectory -> downloadsOuter
                            else -> continue
                        }
                        val chatsDir = File(projectOuter, "chats")
                        if (!chatsDir.exists()) chatsDir.mkdirs()

                        val existingFolder = chatsDir.listFiles()?.firstOrNull { f ->
                            f.isDirectory && File(f, dbFile.name).exists()
                        }
                        val rawTitle = meta?.title?.takeIf { it.isNotBlank() }
                        val chatFolder = if (rawTitle != null) {
                            val safeTitle = rawTitle.trim()
                                .replace(Regex("[/\\\\:*?\"<>|\\r\\n]+"), "_")
                                .trim('.', ' ')
                                .take(64)
                                .ifEmpty { chatId }
                            val candidateFolder = File(chatsDir, safeTitle)
                            when {
                                existingFolder != null && existingFolder.name == chatId && !candidateFolder.exists() -> {
                                    if (existingFolder.renameTo(candidateFolder)) candidateFolder else existingFolder
                                }
                                existingFolder != null -> existingFolder
                                candidateFolder.isDirectory && !File(candidateFolder, dbFile.name).exists() &&
                                    candidateFolder.listFiles()?.any { it.extension == "db" || it.extension == "pb" } == true -> {
                                    File(chatsDir, "${safeTitle}_${chatId.take(6)}")
                                }
                                else -> candidateFolder
                            }
                        } else {
                            existingFolder ?: File(chatsDir, chatId)
                        }
                        if (!chatFolder.exists()) chatFolder.mkdirs()

                        dbFile.copyTo(File(chatFolder, dbFile.name), overwrite = true)
                        if (walFile.exists()) walFile.copyTo(File(chatFolder, walFile.name), overwrite = true)
                        val shmFile = File(convDir, "${dbFile.name}-shm")
                        if (shmFile.exists()) shmFile.copyTo(File(chatFolder, shmFile.name), overwrite = true)

                        val brainSrc = File(brainRoot, chatId)
                        if (brainSrc.isDirectory) {
                            val brainDest = File(chatFolder, "brain").apply { if (!exists()) mkdirs() }
                            brainSrc.listFiles()?.forEach { f ->
                                if (f.isFile) {
                                    f.copyTo(File(brainDest, f.name), overwrite = true)
                                }
                            }
                        }
                        syncedStamps[chatId] = stamp
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private data class SummaryMeta(val title: String, val projectName: String?)

    private fun readConversationSummaryMap(summariesDb: File): Map<String, SummaryMeta> {
        if (!summariesDb.isFile) return emptyMap()
        val result = mutableMapOf<String, SummaryMeta>()
        val wsRegex = Regex("/tmp/gemini_app/(?:workspaces|storage)/([^/\"'\\s\\x00\\]]+)")
        runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                summariesDb.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    "SELECT conversation_id, title, workspace_uris FROM conversation_summaries",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val title = cursor.getString(1) ?: ""
                        val ws = cursor.getString(2) ?: ""
                        val rawProj = wsRegex.find(ws)?.groupValues?.get(1)
                        val proj = rawProj?.let {
                            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                        }
                        result[id] = SummaryMeta(title = title, projectName = proj)
                    }
                }
            }
        }
        return result
    }
}
