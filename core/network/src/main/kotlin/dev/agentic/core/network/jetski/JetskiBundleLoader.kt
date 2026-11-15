package dev.agentic.core.network.jetski

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class JetskiAppConfig(
    val productName: String = "antigravity",
    val csrfToken: String,
    val appVersion: String = "1.107.0",
    val devMode: Boolean = false,
    val enableClientDecoratorHook: Boolean = true
)

data class JetskiBundleMetadata(
    val sourceFilePath: String,
    val zipOffset: Long,
    val zipLength: Long,
    val entryCount: Int,
    val assetPaths: Set<String>
)

data class JetskiAssetResponse(
    val path: String,
    val mimeType: String,
    val bytes: ByteArray,
    val statusCode: Int = 200
)

sealed interface BundleValidationResult {
    data class Valid(val metadata: JetskiBundleMetadata) : BundleValidationResult
    data class InvalidFormat(val reason: String) : BundleValidationResult
    data class IncompleteBundle(
        val missingAssets: List<String>,
        val foundAssets: Set<String>
    ) : BundleValidationResult
    data class IncompatibleBundle(val reason: String) : BundleValidationResult
}

/**
 * Runtime loader and validator for user-supplied Jetski Web bundles (ADR-05 / Stage P0.5-B).
 *
 * Supports reading directly from either:
 * 1. A standalone Jetski Web `.zip` archive, or
 * 2. A desktop `language_server.exe` binary containing the embedded ZIP archive
 *    (fast-path offset 163294824 or dynamic Central Directory discovery),
 * without extracting loose files to disk.
 */
class JetskiBundleLoader {

