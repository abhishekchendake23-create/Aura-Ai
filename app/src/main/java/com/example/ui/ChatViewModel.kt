package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AuraDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
import com.example.data.model.AttachmentDraft
import com.example.data.model.AuraVoiceCharacter
import com.example.data.model.CapabilityMode
import com.example.data.model.SlashCommand
import com.example.data.model.SlashCommandSpecialAction
import com.example.data.model.ThemeMode
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.AiRepository
import com.example.data.repository.AiRequest
import com.example.data.repository.AiStreamEvent
import com.example.data.repository.BackupRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.DirectGeminiAiRepository
import com.example.data.repository.KnowledgeRagEngine
import com.example.data.repository.LiveVoiceManager
import com.example.data.repository.LiveVoiceStatus
import com.example.data.repository.serializeSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface AppDestination {
    data object Chat : AppDestination
    data object PersonaStudio : AppDestination
    data object SavedLibrary : AppDestination
    data object LiveVoice : AppDestination
}

data class StreamingMessageState(
    val messageId: String,
    val conversationId: String,
    val thinkingText: String,
    val thinkingDurationMs: Long,
    val contentText: String,
    val modelDisplayName: String,
    val isThinkingPhase: Boolean,
    val isGeneratingImage: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AuraDatabase.getInstance(application)
    private val chatRepository = ChatRepository(database.auraDao())
    private val backupRepository = BackupRepository(database.auraDao(), application.applicationContext)
    private val aiRepository: AiRepository = DirectGeminiAiRepository(application.applicationContext)
    private val liveVoiceManager = LiveVoiceManager(application.applicationContext)
    private val prefs = PreferencesManager(application)

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Chat)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _attachmentDraft = MutableStateFlow<AttachmentDraft?>(null)
    val attachmentDraft: StateFlow<AttachmentDraft?> = _attachmentDraft.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _streamingState = MutableStateFlow<StreamingMessageState?>(null)
    val streamingState: StateFlow<StreamingMessageState?> = _streamingState.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _liveVoiceStatus = MutableStateFlow<LiveVoiceStatus>(LiveVoiceStatus.Idle)
    val liveVoiceStatus: StateFlow<LiveVoiceStatus> = _liveVoiceStatus.asStateFlow()

    private val _isLiveMicMuted = MutableStateFlow(false)
    val isLiveMicMuted: StateFlow<Boolean> = _isLiveMicMuted.asStateFlow()

    private val _liveVoiceTranscript = MutableStateFlow("")
    val liveVoiceTranscript: StateFlow<String> = _liveVoiceTranscript.asStateFlow()

    private val _liveAudioLevel = MutableStateFlow(0f)
    val liveAudioLevel: StateFlow<Float> = _liveAudioLevel.asStateFlow()

    private val _isEnhancingPrompt = MutableStateFlow(false)
    val isEnhancingPrompt: StateFlow<Boolean> = _isEnhancingPrompt.asStateFlow()

    private val _openScannerTrigger = MutableStateFlow(false)
    val openScannerTrigger: StateFlow<Boolean> = _openScannerTrigger.asStateFlow()

    private val _openVaultTrigger = MutableStateFlow(false)
    val openVaultTrigger: StateFlow<Boolean> = _openVaultTrigger.asStateFlow()

    private val _openMemoryTrigger = MutableStateFlow(false)
    val openMemoryTrigger: StateFlow<Boolean> = _openMemoryTrigger.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var liveVoiceJob: Job? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ThemeMode.DARK
    )

    val selectedModel: StateFlow<AiModelTier> = prefs.defaultModel.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiModelTier.GEMINI_3_8_FLASH
    )

    val selectedCapability: StateFlow<CapabilityMode> = prefs.defaultCapability.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CapabilityMode.STANDARD
    )

    val webSearchEnabled: StateFlow<Boolean> = prefs.webSearchEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val codeExecutionEnabled: StateFlow<Boolean> = prefs.codeExecutionEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val activePersonaId: StateFlow<String> = prefs.activePersonaId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "persona_general"
    )

    val selectedVoice: StateFlow<AuraVoiceCharacter> = prefs.voiceCharacter.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ALL_30_VOICE_CHARACTERS.first()
    )

    val selectedLanguage: StateFlow<AppLanguage> = prefs.appLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppLanguage.AUTO
    )

    val savedUserApiKey: StateFlow<String> = prefs.userApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val savedGroqApiKey: StateFlow<String> = prefs.userGroqApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val savedExaApiKey: StateFlow<String> = prefs.userExaApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val preferFastGroq: StateFlow<Boolean> = prefs.preferFastGroq.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val hasEffectiveGroqApiKey: StateFlow<Boolean> = prefs.userGroqApiKey
        .let { groqFlow ->
            combine(groqFlow) { values ->
                values[0].isNotBlank() || PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank()
        )

    val hasEffectiveExaApiKey: StateFlow<Boolean> = prefs.userExaApiKey
        .let { exaFlow ->
            combine(exaFlow) { values ->
                values[0].isNotBlank() || PreferencesManager.resolveBuildConfigExaApiKey().isNotBlank()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.resolveBuildConfigExaApiKey().isNotBlank()
        )

    val hasEffectiveApiKey: StateFlow<Boolean> = combine(
        prefs.userApiKey,
        prefs.userGroqApiKey
    ) { geminiSaved, groqSaved ->
        geminiSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigApiKey().isNotBlank() ||
            groqSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PreferencesManager.resolveBuildConfigApiKey().isNotBlank() ||
            PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank()
    )

    val personas: StateFlow<List<PersonaEntity>> = chatRepository.observePersonas().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activePersona: StateFlow<PersonaEntity?> = combine(personas, activePersonaId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val conversations: StateFlow<List<ConversationEntity>> =
        combine(chatRepository.observeConversations(), _searchQuery) { list, query ->
            if (query.isBlank()) list
            else list.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val currentMessages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val bookmarkedMessages: StateFlow<List<MessageEntity>> =
        chatRepository.observeBookmarkedMessages().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val vaultRagEnabled: StateFlow<Boolean> = prefs.vaultRagEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val memories: StateFlow<List<MemoryEntity>> = chatRepository.observeMemories().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val knowledgeDocs: StateFlow<List<KnowledgeDocEntity>> = chatRepository.observeKnowledgeDocs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            chatRepository.ensureDefaultPersonas()
        }
        initTts(application)
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                isTtsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        _speakingMessageId.value = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speakingMessageId.value = null
                    }
                })
            }
        }
    }

    fun navigateTo(dest: AppDestination) {
        if (dest != AppDestination.LiveVoice && _destination.value == AppDestination.LiveVoice) {
            stopLiveVoice()
        }
        _destination.value = dest
    }

    fun startLiveVoice(voiceOverride: AuraVoiceCharacter? = null) {
        stopSpeaking()
        val chosenVoice = voiceOverride ?: selectedVoice.value
        val persona = activePersona.value
        val language = selectedLanguage.value
        _isLiveMicMuted.value = false
        _liveVoiceTranscript.value = ""
        _liveAudioLevel.value = 0f
        liveVoiceJob?.cancel()
        liveVoiceJob = viewModelScope.launch {
            liveVoiceManager.startConversation(
                voice = chosenVoice,
                persona = persona,
                language = language,
                onStatusChanged = { status ->
                    _liveVoiceStatus.value = status
                },
                onTranscriptUpdate = { text ->
                    _liveVoiceTranscript.value = text
                },
                onAudioLevel = { level ->
                    _liveAudioLevel.value = level
                }
            )
        }
    }

    fun toggleLiveMicMute() {
        val next = !_isLiveMicMuted.value
        _isLiveMicMuted.value = next
        liveVoiceManager.setMuted(next)
    }

    fun sendLiveCameraFrame(jpegBytes: ByteArray) {
        liveVoiceManager.sendCameraFrame(jpegBytes)
    }

    suspend fun getEffectiveGroqKeySuspend(): String = prefs.getEffectiveGroqApiKey()

    fun consumeScannerTrigger() { _openScannerTrigger.value = false }
    fun consumeVaultTrigger() { _openVaultTrigger.value = false }
    fun consumeMemoryTrigger() { _openMemoryTrigger.value = false }

    fun triggerOpenScanner() { _openScannerTrigger.value = true }

    fun stopLiveVoice() {
        liveVoiceJob?.cancel()
        liveVoiceJob = null
        _isLiveMicMuted.value = false
        _liveAudioLevel.value = 0f
        viewModelScope.launch {
            liveVoiceManager.stopConversation()
            _liveVoiceStatus.value = LiveVoiceStatus.Idle
        }
    }

    fun selectVoiceCharacter(voice: AuraVoiceCharacter) {
        viewModelScope.launch {
            prefs.setVoiceCharacter(voice.apiName)
            if (_destination.value == AppDestination.LiveVoice &&
                (_liveVoiceStatus.value is LiveVoiceStatus.Active || _liveVoiceStatus.value is LiveVoiceStatus.Connecting)
            ) {
                startLiveVoice(voiceOverride = voice)
            }
        }
    }

    fun selectLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefs.setAppLanguage(language)
            _bannerMessage.value = "Language: ${language.nativeLabel}"
            if (_destination.value == AppDestination.LiveVoice &&
                (_liveVoiceStatus.value is LiveVoiceStatus.Active || _liveVoiceStatus.value is LiveVoiceStatus.Connecting)
            ) {
                startLiveVoice()
            }
        }
    }

    fun updateComposerText(text: String) {
        _composerText.value = text
    }

    fun appendVoiceText(spoken: String) {
        val current = _composerText.value
        _composerText.value = if (current.isBlank()) spoken else "$current $spoken"
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    fun showToastMessage(msg: String) {
        _bannerMessage.value = msg
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun saveApiKey(rawKey: String) {
        viewModelScope.launch {
            val cleaned = rawKey.trim()
            prefs.setUserApiKey(cleaned)
            _bannerMessage.value = if (cleaned.isNotBlank()) {
                "Primary API Key saved on device"
            } else {
                "Saved Primary API Key cleared"
            }
        }
    }

    fun saveGroqApiKey(rawKey: String) {
        viewModelScope.launch {
            val cleaned = rawKey.trim()
            prefs.setUserGroqApiKey(cleaned)
            _bannerMessage.value = if (cleaned.isNotBlank()) {
                "Groq Fast Key saved — ultra-fast mode & fallback active"
            } else {
                "Saved Groq Fast Key cleared"
            }
        }
    }

    fun saveExaApiKey(rawKey: String) {
        viewModelScope.launch {
            val cleaned = rawKey.trim()
            prefs.setUserExaApiKey(cleaned)
            _bannerMessage.value = if (cleaned.isNotBlank()) {
                "Exa Live Search API key saved — neural live web active"
            } else {
                "Saved Exa API key cleared"
            }
        }
    }

    fun setPreferFastGroq(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setPreferFastGroq(enabled)
            _bannerMessage.value = if (enabled) {
                "Fast Speed Priority ON (Groq first, cloud fallback)"
            } else {
                "Fast Speed Priority OFF (Groq fallback still active)"
            }
        }
    }

    fun selectModel(model: AiModelTier) {
        viewModelScope.launch {
            prefs.setDefaultModel(model)
            _activeConversationId.value?.let { convoId ->
                chatRepository.updateConversationModel(convoId, model.id)
            }
        }
    }

    fun selectCapability(mode: CapabilityMode) {
        viewModelScope.launch { prefs.setDefaultCapability(mode) }
    }

    fun toggleWebSearch() {
        viewModelScope.launch {
            val next = !webSearchEnabled.value
            prefs.setWebSearchEnabled(next)
            if (next && codeExecutionEnabled.value) {
                prefs.setCodeExecutionEnabled(false)
                _bannerMessage.value = "Live Web Search ON (Sandbox paused)"
            } else {
                _bannerMessage.value = if (next) "Live Web Search ON" else "Live Web Search OFF"
            }
        }
    }

    fun toggleCodeExecution() {
        viewModelScope.launch {
            val next = !codeExecutionEnabled.value
            prefs.setCodeExecutionEnabled(next)
            if (next && webSearchEnabled.value) {
                prefs.setWebSearchEnabled(false)
                _bannerMessage.value = "Python Sandbox ON (Web paused)"
            } else {
                _bannerMessage.value = if (next) "Python Sandbox ON" else "Python Sandbox OFF"
            }
        }
    }

    fun enhanceComposerPrompt() {
        val current = _composerText.value.trim()
        if (current.length < 4 || _isEnhancingPrompt.value) return
        viewModelScope.launch {
            _isEnhancingPrompt.value = true
            aiRepository.enhancePrompt(current)
                .onSuccess { improved ->
                    _composerText.value = improved
                    _bannerMessage.value = "Prompt sharpened by Aura AI"
                }
                .onFailure { err ->
                    _bannerMessage.value = err.message ?: "Could not enhance prompt."
                }
            _isEnhancingPrompt.value = false
        }
    }

    fun applySlashCommand(command: SlashCommand) {
        val current = _composerText.value.trim()
        val remainder = if (current.startsWith("/")) {
            val firstSpace = current.indexOf(' ')
            if (firstSpace == -1) "" else current.substring(firstSpace + 1).trimStart()
        } else {
            current
        }
        when (command.specialAction) {
            SlashCommandSpecialAction.OPEN_SCANNER -> {
                _composerText.value = ""
                _openScannerTrigger.value = true
                return
            }
            SlashCommandSpecialAction.OPEN_VAULT -> {
                _composerText.value = ""
                _openVaultTrigger.value = true
                return
            }
            SlashCommandSpecialAction.OPEN_MEMORY -> {
                if (remainder.isBlank()) {
                    _composerText.value = ""
                    _openMemoryTrigger.value = true
                    return
                }
            }
            SlashCommandSpecialAction.NONE -> Unit
        }
        _composerText.value = if (remainder.isNotBlank()) {
            "${command.promptPrefix}$remainder"
        } else {
            command.promptPrefix
        }
        viewModelScope.launch {
            prefs.setDefaultCapability(command.suggestedMode)
            if (command.requiresWeb) {
                prefs.setWebSearchEnabled(true)
                prefs.setCodeExecutionEnabled(false)
            } else if (command.requiresSandbox) {
                prefs.setCodeExecutionEnabled(true)
                prefs.setWebSearchEnabled(false)
            }
        }
    }

    fun buildExportMarkdown(messages: List<MessageEntity>): String {
        val sb = StringBuilder()
        sb.append("# Aura AI · Abhynex Export\n\n")
        messages.forEach { msg ->
            if (msg.role == "user") {
                sb.append("## You\n${msg.content}\n\n")
            } else {
                sb.append("### Aura AI\n")
                if (!msg.thinkingContent.isNullOrBlank()) {
                    sb.append("<details>\n<summary>Reasoning</summary>\n\n${msg.thinkingContent}\n\n</details>\n\n")
                }
                sb.append("${msg.content}\n\n---\n\n")
            }
        }
        return sb.toString().trim()
    }

    fun selectPersona(persona: PersonaEntity) {
        viewModelScope.launch {
            prefs.setActivePersonaId(persona.id)
            _activeConversationId.value?.let { convoId ->
                chatRepository.updateConversationPersona(convoId, persona.id)
            }
        }
    }

    fun startNewChat() {
        stopGeneration()
        stopSpeaking()
        _activeConversationId.value = null
        _attachmentDraft.value = null
        _destination.value = AppDestination.Chat
    }

    fun openConversation(conversation: ConversationEntity) {
        stopGeneration()
        stopSpeaking()
        _activeConversationId.value = conversation.id
        _destination.value = AppDestination.Chat
        viewModelScope.launch {
            prefs.setDefaultModel(AiModelTier.fromId(conversation.modelId))
            prefs.setActivePersonaId(conversation.personaId)
        }
    }

    fun openConversationById(conversationId: String) {
        viewModelScope.launch {
            val convo = chatRepository.getConversation(conversationId)
            if (convo != null) {
                openConversation(convo)
            } else {
                _activeConversationId.value = conversationId
                _destination.value = AppDestination.Chat
            }
        }
    }

    fun attachImage(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image_attachment.jpg"
        _attachmentDraft.value = AttachmentDraft(
            uri = uri.toString(),
            displayName = name,
            isImage = true
        )
    }

    fun attachGeneratedImageForEdit(imagePath: String) {
        val fileUri = Uri.fromFile(File(imagePath)).toString()
        _attachmentDraft.value = AttachmentDraft(
            uri = fileUri,
            displayName = "Edit generated image",
            isImage = true
        )
        if (!selectedModel.value.isImageModel) {
            selectModel(AiModelTier.NANO_BANANA_2_1)
        }
        _bannerMessage.value = "Image attached — describe how to edit it"
    }

    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val resolvedName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
            val snippet = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText().take(4000)
                    }
                }.getOrNull()
            }
            _attachmentDraft.value = AttachmentDraft(
                uri = uri.toString(),
                displayName = resolvedName,
                isImage = false,
                textSnippet = snippet
            )
        }
    }

    fun clearAttachment() {
        _attachmentDraft.value = null
    }

    fun sendPrompt(
        overridePrompt: String? = null,
        overrideModel: AiModelTier? = null,
        overrideMode: CapabilityMode? = null,
        overrideDraft: AttachmentDraft? = null
    ) {
        val rawPrompt = (overridePrompt ?: _composerText.value).trim()
        val draft = overrideDraft ?: _attachmentDraft.value
        if (rawPrompt.isBlank() && draft == null) return

        val effectivePrompt = if (rawPrompt.isBlank() && draft != null) {
            if (draft.isImage) "Describe and analyze this image in detail."
            else "Summarize and analyze this attached file."
        } else {
            rawPrompt
        }

        if (overridePrompt == null) {
            _composerText.value = ""
        }
        if (overrideDraft == null) {
            _attachmentDraft.value = null
        }

        val model = overrideModel ?: selectedModel.value
        val capability = overrideMode ?: selectedCapability.value
        val persona = activePersona.value

        if (overrideModel != null) selectModel(overrideModel)
        if (overrideMode != null) selectCapability(overrideMode)

        maybeAutoCaptureMemory(effectivePrompt)

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val convoId = _activeConversationId.value ?: run {
                val created = chatRepository.createConversation(
                    title = buildConversationTitle(effectivePrompt),
                    modelId = model.id,
                    personaId = persona?.id ?: "persona_general"
                )
                _activeConversationId.value = created.id
                created.id
            }

            val historyBefore = chatRepository.getMessagesOnce(convoId)

            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convoId,
                role = "user",
                content = effectivePrompt,
                attachmentUri = draft?.uri,
                attachmentName = draft?.displayName,
                isImageAttachment = draft?.isImage ?: false,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(userMsg)

            streamAssistantReply(
                conversationId = convoId,
                prompt = effectivePrompt,
                model = model,
                capability = capability,
                persona = persona,
                history = historyBefore,
                attachment = draft
            )
        }
    }

    fun regenerateResponse(assistantMessage: MessageEntity) {
        if (assistantMessage.role != "assistant") return
        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val messages = chatRepository.getMessagesOnce(assistantMessage.conversationId)
            val idx = messages.indexOfFirst { it.id == assistantMessage.id }
            val precedingUser = messages.take(if (idx >= 0) idx else messages.size)
                .lastOrNull { it.role == "user" } ?: return@launch

            chatRepository.deleteMessage(assistantMessage.id)
            val model = selectedModel.value
            val capability = selectedCapability.value
            val persona = activePersona.value
            val attachment = if (precedingUser.attachmentName != null && precedingUser.attachmentUri != null) {
                AttachmentDraft(
                    uri = precedingUser.attachmentUri,
                    displayName = precedingUser.attachmentName,
                    isImage = precedingUser.isImageAttachment
                )
            } else null

            streamAssistantReply(
                conversationId = assistantMessage.conversationId,
                prompt = precedingUser.content,
                model = model,
                capability = capability,
                persona = persona,
                history = messages.take(idx.coerceAtLeast(0)),
                attachment = attachment
            )
        }
    }

    private suspend fun streamAssistantReply(
        conversationId: String,
        prompt: String,
        model: AiModelTier,
        capability: CapabilityMode,
        persona: PersonaEntity?,
        history: List<MessageEntity>,
        attachment: AttachmentDraft?
    ) {
        val assistantMsgId = UUID.randomUUID().toString()
        _streamingState.value = StreamingMessageState(
            messageId = assistantMsgId,
            conversationId = conversationId,
            thinkingText = "",
            thinkingDurationMs = 0L,
            contentText = "",
            modelDisplayName = "Aura AI",
            isThinkingPhase = !model.isImageModel && (
                capability == CapabilityMode.DEEP_THINK || capability == CapabilityMode.CODE_ARCHITECT
            ),
            isGeneratingImage = model.isImageModel
        )

        val activeMemories = chatRepository.getActiveMemoriesOnce()
            .filter { it.isEnabled }
            .map { "[${it.category}] ${it.content}" }

        val vaultChunks = if (vaultRagEnabled.value && !model.isImageModel) {
            val allChunks = chatRepository.getAllKnowledgeChunksOnce()
            KnowledgeRagEngine.retrieveTopChunks(prompt, allChunks, topK = 4)
        } else {
            emptyList()
        }

        val request = AiRequest(
            prompt = prompt,
            modelTier = model,
            capabilityMode = capability,
            language = selectedLanguage.value,
            persona = persona,
            history = history,
            attachmentUri = attachment?.uri,
            attachmentName = attachment?.displayName,
            attachmentIsImage = attachment?.isImage ?: false,
            attachmentTextSnippet = attachment?.textSnippet,
            extraAttachmentUris = attachment?.extraImageUris.orEmpty(),
            activeMemories = activeMemories,
            vaultContextSnippets = vaultChunks,
            useWebSearch = (webSearchEnabled.value || capability == CapabilityMode.DEEP_RESEARCH) && !model.isImageModel,
            useCodeExecution = codeExecutionEnabled.value && !model.isImageModel && capability != CapabilityMode.DEEP_RESEARCH
        )

        try {
            aiRepository.streamResponse(request).collect { event ->
                val current = _streamingState.value ?: return@collect
                when (event) {
                    is AiStreamEvent.ThinkingChunk -> {
                        _streamingState.value = current.copy(
                            thinkingText = event.thinkingText,
                            thinkingDurationMs = event.elapsedMs,
                            isThinkingPhase = true
                        )
                    }
                    is AiStreamEvent.ContentChunk -> {
                        _streamingState.value = current.copy(
                            contentText = event.accumulatedText,
                            isThinkingPhase = false
                        )
                    }
                    is AiStreamEvent.Completed -> {
                        val saved = MessageEntity(
                            id = assistantMsgId,
                            conversationId = conversationId,
                            role = "assistant",
                            content = event.fullText,
                            thinkingContent = event.thinkingText,
                            thinkingDurationMs = event.thinkingDurationMs,
                            modelDisplayName = "Aura AI",
                            generatedImagePath = event.generatedImagePath,
                            sourcesJson = serializeSources(event.sources),
                            isError = false,
                            timestamp = System.currentTimeMillis()
                        )
                        _streamingState.value = null
                        chatRepository.saveMessage(saved)
                    }
                    is AiStreamEvent.Failed -> {
                        val errorMsg = MessageEntity(
                            id = assistantMsgId,
                            conversationId = conversationId,
                            role = "assistant",
                            content = event.errorMessage,
                            modelDisplayName = "Aura AI",
                            isError = true,
                            timestamp = System.currentTimeMillis()
                        )
                        _streamingState.value = null
                        chatRepository.saveMessage(errorMsg)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val errorMsg = MessageEntity(
                id = assistantMsgId,
                conversationId = conversationId,
                role = "assistant",
                content = t.localizedMessage ?: "Aura AI could not respond. Please retry.",
                modelDisplayName = "Aura AI",
                isError = true,
                timestamp = System.currentTimeMillis()
            )
            _streamingState.value = null
            chatRepository.saveMessage(errorMsg)
        } finally {
            if (_streamingState.value?.messageId == assistantMsgId) {
                _streamingState.value = null
            }
        }
    }

    fun stopGeneration() {
        val current = _streamingState.value
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        if (current != null && current.contentText.isNotBlank()) {
            viewModelScope.launch {
                chatRepository.saveMessage(
                    MessageEntity(
                        id = current.messageId,
                        conversationId = current.conversationId,
                        role = "assistant",
                        content = current.contentText + "\n\n_(Generation stopped)_",
                        thinkingContent = current.thinkingText.ifBlank { null },
                        thinkingDurationMs = current.thinkingDurationMs,
                        modelDisplayName = current.modelDisplayName,
                        isError = false,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        _streamingState.value = null
    }

    fun toggleBookmark(message: MessageEntity) {
        viewModelScope.launch {
            chatRepository.toggleBookmark(message.id, message.isBookmarked)
        }
    }

    fun togglePinConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            chatRepository.togglePinConversation(conversation.id, conversation.isPinned)
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { chatRepository.renameConversation(id, title) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = null
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            chatRepository.clearAllHistory()
            _activeConversationId.value = null
            _bannerMessage.value = "All local conversations cleared"
        }
    }

    fun createPersona(
        name: String,
        roleTag: String,
        description: String,
        systemPrompt: String,
        iconKey: String
    ) {
        if (name.isBlank() || systemPrompt.isBlank()) return
        viewModelScope.launch {
            val created = chatRepository.createCustomPersona(
                name = name,
                roleTag = roleTag,
                description = description.ifBlank { "Custom AI workspace assistant" },
                systemPrompt = systemPrompt,
                iconKey = iconKey
            )
            prefs.setActivePersonaId(created.id)
            _bannerMessage.value = "${created.name} persona active"
        }
    }

    fun deleteCustomPersona(id: String) {
        viewModelScope.launch {
            chatRepository.deleteCustomPersona(id)
            if (activePersonaId.value == id) {
                prefs.setActivePersonaId("persona_general")
            }
        }
    }

    fun toggleSpeakMessage(message: MessageEntity) {
        if (_speakingMessageId.value == message.id) {
            stopSpeaking()
            return
        }
        if (!isTtsReady) {
            _bannerMessage.value = "Text-to-Speech is initializing..."
            return
        }
        val cleanText = message.content
            .replace(Regex("```[\\s\\S]*?```"), "Code block omitted.")
            .replace(Regex("[#*`>_]"), "")
            .trim()
        _speakingMessageId.value = message.id
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, message.id)
    }

    fun stopSpeaking() {
        tts?.stop()
        _speakingMessageId.value = null
    }

    // --- Memory Vault ("Aura Remembers") ---
    fun addMemory(content: String, category: String = "General") {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            chatRepository.addMemory(trimmed, category)
            _bannerMessage.value = "Saved to Aura Memory Vault"
        }
    }

    fun toggleMemory(memory: MemoryEntity) {
        viewModelScope.launch { chatRepository.toggleMemory(memory.id, memory.isEnabled) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { chatRepository.deleteMemory(id) }
    }

    private fun maybeAutoCaptureMemory(prompt: String) {
        val trimmed = prompt.trim()
        val prefixes = listOf(
            "remember this about me:",
            "remember that ",
            "remember:",
            "remember "
        )
        val matchedPrefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return
        val fact = trimmed.substring(matchedPrefix.length).trim()
        if (fact.length >= 8 && fact.length <= 240) {
            viewModelScope.launch {
                chatRepository.addMemory(fact, "Auto")
                _bannerMessage.value = "Saved to Aura Memory"
            }
        }
    }

    // --- Private Document Knowledge Vault (On-Device RAG) ---
    fun setVaultRagEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVaultRagEnabled(enabled)
            _bannerMessage.value = if (enabled) "Document Vault Auto-RAG ON" else "Document Vault Auto-RAG OFF"
        }
    }

    fun indexDocumentToVault(title: String, sourceType: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val docId = UUID.randomUUID().toString()
            val chunks = withContext(Dispatchers.Default) {
                KnowledgeRagEngine.chunkDocument(
                    docId = docId,
                    docTitle = title,
                    rawText = content
                )
            }
            val doc = com.example.data.local.KnowledgeDocEntity(
                id = docId,
                title = title.trim().ifBlank { "Vault Document" },
                sourceType = sourceType,
                chunkCount = chunks.size,
                createdAt = System.currentTimeMillis()
            )
            chatRepository.saveKnowledgeDocument(doc, chunks)
            _bannerMessage.value = "Indexed '${doc.title}' (${chunks.size} chunks)"
        }
    }

    fun deleteKnowledgeDoc(docId: String) {
        viewModelScope.launch {
            chatRepository.deleteKnowledgeDoc(docId)
            _bannerMessage.value = "Removed document from Vault"
        }
    }

    // --- Portable Full Backup & Restore (.json) ---
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            backupRepository.exportToUri(uri)
                .onSuccess { stats ->
                    _bannerMessage.value =
                        "Backup exported: ${stats.conversations} chats, ${stats.memories} memories, ${stats.knowledgeDocs} docs"
                }
                .onFailure { err ->
                    _bannerMessage.value = err.message ?: "Backup export failed."
                }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            backupRepository.importFromUri(uri)
                .onSuccess { stats ->
                    _bannerMessage.value =
                        "Restored ${stats.conversations} chats, ${stats.memories} memories, ${stats.knowledgeDocs} docs"
                }
                .onFailure { err ->
                    _bannerMessage.value = err.message ?: "Backup restore failed."
                }
        }
    }

    // --- Widget & Share-to-Aura Deep Intents ---
    fun handleWidgetAction(action: String) {
        when (action) {
            "live_voice" -> navigateTo(AppDestination.LiveVoice)
            "scanner" -> {
                navigateTo(AppDestination.Chat)
                _openScannerTrigger.value = true
            }
            "ask" -> navigateTo(AppDestination.Chat)
        }
    }

    fun handleIncomingShare(text: String?, uris: List<Uri>, mimeType: String?) {
        navigateTo(AppDestination.Chat)
        if (!text.isNullOrBlank()) {
            _composerText.value = text.trim()
        }
        if (uris.isNotEmpty()) {
            val isPdf = mimeType?.contains("pdf", ignoreCase = true) == true ||
                uris.first().toString().lowercase().endsWith(".pdf")
            if (isPdf) {
                viewModelScope.launch {
                    val app = getApplication<Application>()
                    val pages = KnowledgeRagEngine.renderPdfPagesToCache(app, uris.first(), maxPages = 6)
                    if (pages.isNotEmpty()) {
                        val ocr = KnowledgeRagEngine.extractTextFromImageUris(
                            app,
                            pages.map { Uri.parse(it.uriString) }
                        )
                        _attachmentDraft.value = AttachmentDraft(
                            uri = pages.first().uriString,
                            displayName = "Shared PDF (${pages.size} pages)",
                            isImage = true,
                            textSnippet = ocr.take(6000).ifBlank { null },
                            extraImageUris = pages.drop(1).map { it.uriString }
                        )
                        _bannerMessage.value = "Shared PDF attached (${pages.size} pages)"
                    } else {
                        _bannerMessage.value = "Could not render shared PDF. Try PDF Scanner."
                    }
                }
            } else {
                val first = uris.first()
                val name = first.lastPathSegment?.substringAfterLast('/') ?: "Shared image"
                _attachmentDraft.value = AttachmentDraft(
                    uri = first.toString(),
                    displayName = name,
                    isImage = true,
                    extraImageUris = uris.drop(1).take(5).map { it.toString() }
                )
                _bannerMessage.value = if (uris.size > 1) {
                    "${uris.size} shared images attached"
                } else {
                    "Shared image attached to Aura"
                }
            }
        }
    }

    private fun buildConversationTitle(prompt: String): String {
        val cleaned = prompt.replace("\n", " ").trim()
        return if (cleaned.length <= 42) cleaned else cleaned.take(40).trimEnd() + "…"
    }

    override fun onCleared() {
        super.onCleared()
        liveVoiceJob?.cancel()
        tts?.stop()
        tts?.shutdown()
    }
}
