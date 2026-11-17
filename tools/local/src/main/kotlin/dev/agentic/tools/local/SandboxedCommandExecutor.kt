package dev.agentic.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves workspace-relative paths, absolute POSIX/Windows paths, and file:// URIs
 * strictly within the authorised workspace root directory.
 *
 * Fixes P1.1 audit finding where `removePrefix("file://").trimStart('/')` stripped
 * required leading `/` separators on Android POSIX paths (`/data/user/0/...`).
 */
object WorkspacePathResolver {

    fun resolveSafeFile(workspaceRoot: File, uriOrPath: String?): File {
        val canonicalRoot = workspaceRoot.canonicalFile
        val raw = uriOrPath?.trim().orEmpty()
        if (raw.isEmpty() || raw == "." || raw == "/") {
            return canonicalRoot
        }

        val candidateFile: File = if (raw.startsWith("file:", ignoreCase = true)) {
            parseFileUriToFile(raw, canonicalRoot)
        } else {
            val direct = File(raw)
            if (direct.isAbsolute) {
                direct
            } else {
                File(canonicalRoot, raw)
            }
        }

        val canonicalCandidate = candidateFile.canonicalFile
        if (!isInsideWorkspace(canonicalRoot, canonicalCandidate)) {
            throw SecurityException(
                "Access denied: Path '$uriOrPath' resolves outside authorised workspace root (${canonicalRoot.path})"
            )
        }
        return canonicalCandidate
    }

    private fun parseFileUriToFile(uriStr: String, canonicalRoot: File): File {
        return try {
            val parsed = URI(uriStr)
            if (parsed.scheme.equals("file", ignoreCase = true) && !parsed.path.isNullOrBlank()) {
                // Windows host: "/C:/foo" must become "C:/foo" (File("/C:/foo") is an invalid path there).
                val rawUriPath = parsed.path.replace(Regex("^/+([A-Za-z]:/)"), "$1")
                // On Windows, URI("file:///C:/foo").path is "/C:/foo" -> File("/C:/foo") works,
                // whereas URI("file:///workspace/foo").path on Windows might be a POSIX-style path.
                val f = File(rawUriPath)
                if (f.isAbsolute && isInsideWorkspace(canonicalRoot, f.canonicalFile)) {
                    f
                } else {
                    // Check if it was a workspace-root-relative URI like file:///subdir/file.txt
                    val relTrimmed = rawUriPath.trimStart('/')
                    val relCandidate = File(canonicalRoot, relTrimmed)
                    if (isInsideWorkspace(canonicalRoot, relCandidate.canonicalFile) && !f.exists()) {
                        // If rawUriPath starts with canonicalRoot.path (POSIX on Android), use `f`
                        if (rawUriPath.startsWith(canonicalRoot.path.replace('\\', '/'))) {
                            f
                        } else {
                            f
                        }
                    } else {
                        f
                    }
                }
            } else {
                fallbackStripFileScheme(uriStr, canonicalRoot)
            }
        } catch (_: Exception) {
            fallbackStripFileScheme(uriStr, canonicalRoot)
        }
    }

    private fun fallbackStripFileScheme(uriStr: String, canonicalRoot: File): File {
        val withoutScheme = uriStr.replaceFirst(Regex("^file://", RegexOption.IGNORE_CASE), "")
        // On Windows: file:///C:/Users/... -> withoutScheme is "/C:/Users/..."
        val winDriveMatch = Regex("^/+([A-Za-z]:[\\\\/].*)$").find(withoutScheme)
        if (winDriveMatch != null) {
            return File(winDriveMatch.groupValues[1])
        }
        // On Android/POSIX: file:///data/user/0/... -> withoutScheme is "/data/user/0/..."
        // Preserve single leading '/' for absolute POSIX paths!
        val normalizedPosix = if (withoutScheme.startsWith("/")) {
            "/" + withoutScheme.trimStart('/')
        } else {
            withoutScheme
        }
        val direct = File(normalizedPosix)
        return if (direct.isAbsolute) direct else File(canonicalRoot, normalizedPosix)
    }

    fun isInsideWorkspace(canonicalRoot: File, canonicalCandidate: File): Boolean {
        val rootPath = canonicalRoot.path
        val candidatePath = canonicalCandidate.path
        if (candidatePath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return candidatePath.startsWith(prefix)
    }

    fun toNormalizedFileUri(file: File): String {
        val path = file.canonicalFile.absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) {
            "file://$path"
        } else {
            "file:///$path"
        }
    }

