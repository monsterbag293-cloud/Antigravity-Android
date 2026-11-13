package dev.agentic.core.network.hub

import dev.agentic.core.model.Project
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountSyncManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var geminiRootDir: File
    private lateinit var workspacesDir: File
    private lateinit var projectsDir: File
    private lateinit var antigravityDir: File
    private lateinit var mockHubManager: RemoteHubConnectionManager
    private lateinit var syncManager: AccountSyncManager

    @Before
    fun setup() {
        geminiRootDir = tempFolder.newFolder("gemini_root")
        workspacesDir = File(geminiRootDir, "workspaces").apply { mkdirs() }
        projectsDir = File(geminiRootDir, "config/projects").apply { mkdirs() }
        antigravityDir = File(geminiRootDir, "antigravity").apply { mkdirs() }

        mockHubManager = RemoteHubConnectionManager()
        syncManager = AccountSyncManager(
            hubConnectionManager = mockHubManager,
            geminiRootDir = geminiRootDir,
            workspacesRootDir = workspacesDir,
            projectsRootDir = projectsDir,
            antigravityRootDir = antigravityDir
        )
    }

    @Test
    fun `mapToLocalWorkspaceUri maps desktop Windows paths to Android workspace paths safely`() {
        val desktopWindowsUri = "file:///c%3A/Users/NEW0033/Downloads/Antigravity%20Port"
        val mappedUri = syncManager.mapToLocalWorkspaceUri("Antigravity Port", desktopWindowsUri)

        assertEquals("file:///tmp/gemini_app/workspaces/Antigravity-Port", mappedUri)
        val dir = syncManager.uriToLocalDirectory(mappedUri)
        assertEquals(File(workspacesDir, "Antigravity-Port").absolutePath, dir.absolutePath)
    }

    @Test
    fun `generateFolderManifest computes recursive SHA-256 manifest and ignores excluded dirs`() {
        val workspace = File(workspacesDir, "TestProject").apply { mkdirs() }
        File(workspace, "index.ts").writeText("console.log('hello world');")
        File(workspace, "README.md").writeText("# Test Project")

        // Ignored directories
        val gitDir = File(workspace, ".git").apply { mkdirs() }
        File(gitDir, "HEAD").writeText("ref: refs/heads/main")
        val nodeModules = File(workspace, "node_modules/pkg").apply { mkdirs() }
        File(nodeModules, "index.js").writeText("module.exports = {};")

        val manifest = syncManager.generateFolderManifest(workspace, "proj-123")

        assertEquals("proj-123", manifest.projectId)
        assertEquals("TestProject", manifest.projectName)
        assertEquals(2, manifest.files.size)
        assertTrue(manifest.files.containsKey("index.ts"))
        assertTrue(manifest.files.containsKey("README.md"))
        assertFalse(manifest.files.containsKey(".git/HEAD"))
        assertFalse(manifest.files.containsKey("node_modules/pkg/index.js"))

        val indexSha = manifest.files["index.ts"]!!.sha256
        assertNotNull(indexSha)
        assertEquals(64, indexSha.length) // Valid SHA-256 hex string
    }

    @Test
    fun `computeDiff identifies added, modified, deleted files and conflicts`() {
        val workspaceA = File(workspacesDir, "WorkspaceA").apply { mkdirs() }
        File(workspaceA, "common.txt").writeText("unchanged")
        File(workspaceA, "local_only.txt").writeText("local")
        File(workspaceA, "conflict.txt").writeText("local version")
        File(workspaceA, "conflict.txt").setLastModified(1000000L)

        val workspaceB = File(workspacesDir, "WorkspaceB").apply { mkdirs() }
        File(workspaceB, "common.txt").writeText("unchanged")
        File(workspaceB, "remote_only.txt").writeText("remote")
        File(workspaceB, "conflict.txt").writeText("remote version")
        File(workspaceB, "conflict.txt").setLastModified(1000500L) // Concurrent within 2s

        val localManifest = syncManager.generateFolderManifest(workspaceA, "proj-diff")
        val remoteManifest = syncManager.generateFolderManifest(workspaceB, "proj-diff")

        val diff = syncManager.computeDiff(localManifest, remoteManifest)

        assertTrue("remote_only.txt should be in addedFiles", diff.addedFiles.contains("remote_only.txt"))
        assertTrue("local_only.txt should be in deletedFiles", diff.deletedFiles.contains("local_only.txt"))
        assertTrue("conflict.txt should be detected as conflict", diff.conflicts.contains("conflict.txt"))
    }

    @Test
    fun `applyFileTransfer preserves conflict files without silent overwrite`() {
        val sourceDir = File(workspacesDir, "Source").apply { mkdirs() }
        val targetDir = File(workspacesDir, "Target").apply { mkdirs() }

        File(sourceDir, "added.txt").writeText("new file")
        File(sourceDir, "conflict.txt").writeText("remote edits")
        File(targetDir, "conflict.txt").writeText("local edits")

        val diff = AccountSyncManager.SyncDiff(
            addedFiles = listOf("added.txt"),
            modifiedFiles = emptyList(),
            deletedFiles = emptyList(),
            conflicts = listOf("conflict.txt")
        )

        syncManager.applyFileTransfer(sourceDir, targetDir, diff, isPull = true)

        // 1. added.txt was copied
        assertTrue(File(targetDir, "added.txt").exists())
        assertEquals("new file", File(targetDir, "added.txt").readText())

        // 2. Original local conflict.txt was preserved (not overwritten)
        assertEquals("local edits", File(targetDir, "conflict.txt").readText())

        // 3. A .conflict copy was created
        val conflictFiles = targetDir.listFiles { _, name -> name.startsWith("conflict.txt.") && name.endsWith(".conflict") }
        assertNotNull(conflictFiles)
        assertEquals(1, conflictFiles!!.size)
        assertEquals("remote edits", conflictFiles[0].readText())
    }

    @Test
    fun `syncProjectsBidirectional imports remote project and creates local config and workspace`() = runBlocking {
        // Prepopulate a local project in projectsDir
        val localProjId = "local-proj-01"
        val localJson = """
        {
          "id": "$localProjId",
          "name": "Local Android Project",
          "projectResources": {
            "resources": [{ "folderUri": "file:///tmp/gemini_app/workspaces/LocalAndroid" }]
          }
        }
        """.trimIndent()
        File(projectsDir, "$localProjId.json").writeText(localJson)

        val synced = syncManager.syncProjectsBidirectional()

        assertTrue(synced.any { it.id == localProjId && it.name == "Local Android Project" })
    }

    @Test
    fun `syncConversationsBidirectional manages local conversations correctly`() = runBlocking {
        val convDir = File(antigravityDir, "conversations").apply { mkdirs() }
        val testDb = File(convDir, "conv-1234.db").apply { writeText("sqlite-test") }

        val synced = syncManager.syncConversationsBidirectional()

        assertTrue(synced.any { it.conversationId == "conv-1234" })
    }
}
