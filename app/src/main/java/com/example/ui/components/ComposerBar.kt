package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.PersonaEntity
import com.example.data.model.AURA_SLASH_COMMANDS
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
import com.example.data.model.AttachmentDraft
import com.example.data.model.CapabilityMode
import com.example.data.model.SlashCommand
import com.example.ui.theme.LocalAuraColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerBar(
    text: String,
    attachment: AttachmentDraft?,
    selectedModel: AiModelTier,
    selectedCapability: CapabilityMode,
    selectedLanguage: AppLanguage,
    webSearchEnabled: Boolean,
    codeExecutionEnabled: Boolean,
    vaultRagActive: Boolean = false,
    selfVerifyEnabled: Boolean = false,
    councilModeEnabled: Boolean = false,
    autonomousAgentEnabled: Boolean = false,
    compareArenaEnabled: Boolean = false,
    jsonSchemaActive: Boolean = false,
    activeProjectName: String? = null,
    enabledWebhookCount: Int = 0,
    liveVoiceReadoutEnabled: Boolean = false,
    isStreaming: Boolean,
    onTextChange: (String) -> Unit,
    onToggleDeepSearch: () -> Unit,
    onToggleDeepThink: () -> Unit,
    onOpenToolsSheet: () -> Unit,
    onApplySlashCommand: (SlashCommand) -> Unit,
    onPickImage: () -> Unit,
    onOpenLiveVoice: () -> Unit,
    onClearAttachment: () -> Unit,
    onClearImagineMode: () -> Unit,
    onClearSandboxMode: () -> Unit,
    onClearSelfVerify: () -> Unit = {},
    onClearCouncilMode: () -> Unit = {},
    onClearAutonomousAgent: () -> Unit = {},
    onClearCompareArena: () -> Unit = {},
    onOpenWorkbench: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenWebhooks: () -> Unit = {},
    onClearLiveVoiceReadout: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current
    val canSend = text.isNotBlank() || attachment != null
    val isImageActive = selectedModel.isImageModel
    val isThinkActive = selectedCapability == CapabilityMode.DEEP_THINK

    val trimmedSlash = text.trimStart()
    val slashToken = if (!isImageActive && trimmedSlash.startsWith("/") && !trimmedSlash.contains('\n')) {
        trimmedSlash.substringBefore(' ').lowercase()
    } else null
    val matchingCommands = remember(slashToken) {
        if (slashToken == null) emptyList()
        else if (slashToken == "/") AURA_SLASH_COMMANDS
        else AURA_SLASH_COMMANDS.filter {
            it.trigger.startsWith(slashToken, ignoreCase = true) ||
                it.title.contains(slashToken.drop(1), ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Slash Command Autocomplete Studio (only appears when typing `/`)
        AnimatedVisibility(visible = matchingCommands.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    matchingCommands.take(5).forEach { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onApplySlashCommand(cmd) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("slash_cmd_${cmd.trigger.drop(1)}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = cmd.trigger,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cmd.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = cmd.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Minimal AMOLED Reference Composer Pill (matching clean reference layout)
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Active attachment or special mode indicator inside pill
                AnimatedVisibility(
                    visible = attachment != null || isImageActive || codeExecutionEnabled ||
                        selectedLanguage != AppLanguage.AUTO ||
                        selectedCapability == CapabilityMode.DEEP_RESEARCH || vaultRagActive ||
                        (selfVerifyEnabled && !isImageActive) ||
                        (councilModeEnabled && !isImageActive) ||
                        (autonomousAgentEnabled && !isImageActive) ||
                        (compareArenaEnabled && !isImageActive) ||
                        (jsonSchemaActive && !isImageActive) ||
                        !activeProjectName.isNullOrBlank() ||
                        (enabledWebhookCount > 0 && !isImageActive) ||
                        (liveVoiceReadoutEnabled && !isImageActive)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attachment?.let { draft ->
                            val totalPages = 1 + draft.extraImageUris.size
                            val displayLabel = if (draft.extraImageUris.isNotEmpty() && !draft.displayName.contains("pages")) {
                                "${draft.displayName} (+${draft.extraImageUris.size})"
                            } else if (totalPages > 1 && !draft.displayName.contains("pages")) {
                                "${draft.displayName} ($totalPages pages)"
                            } else {
                                draft.displayName
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (draft.isImage) {
                                    AsyncImage(
                                        model = Uri.parse(draft.uri),
                                        contentDescription = displayLabel,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable(onClick = onClearAttachment)
                                )
                            }
                        }

                        if (isImageActive) {
                            MiniDismissBadge(
                                label = "Imagine",
                                onDismiss = onClearImagineMode
                            )
                        }
                        if (!isImageActive && codeExecutionEnabled) {
                            MiniDismissBadge(
                                label = "Python Lab",
                                accentColor = auraColors.emerald,
                                onDismiss = onClearSandboxMode
                            )
                        }
                        if (!isImageActive && selectedCapability == CapabilityMode.DEEP_RESEARCH) {
                            MiniDismissBadge(
                                label = "Research Agent",
                                onDismiss = onOpenToolsSheet
                            )
                        }
                        if (!isImageActive && vaultRagActive) {
                            MiniDismissBadge(
                                label = "Vault RAG",
                                accentColor = auraColors.emerald,
                                onDismiss = onOpenToolsSheet
                            )
                        }
                        if (!isImageActive && councilModeEnabled) {
                            MiniDismissBadge(
                                label = "Council 3x",
                                accentColor = auraColors.sparkleStart,
                                onDismiss = onClearCouncilMode
                            )
                        }
                        if (!isImageActive && selfVerifyEnabled) {
                            MiniDismissBadge(
                                label = "Self-Verify",
                                accentColor = auraColors.emerald,
                                onDismiss = onClearSelfVerify
                            )
                        }
                        if (!isImageActive && autonomousAgentEnabled) {
                            MiniDismissBadge(
                                label = "Manus Agent",
                                accentColor = auraColors.sparkleStart,
                                onDismiss = onClearAutonomousAgent
                            )
                        }
                        if (!isImageActive && compareArenaEnabled) {
                            MiniDismissBadge(
                                label = "Arena A/B",
                                accentColor = auraColors.emerald,
                                onDismiss = onClearCompareArena
                            )
                        }
                        if (!isImageActive && jsonSchemaActive) {
                            MiniDismissBadge(
                                label = "JSON Schema",
                                accentColor = auraColors.emerald,
                                onDismiss = onOpenWorkbench
                            )
                        }
                        if (!activeProjectName.isNullOrBlank()) {
                            MiniDismissBadge(
                                label = "Project: $activeProjectName",
                                onDismiss = onOpenProjects
                            )
                        }
                        if (!isImageActive && enabledWebhookCount > 0) {
                            MiniDismissBadge(
                                label = "Tools ($enabledWebhookCount)",
                                accentColor = auraColors.emerald,
                                onDismiss = onOpenWebhooks
                            )
                        }
                        if (!isImageActive && liveVoiceReadoutEnabled) {
                            MiniDismissBadge(
                                label = "Auto-Speak",
                                onDismiss = onClearLiveVoiceReadout
                            )
                        }
                        if (selectedLanguage != AppLanguage.AUTO) {
                            MiniDismissBadge(
                                label = selectedLanguage.nativeLabel,
                                onDismiss = onOpenToolsSheet
                            )
                        }
                    }
                }

                // Multi-line Input Field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 148.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (isImageActive) {
                                stringResource(R.string.composer_image_placeholder)
                            } else {
                                stringResource(R.string.composer_placeholder)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("composer_input")
                    )
                }

                // Reference Bottom Bar: [+] [Image] [Deep] [Think]   ---   [LiveVoice] [↑ Send]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onOpenToolsSheet,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("composer_tools_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "More tools, language & modes",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = onPickImage,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("attach_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = stringResource(R.string.cd_attach_photo),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (!isImageActive) {
                            ComposerModePill(
                                icon = Icons.Rounded.Search,
                                label = "Web",
                                selected = webSearchEnabled,
                                onClick = onToggleDeepSearch,
                                tag = "toggle_web_search"
                            )

                            ComposerModePill(
                                icon = Icons.Rounded.Psychology,
                                label = "Think",
                                selected = isThinkActive,
                                onClick = onToggleDeepThink,
                                tag = "toggle_deep_think"
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClick = onOpenLiveVoice)
                                .testTag("live_voice_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = stringResource(R.string.cd_live_voice),
                                tint = auraColors.sparkleStart,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (isStreaming) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .clickable(onClick = onStop)
                                    .testTag("stop_generation_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.cd_stop_generation),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            val sendBg = if (canSend) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh
                            val sendTint = if (canSend) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(sendBg)
                                    .clickable(enabled = canSend, onClick = onSend)
                                    .testTag("send_message_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(R.string.cd_send_message),
                                    tint = sendTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerModePill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MiniDismissBadge(
    label: String,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Disable $label",
            tint = accentColor,
            modifier = Modifier.size(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuraToolsSheet(
    selectedLanguage: AppLanguage,
    selectedCapability: CapabilityMode,
    selectedModel: AiModelTier,
    activePersona: PersonaEntity?,
    codeExecutionEnabled: Boolean,
    selfVerifyEnabled: Boolean = false,
    councilModeEnabled: Boolean = false,
    autonomousAgentEnabled: Boolean = false,
    compareArenaEnabled: Boolean = false,
    jsonSchemaActive: Boolean = false,
    activeProjectName: String? = null,
    artifactCount: Int = 0,
    webhookCount: Int = 0,
    liveVoiceReadoutEnabled: Boolean = false,
    hasMessages: Boolean,
    canEnhancePrompt: Boolean,
    isEnhancingPrompt: Boolean,
    hasEffectiveApiKey: Boolean,
    memoryCount: Int = 0,
    vaultDocCount: Int = 0,
    autopilotCount: Int = 0,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectCapability: (CapabilityMode) -> Unit,
    onToggleImagineMode: () -> Unit,
    onToggleCodeExecution: () -> Unit,
    onToggleSelfVerify: () -> Unit = {},
    onToggleCouncilMode: () -> Unit = {},
    onToggleAutonomousAgent: () -> Unit = {},
    onToggleCompareArena: () -> Unit = {},
    onToggleLiveVoiceReadout: () -> Unit = {},
    onOpenWorkbench: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenArtifactHub: () -> Unit = {},
    onOpenWebhooks: () -> Unit = {},
    onPickFile: () -> Unit,
    onOpenScanner: () -> Unit = {},
    onOpenWhisperAudio: () -> Unit = {},
    onOpenKnowledgeVault: () -> Unit = {},
    onOpenMemoryVault: () -> Unit = {},
    onOpenAutopilot: () -> Unit = {},
    onVoiceDictation: () -> Unit,
    onInsertSlashCommand: () -> Unit,
    onEnhancePrompt: () -> Unit,
    onOpenPersonaStudio: () -> Unit,
    onToggleInChatSearch: () -> Unit,
    onExportThread: () -> Unit,
    onExportThreadPdf: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auraColors = LocalAuraColors.current

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
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Tools & Languages",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Response Language Picker (Auto / Marathi / Hindi / English)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "RESPONSE LANGUAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val active = lang == selectedLanguage
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    onSelectLanguage(lang)
                                    onDismiss()
                                }
                                .testTag("language_option_${lang.id}")
                        ) {
                            Text(
                                text = lang.nativeLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // Capability Modes
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "INTELLIGENCE STYLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapabilityMode.entries.forEach { mode ->
                        val active = mode == selectedCapability
                        val icon = when (mode) {
                            CapabilityMode.STANDARD -> Icons.Rounded.AutoAwesome
                            CapabilityMode.DEEP_THINK -> Icons.Rounded.Psychology
                            CapabilityMode.CODE_ARCHITECT -> Icons.Rounded.Code
                            CapabilityMode.DEEP_RESEARCH -> Icons.Rounded.TravelExplore
                            CapabilityMode.CONCISE -> Icons.Rounded.Tune
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    onSelectCapability(mode)
                                    onDismiss()
                                }
                                .testTag("capability_chip_${mode.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Action Grid Items
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "WORKSPACE ACTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Palette,
                        title = if (selectedModel.isImageModel) "Imagine ON" else "Create Image",
                        highlighted = selectedModel.isImageModel,
                        onClick = {
                            onToggleImagineMode()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_imagine_mode")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Terminal,
                        title = if (codeExecutionEnabled) "Python ON" else "Python Lab",
                        highlighted = codeExecutionEnabled,
                        accent = auraColors.emerald,
                        onClick = {
                            onToggleCodeExecution()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_code_execution")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.DocumentScanner,
                        title = "PDF & Paper Scan",
                        onClick = {
                            onDismiss()
                            onOpenScanner()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_scanner_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Whisper Audio",
                        onClick = {
                            onDismiss()
                            onOpenWhisperAudio()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_whisper_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.FolderShared,
                        title = if (vaultDocCount > 0) "Vault ($vaultDocCount)" else "Doc Vault (RAG)",
                        highlighted = vaultDocCount > 0,
                        accent = auraColors.emerald,
                        onClick = {
                            onDismiss()
                            onOpenKnowledgeVault()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_knowledge_vault_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Psychology,
                        title = if (memoryCount > 0) "Memory ($memoryCount)" else "Memory Vault",
                        highlighted = memoryCount > 0,
                        onClick = {
                            onDismiss()
                            onOpenMemoryVault()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_memory_vault_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Bolt,
                        title = if (autonomousAgentEnabled) "Manus Agent ON" else "Manus Agent",
                        highlighted = autonomousAgentEnabled,
                        accent = auraColors.sparkleStart,
                        onClick = {
                            onToggleAutonomousAgent()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_autonomous_agent")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Verified,
                        title = if (compareArenaEnabled) "Compare A/B ON" else "Compare A/B",
                        highlighted = compareArenaEnabled,
                        accent = auraColors.emerald,
                        onClick = {
                            onToggleCompareArena()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_compare_arena")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Tune,
                        title = if (jsonSchemaActive) "Workbench · JSON" else "Workbench & Prompts",
                        highlighted = jsonSchemaActive,
                        accent = auraColors.emerald,
                        onClick = {
                            onDismiss()
                            onOpenWorkbench()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_workbench_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.FolderShared,
                        title = activeProjectName?.let { "Proj: $it" } ?: "Projects",
                        highlighted = !activeProjectName.isNullOrBlank(),
                        onClick = {
                            onDismiss()
                            onOpenProjects()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_projects_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Code,
                        title = if (artifactCount > 0) "Artifacts ($artifactCount)" else "Artifact Hub",
                        highlighted = artifactCount > 0,
                        accent = auraColors.emerald,
                        onClick = {
                            onDismiss()
                            onOpenArtifactHub()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_artifact_hub_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Public,
                        title = if (webhookCount > 0) "Webhooks ($webhookCount)" else "REST Tools",
                        highlighted = webhookCount > 0,
                        accent = auraColors.emerald,
                        onClick = {
                            onDismiss()
                            onOpenWebhooks()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_webhooks_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Verified,
                        title = if (councilModeEnabled) "Council 3x ON" else "Council 3x",
                        highlighted = councilModeEnabled,
                        accent = auraColors.sparkleStart,
                        onClick = {
                            onToggleCouncilMode()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_council_mode")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Bolt,
                        title = if (autopilotCount > 0) "Autopilot ($autopilotCount)" else "Autopilot",
                        highlighted = autopilotCount > 0,
                        accent = auraColors.emerald,
                        onClick = {
                            onDismiss()
                            onOpenAutopilot()
                        },
                        modifier = Modifier.weight(1f).testTag("tools_autopilot_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.CheckCircle,
                        title = if (selfVerifyEnabled) "Self-Verify ON" else "Self-Verify",
                        highlighted = selfVerifyEnabled,
                        accent = auraColors.emerald,
                        onClick = {
                            onToggleSelfVerify()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_self_verify")
                    )
                    ToolActionCard(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        title = if (liveVoiceReadoutEnabled) "Auto-Speak ON" else "Auto-Speak",
                        highlighted = liveVoiceReadoutEnabled,
                        onClick = {
                            onToggleLiveVoiceReadout()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("toggle_live_voice_readout")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.AttachFile,
                        title = "Files / ZIP",
                        onClick = {
                            onDismiss()
                            onPickFile()
                        },
                        modifier = Modifier.weight(1f).testTag("attach_file_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Mic,
                        title = "Quick Dictate",
                        onClick = {
                            onDismiss()
                            onVoiceDictation()
                        },
                        modifier = Modifier.weight(1f).testTag("voice_input_button")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Code,
                        title = "Slash / Commands",
                        onClick = {
                            onDismiss()
                            onInsertSlashCommand()
                        },
                        modifier = Modifier.weight(1f).testTag("slash_commands_button")
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.AutoAwesome,
                        title = activePersona?.name ?: "Personas",
                        onClick = {
                            onDismiss()
                            onOpenPersonaStudio()
                        },
                        modifier = Modifier.weight(1f).testTag("active_persona_chip")
                    )
                }

                if (canEnhancePrompt) {
                    ToolActionCard(
                        icon = Icons.Rounded.AutoFixHigh,
                        title = if (isEnhancingPrompt) "Enhancing Prompt…" else "Sharpen Draft Prompt with Aura AI",
                        highlighted = true,
                        isBusy = isEnhancingPrompt,
                        onClick = {
                            onEnhancePrompt()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("enhance_prompt_button")
                    )
                }

                if (hasMessages) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolActionCard(
                            icon = Icons.Rounded.Search,
                            title = "Search Chat",
                            onClick = {
                                onDismiss()
                                onToggleInChatSearch()
                            },
                            modifier = Modifier.weight(1f).testTag("chat_search_button")
                        )
                        ToolActionCard(
                            icon = Icons.Rounded.IosShare,
                            title = "Export .md",
                            onClick = {
                                onDismiss()
                                onExportThread()
                            },
                            modifier = Modifier.weight(1f).testTag("export_thread_button")
                        )
                    }
                    ToolActionCard(
                        icon = Icons.Rounded.PictureAsPdf,
                        title = "Export Thread as PDF",
                        onClick = {
                            onDismiss()
                            onExportThreadPdf()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_thread_pdf_button")
                    )
                }

                ToolActionCard(
                    icon = Icons.Rounded.Key,
                    title = if (hasEffectiveApiKey) "API Key & Theme Settings" else "Add & Save API Key",
                    highlighted = !hasEffectiveApiKey,
                    accent = if (!hasEffectiveApiKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tools_api_key_settings_button")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ToolActionCard(
    icon: ImageVector,
    title: String,
    highlighted: Boolean = false,
    isBusy: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (highlighted) accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
