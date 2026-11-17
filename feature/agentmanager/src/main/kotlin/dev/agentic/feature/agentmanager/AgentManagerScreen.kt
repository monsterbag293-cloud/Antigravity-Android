package dev.agentic.feature.agentmanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.designsystem.AgenticAppBar
import dev.agentic.core.designsystem.BrandPrimary
import dev.agentic.core.designsystem.StatusBadge
import dev.agentic.core.model.AgentRun
import dev.agentic.core.model.RunStatus

@Composable
fun AgentManagerScreen(
    modifier: Modifier = Modifier
) {
    val sampleRuns = listOf(
        AgentRun(
            id = "run-1",
            conversationId = "conv-welcome",
            title = "Analyze project dependencies",
            status = RunStatus.RUNNING,
            currentStep = "Inspecting gradle/libs.versions.toml...",
            totalSteps = 3
        ),
        AgentRun(
            id = "run-2",
            conversationId = "conv-welcome",
            title = "Apply Android Keystore secret storage",
            status = RunStatus.AWAITING_APPROVAL,
            currentStep = "Waiting for approval to write KeystoreSecretStorage.kt",
            pendingApprovalMessage = "Approve tool call: write_file(core/data/...)"
        ),
        AgentRun(
            id = "run-3",
            conversationId = "conv-welcome",
            title = "Initialize Gradle wrapper",
            status = RunStatus.COMPLETED,
            currentStep = "Wrapper generated successfully (Gradle 8.8)",
            totalSteps = 1
        )
    )

    Scaffold(
        topBar = {
            AgenticAppBar(title = "Agent Manager")
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ACTIVE & HISTORICAL RUNS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(sampleRuns.size) { index ->
                RunCard(run = sampleRuns[index])
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun RunCard(run: AgentRun) {
    val (statusColor, statusIcon) = when (run.status) {
        RunStatus.RUNNING -> BrandPrimary to Icons.Default.PlayArrow
        RunStatus.AWAITING_APPROVAL -> Color(0xFFFFA000) to Icons.Default.HourglassTop
        RunStatus.COMPLETED -> Color(0xFF4CAF50) to Icons.Default.CheckCircle
        else -> MaterialTheme.colorScheme.error to Icons.Default.Warning
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = run.status.name,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                StatusBadge(text = if (run.status == RunStatus.RUNNING) "Active" else "Step ${run.totalSteps}")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = run.currentStep ?: "No details",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (run.status == RunStatus.RUNNING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val approvalMessage = run.pendingApprovalMessage
            if (run.status == RunStatus.AWAITING_APPROVAL && approvalMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = approvalMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA000),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                        Text("Deny")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {}, modifier = Modifier.weight(1f)) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}
