package dev.agentic.tools.local

import dev.agentic.core.model.ToolCall
import dev.agentic.tools.spi.RiskLevel
import dev.agentic.tools.spi.ToolDefinition
import dev.agentic.tools.spi.ToolProvider
import dev.agentic.tools.spi.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.regex.Pattern

/**
 * Sandboxed Workspace Tool Provider (P1.2 Phase 3 & Phase 4).
 *
 * Implements the Antigravity workspace tools strictly inside `rootDirectory`:
 * - `view_file` (and alias `read_file`)
 * - `write_to_file` (and alias `write_file`)
 * - `edit_file`
 * - `list_dir` (and alias `list_files`)
 * - `grep_search`
 * - `find_by_name`
 * - `run_command`
 */
class LocalFileTools(
    private val rootDirectory: File,
    val commandExecutor: SandboxedCommandExecutor = SandboxedCommandExecutor(rootDirectory)
) : ToolProvider {
    override val providerName: String = "LocalFileTools"

    private val json = Json { ignoreUnknownKeys = true }

    override fun getAvailableTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "view_file",
            description = "Read the contents of a file in the workspace, optionally specifying 1-based start_line and end_line.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Workspace-relative path or file:// URI to read."},"start_line":{"type":"INTEGER","description":"Optional 1-based start line (inclusive)."},"end_line":{"type":"INTEGER","description":"Optional 1-based end line (inclusive)."}},"required":["path"]}""",
            riskLevel = RiskLevel.LOW
        ),
        ToolDefinition(
            name = "write_to_file",
            description = "Create a new file or overwrite an existing file in the workspace with the provided content.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Workspace-relative path or file:// URI to write."},"content":{"type":"STRING","description":"Full text content to write to the file."},"overwrite":{"type":"BOOLEAN","description":"Whether to allow overwriting an existing file (default true)."}},"required":["path","content"]}""",
            riskLevel = RiskLevel.MEDIUM
        ),
        ToolDefinition(
            name = "edit_file",
            description = "Apply a targeted replacement to an existing file in the workspace while preserving all surrounding content.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Workspace-relative path or file:// URI to edit."},"target_content":{"type":"STRING","description":"Exact contiguous block of existing text to replace."},"replacement_content":{"type":"STRING","description":"Replacement text for the target block."},"allow_multiple":{"type":"BOOLEAN","description":"Whether to replace multiple occurrences if target_content appears more than once (default false)."}},"required":["path","target_content","replacement_content"]}""",
            riskLevel = RiskLevel.MEDIUM
        ),
        ToolDefinition(
            name = "list_dir",
            description = "List files and subdirectories in a directory of the active workspace.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Workspace-relative directory path (defaults to '.' workspace root)."}},"required":[]}""",
            riskLevel = RiskLevel.LOW
        ),
        ToolDefinition(
            name = "grep_search",
            description = "Search for a text pattern or regular expression across files in the workspace.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"Search query string or regular expression."},"search_path":{"type":"STRING","description":"Optional directory or file path within the workspace to search (default '.')."},"case_insensitive":{"type":"BOOLEAN","description":"Perform case-insensitive matching (default false)."},"is_regex":{"type":"BOOLEAN","description":"Treat query as a regular expression (default false)."},"includes":{"type":"STRING","description":"Optional filename glob filter such as '*.kt' or '*.md'."}},"required":["query"]}""",
            riskLevel = RiskLevel.LOW
        ),
        ToolDefinition(
            name = "find_by_name",
            description = "Find files and directories in the workspace matching a filename pattern or glob.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"pattern":{"type":"STRING","description":"Filename substring or glob pattern (e.g., '*.kt', 'Main*')."},"search_directory":{"type":"STRING","description":"Optional subdirectory within the workspace (default '.')."},"max_depth":{"type":"INTEGER","description":"Maximum directory traversal depth (default 10)."}},"required":["pattern"]}""",
            riskLevel = RiskLevel.LOW
        ),
        ToolDefinition(
            name = "run_command",
            description = "Execute a sandboxed shell command inside the workspace directory.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"Shell command line to execute inside the workspace."},"cwd":{"type":"STRING","description":"Optional working directory relative to workspace root."},"timeout_ms":{"type":"INTEGER","description":"Optional execution timeout in milliseconds (default 15000)."}},"required":["command"]}""",
            riskLevel = RiskLevel.MEDIUM
        )
    )

    override suspend fun executeTool(call: ToolCall): ToolResult {
        if (!call.parseError.isNullOrBlank()) {
            return ToolResult(
                callId = call.id,
                success = false,
                output = "Error: Malformed tool arguments for ${call.name}: ${call.parseError}"
            )
        }
        val args = try {
            if (call.argumentsJson.isBlank()) {
                buildJsonObject {}
            } else {
                json.parseToJsonElement(call.argumentsJson).jsonObject
            }
        } catch (e: Exception) {
            return ToolResult(
                callId = call.id,
                success = false,
                output = "Error: Invalid JSON arguments for ${call.name}: ${e.message}"
            )
        }

        return try {
            when (call.name) {
                "view_file", "read_file" -> executeViewFile(call.id, args)
                "write_to_file", "write_file" -> executeWriteToFile(call.id, args)
                "edit_file" -> executeEditFile(call.id, args)
                "list_dir", "list_files" -> executeListDir(call.id, args)
                "grep_search" -> executeGrepSearch(call.id, args)
                "find_by_name" -> executeFindByName(call.id, args)
                "run_command" -> executeRunCommand(call.id, args)
                else -> ToolResult(
                    callId = call.id,
                    success = false,
                    output = "Error: Unsupported tool '${call.name}'."
                )
            }
        } catch (se: SecurityException) {
            ToolResult(
                callId = call.id,
                success = false,
                output = "Error: Path traversal attempt outside workspace root (${se.message})"
            )
        } catch (e: Exception) {
            ToolResult(
                callId = call.id,
                success = false,
                output = "Execution failed: ${e.message}"
            )
        }
    }

    private fun executeViewFile(callId: String, args: JsonObject): ToolResult {
        val rawPath = stringArg(args, "path", "AbsolutePath", "file_path", "TargetFile")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'path' for view_file.")
        val target = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawPath)
        if (!target.exists()) {
            return ToolResult(callId, false, "Error: File not found: $rawPath")
        }
        if (target.isDirectory) {
            return ToolResult(callId, false, "Error: '$rawPath' is a directory, not a file. Use list_dir instead.")
        }

        val fullText = target.readText()
        val allLines = fullText.lines()
        val totalLines = allLines.size
        val startLineReq = intArg(args, "start_line", "StartLine")
        val endLineReq = intArg(args, "end_line", "EndLine")

        val actualStart = (startLineReq ?: 1).coerceAtLeast(1)
        val actualEnd = (endLineReq ?: totalLines).coerceAtLeast(actualStart).coerceAtMost(maxOf(1, totalLines))

        val selectedContent = if (startLineReq == null && endLineReq == null) {
            fullText
        } else if (totalLines == 0) {
            ""
        } else {
            allLines.subList(actualStart - 1, actualEnd).joinToString("\n")
        }

        val absUri = WorkspacePathResolver.toNormalizedFileUri(target)
        val relPath = WorkspacePathResolver.toRelativePath(rootDirectory, target)

        return ToolResult(
            callId = callId,
            success = true,
            output = selectedContent,
            artifacts = listOf(relPath),
            structuredData = mapOf(
                "toolType" to "view_file",
                "absolutePathUri" to absUri,
                "relativePath" to relPath,
                "startLine" to actualStart.toString(),
                "endLine" to actualEnd.toString(),
                "numLines" to totalLines.toString(),
                "numBytes" to target.length().toString(),
                "content" to selectedContent
            )
        )
    }

    private fun executeWriteToFile(callId: String, args: JsonObject): ToolResult {
        val rawPath = stringArg(args, "path", "TargetFile", "file_path", "AbsolutePath")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'path' for write_to_file.")
        val content = stringArg(args, "content", "CodeContent")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'content' for write_to_file.")
        val overwrite = boolArg(args, "overwrite", "Overwrite") ?: true

        val target = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawPath)
        if (target.exists() && target.isDirectory) {
            return ToolResult(callId, false, "Error: Cannot overwrite directory '$rawPath' with a file.")
        }
        if (target.exists() && !overwrite) {
            return ToolResult(
                callId = callId,
                success = false,
                output = "Error: File already exists at '$rawPath' and overwrite=false. Use edit_file or set overwrite=true."
            )
        }

        val existedBefore = target.exists()
        val originalContents = if (existedBefore) target.readText() else ""

        target.parentFile?.mkdirs()
        target.writeText(content)

        val (additions, deletions) = computeLineDiffStats(originalContents, content, existedBefore)
        val absUri = WorkspacePathResolver.toNormalizedFileUri(target)
        val relPath = WorkspacePathResolver.toRelativePath(rootDirectory, target)
        val changeType = if (existedBefore) "FILE_CHANGE_TYPE_EDIT" else "FILE_CHANGE_TYPE_CREATE"

        return ToolResult(
            callId = callId,
            success = true,
            output = "File written successfully: $relPath (${content.length} characters, +$additions/-$deletions lines)",
            artifacts = listOf(relPath),
            structuredData = mapOf(
                "toolType" to "write_to_file",
                "absolutePathUri" to absUri,
                "relativePath" to relPath,
                "fileChangeType" to changeType,
                "originalContents" to originalContents,
                "modifiedContents" to content,
                "additions" to additions.toString(),
                "deletions" to deletions.toString(),
                "overwrite" to overwrite.toString()
            )
        )
    }

    private fun executeEditFile(callId: String, args: JsonObject): ToolResult {
        val rawPath = stringArg(args, "path", "TargetFile", "file_path", "AbsolutePath")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'path' for edit_file.")
        val targetContent = stringArg(args, "target_content", "TargetContent", "Target")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'target_content' for edit_file.")
        val replacementContent = stringArg(args, "replacement_content", "ReplacementContent", "Replacement")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'replacement_content' for edit_file.")
        val allowMultiple = boolArg(args, "allow_multiple", "AllowMultiple") ?: false

        if (targetContent.isEmpty()) {
            return ToolResult(callId, false, "Error: 'target_content' must not be empty.")
        }

        val target = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawPath)
        if (!target.exists() || !target.isFile) {
            return ToolResult(callId, false, "Error: File not found: $rawPath")
        }

        val originalContents = target.readText()
        val usesCrLf = originalContents.contains("\r\n")
        val normalizedOriginal = originalContents.replace("\r\n", "\n")
        val normalizedTarget = targetContent.replace("\r\n", "\n")
        val normalizedReplacement = replacementContent.replace("\r\n", "\n")

        val occurrences = countOccurrences(normalizedOriginal, normalizedTarget)
        if (occurrences == 0) {
            return ToolResult(
                callId = callId,
                success = false,
                output = "Error: target_content was not found in '$rawPath'. Existing file contents were preserved unchanged."
            )
        }
        if (occurrences > 1 && !allowMultiple) {
            return ToolResult(
                callId = callId,
                success = false,
                output = "Error: target_content appears $occurrences times in '$rawPath' and allow_multiple=false. Include more surrounding lines."
            )
        }

        val firstMatchIdx = normalizedOriginal.indexOf(normalizedTarget)
        val startLine = normalizedOriginal.substring(0, firstMatchIdx).count { it == '\n' } + 1
        val endLine = startLine + normalizedTarget.count { it == '\n' }

        val updatedNormalized = if (allowMultiple) {
            normalizedOriginal.replace(normalizedTarget, normalizedReplacement)
        } else {
            normalizedOriginal.replaceFirst(normalizedTarget, normalizedReplacement)
        }

        val finalContent = if (usesCrLf) updatedNormalized.replace("\n", "\r\n") else updatedNormalized
        target.writeText(finalContent)

        val (additions, deletions) = computeLineDiffStats(normalizedOriginal, updatedNormalized, existedBefore = true)
        val absUri = WorkspacePathResolver.toNormalizedFileUri(target)
        val relPath = WorkspacePathResolver.toRelativePath(rootDirectory, target)

        return ToolResult(
            callId = callId,
            success = true,
            output = "Edited $relPath (lines $startLine..$endLine, +$additions/-$deletions lines).",
            artifacts = listOf(relPath),
            structuredData = mapOf(
                "toolType" to "edit_file",
                "absolutePathUri" to absUri,
                "relativePath" to relPath,
                "fileChangeType" to "FILE_CHANGE_TYPE_EDIT",
                "originalContents" to originalContents,
                "modifiedContents" to finalContent,
                "targetContent" to targetContent,
                "replacementContent" to replacementContent,
                "startLine" to startLine.toString(),
                "endLine" to endLine.toString(),
                "additions" to additions.toString(),
                "deletions" to deletions.toString()
            )
        )
    }

    private fun executeListDir(callId: String, args: JsonObject): ToolResult {
        val rawPath = stringArg(args, "path", "DirectoryPath", "dir_path") ?: "."
        val target = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawPath)
        if (!target.exists()) {
            return ToolResult(
                callId = callId,
                success = false,
                output = "Error: Directory not found: $rawPath",
                structuredData = mapOf(
                    "toolType" to "list_dir",
                    "directoryPathUri" to WorkspacePathResolver.toNormalizedFileUri(target),
                    "dirNotFound" to "true"
                )
            )
        }
        if (!target.isDirectory) {
            return ToolResult(callId, false, "Error: '$rawPath' is a file, not a directory. Use view_file instead.")
        }

        val childrenFiles = target.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()

        val entriesJson = buildJsonArray {
            for (child in childrenFiles) {
                add(buildJsonObject {
                    put("name", child.name)
                    put("isDir", child.isDirectory)
                    put("sizeBytes", if (child.isFile) child.length() else 0L)
                    if (child.isDirectory) {
                        put("numChildren", child.listFiles()?.size ?: 0)
                    }
                    put("uri", WorkspacePathResolver.toNormalizedFileUri(child))
                })
            }
        }.toString()

        val listingText = if (childrenFiles.isEmpty()) {
            "Empty directory"
        } else {
            childrenFiles.joinToString("\n") { child ->
                if (child.isDirectory) {
                    "[DIR] ${child.name}/ (${child.listFiles()?.size ?: 0} items)"
                } else {
                    "[FILE] ${child.name} (${child.length()} bytes)"
                }
            }
        }

        val dirUri = WorkspacePathResolver.toNormalizedFileUri(target)
        return ToolResult(
            callId = callId,
            success = true,
            output = listingText,
            structuredData = mapOf(
                "toolType" to "list_dir",
                "directoryPathUri" to dirUri,
                "entriesJson" to entriesJson,
                "totalChildren" to childrenFiles.size.toString()
            )
        )
    }

    private fun executeGrepSearch(callId: String, args: JsonObject): ToolResult {
        val query = stringArg(args, "query", "Query", "pattern")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'query' for grep_search.")
        if (query.isBlank()) {
            return ToolResult(callId, false, "Error: 'query' must not be blank.")
        }
        val rawSearchPath = stringArg(args, "search_path", "SearchPath", "path") ?: "."
        val caseInsensitive = boolArg(args, "case_insensitive", "CaseInsensitive") ?: false
        val isRegex = boolArg(args, "is_regex", "IsRegex") ?: false
        val includesGlob = stringArg(args, "includes", "Includes")

        val searchTarget = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawSearchPath)
        if (!searchTarget.exists()) {
            return ToolResult(callId, false, "Error: Search path does not exist: $rawSearchPath")
        }

        val regex = try {
            val flags = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            if (isRegex) Regex(query, flags) else Regex(Regex.escape(query), flags)
        } catch (e: Exception) {
            return ToolResult(callId, false, "Error: Invalid regular expression '$query': ${e.message}")
        }

        val includePattern = includesGlob?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val matches = mutableListOf<GrepMatchItem>()

        val filesToSearch = if (searchTarget.isFile) {
            sequenceOf(searchTarget)
        } else {
            searchTarget.walkTopDown()
                .onEnter { dir -> dir.name !in IGNORED_SEARCH_DIRS }
                .filter { it.isFile && it.length() <= MAX_GREP_FILE_BYTES }
        }

        for (file in filesToSearch) {
            if (includePattern != null && !includePattern.matches(file.name)) continue
            val relPath = WorkspacePathResolver.toRelativePath(rootDirectory, file)
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for ((idx, line) in lines.withIndex()) {
                if (regex.containsMatchIn(line)) {
                    matches.add(
                        GrepMatchItem(
                            relativePath = relPath,
                            lineNumber = idx + 1,
                            content = line.take(300),
                            absolutePath = file.canonicalFile.path.replace('\\', '/')
                        )
                    )
                    if (matches.size >= MAX_GREP_MATCHES) break
                }
            }
            if (matches.size >= MAX_GREP_MATCHES) break
        }

        val resultsJson = buildJsonArray {
            for (m in matches) {
                add(buildJsonObject {
                    put("relativePath", m.relativePath)
                    put("lineNumber", m.lineNumber)
                    put("content", m.content)
                    put("absolutePath", m.absolutePath)
                })
            }
        }.toString()

        val summaryOutput = if (matches.isEmpty()) {
            "No matches found for '$query' in $rawSearchPath."
        } else {
            matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.content}" }
        }

        return ToolResult(
            callId = callId,
            success = true,
            output = summaryOutput,
            structuredData = mapOf(
                "toolType" to "grep_search",
                "searchPathUri" to WorkspacePathResolver.toNormalizedFileUri(searchTarget),
                "query" to query,
                "totalResults" to matches.size.toString(),
                "resultsJson" to resultsJson,
                "rawOutput" to summaryOutput
            )
        )
    }

    private fun executeFindByName(callId: String, args: JsonObject): ToolResult {
        val pattern = stringArg(args, "pattern", "Pattern", "query")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'pattern' for find_by_name.")
        val rawSearchDir = stringArg(args, "search_directory", "SearchDirectory", "path") ?: "."
        val maxDepth = (intArg(args, "max_depth", "MaxDepth") ?: 10).coerceIn(1, 25)

        val searchDir = WorkspacePathResolver.resolveSafeFile(rootDirectory, rawSearchDir)
        if (!searchDir.exists() || !searchDir.isDirectory) {
            return ToolResult(callId, false, "Error: Search directory not found: $rawSearchDir")
        }

        val nameRegex = if (pattern.contains('*') || pattern.contains('?')) {
            globToRegex(pattern)
        } else {
            Regex(".*" + Regex.escape(pattern) + ".*", RegexOption.IGNORE_CASE)
        }

        val matchedPaths = mutableListOf<String>()
        searchDir.walkTopDown()
            .maxDepth(maxDepth)
            .onEnter { dir -> dir == searchDir || dir.name !in IGNORED_SEARCH_DIRS }
            .forEach { file ->
                if (file != searchDir && nameRegex.matches(file.name)) {
                    val rel = WorkspacePathResolver.toRelativePath(rootDirectory, file)
                    val suffix = if (file.isDirectory) "/" else ""
                    matchedPaths.add(rel + suffix)
                }
            }

        matchedPaths.sort()
        val limited = matchedPaths.take(MAX_FIND_RESULTS)
        val rawOutput = if (limited.isEmpty()) {
            "No files or directories matching '$pattern' found in $rawSearchDir."
        } else {
            limited.joinToString("\n")
        }

        val resultsJson = buildJsonArray {
            for (p in limited) {
                add(kotlinx.serialization.json.JsonPrimitive(p))
            }
        }.toString()

        return ToolResult(
            callId = callId,
            success = true,
            output = rawOutput,
            structuredData = mapOf(
                "toolType" to "find_by_name",
                "searchDirectory" to WorkspacePathResolver.toNormalizedFileUri(searchDir),
                "pattern" to pattern,
                "totalResults" to limited.size.toString(),
                "resultsJson" to resultsJson,
                "rawOutput" to rawOutput
            )
        )
    }

    private suspend fun executeRunCommand(callId: String, args: JsonObject): ToolResult {
        val commandLine = stringArg(args, "command", "CommandLine", "command_line", "cmd")
            ?: return ToolResult(callId, false, "Error: Missing required parameter 'command' for run_command.")
        val cwd = stringArg(args, "cwd", "Cwd")
        val timeoutMs = longArg(args, "timeout_ms", "WaitMsBeforeAsync")

        val outcome = commandExecutor.executeCommand(
            command = commandLine,
            cwdUriOrPath = cwd,
            timeoutMs = timeoutMs,
            commandId = callId
        )

        val combinedOutput = buildString {
            if (outcome.stdout.isNotBlank()) {
                appendLine(outcome.stdout)
            }
            if (outcome.stderr.isNotBlank()) {
                appendLine(outcome.stderr)
            }
            append("Exit code: ${outcome.exitCode}")
            if (outcome.timedOut) append(" (TIMED OUT)")
        }.trim()

        val isSuccess = outcome.exitCode == 0 && !outcome.timedOut && outcome.blockedReason == null
        return ToolResult(
            callId = callId,
            success = isSuccess,
            output = combinedOutput,
            structuredData = mapOf(
                "toolType" to "run_command",
                "commandId" to outcome.commandId,
                "commandLine" to outcome.commandLine,
                "cwd" to outcome.cwd,
                "stdout" to outcome.stdout,
                "stderr" to outcome.stderr,
                "exitCode" to outcome.exitCode.toString(),
                "timedOut" to outcome.timedOut.toString(),
                "combinedOutput" to combinedOutput
            )
        )
    }

    private fun stringArg(args: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = args[k]?.jsonPrimitive?.content
            if (v != null) return v
        }
        return null
    }

    private fun intArg(args: JsonObject, vararg keys: String): Int? {
        for (k in keys) {
            val v = args[k]?.jsonPrimitive?.intOrNull
            if (v != null) return v
        }
        return null
    }

    private fun longArg(args: JsonObject, vararg keys: String): Long? {
        for (k in keys) {
            val v = args[k]?.jsonPrimitive?.longOrNull
            if (v != null) return v
        }
        return null
    }

    private fun boolArg(args: JsonObject, vararg keys: String): Boolean? {
        for (k in keys) {
            val v = args[k]?.jsonPrimitive?.booleanOrNull
            if (v != null) return v
        }
        return null
    }

    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = text.indexOf(sub, idx)
            if (found == -1) break
            count++
            idx = found + sub.length
        }
        return count
    }

    private fun computeLineDiffStats(
        originalContents: String,
        modifiedContents: String,
        existedBefore: Boolean
    ): Pair<Int, Int> {
        if (!existedBefore) {
            val added = if (modifiedContents.isEmpty()) 0 else modifiedContents.lines().size
            return added to 0
        }
        val oldLines = if (originalContents.isEmpty()) emptyList() else originalContents.lines()
        val newLines = if (modifiedContents.isEmpty()) emptyList() else modifiedContents.lines()
        val oldFreq = oldLines.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (line in newLines) {
            val rem = oldFreq[line] ?: 0
            if (rem > 0) {
                common++
                oldFreq[line] = rem - 1
            }
        }
        val additions = (newLines.size - common).coerceAtLeast(0)
        val deletions = (oldLines.size - common).coerceAtLeast(0)
        return additions to deletions
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> sb.append(Pattern.quote(ch.toString()))
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private data class GrepMatchItem(
        val relativePath: String,
        val lineNumber: Int,
        val content: String,
        val absolutePath: String
    )

    companion object {
        private const val MAX_GREP_FILE_BYTES = 1024 * 1024L
        private const val MAX_GREP_MATCHES = 100
        private const val MAX_FIND_RESULTS = 200
        private val IGNORED_SEARCH_DIRS = setOf(".git", ".gradle", "build", "node_modules", ".idea")
    }
}
