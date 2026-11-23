package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.MessageEntity
import com.example.data.model.AiModelTier
import com.example.data.model.CapabilityMode
import com.example.data.repository.AuraPdfExporter
import com.example.ui.AppDestination
import com.example.ui.ChatViewModel
import com.example.ui.components.AssistantMessageBlock
import com.example.ui.components.AuraToolsSheet
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ComposerBar
import com.example.ui.components.GeminiSparkleIcon
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.StreamingAssistantBlock
import com.example.ui.components.UserMessageBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AuraAppRoot(viewModel: ChatViewModel) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val activePersonaId by viewModel.activePersonaId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkedMessages.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val liveVoiceStatus by viewModel.liveVoiceStatus.collectAsStateWithLifecycle()
    val isLiveMicMuted by viewModel.isLiveMicMuted.collectAsStateWithLifecycle()
    val liveVoiceTranscript by viewModel.liveVoiceTranscript.collectAsStateWithLifecycle()
    val liveAudioLevel by viewModel.liveAudioLevel.collectAsStateWithLifecycle()

    when (destination) {
        AppDestination.Chat -> {
            AuraChatWorkspace(viewModel = viewModel)
        }
        AppDestination.PersonaStudio -> {
            PersonaStudioScreen(
                personas = personas,
                activePersonaId = activePersonaId,
                onSelectPersona = { viewModel.selectPersona(it) },
                onCreatePersona = { name, role, desc, prompt, icon ->
                    viewModel.createPersona(name, role, desc, prompt, icon)
                },
                onDeleteCustomPersona = { viewModel.deleteCustomPersona(it) },
                onBack = { viewModel.navigateTo(AppDestination.Chat) }
            )
        }
        AppDestination.SavedLibrary -> {
            SavedLibraryScreen(
                bookmarks = bookmarks,
                onCopy = { viewModel.showToastMessage(it) },
                onRemoveBookmark = { viewModel.toggleBookmark(it) },
                onOpenThread = { convoId -> viewModel.openConversationById(convoId) },
                onBack = { viewModel.navigateTo(AppDestination.Chat) }
            )
        }
        AppDestination.LiveVoice -> {
            LiveVoiceScreen(
                selectedVoice = selectedVoice,
                selectedLanguage = selectedLanguage,
                status = liveVoiceStatus,
                isMuted = isLiveMicMuted,
                liveTranscript = liveVoiceTranscript,
                audioLevel = liveAudioLevel,
                onStartVoice = { viewModel.startLiveVoice() },
                onStopVoice = { viewModel.stopLiveVoice() },
                onToggleMute = { viewModel.toggleLiveMicMute() },
                onSelectVoice = { viewModel.selectVoiceCharacter(it) },
                onSelectLanguage = { viewModel.selectLanguage(it) },
                onSendCameraFrame = { viewModel.sendLiveCameraFrame(it) },
                onBack = { viewModel.navigateTo(AppDestination.Chat) }
            )
        }
    }
}

