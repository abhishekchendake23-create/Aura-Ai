package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ArtifactEntity
import com.example.data.local.AuraDatabase
import com.example.data.local.AutopilotTaskEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.CustomWebhookEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageSearchHit
import com.example.data.local.MessageVariant
import com.example.data.local.PersonaEntity
import com.example.data.local.ProjectEntity
import com.example.data.local.PromptTemplateEntity
import com.example.data.local.parseMessageVariants
import com.example.data.local.serializeMessageVariants
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
import com.example.data.model.AttachmentDraft
import com.example.data.model.AuraVoiceCharacter
import com.example.data.model.CapabilityMode
import com.example.data.model.SlashCommand
import com.example.data.model.SlashCommandSpecialAction
import com.example.data.model.ThemeMode
import com.example.data.model.ThinkingBudgetMode
import com.example.data.model.WorkbenchConfig
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
import com.example.data.repository.ProjectZipReader
import com.example.data.repository.WebhookExecutionResult
import com.example.data.repository.serializeSources
import com.example.service.AutopilotRunner
import com.example.service.AutopilotScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
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

data class ArtifactLaunchPayload(
    val title: String,
    val language: String,
    val code: String
)

data class StreamingMessageState(
    val messageId: String,
    val conversationId: String,
    val thinkingText: String,
    val thinkingDurationMs: Long,
    val contentText: String,
    val modelDisplayName: String,
    val activeSwitchNote: String? = null,
    val isThinkingPhase: Boolean,
    val isGeneratingImage: Boolean = false,
    val compareAlphaText: String = "",
    val compareBetaText: String = "",
    val isCompareArena: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

    private val _runningAutopilotTaskId = MutableStateFlow<String?>(null)
    val runningAutopilotTaskId: StateFlow<String?> = _runningAutopilotTaskId.asStateFlow()

    private val _autoLaunchArtifactPreview = MutableStateFlow<ArtifactLaunchPayload?>(null)
    val autoLaunchArtifactPreview: StateFlow<ArtifactLaunchPayload?> = _autoLaunchArtifactPreview.asStateFlow()

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

    val selfVerifyEnabled: StateFlow<Boolean> = prefs.selfVerifyEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val councilModeEnabled: StateFlow<Boolean> = prefs.councilModeEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val autonomousAgentEnabled: StateFlow<Boolean> = prefs.autonomousAgentEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val compareArenaEnabled: StateFlow<Boolean> = prefs.compareArenaEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val appBuilderEnabled: StateFlow<Boolean> = prefs.appBuilderEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val workbenchConfig: StateFlow<WorkbenchConfig> = combine(
        combine(
            prefs.workbenchTemperature,
            prefs.workbenchTopP,
            prefs.workbenchMaxTokens,
            prefs.workbenchThinkingBudget
        ) { temp, topP, maxTokens, budget ->
            Triple(temp, topP, maxTokens to ThinkingBudgetMode.fromId(budget))
        },
        combine(
            prefs.jsonSchemaMode,
            prefs.jsonSchemaText,
            prefs.systemInstructionOverride
        ) { schemaMode, schemaText, sysOverride ->
            Triple(schemaMode, schemaText, sysOverride)
        }
    ) { firstPart, secondPart ->
        WorkbenchConfig(
            temperature = firstPart.first,
            topP = firstPart.second,
            maxOutputTokens = firstPart.third.first,
            thinkingBudgetMode = firstPart.third.second,
            jsonSchemaMode = secondPart.first,
            jsonSchemaText = secondPart.second,
            systemInstructionOverride = secondPart.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkbenchConfig()
    )

    val liveVoiceReadoutEnabled: StateFlow<Boolean> = prefs.liveVoiceReadoutEnabled.stateIn(
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

    val savedOpenRouterApiKey: StateFlow<String> = prefs.userOpenRouterApiKey.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val savedDeepSeekApiKey: StateFlow<String> = prefs.userDeepSeekApiKey.stateIn(
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

    val hasEffectiveOpenRouterApiKey: StateFlow<Boolean> = prefs.userOpenRouterApiKey
        .let { orFlow ->
            combine(orFlow) { values ->
                values[0].isNotBlank() || PreferencesManager.resolveBuildConfigOpenRouterApiKey().isNotBlank()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.resolveBuildConfigOpenRouterApiKey().isNotBlank()
        )

    val hasEffectiveDeepSeekApiKey: StateFlow<Boolean> = prefs.userDeepSeekApiKey
        .let { dsFlow ->
            combine(dsFlow) { values ->
                values[0].isNotBlank() || PreferencesManager.resolveBuildConfigDeepSeekApiKey().isNotBlank()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferencesManager.resolveBuildConfigDeepSeekApiKey().isNotBlank()
        )

    val hasEffectiveApiKey: StateFlow<Boolean> = combine(
        prefs.userApiKey,
        prefs.userGroqApiKey,
        prefs.userOpenRouterApiKey,
        prefs.userDeepSeekApiKey
    ) { geminiSaved, groqSaved, orSaved, dsSaved ->
        geminiSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigApiKey().isNotBlank() ||
            groqSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank() ||
            orSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigOpenRouterApiKey().isNotBlank() ||
            dsSaved.isNotBlank() ||
            PreferencesManager.resolveBuildConfigDeepSeekApiKey().isNotBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PreferencesManager.resolveBuildConfigApiKey().isNotBlank() ||
            PreferencesManager.resolveBuildConfigGroqApiKey().isNotBlank() ||
            PreferencesManager.resolveBuildConfigOpenRouterApiKey().isNotBlank() ||
            PreferencesManager.resolveBuildConfigDeepSeekApiKey().isNotBlank()
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

    val projects: StateFlow<List<ProjectEntity>> = chatRepository.observeProjects().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activeProjectId: StateFlow<String?> = prefs.activeProjectId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val activeProject: StateFlow<ProjectEntity?> = combine(projects, activeProjectId) { list, id ->
        if (id.isNullOrBlank()) null else list.find { it.id == id }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val artifacts: StateFlow<List<ArtifactEntity>> = chatRepository.observeArtifacts().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val promptTemplates: StateFlow<List<PromptTemplateEntity>> = chatRepository.observePromptTemplates().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val customWebhooks: StateFlow<List<CustomWebhookEntity>> = chatRepository.observeCustomWebhooks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val conversations: StateFlow<List<ConversationEntity>> =
        combine(
            chatRepository.observeConversations(),
            _searchQuery,
            prefs.activeProjectId
        ) { list, query, projId ->
            val projectFiltered = if (projId.isNullOrBlank()) {
                list
            } else {
                list.filter { it.projectId == projId }
            }
            if (query.isBlank()) projectFiltered
            else projectFiltered.filter { it.title.contains(query.trim(), ignoreCase = true) }
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

    val autopilotTasks: StateFlow<List<AutopilotTaskEntity>> = chatRepository.observeAutopilotTasks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val messageSearchHits: StateFlow<List<MessageSearchHit>> = _searchQuery
        .debounce(220L)
        .mapLatest { q ->
            if (q.trim().length < 2) emptyList()
            else withContext(Dispatchers.IO) { chatRepository.searchAllMessages(q) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            chatRepository.ensureDefaultPersonas()
            chatRepository.ensureDefaultPromptTemplates()
            withContext(Dispatchers.IO) {
                runCatching {
                    val enabledTasks = database.auraDao().getEnabledAutopilotTasksOnce()
                    AutopilotScheduler.syncSchedules(getApplication(), enabledTasks)
                }
            }
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
                        if (!utteranceId.orEmpty().startsWith("live_stream_")) {
                            _speakingMessageId.value = null
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!utteranceId.orEmpty().startsWith("live_stream_")) {
                            _speakingMessageId.value = null
                        }
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

    fun saveOpenRouterApiKey(apiKey: String) {
        viewModelScope.launch {
            prefs.setUserOpenRouterApiKey(apiKey)
            val cleaned = apiKey.trim()
            _bannerMessage.value = if (cleaned.isNotEmpty()) {
                "OpenRouter Free Key saved (DeepSeek R1 & V3 ready)"
            } else {
                "Saved OpenRouter Key removed"
            }
        }
    }

    fun saveDeepSeekApiKey(apiKey: String) {
        viewModelScope.launch {
            prefs.setUserDeepSeekApiKey(apiKey)
            val cleaned = apiKey.trim()
            _bannerMessage.value = if (cleaned.isNotEmpty()) {
                "DeepSeek API Key saved"
            } else {
                "Saved DeepSeek Key removed"
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

    fun toggleSelfVerify() {
        viewModelScope.launch {
            val next = !selfVerifyEnabled.value
            prefs.setSelfVerifyEnabled(next)
            _bannerMessage.value = if (next) {
                "Zero-Bug Self-Verify ON (Dual-pass audit active)"
            } else {
                "Zero-Bug Self-Verify OFF"
            }
        }
    }

    fun toggleCouncilMode() {
        viewModelScope.launch {
            val next = !councilModeEnabled.value
            prefs.setCouncilModeEnabled(next)
            _bannerMessage.value = if (next) {
                "Aura Council 3x ON — 3 parallel specialists + Judge synthesis"
            } else {
                "Aura Council 3x OFF"
            }
        }
    }

    fun toggleLiveVoiceReadout() {
        viewModelScope.launch {
            val next = !liveVoiceReadoutEnabled.value
            prefs.setLiveVoiceReadoutEnabled(next)
            if (!next) stopSpeaking()
            _bannerMessage.value = if (next) {
                "Auto-Speak ON — Aura AI will read replies aloud as they stream"
            } else {
                "Auto-Speak OFF"
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
            if (command.trigger == "/app") {
                prefs.setAppBuilderEnabled(true)
            }
            if (command.trigger == "/agent") {
                prefs.setAutonomousAgentEnabled(true)
            }
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
        attachProjectFiles(listOf(uri))
    }

    fun attachProjectFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val first = uris.first()
            val mime = app.contentResolver.getType(first).orEmpty().lowercase()
            val lower = first.toString().lowercase()

            if (uris.size == 1 && (mime.contains("pdf") || lower.endsWith(".pdf"))) {
                val pages = withContext(Dispatchers.IO) {
                    KnowledgeRagEngine.renderPdfPagesToCache(app, first, maxPages = 6)
                }
                if (pages.isNotEmpty()) {
                    val ocr = withContext(Dispatchers.IO) {
                        KnowledgeRagEngine.extractTextFromImageUris(
                            app,
                            pages.map { Uri.parse(it.uriString) }
                        )
                    }
                    _attachmentDraft.value = AttachmentDraft(
                        uri = pages.first().uriString,
                        displayName = "PDF (${pages.size} pages)",
                        isImage = true,
                        textSnippet = ocr.take(6000).ifBlank { null },
                        extraImageUris = pages.drop(1).map { it.uriString }
                    )
                    _bannerMessage.value = "PDF attached (${pages.size} pages)"
                    return@launch
                }
            }

            val projectResult = withContext(Dispatchers.IO) {
                ProjectZipReader.readFromUris(app, uris)
            }
            if (projectResult != null) {
                _attachmentDraft.value = AttachmentDraft(
                    uri = first.toString(),
                    displayName = projectResult.displayName,
                    isImage = false,
                    textSnippet = projectResult.formattedContext
                )
                _bannerMessage.value = "${projectResult.displayName} attached"
            } else {
                _bannerMessage.value = "Could not extract readable code or text from selection"
            }
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
                    personaId = persona?.id ?: "persona_general",
                    projectId = activeProjectId.value
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

    fun editUserMessageAndRegenerate(message: MessageEntity, newPrompt: String) {
        val updated = newPrompt.trim()
        if (message.role != "user" || updated.isBlank()) return
        activeGenerationJob?.cancel()
        stopSpeaking()
        activeGenerationJob = viewModelScope.launch {
            chatRepository.deleteMessagesFromTimestamp(message.conversationId, message.timestamp)
            val historyBefore = chatRepository.getMessagesOnce(message.conversationId)
            val editedUserMsg = message.copy(
                content = updated,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(editedUserMsg)

            val attachment = buildAttachmentFromMessage(editedUserMsg)
            streamAssistantReply(
                conversationId = editedUserMsg.conversationId,
                prompt = updated,
                model = selectedModel.value,
                capability = selectedCapability.value,
                persona = activePersona.value,
                history = historyBefore,
                attachment = attachment
            )
        }
    }

    fun switchAssistantVariant(message: MessageEntity, targetIndex: Int) {
        viewModelScope.launch {
            chatRepository.selectAssistantVariant(message, targetIndex)
        }
    }

    fun lockArenaWinner(message: MessageEntity, targetIndex: Int) {
        viewModelScope.launch {
            chatRepository.selectAssistantVariant(message, targetIndex, lockAsFinal = true)
            _bannerMessage.value = "Locked winning response to thread history"
        }
    }

    fun forkConversationFromMessage(message: MessageEntity) {
        stopGeneration()
        stopSpeaking()
        viewModelScope.launch {
            val forked = chatRepository.forkConversationFromMessage(
                sourceConversationId = message.conversationId,
                upToMessageId = message.id
            )
            if (forked != null) {
                _activeConversationId.value = forked.id
                _destination.value = AppDestination.Chat
                _bannerMessage.value = "Branched new thread: ${forked.title}"
            } else {
                _bannerMessage.value = "Could not branch conversation"
            }
        }
    }

    fun toggleMessagePinned(message: MessageEntity) {
        viewModelScope.launch {
            chatRepository.togglePinMessage(message.id, message.isPinned)
            _bannerMessage.value = if (!message.isPinned) {
                "Pinned to rolling context memory"
            } else {
                "Unpinned from rolling context"
            }
        }
    }

    fun autoFixArtifactCode(errorLog: String, code: String) {
        val trimmedError = errorLog.trim().take(1200)
        val trimmedCode = code.trim().take(6000)
        if (trimmedCode.isBlank()) return
        val fixPrompt = buildString {
            append("Fix the runtime/console error below, briefly explain the root cause, ")
            append("and return the complete fixed self-contained code block ready for Aura Sandbox preview.\n\n")
            if (trimmedError.isNotBlank()) {
                append("Error Log:\n```\n$trimmedError\n```\n\n")
            }
            append("Code:\n```\n$trimmedCode\n```")
        }
        sendPrompt(
            overridePrompt = fixPrompt,
            overrideMode = CapabilityMode.CODE_ARCHITECT
        )
    }

    fun iterateBuiltApp(instruction: String, currentCode: String) {
        val cleanInst = instruction.trim()
        val trimmedCode = currentCode.trim().take(18000)
        if (cleanInst.isBlank() || trimmedCode.isBlank()) return
        val updatePrompt = buildString {
            append("Update and improve this interactive HTML app based on the user's request.\n")
            append("Request: ").append(cleanInst).append("\n\n")
            append("Return the complete, updated, self-contained HTML5/CSS3/JS application inside a single ```html code block.\n\n")
            append("Current App Code:\n```html\n").append(trimmedCode).append("\n```")
        }
        sendPrompt(
            overridePrompt = updatePrompt,
            overrideMode = CapabilityMode.CODE_ARCHITECT
        )
    }

    fun consumeAutoLaunchArtifact() {
        _autoLaunchArtifactPreview.value = null
    }

    private suspend fun buildAttachmentFromMessage(userMessage: MessageEntity): AttachmentDraft? {
        if (userMessage.attachmentName == null || userMessage.attachmentUri == null) return null
        val textSnippet = if (!userMessage.isImageAttachment) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ProjectZipReader.readFromUris(
                        getApplication(),
                        listOf(Uri.parse(userMessage.attachmentUri))
                    )?.formattedContext
                }.getOrNull()
            }
        } else null
        return AttachmentDraft(
            uri = userMessage.attachmentUri,
            displayName = userMessage.attachmentName,
            isImage = userMessage.isImageAttachment,
            textSnippet = textSnippet
        )
    }

    fun regenerateResponse(assistantMessage: MessageEntity) {
        if (assistantMessage.role != "assistant") return
        activeGenerationJob?.cancel()
        stopSpeaking()
        activeGenerationJob = viewModelScope.launch {
            val messages = chatRepository.getMessagesOnce(assistantMessage.conversationId)
            val idx = messages.indexOfFirst { it.id == assistantMessage.id }
            val precedingUser = messages.take(if (idx >= 0) idx else messages.size)
                .lastOrNull { it.role == "user" } ?: return@launch

            val existingVariants = parseMessageVariants(assistantMessage.variantsJson).ifEmpty {
                if (!assistantMessage.isError && assistantMessage.content.isNotBlank()) {
                    listOf(
                        MessageVariant(
                            content = assistantMessage.content,
                            thinkingContent = assistantMessage.thinkingContent,
                            thinkingDurationMs = assistantMessage.thinkingDurationMs,
                            sourcesJson = assistantMessage.sourcesJson,
                            createdAt = assistantMessage.timestamp
                        )
                    )
                } else {
                    emptyList()
                }
            }

            chatRepository.deleteMessage(assistantMessage.id)
            val model = selectedModel.value
            val capability = selectedCapability.value
            val persona = activePersona.value
            val attachment = buildAttachmentFromMessage(precedingUser)

            streamAssistantReply(
                conversationId = assistantMessage.conversationId,
                prompt = precedingUser.content,
                model = model,
                capability = capability,
                persona = persona,
                history = messages.take(idx.coerceAtLeast(0)),
                attachment = attachment,
                targetMessageId = assistantMessage.id,
                priorVariants = existingVariants,
                fallbackPreviousMessage = assistantMessage
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
        attachment: AttachmentDraft?,
        targetMessageId: String? = null,
        priorVariants: List<MessageVariant> = emptyList(),
        fallbackPreviousMessage: MessageEntity? = null
    ) {
        val assistantMsgId = targetMessageId ?: UUID.randomUUID().toString()
        val isArenaActive = compareArenaEnabled.value && !model.isImageModel
        val isAgentActive = autonomousAgentEnabled.value && !model.isImageModel
        val isAppBuilderActive = (appBuilderEnabled.value || DirectGeminiAiRepository.isAppBuildIntent(prompt)) &&
            !model.isImageModel &&
            !isArenaActive &&
            !codeExecutionEnabled.value &&
            capability != CapabilityMode.DEEP_RESEARCH
        val auraHeaderLabel = when {
            model.isImageModel -> "Aura Imagine"
            isArenaActive -> "Aura Arena"
            isAppBuilderActive -> "Aura Builder"
            else -> "Aura AI"
        }
        _streamingState.value = StreamingMessageState(
            messageId = assistantMsgId,
            conversationId = conversationId,
            thinkingText = "",
            thinkingDurationMs = 0L,
            contentText = "",
            modelDisplayName = auraHeaderLabel,
            activeSwitchNote = null,
            isThinkingPhase = !model.isImageModel && (
                capability == CapabilityMode.DEEP_THINK ||
                    capability == CapabilityMode.CODE_ARCHITECT ||
                    model.isDeepSeekTier ||
                    selfVerifyEnabled.value ||
                    councilModeEnabled.value ||
                    isAgentActive ||
                    isAppBuilderActive
                ),
            isGeneratingImage = model.isImageModel,
            isCompareArena = isArenaActive
        )

        val liveReadoutActive = liveVoiceReadoutEnabled.value && !model.isImageModel && isTtsReady && !isArenaActive
        var spokenCursor = 0

        val activeMemories = chatRepository.getActiveMemoriesOnce()
            .filter { it.isEnabled }
            .map { "[${it.category}] ${it.content}" }

        val vaultChunks = if (vaultRagEnabled.value && !model.isImageModel) {
            val allChunks = chatRepository.getAllKnowledgeChunksOnce()
            KnowledgeRagEngine.retrieveTopChunks(prompt, allChunks, topK = 4)
        } else {
            emptyList()
        }

        val resolvedProject = activeProject.value ?: run {
            val convoProjId = chatRepository.getConversation(conversationId)?.projectId
            if (!convoProjId.isNullOrBlank()) chatRepository.getProjectById(convoProjId) else null
        }
        val projectContextBlock = resolvedProject?.let { proj ->
            buildString {
                append("### PROJECT WORKSPACE: ${proj.name}\n")
                if (proj.customInstructions.isNotBlank()) {
                    append("Workspace Instructions:\n${proj.customInstructions}\n")
                }
                if (proj.pinnedUrlsOrNotes.isNotBlank()) {
                    append("Pinned Reference URLs / Notes:\n${proj.pinnedUrlsOrNotes}\n")
                }
            }.trim()
        }.orEmpty()

        val enabledHooks = if (!model.isImageModel) {
            chatRepository.getEnabledWebhooksOnce()
        } else {
            emptyList()
        }
        val (webhookLogPrefix, webhookContextBlock) = if (enabledHooks.isNotEmpty()) {
            aiRepository.executeEnabledWebhooks(prompt, enabledHooks)
        } else {
            "" to ""
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
            useCodeExecution = codeExecutionEnabled.value && !model.isImageModel && capability != CapabilityMode.DEEP_RESEARCH,
            enableSelfVerify = selfVerifyEnabled.value && !model.isImageModel,
            enableCouncilMode = councilModeEnabled.value && !model.isImageModel,
            workbenchConfig = workbenchConfig.value,
            projectContextBlock = projectContextBlock,
            webhookContextBlock = webhookContextBlock,
            webhookLogPrefix = webhookLogPrefix,
            enableAutonomousAgent = isAgentActive,
            enableCompareArena = isArenaActive,
            enableAppBuilder = appBuilderEnabled.value && !model.isImageModel
        )

        try {
            aiRepository.streamResponse(request).collect { event ->
                val current = _streamingState.value ?: return@collect
                when (event) {
                    is AiStreamEvent.ModelActivated -> {
                        if (event.switchedFromModel != null) {
                            spokenCursor = 0
                            if (liveReadoutActive) tts?.stop()
                            _streamingState.value = current.copy(
                                modelDisplayName = auraHeaderLabel,
                                activeSwitchNote = null,
                                contentText = ""
                            )
                        }
                    }
                    is AiStreamEvent.ThinkingChunk -> {
                        _streamingState.value = current.copy(
                            thinkingText = event.thinkingText,
                            thinkingDurationMs = event.elapsedMs,
                            isThinkingPhase = true
                        )
                    }
                    is AiStreamEvent.CompareProgress -> {
                        _streamingState.value = current.copy(
                            compareAlphaText = event.alphaText,
                            compareBetaText = event.betaText,
                            isCompareArena = true,
                            isThinkingPhase = false
                        )
                    }
                    is AiStreamEvent.ContentChunk -> {
                        _streamingState.value = current.copy(
                            contentText = event.accumulatedText,
                            isThinkingPhase = false
                        )
                        if (liveReadoutActive) {
                            val accumulated = event.accumulatedText
                            val openFenceCount = Regex("```").findAll(accumulated).count()
                            if (openFenceCount % 2 == 0 && spokenCursor < accumulated.length) {
                                val unqueued = accumulated.substring(spokenCursor)
                                val sentenceEnd = Regex("[.!?\\n]+\\s").findAll(unqueued).lastOrNull()
                                if (sentenceEnd != null && sentenceEnd.range.last >= 12) {
                                    val chunk = unqueued.substring(0, sentenceEnd.range.last + 1)
                                    val cleanSpeech = sanitizeForSpeech(chunk)
                                    if (cleanSpeech.isNotBlank()) {
                                        tts?.speak(
                                            cleanSpeech,
                                            TextToSpeech.QUEUE_ADD,
                                            null,
                                            "live_stream_${UUID.randomUUID()}"
                                        )
                                    }
                                    spokenCursor += sentenceEnd.range.last + 1
                                }
                            }
                        }
                    }
                    is AiStreamEvent.Completed -> {
                        val isArenaResult = event.compareVariants.isNotEmpty()
                        val finalHeader = when {
                            event.generatedImagePath != null -> "Aura Imagine"
                            isArenaResult -> "Aura Arena"
                            else -> auraHeaderLabel
                        }
                        val sourcesJson = serializeSources(event.sources)
                        val mergedVariants = if (isArenaResult) {
                            priorVariants + event.compareVariants
                        } else {
                            priorVariants + MessageVariant(
                                content = event.fullText,
                                thinkingContent = event.thinkingText,
                                thinkingDurationMs = event.thinkingDurationMs,
                                sourcesJson = sourcesJson,
                                createdAt = System.currentTimeMillis()
                            )
                        }
                        val selectedVariantIndex = if (isArenaResult) {
                            priorVariants.size.coerceAtLeast(0)
                        } else {
                            mergedVariants.lastIndex.coerceAtLeast(0)
                        }
                        val variantsJson = if (mergedVariants.size > 1) {
                            serializeMessageVariants(mergedVariants)
                        } else {
                            null
                        }
                        val displayContent = if (isArenaResult) {
                            event.compareVariants.first().content
                        } else {
                            event.fullText
                        }
                        val saved = MessageEntity(
                            id = assistantMsgId,
                            conversationId = conversationId,
                            role = "assistant",
                            content = displayContent,
                            thinkingContent = event.thinkingText,
                            thinkingDurationMs = event.thinkingDurationMs,
                            modelDisplayName = finalHeader,
                            generatedImagePath = event.generatedImagePath,
                            sourcesJson = sourcesJson,
                            isPinned = fallbackPreviousMessage?.isPinned ?: false,
                            isBookmarked = fallbackPreviousMessage?.isBookmarked ?: false,
                            variantsJson = variantsJson,
                            selectedVariantIndex = selectedVariantIndex,
                            isError = false,
                            timestamp = fallbackPreviousMessage?.timestamp ?: System.currentTimeMillis()
                        )
                        _streamingState.value = null
                        chatRepository.saveMessage(saved)
                        if (event.generatedImagePath == null) {
                            chatRepository.extractAndSaveArtifactsFromMessage(
                                conversationId = conversationId,
                                messageId = assistantMsgId,
                                markdownContent = displayContent
                            )
                            if (isAppBuilderActive) {
                                DirectGeminiAiRepository.extractPrimaryHtmlArtifact(displayContent)?.let { (appTitle, htmlCode) ->
                                    _autoLaunchArtifactPreview.value = ArtifactLaunchPayload(
                                        title = appTitle,
                                        language = "html",
                                        code = htmlCode
                                    )
                                }
                            }
                        }
                        if (liveReadoutActive && spokenCursor < displayContent.length) {
                            val tail = sanitizeForSpeech(displayContent.substring(spokenCursor))
                            if (tail.isNotBlank()) {
                                tts?.speak(
                                    tail,
                                    TextToSpeech.QUEUE_ADD,
                                    null,
                                    "live_stream_${UUID.randomUUID()}"
                                )
                            }
                        }
                    }
                    is AiStreamEvent.Failed -> {
                        _streamingState.value = null
                        if (fallbackPreviousMessage != null && priorVariants.isNotEmpty()) {
                            chatRepository.saveMessage(fallbackPreviousMessage)
                            _bannerMessage.value = event.errorMessage
                        } else {
                            val errorMsg = MessageEntity(
                                id = assistantMsgId,
                                conversationId = conversationId,
                                role = "assistant",
                                content = event.errorMessage,
                                modelDisplayName = auraHeaderLabel,
                                isError = true,
                                timestamp = fallbackPreviousMessage?.timestamp ?: System.currentTimeMillis()
                            )
                            chatRepository.saveMessage(errorMsg)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (fallbackPreviousMessage != null && priorVariants.isNotEmpty() && _streamingState.value?.contentText.isNullOrBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    chatRepository.saveMessage(fallbackPreviousMessage)
                }
            }
            throw e
        } catch (t: Throwable) {
            _streamingState.value = null
            if (fallbackPreviousMessage != null && priorVariants.isNotEmpty()) {
                chatRepository.saveMessage(fallbackPreviousMessage)
                _bannerMessage.value = t.localizedMessage ?: "Regeneration failed — kept previous answer."
            } else {
                val errorMsg = MessageEntity(
                    id = assistantMsgId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = t.localizedMessage ?: "Aura AI could not respond. Please retry.",
                    modelDisplayName = "Aura AI",
                    isError = true,
                    timestamp = fallbackPreviousMessage?.timestamp ?: System.currentTimeMillis()
                )
                chatRepository.saveMessage(errorMsg)
            }
        } finally {
            if (_streamingState.value?.messageId == assistantMsgId) {
                _streamingState.value = null
            }
        }
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
            .replace(Regex("```[\\s\\S]*$"), "")
            .replace(Regex("[#*`_>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
                        modelDisplayName = "Aura AI",
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
        val explicitPrefixes = listOf(
            "remember this about me:",
            "remember that ",
            "remember:",
            "remember "
        )
        val matchedPrefix = explicitPrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        if (matchedPrefix != null) {
            val fact = trimmed.substring(matchedPrefix.length).trim()
            if (fact.length in 8..240) {
                viewModelScope.launch {
                    chatRepository.addMemory(fact, "Auto")
                    _bannerMessage.value = "Saved to Aura Memory"
                }
            }
            return
        }

        if (!trimmed.contains("?") && !trimmed.contains("\n") && trimmed.length in 14..180) {
            val lower = trimmed.lowercase()
            val (fact, category) = when {
                lower.startsWith("my name is ") -> trimmed to "Profile"
                lower.startsWith("call me ") -> trimmed to "Profile"
                lower.startsWith("my tech stack is ") ||
                    lower.startsWith("i work with ") ||
                    lower.startsWith("i build with ") -> trimmed to "Tech Stack"
                lower.startsWith("i prefer ") ||
                    lower.startsWith("always reply in ") -> trimmed to "Preference"
                else -> null to ""
            }
            if (fact != null) {
                viewModelScope.launch {
                    chatRepository.addMemory(fact, category)
                    _bannerMessage.value = "Auto-saved to Aura Memory"
                }
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

    // --- Aura Autopilot Scheduled Background Agents ---
    fun createAutopilotTask(
        title: String,
        prompt: String,
        intervalHours: Int,
        useWebSearch: Boolean
    ) {
        if (title.isBlank() || prompt.isBlank()) return
        viewModelScope.launch {
            val created = chatRepository.createAutopilotTask(
                title = title,
                prompt = prompt,
                intervalHours = intervalHours,
                useWebSearch = useWebSearch
            )
            withContext(Dispatchers.IO) {
                val enabledTasks = database.auraDao().getEnabledAutopilotTasksOnce()
                AutopilotScheduler.syncSchedules(getApplication(), enabledTasks)
            }
            _bannerMessage.value = "Autopilot '${created.title}' scheduled every ${created.intervalHours}h"
        }
    }

    fun toggleAutopilotTask(task: AutopilotTaskEntity) {
        viewModelScope.launch {
            val nextEnabled = !task.isEnabled
            chatRepository.setAutopilotTaskEnabled(task.id, nextEnabled)
            withContext(Dispatchers.IO) {
                val enabledTasks = database.auraDao().getEnabledAutopilotTasksOnce()
                AutopilotScheduler.syncSchedules(getApplication(), enabledTasks)
            }
            _bannerMessage.value = if (nextEnabled) {
                "Autopilot '${task.title}' enabled"
            } else {
                "Autopilot '${task.title}' paused"
            }
        }
    }

    fun runAutopilotTaskNow(task: AutopilotTaskEntity) {
        if (_runningAutopilotTaskId.value != null) return
        viewModelScope.launch {
            _runningAutopilotTaskId.value = task.id
            val result = withContext(Dispatchers.IO) {
                AutopilotRunner.runTask(getApplication(), task)
            }
            _runningAutopilotTaskId.value = null
            result
                .onSuccess { convoId ->
                    _bannerMessage.value = "Autopilot brief ready for '${task.title}'"
                    openConversationById(convoId)
                }
                .onFailure { err ->
                    _bannerMessage.value = err.message ?: "Autopilot run failed."
                }
        }
    }

    fun deleteAutopilotTask(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AutopilotScheduler.cancelTaskJob(getApplication(), id)
            }
            chatRepository.deleteAutopilotTask(id)
            withContext(Dispatchers.IO) {
                val enabledTasks = database.auraDao().getEnabledAutopilotTasksOnce()
                AutopilotScheduler.syncSchedules(getApplication(), enabledTasks)
            }
            _bannerMessage.value = "Autopilot agent deleted"
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

    // --- Widget, Process Text & Share-to-Aura Deep Intents ---
    fun handleProcessText(selectedText: String) {
        val cleaned = selectedText.trim()
        if (cleaned.isBlank()) return
        navigateTo(AppDestination.Chat)
        _composerText.value = cleaned
        _bannerMessage.value = "Selected text loaded into Aura AI"
    }

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

    // --- Manus Agent, Compare Arena & AI Studio Workbench ---
    fun toggleAutonomousAgent() {
        viewModelScope.launch {
            val next = !autonomousAgentEnabled.value
            prefs.setAutonomousAgentEnabled(next)
            _bannerMessage.value = if (next) {
                "Manus Autonomous Agent ON — multi-step planning, live research & synthesis"
            } else {
                "Manus Autonomous Agent OFF"
            }
        }
    }

    fun toggleCompareArena() {
        viewModelScope.launch {
            val next = !compareArenaEnabled.value
            prefs.setCompareArenaEnabled(next)
            _bannerMessage.value = if (next) {
                "Compare Arena A/B ON — Engine Alpha vs Engine Beta side-by-side"
            } else {
                "Compare Arena A/B OFF"
            }
        }
    }

    fun toggleAppBuilderMode() {
        viewModelScope.launch {
            val next = !appBuilderEnabled.value
            prefs.setAppBuilderEnabled(next)
            _bannerMessage.value = if (next) {
                "Auto App Builder ON — describe any app and Aura builds & runs it live"
            } else {
                "Auto App Builder OFF"
            }
        }
    }

    fun setWorkbenchTemperature(value: Float) {
        viewModelScope.launch { prefs.setWorkbenchTemperature(value) }
    }

    fun setWorkbenchTopP(value: Float) {
        viewModelScope.launch { prefs.setWorkbenchTopP(value) }
    }

    fun setWorkbenchMaxTokens(value: Int) {
        viewModelScope.launch { prefs.setWorkbenchMaxTokens(value) }
    }

    fun setWorkbenchThinkingBudget(mode: ThinkingBudgetMode) {
        viewModelScope.launch { prefs.setWorkbenchThinkingBudget(mode.id) }
    }

    fun setWorkbenchJsonSchema(enabled: Boolean, schema: String) {
        viewModelScope.launch {
            prefs.setJsonSchemaMode(enabled)
            prefs.setJsonSchemaText(schema)
        }
    }

    fun setWorkbenchSystemOverride(text: String) {
        viewModelScope.launch { prefs.setSystemInstructionOverride(text) }
    }

    fun resetWorkbenchTuning() {
        viewModelScope.launch {
            prefs.resetWorkbenchTuning()
            _bannerMessage.value = "Workbench tuning reset to defaults"
        }
    }

    fun applyPromptTemplate(
        template: PromptTemplateEntity,
        variables: Map<String, String>,
        runImmediately: Boolean
    ) {
        val resolvedPrompt = ChatRepository.substituteTemplateVariables(template.userTemplate, variables)
        viewModelScope.launch {
            if (template.systemPrompt.isNotBlank()) {
                prefs.setSystemInstructionOverride(template.systemPrompt)
            }
            if (runImmediately) {
                sendPrompt(overridePrompt = resolvedPrompt)
            } else {
                _composerText.value = resolvedPrompt
                _bannerMessage.value = "Template '${template.title}' loaded"
            }
        }
    }

    fun createPromptTemplate(
        title: String,
        category: String,
        systemPrompt: String,
        userTemplate: String
    ) {
        if (title.isBlank() || userTemplate.isBlank()) return
        viewModelScope.launch {
            val created = chatRepository.createCustomPromptTemplate(title, category, systemPrompt, userTemplate)
            _bannerMessage.value = "Saved prompt template '${created.title}'"
        }
    }

    fun deletePromptTemplate(id: String) {
        viewModelScope.launch {
            chatRepository.deleteCustomPromptTemplate(id)
            _bannerMessage.value = "Prompt template deleted"
        }
    }

    // --- Claude-Style Project Workspaces ---
    fun selectActiveProject(projectId: String?) {
        viewModelScope.launch {
            prefs.setActiveProjectId(projectId)
            val label = if (projectId.isNullOrBlank()) {
                "Showing all conversations"
            } else {
                val p = chatRepository.getProjectById(projectId)
                "Workspace: ${p?.name ?: "Project"} active"
            }
            _bannerMessage.value = label
        }
    }

    fun assignCurrentChatToProject(projectId: String?) {
        val convoId = _activeConversationId.value ?: return
        viewModelScope.launch {
            chatRepository.updateConversationProject(convoId, projectId)
            _bannerMessage.value = if (projectId == null) {
                "Chat removed from workspace"
            } else {
                "Assigned current chat to workspace"
            }
        }
    }

    fun saveProject(
        id: String?,
        name: String,
        description: String,
        customInstructions: String,
        pinnedUrlsOrNotes: String
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val saved = chatRepository.saveProject(
                id = id,
                name = name,
                description = description,
                customInstructions = customInstructions,
                pinnedUrlsOrNotes = pinnedUrlsOrNotes
            )
            prefs.setActiveProjectId(saved.id)
            _bannerMessage.value = "Workspace '${saved.name}' saved & active"
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            chatRepository.deleteProject(projectId)
            if (activeProjectId.value == projectId) {
                prefs.setActiveProjectId(null)
            }
            _bannerMessage.value = "Workspace deleted"
        }
    }

    // --- Versioned Artifact Hub & Self-Built App Shortcuts ---
    fun pinBuiltAppShortcut(title: String, htmlCode: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                com.example.service.AppShortcutHelper.pinBuiltAppToHomeScreen(
                    context = getApplication(),
                    appTitle = title,
                    htmlCode = htmlCode
                )
            }
            _bannerMessage.value = if (ok) {
                "Shortcut request sent — confirm Add on your Home Screen"
            } else {
                "Home Screen shortcuts not supported on this launcher"
            }
        }
    }

    fun openPinnedAppShortcut(
        artifactId: String?,
        fallbackTitle: String?,
        fallbackFilePath: String?
    ) {
        viewModelScope.launch {
            navigateTo(AppDestination.Chat)
            val fromFile = withContext(Dispatchers.IO) {
                fallbackFilePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) f.readText(Charsets.UTF_8) else null
                }
            }
            if (!fromFile.isNullOrBlank()) {
                _autoLaunchArtifactPreview.value = ArtifactLaunchPayload(
                    title = fallbackTitle?.ifBlank { null } ?: "Aura App",
                    language = "html",
                    code = fromFile
                )
                return@launch
            }
            if (!artifactId.isNullOrBlank()) {
                val artifact = chatRepository.getArtifactById(artifactId)
                if (artifact != null) {
                    _autoLaunchArtifactPreview.value = ArtifactLaunchPayload(
                        title = artifact.title,
                        language = artifact.language,
                        code = artifact.code
                    )
                }
            }
        }
    }

    fun deleteArtifact(id: String) {
        viewModelScope.launch {
            chatRepository.deleteArtifact(id)
            _bannerMessage.value = "Artifact removed from Hub"
        }
    }

    // --- Custom Webhook / REST Tools (MCP-Lite) ---
    fun createCustomWebhook(
        name: String,
        description: String,
        endpointUrl: String,
        httpMethod: String,
        headersJson: String,
        bodyTemplate: String
    ) {
        if (name.isBlank() || endpointUrl.isBlank()) return
        viewModelScope.launch {
            val created = chatRepository.createCustomWebhook(
                name = name,
                description = description,
                endpointUrl = endpointUrl,
                httpMethod = httpMethod,
                headersJson = headersJson,
                bodyTemplate = bodyTemplate
            )
            _bannerMessage.value = "REST tool '${created.name}' added & enabled"
        }
    }

    fun toggleCustomWebhook(id: String, enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setCustomWebhookEnabled(id, enabled)
        }
    }

    fun deleteCustomWebhook(id: String) {
        viewModelScope.launch {
            chatRepository.deleteCustomWebhook(id)
            _bannerMessage.value = "REST tool deleted"
        }
    }

    suspend fun testWebhook(webhook: CustomWebhookEntity, sampleQuery: String): WebhookExecutionResult {
        return aiRepository.testWebhook(webhook, sampleQuery)
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
