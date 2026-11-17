package com.example.ui.screens

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ui.AppDestination
import com.example.ui.ChatViewModel
import com.example.ui.components.AssistantMessageBlock
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ComposerBar
import com.example.ui.components.GeminiSparkleIcon
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.StreamingAssistantBlock
import com.example.ui.components.UserMessageBubble
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AuraAppRoot(viewModel: ChatViewModel) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val activePersonaId by viewModel.activePersonaId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkedMessages.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val liveVoiceStatus by viewModel.liveVoiceStatus.collectAsStateWithLifecycle()

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
                status = liveVoiceStatus,
                onStartVoice = { viewModel.startLiveVoice() },
                onStopVoice = { viewModel.stopLiveVoice() },
                onSelectVoice = { viewModel.selectVoiceCharacter(it) },
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
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val codeExecutionEnabled by viewModel.codeExecutionEnabled.collectAsStateWithLifecycle()
    val isEnhancingPrompt by viewModel.isEnhancingPrompt.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val activePersona by viewModel.activePersona.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkedMessages.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val savedUserApiKey by viewModel.savedUserApiKey.collectAsStateWithLifecycle()
    val hasEffectiveApiKey by viewModel.hasEffectiveApiKey.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    var showModelSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var isInChatSearchOpen by remember { mutableStateOf(false) }
    var inChatQuery by remember { mutableStateOf("") }

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
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachDocument(uri)
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

    LaunchedEffect(
        messages.size,
        streamingState?.contentText?.length,
        streamingState?.thinkingText?.length
    ) {
        val lastItemIndex = messages.size + if (streamingState != null) 1 else 0
        if (messages.isNotEmpty() || streamingState != null) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawerContent(
                conversations = conversations,
                activeConversationId = activeConversationId,
                searchQuery = searchQuery,
                bookmarkCount = bookmarks.size,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { convo ->
                    viewModel.openConversation(convo)
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
                        modelShortName = selectedModel.shortName,
                        hasMessages = messages.isNotEmpty(),
                        isSearchOpen = isInChatSearchOpen,
                        hasEffectiveApiKey = hasEffectiveApiKey,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenModelSelector = { showModelSheet = true },
                        onOpenApiKeySettings = { showSettingsSheet = true },
                        onToggleSearch = {
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
                                .padding(horizontal = 12.dp, vertical = 4.dp)
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
                    activePersona = activePersona,
                    webSearchEnabled = webSearchEnabled,
                    codeExecutionEnabled = codeExecutionEnabled,
                    isEnhancingPrompt = isEnhancingPrompt,
                    isStreaming = streamingState != null,
                    onTextChange = { viewModel.updateComposerText(it) },
                    onSelectModel = { viewModel.selectModel(it) },
                    onSelectCapability = { viewModel.selectCapability(it) },
                    onToggleWebSearch = { viewModel.toggleWebSearch() },
                    onToggleCodeExecution = { viewModel.toggleCodeExecution() },
                    onEnhancePrompt = { viewModel.enhanceComposerPrompt() },
                    onApplySlashCommand = { viewModel.applySlashCommand(it) },
                    onOpenPersonaStudio = { viewModel.navigateTo(AppDestination.PersonaStudio) },
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf("text/*", "application/json"))
                    },
                    onVoiceInput = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Aura AI")
                        }
                        runCatching { voiceLauncher.launch(intent) }.onFailure {
                            viewModel.showToastMessage("Voice input is unavailable on this device")
                        }
                    },
                    onOpenLiveVoice = { viewModel.navigateTo(AppDestination.LiveVoice) },
                    onClearAttachment = { viewModel.clearAttachment() },
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
                        selectedModel = selectedModel,
                        personas = personas,
                        activePersona = activePersona,
                        webSearchEnabled = webSearchEnabled,
                        codeExecutionEnabled = codeExecutionEnabled,
                        hasEffectiveApiKey = hasEffectiveApiKey,
                        onSelectModel = { viewModel.selectModel(it) },
                        onSelectPersona = { viewModel.selectPersona(it) },
                        onToggleWebSearch = { viewModel.toggleWebSearch() },
                        onToggleCodeExecution = { viewModel.toggleCodeExecution() },
                        onOpenApiKeySettings = { showSettingsSheet = true },
                        onStarterSelected = { starter ->
                            viewModel.sendPrompt(
                                overridePrompt = starter.prompt,
                                overrideModel = starter.suggestedModel,
                                overrideMode = starter.suggestedMode
                            )
                        },
                        onOpenPersonaStudio = { viewModel.navigateTo(AppDestination.PersonaStudio) },
                        onOpenLiveVoice = { viewModel.navigateTo(AppDestination.LiveVoice) }
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
                        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp)
                    ) {
                        items(visibleMessages, key = { it.id }) { msg ->
                            if (msg.role == "user") {
                                UserMessageBubble(message = msg)
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
                                AnimatedVisibility(visible = true) {
                                    StreamingAssistantBlock(streaming = activeStream)
                                }
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

    if (showSettingsSheet) {
        SettingsSheet(
            themeMode = themeMode,
            savedApiKey = savedUserApiKey,
            hasEffectiveApiKey = hasEffectiveApiKey,
            onSelectTheme = { viewModel.setThemeMode(it) },
            onSaveApiKey = { viewModel.saveApiKey(it) },
            onClearAllHistory = { viewModel.clearAllHistory() },
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun AuraTopBar(
    modelShortName: String,
    hasMessages: Boolean,
    isSearchOpen: Boolean,
    hasEffectiveApiKey: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
    onToggleSearch: () -> Unit,
    onExportThread: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
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

        // Clean AMOLED Aura AI Mode Selector Pill
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenApiKeySettings,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("top_api_key_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = "Manage API Key",
                    tint = if (hasEffectiveApiKey) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (hasMessages) {
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search messages in chat",
                        tint = if (isSearchOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onExportThread,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("export_thread_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = "Export chat thread as Markdown",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
}
