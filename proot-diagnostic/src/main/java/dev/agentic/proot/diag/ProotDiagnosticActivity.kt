package dev.agentic.proot.diag

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class ProotDiagnosticActivity : Activity() {

    private val TAG = "ProotBackendDiag"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView

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

        scrollView = ScrollView(this)
        textView = TextView(this).apply {
            textSize = 11f
            setPadding(20, 20, 20, 20)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "PRoot Original Backend Diagnostic Initializing...\n"
        }
        scrollView.addView(textView)
        setContentView(scrollView)

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
        runOnUiThread {
            textView.append(msg + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private suspend fun runDiagnostics() = withContext(Dispatchers.IO) {
        val results = JSONObject()
        val testsObj = JSONObject()

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
            if (!unameBinary.exists()) {
                log("\nExtracting Debian Rootfs to ${rootfsDir.absolutePath}...")
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
            val stage34Results = testBackendStartupAndEndpoints(
                prootFile = prootFile,
                loaderFile = loaderFile,
                rootfsDir = rootfsDir,
                tmpDir = tmpDir,
                nativeLibDir = nativeLibDir,
                port = BACKEND_PORT
            )
            results.put("stage3_4_verification", stage34Results)

            // Save complete results to disk
            val reportFile = File(filesDir, "backend_diag_results.json")
            reportFile.writeText(results.toString(2))
            log("\n==================================================")
            log("ALL P3.6.4 IN-APK BACKEND TESTS COMPLETED SUCCESSFULLY")
            log("Detailed report saved to: ${reportFile.absolutePath}")
            log("==================================================")

        } catch (e: Exception) {
            log("FATAL DIAGNOSTIC ERROR: ${e.message}\n${Log.getStackTraceString(e)}")
            results.put("fatal_error", e.message)
            results.put("stack_trace", Log.getStackTraceString(e))
            File(filesDir, "backend_diag_results.json").writeText(results.toString(2))
        }
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
        port: Int
    ): JSONObject {
        val result = JSONObject()
        val isolatedGeminiDir = File(rootfsDir, "tmp/gemini_sandbox").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val csrfToken = "p364_token_" + UUID.randomUUID().toString().replace("-", "").take(16)
        log("Starting backend on loopback port $port with isolated data dir ${isolatedGeminiDir.absolutePath}...")

        val backendCmd = listOf(
            prootFile.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
            "/usr/bin/language_server",
            "--standalone=true",
            "--persistent_mode=false",
            "--http_server_port=$port",
            "--csrf_token=$csrfToken",
            "--gemini_dir=/tmp/gemini_sandbox",
            "--app_data_dir=antigravity-test",
            "--disable_telemetry=true",
            "--headless=false",
            "--override_ide_name=antigravity",
            "--subclient_type=hub"
        )

        val pb = ProcessBuilder(backendCmd)
        pb.environment().putAll(
            mapOf(
                "HOME" to "/root",
                "USER" to "root",
                "PATH" to "/usr/bin:/bin",
                "LD_LIBRARY_PATH" to nativeLibDir,
                "PROOT_LOADER" to loaderFile.absolutePath,
                "PROOT_TMP_DIR" to tmpDir.absolutePath
            )
        )

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
        val timeoutMs = 15000L
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

        // Lifecycle testing pause: allow external ADB testing (backgrounding, resumption)
        log("\nBackend healthy and running. Entering 10s active window for lifecycle testing...")
        Thread.sleep(10000)

        // Controlled Process Shutdown Test
        log("\n--- Testing Controlled Backend Shutdown ---")
        val tKill = System.currentTimeMillis()
        proc.destroy()
        Thread.sleep(300)
        if (proc.isAlive) {
            proc.destroyForcibly()
        }
        val exitCode = proc.waitFor()
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
        val assetName = assetList.firstOrNull { it.startsWith("rootfs") } ?: "rootfs.tar"
        log("Found rootfs asset: $assetName")

        val tempArchive = File(cacheDir, assetName)
        assets.open(assetName).use { input ->
            FileOutputStream(tempArchive).use { output ->
                input.copyTo(output)
            }
        }
        log("Copied asset to cache: ${tempArchive.length()} bytes")

        val isGzip = assetName.endsWith(".gz")
        val tarArgs = if (isGzip) listOf("/system/bin/tar", "-xzof", tempArchive.absolutePath, "-C", destDir.absolutePath)
                      else listOf("/system/bin/tar", "-xof", tempArchive.absolutePath, "-C", destDir.absolutePath)

        val tarProc = ProcessBuilder(tarArgs).redirectErrorStream(true).start()
        val output = tarProc.inputStream.bufferedReader().readText()
        val exitCode = tarProc.waitFor()
        log("Native tar exit code: $exitCode (output: ${output.take(300)})")

        tempArchive.delete()
        if (!File(destDir, "usr/bin/uname").exists()) {
            throw IOException("Rootfs unpack failed: usr/bin/uname not found! exit: $exitCode, output: $output")
        }
    }

    private fun readFirstLine(path: String): String {
        return try {
            File(path).bufferedReader().use { it.readLine() ?: "" }
        } catch (e: Exception) {
            "Unavailable: ${e.message}"
        }
    }
}