    companion object {
        const val KNOWN_DESKTOP_ZIP_OFFSET = 163_294_824L
        const val KNOWN_DESKTOP_ZIP_LENGTH = 5_628_057L

        private const val LOCAL_FILE_HEADER_SIG = 0x04034B50
        private const val CENTRAL_DIR_HEADER_SIG = 0x02014B50
        private const val EOCD_SIG = 0x06054B50

        val REQUIRED_ASSETS = listOf(
            "index.html",
            "main.js",
            "jetbox.css",
            "compiled_tailwind.css",
            "diff_worker.js",
            "workers/shiki_worker.js"
        )

        private val MIME_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "mjs" to "application/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "svg" to "image/svg+xml",
            "mp3" to "audio/mpeg",
            "png" to "image/png",
            "woff2" to "font/woff2"
        )
    }

    internal data class ZipEntryIndex(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderRelOffset: Long
    )

    inner class LoadedJetskiBundle internal constructor(
        val metadata: JetskiBundleMetadata,
        private val backingFile: File,
        private val entries: Map<String, ZipEntryIndex>
    ) {
        fun hasAsset(path: String): Boolean {
            val normalized = path.trimStart('/')
            return entries.containsKey(normalized)
        }

        fun readAssetBytes(path: String): ByteArray? {
            val normalized = path.trimStart('/')
            val entry = entries[normalized] ?: return null
            RandomAccessFile(backingFile, "r").use { raf ->
                val absLocalHeader = metadata.zipOffset + entry.localHeaderRelOffset
                if (absLocalHeader + 30 > raf.length()) return null
                raf.seek(absLocalHeader)
                val headerBuf = ByteArray(30)
                raf.readFully(headerBuf)
                val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val sig = bb.int
                if (sig != LOCAL_FILE_HEADER_SIG) return null
                val nameLen = bb.getShort(26).toInt() and 0xFFFF
                val extraLen = bb.getShort(28).toInt() and 0xFFFF
                val dataOffset = absLocalHeader + 30L + nameLen + extraLen
                if (entry.compressedSize < 0 || entry.compressedSize > 64 * 1024 * 1024) return null
                if (dataOffset + entry.compressedSize > raf.length()) return null

                raf.seek(dataOffset)
                val compBytes = ByteArray(entry.compressedSize.toInt())
                raf.readFully(compBytes)

                return when (entry.compressionMethod) {
                    0 -> compBytes
                    8 -> inflateRaw(compBytes, entry.uncompressedSize.toInt().coerceAtLeast(compBytes.size))
                    else -> null
                }
            }
        }

        @Volatile
        private var cachedPatchedMainJs: ByteArray? = null

        private fun patchMainJsForMobile(raw: ByteArray): ByteArray {
            cachedPatchedMainJs?.let { return it }
            var text = raw.toString(Charsets.UTF_8)
            val splitTarget = """splitView:{feature:{enabled:!1},applyAll:[{runtimeGating:{customFlag:"enable-split-view"},feature:{enabled:!0}},{runtimeGating:{isDevMode:!0},feature:{enabled:!0}}"""
            val splitReplacement = """splitView:{feature:{enabled:!1},applyAll:[{runtimeGating:{customFlag:"enable-split-view"},feature:{enabled:!1}},{runtimeGating:{isDevMode:!0},feature:{enabled:!1}}"""
            if (text.contains(splitTarget)) {
                text = text.replace(splitTarget, splitReplacement)
            }
            // Keep original desktop model menu, popups, welcome screen, and closable sidebar,
            // while making the model thinking-level chevron always visible and independently tappable.
            val submenuTriggerTarget = """A.createElement(DRa,{disabled:d,onClick:p=>{p.stopPropagation();c?.(p)},"""
            val submenuTriggerReplacement = """A.createElement(DRa,{disabled:d,onClick:p=>{p.stopPropagation();if(p.target?.closest?.("[data-agy-submenu-arrow]")){p.preventDefault();return}c?.(p)},"""
            if (text.contains(submenuTriggerTarget)) {
                text = text.replace(submenuTriggerTarget, submenuTriggerReplacement)
            }
            val chevronTarget = """A.createElement("span",{className:dj("flex shrink-0 pl-1 opacity-50",f&&dj("hidden group-hover:flex group-data-[highlighted]:flex",!l&&"group-focus:flex"))},A.createElement(lt,{name:"chevron_right",size:14}))"""
            val chevronReplacement = """A.createElement("span",{"data-agy-submenu-arrow":"1",className:"flex shrink-0 items-center justify-center pl-2 pr-1 py-0.5 -mr-1 rounded opacity-70 hover:opacity-100"},A.createElement(lt,{name:"chevron_right",size:14}))"""
            if (text.contains(chevronTarget)) {
                text = text.replace(chevronTarget, chevronReplacement)
            }
            text = text
                .replace("This Computer", "This Device")
                .replace("this computer", "this device")
            val patchedBytes = text.toByteArray(Charsets.UTF_8)
            cachedPatchedMainJs = patchedBytes
            return patchedBytes
        }

        fun serveAsset(
            requestPath: String,
            appConfig: JetskiAppConfig
        ): JetskiAssetResponse? {
            val cleanPath = requestPath.substringBefore('?').substringBefore('#').ifBlank { "/" }
            val normalized = cleanPath.trimStart('/')

            // Direct static asset hit
            if (normalized.isNotEmpty() && normalized != "index.html" && entries.containsKey(normalized)) {
                val raw = readAssetBytes(normalized) ?: return null
                val bytes = if (normalized == "main.js") patchMainJsForMobile(raw) else raw
                val ext = normalized.substringAfterLast('.', "").lowercase()
                val mime = MIME_TYPES[ext] ?: "application/octet-stream"
                return JetskiAssetResponse(
                    path = "/$normalized",
                    mimeType = mime,
                    bytes = bytes
                )
            }

            // Missing static file with an extension (e.g. /missing.js) should 404, not SPA-fallback
            val lastSegment = normalized.substringAfterLast('/')
            if (normalized != "index.html" && lastSegment.contains('.')) {
                return null
            }

            // SPA route (/, /c/<id>, /history, /customizations, /onboarding, or /index.html)
            val indexBytes = readAssetBytes("index.html") ?: return null
            val indexHtml = indexBytes.toString(Charsets.UTF_8)
            val injectedHtml = injectBootConfig(indexHtml, appConfig)
            return JetskiAssetResponse(
                path = "/index.html",
                mimeType = "text/html; charset=utf-8",
                bytes = injectedHtml.toByteArray(Charsets.UTF_8)
            )
        }
    }

    /**
     * Validates a candidate bundle file (either `.zip` or `language_server.exe`) and returns
     * both the validation result and an on-demand [LoadedJetskiBundle] handle if valid.
     */
    fun validateAndOpen(file: File): Pair<BundleValidationResult, LoadedJetskiBundle?> {
        if (!file.exists() || !file.isFile) {
            return BundleValidationResult.InvalidFormat("File does not exist: ${file.path}") to null
        }
        val length = file.length()
        if (length < 22) {
            return BundleValidationResult.InvalidFormat("File is too small to contain a valid ZIP archive ($length bytes)") to null
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val slice = locateEmbeddedZipSlice(raf)
                    ?: return BundleValidationResult.InvalidFormat(
                        "No valid ZIP central directory or embedded Jetski Web archive found in ${file.name}"
                    ) to null

                val entries = parseCentralDirectory(raf, slice.first, slice.second)
                    ?: return BundleValidationResult.InvalidFormat(
                        "Corrupt or unreadable ZIP central directory in ${file.name}"
                    ) to null

                // Check for path traversal entries
                for (name in entries.keys) {
                    if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                        return BundleValidationResult.InvalidFormat(
                            "Unsafe ZIP entry path rejected: $name"
                        ) to null
                    }
                }

                val missing = REQUIRED_ASSETS.filter { req ->
                    val entry = entries[req]
                    entry == null || entry.uncompressedSize <= 0L
                }
                if (missing.isNotEmpty()) {
                    return BundleValidationResult.IncompleteBundle(
                        missingAssets = missing,
                        foundAssets = entries.keys
                    ) to null
                }

                val metadata = JetskiBundleMetadata(
                    sourceFilePath = file.absolutePath,
                    zipOffset = slice.first,
                    zipLength = slice.second,
                    entryCount = entries.size,
                    assetPaths = entries.keys
                )
                val bundle = LoadedJetskiBundle(metadata, file, entries)

                // Verify semantic compatibility of index.html and main.js
                val indexHtml = bundle.readAssetBytes("index.html")?.toString(Charsets.UTF_8) ?: ""
                if (!indexHtml.contains("id=\"root\"") || !indexHtml.contains("main.js")) {
                    return BundleValidationResult.IncompatibleBundle(
                        "index.html does not contain expected Jetski Web root mount (#root) and main.js script tag"
                    ) to null
                }

                val mainJsBytes = bundle.readAssetBytes("main.js")
                    ?: return BundleValidationResult.IncompatibleBundle("Unable to inflate main.js") to null
                val mainJsStr = mainJsBytes.toString(Charsets.UTF_8)
                if (!mainJsStr.contains("__JETSKI_LS_CLIENT_DECORATOR__")) {
                    return BundleValidationResult.IncompatibleBundle(
                        "main.js does not contain expected __JETSKI_LS_CLIENT_DECORATOR__ hook"
                    ) to null
                }

                BundleValidationResult.Valid(metadata) to bundle
            }
        } catch (e: Exception) {
            BundleValidationResult.InvalidFormat("Failed to inspect bundle: ${e.message}") to null
        }
    }

    /**
     * Imports a user-supplied stream (e.g., from Android SAF ContentResolver) into a compact
     * validated ZIP slice file without extracting loose files or keeping non-ZIP executable bytes.
     */
    fun importBundleFromStream(
        inputStream: InputStream,
        destinationZipFile: File
    ): Pair<BundleValidationResult, LoadedJetskiBundle?> {
        val tempFile = File(destinationZipFile.parentFile, "${destinationZipFile.name}.tmp")
        try {
            destinationZipFile.parentFile?.mkdirs()
            tempFile.outputStream().use { out ->
                inputStream.copyTo(out, bufferSize = 64 * 1024)
            }
            RandomAccessFile(tempFile, "r").use { raf ->
                val slice = locateEmbeddedZipSlice(raf)
                if (slice == null) {
                    tempFile.delete()
                    return BundleValidationResult.InvalidFormat(
                        "No valid ZIP archive found in imported stream"
                    ) to null
                }
                val (offset, length) = slice
                if (offset == 0L && length == tempFile.length()) {
                    if (destinationZipFile.exists()) destinationZipFile.delete()
                } else {
                    // Copy only the embedded ZIP slice (~5.6 MB) and discard surrounding binary bytes
                    raf.seek(offset)
                    destinationZipFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = raf.read(buf, 0, toRead)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                    tempFile.delete()
                }
            }
            if (tempFile.exists()) {
                tempFile.renameTo(destinationZipFile)
            }
            val result = validateAndOpen(destinationZipFile)
            if (result.first !is BundleValidationResult.Valid) {
                destinationZipFile.delete()
            }
            return result
        } catch (e: Exception) {
            tempFile.delete()
            destinationZipFile.delete()
            return BundleValidationResult.InvalidFormat("Import failed: ${e.message}") to null
        }
    }

    internal fun injectBootConfig(indexHtml: String, appConfig: JetskiAppConfig): String {
        val safeProduct = escapeJsString(appConfig.productName)
        val safeCsrf = escapeJsString(appConfig.csrfToken)
        val safeVersion = escapeJsString(appConfig.appVersion)
        val mobileCss = """
            <style id="ag-mobile-ux-styles">
              /* 1. Mobile Top Panel Removal: Remove unnecessary desktop title/menu bar */
              .agy-shell > .shrink-0:has([data-testid="title-menu-bar"]),
              [data-testid="title-menu-bar"],
              div[data-testid="title-menu-bar"] {
                display: none !important;
                height: 0 !important;
                min-height: 0 !important;
                padding: 0 !important;
                margin: 0 !important;
                overflow: hidden !important;
              }

              /* 2. Full Overlay Left Navigation Panel for Mobile with GPU Slide Animation */
              .agy-shell .flex-1 [data-testid="sidebar-split"] > .flex:first-child {
                position: absolute !important;
                top: 0 !important;
                bottom: 0 !important;
                left: 0 !important;
                right: auto !important;
                width: 100% !important;
                max-width: 100% !important;
                z-index: 50 !important;
                background: #000000 !important;
                box-shadow: 0 0 30px rgba(0,0,0,0.8) !important;
                will-change: transform !important;
                transform: translate3d(-100%, 0, 0) !important;
                pointer-events: none !important;
                transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), visibility 0s linear 240ms !important;
              }
              .agy-shell .flex-1 [data-testid="sidebar-split"] > .flex:first-child:not([style*="width: 0px"]) {
                transform: translate3d(0, 0, 0) !important;
                pointer-events: auto !important;
                visibility: visible !important;
                transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), visibility 0s linear 0s !important;
              }
              .agy-shell .flex-1 [data-testid="sidebar-split"] > .flex:first-child > div {
                width: 100vw !important;
                max-width: 100vw !important;
                right: 0 !important;
                left: auto !important;
              }
              * {
                -webkit-tap-highlight-color: transparent !important;
              }
              .agy-sheet {
                overflow-x: clip !important;
                touch-action: pan-y !important;
              }
              .agy-sheet .overflow-y-auto,
              [data-testid="conversation-view"] {
                touch-action: pan-y !important;
              }
              .agy-shell [role="navigation"] {
                user-select: none !important;
                -webkit-user-select: none !important;
              }
              .agy-shell [role="navigation"],
              .agy-shell [role="navigation"] .overflow-y-auto {
                touch-action: pan-y !important;
                overscroll-behavior-x: none !important;
              }
              .agy-shell [role="navigation"] * {
                user-select: none !important;
                -webkit-user-select: none !important;
                -webkit-touch-callout: none !important;
                -webkit-user-drag: none !important;
              }
              .agy-shell [data-sidebar-row-id],
              .agy-shell [data-project-card="true"],
              .agy-shell .bg-sidebar.pt-1 {
                touch-action: none !important;
              }
              .agy-shell [role="navigation"] a:focus,
              .agy-shell [role="navigation"] a:focus-visible,
              .agy-shell [role="navigation"] button:focus,
              .agy-shell [role="navigation"] button:focus-visible,
              .agy-shell [data-sidebar-row-id]:focus,
              .agy-shell [data-sidebar-row-id]:focus-visible,
              .agy-shell [data-project-card="true"]:focus,
              .agy-shell [data-project-card="true"]:focus-visible {
                outline: none !important;
                box-shadow: none !important;
              }
              body.agy-sidebar-animating .agy-shell,
              body.agy-sidebar-animating .agy-shell * {
                pointer-events: none !important;
              }
              button[aria-label="Toggle Side Panel"] {
                position: relative !important;
                z-index: 60 !important;
              }
              body.agy-sidebar-open button[aria-label="Toggle Side Panel"] {
                opacity: 0 !important;
                pointer-events: none !important;
              }
              [data-testid="aux-pane-split"] {
                position: relative !important;
              }
              [data-testid="aux-pane-split"] > div:last-child {
                position: absolute !important;
                top: 0 !important;
                bottom: 0 !important;
                right: 0 !important;
                left: auto !important;
                width: 100% !important;
                max-width: 100% !important;
                height: 100% !important;
                z-index: 40 !important;
                background-color: #000000 !important;
                will-change: transform !important;
                transform: translate3d(100%, 0, 0) !important;
                pointer-events: none !important;
                visibility: hidden !important;
                transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), visibility 0s linear 240ms !important;
              }
              [data-testid="aux-pane-split"]:has([data-aux-pane-open="true"]) > div:last-child,
              [data-testid="aux-pane-split"]:has([data-side-panel-open="true"]) > div:last-child {
                transform: translate3d(0, 0, 0) !important;
                pointer-events: auto !important;
                visibility: visible !important;
                transition: transform 240ms cubic-bezier(0.22, 1, 0.36, 1), visibility 0s linear 0s !important;
              }
              [data-testid="aux-pane-split"] > div:last-child > div {
                position: absolute !important;
                inset: 0 !important;
                width: 100% !important;
                max-width: 100% !important;
                height: 100% !important;
                background-color: #000000 !important;
              }
              .agy-aux-tab {
                padding-right: 6px !important;
              }
              .agy-aux-tab .agy-hover-actions {
                position: static !important;
                opacity: 1 !important;
                padding-left: 4px !important;
                padding-right: 0 !important;
                background: transparent !important;
                flex-shrink: 0 !important;
              }
              .agy-shell:has([data-aux-pane-open="true"]) [data-testid="sidebar-toggle"],
              .agy-shell:has([data-aux-pane-open="true"]) button[aria-label="Go Back"],
              .agy-shell:has([data-aux-pane-open="true"]) button[aria-label="Go Forward"],
              .agy-shell:has([data-aux-pane-open="true"]) [data-testid="sidebar-search-button"],
              .agy-shell:has([data-side-panel-open="true"]) [data-testid="sidebar-toggle"],
              .agy-shell:has([data-side-panel-open="true"]) button[aria-label="Go Back"],
              .agy-shell:has([data-side-panel-open="true"]) button[aria-label="Go Forward"],
              .agy-shell:has([data-side-panel-open="true"]) [data-testid="sidebar-search-button"] {
                opacity: 0 !important;
                pointer-events: none !important;
                width: 0 !important;
                min-width: 0 !important;
                padding: 0 !important;
                margin: 0 !important;
                overflow: hidden !important;
              }
              .agy-shell:has([data-aux-pane-open="true"]) [data-side-panel-focus-scope] > div:first-child,
              .agy-shell:has([data-side-panel-open="true"]) [data-side-panel-focus-scope] > div:first-child {
                padding-left: 46px !important;
              }

              /* 3. Mobile Settings Panel */
              [role="menu"] {
                max-width: calc(100vw - 16px) !important;
                max-height: 75vh !important;
                overflow-y: auto !important;
              }
              div[role="dialog"]:not([data-testid="bottom-sheet"]) {
                max-width: 100% !important;
                max-height: 100% !important;
                border-radius: 0 !important;
                inset: 0 !important;
              }
              /* Hide unsupported desktop shortcuts on Android */
              [data-screen="Shortcuts"],
              button:has(span:contains("Shortcuts")) {
                display: none !important;
              }

              /* 4. OLED Pure Black Backgrounds & Edge-to-Edge Status Bar */
              html, body, #root, .agy-shell {
                background-color: #000000 !important;
              }
              .agy-shell {
                padding-top: var(--ag-status-bar-top, 0px) !important;
                box-sizing: border-box !important;
              }
              .agy-shell .fixed.inset-0,
              .agy-shell .fixed.top-0 {
                top: var(--ag-status-bar-top, 0px) !important;
                height: calc(100vh - var(--ag-status-bar-top, 0px)) !important;
              }

              /* 5. Mic Recording Red Circle + 3 White Waveform Bars */
              @keyframes agyMicWaveBar {
                0%, 100% { height: 5px; opacity: 0.9; }
                50% { height: 14px; opacity: 1; }
              }
              button.agy-mic-recording {
                width: 28px !important;
                height: 28px !important;
                min-width: 28px !important;
                min-height: 28px !important;
                padding: 4px !important;
                border-radius: 9999px !important;
                background-color: #ef4444 !important;
                color: #ffffff !important;
                display: inline-flex !important;
                align-items: center !important;
                justify-content: center !important;
              }
              button.agy-mic-recording .google-symbols {
                display: none !important;
              }
              button.agy-mic-recording .agy-mic-wave {
                display: flex !important;
                align-items: center !important;
                justify-content: center !important;
                gap: 2px !important;
                width: 16px !important;
                height: 16px !important;
                pointer-events: none !important;
              }
              button.agy-mic-recording .agy-mic-wave > span {
                display: block !important;
                width: 2px !important;
                height: 6px !important;
                border-radius: 9999px !important;
                background-color: #ffffff !important;
                animation: agyMicWaveBar 0.7s ease-in-out infinite !important;
              }
              button.agy-mic-recording .agy-mic-wave > span:nth-child(2) {
                animation-delay: 0.16s !important;
              }
              button.agy-mic-recording .agy-mic-wave > span:nth-child(3) {
                animation-delay: 0.32s !important;
              }
            </style>
        """.trimIndent()
        val bootScript = buildString {
            append(mobileCss)
            append("\n<script>\n")
            append("  window.__APP_CONFIG__ = {\n")
            append("    productName: \"$safeProduct\",\n")
            append("    csrfToken: \"$safeCsrf\",\n")
            append("    appVersion: \"$safeVersion\",\n")
            append("    devMode: ${appConfig.devMode}\n")
            append("  };\n")
            append("  (function() {\n")
            append("    function sanitizeConversationUrl(rawUrl) {\n")
            append("      if (!rawUrl || typeof rawUrl !== 'string') return rawUrl;\n")
            append("      try {\n")
            append("        const u = new URL(rawUrl, window.location.origin);\n")
            append("        if (u.pathname.startsWith('/c/')) {\n")
            append("          const rest = u.pathname.slice(3);\n")
            append("          const slashIdx = rest.indexOf('/');\n")
            append("          const rawSeg = slashIdx === -1 ? rest : rest.slice(0, slashIdx);\n")
            append("          const suffix = slashIdx === -1 ? '' : rest.slice(slashIdx);\n")
            append("          let decoded = rawSeg;\n")
            append("          try { decoded = decodeURIComponent(rawSeg); } catch (e) {}\n")
            append("          if (decoded.indexOf('+') !== -1) {\n")
            append("            const parts = decoded.split('+').filter(Boolean);\n")
            append("            const focused = u.searchParams.get('focused');\n")
            append("            const singleId = (focused && parts.includes(focused)) ? focused : parts[parts.length - 1];\n")
            append("            if (singleId) {\n")
            append("              u.pathname = '/c/' + encodeURIComponent(singleId) + suffix;\n")
            append("            }\n")
            append("            u.searchParams.delete('focused');\n")
            append("            return u.pathname + u.search + u.hash;\n")
            append("          }\n")
            append("        }\n")
            append("      } catch (e) {}\n")
            append("      return rawUrl;\n")
            append("    }\n")
            append("    try {\n")
            append("      for (let i = localStorage.length - 1; i >= 0; i--) {\n")
            append("        const k = localStorage.key(i);\n")
            append("        if (k && k.indexOf('antigravity-multi-conversation-layout') === 0) {\n")
            append("          localStorage.removeItem(k);\n")
            append("        }\n")
            append("      }\n")
            append("      localStorage.removeItem('sidebar_collapsed_sections');\n")
            append("      try {\n")
            append("        const rawHeaders = localStorage.getItem('sidebar_collapsed_section_headers');\n")
            append("        if (rawHeaders) {\n")
            append("          const parsed = JSON.parse(rawHeaders);\n")
            append("          if (Array.isArray(parsed)) {\n")
            append("            const next = parsed.filter((x) => x !== 'main-section-header' && x !== 'standalone');\n")
            append("            localStorage.setItem('sidebar_collapsed_section_headers', JSON.stringify(next));\n")
            append("          }\n")
            append("        }\n")
            append("      } catch (e) {}\n")
            append("      const origSetItem = Storage.prototype.setItem;\n")
            append("      Storage.prototype.setItem = function(key, value) {\n")
            append("        if (typeof key === 'string' && key.indexOf('antigravity-multi-conversation-layout') === 0) {\n")
            append("          return;\n")
            append("        }\n")
            append("        return origSetItem.apply(this, arguments);\n")
            append("      };\n")
            append("    } catch (e) {}\n")
            append("    try {\n")
            append("      const cleanedInitial = sanitizeConversationUrl(window.location.href);\n")
            append("      if (cleanedInitial && cleanedInitial !== (window.location.pathname + window.location.search + window.location.hash)) {\n")
            append("        history.replaceState(history.state, '', cleanedInitial);\n")
            append("      }\n")
            append("      const origPush = history.pushState.bind(history);\n")
            append("      const origReplace = history.replaceState.bind(history);\n")
            append("      history.pushState = function(state, title, url) {\n")
            append("        return origPush(state, title, sanitizeConversationUrl(url));\n")
            append("      };\n")
            append("      history.replaceState = function(state, title, url) {\n")
            append("        return origReplace(state, title, sanitizeConversationUrl(url));\n")
            append("      };\n")
            append("    } catch (e) {}\n")
            append("  })();\n")
            if (appConfig.enableClientDecoratorHook) {
                append("  try {\n")
                append("    const sbDp = window.__ANTIGRAVITY_ANDROID_BRIDGE__?.getStatusBarInsetDp?.();\n")
                append("    if (typeof sbDp === 'number' && sbDp >= 0) {\n")
                append("      document.documentElement.style.setProperty('--ag-status-bar-top', sbDp + 'px');\n")
                append("    }\n")
                append("  } catch (e) {}\n")
                append("  window.__JETSKI_RPC_HISTORY__ = window.__JETSKI_RPC_HISTORY__ || [];\n")
                append("  window.__agyStreamWakeups = window.__agyStreamWakeups || new Set();\n")
                append("  window.__agyStreamEpoch = window.__agyStreamEpoch || 0;\n")
                append("  window.__agyLoadedProjectCount = window.__agyLoadedProjectCount || 0;\n")
                append("  window.__agyLoadedSummaryCount = window.__agyLoadedSummaryCount || 0;\n")
                append("  window.__agyLoadedProjectNames = window.__agyLoadedProjectNames || new Set();\n")
                append("  window.__agyForceRefreshStreams = function() {\n")
                append("    window.__agyStreamEpoch = (window.__agyStreamEpoch || 0) + 1;\n")
                append("    for (const wake of Array.from(window.__agyStreamWakeups || [])) {\n")
                append("      try { wake(); } catch (e) {}\n")
                append("    }\n")
                append("  };\n")
                append("  window.__agyRunFirstLoginSync = function(runFirst) {\n")
                append("    if (window.__agyFirstLoginSyncPending) return;\n")
                append("    window.__agyFirstLoginSyncPending = true;\n")
                append("    try { if (runFirst !== false) window.__agyForceRefreshStreams(); } catch (e) {}\n")
                append("    setTimeout(() => {\n")
                append("      window.__agyFirstLoginSyncPending = false;\n")
                append("      const loadedConvs = window.__agyLoadedSummaryCount || 0;\n")
                append("      const loadedProjs = window.__agyLoadedProjectCount || 0;\n")
                append("      if (loadedConvs === 0 && loadedProjs === 0) {\n")
                append("        try { window.__agyForceRefreshStreams(); } catch (e) {}\n")
                append("      }\n")
                append("    }, 1800);\n")
                append("  };\n")
                append("  try {\n")
                append("    window.addEventListener('antigravity:auth-changed', () => {\n")
                append("      window.__agyRunFirstLoginSync(true);\n")
                append("    });\n")
                append("  } catch (e) {}\n")
                append("  if (!globalThis.__JETSKI_LS_CLIENT_DECORATOR__) {\n")
                append("    const BG_STREAM_METHODS = {\n")
                append("      jetboxSubscribeToState: true,\n")
                append("      jetboxSubscribeToSummaries: true,\n")
                append("      projectUpdatesStream: true,\n")
                append("      subscribeToSidecars: true,\n")
                append("      subscribeToMcpServerStates: true,\n")
                append("      subscribeToPlugins: true\n")
                append("    };\n")
                append("    function sanitizeSandboxPolicyValue(val) {\n")
                append("      return (val === 4 || val === 2) ? 3 : val;\n")
                append("    }\n")
                append("    function patchCascadeSandboxConfig(req) {\n")
                append("      try {\n")
                append("        const autoCfg = req?.cascadeConfig?.plannerConfig?.toolConfig?.runCommand?.autoCommandConfig;\n")
                append("        if (autoCfg && (autoCfg.autoExecutionPolicy === 4 || autoCfg.autoExecutionPolicy === 2)) {\n")
                append("          autoCfg.autoExecutionPolicy = 3;\n")
                append("        }\n")
                append("      } catch (e) {}\n")
                append("    }\n")
                append("    function patchUserSettingsObject(us) {\n")
                append("      if (!us || typeof us !== 'object') return false;\n")
                append("      let changed = false;\n")
                append("      if (us.enableTerminalSandbox !== false) {\n")
                append("        us.enableTerminalSandbox = false;\n")
                append("        changed = true;\n")
                append("      }\n")
                append("      const nextPol = sanitizeSandboxPolicyValue(us.cascadeAutoExecutionPolicy);\n")
                append("      if (nextPol !== us.cascadeAutoExecutionPolicy) {\n")
                append("        us.cascadeAutoExecutionPolicy = nextPol;\n")
                append("        changed = true;\n")
                append("      }\n")
                append("      return changed;\n")
                append("    }\n")
                append("    globalThis.__JETSKI_LS_CLIENT_DECORATOR__ = function(client) {\n")
                append("      window.__JETSKI_DECORATOR_ACTIVE__ = true;\n")
                append("      try { window.__ANTIGRAVITY_ANDROID_BRIDGE__?.reportDecoratorReady?.(true); } catch (e) {}\n")
                append("      return new Proxy(client, {\n")
                append("        get(target, prop, receiver) {\n")
                append("          const orig = Reflect.get(target, prop, receiver);\n")
                append("          if (typeof orig === 'function') {\n")
                append("            return function(...args) {\n")
                append("              const methodName = String(prop);\n")
                append("              window.__JETSKI_RPC_HISTORY__.push({ method: methodName, ts: Date.now() });\n")
                append("              if (methodName === 'sendUserCascadeMessage' && args[0]) {\n")
                append("                patchCascadeSandboxConfig(args[0]);\n")
                append("                if (!window.__agyPatchedServerSandbox) {\n")
                append("                  window.__agyPatchedServerSandbox = true;\n")
                append("                  try { target.jetboxWriteState?.({ userConfig: { userSettings: { enableTerminalSandbox: false, cascadeAutoExecutionPolicy: 3 } } }); } catch (e) {}\n")
                append("                }\n")
                append("              } else if (methodName === 'startBattleMode' && args[0]) {\n")
                append("                patchCascadeSandboxConfig(args[0].request);\n")
                append("                if (Array.isArray(args[0].requests)) args[0].requests.forEach(patchCascadeSandboxConfig);\n")
                append("              } else if (methodName === 'jetboxWriteState' && args[0]?.userConfig?.userSettings) {\n")
                append("                patchUserSettingsObject(args[0].userConfig.userSettings);\n")
                append("              }\n")
                append("              if (methodName === 'streamAgentStateUpdates') {\n")
                append("                const opts = args[1];\n")
                append("                const signal = opts && opts.signal;\n")
                append("                const self = this;\n")
                append("                return (async function*() {\n")
                append("                  let lastSig = '';\n")
                append("                  while (!signal || !signal.aborted) {\n")
                append("                    const rawIterable = orig.apply(self, args);\n")
                append("                    for await (const msg of rawIterable) {\n")
                append("                      if (signal && signal.aborted) return;\n")
                append("                      const u = msg && msg.update;\n")
                append("                      let sig = '';\n")
                append("                      try {\n")
                append("                        sig = JSON.stringify(u || msg, (_, v) => typeof v === 'bigint' ? v.toString() : v);\n")
                append("                      } catch (err) {\n")
                append("                        const su = u && u.mainTrajectoryUpdate && u.mainTrajectoryUpdate.stepsUpdate;\n")
                append("                        sig = JSON.stringify({ s: u && u.status, i: u && u.fullyIdle, l: su ? su.totalLength : 0 });\n")
                append("                      }\n")
                append("                      if (sig !== lastSig) {\n")
                append("                        lastSig = sig;\n")
                append("                        yield msg;\n")
                append("                      }\n")
                append("                    }\n")
                append("                    if (signal && signal.aborted) return;\n")
                append("                    await new Promise(resolve => {\n")
                append("                      const timer = setTimeout(resolve, 350);\n")
                append("                      if (signal) signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });\n")
                append("                    });\n")
                append("                  }\n")
                append("                })();\n")
                append("              }\n")
                append("              if (BG_STREAM_METHODS[methodName]) {\n")
                append("                const reqArg = args[0] || {};\n")
                append("                const opts = args[1];\n")
                append("                const signal = opts && opts.signal;\n")
                append("                const self = this;\n")
                append("                const isLocalProjectStream = (methodName === 'projectUpdatesStream' && !reqArg.backend);\n")
                append("                const baseIntervalMs = (methodName === 'projectUpdatesStream' || methodName === 'jetboxSubscribeToSummaries') ? 2500 : 4000;\n")
                append("                return (async function*() {\n")
                append("                  let lastSig = '';\n")
                append("                  let seenEpoch = window.__agyStreamEpoch || 0;\n")
                append("                  let hadNonEmptyProjects = false;\n")
                append("                  let consecutiveEmptyProjects = 0;\n")
                append("                  let backoffMs = baseIntervalMs;\n")
                append("                  while (!signal || !signal.aborted) {\n")
                append("                    if ((window.__agyStreamEpoch || 0) !== seenEpoch) {\n")
                append("                      seenEpoch = window.__agyStreamEpoch || 0;\n")
                append("                      lastSig = '';\n")
                append("                    }\n")
                append("                    try {\n")
                append("                      backoffMs = baseIntervalMs;\n")
                append("                      const rawIterable = orig.apply(self, args);\n")
                append("                      for await (const msg of rawIterable) {\n")
                append("                        if (signal && signal.aborted) return;\n")
                append("                        if (methodName === 'jetboxSubscribeToState' && msg?.userConfig?.userSettings) {\n")
                append("                          const us = msg.userConfig.userSettings;\n")
                append("                          if (patchUserSettingsObject(us) && !window.__agyPatchedServerSandbox) {\n")
                append("                            window.__agyPatchedServerSandbox = true;\n")
                append("                            try { target.jetboxWriteState?.({ userConfig: { userSettings: { enableTerminalSandbox: false, cascadeAutoExecutionPolicy: us.cascadeAutoExecutionPolicy } } }); } catch (e) {}\n")
                append("                          }\n")
                append("                        }\n")
                append("                        if (isLocalProjectStream && msg?.update?.case === 'projectList') {\n")
                append("                          const ids = msg.update.value?.projectIds || [];\n")
                append("                          if (ids.length > 0) {\n")
                append("                            hadNonEmptyProjects = true;\n")
                append("                            consecutiveEmptyProjects = 0;\n")
                append("                            window.__agyLoadedProjectCount = ids.length;\n")
                append("                            ids.forEach((pId) => {\n")
                append("                              if (typeof pId === 'string') {\n")
                append("                                const seg = pId.split('/').filter(Boolean).pop();\n")
                append("                                if (seg) window.__agyLoadedProjectNames.add(seg);\n")
                append("                              }\n")
                append("                            });\n")
                append("                          } else if (hadNonEmptyProjects) {\n")
                append("                            consecutiveEmptyProjects++;\n")
                append("                            if (consecutiveEmptyProjects < 4) continue;\n")
                append("                          }\n")
                append("                        }\n")
                append("                        if (methodName === 'jetboxSubscribeToSummaries' && !reqArg.backend && msg?.summaries) {\n")
                append("                          const n = Object.keys(msg.summaries).length;\n")
                append("                          if (n > 0) window.__agyLoadedSummaryCount = n;\n")
                append("                        }\n")
                append("                        let sig = '';\n")
                append("                        try {\n")
                append("                          sig = JSON.stringify(msg, (_, v) => typeof v === 'bigint' ? v.toString() : v);\n")
                append("                        } catch (err) {\n")
                append("                          sig = String(Math.random());\n")
                append("                        }\n")
                append("                        if (sig !== lastSig) {\n")
                append("                          lastSig = sig;\n")
                append("                          yield msg;\n")
                append("                        }\n")
                append("                      }\n")
                append("                    } catch (err) {\n")
                append("                      if (signal && signal.aborted) return;\n")
                append("                      const em = String(err?.message || err || '');\n")
                append("                      if (/failed_precondition|not enabled for this user/i.test(em)) {\n")
                append("                        backoffMs = 45000;\n")
                append("                      }\n")
                append("                    }\n")
                append("                    if (signal && signal.aborted) return;\n")
                append("                    await new Promise(resolve => {\n")
                append("                      let done = false;\n")
                append("                      const finish = () => {\n")
                append("                        if (done) return; done = true;\n")
                append("                        clearTimeout(timer);\n")
                append("                        window.__agyStreamWakeups.delete(finish);\n")
                append("                        resolve();\n")
                append("                      };\n")
                append("                      const timer = setTimeout(finish, backoffMs);\n")
                append("                      window.__agyStreamWakeups.add(finish);\n")
                append("                      if (signal) signal.addEventListener('abort', finish, { once: true });\n")
                append("                    });\n")
                append("                  }\n")
                append("                })();\n")
                append("              }\n")
                append("              return orig.apply(this, args);\n")
                append("            };\n")
                append("          }\n")
                append("          return orig;\n")
                append("        }\n")
                append("      });\n")
                append("    };\n")
                append("  }\n")
                append("  (function() {\n")
                append("    const origFetch = window.fetch ? window.fetch.bind(window) : null;\n")
                append("    if (!origFetch || window.__ANTIGRAVITY_FETCH_PATCHED__) return;\n")
                append("    window.__ANTIGRAVITY_FETCH_PATCHED__ = true;\n")
                append("    function bytesToBase64(bytes) {\n")
                append("      let binary = '';\n")
                append("      const len = bytes.byteLength;\n")
                append("      for (let i = 0; i < len; i++) binary += String.fromCharCode(bytes[i]);\n")
                append("      return btoa(binary);\n")
                append("    }\n")
                append("    function base64ToBytes(b64) {\n")
                append("      const binary = atob(b64);\n")
                append("      const len = binary.length;\n")
                append("      const out = new Uint8Array(len);\n")
                append("      for (let i = 0; i < len; i++) out[i] = binary.charCodeAt(i) & 0xff;\n")
                append("      return out;\n")
                append("    }\n")
                append("    window.fetch = async function(input, init) {\n")
                append("      let isLsRpc = false;\n")
                append("      try {\n")
                append("        const url = typeof input === 'string' ? input : (input && input.url ? input.url : String(input));\n")
                append("        isLsRpc = url.indexOf('/exa.language_server_pb.LanguageServerService/') !== -1;\n")
                append("        if (isLsRpc && init && init.body) {\n")
                append("          let b64 = null;\n")
                append("          if (init.body instanceof Uint8Array) {\n")
                append("            b64 = bytesToBase64(init.body);\n")
                append("          } else if (init.body instanceof ArrayBuffer) {\n")
                append("            b64 = bytesToBase64(new Uint8Array(init.body));\n")
                append("          } else if (typeof init.body === 'string') {\n")
                append("            b64 = btoa(unescape(encodeURIComponent(init.body)));\n")
                append("          }\n")
                append("          if (b64 !== null) {\n")
                append("            const nextHeaders = new Headers(init.headers || {});\n")
                append("            nextHeaders.set('x-antigravity-post-body-b64', b64);\n")
                append("            init = Object.assign({}, init, { headers: nextHeaders });\n")
                append("          }\n")
                append("        }\n")
                append("      } catch (err) {}\n")
                append("      const res = await origFetch(input, init);\n")
                append("      try {\n")
                append("        if (isLsRpc && res && res.headers) {\n")
                append("          const respB64 = res.headers.get('x-antigravity-grpc-web-b64');\n")
                append("          if (respB64) {\n")
                append("            const exactBytes = base64ToBytes(respB64);\n")
                append("            return new Response(exactBytes, {\n")
                append("              status: res.status,\n")
                append("              statusText: res.statusText,\n")
                append("              headers: res.headers\n")
                append("            });\n")
                append("          }\n")
                append("        }\n")
                append("      } catch (err) {}\n")
                append("      return res;\n")
                append("    };\n")
                append("  })();\n")
                append("  if (typeof window !== 'undefined' && window.__ANTIGRAVITY_ANDROID_BRIDGE__) {\n")
                append("    const bridge = window.__ANTIGRAVITY_ANDROID_BRIDGE__;\n")
                append("    if (!window.nativeStorage) {\n")
                append("      const storageListeners = new Set();\n")
                append("      window.nativeStorage = {\n")
                append("        getItems: function() {\n")
                append("          return new Promise((resolve) => {\n")
                append("            try {\n")
                append("              const raw = bridge.nativeStorageGetItems();\n")
                append("              resolve(raw ? JSON.parse(raw) : {});\n")
                append("            } catch (e) { resolve({}); }\n")
                append("          });\n")
                append("        },\n")
                append("        updateItems: function(changes) {\n")
                append("          return new Promise((resolve) => {\n")
                append("            try {\n")
                append("              bridge.nativeStorageUpdateItems(JSON.stringify(changes));\n")
                append("              storageListeners.forEach((cb) => { try { cb(changes); } catch (err) {} });\n")
                append("            } catch (e) {}\n")
                append("            resolve();\n")
                append("          });\n")
                append("        },\n")
                append("        onChanged: function(callback) {\n")
                append("          storageListeners.add(callback);\n")
                append("          return () => { storageListeners.delete(callback); };\n")
                append("        }\n")
                append("      };\n")
                append("    }\n")
                append("    if (!window.nativeNotifications) {\n")
                append("      const notifListeners = new Set();\n")
                append("      window.nativeNotifications = {\n")
                append("        send: function(options) {\n")
                append("          return new Promise((resolve) => {\n")
                append("            try {\n")
                append("              bridge.sendNotification(\n")
                append("                options.title || 'Antigravity',\n")
                append("                options.body || '',\n")
                append("                Boolean(options.silent),\n")
                append("                JSON.stringify(options.payload || {})\n")
                append("              );\n")
                append("            } catch (e) {}\n")
                append("            resolve();\n")
                append("          });\n")
                append("        },\n")
                append("        openSystemPreferences: function() {\n")
                append("          return new Promise((resolve) => {\n")
                append("            try { bridge.openNotificationSettings(); } catch (e) {}\n")
                append("            resolve();\n")
                append("          });\n")
                append("        },\n")
                append("        onClicked: function(callback) {\n")
                append("          notifListeners.add(callback);\n")
                append("          return () => { notifListeners.delete(callback); };\n")
                append("        }\n")
                append("      };\n")
                append("    }\n")
                append("    if (!window.electronNative) {\n")
                append("      window.electronNative = {\n")
                append("        getZoomLevel: () => 1.0,\n")
                append("        getPathForFile: (file) => (file && (file.path || file.name)) || '',\n")
                append("        setTitleBarOverlay: () => Promise.resolve(),\n")
                append("        minimize: () => Promise.resolve(),\n")
                append("        maximize: () => Promise.resolve(),\n")
                append("        unmaximize: () => Promise.resolve(),\n")
                append("        isMaximized: () => Promise.resolve(false),\n")
                append("        close: () => Promise.resolve(),\n")
                append("        toggleDevTools: () => Promise.resolve(),\n")
                append("        zoomIn: () => {},\n")
                append("        zoomOut: () => {},\n")
                append("        resetZoom: () => {},\n")
                append("        openExternal: (url) => { try { bridge.openExternal(url); } catch (e) {} return Promise.resolve(); },\n")
                append("        revealInFilePicker: (path) => { try { bridge.revealInFilePicker(path); } catch (e) {} return Promise.resolve(); },\n")
                append("        getWebviewMemoryUsage: () => Promise.resolve({}),\n")
                append("        showContextMenu: () => Promise.resolve(null),\n")
                append("        closeContextMenu: () => Promise.resolve(),\n")
                append("        executeJavaScriptInIsolatedWorld: () => Promise.resolve(),\n")
                append("        runScriptInJail: () => Promise.resolve({}),\n")
                append("        abortScriptJail: () => Promise.resolve(),\n")
                append("        subscribeWebviewSecurityState: () => () => {},\n")
                append("        onWebviewOpenNewTab: () => () => {},\n")
                append("        setWebviewDownloadDirectory: () => Promise.resolve(),\n")
                append("        onWebviewFaviconUpdated: () => () => {},\n")
                append("        onWebviewDownloadProgress: () => () => {},\n")
                append("        setWebviewDialogPolicy: () => Promise.resolve(),\n")
                append("        handleWebviewDialog: () => Promise.resolve(),\n")
                append("        onWebviewDialogOpening: () => () => {},\n")
                append("        wcvBrowser: {\n")
                append("          acquire: (req) => Promise.resolve({ tabKey: (req && req.tabKey) || 'tab-1' }),\n")
                append("          show: () => Promise.resolve(),\n")
                append("          hide: () => Promise.resolve(),\n")
                append("          setBounds: () => Promise.resolve(),\n")
                append("          setThemeColor: () => Promise.resolve(true),\n")
                append("          setPinned: () => Promise.resolve(),\n")
                append("          setAgentControlling: () => Promise.resolve(),\n")
                append("          setOverlayActive: () => Promise.resolve(),\n")
                append("          setPortalOverlayOpen: () => Promise.resolve(),\n")
                append("          elevate: () => Promise.resolve(),\n")
                append("          setDragOver: () => Promise.resolve(),\n")
                append("          demote: () => Promise.resolve(),\n")
                append("          navigate: (tabKey, url) => { try { bridge.navigateBrowser(tabKey, url); } catch (e) {} return Promise.resolve(); },\n")
                append("          control: () => Promise.resolve(),\n")
                append("          sendMouse: () => Promise.resolve(),\n")
                append("          sendKey: () => Promise.resolve(),\n")
                append("          insertText: () => Promise.resolve(),\n")
                append("          capturePage: () => Promise.resolve(''),\n")
                append("          dispose: () => Promise.resolve(),\n")
                append("          onGuestEvent: () => () => {},\n")
                append("          onZOrderChanged: () => () => {},\n")
                append("          onShortcutForwarded: () => () => {},\n")
                append("          onCursorChanged: () => () => {}\n")
                append("        }\n")
                append("      };\n")
                append("    }\n")
                append("    if (!window.deepLink) {\n")
                append("      const deepLinkListeners = new Set();\n")
                append("      window.__dispatchDeepLink = function(url) {\n")
                append("        if (!url) return;\n")
                append("        window.__agyPendingAuthReturn = 0;\n")
                append("        deepLinkListeners.forEach((cb) => { try { cb(url); } catch (e) {} });\n")
                append("        try { window.dispatchEvent(new Event('focus')); } catch (e) {}\n")
                append("      };\n")
                append("      window.deepLink = {\n")
                append("        onDeepLink: (cb) => { deepLinkListeners.add(cb); return () => { deepLinkListeners.delete(cb); }; },\n")
                append("        getStoredDeepLink: () => Promise.resolve(bridge.getStoredDeepLink() || null)\n")
                append("      };\n")
                append("    }\n")
                append("    if (!window.agent) {\n")
                append("      window.agent = {\n")
                append("        updateActiveAgentCount: (count) => { try { bridge.updateActiveAgentCount(count); } catch (e) {} return Promise.resolve(); }\n")
                append("      };\n")
                append("    }\n")
                append("    if (!window.ide) {\n")
                append("      window.ide = { isInstalled: () => Promise.resolve(true) };\n")
                append("    }\n")
                append("    if (!window.logs) {\n")
                append("      window.logs = { getElectronLogs: () => Promise.resolve(bridge.getLogs()) };\n")
                append("    }\n")
                append("    if (!window.dialog) {\n")
                append("      window.dialog = {\n")
                append("        showOpenMultipleFolderDialog: function() {\n")
                append("          return window.dialog.showOpenDialog().then(p => p ? [p] : []);\n")
                append("        },\n")
                append("        showOpenDialog: function() {\n")
                append("        return new Promise((resolve) => {\n")
                append("          const bridge = window.__ANTIGRAVITY_ANDROID_BRIDGE__;\n")
                append("          let existing = [];\n")
                append("          try { existing = JSON.parse(bridge.listWorkspaceFolders() || '[]'); } catch (e) {}\n")
                append("          const knownNames = window.__agyLoadedProjectNames;\n")
                append("          if (knownNames && knownNames.size > 0) {\n")
                append("            existing = existing.filter((f) => f && f.name && knownNames.has(f.name));\n")
                append("          } else {\n")
                append("            existing = [];\n")
                append("          }\n")
                append("          const hasAllFiles = bridge.hasAllFilesAccess ? !!bridge.hasAllFilesAccess() : true;\n")
                append("          const overlay = document.createElement('div');\n")
                append("          overlay.id = 'ag-android-project-dialog';\n")
                append("          overlay.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,0.72);display:flex;align-items:center;justify-content:center;padding:16px;font-family:sans-serif;';\n")
                append("          const card = document.createElement('div');\n")
                append("          card.style.cssText = 'width:100%;max-width:380px;background:#1a1b1e;color:#f3f4f6;border:1px solid #2e3038;border-radius:14px;padding:18px;box-shadow:0 16px 40px rgba(0,0,0,0.55);';\n")
                append("          const title = document.createElement('div');\n")
                append("          title.textContent = 'Create or Open Project';\n")
                append("          title.style.cssText = 'font-size:16px;font-weight:600;margin-bottom:6px;';\n")
                append("          const sub = document.createElement('div');\n")
                append("          sub.textContent = hasAllFiles ? 'Creates a project folder directly in your phone main storage directory.' : 'Grant All Files Access so folders appear in your phone main directory.';\n")
                append("          sub.style.cssText = 'font-size:12px;color:#9ca3af;margin-bottom:14px;line-height:1.4;';\n")
                append("          const input = document.createElement('input');\n")
                append("          input.type = 'text';\n")
                append("          input.placeholder = 'Project name (e.g. my-app)';\n")
                append("          input.style.cssText = 'width:100%;box-sizing:border-box;padding:10px 12px;border-radius:8px;border:1px solid #3a3d46;background:#111215;color:#fff;font-size:14px;outline:none;margin-bottom:12px;';\n")
                append("          const cleanup = (val) => { try { overlay.remove(); } catch (e) {} resolve(val); };\n")
                append("          const submitName = () => {\n")
                append("            const name = (input.value || '').trim() || 'my-project';\n")
                append("            try {\n")
                append("              const createdPath = bridge.createWorkspaceFolder(name);\n")
                append("              cleanup(createdPath || undefined);\n")
                append("            } catch (e) { cleanup(undefined); }\n")
                append("          };\n")
                append("          input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') { ev.preventDefault(); submitName(); } });\n")
                append("          card.appendChild(title); card.appendChild(sub);\n")
                append("          if (!hasAllFiles && bridge.requestAllFilesAccess) {\n")
                append("            const permBtn = document.createElement('button');\n")
                append("            permBtn.type = 'button'; permBtn.textContent = 'Allow All Files Access';\n")
                append("            permBtn.style.cssText = 'width:100%;margin-bottom:10px;padding:8px 12px;border-radius:8px;border:1px solid #3b82f6;background:rgba(59,130,246,0.14);color:#93c5fd;font-size:12px;font-weight:600;cursor:pointer;';\n")
                append("            permBtn.onclick = () => { bridge.requestAllFilesAccess(); cleanup(undefined); };\n")
                append("            card.appendChild(permBtn);\n")
                append("          }\n")
                append("          card.appendChild(input);\n")
                append("          if (existing.length > 0) {\n")
                append("            const listLabel = document.createElement('div');\n")
                append("            listLabel.textContent = 'Open Existing Project';\n")
                append("            listLabel.style.cssText = 'font-size:11px;color:#9ca3af;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:6px;';\n")
                append("            const listWrap = document.createElement('div');\n")
                append("            listWrap.style.cssText = 'max-height:150px;overflow-y:auto;display:flex;flex-direction:column;gap:6px;margin-bottom:14px;';\n")
                append("            existing.forEach((folder) => {\n")
                append("              const btn = document.createElement('button');\n")
                append("              btn.type = 'button';\n")
                append("              btn.textContent = folder.name;\n")
                append("              btn.style.cssText = 'text-align:left;padding:8px 10px;border-radius:8px;border:1px solid #2e3038;background:#23252b;color:#e5e7eb;font-size:13px;cursor:pointer;';\n")
                append("              btn.onclick = () => cleanup(folder.path);\n")
                append("              listWrap.appendChild(btn);\n")
                append("            });\n")
                append("            card.appendChild(listLabel); card.appendChild(listWrap);\n")
                append("          }\n")
                append("          const row = document.createElement('div');\n")
                append("          row.style.cssText = 'display:flex;justify-content:flex-end;gap:8px;margin-top:4px;';\n")
                append("          const cancelBtn = document.createElement('button');\n")
                append("          cancelBtn.type = 'button'; cancelBtn.textContent = 'Cancel';\n")
                append("          cancelBtn.style.cssText = 'padding:8px 14px;border-radius:8px;border:1px solid #3a3d46;background:transparent;color:#d1d5db;font-size:13px;cursor:pointer;';\n")
                append("          cancelBtn.onclick = () => cleanup(undefined);\n")
                append("          const okBtn = document.createElement('button');\n")
                append("          okBtn.type = 'button'; okBtn.textContent = 'Create & Open';\n")
                append("          okBtn.style.cssText = 'padding:8px 14px;border-radius:8px;border:none;background:#3b82f6;color:#fff;font-size:13px;font-weight:600;cursor:pointer;';\n")
                append("          okBtn.onclick = submitName;\n")
                append("          row.appendChild(cancelBtn); row.appendChild(okBtn);\n")
                append("          card.appendChild(row);\n")
                append("          overlay.appendChild(card);\n")
                append("          overlay.addEventListener('click', (ev) => { if (ev.target === overlay) cleanup(undefined); });\n")
                append("          document.body.appendChild(overlay);\n")
                append("          setTimeout(() => { try { input.focus(); } catch (e) {} }, 50);\n")
                append("        });\n")
                append("      }\n")
                append("    };\n")
                append("    }\n")
                append("    let lastHandledPicker = null;\n")
                append("    const attachAndroidProjectHook = () => {\n")
                append("      const pickerInput = document.querySelector('input[placeholder*=\"Type absolute path\"]');\n")
                append("      if (!pickerInput || pickerInput === lastHandledPicker || document.getElementById('ag-android-project-dialog')) return;\n")
                append("      lastHandledPicker = pickerInput;\n")
                append("      window.dialog.showOpenDialog().then((selectedPath) => {\n")
                append("        if (selectedPath) {\n")
                append("          const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;\n")
                append("          if (setter) setter.call(pickerInput, selectedPath);\n")
                append("          else pickerInput.value = selectedPath;\n")
                append("          pickerInput.dispatchEvent(new Event('input', { bubbles: true }));\n")
                append("          setTimeout(() => {\n")
                append("            const okBtn = [...document.querySelectorAll('button')].find(b => b.textContent && b.textContent.trim() === 'OK' && !b.disabled);\n")
                append("            if (okBtn) okBtn.click();\n")
                append("          }, 120);\n")
                append("        } else {\n")
                append("          pickerInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));\n")
                append("        }\n")
                append("      });\n")
                append("    };\n")
                append("    if (typeof MutationObserver !== 'undefined') {\n")
                append("      const obs = new MutationObserver(() => attachAndroidProjectHook());\n")
                append("      if (document.body) obs.observe(document.body, { childList: true, subtree: true });\n")
                append("      else document.addEventListener('DOMContentLoaded', () => { if (document.body) obs.observe(document.body, { childList: true, subtree: true }); });\n")
                append("    }\n")
                append("    (function() {\n")
                append("      let touchStartX = 0;\n")
                append("      let touchStartY = 0;\n")
                append("      let isTouchTrackingSidebar = false;\n")
                append("      let isSidebarAnimating = false;\n")
                append("      let suppressNextClick = false;\n")
                append("      let isDraggingDrawer = false;\n")
                append("      let drawerDragDir = 0;\n")
                append("      let drawerSavedVisibility = '';\n")
                append("      document.addEventListener('dragstart', (e) => {\n")
                append("        if (e.isTrusted && e.target && e.target.closest && e.target.closest('[role=\"navigation\"]')) {\n")
                append("          e.preventDefault();\n")
                append("        }\n")
                append("      }, true);\n")
                append("      function toggleSidebarSafely() {\n")
                append("        const toggleBtn = document.querySelector('[aria-label=\"Toggle Sidebar\"]');\n")
                append("        if (!toggleBtn || isSidebarAnimating) return;\n")
                append("        toggleBtn.click();\n")
                append("        isSidebarAnimating = true;\n")
                append("        document.body.classList.add('agy-sidebar-animating');\n")
                append("        setTimeout(() => {\n")
                append("          isSidebarAnimating = false;\n")
                append("          document.body.classList.remove('agy-sidebar-animating');\n")
                append("          syncMobileUiState();\n")
                append("        }, 260);\n")
                append("      }\n")
                append("      function isSbOpen(sb) {\n")
                append("        return !!(sb && sb.style.width !== '0px' && !(sb.getAttribute('style') || '').includes('width: 0px'));\n")
                append("      }\n")
                append("      function clearDrawerInlineStyles(sb) {\n")
                append("        if (!sb) return;\n")
                append("        sb.style.removeProperty('transition');\n")
                append("        sb.style.removeProperty('transform');\n")
                append("        if (drawerDragDir === 1) {\n")
                append("          if (drawerSavedVisibility) sb.style.visibility = drawerSavedVisibility;\n")
                append("          else sb.style.removeProperty('visibility');\n")
                append("        }\n")
                append("      }\n")
                append("      let isDrawerTouchActive = false;\n")
                append("      document.addEventListener('touchstart', (e) => {\n")
                append("        if (e.touches.length !== 1) return;\n")
                append("        const t = e.touches[0];\n")
                append("        touchStartX = t.clientX;\n")
                append("        touchStartY = t.clientY;\n")
                append("        const sidebar = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("        if (isDraggingDrawer) { clearDrawerInlineStyles(sidebar); }\n")
                append("        isDraggingDrawer = false;\n")
                append("        drawerDragDir = 0;\n")
                append("        isDrawerTouchActive = true;\n")
                append("        isTouchTrackingSidebar = !!(sidebar && sidebar.contains(e.target) && isSbOpen(sidebar));\n")
                append("      }, { capture: true, passive: true });\n")
                append("      document.addEventListener('touchmove', (e) => {\n")
                append("        if (!isDrawerTouchActive || e.touches.length !== 1) return;\n")
                append("        if (isSidebarAnimating || isPointerDragging) return;\n")
                append("        const sb = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("        if (!sb) return;\n")
                append("        const dialog = document.querySelector('div[role=\"dialog\"]');\n")
                append("        const auxOpen = !!document.querySelector('[data-aux-pane-open=\"true\"], [data-side-panel-open=\"true\"]');\n")
                append("        if (dialog || auxOpen) return;\n")
                append("        const t = e.touches[0];\n")
                append("        const dxRight = t.clientX - touchStartX;\n")
                append("        const dxLeft = touchStartX - t.clientX;\n")
                append("        const dy = Math.abs(touchStartY - t.clientY);\n")
                append("        if (!isDraggingDrawer) {\n")
                append("          if (isTouchTrackingSidebar && dxLeft > 10 && dy < dxLeft * 1.15) {\n")
                append("            isDraggingDrawer = true;\n")
                append("            drawerDragDir = -1;\n")
                append("          } else if (!isTouchTrackingSidebar && dxRight > 10 && dy < dxRight * 1.15) {\n")
                append("            isDraggingDrawer = true;\n")
                append("            drawerDragDir = 1;\n")
                append("            drawerSavedVisibility = sb.style.visibility;\n")
                append("            sb.style.setProperty('visibility', 'visible', 'important');\n")
                append("          }\n")
                append("        }\n")
                append("        if (isDraggingDrawer) {\n")
                append("          if (e.cancelable) e.preventDefault();\n")
                append("          e.stopPropagation();\n")
                append("          const w = window.innerWidth || 411;\n")
                append("          sb.style.setProperty('transition', 'none', 'important');\n")
                append("          if (drawerDragDir === 1) {\n")
                append("            const pct = Math.min(0, -100 + (Math.max(0, dxRight) / w) * 100);\n")
                append("            sb.style.setProperty('transform', 'translate3d(' + pct + '%, 0, 0)', 'important');\n")
                append("          } else {\n")
                append("            const pct = Math.max(-100, -(Math.max(0, dxLeft) / w) * 100);\n")
                append("            sb.style.setProperty('transform', 'translate3d(' + pct + '%, 0, 0)', 'important');\n")
                append("          }\n")
                append("        }\n")
                append("      }, { capture: true, passive: false });\n")
                append("      const finishDrawerTouch = (e) => {\n")
                append("        if (!isDrawerTouchActive) return;\n")
                append("        isDrawerTouchActive = false;\n")
                append("        const t = (e.changedTouches && e.changedTouches[0]) || { clientX: touchStartX, clientY: touchStartY };\n")
                append("        const dxLeft = touchStartX - t.clientX;\n")
                append("        const dxRight = t.clientX - touchStartX;\n")
                append("        const dy = Math.abs(touchStartY - t.clientY);\n")
                append("        const sb = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("        if (isDraggingDrawer) {\n")
                append("          e.stopPropagation();\n")
                append("          clearDrawerInlineStyles(sb);\n")
                append("          const w = window.innerWidth || 411;\n")
                append("          const threshold = Math.min(60, w * 0.16);\n")
                append("          const shouldToggle = (drawerDragDir === 1 && dxRight > threshold) || (drawerDragDir === -1 && dxLeft > threshold);\n")
                append("          isDraggingDrawer = false;\n")
                append("          drawerDragDir = 0;\n")
                append("          if (shouldToggle) toggleSidebarSafely();\n")
                append("          return;\n")
                append("        }\n")
                append("        const dialog = document.querySelector('div[role=\"dialog\"]:not([data-testid=\"bottom-sheet\"])');\n")
                append("        if (dialog && dialog.contains(e.target) && dxLeft > 55 && dy < 90) {\n")
                append("          const closeBtn = dialog.querySelector('button[aria-label=\"Close\"], button:has(svg)');\n")
                append("          if (closeBtn) closeBtn.click();\n")
                append("        }\n")
                append("      };\n")
                append("      document.addEventListener('touchend', finishDrawerTouch, { capture: true, passive: true });\n")
                append("      document.addEventListener('touchcancel', finishDrawerTouch, { capture: true, passive: true });\n")
                append("      function checkPendingAuthReturn() {\n")
                append("        if (!window.__agyPendingAuthReturn || (Date.now() - window.__agyPendingAuthReturn) >= 300000) return;\n")
                append("        if (!location.pathname.includes('/customizations')) return;\n")
                append("        const localCard = document.querySelector('[data-testid=\"plugin-connection-local\"]');\n")
                append("        const cloudCard = document.querySelector('[data-testid=\"plugin-connection-cloud\"]');\n")
                append("        const scope = localCard || cloudCard || document.querySelector('.agy-sheet') || document.querySelector('main') || document.body;\n")
                append("        const txt = (scope && scope.innerText) || '';\n")
                append("        const hasDisconnectMenu = !!(scope && scope.querySelector('button[aria-label^=\"More for \"], button[aria-label^=\"Disconnect\"]'));\n")
                append("        const hasConnectedText = !/Finish signing in|Not signed in|Not connected/i.test(txt) && /Account connected|Signed in|Disconnect/i.test(txt);\n")
                append("        if (hasDisconnectMenu || hasConnectedText) {\n")
                append("          window.__agyPendingAuthReturn = 0;\n")
                append("          try { window.__ANTIGRAVITY_ANDROID_BRIDGE__?.bringToFront?.(); } catch (e) {}\n")
                append("        }\n")
                append("      }\n")
                append("      setInterval(() => {\n")
                append("        if (window.__agyPendingAuthReturn && (Date.now() - window.__agyPendingAuthReturn) < 300000) {\n")
                append("          checkPendingAuthReturn();\n")
                append("        }\n")
                append("      }, 800);\n")
                append("      function syncMobileUiState() {\n")
                append("        checkPendingAuthReturn();\n")
                append("        const sheet = document.querySelector('.agy-sheet');\n")
                append("        if (sheet && sheet.scrollLeft !== 0) sheet.scrollLeft = 0;\n")
                append("        const sb = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("        const isOpen = isSbOpen(sb);\n")
                append("        document.body.classList.toggle('agy-sidebar-open', isOpen);\n")
                append("        const header = document.querySelector('[data-sidebar-section-drop-id=\"main-section-header\"]');\n")
                append("        if (header && !document.getElementById('agy-projects-local-note')) {\n")
                append("          const note = document.createElement('div');\n")
                append("          note.id = 'agy-projects-local-note';\n")
                append("          note.className = 'px-2 pt-0.5 pb-1 text-[11px] text-muted-foreground opacity-60 leading-tight';\n")
                append("          note.textContent = 'Note: Folder projects remain local to this device and are not synced across devices.';\n")
                append("          header.appendChild(note);\n")
                append("        }\n")
                append("      }\n")
                append("      const uiObs = new MutationObserver(() => syncMobileUiState());\n")
                append("      if (document.body) uiObs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });\n")
                append("      else document.addEventListener('DOMContentLoaded', () => uiObs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] }));\n")
                append("      document.addEventListener('click', (e) => {\n")
                append("        if (suppressNextClick || isSidebarAnimating) {\n")
                append("          e.preventDefault();\n")
                append("          e.stopPropagation();\n")
                append("          return;\n")
                append("        }\n")
                append("        setTimeout(syncMobileUiState, 50);\n")
                append("        const micBtn = e.target.closest('button');\n")
                append("        if (micBtn && window.__ANTIGRAVITY_ANDROID_BRIDGE__?.startSpeechToText) {\n")
                append("          const aria = (micBtn.getAttribute('aria-label') || '').toLowerCase();\n")
                append("          const iconEl = micBtn.querySelector('.google-symbols');\n")
                append("          const iconTxt = iconEl ? iconEl.textContent.trim() : '';\n")
                append("          const isActiveMic = micBtn.classList.contains('agy-mic-recording') || !!micBtn.querySelector('.agy-mic-wave');\n")
                append("          if (isActiveMic || aria.includes('record audio') || aria.includes('voice') || iconTxt === 'mic' || iconTxt === 'mic_off') {\n")
                append("            e.preventDefault();\n")
                append("            e.stopPropagation();\n")
                append("            try {\n")
                append("              if (window.__agyVoiceRecording) {\n")
                append("                window.__agyResetMicVisual?.();\n")
                append("                window.__ANTIGRAVITY_ANDROID_BRIDGE__.stopSpeechToText();\n")
                append("              } else {\n")
                append("                window.__agySetMicVisual?.(micBtn, true);\n")
                append("                window.__ANTIGRAVITY_ANDROID_BRIDGE__.startSpeechToText();\n")
                append("              }\n")
                append("            } catch (err) { window.__agyResetMicVisual?.(); }\n")
                append("            return;\n")
                append("          }\n")
                append("        }\n")
                append("        const sidebar = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("        if (sidebar && sidebar.contains(e.target) && isSbOpen(sidebar)) {\n")
                append("          const btn = e.target.closest('button');\n")
                append("          const btnLabel = btn && btn.textContent ? btn.textContent.trim() : '';\n")
                append("          const isNavBtn = btnLabel === 'Automations' || btnLabel === 'Customizations' || btnLabel === 'New Conversation' || btnLabel === 'Conversation History';\n")
                append("          const navLink = e.target.closest('[data-testid=\"new-conversation-button\"], [data-testid=\"app-icon-new-conversation-button\"], a[href^=\"/c/\"], a[href^=\"/?\"], a[href=\"/\"], a[href^=\"/history\"], a[href^=\"/customizations\"], a[href^=\"/automations\"], div[role=\"button\"]:has(span)');\n")
                append("          if (navLink || isNavBtn) {\n")
                append("            if (document.activeElement && typeof document.activeElement.blur === 'function') {\n")
                append("              document.activeElement.blur();\n")
                append("            }\n")
                append("            setTimeout(() => {\n")
                append("              const sb = document.querySelector('.agy-shell .flex-1 [data-testid=sidebar-split] > .flex:first-child');\n")
                append("              if (isSbOpen(sb)) {\n")
                append("                const toggleBtn = document.querySelector('[aria-label=\"Toggle Sidebar\"]');\n")
                append("                if (toggleBtn) toggleBtn.click();\n")
                append("              }\n")
                append("              if (document.activeElement && typeof document.activeElement.blur === 'function') {\n")
                append("                document.activeElement.blur();\n")
                append("              }\n")
                append("            }, 80);\n")
                append("          }\n")
                append("        }\n")
                append("      }, true);\n")
                append("      const origWindowOpen = window.open;\n")
                append("      window.open = function(url) {\n")
                append("        if (typeof url === 'string' && /^https?:\\/\\//i.test(url) && !url.startsWith(location.origin)) {\n")
                append("          try {\n")
                append("            if (location.pathname.includes('/customizations') || /accounts\\.google\\.com|oauth|authorize|login|signin|consent|auth/i.test(url)) {\n")
                append("              window.__agyPendingAuthReturn = Date.now();\n")
                append("            }\n")
                append("            const b = window.__ANTIGRAVITY_ANDROID_BRIDGE__;\n")
                append("            if (b && b.openExternal) { b.openExternal(url); return null; }\n")
                append("          } catch (err) {}\n")
                append("        }\n")
                append("        return origWindowOpen ? origWindowOpen.apply(window, arguments) : null;\n")
                append("      };\n")
                append("      window.__agySetMicVisual = function(btn, active) {\n")
                append("        window.__agyVoiceRecording = !!active;\n")
                append("        try {\n")
                append("          const targetBtn = btn || document.querySelector('button.agy-mic-recording');\n")
                append("          if (targetBtn) {\n")
                append("            if (active) {\n")
                append("              targetBtn.classList.add('agy-mic-recording');\n")
                append("              targetBtn.querySelectorAll('.google-symbols, svg').forEach((el) => el.style.setProperty('display', 'none', 'important'));\n")
                append("              if (!targetBtn.querySelector('.agy-mic-wave')) {\n")
                append("                const w = document.createElement('span');\n")
                append("                w.className = 'agy-mic-wave';\n")
                append("                w.innerHTML = '<span></span><span></span><span></span>';\n")
                append("                targetBtn.appendChild(w);\n")
                append("              }\n")
                append("            } else {\n")
                append("              targetBtn.classList.remove('agy-mic-recording');\n")
                append("              targetBtn.querySelectorAll('.google-symbols, svg').forEach((el) => el.style.removeProperty('display'));\n")
                append("              targetBtn.querySelectorAll('.agy-mic-wave').forEach((n) => n.remove());\n")
                append("            }\n")
                append("          }\n")
                append("        } catch (err) {}\n")
                append("      };\n")
                append("      window.__agyResetMicVisual = function() {\n")
                append("        window.__agyVoiceRecording = false;\n")
                append("        try {\n")
                append("          document.querySelectorAll('button.agy-mic-recording').forEach((b) => {\n")
                append("            b.classList.remove('agy-mic-recording');\n")
                append("            b.querySelectorAll('.google-symbols, svg').forEach((el) => el.style.removeProperty('display'));\n")
                append("            b.querySelectorAll('.agy-mic-wave').forEach((n) => n.remove());\n")
                append("          });\n")
                append("        } catch (err) {}\n")
                append("      };\n")
                append("      window.__insertRecognizedSpeech = function(text) {\n")
                append("        window.__agyResetMicVisual();\n")
                append("        if (!text) return;\n")
                append("        const value = text.trim() + ' ';\n")
                append("        const editor = document.querySelector('[contenteditable=\"true\"]');\n")
                append("        if (editor) {\n")
                append("          editor.focus();\n")
                append("          try {\n")
                append("            const sel = window.getSelection();\n")
                append("            if (sel && (!sel.rangeCount || !editor.contains(sel.anchorNode))) {\n")
                append("              const range = document.createRange();\n")
                append("              range.selectNodeContents(editor);\n")
                append("              range.collapse(false);\n")
                append("              sel.removeAllRanges();\n")
                append("              sel.addRange(range);\n")
                append("            }\n")
                append("          } catch (err) {}\n")
                append("          const beforeLen = (editor.textContent || '').length;\n")
                append("          let ok = false;\n")
                append("          try { ok = document.execCommand('insertText', false, value); } catch (err) {}\n")
                append("          if (!ok || (editor.textContent || '').length === beforeLen) {\n")
                append("            try {\n")
                append("              editor.dispatchEvent(new InputEvent('beforeinput', { inputType: 'insertText', data: value, bubbles: true, cancelable: true }));\n")
                append("            } catch (err) {}\n")
                append("          }\n")
                append("        }\n")
                append("      };\n")
                append("      let dragTimer = null;\n")
                append("      let dragTarget = null;\n")
                append("      let dragScrollContainer = null;\n")
                append("      let isPointerDragging = false;\n")
                append("      let dragStartX = 0;\n")
                append("      let dragStartY = 0;\n")
                append("      function findPointerDragTarget(el) {\n")
                append("        if (!el || !el.closest) return null;\n")
                append("        if (el.closest('[data-no-drag], button[aria-label*=\"options\" i], button[data-testid*=\"kebab\" i]')) return null;\n")
                append("        const row = el.closest('[data-sidebar-row-id]');\n")
                append("        if (row) return row;\n")
                append("        const proj = el.closest('[data-project-card=\"true\"], button[data-testid=\"project-header\"]');\n")
                append("        if (proj) return proj;\n")
                append("        return el.closest('[draggable=\"true\"]');\n")
                append("      }\n")
                append("      function emitPointerAndMouse(target, pType, mType, x, y) {\n")
                append("        try {\n")
                append("          target.dispatchEvent(new PointerEvent(pType, {\n")
                append("            pointerId: 1, pointerType: 'mouse', isPrimary: true,\n")
                append("            button: 0, buttons: pType === 'pointerup' ? 0 : 1,\n")
                append("            clientX: x, clientY: y, bubbles: true, cancelable: true\n")
                append("          }));\n")
                append("          target.dispatchEvent(new MouseEvent(mType, {\n")
                append("            button: 0, buttons: mType === 'mouseup' ? 0 : 1,\n")
                append("            clientX: x, clientY: y, bubbles: true, cancelable: true\n")
                append("          }));\n")
                append("        } catch (err) {}\n")
                append("      }\n")
                append("      let lastTouchX = 0;\n")
                append("      let lastTouchY = 0;\n")
                append("      let isScrollFallback = false;\n")
                append("      document.addEventListener('selectstart', (e) => {\n")
                append("        if (e.target && e.target.closest && e.target.closest('[role=\"navigation\"]')) {\n")
                append("          e.preventDefault();\n")
                append("        }\n")
                append("      }, true);\n")
                append("      const cancelPendingItemDrag = () => {\n")
                append("        if (dragTimer) { clearTimeout(dragTimer); dragTimer = null; }\n")
                append("        if (!isPointerDragging) { dragTarget = null; }\n")
                append("      };\n")
                append("      document.addEventListener('scroll', (e) => {\n")
                append("        if (dragTarget && !isPointerDragging && e.target && e.target.closest && e.target.closest('[role=\"navigation\"]')) {\n")
                append("          cancelPendingItemDrag();\n")
                append("        }\n")
                append("      }, { capture: true, passive: true });\n")
                append("      document.addEventListener('pointermove', (e) => {\n")
                append("        if (e.pointerType !== 'touch' || !dragTarget || isPointerDragging) return;\n")
                append("        if (Math.hypot(e.clientX - dragStartX, e.clientY - dragStartY) > 8) {\n")
                append("          cancelPendingItemDrag();\n")
                append("          isScrollFallback = true;\n")
                append("          lastTouchX = e.clientX;\n")
                append("          lastTouchY = e.clientY;\n")
                append("        }\n")
                append("      }, { capture: true, passive: true });\n")
                append("      document.addEventListener('touchstart', (e) => {\n")
                append("        if (e.touches.length !== 1) return;\n")
                append("        const target = findPointerDragTarget(e.target);\n")
                append("        if (!target) return;\n")
                append("        const t = e.touches[0];\n")
                append("        dragStartX = t.clientX;\n")
                append("        dragStartY = t.clientY;\n")
                append("        lastTouchX = t.clientX;\n")
                append("        lastTouchY = t.clientY;\n")
                append("        dragTarget = target;\n")
                append("        dragScrollContainer = target.closest('.overflow-y-auto');\n")
                append("        isPointerDragging = false;\n")
                append("        isScrollFallback = false;\n")
                append("        dragTimer = setTimeout(() => {\n")
                append("          if (!dragTarget || isDraggingDrawer) return;\n")
                append("          isPointerDragging = true;\n")
                append("          if (dragScrollContainer) dragScrollContainer.style.touchAction = 'none';\n")
                append("          dragTarget.style.opacity = '0.7';\n")
                append("          if (dragTarget.getAttribute('draggable') === 'true') {\n")
                append("            try { dragTarget.dispatchEvent(new Event('dragstart', { bubbles: true, cancelable: true })); } catch (err) {}\n")
                append("          }\n")
                append("          emitPointerAndMouse(dragTarget, 'pointerdown', 'mousedown', dragStartX, dragStartY);\n")
                append("        }, 2000);\n")
                append("      }, { passive: true });\n")
                append("      document.addEventListener('touchmove', (e) => {\n")
                append("        if (isScrollFallback && dragScrollContainer && e.touches.length === 1) {\n")
                append("          const t = e.touches[0];\n")
                append("          dragScrollContainer.scrollTop += (lastTouchY - t.clientY);\n")
                append("          lastTouchX = t.clientX;\n")
                append("          lastTouchY = t.clientY;\n")
                append("          return;\n")
                append("        }\n")
                append("        if (!dragTarget) return;\n")
                append("        const t = e.touches[0];\n")
                append("        if (!isPointerDragging) {\n")
                append("          if (Math.hypot(t.clientX - dragStartX, t.clientY - dragStartY) > 8) {\n")
                append("            cancelPendingItemDrag();\n")
                append("            isScrollFallback = true;\n")
                append("            lastTouchX = t.clientX;\n")
                append("            lastTouchY = t.clientY;\n")
                append("          }\n")
                append("        } else {\n")
                append("          e.preventDefault();\n")
                append("          const nav = document.querySelector('[role=\"navigation\"]');\n")
                append("          const navR = nav ? nav.getBoundingClientRect() : null;\n")
                append("          const cx = navR ? Math.max(navR.left + 8, Math.min(t.clientX, navR.right - 8)) : t.clientX;\n")
                append("          const cy = navR ? Math.max(navR.top + 8, Math.min(t.clientY, navR.bottom - 8)) : t.clientY;\n")
                append("          lastTouchX = cx;\n")
                append("          lastTouchY = cy;\n")
                append("          emitPointerAndMouse(document, 'pointermove', 'mousemove', cx, cy);\n")
                append("        }\n")
                append("      }, { passive: false });\n")
                append("      const endPointerDrag = (e) => {\n")
                append("        if (e && e.type && e.type.startsWith('pointer') && e.pointerType === 'mouse') return;\n")
                append("        if (dragTimer) { clearTimeout(dragTimer); dragTimer = null; }\n")
                append("        isScrollFallback = false;\n")
                append("        if (isPointerDragging) {\n")
                append("          isPointerDragging = false;\n")
                append("          suppressNextClick = true;\n")
                append("          setTimeout(() => { suppressNextClick = false; }, 300);\n")
                append("          const t = e.changedTouches && e.changedTouches[0];\n")
                append("          const nav = document.querySelector('[role=\"navigation\"]');\n")
                append("          const navR = nav ? nav.getBoundingClientRect() : null;\n")
                append("          const rawX = t ? t.clientX : (e.clientX || lastTouchX || dragStartX);\n")
                append("          const rawY = t ? t.clientY : (e.clientY || lastTouchY || dragStartY);\n")
                append("          const x = navR ? Math.max(navR.left + 8, Math.min(rawX, navR.right - 8)) : rawX;\n")
                append("          const y = navR ? Math.max(navR.top + 8, Math.min(rawY, navR.bottom - 8)) : rawY;\n")
                append("          emitPointerAndMouse(document, 'pointerup', 'mouseup', x, y);\n")
                append("          if (dragTarget) dragTarget.style.opacity = '';\n")
                append("        }\n")
                append("        if (dragScrollContainer) { dragScrollContainer.style.touchAction = ''; dragScrollContainer = null; }\n")
                append("        isPointerDragging = false;\n")
                append("        dragTarget = null;\n")
                append("      };\n")
                append("      window.addEventListener('touchend', endPointerDrag, { capture: true, passive: true });\n")
                append("      window.addEventListener('touchcancel', endPointerDrag, { capture: true, passive: true });\n")
                append("      window.addEventListener('pointerup', endPointerDrag, { capture: true, passive: true });\n")
                append("      window.addEventListener('pointercancel', endPointerDrag, { capture: true, passive: true });\n")
                append("    })();\n")
                append("  }\n")
            }
            append("</script>")
        }
        return if (indexHtml.contains("<head>")) {
            indexHtml.replaceFirst("<head>", "<head>\n$bootScript")
        } else {
            "$bootScript\n$indexHtml"
        }
    }

    private fun escapeJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("<", "\\x3c")
            .replace(">", "\\x3e")
    }

    private fun locateEmbeddedZipSlice(raf: RandomAccessFile): Pair<Long, Long>? {
        val fileLen = raf.length()
        if (fileLen < 22) return null

        // 1. Check standalone ZIP at offset 0
        raf.seek(0)
        if (Integer.reverseBytes(raf.readInt()) == LOCAL_FILE_HEADER_SIG) {
            val eocdPos = findEocdInRange(raf, maxOf(0L, fileLen - 65557L), fileLen)
            if (eocdPos != null) {
                return 0L to (eocdPos.second - 0L)
            }
        }

        // 2. Fast-path check for verified Antigravity 1.107.0 language_server.exe offset
        if (fileLen >= KNOWN_DESKTOP_ZIP_OFFSET + KNOWN_DESKTOP_ZIP_LENGTH) {
            raf.seek(KNOWN_DESKTOP_ZIP_OFFSET)
            if (Integer.reverseBytes(raf.readInt()) == LOCAL_FILE_HEADER_SIG) {
                return KNOWN_DESKTOP_ZIP_OFFSET to KNOWN_DESKTOP_ZIP_LENGTH
            }
        }

        // 3. Dynamic scan across the binary for EOCD record (0x06054b50) with relative offsets
        val chunkSize = 1024 * 1024
        val buf = ByteArray(chunkSize + 64)
        var pos = 0L
        while (pos < fileLen) {
            raf.seek(pos)
            val read = raf.read(buf, 0, minOf(buf.size.toLong(), fileLen - pos).toInt())
            if (read < 22) break
            for (i in 0..(read - 22)) {
                if (buf[i] == 0x50.toByte() &&
                    buf[i + 1] == 0x4B.toByte() &&
                    buf[i + 2] == 0x05.toByte() &&
                    buf[i + 3] == 0x06.toByte()
                ) {
                    val bb = ByteBuffer.wrap(buf, i, 22).slice().order(ByteOrder.LITTLE_ENDIAN)
                    val entries = bb.getShort(10).toInt() and 0xFFFF
                    val cdSize = bb.getInt(12).toLong() and 0xFFFFFFFFL
                    val cdOffsetRel = bb.getInt(16).toLong() and 0xFFFFFFFFL
                    val commentLen = bb.getShort(20).toInt() and 0xFFFF
                    val eocdAbs = pos + i
                    val zipStart = eocdAbs - cdSize - cdOffsetRel
                    val zipTotalLen = cdOffsetRel + cdSize + 22L + commentLen
                    if (entries in 5..5000 && zipStart >= 0 && zipStart + zipTotalLen <= fileLen) {
                        raf.seek(zipStart)
                        if (Integer.reverseBytes(raf.readInt()) == LOCAL_FILE_HEADER_SIG) {
                            return zipStart to zipTotalLen
                        }
                    }
                }
            }
            pos += chunkSize
        }
        return null
    }

    private fun findEocdInRange(raf: RandomAccessFile, start: Long, end: Long): Pair<Long, Long>? {
        val len = (end - start).toInt()
        if (len < 22) return null
        val buf = ByteArray(len)
        raf.seek(start)
        raf.readFully(buf)
        for (i in (len - 22) downTo 0) {
            if (buf[i] == 0x50.toByte() &&
                buf[i + 1] == 0x4B.toByte() &&
                buf[i + 2] == 0x05.toByte() &&
                buf[i + 3] == 0x06.toByte()
            ) {
                val bb = ByteBuffer.wrap(buf, i, 22).slice().order(ByteOrder.LITTLE_ENDIAN)
                val commentLen = bb.getShort(20).toInt() and 0xFFFF
                val eocdAbs = start + i
                return eocdAbs to (eocdAbs + 22L + commentLen)
            }
        }
        return null
    }

    private fun parseCentralDirectory(
        raf: RandomAccessFile,
        zipOffset: Long,
        zipLength: Long
    ): Map<String, ZipEntryIndex>? {
        val zipEnd = zipOffset + zipLength
        val searchStart = maxOf(zipOffset, zipEnd - 65557L)
        val eocdInfo = findEocdInRange(raf, searchStart, zipEnd) ?: return null
        val eocdAbs = eocdInfo.first

        raf.seek(eocdAbs)
        val eocdBuf = ByteArray(22)
        raf.readFully(eocdBuf)
        val eocdBb = ByteBuffer.wrap(eocdBuf).order(ByteOrder.LITTLE_ENDIAN)
        if (eocdBb.int != EOCD_SIG) return null

        val entryCount = eocdBb.getShort(10).toInt() and 0xFFFF
        val cdOffsetRel = eocdBb.getInt(16).toLong() and 0xFFFFFFFFL

        var cursor = zipOffset + cdOffsetRel
        val result = LinkedHashMap<String, ZipEntryIndex>(entryCount)

        for (idx in 0 until entryCount) {
            if (cursor + 46 > zipEnd) return null
            raf.seek(cursor)
            val hdr = ByteArray(46)
            raf.readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != CENTRAL_DIR_HEADER_SIG) return null

            val compMethod = bb.getShort(10).toInt() and 0xFFFF
            val compSize = bb.getInt(20).toLong() and 0xFFFFFFFFL
            val uncompSize = bb.getInt(24).toLong() and 0xFFFFFFFFL
            val nameLen = bb.getShort(28).toInt() and 0xFFFF
            val extraLen = bb.getShort(30).toInt() and 0xFFFF
            val commentLen = bb.getShort(32).toInt() and 0xFFFF
            val localHeaderOffset = bb.getInt(42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            raf.readFully(nameBytes)
            val name = nameBytes.toString(Charsets.UTF_8)

            if (!name.endsWith("/")) {
                result[name] = ZipEntryIndex(
                    name = name,
                    compressionMethod = compMethod,
                    compressedSize = compSize,
                    uncompressedSize = uncompSize,
                    localHeaderRelOffset = localHeaderOffset
                )
            }
            cursor += 46L + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun inflateRaw(compressed: ByteArray, expectedSizeHint: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(expectedSizeHint.coerceIn(1024, 16 * 1024 * 1024))
            val buffer = ByteArray(32 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
