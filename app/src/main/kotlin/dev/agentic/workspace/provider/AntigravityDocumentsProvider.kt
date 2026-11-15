package dev.agentic.workspace.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the Antigravity workspaces directory to the Android Files app (DocumentsUI)
 * and allows opening project folders directly in the system file manager.
 */
class AntigravityDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "dev.agentic.workspace.documents"
        private const val ROOT_ID = "antigravity_workspaces_root"
        private const val ROOT_DOC_ID = "."

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        fun hasAllFilesAccess(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        }

        fun resolveWorkspacesRoot(context: Context): File {
            if (hasAllFilesAccess()) {
                val primaryRoot = Environment.getExternalStorageDirectory()
                if (primaryRoot.exists() && primaryRoot.canWrite()) {
                    return primaryRoot
                }
            }
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val root = File(downloads, "Antigravity/workspaces")
            if (!root.exists()) {
                root.mkdirs()
            }
            if (root.exists() && root.canWrite()) {
                return root
            }
            val internalFallback = File(context.filesDir, "rootfs/tmp/gemini_app/workspaces")
            if (!internalFallback.exists()) {
                internalFallback.mkdirs()
            }
            return internalFallback
        }

        fun resolveInternalWorkspacesDir(context: Context): File {
            val dir = File(context.filesDir, "rootfs/tmp/gemini_app/workspaces")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun resolveRealProjectDirectory(context: Context, sanitizedName: String): File {
            val mainDirCandidate = File(Environment.getExternalStorageDirectory(), sanitizedName)
            if (mainDirCandidate.exists()) return mainDirCandidate
            val downloadsCandidate = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Antigravity/workspaces/$sanitizedName"
            )
            if (downloadsCandidate.exists()) return downloadsCandidate
            val parent = resolveWorkspacesRoot(context)
            return File(parent, sanitizedName)
        }

        fun createAndLinkProjectFolder(context: Context, sanitizedName: String): File {
            val parent = resolveWorkspacesRoot(context)
            val realTarget = File(parent, sanitizedName)
            if (!realTarget.exists()) realTarget.mkdirs()

            val internalDir = resolveInternalWorkspacesDir(context)
            val linkFile = File(internalDir, sanitizedName)
            val externalRootPath = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            val guestTargetPath = if (realTarget.absolutePath.startsWith("$externalRootPath/")) {
                val rel = realTarget.absolutePath.removePrefix("$externalRootPath/").trimStart('/')
                "/tmp/gemini_app/storage/$rel"
            } else {
                null
            }
            if (guestTargetPath != null) {
                try {
                    if (linkFile.exists() || runCatching { android.system.Os.lstat(linkFile.absolutePath) }.isSuccess) {
                        linkFile.deleteRecursively()
                    }
                    android.system.Os.symlink(guestTargetPath, linkFile.absolutePath)
                } catch (_: Exception) {
                    if (!linkFile.exists()) linkFile.mkdirs()
                }
            } else if (!linkFile.exists()) {
                linkFile.mkdirs()
            }
            return realTarget
        }

        fun listLinkedWorkspaceFolders(context: Context): List<Pair<String, String>> {
            val internalDir = resolveInternalWorkspacesDir(context)
            val externalRoot = Environment.getExternalStorageDirectory()
            val results = mutableListOf<Pair<String, String>>()
            val entries = internalDir.listFiles() ?: return results
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                val linkTarget = runCatching { android.system.Os.readlink(entry.absolutePath) }.getOrNull()
                val backingExists = when {
                    linkTarget != null && linkTarget.startsWith("/tmp/gemini_app/storage/") -> {
                        val rel = linkTarget.removePrefix("/tmp/gemini_app/storage/").trimStart('/')
                        File(externalRoot, rel).isDirectory
                    }
                    linkTarget != null -> entry.isDirectory
                    else -> entry.isDirectory
                }
                if (backingExists) {
                    results.add(entry.name to "/tmp/gemini_app/workspaces/${entry.name}")
                }
            }
            return results
        }

        fun resolveHostPathForWorkspaceUri(context: Context, targetPath: String): File {
            val root = resolveWorkspacesRoot(context)
            val normalized = targetPath.removePrefix("file://").trim()
            val externalRoot = Environment.getExternalStorageDirectory()
            return when {
                normalized.startsWith("/tmp/gemini_app/storage/") -> {
                    val rel = normalized.removePrefix("/tmp/gemini_app/storage/").trimStart('/')
                    File(externalRoot, rel)
                }
                normalized.startsWith("/tmp/gemini_app/workspaces/") -> {
                    val rel = normalized.removePrefix("/tmp/gemini_app/workspaces/").trimStart('/')
                    val parts = rel.split('/', limit = 2)
                    val projectName = parts.firstOrNull().orEmpty()
                    val subPath = if (parts.size > 1) parts[1] else ""
                    val internalEntry = File(resolveInternalWorkspacesDir(context), projectName)
                    val linkTarget = runCatching { android.system.Os.readlink(internalEntry.absolutePath) }.getOrNull()
                    val projectDir = when {
                        linkTarget != null && linkTarget.startsWith("/tmp/gemini_app/storage/") -> {
                            File(externalRoot, linkTarget.removePrefix("/tmp/gemini_app/storage/").trimStart('/'))
                        }
                        else -> resolveRealProjectDirectory(context, projectName)
                    }
                    if (subPath.isNotEmpty()) File(projectDir, subPath) else projectDir
                }
                normalized == "/tmp/gemini_app/workspaces" -> root
                normalized.startsWith(root.absolutePath) -> File(normalized)
                else -> resolveRealProjectDirectory(context, File(normalized).name)
            }
        }

        fun documentIdForPath(context: Context, targetPath: String): String {
            val root = resolveWorkspacesRoot(context)
            val candidate = resolveHostPathForWorkspaceUri(context, targetPath)
            if (!candidate.exists() && candidate.parentFile?.exists() == true) {
                candidate.mkdirs()
            }
            val rel = runCatching { candidate.canonicalFile.relativeTo(root.canonicalFile).path }.getOrNull()
            return if (rel.isNullOrBlank()) ROOT_DOC_ID else rel.replace('\\', '/')
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result
        val root = resolveWorkspacesRoot(ctx)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, ctx.applicationInfo.icon)
            add(Root.COLUMN_TITLE, "Antigravity Projects")
            add(Root.COLUMN_SUMMARY, root.absolutePath)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, fileForDocumentId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileForDocumentId(parentDocumentId)
        val children = parent.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        for (file in children) {
            val childId = if (parentDocumentId == ROOT_DOC_ID) file.name else "$parentDocumentId/${file.name}"
            includeFile(result, childId, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForDocumentId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileForDocumentId(parentDocumentId)
        val target = File(parent, displayName)
        if (Document.MIME_TYPE_DIR == mimeType) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        return if (parentDocumentId == ROOT_DOC_ID) target.name else "$parentDocumentId/${target.name}"
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocumentId(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete $documentId")
        }
    }

    private fun fileForDocumentId(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val root = resolveWorkspacesRoot(ctx).canonicalFile
        if (documentId == ROOT_DOC_ID || documentId.isBlank()) return root
        val target = File(root, documentId.trimStart('/')).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw FileNotFoundException("Invalid document path: $documentId")
        }
        return target
    }

    private fun includeFile(cursor: MatrixCursor, docId: String, file: File) {
        val isDir = file.isDirectory
        val mime = if (isDir) Document.MIME_TYPE_DIR else getMimeType(file)
        var flags = 0
        if (file.canWrite()) {
            flags = if (isDir) {
                flags or Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
            } else {
                flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            }
        }
        val displayName = if (docId == ROOT_DOC_ID) "Antigravity Projects" else file.name
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) 0L else file.length())
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }
}
