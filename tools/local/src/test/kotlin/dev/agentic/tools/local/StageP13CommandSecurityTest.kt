package dev.agentic.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * P1.3 command-execution security regression tests.
 * CATEGORY: unit tests on a **Windows/JVM host** (not Android, not an emulator, not a device).
 */
class StageP13CommandSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun ws() = tmp.newFolder("ws").canonicalFile

    // ---- validator hardening (screening only; NOT an isolation boundary) ----

    @Test
    fun `quoted absolute paths and quoted traversal are rejected`() {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        val outside = if (isWindows) "C:/Windows/win.ini" else "/etc/hosts"
        for (cmd in listOf(
            "cat \"$outside\"",
            "cat '$outside'",
            "cat \"../x\"",
            "type $outside",
            "cat x=$outside",
            "cat ~/secret"
        )) {
            assertNotNull("must reject: $cmd", ex.validateCommandSecurity(cmd, ws))
        }
    }

    @Test
    fun `command substitution and expansion are rejected`() {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        for (cmd in listOf("echo `id`", "echo $(id)", "cat \${HOME}/x")) {
            assertNotNull("must reject: $cmd", ex.validateCommandSecurity(cmd, ws))
        }
    }

    @Test
    fun `privilege wrappers and link or eval bypass binaries are rejected anywhere in the line`() {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        for (cmd in listOf("env su", "busybox dd if=x", "toybox cat x", "eval ls", "ln -s / l; cat l/etc/hosts", "echo hi && sudo id", "/system/bin/su")) {
            assertNotNull("must reject: $cmd", ex.validateCommandSecurity(cmd, ws))
        }
    }

    @Test
    fun `existing symlink pointing outside workspace is rejected by command validation`() {
        val ws = ws()
        val outsideDir = tmp.newFolder("outside").canonicalFile
        File(outsideDir, "secret.txt").writeText("s")
        val link = File(ws, "escape")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outsideDir.toPath()) }.isSuccess
        assumeTrue("symlink creation not permitted on this host", created)
        val ex = SandboxedCommandExecutor(ws)
        assertNotNull(ex.validateCommandSecurity("cat escape/secret.txt", ws))
        // File-tool resolver must also follow the link and refuse.
        val rejected = try { WorkspacePathResolver.resolveSafeFile(ws, "escape/secret.txt"); false } catch (_: SecurityException) { true }
        assertTrue(rejected)
    }

    @Test
    fun `benign commands inside the workspace are still allowed`() {
        val ws = ws()
        File(ws, "sub").mkdirs()
        val ex = SandboxedCommandExecutor(ws)
        assertNull(ex.validateCommandSecurity("echo hello", ws))
        assertNull(ex.validateCommandSecurity("echo hi > sub/out.txt", ws))
        assertNull(ex.validateCommandSecurity("ls sub", ws))
    }

    // ---- output bound ----

    @Test
    fun `output capture is bounded even for one enormous line with no newline`() {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        val sink = StringBuilder()
        val hugeNoNewline = ByteArray(5_000_000) { 'a'.code.toByte() }
        ex.readBounded(ByteArrayInputStream(hugeNoNewline), sink)
        assertTrue("sink was ${sink.length}", sink.length < 70_000)
        assertTrue(sink.contains("[output truncated"))
    }

    // ---- cancellation / timeout / process-tree ----

    @Test
    fun `coroutine cancellation promptly kills the running process`() = runBlocking {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        val cmd = if (isWindows) "ping -n 60 127.0.0.1" else "sleep 60"
        val job = async(Dispatchers.IO) { ex.executeCommand(cmd, timeoutMs = 60_000, commandId = "cancel-me") }
        var seen = false
        repeat(60) { if (!seen && ex.getActiveCommands().any { it.commandId == "cancel-me" }) seen = true else if (!seen) delay(50) }
        assertTrue("command never became active", seen)
        val t0 = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took ${elapsedMs}ms (should be well under the 60s timeout)", elapsedMs < 5_000)
        assertTrue(ex.getActiveCommands().isEmpty())
    }

    @Test
    fun `timeout terminates the whole process tree not just the shell`() = runBlocking {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        // The shell spawns a long-lived grandchild; after timeout no descendant may survive.
        val cmd = if (isWindows) "ping -n 60 127.0.0.1 > NUL" else "sleep 60 & sleep 60"
        val before = ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()
        val out = ex.executeCommand(cmd, timeoutMs = 800)
        assertTrue(out.timedOut)
        delay(600)
        val survivors = ProcessHandle.current().descendants()
            .filter { it.pid() !in before && it.info().command().map { c -> c.lowercase() }.map { c -> c.contains("ping") || c.contains("sleep") }.orElse(false) }
            .toList()
        survivors.forEach { it.destroyForcibly() } // clean up regardless
        assertTrue("orphaned descendants survived timeout: ${survivors.map { it.pid() }}", survivors.isEmpty())
    }

    @Test
    fun `workspace root is the working directory and env HOME points at it`() = runBlocking {
        val ws = ws()
        val ex = SandboxedCommandExecutor(ws)
        val out = ex.executeCommand(if (isWindows) "cd" else "pwd")
        assertEquals(0, out.exitCode)
        assertTrue(out.stdout.trim().replace('\\', '/').endsWith(ws.name))
        assertFalse(out.timedOut)
    }
}
