package dev.agentic.core.network.jetski

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StageP05DFrontendModeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mode manager transitions between Fallback, Imported Jetski Web, and Remote Host`() {
        val storageFile = File(tempFolder.root, "jetski-bundle.zip")
        val manager = JetskiFrontendModeManager(bundleStorageFile = storageFile)

        // 1. Starts in Clean-Room Native Jetbox Fallback when no bundle exists
        val initial = manager.initializeFromStorage()
        assertEquals(FrontendUiMode.NATIVE_JETBOX_FALLBACK, initial.activeMode)
        assertFalse(initial.hasValidBundle)
        assertNull(manager.currentBundle())

        // 2. Rejects switching to IMPORTED_JETSKI_WEB when no bundle is loaded
        assertFalse(manager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB))
        assertNotNull(manager.state.value.lastValidationError)

        // 3. Rejects invalid bundle stream without crashing
        val invalidRes = manager.importBundleFromStream(
            ByteArrayInputStream("not-a-zip".toByteArray(Charsets.UTF_8)),
            "corrupt.bin"
        )
        assertTrue(invalidRes is BundleValidationResult.InvalidFormat)
        assertEquals(FrontendUiMode.NATIVE_JETBOX_FALLBACK, manager.state.value.activeMode)
        assertTrue(manager.state.value.lastValidationError!!.contains("Invalid bundle format"))

        // 4. Imports valid Jetski Web bundle stream and switches immediately to IMPORTED_JETSKI_WEB
        val validZipBytes = createValidBundleBytes()
        val importRes = manager.importBundleFromStream(
            ByteArrayInputStream(validZipBytes),
            "language_server.exe"
        )
        assertTrue(importRes is BundleValidationResult.Valid)
        assertEquals(FrontendUiMode.IMPORTED_JETSKI_WEB, manager.state.value.activeMode)
        assertTrue(manager.state.value.hasValidBundle)
        assertNull(manager.state.value.lastValidationError)
        assertNotNull(manager.currentBundle())

        // 5. Switch to NATIVE_JETBOX_FALLBACK and back to IMPORTED_JETSKI_WEB
        assertTrue(manager.switchToMode(FrontendUiMode.NATIVE_JETBOX_FALLBACK))
        assertEquals(FrontendUiMode.NATIVE_JETBOX_FALLBACK, manager.state.value.activeMode)
        assertTrue(manager.switchToMode(FrontendUiMode.IMPORTED_JETSKI_WEB))
        assertEquals(FrontendUiMode.IMPORTED_JETSKI_WEB, manager.state.value.activeMode)

        // 6. Remote Host URL validation and activation
        assertFalse(manager.activateRemoteHost("ftp://invalid-scheme"))
        assertNotNull(manager.state.value.lastValidationError)

        assertTrue(manager.activateRemoteHost("https://192.168.1.50:9090"))
        assertEquals(FrontendUiMode.REMOTE_HOST, manager.state.value.activeMode)
        assertEquals("https://192.168.1.50:9090", manager.state.value.remoteHostUrl)

        // Disabling remote host returns to IMPORTED_JETSKI_WEB since bundle is still loaded
        manager.disableRemoteHost()
        assertEquals(FrontendUiMode.IMPORTED_JETSKI_WEB, manager.state.value.activeMode)

        // 7. Clearing imported bundle deletes storage file and switches to NATIVE_JETBOX_FALLBACK
        manager.clearImportedBundle()
        assertEquals(FrontendUiMode.NATIVE_JETBOX_FALLBACK, manager.state.value.activeMode)
        assertFalse(manager.state.value.hasValidBundle)
        assertFalse(storageFile.exists())
    }

    private fun createValidBundleBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entries = mapOf(
                "index.html" to "<!doctype html><html><head></head><body><div id=\"root\"></div><script src=\"/main.js\"></script></body></html>",
                "main.js" to "globalThis.__JETSKI_LS_CLIENT_DECORATOR__; console.log('LanguageServerService');",
                "jetbox.css" to ":root { --color-jetbox-background: #101010; }",
                "compiled_tailwind.css" to ".bg-jetbox-background { background-color: #101010; }",
                "diff_worker.js" to "self.onmessage = function(e) {};",
                "workers/shiki_worker.js" to "self.onmessage = function(e) {};"
            )
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
