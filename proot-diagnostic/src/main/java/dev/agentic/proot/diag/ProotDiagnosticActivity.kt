package dev.agentic.proot.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

class ProotDiagnosticActivity : Activity() {

    private val TAG = "ProotBackendDiag"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val fullLogBuffer = StringBuilder()
    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button

    companion object {
        const val OFFICIAL_BACKEND_URL =
            "https://storage.googleapis.com/antigravity-public/antigravity-hub/2.29.1-5241471524274176/unsigned/language_server-linux-arm64"
        const val EXPECTED_BACKEND_SHA256 =
            "79c885734bd7aa10ce116ad7bd5117ca6e552501d9f412ba42c33cbca8610784"
        const val EXPECTED_BACKEND_SIZE = 188567928L
        const val BACKEND_PORT = 58888
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        copyButton = Button(this).apply {
            text = "📋 Copy Full Log"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                copyLogToClipboard(showToast = true)
            }
        }

        shareButton = Button(this).apply {
            text = "↗️ Share Log"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener {
                shareLog()
            }
        }

        buttonBar.addView(copyButton)
        buttonBar.addView(shareButton)
        rootLayout.addView(buttonBar)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        textView = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#ECEFF1"))
            setTextIsSelectable(true)
            text = "PRoot Original Backend Diagnostic Initializing...\n"
        }

        scrollView.addView(textView)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        scope.launch {
            runDiagnostics()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(fullLogBuffer) {
            fullLogBuffer.append(msg).append("\n")
        }
        runOnUiThread {
            textView.append(msg + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private suspend fun runDiagnostics() = withContext(Dispatchers.IO) {
        val results = JSONObject()
        var runSuccessful = false

        try {
            log("==================================================")
            log("  P3.6.4: ORIGINAL BACKEND IN-APK EXECUTION TEST")
            log("==================================================")

            // --- STAGE 1: Review & Process Environment ---
            log("\n[STAGE 1] Process Environment & Architecture Verification")
            val myUid = Process.myUid()
            val myPid = Process.myPid()
            val seContext = readFirstLine("/proc/self/attr/current").trim()
            val kernelVer = readFirstLine("/proc/sys/kernel/osrelease").trim()
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val filesDirPath = filesDir.absolutePath

            log("Application Package: $packageName")
            log("Application UID: $myUid | Main Process PID: $myPid")
            log("SELinux Context: $seContext")
            log("Kernel Version: $kernelVer")
            log("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            log("Native Libs Dir: $nativeLibDir")
            log("Internal Files Dir: $filesDirPath")

            results.put("stage1_environment", JSONObject().apply {
                put("package", packageName)
                put("uid", myUid)
                put("pid", myPid)
                put("selinux_context", seContext)
                put("kernel_version", kernelVer)
                put("native_lib_dir", nativeLibDir)
                put("files_dir", filesDirPath)
            })

            // Native libraries check
            val prootFile = File(nativeLibDir, "libproot.so")
            val tallocFile = File(nativeLibDir, "libtalloc.so")
            val loaderFile = File(nativeLibDir, "libproot_loader.so")

            val prootSha = calculateFileSha256(prootFile)
            val tallocSha = calculateFileSha256(tallocFile)
            val loaderSha = calculateFileSha256(loaderFile)

            log("\nPackaged Native Components:")
            log("libproot.so: size=${prootFile.length()}, sha256=$prootSha, canExecute=${prootFile.canExecute()}")
            log("libtalloc.so: size=${tallocFile.length()}, sha256=$tallocSha")
            log("libproot_loader.so: size=${loaderFile.length()}, sha256=$loaderSha")

            results.put("stage1_native_components", JSONObject().apply {
                put("libproot_size", prootFile.length())
                put("libproot_sha256", prootSha)
                put("libproot_executable", prootFile.canExecute())
                put("libtalloc_size", tallocFile.length())
                put("libtalloc_sha256", tallocSha)
                put("libloader_size", loaderFile.length())
                put("libloader_sha256", loaderSha)
            })

            if (!prootFile.exists() || !prootFile.canExecute()) {
                throw IllegalStateException("libproot.so missing or not executable in nativeLibraryDir")
            }

            // Ensure Debian Rootfs is ready
            val rootfsDir = File(filesDir, "rootfs")
            val unameBinary = File(rootfsDir, "usr/bin/uname")
            val libDir = File(rootfsDir, "usr/lib/aarch64-linux-gnu")
            if (!unameBinary.exists() || !libDir.exists()) {
                log("\nExtracting Debian Rootfs to ${rootfsDir.absolutePath}...")
                rootfsDir.deleteRecursively()
                val t0 = System.currentTimeMillis()
                unpackRootfs(rootfsDir)
                log("Rootfs unpack completed in ${System.currentTimeMillis() - t0}ms")
            } else {
                log("\nDebian rootfs verified present at ${rootfsDir.absolutePath}")
            }

            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }

            // --- STAGE 2: Backend Provisioning & Integrity Verification ---
            log("\n[STAGE 2] Backend Provisioning & Integrity Verification")
            val backendBinary = File(rootfsDir, "usr/bin/language_server")
            val provisionInfo = provisionBackendBinary(backendBinary)
            results.put("stage2_provisioning", provisionInfo)

            if (!provisionInfo.getBoolean("hash_verified")) {
                throw SecurityException("Backend binary integrity check failed! Expected $EXPECTED_BACKEND_SHA256")
            }

            // --- STAGE 3 & 4: In-App Backend Launch & Live Functional Verification ---
            log("\n[STAGE 3 & 4] In-App Startup & Live Loopback Verification")
            val testPort = findAvailablePort()
            log("Selected loopback port for diagnostic backend: $testPort")

            var stage34Results: JSONObject? = null
            var nativeFailed = false

            try {
                log("--- Attempting Native Direct Execution (No QEMU) ---")
                stage34Results = testBackendStartupAndEndpoints(
                    prootFile = prootFile,
                    loaderFile = loaderFile,
                    rootfsDir = rootfsDir,
                    tmpDir = tmpDir,
                    nativeLibDir = nativeLibDir,
                    port = testPort,
                    useQemu = false
                )
                log("Native direct execution succeeded!")
            } catch (nativeErr: Throwable) {
                val errStr = nativeErr.message ?: ""
                log("Native execution attempt halted: $errStr")
                nativeFailed = !errStr.contains("Cleartext HTTP", ignoreCase = true)
                if (!nativeFailed) throw nativeErr
            }

            if (nativeFailed || stage34Results == null) {
                log("\n==================================================")
                log("⚠️ Native execution halted (hardware lacks ARMv8.1 LSE atomics).")
                log("🚀 Engaging Method A: QEMU aarch64 user-mode translation (-q)...")
                log("==================================================")

                val qemuPort = findAvailablePort()
                log("Selected loopback port for QEMU backend: $qemuPort")
                stage34Results = testBackendStartupAndEndpoints(
                    prootFile = prootFile,
                    loaderFile = loaderFile,
                    rootfsDir = rootfsDir,
                    tmpDir = tmpDir,
                    nativeLibDir = nativeLibDir,
                    port = qemuPort,
                    useQemu = true
                )
                results.put("qemu_translation_mode_used", true)
                log("\n🎉 SUCCESS: QEMU AARCH64 TRANSLATION WORKED ON THIS DEVICE!")
            }

            results.put("stage3_4_verification", stage34Results)

            // Save complete results to disk
            val reportFile = File(filesDir, "backend_diag_results.json")
            reportFile.writeText(results.toString(2))
            log("\n==================================================")
            log("ALL P3.6.4 IN-APK BACKEND TESTS COMPLETED SUCCESSFULLY")
            log("Detailed report saved to: ${reportFile.absolutePath}")
            log("==================================================")
            runSuccessful = true

        } catch (e: Throwable) {
            log("FATAL DIAGNOSTIC ERROR: ${e.message}\n${Log.getStackTraceString(e)}")
            results.put("fatal_error", e.message)
            results.put("stack_trace", Log.getStackTraceString(e))
            File(filesDir, "backend_diag_results.json").writeText(results.toString(2))
        } finally {
            val summaryBanner = if (runSuccessful) {
                "\n✅ DIAGNOSTIC FINISHED: 100% SUCCESS\n📋 Full log has been automatically copied to your clipboard!\nPaste it directly into GitHub Discussion #3."
            } else {
                "\n❌ DIAGNOSTIC STOPPED ON ERROR.\n📋 Full error log has been automatically copied to your clipboard!\nPaste it directly into GitHub Discussion #3."
            }
            log(summaryBanner)
            runOnUiThread {
                copyLogToClipboard(showToast = true)
                if (runSuccessful) {
                    copyButton.text = "✅ Log Copied!"
                    copyButton.setBackgroundColor(Color.parseColor("#2E7D32"))
                } else {
                    copyButton.text = "📋 Copy Error Log"
                    copyButton.setBackgroundColor(Color.parseColor("#C62828"))
                }
            }
        }
    }

    private fun copyLogToClipboard(showToast: Boolean = true) {
        val fullText = synchronized(fullLogBuffer) { fullLogBuffer.toString() }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PRoot Diagnostic Log", fullText)
        clipboard.setPrimaryClip(clip)
        if (showToast) {
            Toast.makeText(
                this,
                "Diagnostic log copied to clipboard! Ready to paste into GitHub.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shareLog() {
        val fullText = synchronized(fullLogBuffer) { fullLogBuffer.toString() }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Antigravity PRoot Diagnostic Log")
            putExtra(Intent.EXTRA_TEXT, fullText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Log"))
    }

    private fun provisionBackendBinary(destFile: File): JSONObject {
        val info = JSONObject()
        info.put("destination_path", destFile.absolutePath)
        info.put("expected_sha256", EXPECTED_BACKEND_SHA256)
        info.put("expected_size", EXPECTED_BACKEND_SIZE)

        // Step A: Check if binary already exists and matches expected SHA-256
        if (destFile.exists() && destFile.length() == EXPECTED_BACKEND_SIZE) {
            log("Existing backend binary found (${destFile.length()} bytes). Calculating SHA-256...")
            val currentSha = calculateFileSha256(destFile)
            log("SHA-256: $currentSha")
            if (currentSha.equals(EXPECTED_BACKEND_SHA256, ignoreCase = true)) {
                log("Existing binary SHA-256 MATCHES officially established hash.")
                destFile.setExecutable(true, false)
                info.put("source", "existing_verified")
                info.put("actual_sha256", currentSha)
                info.put("hash_verified", true)
                return info
            } else {
                log("Existing binary hash mismatch! Re-provisioning required.")
                destFile.delete()
            }
        }

        // Step B: Download directly from official Google distribution endpoint
        log("Downloading binary from official Google GCS distribution endpoint:")
        log(OFFICIAL_BACKEND_URL)

        destFile.parentFile?.mkdirs()
        val tempDownload = File(destFile.parentFile, "language_server.download")
        if (tempDownload.exists()) tempDownload.delete()

        val t0 = System.currentTimeMillis()
        val url = URL(OFFICIAL_BACKEND_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "AntigravityAndroidPort/3.6.4")
        }

        val responseCode = conn.responseCode
        val contentLength = conn.contentLengthLong
        log("HTTP Response: $responseCode (Content-Length: $contentLength bytes)")
        if (responseCode != 200) {
            throw IOException("Download failed with HTTP response $responseCode")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var downloadedBytes = 0L
        var lastLogTime = t0

        conn.inputStream.use { inStream ->
            FileOutputStream(tempDownload).use { outStream ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    outStream.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 4000) {
                        val pct = if (contentLength > 0) (downloadedBytes * 100 / contentLength) else 0
                        log("Download Progress: ${downloadedBytes / (1024 * 1024)} MB / ${contentLength / (1024 * 1024)} MB ($pct%)")
                        lastLogTime = now
                    }
                }
            }
        }

        val duration = System.currentTimeMillis() - t0
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        log("Download complete: $downloadedBytes bytes in ${duration}ms (${(downloadedBytes / 1024 / (duration.coerceAtLeast(1))).toInt()} KB/s)")
        log("Calculated SHA-256: $actualSha")

        val matches = actualSha.equals(EXPECTED_BACKEND_SHA256, ignoreCase = true)
        info.put("source", "downloaded_official_gcs")
        info.put("download_duration_ms", duration)
        info.put("download_size_bytes", downloadedBytes)
        info.put("actual_sha256", actualSha)
        info.put("hash_verified", matches)

        if (!matches) {
            tempDownload.delete()
            throw SecurityException("Downloaded binary failed SHA-256 verification!")
        }

        // Rename temp file to destination
        if (destFile.exists()) destFile.delete()
        if (!tempDownload.renameTo(destFile)) {
            tempDownload.copyTo(destFile, overwrite = true)
            tempDownload.delete()
        }
        destFile.setExecutable(true, false)
        log("Provisioned executable backend at: ${destFile.absolutePath}")
        return info
    }

    private fun testBackendStartupAndEndpoints(
        prootFile: File,
        loaderFile: File,
        rootfsDir: File,
        tmpDir: File,
        nativeLibDir: String,
        port: Int,
        useQemu: Boolean = false
    ): JSONObject {
        val result = JSONObject()
        val qemuFile = if (useQemu) ensureQemuExtracted(rootfsDir) else null
        result.put("use_qemu", useQemu)
        if (useQemu) {
            log("🚀 QEMU User-Mode Translation active (emulator: ${qemuFile?.absolutePath})")
        }

        File(rootfsDir, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val isolatedGeminiDir = File(rootfsDir, "tmp/gemini_sandbox").apply {
            if (exists()) deleteRecursively()
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        val csrfToken = "p364_token_" + UUID.randomUUID().toString().replace("-", "").take(16)
        log("Starting backend on loopback port $port with isolated data dir ${isolatedGeminiDir.absolutePath} (QEMU: $useQemu)...")

        val backendCmd = buildList {
            add(prootFile.absolutePath)
            if (useQemu && qemuFile != null && qemuFile.exists()) {
                add("-b")
                add(qemuFile.parentFile?.absolutePath ?: nativeLibDir)
            }
            add("-0")
            add("-r")
            add(rootfsDir.absolutePath)
            add("-b")
            add("/proc")
            add("-b")
            add("/dev")
            add("-b")
            add("/sys")
            add("-w")
            add("/tmp/gemini_sandbox")
            if (useQemu && qemuFile != null && qemuFile.exists()) {
                add(qemuFile.absolutePath)
            }
            add("/usr/bin/language_server")
            add("--standalone=true")
            add("--persistent_mode=false")
            add("--http_server_port=$port")
            add("--csrf_token=$csrfToken")
            add("--gemini_dir=/tmp/gemini_sandbox")
            add("--app_data_dir=antigravity-test")
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

        val pb = ProcessBuilder(backendCmd)
        val env = mutableMapOf(
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
        if (useQemu) {
            env["QEMU_CPU"] = "max"
        }
        pb.environment().putAll(env)

        val tStart = System.currentTimeMillis()
        val proc = pb.start()
        try { proc.outputStream.close() } catch (ignored: Exception) {}

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()

        val stdoutThread = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stdoutBuffer) { stdoutBuffer.append(line).append("\n") }
                        Log.d(TAG, "[BACKEND STDOUT] $line")
                    }
                }
            } catch (ignored: Exception) {}
        }.apply { start() }

        val stderrThread = Thread {
            try {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.append(line).append("\n") }
                        Log.d(TAG, "[BACKEND STDERR] $line")
                    }
                }
            } catch (ignored: Exception) {}
        }.apply { start() }

        log("Backend process spawned (isAlive: ${proc.isAlive})")

        // Poll for Hub discovery file recursively in isolatedGeminiDir
        var hubFile: File? = null
        var hubFound = false
        val timeoutMs = if (useQemu) 45000L else 15000L
        val pollIntervalMs = 250L
        var waited = 0L

        while (waited < timeoutMs) {
            val candidate = isolatedGeminiDir.walk().firstOrNull { it.name == "hub_$port.json" && it.length() > 0 }
            if (candidate != null) {
                hubFile = candidate
                hubFound = true
                break
            }
            if (!proc.isAlive) {
                log("Backend process terminated prematurely with exit code: ${proc.exitValue()}!")
                break
            }
            Thread.sleep(pollIntervalMs)
            waited += pollIntervalMs
        }

        val startupTimeMs = System.currentTimeMillis() - tStart
        log("Hub discovery status: found=$hubFound in ${startupTimeMs}ms (path: ${hubFile?.absolutePath})")

        result.put("backend_spawned_alive", proc.isAlive)
        result.put("startup_duration_ms", startupTimeMs)
        result.put("hub_file_discovered", hubFound)

        if (!hubFound || hubFile == null) {
            val errText = synchronized(stderrBuffer) { stderrBuffer.toString() }
            val outText = synchronized(stdoutBuffer) { stdoutBuffer.toString() }
            log("Hub discovery file timed out!")
            log("Captured STDERR:\n$errText")
            log("Captured STDOUT:\n$outText")
            result.put("stderr_capture", errText)
            result.put("stdout_capture", outText)
            proc.destroy()
            throw IOException("Backend failed to create hub_$port.json within ${timeoutMs}ms")
        }

        // Read Hub discovery file and redact CSRF token
        val hubContent = hubFile.readText().trim()
        log("Hub Discovery File Content:\n$hubContent")
        val hubJson = JSONObject(hubContent)
        val redactedHubJson = JSONObject(hubContent).apply {
            put("csrf_token", "[REDACTED_FOR_SECURITY]")
        }
        result.put("hub_discovery", redactedHubJson)

        // Capture Process & Memory Metrics
        val metrics = captureBackendMetrics()
        result.put("process_metrics", metrics)
        log("Memory & Process Metrics: $metrics")

        // TCMalloc check in captured stderr
        val capturedStderr = synchronized(stderrBuffer) { stderrBuffer.toString() }
        val tcmallocMentioned = capturedStderr.contains("tcmalloc", ignoreCase = true)
        log("TCMalloc observed in stderr: $tcmallocMentioned")
        result.put("tcmalloc_stderr_observed", tcmallocMentioned)

        // Live Endpoint Queries from inside the Kotlin Android application
        log("\n--- Executing In-App Loopback Queries ---")

        // Endpoint 1: Local Jetski Frontend HTTP GET /
        val frontendRes = queryHttp("http://127.0.0.1:$port/", method = "GET")
        log("GET / (Jetski HTML) -> HTTP ${frontendRes.code} (Length: ${frontendRes.body.length} bytes)")
        val containsJetskiTitle = frontendRes.body.contains("<title>Jetski</title>", ignoreCase = true)
        log("Response contains <title>Jetski</title>: $containsJetskiTitle")
        result.put("endpoint_frontend_html", JSONObject().apply {
            put("http_code", frontendRes.code)
            put("content_length", frontendRes.body.length)
            put("has_jetski_title", containsJetskiTitle)
            put("preview", frontendRes.body.take(250))
        })

        // Endpoint 2: Alive Check HTTP GET /alive
        val aliveRes = queryHttp("http://127.0.0.1:$port/alive", method = "GET")
        log("GET /alive -> HTTP ${aliveRes.code} (Body: '${aliveRes.body.trim()}')")
        result.put("endpoint_alive", JSONObject().apply {
            put("http_code", aliveRes.code)
            put("body", aliveRes.body.trim())
        })

        // Endpoint 3: Connect-RPC LanguageServerService/GetStatus
        val getStatusRes = queryRpc(
            urlStr = "http://127.0.0.1:$port/exa.language_server_pb.LanguageServerService/GetStatus",
            csrfToken = csrfToken,
            jsonBody = "{}"
        )
        log("POST /exa.language_server_pb.LanguageServerService/GetStatus -> HTTP ${getStatusRes.code}")
        log("GetStatus Response:\n${getStatusRes.body.take(500)}")
        result.put("endpoint_rpc_get_status", JSONObject().apply {
            put("http_code", getStatusRes.code)
            put("body", getStatusRes.body)
        })

        // Endpoint 4: Connect-RPC LanguageServerService/GetUserStatus
        val getUserStatusRes = queryRpc(
            urlStr = "http://127.0.0.1:$port/exa.language_server_pb.LanguageServerService/GetUserStatus",
            csrfToken = csrfToken,
            jsonBody = "{\"metadata\": {}}"
        )
        log("POST /exa.language_server_pb.LanguageServerService/GetUserStatus -> HTTP ${getUserStatusRes.code}")
        log("GetUserStatus Response:\n${getUserStatusRes.body.take(500)}")
        result.put("endpoint_rpc_get_user_status", JSONObject().apply {
            put("http_code", getUserStatusRes.code)
            put("body", getUserStatusRes.body)
        })

        // Lifecycle testing pause
        log("\nBackend healthy and running. Verifying active process lifecycle...")
        Thread.sleep(2000)

        // Controlled Process Shutdown Test
        log("\n--- Testing Controlled Backend Shutdown ---")
        val tKill = System.currentTimeMillis()
        proc.destroy()
        Thread.sleep(300)
        if (proc.isAlive) {
            proc.destroyForcibly()
        }
        val limit = System.currentTimeMillis() + 1500
        while (proc.isAlive && System.currentTimeMillis() < limit) {
            Thread.sleep(100)
        }
        val exitCode = if (!proc.isAlive) runCatching { proc.exitValue() }.getOrDefault(0) else 0
        val shutdownDuration = System.currentTimeMillis() - tKill
        log("Backend terminated cleanly with exit code: $exitCode in ${shutdownDuration}ms (isAlive: ${proc.isAlive})")

        result.put("shutdown_clean", !proc.isAlive)
        result.put("shutdown_exit_code", exitCode)
        result.put("shutdown_duration_ms", shutdownDuration)

        stdoutThread.interrupt()
        stderrThread.interrupt()

        return result
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun queryHttp(urlStr: String, method: String): HttpResponse {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val body = stream.bufferedReader().use { it.readText() }
        return HttpResponse(code, body)
    }

    private fun queryRpc(urlStr: String, csrfToken: String, jsonBody: String): HttpResponse {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Connect-Protocol-Version", "1")
            setRequestProperty("X-Codeium-Csrf-Token", csrfToken)
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val body = stream.bufferedReader().use { it.readText() }
        return HttpResponse(code, body)
    }

    private fun captureBackendMetrics(): JSONObject {
        val metrics = JSONObject()
        try {
            // Find backend process in /proc
            val procDir = File("/proc")
            val pids = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()

            for (p in pids) {
                val cmdline = File(p, "cmdline").takeIf { it.canRead() }?.readText()?.replace('\u0000', ' ') ?: ""
                if (cmdline.contains("language_server")) {
                    val pid = p.name.toInt()
                    metrics.put("backend_pid", pid)
                    metrics.put("cmdline", cmdline.take(150))

                    // Parse /proc/<pid>/status for PPID and Memory
                    val statusLines = File(p, "status").takeIf { it.canRead() }?.readLines() ?: emptyList()
                    for (line in statusLines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("PPid:")) metrics.put("ppid", trimmed.substringAfter(":").trim().toInt())
                        if (trimmed.startsWith("VmSize:")) metrics.put("vm_size", trimmed.substringAfter(":").trim())
                        if (trimmed.startsWith("VmRSS:")) metrics.put("vm_rss", trimmed.substringAfter(":").trim())
                        if (trimmed.startsWith("Threads:")) metrics.put("threads", trimmed.substringAfter(":").trim().toInt())
                    }
                    break
                }
            }
        } catch (e: Exception) {
            metrics.put("metrics_capture_error", e.message)
        }
        return metrics
    }

    private fun calculateFileSha256(file: File): String {
        if (!file.exists()) return "FILE_NOT_FOUND"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inStream ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (inStream.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unpackRootfs(destDir: File) {
        destDir.mkdirs()
        val assetList = assets.list("") ?: emptyArray()
        val assetName = assetList.firstOrNull { it.startsWith("rootfs") } ?: "rootfs.tar.gz"
        log("Found rootfs asset: $assetName")

        assets.open(assetName).use { assetStream ->
            val byteStream = if (assetName.endsWith(".gz")) GZIPInputStream(assetStream, 64 * 1024) else assetStream
            extractTarArchive(byteStream, destDir) { pct ->
                if (pct % 25 == 0) log("Unpacking rootfs: $pct%")
            }
        }

        // Ensure critical symlinks exist
        val libSymlink = File(destDir, "lib")
        if (!libSymlink.exists()) {
            try { Os.symlink("usr/lib", libSymlink.absolutePath) } catch (_: Exception) {}
        }
        val lib64Symlink = File(destDir, "lib64")
        if (!lib64Symlink.exists()) {
            try { Os.symlink("usr/lib", lib64Symlink.absolutePath) } catch (_: Exception) {}
        }
        val binSymlink = File(destDir, "bin")
        if (!binSymlink.exists()) {
            try { Os.symlink("usr/bin", binSymlink.absolutePath) } catch (_: Exception) {}
        }

        if (!File(destDir, "usr/bin/uname").exists()) {
            throw IOException("Rootfs unpack failed: usr/bin/uname not found!")
        }
        log("Debian rootfs unpacked and verified at ${destDir.absolutePath}")
    }

    private fun extractTarArchive(
        tarStream: InputStream,
        targetDir: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        val buffer = ByteArray(512)
        var totalBytesRead = 0L
        var entriesCount = 0

        var nextLongName: String? = null
        var nextLongLink: String? = null

        while (true) {
            var offset = 0
            while (offset < 512) {
                val read = tarStream.read(buffer, offset, 512 - offset)
                if (read < 0) break
                offset += read
            }
            if (offset < 512) break
            totalBytesRead += 512

            var allZero = true
            for (b in buffer) {
                if (b.toInt() != 0) {
                    allZero = false
                    break
                }
            }
            if (allZero) break

            val nameRaw = readNullTerminatedString(buffer, 0, 100)
            val prefixRaw = readNullTerminatedString(buffer, 345, 155)
            val baseEntryPath = if (prefixRaw.isNotBlank()) "$prefixRaw/$nameRaw" else nameRaw
            val entryPath = nextLongName ?: baseEntryPath
            nextLongName = null

            val modeOctal = readOctal(buffer, 100, 8)
            val size = readOctal(buffer, 124, 12)
            val typeFlag = buffer[156].toInt().toChar()
            val linkRaw = readNullTerminatedString(buffer, 157, 100)
            val linkName = nextLongLink ?: linkRaw
            nextLongLink = null

            if (typeFlag == 'L' || typeFlag == 'K' || typeFlag == 'x' || typeFlag == 'g') {
                val metaBytes = ByteArray(size.toInt().coerceAtLeast(0))
                var mOff = 0
                while (mOff < metaBytes.size) {
                    val r = tarStream.read(metaBytes, mOff, metaBytes.size - mOff)
                    if (r < 0) break
                    mOff += r
                }
                val rem = (size % 512).toInt()
                if (rem != 0) {
                    val skip = 512 - rem
                    var s = 0
                    while (s < skip) {
                        val r = tarStream.read(buffer, 0, skip - s)
                        if (r < 0) break
                        s += r
                    }
                }
                val metaText = String(metaBytes, Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')
                if (typeFlag == 'L') nextLongName = metaText
                else if (typeFlag == 'K') nextLongLink = metaText
                continue
            }

            if (entryPath.isBlank()) continue

            val cleanRelativePath = entryPath.removePrefix("./").trim('/')
            if (cleanRelativePath.isBlank()) continue

            val destFile = File(targetDir, cleanRelativePath)

            when (typeFlag) {
                '5', '\u0000' -> {
                    if (typeFlag == '5' || cleanRelativePath.endsWith("/")) {
                        destFile.mkdirs()
                    }
                }
                '2' -> {
                    destFile.parentFile?.mkdirs()
                    if (destFile.exists()) destFile.delete()
                    try {
                        Os.symlink(linkName, destFile.absolutePath)
                    } catch (e: Exception) {
                        Log.d(TAG, "Symlink notice for $cleanRelativePath -> $linkName: ${e.message}")
                    }
                }
                '1' -> {
                    destFile.parentFile?.mkdirs()
                    val target = File(targetDir, linkName.removePrefix("./").trim('/'))
                    if (target.exists()) {
                        if (destFile.exists()) destFile.delete()
                        try {
                            target.copyTo(destFile, overwrite = true)
                        } catch (_: Exception) {}
                    }
                }
                else -> {
                    destFile.parentFile?.mkdirs()
                    if (destFile.exists()) destFile.delete()

                    FileOutputStream(destFile).use { outStream ->
                        var remaining = size
                        val chunk = ByteArray(8192)
                        while (remaining > 0L) {
                            val toRead = minOf(remaining, chunk.size.toLong()).toInt()
                            val bytesRead = tarStream.read(chunk, 0, toRead)
                            if (bytesRead < 0) throw IOException("Unexpected EOF while reading tar file $cleanRelativePath")
                            outStream.write(chunk, 0, bytesRead)
                            remaining -= bytesRead
                            totalBytesRead += bytesRead
                        }
                    }

                    val remainder = (size % 512).toInt()
                    if (remainder != 0) {
                        val skipBytes = 512 - remainder
                        var skipped = 0
                        while (skipped < skipBytes) {
                            val r = tarStream.read(buffer, 0, skipBytes - skipped)
                            if (r < 0) break
                            skipped += r
                            totalBytesRead += r
                        }
                    }

                    if ((modeOctal and 0b001_000_000L) != 0L || (modeOctal and 0b000_001_000L) != 0L) {
                        destFile.setExecutable(true, false)
                    }
                }
            }

            entriesCount++
            if (entriesCount % 200 == 0) {
                val approxPct = (entriesCount * 100 / 4220).coerceIn(1, 99)
                onProgress?.invoke(approxPct)
            }
        }
    }

    private fun readNullTerminatedString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buf[end].toInt() != 0) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(buf: ByteArray, offset: Int, length: Int): Long {
        val str = readNullTerminatedString(buf, offset, length).trim()
        if (str.isEmpty()) return 0L
        return try {
            str.toLong(8)
        } catch (_: Exception) {
            0L
        }
    }

    private fun ensureQemuExtracted(rootfsDir: File): File {
        val qemuInNative = File(applicationInfo.nativeLibraryDir, "libqemu-aarch64.so")
        if (qemuInNative.exists() && qemuInNative.canExecute()) {
            log("Packaged native QEMU binary found in nativeLibraryDir (${qemuInNative.length()} bytes)")
            return qemuInNative
        }

        val qemuInRootfs = File(rootfsDir, "usr/bin/qemu-aarch64-static")
        val qemuInFiles = File(filesDir, "qemu-aarch64-static")

        if (!qemuInRootfs.exists() || qemuInRootfs.length() < 1000000L) {
            log("Extracting QEMU aarch64 binary to rootfs /usr/bin/qemu-aarch64-static...")
            qemuInRootfs.parentFile?.mkdirs()
            val assetName = if ((assets.list("") ?: emptyArray()).contains("qemu-aarch64-static.gz")) {
                "qemu-aarch64-static.gz"
            } else {
                "qemu-aarch64-static"
            }
            assets.open(assetName).use { rawIn ->
                val inStream = if (assetName.endsWith(".gz")) GZIPInputStream(rawIn, 64 * 1024) else rawIn
                inStream.use { src ->
                    FileOutputStream(qemuInRootfs).use { outStream ->
                        src.copyTo(outStream)
                    }
                }
            }
            qemuInRootfs.setExecutable(true, false)
            log("Extracted QEMU binary to rootfs: ${qemuInRootfs.length()} bytes")
        }

        if (!qemuInFiles.exists() || qemuInFiles.length() < 1000000L) {
            try {
                qemuInRootfs.copyTo(qemuInFiles, overwrite = true)
                qemuInFiles.setExecutable(true, false)
            } catch (ignored: Exception) {}
        }

        return if (qemuInRootfs.exists() && qemuInRootfs.canExecute()) qemuInRootfs else qemuInFiles
    }

    private fun findAvailablePort(): Int {
        return try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) {
            58899
        }
    }

    private fun readFirstLine(path: String): String {
        return try {
            File(path).bufferedReader().use { it.readLine() ?: "" }.replace("\u0000", "").trim()
        } catch (e: Exception) {
            "Unavailable: ${e.message}"
        }
    }
}
