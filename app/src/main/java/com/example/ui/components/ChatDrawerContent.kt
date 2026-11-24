package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageSearchHit
import com.example.data.local.ProjectEntity
import com.example.ui.theme.LocalAuraColors

@Composable
fun ChatDrawerContent(
    conversations: List<ConversationEntity>,
    messageSearchHits: List<MessageSearchHit> = emptyList(),
    projects: List<ProjectEntity> = emptyList(),
    activeProjectId: String? = null,
    activeConversationId: String?,
    searchQuery: String,
    bookmarkCount: Int,
    memoryCount: Int = 0,
    vaultDocCount: Int = 0,
    autopilotCount: Int = 0,
    artifactCount: Int = 0,
    webhookCount: Int = 0,
    onSearchQueryChange: (String) -> Unit,
    onSelectActiveProject: (String?) -> Unit = {},
    onNewChat: () -> Unit,
    onSelectConversation: (ConversationEntity) -> Unit,
    onSelectSearchHit: (MessageSearchHit) -> Unit = {},
    onTogglePin: (ConversationEntity) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenImageStudio: () -> Unit = {},
    onOpenAppBuilder: () -> Unit = {},
    onOpenSlides: () -> Unit = {},
    onOpenPersonaStudio: () -> Unit,
    onOpenSavedLibrary: () -> Unit,
    onOpenLiveVoice: () -> Unit,
    onOpenScanner: () -> Unit = {},
    onOpenKnowledgeVault: () -> Unit = {},
    onOpenMemoryVault: () -> Unit = {},
    onOpenAutopilot: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenArtifactHub: () -> Unit = {},
    onOpenWorkbench: () -> Unit = {},
    onOpenWebhooks: () -> Unit = {},
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }

    val pinned = remember(conversations) { conversations.filter { it.isPinned } }
    val recent = remember(conversations) { conversations.filterNot { it.isPinned } }
    val auraColors = LocalAuraColors.current

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.width(304.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Minimal Header: Crisp Orb Left + Pill [Search | Avatar] Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_aura_orb),
                    contentDescription = "Aura AI",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded && searchQuery.isNotEmpty()) {
                                    onSearchQueryChange("")
                                }
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("drawer_search_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search chats",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(auraColors.emerald)
                                .clickable(onClick = onOpenMemoryVault)
                                .testTag("drawer_profile_badge"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = searchExpanded || searchQuery.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            "Search chats & code…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                onSearchQueryChange("")
                                searchExpanded = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close search",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("drawer_search_input")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Scrollable Clean Menu + Recents
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "nav_menu") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.EditNote,
                            label = "New Chat",
                            onClick = onNewChat,
                            tag = "drawer_new_chat_button"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.Image,
                            label = "Image Studio",
                            onClick = onOpenImageStudio,
                            tag = "drawer_image_studio"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.Build,
                            label = "App Builder",
                            onClick = onOpenAppBuilder,
                            tag = "drawer_app_builder"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.Description,
                            label = "Slides",
                            onClick = onOpenSlides,
                            tag = "drawer_slides"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.AutoAwesome,
                            label = "Personas",
                            onClick = onOpenPersonaStudio,
                            tag = "drawer_persona_studio"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.FolderShared,
                            label = if (projects.isNotEmpty()) "Projects (${projects.size})" else "Projects",
                            onClick = onOpenProjects,
                            tag = "drawer_projects_tile"
                        )
                        DrawerCleanMenuItem(
                            icon = Icons.Rounded.Bookmark,
                            label = if (bookmarkCount > 0) "Library ($bookmarkCount)" else "Library",
                            onClick = onOpenSavedLibrary,
                            tag = "drawer_saved_library"
                        )

                        // Expandable Studio & Vault row (keeps drawer airy while all tools stay 1 tap away)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { toolsExpanded = !toolsExpanded }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .testTag("drawer_studio_tools_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Studio & Vault",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 15.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = if (toolsExpanded) "Collapse tools" else "Expand tools",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        AnimatedVisibility(visible = toolsExpanded) {
                            Column(
                                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.GraphicEq,
                                    label = "Live Voice",
                                    onClick = onOpenLiveVoice,
                                    tag = "drawer_live_voice"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.Code,
                                    label = if (artifactCount > 0) "Artifacts ($artifactCount)" else "Artifacts",
                                    onClick = onOpenArtifactHub,
                                    tag = "drawer_artifact_hub"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.Tune,
                                    label = "Workbench",
                                    onClick = onOpenWorkbench,
                                    tag = "drawer_workbench"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.DocumentScanner,
                                    label = "PDF Scanner",
                                    onClick = onOpenScanner,
                                    tag = "drawer_scanner"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.FolderShared,
                                    label = if (vaultDocCount > 0) "Doc Vault ($vaultDocCount)" else "Doc Vault (RAG)",
                                    onClick = onOpenKnowledgeVault,
                                    tag = "drawer_knowledge_vault"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.Psychology,
                                    label = if (memoryCount > 0) "Memory Vault ($memoryCount)" else "Memory Vault",
                                    onClick = onOpenMemoryVault,
                                    tag = "drawer_memory_vault"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.Bolt,
                                    label = if (autopilotCount > 0) "Autopilot ($autopilotCount)" else "Autopilot",
                                    onClick = onOpenAutopilot,
                                    tag = "drawer_autopilot"
                                )
                                DrawerCleanMenuItem(
                                    icon = Icons.Rounded.Public,
                                    label = if (webhookCount > 0) "REST Tools ($webhookCount)" else "REST Tools",
                                    onClick = onOpenWebhooks,
                                    tag = "drawer_webhooks"
                                )
                            }
                        }
                    }

                    if (projects.isNotEmpty() || activeProjectId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (activeProjectId == null) MaterialTheme.colorScheme.surfaceContainerHigh
                                else Color.Transparent,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (activeProjectId == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectActiveProject(null) }
                                    .testTag("drawer_project_all")
                            ) {
                                Text(
                                    text = "All",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            projects.forEach { proj ->
                                val selected = proj.id == activeProjectId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onSelectActiveProject(proj.id) }
                                        .testTag("drawer_project_${proj.id}")
                                ) {
                                    Text(
                                        text = proj.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (searchQuery.isNotBlank() && messageSearchHits.isNotEmpty()) {
                    item(key = "hits_header") {
                        Text(
                            text = "Matches (${messageSearchHits.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    items(messageSearchHits, key = { "hit_${it.messageId}" }) { hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { onSelectSearchHit(hit) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("drawer_search_hit_${hit.messageId}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = hit.conversationTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = hit.content.replace(Regex("\\s+"), " ").trim().take(84),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                if (pinned.isNotEmpty()) {
                    item(key = "pinned_header") {
                        Text(
                            text = "Pinned",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    items(pinned, key = { "pin_${it.id}" }) { convo ->
                        ConversationDrawerRow(
                            conversation = convo,
                            isSelected = convo.id == activeConversationId,
                            onClick = { onSelectConversation(convo) },
                            onTogglePin = { onTogglePin(convo) },
                            onRequestRename = {
                                renameTarget = convo
                                renameText = convo.title
                            },
                            onDelete = { onDeleteConversation(convo.id) }
                        )
                    }
                }

                if (recent.isNotEmpty()) {
                    item(key = "recents_header") {
                        Text(
                            text = "Recents",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    items(recent, key = { it.id }) { convo ->
                        ConversationDrawerRow(
                            conversation = convo,
                            isSelected = convo.id == activeConversationId,
                            onClick = { onSelectConversation(convo) },
                            onTogglePin = { onTogglePin(convo) },
                            onRequestRename = {
                                renameTarget = convo
                                renameText = convo.title
                            },
                            onDelete = { onDeleteConversation(convo.id) }
                        )
                    }
                } else if (conversations.isEmpty() && messageSearchHits.isEmpty()) {
                    item(key = "empty_recents") {
                        Text(
                            text = if (searchQuery.isBlank()) "No recent chats yet" else "No matching chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clean Footer Studio Card (zero fake paywall)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Aura Studio",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "API keys, theme & backups",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.background
                        ),
                        contentPadding = PaddingValues(vertical = 11.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_settings_button")
                    ) {
                        Text(
                            text = "Settings & Keys",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameConversation(target.id, renameText)
                        renameTarget = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DrawerCleanMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConversationDrawerRow(
    conversation: ConversationEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp)
            .testTag("drawer_convo_${conversation.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (conversation.isPinned) Icons.Rounded.PushPin else Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = conversation.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Chat actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (conversation.isPinned) "Unpin" else "Pin chat") },
                    leadingIcon = {
                        Icon(Icons.Outlined.PushPin, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onRequestRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}
