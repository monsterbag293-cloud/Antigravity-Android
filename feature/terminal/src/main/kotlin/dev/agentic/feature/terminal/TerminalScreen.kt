package dev.agentic.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.designsystem.AgenticAppBar
import dev.agentic.core.designsystem.BrandInfo
import dev.agentic.core.designsystem.BrandWarning
import dev.agentic.core.designsystem.StatusBadge

data class TerminalLine(
    val type: LineType,
    val text: String
)

enum class LineType {
    INPUT, OUTPUT, ERROR, INFO
}

@Composable
fun TerminalScreen(
    isHostConnected: Boolean = false
) {
    var commandInput by remember { mutableStateOf("") }
    val terminalLines = remember {
        mutableStateListOf(
            TerminalLine(LineType.INFO, "Agentic Mobile Terminal v0.1.0"),
            TerminalLine(LineType.INFO, "Target: Android 14 (API 34) Sandboxed Environment"),
            TerminalLine(LineType.INFO, "Mode: " + if (isHostConnected) "Remote Workspace Host PTY" else "Device Standalone (Sandboxed SPI)"),
            TerminalLine(LineType.OUTPUT, "Ready. Type 'help', 'status', 'ls', or 'git' for supported commands.")
        )
    }

    val listState = rememberLazyListState()

    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            AgenticAppBar(
                title = "Terminal",
                actions = {
                    if (isHostConnected) {
                        StatusBadge(text = "Host PTY", color = BrandInfo)
                    } else {
                        StatusBadge(text = "Standalone SPI", color = BrandWarning)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Standalone mode banner explaining constraints & ADR-01 / ADR-03
            if (!isHostConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = BrandInfo
                        )
                        Text(
                            text = "Device-only mode active: Local execution runs through sandboxed file and git SPIs. For unconstrained bash/zsh and full compiler toolchains, connect an optional Go Workspace Host in Settings (ADR-03).",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Terminal log viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(terminalLines) { line ->
                        val textColor = when (line.type) {
                            LineType.INPUT -> Color(0xFF38BDF8)
                            LineType.OUTPUT -> Color(0xFFE2E8F0)
                            LineType.ERROR -> Color(0xFFF87171)
                            LineType.INFO -> Color(0xFF94A3B8)
                        }
                        val prefix = when (line.type) {
                            LineType.INPUT -> "$ "
                            LineType.OUTPUT -> ""
                            LineType.ERROR -> "[ERR] "
                            LineType.INFO -> "[INFO] "
                        }
                        Text(
                            text = prefix + line.text,
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Command input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("Enter command...", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val cmd = commandInput.trim()
                        if (cmd.isNotBlank()) {
                            terminalLines.add(TerminalLine(LineType.INPUT, cmd))
                            when (cmd.lowercase()) {
                                "help" -> {
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "Available standalone commands:"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "  status - show device and workspace runtime status"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "  ls     - list files in sandboxed project root"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "  git    - display on-device JGit workspace status"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "  clear  - clear terminal history"))
                                }
                                "status" -> {
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "Runtime: Standalone Device-Only (ADR-01)"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "Provider: ModelProvider SPI (decoupled)"))
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "Security: Android Keystore hardware-backed"))
                                }
                                "ls" -> {
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "README.md\napp/\ncore/\nbuild.gradle.kts\nsettings.gradle.kts"))
                                }
                                "git" -> {
                                    terminalLines.add(TerminalLine(LineType.OUTPUT, "On branch main. Working tree clean (managed by JGit provider)."))
                                }
                                "clear" -> {
                                    terminalLines.clear()
                                }
                                else -> {
                                    terminalLines.add(TerminalLine(LineType.ERROR, "Command '$cmd' is not permitted in standalone device sandbox."))
                                    terminalLines.add(TerminalLine(LineType.INFO, "Connect a remote Workspace Host to run arbitrary shell processes."))
                                }
                            }
                            commandInput = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Run command",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
