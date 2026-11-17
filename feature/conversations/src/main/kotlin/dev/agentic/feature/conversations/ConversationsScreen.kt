package dev.agentic.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentic.core.data.ConversationRepository
import dev.agentic.core.designsystem.AgenticAppBar
import dev.agentic.core.designsystem.StatusBadge
import dev.agentic.core.model.Conversation
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ConversationsScreen(
    conversationRepository: ConversationRepository,
    onSendPrompt: (suspend (String) -> String)? = null,
    onImportBundleClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val conversations by conversationRepository.getAllConversations().collectAsState(initial = emptyList())
    var selectedConversationId by remember {
        mutableStateOf(
            conversations.firstOrNull()?.id ?: "c367db09-5d84-4822-8946-5d626af85692"
        )
    }

    // Ensure selectedConversationId updates if initial conversations list was empty
    if (selectedConversationId.isBlank() && conversations.isNotEmpty()) {
        selectedConversationId = conversations.first().id
    }

    val activeConversation = conversations.find { it.id == selectedConversationId }
        ?: conversations.firstOrNull()
        ?: Conversation(
            id = "conv-welcome",
            projectId = "proj-default",
            title = "Welcome to Agentic Assistant"
        )

    val messages by conversationRepository.getMessages(activeConversation.id).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            AgenticAppBar(title = "Cascade Conversations") {
                if (onSyncClick != null) {
                    IconButton(onClick = onSyncClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync from Hub",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                StatusBadge(text = "${conversations.size} Synced")
                Spacer(modifier = Modifier.width(8.dp))
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Conversation Selector Bar
            ConversationSelectorBar(
                activeConversation = activeConversation,
                allConversations = conversations,
                isExpanded = isDropdownExpanded,
                onExpandChange = { isDropdownExpanded = it },
                onSelectConversation = { conv ->
                    selectedConversationId = conv.id
                    isDropdownExpanded = false
                },
                onNewConversationClick = {
                    val newId = UUID.randomUUID().toString()
                    val newConv = Conversation(
                        id = newId,
                        projectId = activeConversation.projectId,
                        title = "New Cascade Conversation"
                    )
                    coroutineScope.launch {
                        conversationRepository.upsertConversation(newConv)
                        selectedConversationId = newId
                    }
                    isDropdownExpanded = false
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = false
            ) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    IntroCard(
                        activeConversation = activeConversation,
                        onImportBundleClick = onImportBundleClick
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
                items(messages) { message ->
                    MessageBubble(message = message)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask Cascade or give an instruction...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4,
                    enabled = !isSending
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotBlank() && !isSending) {
                            inputText = ""
                            isSending = true
                            coroutineScope.launch {
                                try {
                                    val userMsg = Message(
                                        id = "msg-${UUID.randomUUID()}",
                                        conversationId = activeConversation.id,
                                        role = Role.USER,
                                        content = trimmed
                                    )
                                    conversationRepository.addMessage(activeConversation.id, userMsg)

                                    if (onSendPrompt != null) {
                                        val reply = onSendPrompt(trimmed)
                                        conversationRepository.addMessage(
                                            activeConversation.id,
                                            Message(
                                                id = "msg-${UUID.randomUUID()}",
                                                conversationId = activeConversation.id,
                                                role = Role.ASSISTANT,
                                                content = reply
                                            )
                                        )
                                    }
                                } finally {
                                    isSending = false
                                }
                            }
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationSelectorBar(
    activeConversation: Conversation,
    allConversations: List<Conversation>,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onNewConversationClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandChange(!isExpanded) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeConversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "${if (activeConversation.stepCount > 0) "${activeConversation.stepCount} steps • " else ""}${activeConversation.status}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Switch Conversation",
                tint = MaterialTheme.colorScheme.outline
            )

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { onExpandChange(false) },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Conversation", fontWeight = FontWeight.Bold)
                        }
                    },
                    onClick = onNewConversationClick
                )

                allConversations.forEach { conv ->
                    val isSelected = conv.id == activeConversation.id
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = conv.title,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${if (conv.stepCount > 0) "${conv.stepCount} steps" else "1 step"} • ${conv.id.take(8)}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        onClick = { onSelectConversation(conv) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard(
    activeConversation: Conversation,
    onImportBundleClick: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Desktop Synchronized Cascade",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Viewing: ${activeConversation.title}\nID: ${activeConversation.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (onImportBundleClick != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onImportBundleClick) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Jetski Bundle")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == Role.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isUser) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = if (isUser) "You" else "Antigravity Agent",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