    fun toRelativePath(workspaceRoot: File, targetFile: File): String {
        val root = workspaceRoot.canonicalFile
        val target = targetFile.canonicalFile
        return if (target == root) {
            "."
        } else {
            target.relativeTo(root).path.replace('\\', '/')
        }
    }
}

data class ActiveCommandInfo(
    val commandId: String,
    val commandLine: String,
    val cwd: String,
    val startedAtEpochMs: Long
)

data class CommandExecutionOutcome(
    val commandId: String,
    val commandLine: String,
    val cwd: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val cancelled: Boolean = false,
    val blockedReason: String? = null
)

/**
 * Controlled Android-compatible command execution mechanism (P1.2 Phase 4).
 *
 * Security invariants:
 * - Commands execute strictly within the authorised workspace directory (`workspaceRoot`).
 * - Rejects privileged escalation or system-mutation binaries (`su`, `sudo`, `mount`, `chroot`, etc.).
 * - Rejects command strings attempting `..` path traversal outside the workspace.
 * - Enforces execution timeouts and supports cancellation (`killCommand`).
 */
class SandboxedCommandExecutor(
    private val workspaceRoot: File,
    private val defaultTimeoutMs: Long = 15_000L,
    private val maxTimeoutMs: Long = 60_000L
) {
    private data class RunningProcessEntry(
        val info: ActiveCommandInfo,
        val process: Process
    )

    private val activeProcesses = ConcurrentHashMap<String, RunningProcessEntry>()

    fun getActiveCommands(): List<ActiveCommandInfo> =
        activeProcesses.values.map { it.info }.sortedBy { it.startedAtEpochMs }

    fun killCommand(commandId: String? = null): Boolean {
        if (commandId.isNullOrBlank()) {
            var killedAny = false
            for ((id, entry) in activeProcesses) {
                destroyProcessTree(entry.process)
                activeProcesses.remove(id)
                killedAny = true
            }
            return killedAny
        }
        val removed = activeProcesses.remove(commandId) ?: return false
        destroyProcessTree(removed.process)
        return true
    }

    suspend fun executeCommand(
        command: String,
        args: List<String> = emptyList(),
        cwdUriOrPath: String? = null,
        timeoutMs: Long? = null,
        commandId: String = "cmd-${UUID.randomUUID().toString().take(8)}"
    ): CommandExecutionOutcome = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        val fullCommandLine = if (args.isEmpty()) {
            command.trim()
        } else {
            (listOf(command.trim()) + args).joinToString(" ")
        }

        if (fullCommandLine.isBlank()) {
            return@withContext CommandExecutionOutcome(
                commandId = commandId,
                commandLine = "",
                cwd = workspaceRoot.canonicalFile.path,
                stdout = "",
                stderr = "Error: Empty command line.",
                exitCode = 2,
                timedOut = false,
                blockedReason = "Empty command line"
            )
        }

        val resolvedCwd = try {
            WorkspacePathResolver.resolveSafeFile(workspaceRoot, cwdUriOrPath)
        } catch (se: SecurityException) {
            return@withContext CommandExecutionOutcome(
                commandId = commandId,
                commandLine = fullCommandLine,
                cwd = cwdUriOrPath.orEmpty(),
                stdout = "",
                stderr = se.message ?: "Access denied: cwd is outside workspace.",
                exitCode = 126,
                timedOut = false,
                blockedReason = se.message
            )
        }

        if (!resolvedCwd.exists() || !resolvedCwd.isDirectory) {
            return@withContext CommandExecutionOutcome(
                commandId = commandId,
                commandLine = fullCommandLine,
                cwd = resolvedCwd.path,
                stdout = "",
                stderr = "Error: Working directory does not exist: ${resolvedCwd.path}",
                exitCode = 126,
                timedOut = false
            )
        }

        val securityViolation = validateCommandSecurity(fullCommandLine, resolvedCwd)
        if (securityViolation != null) {
            return@withContext CommandExecutionOutcome(
                commandId = commandId,
                commandLine = fullCommandLine,
                cwd = resolvedCwd.path,
                stdout = "",
                stderr = "Security Violation: $securityViolation",
                exitCode = 126,
                timedOut = false,
                blockedReason = securityViolation
            )
        }

        val effectiveTimeout = (timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs).coerceIn(500L, maxTimeoutMs)
        val shellCommand = buildShellInvocation(fullCommandLine)

        try {
            val pb = ProcessBuilder(shellCommand)
                .directory(resolvedCwd)
            pb.environment()["HOME"] = workspaceRoot.canonicalFile.path
            pb.environment()["WORKSPACE_ROOT"] = workspaceRoot.canonicalFile.path

            val process = pb.start()
            val info = ActiveCommandInfo(
                commandId = commandId,
                commandLine = fullCommandLine,
                cwd = resolvedCwd.path,
                startedAtEpochMs = System.currentTimeMillis()
            )
            activeProcesses[commandId] = RunningProcessEntry(info, process)

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread { readBounded(process.inputStream, stdoutBuilder) }
                .apply { isDaemon = true; start() }
            val stderrThread = Thread { readBounded(process.errorStream, stderrBuilder) }
                .apply { isDaemon = true; start() }
            try {
                val finished = waitCancellably(process, effectiveTimeout)
                if (!finished) {
                    destroyProcessTree(process)
                    stdoutThread.join(500)
                    stderrThread.join(500)
                    return@withContext CommandExecutionOutcome(
                        commandId = commandId,
                        commandLine = fullCommandLine,
                        cwd = resolvedCwd.path,
                        stdout = stdoutBuilder.toString().trimEnd(),
                        stderr = (stderrBuilder.toString().trimEnd() + "\nCommand timed out after ${effectiveTimeout}ms.").trim(),
                        exitCode = 124,
                        timedOut = true
                    )
                }

                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                val exit = process.exitValue()
                val rawStderr = stderrBuilder.toString().trimEnd()
                val enhancedStderr = if (exit == 127 || rawStderr.contains("not found", ignoreCase = true) || rawStderr.contains("is not recognized", ignoreCase = true)) {
                    "$rawStderr\n(Note: Command executable is not available in the Android local shell sandbox.)".trim()
                } else {
                    rawStderr
                }

                CommandExecutionOutcome(
                    commandId = commandId,
                    commandLine = fullCommandLine,
                    cwd = resolvedCwd.path,
                    stdout = stdoutBuilder.toString().trimEnd(),
                    stderr = enhancedStderr,
                    exitCode = exit,
                    timedOut = false,
                    cancelled = !activeProcesses.containsKey(commandId) && exit != 0
                )
            } catch (ce: CancellationException) {
                destroyProcessTree(process)
                throw ce
            } finally {
                activeProcesses.remove(commandId)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            CommandExecutionOutcome(
                commandId = commandId,
                commandLine = fullCommandLine,
                cwd = resolvedCwd.path,
                stdout = "",
                stderr = "Failed to execute command in Android sandbox: ${ioe.message}",
                exitCode = 127,
                timedOut = false
            )
        }
    }

    /** Reads at most [MAX_OUTPUT_CHARS] chars (bounded even for a single unterminated huge line), then drains. */
    internal fun readBounded(stream: java.io.InputStream, sink: StringBuilder) {
        runCatching {
            val reader = stream.bufferedReader()
            val buf = CharArray(4096)
            var truncated = false
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val room = MAX_OUTPUT_CHARS - sink.length
                if (room > 0) {
                    sink.append(buf, 0, minOf(n, room))
                } else if (!truncated) {
                    truncated = true
                }
            }
            if (truncated) sink.append("\n[output truncated at ${MAX_OUTPUT_CHARS} chars]")
        }
    }

    /** Polls so coroutine cancellation is honoured promptly (Process.waitFor ignores coroutine cancellation). */
    private suspend fun waitCancellably(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) return true
            if (System.nanoTime() >= deadline) return false
        }
    }

    /**
     * Kills the shell AND its descendants. Descendant lookup uses Java 9 ProcessHandle, which is absent on
     * older Android API levels; there we fall back to killing only the direct child (documented limitation).
     */
    private fun destroyProcessTree(process: Process) {
        try {
            process.descendants().forEach { it.destroyForcibly() }
        } catch (_: Throwable) {
            // NoSuchMethodError / NoClassDefFoundError on pre-API-33 Android: fall through.
        }
        process.destroyForcibly()
    }
    /**
     * Best-effort DENYLIST screening. This is NOT an isolation boundary: an attacker-controlled command line
     * can still use constructs this screen does not model. It only rejects clearly unsafe forms; see
     * research/p1/p1.3-runtime-validation-report.md for the required real isolation architecture.
     */
    internal fun validateCommandSecurity(commandLine: String, cwd: File): String? {
        if (commandLine.contains('`') || commandLine.contains("$(") || commandLine.contains("\${")) {
            return "Command substitution and parameter expansion are prohibited in workspace sandbox."
        }
        // Strip quoting/escaping so "/etc/passwd" and '/etc/passwd' are screened like /etc/passwd.
        val unquoted = commandLine.replace(Regex("[\"'\\\\]"), "")
        val tokens = unquoted.split(Regex("[\\s;|&<>()=,:]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "Empty command"
        val canonicalRoot = workspaceRoot.canonicalFile

        for (token in tokens) {
            val baseExecutable = token.substringAfterLast('/').substringAfterLast('\\').lowercase()
            if (baseExecutable in FORBIDDEN_EXECUTABLES) {
                return "Privileged or system-level executable '$baseExecutable' is prohibited in workspace sandbox."
            }
            if (token == "~" || token.startsWith("~/")) {
                return "Command references home-directory shorthand '$token', which is not allowed."
            }
            if (token.contains('/') || token.contains('\\') || token.contains("..")) {
                // canonicalFile follows existing symlinks, so links pointing out of the workspace are caught.
                val candidate = if (token.startsWith("/") || File(token).isAbsolute) File(token) else File(cwd, token)
                val canonical = runCatching { candidate.canonicalFile }.getOrNull()
                val devNull = token == "/dev/null"
                if (!devNull && (canonical == null || !WorkspacePathResolver.isInsideWorkspace(canonicalRoot, canonical))) {
                    return "Command references path '$token' outside the authorised workspace root."
                }
            }
        }
        return null
    }
    fun isTermuxAvailable(): Boolean {
        val termuxDir = File("/data/data/com.termux")
        val termuxSh = File("/data/data/com.termux/files/usr/bin/sh")
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        return (termuxDir.exists() && termuxDir.canRead()) || termuxSh.exists() || termuxBash.exists()
    }

    fun getEnvironmentInfo(): Map<String, String> {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return mapOf(
            "os" to if (isWindows) "windows" else "android-linux",
            "arch" to System.getProperty("os.arch").orEmpty(),
            "shell" to if (isWindows) "cmd.exe" else "sh/bash",
            "termux_installed" to isTermuxAvailable().toString(),
            "system_description" to if (isWindows) {
                "Windows Host"
            } else {
                "Android ARM64 Linux Environment (Termux / PRoot sandbox, POSIX sh/bash, do not use Windows PowerShell or CMD commands)"
            }
        )
    }

    private fun buildShellInvocation(commandLine: String): List<String> {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        val termuxSh = File("/data/data/com.termux/files/usr/bin/sh")
        val androidSh = File("/system/bin/sh")
        val posixSh = File("/bin/sh")
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return when {
            termuxBash.exists() && termuxBash.canExecute() -> listOf(termuxBash.absolutePath, "-c", commandLine)
            termuxSh.exists() && termuxSh.canExecute() -> listOf(termuxSh.absolutePath, "-c", commandLine)
            androidSh.exists() -> listOf(androidSh.absolutePath, "-c", commandLine)
            posixSh.exists() -> listOf(posixSh.absolutePath, "-c", commandLine)
            isWindows -> listOf("cmd.exe", "/c", commandLine)
            else -> listOf("sh", "-c", commandLine)
        }
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 64 * 1024

        private val FORBIDDEN_EXECUTABLES = setOf(
            "su",
            "sudo",
            "mount",
            "umount",
            "chroot",
            "reboot",
            "shutdown",
            "halt",
            "poweroff",
            "setprop",
            "insmod",
            "rmmod",
            "modprobe",
            "dd",
            "busybox",
            "toybox",
            "eval",
            "exec",
            "ln",
            "nsenter",
            "unshare",
            "mkfs",
            "fdisk",
            "iptables"
        )
    }
}