@Composable
private fun AuraChatWorkspace(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val attachmentDraft by viewModel.attachmentDraft.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val selectedCapability by viewModel.selectedCapability.collectAsStateWithLifecycle()
    val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val codeExecutionEnabled by viewModel.codeExecutionEnabled.collectAsStateWithLifecycle()
    val selfVerifyEnabled by viewModel.selfVerifyEnabled.collectAsStateWithLifecycle()
    val councilModeEnabled by viewModel.councilModeEnabled.collectAsStateWithLifecycle()
    val liveVoiceReadoutEnabled by viewModel.liveVoiceReadoutEnabled.collectAsStateWithLifecycle()
    val isEnhancingPrompt by viewModel.isEnhancingPrompt.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val activePersona by viewModel.activePersona.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val messageSearchHits by viewModel.messageSearchHits.collectAsStateWithLifecycle()
    val autopilotTasks by viewModel.autopilotTasks.collectAsStateWithLifecycle()
    val runningAutopilotTaskId by viewModel.runningAutopilotTaskId.collectAsStateWithLifecycle()
    val autonomousAgentEnabled by viewModel.autonomousAgentEnabled.collectAsStateWithLifecycle()
    val compareArenaEnabled by viewModel.compareArenaEnabled.collectAsStateWithLifecycle()
    val workbenchConfig by viewModel.workbenchConfig.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()
    val promptTemplates by viewModel.promptTemplates.collectAsStateWithLifecycle()
    val customWebhooks by viewModel.customWebhooks.collectAsStateWithLifecycle()
    val currentConvoProjectId = remember(conversations, activeConversationId) {
        conversations.find { it.id == activeConversationId }?.projectId
    }
    val enabledWebhookCount = remember(customWebhooks) {
        customWebhooks.count { it.isEnabled }
    }
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkedMessages.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val savedUserApiKey by viewModel.savedUserApiKey.collectAsStateWithLifecycle()
    val hasEffectiveApiKey by viewModel.hasEffectiveApiKey.collectAsStateWithLifecycle()
    val savedGroqApiKey by viewModel.savedGroqApiKey.collectAsStateWithLifecycle()
    val hasEffectiveGroqApiKey by viewModel.hasEffectiveGroqApiKey.collectAsStateWithLifecycle()
    val savedExaApiKey by viewModel.savedExaApiKey.collectAsStateWithLifecycle()
    val hasEffectiveExaApiKey by viewModel.hasEffectiveExaApiKey.collectAsStateWithLifecycle()
    val savedOpenRouterApiKey by viewModel.savedOpenRouterApiKey.collectAsStateWithLifecycle()
    val hasEffectiveOpenRouterApiKey by viewModel.hasEffectiveOpenRouterApiKey.collectAsStateWithLifecycle()
    val savedDeepSeekApiKey by viewModel.savedDeepSeekApiKey.collectAsStateWithLifecycle()
    val hasEffectiveDeepSeekApiKey by viewModel.hasEffectiveDeepSeekApiKey.collectAsStateWithLifecycle()
    val preferFastGroq by viewModel.preferFastGroq.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val knowledgeDocs by viewModel.knowledgeDocs.collectAsStateWithLifecycle()
    val vaultRagEnabled by viewModel.vaultRagEnabled.collectAsStateWithLifecycle()
    val openScannerTrigger by viewModel.openScannerTrigger.collectAsStateWithLifecycle()
    val openVaultTrigger by viewModel.openVaultTrigger.collectAsStateWithLifecycle()
    val openMemoryTrigger by viewModel.openMemoryTrigger.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    var showModelSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showScannerSheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showKnowledgeVaultSheet by remember { mutableStateOf(false) }
    var showWhisperSheet by remember { mutableStateOf(false) }
    var showAutopilotSheet by remember { mutableStateOf(false) }
    var showWorkbenchSheet by remember { mutableStateOf(false) }
    var showProjectsSheet by remember { mutableStateOf(false) }
    var showArtifactHubSheet by remember { mutableStateOf(false) }
    var showWebhooksSheet by remember { mutableStateOf(false) }
    var isInChatSearchOpen by remember { mutableStateOf(false) }
    var inChatQuery by remember { mutableStateOf("") }
    var editingUserMessage by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(openScannerTrigger) {
        if (openScannerTrigger) {
            showScannerSheet = true
            viewModel.consumeScannerTrigger()
        }
    }
    LaunchedEffect(openVaultTrigger) {
        if (openVaultTrigger) {
            showKnowledgeVaultSheet = true
            viewModel.consumeVaultTrigger()
        }
    }
    LaunchedEffect(openMemoryTrigger) {
        if (openMemoryTrigger) {
            showMemorySheet = true
            viewModel.consumeMemoryTrigger()
        }
    }

    LaunchedEffect(activeConversationId) {
        isInChatSearchOpen = false
        inChatQuery = ""
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.attachImage(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.attachProjectFiles(uris)
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri)
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importBackup(uri)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                viewModel.appendVoiceText(spoken)
            }
        }
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            total == 0 || lastVisible >= total - 2
        }
    }

    LaunchedEffect(messages.size, streamingState?.messageId) {
        val lastItemIndex = messages.size + if (streamingState != null) 1 else 0
        if (messages.isNotEmpty() || streamingState != null) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    LaunchedEffect(
        streamingState?.contentText?.length,
        streamingState?.thinkingText?.length,
        streamingState?.compareAlphaText?.length,
        streamingState?.compareBetaText?.length
    ) {
        if (streamingState != null && isNearBottom) {
            delay(90L)
            val lastItemIndex = messages.size + 1
            listState.scrollToItem(lastItemIndex, scrollOffset = Int.MAX_VALUE / 2)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawerContent(
                conversations = conversations,
                messageSearchHits = messageSearchHits,
                projects = projects,
                activeProjectId = activeProjectId,
                activeConversationId = activeConversationId,
                searchQuery = searchQuery,
                bookmarkCount = bookmarks.size,
                memoryCount = memories.size,
                vaultDocCount = knowledgeDocs.size,
                autopilotCount = autopilotTasks.count { it.isEnabled },
                artifactCount = artifacts.size,
                webhookCount = enabledWebhookCount,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onSelectActiveProject = { viewModel.selectActiveProject(it) },
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { convo ->
                    viewModel.openConversation(convo)
                    scope.launch { drawerState.close() }
                },
                onSelectSearchHit = { hit ->
                    viewModel.openConversationById(hit.conversationId)
                    scope.launch { drawerState.close() }
                },
                onTogglePin = { viewModel.togglePinConversation(it) },
                onRenameConversation = { id, title -> viewModel.renameConversation(id, title) },
                onDeleteConversation = { viewModel.deleteConversation(it) },
                onOpenPersonaStudio = {
                    scope.launch { drawerState.close() }
                    viewModel.navigateTo(AppDestination.PersonaStudio)
                },
                onOpenSavedLibrary = {
                    scope.launch { drawerState.close() }
                    viewModel.navigateTo(AppDestination.SavedLibrary)
                },
                onOpenLiveVoice = {
                    scope.launch { drawerState.close() }
                    viewModel.navigateTo(AppDestination.LiveVoice)
                },
                onOpenScanner = {
                    scope.launch { drawerState.close() }
                    showScannerSheet = true
                },
                onOpenKnowledgeVault = {
                    scope.launch { drawerState.close() }
                    showKnowledgeVaultSheet = true
                },
                onOpenMemoryVault = {
                    scope.launch { drawerState.close() }
                    showMemorySheet = true
                },
                onOpenAutopilot = {
                    scope.launch { drawerState.close() }
                    showAutopilotSheet = true
                },
                onOpenProjects = {
                    scope.launch { drawerState.close() }
                    showProjectsSheet = true
                },
                onOpenArtifactHub = {
                    scope.launch { drawerState.close() }
                    showArtifactHubSheet = true
                },
                onOpenWorkbench = {
                    scope.launch { drawerState.close() }
                    showWorkbenchSheet = true
                },
                onOpenWebhooks = {
                    scope.launch { drawerState.close() }
                    showWebhooksSheet = true
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    showSettingsSheet = true
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AuraTopBar(
                        modelShortName = when {
                            selectedModel.isImageModel -> "Aura Imagine"
                            compareArenaEnabled -> "Aura Arena"
                            else -> "Aura AI"
                        },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenModelSelector = { showModelSheet = true },
                        onNewChat = { viewModel.startNewChat() }
                    )

                    AnimatedVisibility(visible = isInChatSearchOpen && messages.isNotEmpty()) {
                        val trimmedQuery = inChatQuery.trim()
                        val matchCount = remember(messages, trimmedQuery) {
                            if (trimmedQuery.isEmpty()) messages.size
                            else messages.count { it.content.contains(trimmedQuery, ignoreCase = true) }
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    if (inChatQuery.isEmpty()) {
                                        Text(
                                            text = "Search in this chat...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    BasicTextField(
                                        value = inChatQuery,
                                        onValueChange = { inChatQuery = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("in_chat_search_input")
                                    )
                                }
                                if (trimmedQuery.isNotEmpty()) {
                                    Text(
                                        text = "$matchCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (inChatQuery.isNotEmpty()) {
                                            inChatQuery = ""
                                        } else {
                                            isInChatSearchOpen = false
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                ComposerBar(
                    text = composerText,
                    attachment = attachmentDraft,
                    selectedModel = selectedModel,
                    selectedCapability = selectedCapability,
                    selectedLanguage = selectedLanguage,
                    webSearchEnabled = webSearchEnabled,
                    codeExecutionEnabled = codeExecutionEnabled,
                    vaultRagActive = vaultRagEnabled && knowledgeDocs.isNotEmpty(),
                    selfVerifyEnabled = selfVerifyEnabled,
                    councilModeEnabled = councilModeEnabled,
                    autonomousAgentEnabled = autonomousAgentEnabled,
                    compareArenaEnabled = compareArenaEnabled,
                    jsonSchemaActive = workbenchConfig.jsonSchemaMode,
                    activeProjectName = activeProject?.name,
                    enabledWebhookCount = enabledWebhookCount,
                    liveVoiceReadoutEnabled = liveVoiceReadoutEnabled,
                    isStreaming = streamingState != null,
                    onTextChange = { viewModel.updateComposerText(it) },
                    onToggleDeepSearch = { viewModel.toggleWebSearch() },
                    onToggleDeepThink = {
                        val nextMode = if (selectedCapability == CapabilityMode.DEEP_THINK) {
                            CapabilityMode.STANDARD
                        } else {
                            CapabilityMode.DEEP_THINK
                        }
                        viewModel.selectCapability(nextMode)
                    },
                    onOpenToolsSheet = { showToolsSheet = true },
                    onApplySlashCommand = { viewModel.applySlashCommand(it) },
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenLiveVoice = { viewModel.navigateTo(AppDestination.LiveVoice) },
                    onClearAttachment = { viewModel.clearAttachment() },
                    onClearImagineMode = { viewModel.selectModel(AiModelTier.GEMINI_3_8_FLASH) },
                    onClearSandboxMode = { viewModel.toggleCodeExecution() },
                    onClearSelfVerify = { viewModel.toggleSelfVerify() },
                    onClearCouncilMode = { viewModel.toggleCouncilMode() },
                    onClearAutonomousAgent = { viewModel.toggleAutonomousAgent() },
                    onClearCompareArena = { viewModel.toggleCompareArena() },
                    onOpenWorkbench = { showWorkbenchSheet = true },
                    onOpenProjects = { showProjectsSheet = true },
                    onOpenWebhooks = { showWebhooksSheet = true },
                    onClearLiveVoiceReadout = { viewModel.toggleLiveVoiceReadout() },
                    onSend = { viewModel.sendPrompt() },
                    onStop = { viewModel.stopGeneration() }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                if (messages.isEmpty() && streamingState == null) {
                    WelcomeEmptyState(
                        selectedLanguage = selectedLanguage,
                        hasEffectiveApiKey = hasEffectiveApiKey,
                        onSelectLanguage = { viewModel.selectLanguage(it) },
                        onOpenApiKeySettings = { showSettingsSheet = true },
                        onStarterSelected = { starter ->
                            viewModel.sendPrompt(
                                overridePrompt = starter.prompt,
                                overrideModel = starter.suggestedModel,
                                overrideMode = starter.suggestedMode
                            )
                        },
                        onOpenPersonaStudio = { viewModel.navigateTo(AppDestination.PersonaStudio) },
                        onOpenLiveVoice = { viewModel.navigateTo(AppDestination.LiveVoice) },
                        onOpenToolsSheet = { showToolsSheet = true }
                    )
                } else {
                    val lastAssistantId = remember(messages) {
                        messages.lastOrNull { it.role == "assistant" }?.id
                    }
                    val visibleMessages = remember(messages, isInChatSearchOpen, inChatQuery) {
                        val q = inChatQuery.trim()
                        if (!isInChatSearchOpen || q.isEmpty()) messages
                        else messages.filter { it.content.contains(q, ignoreCase = true) }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp)
                    ) {
                        items(visibleMessages, key = { it.id }) { msg ->
                            if (msg.role == "user") {
                                UserMessageBubble(
                                    message = msg,
                                    onEditMessage = { editingUserMessage = it },
                                    onTogglePin = { viewModel.toggleMessagePinned(it) }
                                )
                            } else {
                                AssistantMessageBlock(
                                    message = msg,
                                    isSpeaking = speakingMessageId == msg.id,
                                    isLatestAssistantMessage = streamingState == null && msg.id == lastAssistantId,
                                    onToggleSpeak = { viewModel.toggleSpeakMessage(msg) },
                                    onRegenerate = { viewModel.regenerateResponse(msg) },
                                    onToggleBookmark = { viewModel.toggleBookmark(msg) },
                                    onEditGeneratedImage = { path ->
                                        viewModel.attachGeneratedImageForEdit(path)
                                    },
                                    onFollowUpPrompt = { followUp ->
                                        viewModel.sendPrompt(overridePrompt = followUp)
                                    },
                                    onSaveToMemory = { text ->
                                        viewModel.addMemory(
                                            content = text.replace(Regex("\\s+"), " ").trim().take(220),
                                            category = "Saved Insight"
                                        )
                                    },
                                    onTogglePin = { viewModel.toggleMessagePinned(msg) },
                                    onSelectVariant = { idx -> viewModel.switchAssistantVariant(msg, idx) },
                                    onLockArenaWinner = { idx -> viewModel.lockArenaWinner(msg, idx) },
                                    onForkHere = { viewModel.forkConversationFromMessage(msg) },
                                    onExportPdf = {
                                        scope.launch {
                                            runCatching {
                                                val uri = withContext(Dispatchers.IO) {
                                                    AuraPdfExporter.exportMessageToPdf(context, msg)
                                                }
                                                sharePdfUri(context, uri, "Export Aura AI PDF")
                                            }.onFailure { err ->
                                                viewModel.showToastMessage(err.localizedMessage ?: "PDF export failed")
                                            }
                                        }
                                    },
                                    onAutoFixArtifactError = { errLog, code ->
                                        viewModel.autoFixArtifactCode(errLog, code)
                                    },
                                    onShare = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg.content)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share Aura AI Response")
                                        )
                                    }
                                )
                            }
                        }

                        streamingState?.let { activeStream ->
                            item(key = "streaming_${activeStream.messageId}") {
                                StreamingAssistantBlock(streaming = activeStream)
                            }
                        }

                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelSelectorSheet(
            selectedModel = selectedModel,
            onSelectModel = { viewModel.selectModel(it) },
            onDismiss = { showModelSheet = false }
        )
    }

    if (showToolsSheet) {
        AuraToolsSheet(
            selectedLanguage = selectedLanguage,
            selectedCapability = selectedCapability,
            selectedModel = selectedModel,
            activePersona = activePersona,
            codeExecutionEnabled = codeExecutionEnabled,
            selfVerifyEnabled = selfVerifyEnabled,
            councilModeEnabled = councilModeEnabled,
            autonomousAgentEnabled = autonomousAgentEnabled,
            compareArenaEnabled = compareArenaEnabled,
            jsonSchemaActive = workbenchConfig.jsonSchemaMode,
            activeProjectName = activeProject?.name,
            artifactCount = artifacts.size,
            webhookCount = enabledWebhookCount,
            liveVoiceReadoutEnabled = liveVoiceReadoutEnabled,
            hasMessages = messages.isNotEmpty(),
            canEnhancePrompt = composerText.trim().length >= 4 && streamingState == null,
            isEnhancingPrompt = isEnhancingPrompt,
            hasEffectiveApiKey = hasEffectiveApiKey,
            memoryCount = memories.size,
            vaultDocCount = knowledgeDocs.size,
            autopilotCount = autopilotTasks.count { it.isEnabled },
            onSelectLanguage = { viewModel.selectLanguage(it) },
            onSelectCapability = { viewModel.selectCapability(it) },
            onToggleImagineMode = {
                if (selectedModel.isImageModel) {
                    viewModel.selectModel(AiModelTier.GEMINI_3_8_FLASH)
                } else {
                    viewModel.selectModel(AiModelTier.NANO_BANANA_2_1)
                }
            },
            onToggleCodeExecution = { viewModel.toggleCodeExecution() },
            onToggleSelfVerify = { viewModel.toggleSelfVerify() },
            onToggleCouncilMode = { viewModel.toggleCouncilMode() },
            onToggleAutonomousAgent = { viewModel.toggleAutonomousAgent() },
            onToggleCompareArena = { viewModel.toggleCompareArena() },
            onToggleLiveVoiceReadout = { viewModel.toggleLiveVoiceReadout() },
            onOpenWorkbench = { showWorkbenchSheet = true },
            onOpenProjects = { showProjectsSheet = true },
            onOpenArtifactHub = { showArtifactHubSheet = true },
            onOpenWebhooks = { showWebhooksSheet = true },
            onPickFile = {
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onOpenScanner = { showScannerSheet = true },
            onOpenWhisperAudio = { showWhisperSheet = true },
            onOpenKnowledgeVault = { showKnowledgeVaultSheet = true },
            onOpenMemoryVault = { showMemorySheet = true },
            onOpenAutopilot = { showAutopilotSheet = true },
            onVoiceDictation = {
                val speechLang = if (selectedLanguage.speechLocaleTag.isNotBlank()) {
                    selectedLanguage.speechLocaleTag
                } else {
                    Locale.getDefault().toLanguageTag()
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Aura AI")
                }
                runCatching { voiceLauncher.launch(intent) }.onFailure {
                    viewModel.showToastMessage("Voice input is unavailable on this device")
                }
            },
            onInsertSlashCommand = {
                val cur = composerText.trimStart()
                if (!cur.startsWith("/")) {
                    viewModel.updateComposerText(if (composerText.isBlank()) "/" else "/$composerText")
                }
            },
            onEnhancePrompt = { viewModel.enhanceComposerPrompt() },
            onOpenPersonaStudio = { viewModel.navigateTo(AppDestination.PersonaStudio) },
            onToggleInChatSearch = {
                isInChatSearchOpen = !isInChatSearchOpen
                if (!isInChatSearchOpen) inChatQuery = ""
            },
            onExportThread = {
                val md = viewModel.buildExportMarkdown(messages)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Aura AI Thread Export")
                    putExtra(Intent.EXTRA_TEXT, md)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, "Export Aura AI Thread (.md)")
                )
            },
            onExportThreadPdf = {
                scope.launch {
                    runCatching {
                        val uri = withContext(Dispatchers.IO) {
                            AuraPdfExporter.exportThreadToPdf(context, messages)
                        }
                        sharePdfUri(context, uri, "Export Aura AI Thread PDF")
                    }.onFailure { err ->
                        viewModel.showToastMessage(err.localizedMessage ?: "PDF export failed")
                    }
                }
            },
            onOpenSettings = { showSettingsSheet = true },
            onDismiss = { showToolsSheet = false }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            themeMode = themeMode,
            selectedLanguage = selectedLanguage,
            savedApiKey = savedUserApiKey,
            hasEffectiveApiKey = hasEffectiveApiKey,
            savedGroqApiKey = savedGroqApiKey,
            hasEffectiveGroqApiKey = hasEffectiveGroqApiKey,
            preferFastGroq = preferFastGroq,
            savedExaApiKey = savedExaApiKey,
            hasEffectiveExaApiKey = hasEffectiveExaApiKey,
            savedOpenRouterApiKey = savedOpenRouterApiKey,
            hasEffectiveOpenRouterApiKey = hasEffectiveOpenRouterApiKey,
            savedDeepSeekApiKey = savedDeepSeekApiKey,
            hasEffectiveDeepSeekApiKey = hasEffectiveDeepSeekApiKey,
            onSelectTheme = { viewModel.setThemeMode(it) },
            onSelectLanguage = { viewModel.selectLanguage(it) },
            onSaveApiKey = { viewModel.saveApiKey(it) },
            onSaveGroqApiKey = { viewModel.saveGroqApiKey(it) },
            onSaveExaApiKey = { viewModel.saveExaApiKey(it) },
            onSaveOpenRouterApiKey = { viewModel.saveOpenRouterApiKey(it) },
            onSaveDeepSeekApiKey = { viewModel.saveDeepSeekApiKey(it) },
            onSetPreferFastGroq = { viewModel.setPreferFastGroq(it) },
            onExportBackup = {
                exportBackupLauncher.launch("aura_ai_backup_${System.currentTimeMillis()}.json")
            },
            onImportBackup = {
                importBackupLauncher.launch(arrayOf("application/json"))
            },
            onClearAllHistory = { viewModel.clearAllHistory() },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showScannerSheet) {
        ScannerSheet(
            onSolvePaper = { draft, prompt ->
                viewModel.sendPrompt(
                    overridePrompt = prompt,
                    overrideMode = CapabilityMode.DEEP_THINK,
                    overrideDraft = draft
                )
            },
            onSaveToVault = { title, sourceType, content ->
                viewModel.indexDocumentToVault(title, sourceType, content)
            },
            onDismiss = { showScannerSheet = false }
        )
    }

    if (showMemorySheet) {
        MemoryVaultSheet(
            memories = memories,
            onAddMemory = { content, category -> viewModel.addMemory(content, category) },
            onToggleMemory = { viewModel.toggleMemory(it) },
            onDeleteMemory = { viewModel.deleteMemory(it) },
            onDismiss = { showMemorySheet = false }
        )
    }

    if (showKnowledgeVaultSheet) {
        KnowledgeVaultSheet(
            docs = knowledgeDocs,
            vaultRagEnabled = vaultRagEnabled,
            onToggleVaultRag = { viewModel.setVaultRagEnabled(it) },
            onIndexDocument = { title, sourceType, content ->
                viewModel.indexDocumentToVault(title, sourceType, content)
            },
            onDeleteDoc = { viewModel.deleteKnowledgeDoc(it) },
            onDismiss = { showKnowledgeVaultSheet = false }
        )
    }

    if (showWhisperSheet) {
        WhisperAudioSheet(
            groqApiKeyProvider = { viewModel.getEffectiveGroqKeySuspend() },
            selectedLanguage = selectedLanguage,
            onInsertTranscript = { transcript -> viewModel.appendVoiceText(transcript) },
            onRunPromptWithTranscript = { prompt -> viewModel.sendPrompt(overridePrompt = prompt) },
            onDismiss = { showWhisperSheet = false }
        )
    }

    if (showAutopilotSheet) {
        AutopilotSheet(
            tasks = autopilotTasks,
            runningTaskId = runningAutopilotTaskId,
            onCreateTask = { title, prompt, intervalHours, useWebSearch ->
                viewModel.createAutopilotTask(title, prompt, intervalHours, useWebSearch)
            },
            onToggleTask = { task -> viewModel.toggleAutopilotTask(task) },
            onRunTaskNow = { task ->
                showAutopilotSheet = false
                viewModel.runAutopilotTaskNow(task)
            },
            onOpenConversation = { convoId ->
                showAutopilotSheet = false
                viewModel.openConversationById(convoId)
            },
            onDeleteTask = { id -> viewModel.deleteAutopilotTask(id) },
            onDismiss = { showAutopilotSheet = false }
        )
    }

    if (showWorkbenchSheet) {
        WorkbenchSheet(
            config = workbenchConfig,
            templates = promptTemplates,
            onUpdateTemperature = { viewModel.setWorkbenchTemperature(it) },
            onUpdateTopP = { viewModel.setWorkbenchTopP(it) },
            onUpdateMaxTokens = { viewModel.setWorkbenchMaxTokens(it) },
            onUpdateThinkingBudget = { viewModel.setWorkbenchThinkingBudget(it) },
            onUpdateJsonSchema = { enabled, schema -> viewModel.setWorkbenchJsonSchema(enabled, schema) },
            onUpdateSystemOverride = { viewModel.setWorkbenchSystemOverride(it) },
            onResetTuning = { viewModel.resetWorkbenchTuning() },
            onApplyTemplate = { tpl, vars, runNow ->
                viewModel.applyPromptTemplate(tpl, vars, runNow)
                showWorkbenchSheet = false
            },
            onCreateTemplate = { title, cat, sys, usr ->
                viewModel.createPromptTemplate(title, cat, sys, usr)
            },
            onDeleteTemplate = { viewModel.deletePromptTemplate(it) },
            onDismiss = { showWorkbenchSheet = false }
        )
    }

    if (showProjectsSheet) {
        ProjectWorkspacesSheet(
            projects = projects,
            activeProjectId = activeProjectId,
            currentConversationProjectId = if (activeConversationId != null) currentConvoProjectId else activeProjectId,
            onSelectWorkspaceFilter = { viewModel.selectActiveProject(it) },
            onAssignCurrentChatToProject = { viewModel.assignCurrentChatToProject(it) },
            onSaveProject = { id, name, desc, instr, notes ->
                viewModel.saveProject(id, name, desc, instr, notes)
            },
            onDeleteProject = { viewModel.deleteProject(it) },
            onDismiss = { showProjectsSheet = false }
        )
    }

    if (showArtifactHubSheet) {
        ArtifactHubSheet(
            artifacts = artifacts,
            onDeleteArtifact = { viewModel.deleteArtifact(it) },
            onAutoFixArtifactError = { errLog, code ->
                showArtifactHubSheet = false
                viewModel.autoFixArtifactCode(errLog, code)
            },
            onDismiss = { showArtifactHubSheet = false }
        )
    }

    if (showWebhooksSheet) {
        WebhookToolsSheet(
            webhooks = customWebhooks,
            onCreateWebhook = { name, desc, url, method, headers, body ->
                viewModel.createCustomWebhook(name, desc, url, method, headers, body)
            },
            onToggleWebhook = { id, enabled -> viewModel.toggleCustomWebhook(id, enabled) },
            onDeleteWebhook = { viewModel.deleteCustomWebhook(it) },
            onTestWebhook = { hook, query -> viewModel.testWebhook(hook, query) },
            onDismiss = { showWebhooksSheet = false }
        )
    }

    editingUserMessage?.let { targetMsg ->
        var editedText by remember(targetMsg.id) { mutableStateOf(targetMsg.content) }
        AlertDialog(
            onDismissRequest = { editingUserMessage = null },
            title = { Text("Edit & Re-Ask Aura AI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Updating this prompt re-branches the conversation from here with a fresh response.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_user_message_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = editedText.isNotBlank(),
                    onClick = {
                        viewModel.editUserMessageAndRegenerate(targetMsg, editedText)
                        editingUserMessage = null
                    },
                    modifier = Modifier.testTag("confirm_edit_user_message")
                ) {
                    Text("Save & Ask")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingUserMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun sharePdfUri(context: android.content.Context, uri: Uri, chooserTitle: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
}

@Composable
private fun AuraTopBar(
    modelShortName: String,
    onOpenDrawer: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("top_open_drawer_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = stringResource(R.string.cd_open_drawer),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(22.dp)
                )
                .clickable(onClick = onOpenModelSelector)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("model_selector_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GeminiSparkleIcon(size = 16.dp)
            Text(
                text = modelShortName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Switch Aura AI mode",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onNewChat,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("top_new_chat_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.EditNote,
                contentDescription = stringResource(R.string.cd_new_chat),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
