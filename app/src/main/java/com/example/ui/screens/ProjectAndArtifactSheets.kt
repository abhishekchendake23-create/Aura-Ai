package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ArtifactEntity
import com.example.data.local.ProjectEntity
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalAuraColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspacesSheet(
    projects: List<ProjectEntity>,
    activeProjectId: String?,
    currentConversationProjectId: String?,
    onSelectWorkspaceFilter: (String?) -> Unit,
    onAssignCurrentChatToProject: (String?) -> Unit,
    onSaveProject: (id: String?, name: String, desc: String, instructions: String, notes: String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auraColors = LocalAuraColors.current

    var editingProjectId by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var customInstructions by remember { mutableStateOf("") }
    var pinnedNotes by remember { mutableStateOf("") }

    fun openEditor(existing: ProjectEntity?) {
        editingProjectId = existing?.id
        name = existing?.name.orEmpty()
        description = existing?.description.orEmpty()
        customInstructions = existing?.customInstructions.orEmpty()
        pinnedNotes = existing?.pinnedUrlsOrNotes.orEmpty()
        showForm = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderShared,
                        contentDescription = "Project Workspaces",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Claude-Style Project Workspaces",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Isolate chats with persistent project instructions & pinned specs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = { if (showForm) showForm = false else openEditor(null) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("project_new_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    Text(if (showForm) "Close" else "New Project")
                }
            }

            if (showForm) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Project Name") },
                            placeholder = { Text("e.g. Android KMP Architecture") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("project_name_input")
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Short Description") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customInstructions,
                            onValueChange = { customInstructions = it },
                            label = { Text("Project System Instructions") },
                            placeholder = { Text("Architecture conventions, stack rules, coding style...") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("project_instructions_input")
                        )
                        OutlinedTextField(
                            value = pinnedNotes,
                            onValueChange = { pinnedNotes = it },
                            label = { Text("Pinned Specs, URLs & Reference Notes") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    onSaveProject(editingProjectId, name, description, customInstructions, pinnedNotes)
                                    showForm = false
                                }
                            },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("project_save_button")
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text(if (editingProjectId == null) "Create Project Workspace" else "Update Workspace")
                        }
                    }
                }
            }

            // All Chats reset chip
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (activeProjectId == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (activeProjectId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        onSelectWorkspaceFilter(null)
                        onDismiss()
                    }
                    .testTag("project_all_chats_card")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("All Workspaces (Global)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Show every conversation across all projects",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (activeProjectId == null) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            projects.forEach { project ->
                val isActive = project.id == activeProjectId
                val isAssignedToChat = project.id == currentConversationProjectId
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            onSelectWorkspaceFilter(project.id)
                            onDismiss()
                        }
                        .testTag("project_card_${project.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (project.description.isNotBlank()) {
                                    Text(
                                        text = project.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { openEditor(project) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit project")
                                }
                                IconButton(onClick = { onDeleteProject(project.id) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete project",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (project.customInstructions.isNotBlank()) {
                            Text(
                                text = project.customInstructions,
                                style = MaterialTheme.typography.bodySmall,
                                color = auraColors.emerald,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onAssignCurrentChatToProject(if (isAssignedToChat) null else project.id)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isAssignedToChat) "Unlink Current Chat" else "Attach Current Chat")
                            }
                            Button(
                                onClick = {
                                    onSelectWorkspaceFilter(project.id)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isActive) "Active" else "Enter Workspace")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactHubSheet(
    artifacts: List<ArtifactEntity>,
    onDeleteArtifact: (String) -> Unit,
    onAutoFixArtifactError: (errorLog: String, code: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    var previewingArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }
    var filterQuery by remember { mutableStateOf("") }

    val filtered = remember(artifacts, filterQuery) {
        if (filterQuery.isBlank()) artifacts
        else artifacts.filter {
            it.title.contains(filterQuery, ignoreCase = true) ||
                it.language.contains(filterQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Code,
                    contentDescription = "Artifact Hub",
                    tint = auraColors.emerald,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Claude-Grade Artifact Hub",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Versioned interactive web cards, slide decks, CSV charts & modules",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Filter artifacts by filename or language...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("artifact_hub_search")
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (filtered.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No indexed artifacts yet. Ask Aura for HTML widgets, SVG diagrams, Mermaid charts, `/slides` decks, `/csv` data charts, or named `// File:` modules!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                filtered.forEach { art ->
                    val canRun = isPreviewableArtifactLanguage(art.language)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .testTag("artifact_hub_item_${art.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = art.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = auraColors.emeraldContainer
                                        ) {
                                            Text(
                                                text = "v${art.versionNumber}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = auraColors.emerald,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = art.language.uppercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = art.code.lineSequence().take(2).joinToString(" "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = JetBrainsMonoFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDeleteArtifact(art.id) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete artifact",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(art.code)) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp).padding(end = 4.dp))
                                    Text("Copy")
                                }
                                if (canRun) {
                                    Button(
                                        onClick = { previewingArtifact = art },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp).padding(end = 4.dp))
                                        Text("Launch Sandbox")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    previewingArtifact?.let { art ->
        ArtifactPreviewDialog(
            language = art.language,
            code = art.code,
            titleHint = art.title,
            onAutoFixError = { err, broken ->
                previewingArtifact = null
                onDismiss()
                onAutoFixArtifactError(err, broken)
            },
            onDismiss = { previewingArtifact = null }
        )
    }
}
