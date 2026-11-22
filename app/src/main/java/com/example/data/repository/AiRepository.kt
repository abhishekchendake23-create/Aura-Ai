package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.KnowledgeChunkEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import com.example.data.model.AiModelTier
import com.example.data.model.AppLanguage
import com.example.data.model.CapabilityMode
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AuraAiRepository"
private const val INFERENCE_TIMEOUT_MS = 240_000L
private const val STREAM_EMIT_THROTTLE_MS = 24L
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
private const val DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

enum class ModelProvider(val displayName: String) {
    GEMINI("Gemini"),
    OPENROUTER("OpenRouter"),
    DEEPSEEK("DeepSeek"),
    GROQ("Groq")
}

data class ModelCandidate(
    val provider: ModelProvider,
    val modelId: String,
    val displayName: String,
    val supportsThinkingConfig: Boolean = false,
    val supportsSystemInstruction: Boolean = true,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = true
)

data class SourceCitation(
    val title: String,
    val uri: String
)

fun serializeSources(sources: List<SourceCitation>): String? {
    if (sources.isEmpty()) return null
    val array = JSONArray()
    sources.forEach { src ->
        val obj = JSONObject()
        obj.put("title", src.title)
        obj.put("uri", src.uri)
        array.put(obj)
    }
    return array.toString()
}

fun parseSources(rawJson: String?): List<SourceCitation> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawJson)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                val uri = obj.optString("uri").trim()
                if (uri.isNotBlank()) {
                    add(SourceCitation(title = title.ifBlank { uri }, uri = uri))
                }
            }
        }
    }.getOrDefault(emptyList())
}

data class AiRequest(
    val prompt: String,
    val modelTier: AiModelTier,
    val capabilityMode: CapabilityMode,
    val language: AppLanguage = AppLanguage.AUTO,
    val persona: PersonaEntity?,
    val history: List<MessageEntity>,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val attachmentTextSnippet: String? = null,
    val extraAttachmentUris: List<String> = emptyList(),
    val activeMemories: List<String> = emptyList(),
    val vaultContextSnippets: List<KnowledgeChunkEntity> = emptyList(),
    val useWebSearch: Boolean = false,
    val useCodeExecution: Boolean = false,
    val enableSelfVerify: Boolean = false
)

data class AutoRouteDecision(
    val resolvedModel: AiModelTier,
    val resolvedCapability: CapabilityMode,
    val useWebSearch: Boolean,
    val routeLabel: String
)

sealed interface AiStreamEvent {
    data class ModelActivated(
        val modelId: String,
        val modelDisplayName: String,
        val switchedFromModel: String? = null,
        val switchReason: String? = null
    ) : AiStreamEvent

    data class ThinkingChunk(
        val thinkingText: String,
        val elapsedMs: Long
    ) : AiStreamEvent

    data class ContentChunk(
        val accumulatedText: String
    ) : AiStreamEvent

    data class Completed(
        val fullText: String,
        val thinkingText: String?,
        val thinkingDurationMs: Long,
        val generatedImagePath: String? = null,
        val sources: List<SourceCitation> = emptyList(),
        val modelDisplayName: String = "Aura AI"
    ) : AiStreamEvent

    data class Failed(
        val errorMessage: String
    ) : AiStreamEvent
}

interface AiRepository {
    fun streamResponse(request: AiRequest): Flow<AiStreamEvent>
    suspend fun enhancePrompt(rawPrompt: String): Result<String>
}

class DirectGeminiAiRepository(private val appContext: Context) : AiRepository {

    private val prefs = PreferencesManager(appContext)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val liveWebSearchEngine = LiveWebSearchEngine(httpClient)

    private suspend fun requireGeminiApiKey(): String {
        val key = prefs.getEffectiveApiKey()
        if (key.isBlank()) {
            throw IllegalStateException("MISSING_GEMINI_API_KEY")
        }
        return key
    }

    override suspend fun enhancePrompt(rawPrompt: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = rawPrompt.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a prompt to enhance."))
        }
        val geminiKeys = prefs.getEffectiveApiKeyPool()
        val groqKeys = prefs.getEffectiveGroqKeyPool()
        val openRouterKeys = prefs.getEffectiveOpenRouterKeyPool()
        val deepSeekKeys = prefs.getEffectiveDeepSeekKeyPool()
        val preferFastGroq = prefs.preferFastGroq.first()

        if (geminiKeys.isEmpty() && groqKeys.isEmpty() && openRouterKeys.isEmpty() && deepSeekKeys.isEmpty()) {
            return@withContext Result.failure(Exception(sanitizeAuraError("MISSING_API_KEY", isImage = false)))
        }

        val systemPrompt = "You are a frontier prompt engineer for Aura AI (Abhynex). Rewrite the user's rough prompt into a crystal-clear, high-leverage, structured prompt specifying objective, constraints, edge cases, and desired output format. Keep it concise (max 4-6 lines), preserve the user's original intent and language style, and return ONLY the rewritten prompt text with no quotes or preamble."

        var lastErr: Throwable? = null
        val groqFirst = groqKeys.isNotEmpty() && (preferFastGroq || geminiKeys.isEmpty())

        if (groqFirst) {
            for (gKey in groqKeys) {
                val groqResult = enhancePromptViaGroq(gKey, systemPrompt, trimmed)
                groqResult.onSuccess { return@withContext Result.success(it) }
                lastErr = groqResult.exceptionOrNull()
            }
        }

        for (gemKey in geminiKeys) {
            val geminiResult = enhancePromptViaGemini(gemKey, systemPrompt, trimmed)
            geminiResult.onSuccess { return@withContext Result.success(it) }
            lastErr = geminiResult.exceptionOrNull()
        }

        if (!groqFirst) {
            for (gKey in groqKeys) {
                val groqResult = enhancePromptViaGroq(gKey, systemPrompt, trimmed)
                groqResult.onSuccess { return@withContext Result.success(it) }
                lastErr = groqResult.exceptionOrNull()
            }
        }

        for (orKey in openRouterKeys) {
            val orResult = enhancePromptViaOpenAiCompat(
                url = OPENROUTER_API_URL,
                apiKey = orKey,
                modelIds = listOf("deepseek/deepseek-chat-v3-0324:free", "openrouter/free"),
                systemPrompt = systemPrompt,
                trimmedPrompt = trimmed,
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://abhynex.aura.ai",
                    "X-Title" to "Aura AI"
                )
            )
            orResult.onSuccess { return@withContext Result.success(it) }
            lastErr = orResult.exceptionOrNull()
        }

        for (dsKey in deepSeekKeys) {
            val dsResult = enhancePromptViaOpenAiCompat(
                url = DEEPSEEK_API_URL,
                apiKey = dsKey,
                modelIds = listOf("deepseek-chat"),
                systemPrompt = systemPrompt,
                trimmedPrompt = trimmed
            )
            dsResult.onSuccess { return@withContext Result.success(it) }
            lastErr = dsResult.exceptionOrNull()
        }

        Result.failure(Exception(sanitizeAuraError(lastErr?.localizedMessage ?: lastErr?.message, isImage = false)))
    }

    private suspend fun enhancePromptViaOpenAiCompat(
        url: String,
        apiKey: String,
        modelIds: List<String>,
        systemPrompt: String,
        trimmedPrompt: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result<String> {
        var lastErr: Throwable? = null
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        }
        for (modelId in modelIds) {
            try {
                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("stream", false)
                    put("temperature", 0.5)
                    put("max_tokens", 1024)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", systemPrompt))
                            put(JSONObject().put("role", "user").put("content", "Rewrite and sharpen this prompt:\n$trimmedPrompt"))
                        }
                    )
                }
                val responseJson = withTimeout(14_000L) {
                    executeUnaryJsonRequest(url = url, bodyJson = payload, headers = headers)
                }
                val enhanced = extractTextFromGroqResponse(responseJson).trim()
                if (enhanced.isNotBlank()) return Result.success(enhanced)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        return Result.failure(lastErr ?: IOException("Prompt enhancement failed"))
    }

    private suspend fun enhancePromptViaGroq(
        groqKey: String,
        systemPrompt: String,
        trimmedPrompt: String
    ): Result<String> {
        val groqCandidates = listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b")
        var lastErr: Throwable? = null
        for (modelId in groqCandidates) {
            try {
                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("stream", false)
                    put("temperature", 0.5)
                    put("max_completion_tokens", 1024)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", systemPrompt))
                            put(JSONObject().put("role", "user").put("content", "Rewrite and sharpen this prompt:\n$trimmedPrompt"))
                        }
                    )
                }
                val responseJson = withTimeout(12_000L) {
                    executeUnaryJsonRequest(
                        url = GROQ_API_URL,
                        bodyJson = payload,
                        headers = mapOf("Authorization" to "Bearer $groqKey")
                    )
                }
                val enhanced = extractTextFromGroqResponse(responseJson).trim()
                if (enhanced.isNotBlank()) {
                    return Result.success(enhanced)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        return Result.failure(lastErr ?: IOException("Prompt enhancement failed"))
    }

    private suspend fun enhancePromptViaGemini(
        apiKey: String,
        systemPrompt: String,
        trimmedPrompt: String
    ): Result<String> {
        val candidates = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")
        var lastErr: Throwable? = null
        for (modelId in candidates) {
            try {
                val payload = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", systemPrompt))
                        )
                    )
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put("text", "Rewrite and sharpen this prompt:\n$trimmedPrompt")
                                    )
                                )
                            }
                        )
                    )
                }

                val responseJson = withTimeout(15_000L) {
                    executeUnaryJsonRequest(
                        url = "$GEMINI_API_BASE/$modelId:generateContent?key=$apiKey",
                        bodyJson = payload
                    )
                }
                val enhanced = extractTextFromResponse(responseJson).trim()
                if (enhanced.isNotBlank()) {
                    return Result.success(enhanced)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastErr = t
                if (!isRecoverableModelError(t)) break
            }
        }
        return Result.failure(lastErr ?: IOException("Prompt enhancement failed"))
    }

    override fun streamResponse(request: AiRequest): Flow<AiStreamEvent> = flow {
        if (request.modelTier.isImageModel) {
            emit(generateOrEditImage(request))
        } else {
            streamChatOrVision(request) { event -> emit(event) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamChatOrVision(
        originalRequest: AiRequest,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val geminiKeys = prefs.getEffectiveApiKeyPool()
        val groqKeys = prefs.getEffectiveGroqKeyPool()
        val openRouterKeys = prefs.getEffectiveOpenRouterKeyPool()
        val deepSeekKeys = prefs.getEffectiveDeepSeekKeyPool()
        val exaKeys = prefs.getEffectiveExaKeyPool()
        val preferFastGroq = prefs.preferFastGroq.first()

        val geminiKey = geminiKeys.firstOrNull().orEmpty()
        val groqKey = groqKeys.firstOrNull().orEmpty()

        if (geminiKeys.isEmpty() && groqKeys.isEmpty() && openRouterKeys.isEmpty() && deepSeekKeys.isEmpty()) {
            emitEvent(AiStreamEvent.Failed(sanitizeAuraError("MISSING_API_KEY", isImage = false)))
            return
        }

        val hasExplicitUrl = LiveWebSearchEngine.extractUrls(originalRequest.prompt).isNotEmpty()
        val autoFreshWeb = !originalRequest.useCodeExecution &&
            !originalRequest.attachmentIsImage &&
            hasFreshWebCue(originalRequest.prompt)

        val request = if (originalRequest.modelTier == AiModelTier.AUTO_ROUTER) {
            val decision = classifyAutoRoute(originalRequest)
            originalRequest.copy(
                modelTier = decision.resolvedModel,
                capabilityMode = decision.resolvedCapability,
                useWebSearch = decision.useWebSearch || originalRequest.useWebSearch || hasExplicitUrl
            )
        } else {
            val shouldSearch = if (originalRequest.useCodeExecution) {
                originalRequest.useWebSearch
            } else {
                originalRequest.useWebSearch || hasExplicitUrl || autoFreshWeb
            }
            originalRequest.copy(useWebSearch = shouldSearch)
        }

        val startMs = System.currentTimeMillis()
        if (request.capabilityMode == CapabilityMode.DEEP_RESEARCH) {
            streamDeepResearch(
                request = request,
                geminiKeys = geminiKeys,
                groqKeys = groqKeys,
                openRouterKeys = openRouterKeys,
                deepSeekKeys = deepSeekKeys,
                exaKeys = exaKeys,
                preferFastGroq = preferFastGroq,
                startMs = startMs,
                emitEvent = emitEvent
            )
            return
        }

        var liveWebContext: LiveWebContext? = null
        val thinkingPrefixBuilder = StringBuilder()
        if (request.useWebSearch) {
            emitEvent(
                AiStreamEvent.ThinkingChunk(
                    thinkingText = "Searching live web for verified current sources...\n",
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            )
            val explicitUrls = LiveWebSearchEngine.extractUrls(request.prompt)
            val searchQueries = rewriteSearchQueriesForWeb(
                rawPrompt = request.prompt,
                groqKey = groqKey,
                geminiKey = geminiKey
            )
            liveWebContext = runCatching {
                liveWebSearchEngine.gatherLiveContext(
                    queries = searchQueries,
                    explicitUrls = explicitUrls,
                    maxResults = 7,
                    exaApiKey = exaKeys.firstOrNull().orEmpty(),
                    preferLiveCrawl = autoFreshWeb || request.useWebSearch,
                    exaApiKeys = exaKeys
                )
            }.getOrNull()

            if (liveWebContext != null && liveWebContext.results.isNotEmpty()) {
                val webLog = "Live Web Search: checked ${liveWebContext.results.size} current sources (${liveWebContext.executedQueries.joinToString(" · ")})\n"
                thinkingPrefixBuilder.append(webLog)
                emitEvent(
                    AiStreamEvent.ThinkingChunk(
                        thinkingText = thinkingPrefixBuilder.toString(),
                        elapsedMs = System.currentTimeMillis() - startMs
                    )
                )
            }
        }

        // Dual-Pass Self-Verify Engine ("Zero-Bug Accuracy Mode")
        var verifyGuardrailsBlock = ""
        if (request.enableSelfVerify && !request.modelTier.isImageModel && request.prompt.trim().length >= 12) {
            thinkingPrefixBuilder.append("Aura Verify · Auditing invariants, edge cases & potential bugs...\n")
            emitEvent(
                AiStreamEvent.ThinkingChunk(
                    thinkingText = thinkingPrefixBuilder.toString(),
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            )
            val auditNotes = runSelfVerifyAuditPass(
                prompt = request.prompt,
                capabilityMode = request.capabilityMode,
                groqKeys = groqKeys,
                geminiKeys = geminiKeys,
                openRouterKeys = openRouterKeys,
                deepSeekKeys = deepSeekKeys
            )
            if (auditNotes.isNotBlank()) {
                verifyGuardrailsBlock = "### AURA SELF-VERIFY PRE-FLIGHT CHECKLIST\n" +
                    "Verify every item below before emitting your final solution:\n$auditNotes\n\n"
                thinkingPrefixBuilder.append("Aura Verify Checklist Ready:\n$auditNotes\n")
                emitEvent(
                    AiStreamEvent.ThinkingChunk(
                        thinkingText = thinkingPrefixBuilder.toString(),
                        elapsedMs = System.currentTimeMillis() - startMs
                    )
                )
            }
        }

        val systemText = buildSystemInstruction(
            persona = request.persona,
            capabilityMode = request.capabilityMode,
            language = request.language,
            useWebSearch = request.useWebSearch,
            useCodeExecution = request.useCodeExecution,
            activeMemories = request.activeMemories,
            hasVaultContext = request.vaultContextSnippets.isNotEmpty()
        )
        val attachedBitmaps = loadAttachedBitmaps(request)
        val livePromptNotice = when {
            !request.useWebSearch -> ""
            liveWebContext != null && liveWebContext.formattedPromptBlock.isNotBlank() ->
                liveWebContext.formattedPromptBlock
            geminiKeys.isEmpty() ->
                "### LIVE WEB SEARCH STATUS\nLive web search returned 0 reachable results right now (device may be offline or search endpoints blocked). Do NOT guess or invent current prices, scores, news, or post-cutoff facts; state clearly what could not be verified live."
            else -> ""
        }
        val combinedPrefixBlock = listOf(livePromptNotice, verifyGuardrailsBlock)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val promptText = buildUserPromptText(request, liveWebContextBlock = combinedPrefixBlock)
        val liveSources = liveWebContext?.sourceCitations.orEmpty()

        val hasExaResults = exaKeys.isNotEmpty() && !liveWebContext?.results.isNullOrEmpty()
        val requiresGeminiTools = request.useCodeExecution ||
            attachedBitmaps.size > 1 ||
            (request.useWebSearch && !hasExaResults)

        val nonGeminiBlock = listOf(
            if (request.useWebSearch && livePromptNotice.isBlank()) {
                "### LIVE WEB SEARCH STATUS\nLive web search returned 0 reachable results right now. Do NOT guess or invent current prices, scores, news, or post-cutoff facts; state clearly what could not be verified live."
            } else {
                livePromptNotice
            },
            verifyGuardrailsBlock
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        val failure = streamAcrossProvidersChain(
            requestedTier = originalRequest.modelTier,
            effectiveRequest = request,
            geminiKeys = geminiKeys,
            openRouterKeys = openRouterKeys,
            deepSeekKeys = deepSeekKeys,
            groqKeys = groqKeys,
            preferFastGroq = preferFastGroq,
            requiresGeminiTools = requiresGeminiTools,
            systemText = systemText,
            promptText = promptText,
            nonGeminiFallbackPromptNotice = nonGeminiBlock,
            attachedBitmaps = attachedBitmaps,
            startMs = startMs,
            initialSources = liveSources,
            initialThinkingPrefix = thinkingPrefixBuilder.toString(),
            emitEvent = emitEvent
        )

        if (failure != null) {
            emitEvent(
                AiStreamEvent.Failed(
                    sanitizeAuraError(failure.localizedMessage ?: failure.message, isImage = false)
                )
            )
        }
    }

    private suspend fun runSelfVerifyAuditPass(
        prompt: String,
        capabilityMode: CapabilityMode,
        groqKeys: List<String>,
        geminiKeys: List<String>,
        openRouterKeys: List<String>,
        deepSeekKeys: List<String>
    ): String {
        val auditInstruction = buildString {
            append("You are Aura Verify, a strict pre-flight technical auditor. ")
            append("In 4 concise bullet points (max 110 words total), list the critical edge cases, invariants, missing-import/null/concurrency traps, or mathematical boundary checks required to answer this request with zero bugs.\n\n")
            append("Mode: ${capabilityMode.label}\n")
            append("Request:\n${prompt.take(2500)}")
        }
        return runCatching {
            for (gKey in groqKeys) {
                val payload = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("stream", false)
                    put("temperature", 0.15)
                    put("max_completion_tokens", 280)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", auditInstruction)))
                }
                val text = runCatching {
                    withTimeout(7_500L) {
                        extractTextFromGroqResponse(
                            executeUnaryJsonRequest(GROQ_API_URL, payload, mapOf("Authorization" to "Bearer $gKey"))
                        )
                    }
                }.getOrNull()?.trim()
                if (!text.isNullOrBlank()) return@runCatching text
            }
            for (gemKey in geminiKeys) {
                val payload = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", auditInstruction)))
                    )
                )
                val text = runCatching {
                    withTimeout(8_500L) {
                        extractTextFromResponse(
                            executeUnaryJsonRequest("$GEMINI_API_BASE/gemini-2.5-flash-lite:generateContent?key=$gemKey", payload)
                        )
                    }
                }.getOrNull()?.trim()
                if (!text.isNullOrBlank()) return@runCatching text
            }
            for (orKey in openRouterKeys) {
                val res = enhancePromptViaOpenAiCompat(
                    url = OPENROUTER_API_URL,
                    apiKey = orKey,
                    modelIds = listOf("deepseek/deepseek-chat-v3-0324:free", "openrouter/free"),
                    systemPrompt = "Return 4 crisp technical audit bullets.",
                    trimmedPrompt = auditInstruction,
                    extraHeaders = mapOf("HTTP-Referer" to "https://abhynex.aura.ai", "X-Title" to "Aura AI")
                ).getOrNull()?.trim()
                if (!res.isNullOrBlank()) return@runCatching res
            }
            for (dsKey in deepSeekKeys) {
                val res = enhancePromptViaOpenAiCompat(
                    url = DEEPSEEK_API_URL,
                    apiKey = dsKey,
                    modelIds = listOf("deepseek-chat"),
                    systemPrompt = "Return 4 crisp technical audit bullets.",
                    trimmedPrompt = auditInstruction
                ).getOrNull()?.trim()
                if (!res.isNullOrBlank()) return@runCatching res
            }
            ""
        }.getOrDefault("")
    }

    private suspend fun streamDeepResearch(
        request: AiRequest,
        geminiKeys: List<String>,
        groqKeys: List<String>,
        openRouterKeys: List<String>,
        deepSeekKeys: List<String>,
        exaKeys: List<String>,
        preferFastGroq: Boolean,
        startMs: Long,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val geminiKey = geminiKeys.firstOrNull().orEmpty()
        val groqKey = groqKeys.firstOrNull().orEmpty()
        val thinkingLog = StringBuilder("Deep Research Agent · Phase 1/4: Designing research plan & sub-queries...\n")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val subQueries = planResearchQueries(request.prompt, geminiKey, groqKey)
        thinkingLog.append("Research Angles:\n")
        subQueries.forEachIndexed { idx, q ->
            thinkingLog.append("  ${idx + 1}. $q\n")
        }
        thinkingLog.append("\nPhase 2/4: Gathering live verified facts & cross-checking sources...\n")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val collectedSources = linkedMapOf<String, SourceCitation>()
        val researchNotes = StringBuilder()

        for ((idx, subQ) in subQueries.withIndex()) {
            val liveStepContext = runCatching {
                liveWebSearchEngine.gatherLiveContext(
                    queries = listOf(subQ),
                    maxResults = 4,
                    exaApiKey = exaKeys.firstOrNull().orEmpty(),
                    preferLiveCrawl = true,
                    exaApiKeys = exaKeys
                )
            }.getOrNull()

            liveStepContext?.sourceCitations?.forEach { (uri, src) ->
                collectedSources[uri] = src
            }
            val liveBlock = liveStepContext?.formattedPromptBlock.orEmpty()

            var stepFindings = ""
            var geminiStepSources = 0
            for (gKey in geminiKeys) {
                val ok = runCatching {
                    val (findings, sources) = runGroundedResearchStep(gKey, subQ, liveBlock)
                    sources.forEach { src -> collectedSources[src.uri] = src }
                    geminiStepSources = sources.size
                    stepFindings = findings
                }.isSuccess
                if (ok && stepFindings.isNotBlank()) break
            }

            if (stepFindings.isBlank()) {
                for (grKey in groqKeys) {
                    val ok = runCatching {
                        stepFindings = runGroqResearchStep(grKey, subQ, liveBlock)
                    }.isSuccess
                    if (ok && stepFindings.isNotBlank()) break
                }
            }

            if (stepFindings.isNotBlank()) {
                researchNotes.append("### Finding ${idx + 1}: $subQ\n$stepFindings\n\n")
            } else if (liveBlock.isNotBlank()) {
                researchNotes.append("### Live Web Findings ${idx + 1}: $subQ\n$liveBlock\n\n")
            }

            val totalStepSources = maxOf(geminiStepSources, liveStepContext?.results?.size ?: 0)
            thinkingLog.append("  • Angle ${idx + 1} verified ($totalStepSources live sources).\n")
            emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))
        }

        // Phase 3/4 Chained Quantitative & Consistency Verification (Web -> Verify Math -> Report)
        var verifiedMathMemo = ""
        val needsQuantitativePass = Regex("(\\d+%?|compare|vs|benchmark|price|market|cost|roi|growth|projection|ratio|statistics|data)", RegexOption.IGNORE_CASE)
            .containsMatchIn(request.prompt) && researchNotes.isNotBlank()
        if (needsQuantitativePass) {
            thinkingLog.append("\nPhase 3/4: Cross-checking numbers, metrics & ratios via Aura Sandbox...\n")
            emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))
            verifiedMathMemo = runChainedQuantitativeCheck(
                prompt = request.prompt,
                researchNotes = researchNotes.toString(),
                geminiKeys = geminiKeys,
                groqKeys = groqKeys
            )
            if (verifiedMathMemo.isNotBlank()) {
                thinkingLog.append("  • Quantitative verification completed.\n")
                emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))
            }
        }

        thinkingLog.append("\nPhase 4/4: Synthesizing structured executive report with comparison tables & takeaways...")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val synthesisPrompt = buildString {
            append("Original Research Request:\n${request.prompt}\n\n")
            if (researchNotes.isNotBlank()) {
                append("Verified Multi-Angle Research Briefs:\n$researchNotes\n\n")
            } else {
                append("Notice: Live research angles returned sparse results; stick strictly to verified facts and clearly flag anything uncertain.\n\n")
            }
            if (verifiedMathMemo.isNotBlank()) {
                append("Chained Quantitative Verification Notes:\n$verifiedMathMemo\n\n")
            }
            append(
                "Synthesize a comprehensive, publication-grade executive research report answering the original request. " +
                    "Include: (1) Executive Summary, (2) Structured Markdown Comparison Table where relevant, " +
                    "(3) Deep Technical / Practical Breakdown with risks & trade-offs, and (4) Clear Actionable Recommendations. " +
                    "If visual comparison adds clarity, you may also include a self-contained ```svg chart block."
            )
        }

        val synthesisRequest = request.copy(
            prompt = synthesisPrompt,
            modelTier = if (request.modelTier.isDeepSeekTier) request.modelTier else AiModelTier.GEMINI_4_ARGON,
            capabilityMode = CapabilityMode.DEEP_THINK,
            useWebSearch = true
        )
        val systemText = buildSystemInstruction(
            persona = request.persona,
            capabilityMode = CapabilityMode.DEEP_RESEARCH,
            language = request.language,
            useWebSearch = true,
            useCodeExecution = false,
            activeMemories = request.activeMemories,
            hasVaultContext = request.vaultContextSnippets.isNotEmpty()
        )
        val bitmaps = loadAttachedBitmaps(request)

        val err = streamAcrossProvidersChain(
            requestedTier = request.modelTier,
            effectiveRequest = synthesisRequest,
            geminiKeys = geminiKeys,
            openRouterKeys = openRouterKeys,
            deepSeekKeys = deepSeekKeys,
            groqKeys = groqKeys,
            preferFastGroq = preferFastGroq,
            requiresGeminiTools = false,
            systemText = systemText,
            promptText = buildUserPromptText(synthesisRequest),
            nonGeminiFallbackPromptNotice = "",
            attachedBitmaps = bitmaps,
            startMs = startMs,
            initialSources = collectedSources,
            initialThinkingPrefix = thinkingLog.toString(),
            emitEvent = emitEvent
        )

        if (err != null) {
            emitEvent(
                AiStreamEvent.Failed(
                    sanitizeAuraError(err.localizedMessage ?: err.message, isImage = false)
                )
            )
        }
    }

    private suspend fun runChainedQuantitativeCheck(
        prompt: String,
        researchNotes: String,
        geminiKeys: List<String>,
        groqKeys: List<String>
    ): String {
        val quantPrompt = buildString {
            append("Verify all numerical claims, percentages, dates, and calculations from the research notes below using Python code execution if numbers need arithmetic verification.\n")
            append("Return ONLY a concise 3-5 bullet verified numbers & discrepancies memo (max 130 words).\n\n")
            append("Question: ${prompt.take(500)}\n\n")
            append("Notes:\n${researchNotes.take(4000)}")
        }
        for (gemKey in geminiKeys) {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", quantPrompt)))
                    )
                )
                put("tools", JSONArray().put(JSONObject().put("code_execution", JSONObject())))
            }
            val text = runCatching {
                withTimeout(14_000L) {
                    extractTextFromResponse(
                        executeUnaryJsonRequest("$GEMINI_API_BASE/gemini-2.5-flash:generateContent?key=$gemKey", payload)
                    )
                }
            }.getOrNull()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        for (grKey in groqKeys) {
            val payload = JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("stream", false)
                put("temperature", 0.1)
                put("max_completion_tokens", 280)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", quantPrompt)))
            }
            val text = runCatching {
                withTimeout(9_000L) {
                    extractTextFromGroqResponse(
                        executeUnaryJsonRequest(GROQ_API_URL, payload, mapOf("Authorization" to "Bearer $grKey"))
                    )
                }
            }.getOrNull()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return ""
    }

    private suspend fun rewriteSearchQueriesForWeb(
        rawPrompt: String,
        groqKey: String,
        geminiKey: String
    ): List<String> {
        val cleaned = LiveWebSearchEngine.extractCleanSearchQuery(rawPrompt)
        if (cleaned.isBlank()) return emptyList()

        val wordCount = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }
        val looksConversationalOrNonEnglish = wordCount > 7 ||
            Regex("[\\u0900-\\u097F]").containsMatchIn(cleaned) ||
            Regex("\\b(aaj|aajcha|aajche|kalcha|ata|sadhya|bhav|kay|kon|kasa|kaise|kya|kitna|jinkla|madhe|batao|saang|please|tell me|what is|who is)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(cleaned)

        if (!looksConversationalOrNonEnglish) {
            return listOf(cleaned)
        }

        val todayStr = currentFormattedDateOnly()
        val rewritePrompt = "Today is $todayStr. Convert the user's question into 1 or 2 short, crisp English web search keyword queries (one per line, no quotes, no numbering, max 10 words each) to find the exact live answer.\nUser question: $cleaned"

        val rewritten = runCatching {
            when {
                groqKey.isNotBlank() -> {
                    val payload = JSONObject().apply {
                        put("model", "llama-3.1-8b-instant")
                        put("stream", false)
                        put("temperature", 0.1)
                        put("max_completion_tokens", 70)
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", rewritePrompt)))
                    }
                    extractTextFromGroqResponse(
                        withTimeout(3_800L) {
                            executeUnaryJsonRequest(GROQ_API_URL, payload, mapOf("Authorization" to "Bearer $groqKey"))
                        }
                    )
                }
                geminiKey.isNotBlank() -> {
                    val payload = JSONObject().put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", rewritePrompt)))
                        )
                    )
                    extractTextFromResponse(
                        withTimeout(4_500L) {
                            executeUnaryJsonRequest("$GEMINI_API_BASE/gemini-2.5-flash-lite:generateContent?key=$geminiKey", payload)
                        }
                    )
                }
                else -> ""
            }
        }.getOrDefault("")

        val parsed = rewritten.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("^\\d+[.)]\\s*"), "").trim('"', '\'', ' ') }
            .filter { it.length >= 3 }
            .distinct()
            .take(2)

        return if (parsed.isNotEmpty()) {
            (parsed + cleaned).distinct().take(2)
        } else {
            listOf(cleaned)
        }
    }

    private suspend fun planResearchQueries(
        prompt: String,
        geminiKey: String,
        groqKey: String
    ): List<String> {
        val cleaned = LiveWebSearchEngine.extractCleanSearchQuery(prompt)
        val fallback = listOf(
            cleaned.take(120),
            "latest facts benchmarks comparisons ${cleaned.take(80)}",
            "key risks trade-offs ${cleaned.take(80)}"
        )
        val todayStr = currentFormattedDateOnly()
        val plannerPrompt =
            "Today is $todayStr. Generate 3 short, distinct English web search queries (one per line, no numbering or bullets) to thoroughly verify and research:\n$cleaned"

        val rawPlan = runCatching {
            when {
                groqKey.isNotBlank() -> {
                    val payload = JSONObject().apply {
                        put("model", "llama-3.1-8b-instant")
                        put("stream", false)
                        put("max_completion_tokens", 160)
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", plannerPrompt)))
                    }
                    extractTextFromGroqResponse(
                        withTimeout(8_000L) {
                            executeUnaryJsonRequest(GROQ_API_URL, payload, mapOf("Authorization" to "Bearer $groqKey"))
                        }
                    )
                }
                geminiKey.isNotBlank() -> {
                    val payload = JSONObject().put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("role", "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", plannerPrompt)))
                        )
                    )
                    extractTextFromResponse(
                        withTimeout(10_000L) {
                            executeUnaryJsonRequest("$GEMINI_API_BASE/gemini-2.5-flash-lite:generateContent?key=$geminiKey", payload)
                        }
                    )
                }
                else -> ""
            }
        }.getOrDefault("")

        val lines = rawPlan.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.length >= 5 }
            .take(3)
        return if (lines.size >= 2) lines else fallback
    }

    private suspend fun runGroundedResearchStep(
        apiKey: String,
        subQuery: String,
        liveContextBlock: String = ""
    ): Pair<String, List<SourceCitation>> {
        val todayStr = currentFormattedDateOnly()
        val stepPrompt = buildString {
            append("Today is $todayStr.\n")
            if (liveContextBlock.isNotBlank()) {
                append("$liveContextBlock\n\n")
            }
            append("Verify using live search and provide concise, factual, verifiable findings and exact data points (max 180 words) for: $subQuery")
        }
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", stepPrompt))
                    )
                )
            )
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        val json = withTimeout(18_000L) {
            executeUnaryJsonRequest("$GEMINI_API_BASE/gemini-2.5-flash:generateContent?key=$apiKey", payload)
        }
        val text = extractTextFromResponse(json).trim()
        val citations = buildList {
            json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("groundingMetadata")
                ?.optJSONArray("groundingChunks")
                ?.let { chunks ->
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val uri = web.optString("uri").trim()
                        if (uri.isNotBlank()) {
                            add(SourceCitation(title = web.optString("title").trim().ifBlank { uri }, uri = uri))
                        }
                    }
                }
        }
        return text to citations
    }

    private suspend fun runGroqResearchStep(
        groqKey: String,
        subQuery: String,
        liveContextBlock: String = ""
    ): String {
        val todayStr = currentFormattedDateOnly()
        val stepPrompt = buildString {
            append("Today is $todayStr.\n")
            if (liveContextBlock.isNotBlank()) {
                append("$liveContextBlock\n\n")
            }
            append("Using ONLY the live web results above (or clearly flagging missing live data without guessing), provide concise, factual notes (max 160 words) analyzing: $subQuery")
        }
        val payload = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("stream", false)
            put("temperature", 0.25)
            put("max_completion_tokens", 500)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", stepPrompt)
                )
            )
        }
        val json = withTimeout(14_000L) {
            executeUnaryJsonRequest(GROQ_API_URL, payload, mapOf("Authorization" to "Bearer $groqKey"))
        }
        return extractTextFromGroqResponse(json).trim()
    }

    private suspend fun streamAcrossProvidersChain(
        requestedTier: AiModelTier,
        effectiveRequest: AiRequest,
        geminiKeys: List<String>,
        openRouterKeys: List<String>,
        deepSeekKeys: List<String>,
        groqKeys: List<String>,
        preferFastGroq: Boolean,
        requiresGeminiTools: Boolean,
        systemText: String,
        promptText: String,
        nonGeminiFallbackPromptNotice: String,
        attachedBitmaps: List<Bitmap>,
        startMs: Long,
        initialSources: Map<String, SourceCitation> = emptyMap(),
        initialThinkingPrefix: String = "",
        emitEvent: suspend (AiStreamEvent) -> Unit
    ): Throwable? {
        val candidates = buildCrossProviderFallbackChain(
            requestedTier = requestedTier,
            effectiveTier = effectiveRequest.modelTier,
            capabilityMode = effectiveRequest.capabilityMode,
            hasImageAttachment = attachedBitmaps.isNotEmpty(),
            requiresGeminiTools = requiresGeminiTools,
            preferFastGroq = preferFastGroq,
            hasGeminiKey = geminiKeys.isNotEmpty(),
            hasOpenRouterKey = openRouterKeys.isNotEmpty(),
            hasDeepSeekKey = deepSeekKeys.isNotEmpty(),
            hasGroqKey = groqKeys.isNotEmpty()
        )
        if (candidates.isEmpty()) {
            return IllegalStateException("MISSING_API_KEY")
        }

        val missingRequestedProviderReason = when {
            requestedTier.isDeepSeekTier && openRouterKeys.isEmpty() && deepSeekKeys.isEmpty() ->
                "DeepSeek route not configured"
            requestedTier.isGeminiTier && geminiKeys.isEmpty() ->
                "Primary route not configured"
            else -> null
        }

        val failedKeyByProvider = mutableMapOf<ModelProvider, MutableSet<String>>()
        val failedProviderAuth = mutableSetOf<ModelProvider>()
        var lastError: Throwable? = null
        var previousFailedDisplay: String? = if (missingRequestedProviderReason != null) requestedTier.displayName else null
        var previousFailReason: String? = missingRequestedProviderReason

        val nonGeminiPromptText = if (nonGeminiFallbackPromptNotice.isNotBlank() &&
            !promptText.contains("### LIVE WEB SEARCH RESULTS")
        ) {
            buildUserPromptText(effectiveRequest, liveWebContextBlock = nonGeminiFallbackPromptNotice)
        } else {
            promptText
        }

        for ((index, candidate) in candidates.withIndex()) {
            if (candidate.provider in failedProviderAuth) continue

            val providerPool = when (candidate.provider) {
                ModelProvider.GEMINI -> geminiKeys
                ModelProvider.OPENROUTER -> openRouterKeys
                ModelProvider.DEEPSEEK -> deepSeekKeys
                ModelProvider.GROQ -> groqKeys
            }.filter { it !in failedKeyByProvider[candidate.provider].orEmpty() }

            if (providerPool.isEmpty()) {
                failedProviderAuth += candidate.provider
                continue
            }

            val effectiveThinkingPrefix = initialThinkingPrefix.trim()
            var candidateSucceeded = false

            for ((keyIdx, activeKey) in providerPool.withIndex()) {
                emitEvent(
                    AiStreamEvent.ModelActivated(
                        modelId = candidate.modelId,
                        modelDisplayName = candidate.displayName,
                        switchedFromModel = previousFailedDisplay,
                        switchReason = previousFailReason
                    )
                )

                val err = when (candidate.provider) {
                    ModelProvider.GEMINI -> streamSingleGeminiCandidate(
                        apiKey = activeKey,
                        candidate = candidate,
                        request = effectiveRequest,
                        systemText = systemText,
                        promptText = promptText,
                        attachedBitmaps = attachedBitmaps,
                        startMs = startMs,
                        initialSources = initialSources,
                        initialThinkingPrefix = effectiveThinkingPrefix,
                        emitEvent = emitEvent
                    )
                    ModelProvider.OPENROUTER -> streamSingleOpenAiCompatCandidate(
                        url = OPENROUTER_API_URL,
                        apiKey = activeKey,
                        extraHeaders = mapOf(
                            "HTTP-Referer" to "https://abhynex.aura.ai",
                            "X-Title" to "Aura AI"
                        ),
                        useMaxCompletionTokens = false,
                        candidate = candidate,
                        request = effectiveRequest,
                        systemText = systemText,
                        promptText = nonGeminiPromptText,
                        attachedBitmaps = attachedBitmaps,
                        startMs = startMs,
                        initialSources = initialSources,
                        initialThinkingPrefix = effectiveThinkingPrefix,
                        emitEvent = emitEvent
                    )
                    ModelProvider.DEEPSEEK -> streamSingleOpenAiCompatCandidate(
                        url = DEEPSEEK_API_URL,
                        apiKey = activeKey,
                        extraHeaders = emptyMap(),
                        useMaxCompletionTokens = false,
                        candidate = candidate,
                        request = effectiveRequest,
                        systemText = systemText,
                        promptText = nonGeminiPromptText,
                        attachedBitmaps = attachedBitmaps,
                        startMs = startMs,
                        initialSources = initialSources,
                        initialThinkingPrefix = effectiveThinkingPrefix,
                        emitEvent = emitEvent
                    )
                    ModelProvider.GROQ -> streamSingleOpenAiCompatCandidate(
                        url = GROQ_API_URL,
                        apiKey = activeKey,
                        extraHeaders = emptyMap(),
                        useMaxCompletionTokens = true,
                        candidate = candidate,
                        request = effectiveRequest,
                        systemText = systemText,
                        promptText = nonGeminiPromptText,
                        attachedBitmaps = attachedBitmaps,
                        startMs = startMs,
                        initialSources = initialSources,
                        initialThinkingPrefix = effectiveThinkingPrefix,
                        emitEvent = emitEvent
                    )
                }

                if (err == null) {
                    candidateSucceeded = true
                    break
                }

                lastError = err
                val isKeySpecificFailure = isProviderAuthOrBillingError(err) || isQuotaOrRateLimitError(err)
                if (isKeySpecificFailure) {
                    failedKeyByProvider.getOrPut(candidate.provider) { mutableSetOf() }.add(activeKey)
                    val hasAnotherKeyInPool = keyIdx < providerPool.lastIndex
                    if (hasAnotherKeyInPool) {
                        Log.w(TAG, "Rotating ${candidate.provider} API key after ${summarizeSwitchReason(err)}")
                        continue
                    }
                    if (isProviderAuthOrBillingError(err)) {
                        failedProviderAuth += candidate.provider
                    }
                }
                break
            }

            if (candidateSucceeded) {
                return null
            }

            val err = lastError ?: IOException("Candidate failed")
            val hasMoreCandidates = candidates.drop(index + 1).any { nextCandidate ->
                val remainingKeys = when (nextCandidate.provider) {
                    ModelProvider.GEMINI -> geminiKeys
                    ModelProvider.OPENROUTER -> openRouterKeys
                    ModelProvider.DEEPSEEK -> deepSeekKeys
                    ModelProvider.GROQ -> groqKeys
                }.count { it !in failedKeyByProvider[nextCandidate.provider].orEmpty() }
                nextCandidate.provider !in failedProviderAuth && remainingKeys > 0
            }
            if (hasMoreCandidates && isRecoverableModelError(err)) {
                previousFailedDisplay = candidate.displayName
                previousFailReason = summarizeSwitchReason(err)
                Log.w(TAG, "Auto-switching model after failure on ${candidate.modelId} (${candidate.provider}): ${err.message}")
                continue
            } else {
                return err
            }
        }

        return lastError ?: IOException("All configured AI models failed to respond")
    }

    private fun isQuotaOrRateLimitError(t: Throwable): Boolean {
        val msg = (t.message ?: t.localizedMessage ?: "").lowercase()
        return msg.contains("429") ||
            msg.contains("quota") ||
            msg.contains("rate") ||
            msg.contains("resource_exhausted") ||
            msg.contains("exceeded")
    }

    private suspend fun streamSingleGeminiCandidate(
        apiKey: String,
        candidate: ModelCandidate,
        request: AiRequest,
        systemText: String,
        promptText: String,
        attachedBitmaps: List<Bitmap>,
        startMs: Long,
        initialSources: Map<String, SourceCitation>,
        initialThinkingPrefix: String,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ): Throwable? {
        val modelId = candidate.modelId
        val effectivePrompt = if (candidate.supportsSystemInstruction) {
            promptText
        } else {
            "[System Instructions]\n$systemText\n\n[User Request]\n$promptText"
        }

        val chatHistory = buildHistoryJsonArray(request.history)
        val hasUrlInPrompt = Regex("https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(promptText)
        val userTurnParts = JSONArray().apply {
            attachedBitmaps.forEach { bmp ->
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", encodeBitmapJpegBase64(bmp))
                        }
                    )
                )
            }
            put(JSONObject().put("text", effectivePrompt))
        }
        chatHistory.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", userTurnParts)
            }
        )

        val toolsJson = if (candidate.supportsTools) {
            buildToolsJsonArray(
                useCodeExecution = request.useCodeExecution,
                useWebSearch = request.useWebSearch,
                hasUrlInPrompt = hasUrlInPrompt
            )
        } else null

        val attemptPlan = buildList {
            if (candidate.supportsThinkingConfig) {
                add(Triple(true, toolsJson != null, 0L))
                add(Triple(true, toolsJson != null, 450L))
                add(Triple(false, toolsJson != null, 0L))
                if (toolsJson != null) add(Triple(false, false, 0L))
            } else {
                add(Triple(false, toolsJson != null, 0L))
                if (toolsJson != null) add(Triple(false, false, 0L))
            }
        }

        var lastError: Throwable? = null
        var skipDuplicateThinkingRetry = false

        for ((attemptIdx, plan) in attemptPlan.withIndex()) {
            val (includeThinkingConfig, includeTools, preDelayMs) = plan
            if (skipDuplicateThinkingRetry && includeThinkingConfig) {
                skipDuplicateThinkingRetry = false
                continue
            }
            if (preDelayMs > 0L) delay(preDelayMs)

            val rawAccumulated = StringBuilder()
            val nativeThinkingAccumulated = StringBuilder(
                if (initialThinkingPrefix.isNotBlank()) "$initialThinkingPrefix\n\n" else ""
            )
            val collectedSources = linkedMapOf<String, SourceCitation>().apply { putAll(initialSources) }
            var lastRenderedContent = ""
            var lastThinkingText = ""
            var lastContentEmitMs = 0L
            var lastThinkingEmitMs = 0L

            try {
                val requestPayload = buildChatRequestJson(
                    systemText = systemText,
                    contentsArray = chatHistory,
                    capabilityMode = request.capabilityMode,
                    modelTier = request.modelTier,
                    modelId = modelId,
                    toolsArray = if (includeTools) toolsJson else null,
                    includeThinking = includeThinkingConfig,
                    includeSystemInstruction = candidate.supportsSystemInstruction
                )

                withTimeout(INFERENCE_TIMEOUT_MS) {
                    executeSseStreamRequest(
                        url = "$GEMINI_API_BASE/$modelId:streamGenerateContent?alt=sse&key=$apiKey",
                        bodyJson = requestPayload
                    ) { chunkJson ->
                        chunkJson.optJSONObject("error")?.let { errObj ->
                            val msg = optNonNullString(errObj, "message").ifBlank { errObj.toString() }
                            val code = errObj.optInt("code", 500)
                            throw IOException("HTTP $code: $msg")
                        }

                        val candidates = chunkJson.optJSONArray("candidates")
                        val chunkCandidate = candidates?.optJSONObject(0) ?: return@executeSseStreamRequest

                        chunkCandidate.optJSONObject("groundingMetadata")
                            ?.optJSONArray("groundingChunks")
                            ?.let { chunks ->
                                for (i in 0 until chunks.length()) {
                                    val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                                    val uri = optNonNullString(web, "uri").trim()
                                    if (uri.isNotBlank()) {
                                        val title = optNonNullString(web, "title").trim().ifBlank { uri }
                                        collectedSources[uri] = SourceCitation(title = title, uri = uri)
                                    }
                                }
                            }

                        val parts = chunkCandidate.optJSONObject("content")?.optJSONArray("parts")
                            ?: return@executeSseStreamRequest

                        var hasNewThinking = false
                        var hasNewContent = false

                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            when {
                                part.optBoolean("thought", false) -> {
                                    val t = optNonNullString(part, "text")
                                    if (t.isNotEmpty()) {
                                        nativeThinkingAccumulated.append(t)
                                        hasNewThinking = true
                                    }
                                }
                                part.has("executableCode") -> {
                                    val ec = part.optJSONObject("executableCode")
                                    if (ec != null) {
                                        val lang = optNonNullString(ec, "language")
                                            .lowercase()
                                            .ifBlank { "python" }
                                        val code = optNonNullString(ec, "code")
                                        if (code.isNotBlank()) {
                                            rawAccumulated.append("\n\n```$lang\n$code\n```\n")
                                            hasNewContent = true
                                        }
                                    }
                                }
                                part.has("codeExecutionResult") -> {
                                    val res = part.optJSONObject("codeExecutionResult")
                                    if (res != null) {
                                        val outcome = optNonNullString(res, "outcome").ifBlank { "OUTCOME_OK" }
                                        val output = optNonNullString(res, "output")
                                        val statusPrefix = if (!outcome.contains("OK", ignoreCase = true)) {
                                            "[Execution Status: $outcome]\n"
                                        } else ""
                                        val combinedOut = "$statusPrefix$output".trim()
                                        if (combinedOut.isNotBlank()) {
                                            rawAccumulated.append("\n```sandbox-output\n$combinedOut\n```\n\n")
                                            hasNewContent = true
                                        }
                                    }
                                }
                                part.has("text") -> {
                                    val textPart = optNonNullString(part, "text")
                                    if (textPart.isNotEmpty()) {
                                        rawAccumulated.append(textPart)
                                        hasNewContent = true
                                    }
                                }
                            }
                        }

                        val nowMs = System.currentTimeMillis()
                        val elapsed = nowMs - startMs
                        val nativeThinkingNow = nativeThinkingAccumulated.toString().trim()

                        if (nativeThinkingNow.isNotEmpty()) {
                            if (hasNewThinking && nativeThinkingNow != lastThinkingText &&
                                (nowMs - lastThinkingEmitMs >= STREAM_EMIT_THROTTLE_MS || lastThinkingEmitMs == 0L)
                            ) {
                                lastThinkingText = nativeThinkingNow
                                lastThinkingEmitMs = nowMs
                                emitEvent(
                                    AiStreamEvent.ThinkingChunk(
                                        thinkingText = nativeThinkingNow,
                                        elapsedMs = elapsed
                                    )
                                )
                            }
                            if (hasNewContent) {
                                val cleanContent = rawAccumulated.toString()
                                if (cleanContent != lastRenderedContent &&
                                    (nowMs - lastContentEmitMs >= STREAM_EMIT_THROTTLE_MS || lastContentEmitMs == 0L)
                                ) {
                                    lastRenderedContent = cleanContent
                                    lastContentEmitMs = nowMs
                                    emitEvent(AiStreamEvent.ContentChunk(cleanContent))
                                }
                            }
                        } else if (hasNewContent || hasNewThinking) {
                            val parsed = splitThinkingAndContent(rawAccumulated.toString())
                            if (!parsed.thinking.isNullOrBlank() && parsed.thinking != lastThinkingText &&
                                (nowMs - lastThinkingEmitMs >= STREAM_EMIT_THROTTLE_MS || lastThinkingEmitMs == 0L)
                            ) {
                                lastThinkingText = parsed.thinking
                                lastThinkingEmitMs = nowMs
                                emitEvent(
                                    AiStreamEvent.ThinkingChunk(
                                        thinkingText = parsed.thinking,
                                        elapsedMs = elapsed
                                    )
                                )
                            }
                            if (parsed.answer.isNotEmpty() && parsed.answer != lastRenderedContent &&
                                (nowMs - lastContentEmitMs >= STREAM_EMIT_THROTTLE_MS || lastContentEmitMs == 0L)
                            ) {
                                lastRenderedContent = parsed.answer
                                lastContentEmitMs = nowMs
                                emitEvent(AiStreamEvent.ContentChunk(parsed.answer))
                            }
                        }
                    }
                }

                val nativeFinalThoughts = nativeThinkingAccumulated.toString().trim().ifBlank { null }
                val parsed = splitThinkingAndContent(rawAccumulated.toString())
                val finalThinking = listOfNotNull(
                    nativeFinalThoughts,
                    parsed.thinking?.ifBlank { null }
                ).distinct().joinToString("\n\n").trim().ifBlank { null }
                val finalAnswer = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                val totalDuration = System.currentTimeMillis() - startMs

                if (finalAnswer.isNotBlank()) {
                    emitEvent(
                        AiStreamEvent.Completed(
                            fullText = finalAnswer,
                            thinkingText = finalThinking,
                            thinkingDurationMs = if (finalThinking != null) totalDuration else 0L,
                            sources = collectedSources.values.toList(),
                            modelDisplayName = candidate.displayName
                        )
                    )
                    return null
                } else {
                    lastError = IOException("Empty response from $modelId")
                    break
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Aura AI stream timed out on $modelId", e)
                if (rawAccumulated.isNotBlank()) {
                    val parsed = splitThinkingAndContent(rawAccumulated.toString())
                    val partialAnswer = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                    if (partialAnswer.isNotBlank()) {
                        emitEvent(
                            AiStreamEvent.Completed(
                                fullText = partialAnswer,
                                thinkingText = parsed.thinking,
                                thinkingDurationMs = System.currentTimeMillis() - startMs,
                                sources = collectedSources.values.toList(),
                                modelDisplayName = candidate.displayName
                            )
                        )
                        return null
                    }
                }
                lastError = e
                break
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Aura AI streaming failed on $modelId (attempt=$attemptIdx): ${t.message}", t)
                if (rawAccumulated.isNotEmpty()) {
                    val parsed = splitThinkingAndContent(rawAccumulated.toString())
                    val partial = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                    if (partial.isNotBlank()) {
                        emitEvent(
                            AiStreamEvent.Completed(
                                fullText = partial,
                                thinkingText = parsed.thinking,
                                thinkingDurationMs = System.currentTimeMillis() - startMs,
                                sources = collectedSources.values.toList(),
                                modelDisplayName = candidate.displayName
                            )
                        )
                        return null
                    }
                }
                val msgLower = (t.message ?: "").lowercase()
                val isConfigIssue = msgLower.contains("thinking") ||
                    msgLower.contains("400") ||
                    msgLower.contains("invalid_argument") ||
                    msgLower.contains("tool")
                val isTransientBusy = attemptIdx == 0 && (
                    msgLower.contains("503") || msgLower.contains("unavailable") ||
                        msgLower.contains("overloaded") || msgLower.contains("429") ||
                        msgLower.contains("500")
                    )
                if (isTransientBusy) {
                    continue
                } else if (isConfigIssue && attemptIdx < attemptPlan.lastIndex) {
                    if (attemptIdx == 0 && includeThinkingConfig) {
                        skipDuplicateThinkingRetry = true
                    }
                    continue
                } else {
                    break
                }
            }
        }

        return lastError ?: IOException("Gemini stream failed on $modelId")
    }

    private suspend fun streamSingleOpenAiCompatCandidate(
        url: String,
        apiKey: String,
        extraHeaders: Map<String, String>,
        useMaxCompletionTokens: Boolean,
        candidate: ModelCandidate,
        request: AiRequest,
        systemText: String,
        promptText: String,
        attachedBitmaps: List<Bitmap>,
        startMs: Long,
        initialSources: Map<String, SourceCitation>,
        initialThinkingPrefix: String,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ): Throwable? {
        val modelId = candidate.modelId
        val visionBitmaps = if (candidate.supportsVision) attachedBitmaps else emptyList()
        val messagesArray = buildGroqMessagesArray(
            systemText = systemText,
            history = request.history,
            promptText = promptText,
            attachedBitmaps = visionBitmaps
        )
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            putAll(extraHeaders)
        }
        val resolvedSources = initialSources.values.toList()
        val rawAccumulated = StringBuilder()
        val nativeThinkingAccumulated = StringBuilder(
            if (initialThinkingPrefix.isNotBlank()) "$initialThinkingPrefix\n\n" else ""
        )
        var lastRenderedContent = ""
        var lastThinkingText = ""
        var lastContentEmitMs = 0L
        var lastThinkingEmitMs = 0L

        return try {
            val maxTokens = when {
                modelId.contains("8b-instant") || modelId.contains("llama-4-scout") -> 8192
                else -> 16384
            }
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messagesArray)
                put("stream", true)
                if (!modelId.contains("deepseek-reasoner")) {
                    put(
                        "temperature",
                        when {
                            request.capabilityMode == CapabilityMode.CODE_ARCHITECT -> 0.2
                            request.useWebSearch -> 0.25
                            request.capabilityMode == CapabilityMode.CONCISE -> 0.35
                            request.capabilityMode == CapabilityMode.DEEP_THINK -> 0.45
                            else -> 0.6
                        }
                    )
                }
                if (useMaxCompletionTokens) {
                    put("max_completion_tokens", maxTokens)
                } else {
                    put("max_tokens", maxTokens)
                }
            }

            withTimeout(INFERENCE_TIMEOUT_MS) {
                executeSseStreamRequest(
                    url = url,
                    bodyJson = payload,
                    headers = headers
                ) { chunkJson ->
                    parseOpenAiCompatSseChunk(chunkJson)?.let { deltaChunk ->
                        if (deltaChunk.errorMessage != null) {
                            throw IOException(deltaChunk.errorMessage)
                        }
                        var hasNewThinking = false
                        var hasNewContent = false

                        if (deltaChunk.reasoningDelta.isNotEmpty()) {
                            nativeThinkingAccumulated.append(deltaChunk.reasoningDelta)
                            hasNewThinking = true
                        }
                        if (deltaChunk.contentDelta.isNotEmpty()) {
                            rawAccumulated.append(deltaChunk.contentDelta)
                            hasNewContent = true
                        }

                        val nowMs = System.currentTimeMillis()
                        val elapsed = nowMs - startMs
                        val nativeThinkingNow = nativeThinkingAccumulated.toString().trim()
                        val parsed = splitThinkingAndContent(rawAccumulated.toString())
                        val combinedThinking = listOfNotNull(
                            nativeThinkingNow.ifBlank { null },
                            parsed.thinking?.ifBlank { null }
                        ).joinToString("\n\n").trim()

                        if ((hasNewThinking || hasNewContent) && combinedThinking.isNotEmpty() &&
                            combinedThinking != lastThinkingText &&
                            (nowMs - lastThinkingEmitMs >= STREAM_EMIT_THROTTLE_MS || lastThinkingEmitMs == 0L)
                        ) {
                            lastThinkingText = combinedThinking
                            lastThinkingEmitMs = nowMs
                            emitEvent(
                                AiStreamEvent.ThinkingChunk(
                                    thinkingText = combinedThinking,
                                    elapsedMs = elapsed
                                )
                            )
                        }
                        if (hasNewContent && parsed.answer.isNotEmpty() &&
                            parsed.answer != lastRenderedContent &&
                            (nowMs - lastContentEmitMs >= STREAM_EMIT_THROTTLE_MS || lastContentEmitMs == 0L)
                        ) {
                            lastRenderedContent = parsed.answer
                            lastContentEmitMs = nowMs
                            emitEvent(AiStreamEvent.ContentChunk(parsed.answer))
                        }
                    }
                }
            }

            val nativeFinalThoughts = nativeThinkingAccumulated.toString().trim().ifBlank { null }
            val parsed = splitThinkingAndContent(rawAccumulated.toString())
            val finalThinking = listOfNotNull(
                nativeFinalThoughts,
                parsed.thinking?.ifBlank { null }
            ).joinToString("\n\n").trim().ifBlank { null }
            val finalAnswer = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
            val totalDuration = System.currentTimeMillis() - startMs

            if (finalAnswer.isNotBlank()) {
                emitEvent(
                    AiStreamEvent.Completed(
                        fullText = finalAnswer,
                        thinkingText = finalThinking,
                        thinkingDurationMs = if (finalThinking != null) totalDuration else 0L,
                        sources = resolvedSources,
                        modelDisplayName = candidate.displayName
                    )
                )
                null
            } else {
                IOException("Empty response from ${candidate.displayName} ($modelId)")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${candidate.provider} stream timed out on $modelId", e)
            val parsed = splitThinkingAndContent(rawAccumulated.toString())
            val partialAnswer = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
            if (partialAnswer.isNotBlank()) {
                emitEvent(
                    AiStreamEvent.Completed(
                        fullText = partialAnswer,
                        thinkingText = parsed.thinking,
                        thinkingDurationMs = System.currentTimeMillis() - startMs,
                        sources = resolvedSources,
                        modelDisplayName = candidate.displayName
                    )
                )
                null
            } else {
                e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "${candidate.provider} streaming failed on $modelId: ${t.message}", t)
            if (rawAccumulated.isNotEmpty()) {
                val parsed = splitThinkingAndContent(rawAccumulated.toString())
                val partial = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                if (partial.isNotBlank()) {
                    emitEvent(
                        AiStreamEvent.Completed(
                            fullText = partial,
                            thinkingText = parsed.thinking,
                            thinkingDurationMs = System.currentTimeMillis() - startMs,
                            sources = resolvedSources,
                            modelDisplayName = candidate.displayName
                        )
                    )
                    return null
                }
            }
            t
        }
    }

    private fun isProviderAuthOrBillingError(t: Throwable): Boolean {
        val msg = (t.message ?: t.localizedMessage ?: "").lowercase()
        return msg.contains("401") ||
            msg.contains("402") ||
            msg.contains("api_key_invalid") ||
            msg.contains("invalid_api_key") ||
            msg.contains("api key not valid") ||
            msg.contains("unauthorized") ||
            msg.contains("insufficient balance") ||
            msg.contains("unauthenticated")
    }

    private fun summarizeSwitchReason(t: Throwable): String {
        val msg = (t.message ?: t.localizedMessage ?: "").lowercase()
        return when {
            msg.contains("429") || msg.contains("rate") || msg.contains("quota") || msg.contains("resource_exhausted") ->
                "Rate limit / quota reached"
            msg.contains("503") || msg.contains("502") || msg.contains("504") || msg.contains("overloaded") || msg.contains("unavailable") ->
                "Server busy"
            msg.contains("404") || msg.contains("not found") || msg.contains("decommissioned") ->
                "Endpoint unavailable"
            msg.contains("401") || msg.contains("403") || msg.contains("api_key") || msg.contains("unauthorized") ->
                "Provider auth error"
            msg.contains("402") || msg.contains("balance") || msg.contains("billing") ->
                "Paid quota required"
            msg.contains("timeout") ->
                "Request timed out"
            else -> "Temporary error"
        }
    }

    private suspend fun generateOrEditImage(request: AiRequest): AiStreamEvent {
        val apiKey = try {
            requireGeminiApiKey()
        } catch (t: Throwable) {
            return AiStreamEvent.Failed(sanitizeAuraError(t.message, isImage = true))
        }

        val attachedBitmap = if (request.attachmentIsImage && request.attachmentUri != null) {
            loadScaledBitmap(Uri.parse(request.attachmentUri))
        } else null

        val candidateImageModels = buildList {
            add(request.modelTier.id)
            add("gemini-2.5-flash-image")
            add("gemini-2.0-flash-exp-image-generation")
            add("gemini-3.1-flash-lite-image")
            add("gemini-nano-banana-2.1")
        }.distinct()

        var lastError: Throwable? = null

        for ((index, modelId) in candidateImageModels.withIndex()) {
            try {
                val parts = JSONArray().apply {
                    if (attachedBitmap != null) {
                        put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", encodeBitmapJpegBase64(attachedBitmap))
                                }
                            )
                        )
                    }
                    put(JSONObject().put("text", request.prompt))
                }
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", parts)
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().put(
                            "responseModalities",
                            JSONArray().put("IMAGE").put("TEXT")
                        )
                    )
                }

                val responseJson = withTimeout(INFERENCE_TIMEOUT_MS) {
                    executeUnaryJsonRequest(
                        url = "$GEMINI_API_BASE/$modelId:generateContent?key=$apiKey",
                        bodyJson = payload
                    )
                }

                val bitmap = extractBitmapFromResponse(responseJson)
                val caption = extractTextFromResponse(responseJson).trim()

                if (bitmap == null) {
                    val hasNext = index < candidateImageModels.lastIndex
                    if (hasNext) continue
                    return AiStreamEvent.Failed(
                        caption.ifBlank { "Aura AI could not generate an image for that prompt." }
                    )
                } else {
                    val savedPath = saveBitmapToInternalStorage(bitmap)
                    return AiStreamEvent.Completed(
                        fullText = caption.ifBlank { "Generated with Aura AI · Abhynex" },
                        thinkingText = null,
                        thinkingDurationMs = 0L,
                        generatedImagePath = savedPath
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Image generation timed out on $modelId", e)
                return AiStreamEvent.Failed("Aura AI image creation timed out after 60 seconds. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Image generation failed on $modelId: ${t.message}", t)
                val hasNext = index < candidateImageModels.lastIndex
                if (hasNext && isRecoverableModelError(t)) {
                    continue
                }
                return AiStreamEvent.Failed(
                    sanitizeAuraError(t.localizedMessage ?: t.message, isImage = true)
                )
            }
        }

        return AiStreamEvent.Failed(
            sanitizeAuraError(lastError?.localizedMessage ?: lastError?.message, isImage = true)
        )
    }

    private suspend fun executeUnaryJsonRequest(
        url: String,
        bodyJson: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject = suspendCancellableCoroutine { cont ->
        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.build()

        val call = httpClient.newCall(req)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { res ->
                    val rawBody = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        val parsedErr = parseApiErrorMessage(res.code, rawBody)
                        if (cont.isActive) cont.resumeWithException(IOException(parsedErr))
                        return
                    }
                    runCatching { JSONObject(rawBody) }
                        .onSuccess { json -> if (cont.isActive) cont.resume(json) }
                        .onFailure { e -> if (cont.isActive) cont.resumeWithException(e) }
                }
            }
        })
    }

    private suspend fun executeSseStreamRequest(
        url: String,
        bodyJson: JSONObject,
        headers: Map<String, String> = emptyMap(),
        onChunk: suspend (JSONObject) -> Unit
    ) {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.build()

        val call = httpClient.newCall(req)
        val response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

        response.use { res ->
            if (!res.isSuccessful) {
                val errBody = res.body?.string().orEmpty()
                throw IOException(parseApiErrorMessage(res.code, errBody))
            }
            val reader = res.body?.charStream()?.buffered() ?: return
            val dataBuffer = StringBuilder()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) {
                    if (dataBuffer.isNotEmpty()) {
                        val payload = dataBuffer.toString().trim()
                        dataBuffer.clear()
                        if (payload != "[DONE]") {
                            runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                                onChunk(json)
                            }
                        }
                    }
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.removePrefix("data:").trimStart())
                }
            }

            if (dataBuffer.isNotEmpty()) {
                val payload = dataBuffer.toString().trim()
                if (payload != "[DONE]") {
                    runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                        onChunk(json)
                    }
                }
            }
        }
    }

    private fun parseApiErrorMessage(code: Int, rawBody: String): String {
        val extracted = runCatching {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            val status = errObj?.optString("status").orEmpty()
            val msg = errObj?.optString("message").orEmpty()
            "$code $status: $msg".trim()
        }.getOrNull()
        return extracted?.ifBlank { "HTTP $code: $rawBody" } ?: "HTTP $code: $rawBody"
    }

    private fun buildChatRequestJson(
        systemText: String,
        contentsArray: JSONArray,
        capabilityMode: CapabilityMode,
        modelTier: AiModelTier,
        modelId: String,
        toolsArray: JSONArray?,
        includeThinking: Boolean,
        includeSystemInstruction: Boolean = true
    ): JSONObject {
        val isProModel = modelId.contains("2.5-pro", ignoreCase = true)
        val maxOut = when {
            modelId.startsWith("gemma-", ignoreCase = true) -> 8192
            modelId.contains("2.0-flash", ignoreCase = true) -> 8192
            else -> 65536
        }
        return JSONObject().apply {
            if (includeSystemInstruction) {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemText))
                    )
                )
            }
            put("contents", contentsArray)
            val generationConfig = JSONObject().apply {
                put("maxOutputTokens", maxOut)
                put(
                    "temperature",
                    when {
                        capabilityMode == CapabilityMode.CODE_ARCHITECT -> 0.2
                        capabilityMode == CapabilityMode.DEEP_RESEARCH -> 0.25
                        capabilityMode == CapabilityMode.CONCISE -> 0.35
                        capabilityMode == CapabilityMode.DEEP_THINK -> 0.45
                        else -> 0.6
                    }
                )
                if (includeThinking) {
                    val thinkingObj = JSONObject().apply {
                        when {
                            capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                                capabilityMode == CapabilityMode.DEEP_THINK ||
                                capabilityMode == CapabilityMode.DEEP_RESEARCH ||
                                modelTier == AiModelTier.GEMINI_4_ARGON -> {
                                put("includeThoughts", true)
                                put("thinkingBudget", 12288)
                            }
                            capabilityMode == CapabilityMode.CONCISE -> {
                                put("includeThoughts", false)
                                if (!isProModel) {
                                    put("thinkingBudget", 0)
                                }
                            }
                            else -> {
                                put("includeThoughts", true)
                                put("thinkingBudget", 2048)
                            }
                        }
                    }
                    put("thinkingConfig", thinkingObj)
                }
            }
            put("generationConfig", generationConfig)
            if (toolsArray != null && toolsArray.length() > 0) {
                put("tools", toolsArray)
            }
        }
    }

    private fun buildToolsJsonArray(
        useCodeExecution: Boolean,
        useWebSearch: Boolean,
        hasUrlInPrompt: Boolean
    ): JSONArray? {
        val tools = JSONArray()
        when {
            useCodeExecution -> tools.put(JSONObject().put("code_execution", JSONObject()))
            useWebSearch && hasUrlInPrompt -> {
                tools.put(JSONObject().put("google_search", JSONObject()))
                tools.put(JSONObject().put("url_context", JSONObject()))
            }
            useWebSearch -> tools.put(JSONObject().put("google_search", JSONObject()))
            hasUrlInPrompt -> tools.put(JSONObject().put("url_context", JSONObject()))
        }
        return if (tools.length() > 0) tools else null
    }

    private fun extractTextFromResponse(json: JSONObject): String {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought", false) && part.has("text")) {
                sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    private fun extractTextFromGroqResponse(json: JSONObject): String {
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
    }

    private fun extractBitmapFromResponse(json: JSONObject): Bitmap? {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            val b64 = inline?.optString("data").orEmpty()
            if (b64.isNotBlank()) {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return bmp
            }
        }
        return null
    }

    private fun isRecoverableModelError(t: Throwable): Boolean {
        val msg = (t.message ?: t.localizedMessage ?: "").lowercase()
        return isTransientOrRecoverableErrorMessage(msg)
    }

    private fun sanitizeAuraError(raw: String?, isImage: Boolean): String {
        val msg = (raw ?: "").lowercase()
        return when {
            msg.contains("missing_api_key") || msg.contains("missing_gemini_api_key") -> {
                if (isImage) {
                    "Image synthesis requires a primary API Key. Open Settings → API Keys to paste and save your key."
                } else {
                    "No API Key is set yet. Tap Settings → API Keys to paste and save your key."
                }
            }
            msg.contains("api_key_invalid") || msg.contains("api key not valid") ||
                msg.contains("invalid_api_key") || msg.contains("401") || msg.contains("unauthenticated") -> {
                "Invalid API Key. Open Settings → API Keys to update and save a valid key."
            }
            msg.contains("503") || msg.contains("unavailable") || msg.contains("overloaded") ||
                msg.contains("500") || msg.contains("502") || msg.contains("504") -> {
                "Aura AI servers are temporarily busy right now. Please tap Retry in a moment."
            }
            msg.contains("quota") || msg.contains("429") || msg.contains("rate") ||
                msg.contains("exceeded") || msg.contains("resource_exhausted") -> {
                if (isImage) {
                    "Aura Imagine capacity is currently limited or requires an upgraded API plan. Please retry shortly."
                } else {
                    "Aura AI request rate limit reached. Please wait a moment and tap Retry."
                }
            }
            msg.contains("billing") || msg.contains("blaze") -> {
                "This Aura AI mode requires billing enabled on your API project."
            }
            msg.contains("403") || msg.contains("permission") -> {
                "Aura AI access denied (403). Verify your API key permissions in Settings and try again."
            }
            msg.contains("unable to resolve host") || msg.contains("network") ||
                msg.contains("connect") || msg.contains("timeout") -> {
                "Unable to reach Aura AI servers. Check your internet connection and tap Retry."
            }
            raw.isNullOrBlank() -> "Aura AI could not complete the request. Please tap Retry."
            else -> {
                raw.replace(Regex("https?://\\S+"), "")
                    .replace(Regex("gemini[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("gemma[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("deepseek[-a-z0-9./:]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("openrouter[-a-z0-9./:]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("groq[-a-z0-9./]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("exa\\s*api|exa\\.ai|\\bexa\\b", RegexOption.IGNORE_CASE), "Aura Search")
                    .replace(Regex("(meta-)?llama[-a-z0-9./]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("(openai/)?gpt-oss[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("(qwen|mistral|tngtech|anthropic|claude)[-a-z0-9./:]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("openai", RegexOption.IGNORE_CASE), "Abhynex")
                    .replace(Regex("generativelanguage\\.googleapis\\.com\\S*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("api\\.(groq|deepseek|exa)\\.\\S*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("openrouter\\.ai\\S*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("google", RegexOption.IGNORE_CASE), "Abhynex")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "Aura AI could not complete the request. Please tap Retry." }
            }
        }
    }

    internal fun buildSystemInstruction(
        persona: PersonaEntity?,
        capabilityMode: CapabilityMode,
        language: AppLanguage,
        useWebSearch: Boolean,
        useCodeExecution: Boolean,
        activeMemories: List<String> = emptyList(),
        hasVaultContext: Boolean = false
    ): String {
        val currentDateTime = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm z", Locale.US).format(Date())
        val brandRule = """
            Current Date & Time: $currentDateTime
            Your name is **Aura AI**, created and engineered by **Abhi** (founder of **Abhynex**).
            If anyone asks who created, built, owns, or made you, clearly and proudly state that **Abhi** built you at **Abhynex**.
            STRICT CONFIDENTIALITY RULE: NEVER mention, expose, or reference Gemini, DeepSeek, Groq, OpenRouter, Exa, Llama, Gemma, OpenAI, GPT, Google, Claude, Anthropic, or ANY third-party API/provider/model ID anywhere in your thinking or chat response. Always present yourself exclusively as **Aura AI** by **Abhynex**.

            Aura Apex Executive & Principal Engineering Protocol:
            1. **Executive Publication Formatting**: Open directly with the core solution — zero filler, zero robotic preamble ("Sure!", "Great question!"). Structure every non-trivial answer cleanly with `##` / `###` section headings, **bold lead-ins** on crisp bullets, Markdown comparison tables (`| Option | ... |`) for trade-offs, and inline `code` formatting for symbols, commands, and file paths.
            2. **Language Mastery**: ${language.promptInstruction} Always support English, Devanagari Marathi (मराठी), Romanized Marathi, Devanagari Hindi (हिन्दी), and Hinglish seamlessly.
            3. **Principal Software Engineer Coding Standard**: For EVERY coding, debugging, or architecture task:
               - **Design & Invariants First**: Briefly state the architecture, data flow, and edge cases being guarded (2–4 crisp bullets) before emitting code.
               - **Complete, Compile-Ready Code**: Organize cleanly by file using `// File: path/FileName.ext` on the first line inside fenced code blocks (```` ```kotlin ````, ```` ```python ````, ```` ```ts ````, etc.) with 100% complete imports, strict types, null/bounds checks, coroutine cancellation & thread safety, and proper resource cleanup. Strictly ZERO `// TODO`, ZERO `...` omissions, ZERO stubbed logic.
               - **Surgical Diffs for Bug Fixes**: When fixing or refactoring existing code, diagnose the root cause concisely, emit a clean ```` ```diff ```` patch showing exact `-`/`+` lines, AND provide the full verified function/file.
               - **Verification & Edge Cases**: Note boundary cases handled and include concrete unit tests or verification steps when building non-trivial logic.
            4. **KaTeX / LaTeX Mathematics**: Format ALL equations, calculus, linear algebra, and physics cleanly with KaTeX delimiters: `${'$'}...${'$'}` inline and standalone `${'$'}${'$'}...${'$'}${'$'}` display blocks for every derivation step (`\int`, `\frac{a}{b}`, `\lim`, `\sum`, `\sqrt`, matrices), and place final answers inside `${'$'}${'$'}\boxed{...}${'$'}${'$'}` with a sanity check.
            5. **Batch & Multi-Question Completeness (10–20+ Questions)**: Whenever the user sends a batch or list of multiple questions (e.g., 5, 10, 15, or 20+ questions in one prompt), you MUST solve **EVERY SINGLE QUESTION sequentially from Q1 through QN** with clear numbered headings (`### Q1.`, `### Q2.`, ...), complete step-by-step reasoning, KaTeX math / code, and final boxed answers — NEVER skip middle questions, never summarize "similar to above", and never truncate early.
            6. **Interactive Diagrams & Web Artifacts**: When asked for interactive visual UI, flowcharts, architecture diagrams, or interactive HTML cards, output self-contained ```html, ```svg, or ```mermaid blocks so Aura Live Artifacts can preview them directly.
        """.trimIndent()

        val searchDirective = if (useWebSearch) {
            "\n\nLive Web Search Grounding & URL Context ON (Today: $currentDateTime): ALWAYS prioritize verified live search results and URL context provided in this request over training memory. Cross-check dates against Today, state exact dates/figures from the live sources, and NEVER invent or guess prices, scores, officeholders, news, or release versions if they are absent from the fetched sources."
        } else ""

        val sandboxDirective = if (useCodeExecution) {
            "\n\nLive Python Code Execution Sandbox ON: whenever exact computation, symbolic math, numerical verification, algorithm testing, or data analysis is needed, write and execute Python in the sandbox tool to prove and double-check your result before presenting the final answer."
        } else ""

        val memoryBlock = if (activeMemories.isNotEmpty()) {
            "\n\nPersonal User Memory (respect these facts & preferences naturally without repeating them robotically):\n" +
                activeMemories.take(20).joinToString("\n") { "- $it" }
        } else ""

        val vaultDirective = if (hasVaultContext) {
            "\n\nPrivate Knowledge Vault Context Provided: Prioritize grounded facts from the `[Vault: ...]` excerpts included in the prompt and cite the document title inline (e.g., `[Vault: Title]`)."
        } else ""

        val personaPrompt = persona?.systemPrompt
            ?: "You are a helpful, accurate, and thoughtful AI assistant."

        val modeInstruction = when (capabilityMode) {
            CapabilityMode.STANDARD ->
                "Deliver an executive-grade, cleanly structured Markdown answer with crisp hierarchy, Principal-tier zero-error code when coding is involved, and KaTeX math blocks when relevant."
            CapabilityMode.DEEP_THINK ->
                "Reason rigorously from first principles, audit edge cases and adversarial failure modes, and present a complete verified Markdown/KaTeX/code solution."
            CapabilityMode.CODE_ARCHITECT ->
                "Operate in Principal Code Architect Mode: outline architecture & invariants briefly, write 100% compile-ready multi-file production code (`// File: ...`) with full imports, strict typing, thread/coroutine/null safety, edge-case guards, clean ```diff patches when fixing bugs, and verification unit tests."
            CapabilityMode.DEEP_RESEARCH ->
                "Operate as an autonomous Deep Research Analyst: synthesize cross-verified facts into executive summaries, Markdown comparison matrices, risk analyses, and cited recommendations."
            CapabilityMode.CONCISE ->
                "Be direct, crisp, and scannable using executive bullet points, clean equations, and zero filler."
        }

        return "$brandRule$searchDirective$sandboxDirective$memoryBlock$vaultDirective\n\nPersona Focus: $personaPrompt\n\nMode: $modeInstruction"
    }

    private fun currentFormattedDateOnly(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    companion object {
        data class OpenAiCompatSseDelta(
            val reasoningDelta: String = "",
            val contentDelta: String = "",
            val errorMessage: String? = null
        )

        private fun optNonNullString(obj: JSONObject, key: String): String {
            return if (obj.isNull(key)) "" else obj.optString(key, "")
        }

        fun parseOpenAiCompatSseChunk(chunkJson: JSONObject): OpenAiCompatSseDelta? {
            chunkJson.optJSONObject("error")?.let { errObj ->
                val msg = optNonNullString(errObj, "message").ifBlank { errObj.toString() }
                val code = errObj.opt("code")?.toString().orEmpty()
                return OpenAiCompatSseDelta(errorMessage = "HTTP $code: $msg".trim())
            }
            val choice = chunkJson.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val delta = choice.optJSONObject("delta") ?: return null
            val reasoning = optNonNullString(delta, "reasoning")
                .ifEmpty { optNonNullString(delta, "reasoning_content") }
            val content = optNonNullString(delta, "content")
            return OpenAiCompatSseDelta(reasoningDelta = reasoning, contentDelta = content)
        }

        val ALL_FREE_GEMINI_CANDIDATES: List<ModelCandidate> = listOf(
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-3.8-flash",
                displayName = "Gemini 3.8 Flash",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-3.7-flash",
                displayName = "Gemini 3.7 Flash",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-3.6-flash",
                displayName = "Gemini 3.6 Flash",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-3.5-flash",
                displayName = "Gemini 3.5 Flash",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-3.5-flash-lite",
                displayName = "Gemini 3.5 Flash-Lite",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-2.5-flash",
                displayName = "Gemini 2.5 Flash",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-2.5-pro",
                displayName = "Gemini 2.5 Pro",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-2.5-flash-lite",
                displayName = "Gemini 2.5 Flash-Lite",
                supportsThinkingConfig = true,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemini-2.0-flash",
                displayName = "Gemini 2.0 Flash",
                supportsThinkingConfig = false,
                supportsSystemInstruction = true,
                supportsTools = true,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemma-3-27b-it",
                displayName = "Gemma 3 27B",
                supportsThinkingConfig = false,
                supportsSystemInstruction = false,
                supportsTools = false,
                supportsVision = true
            ),
            ModelCandidate(
                provider = ModelProvider.GEMINI,
                modelId = "gemma-3-12b-it",
                displayName = "Gemma 3 12B",
                supportsThinkingConfig = false,
                supportsSystemInstruction = false,
                supportsTools = false,
                supportsVision = true
            )
        )

        val ALL_FREE_OPENROUTER_DEEPSEEK_CANDIDATES: List<ModelCandidate> = listOf(
            ModelCandidate(
                provider = ModelProvider.OPENROUTER,
                modelId = "deepseek/deepseek-r1-0528:free",
                displayName = "DeepSeek R1 (0528)",
                supportsVision = false
            ),
            ModelCandidate(
                provider = ModelProvider.OPENROUTER,
                modelId = "deepseek/deepseek-chat-v3-0324:free",
                displayName = "DeepSeek V3 (0324)",
                supportsVision = false
            ),
            ModelCandidate(
                provider = ModelProvider.OPENROUTER,
                modelId = "tngtech/deepseek-r1t2-chimera:free",
                displayName = "DeepSeek R1T2 Chimera",
                supportsVision = false
            ),
            ModelCandidate(
                provider = ModelProvider.OPENROUTER,
                modelId = "deepseek/deepseek-r1:free",
                displayName = "DeepSeek R1",
                supportsVision = false
            ),
            ModelCandidate(
                provider = ModelProvider.OPENROUTER,
                modelId = "openrouter/free",
                displayName = "OpenRouter Free Auto",
                supportsVision = false
            )
        )

        val DIRECT_DEEPSEEK_CANDIDATES: List<ModelCandidate> = listOf(
            ModelCandidate(
                provider = ModelProvider.DEEPSEEK,
                modelId = "deepseek-reasoner",
                displayName = "DeepSeek R1 Reasoner",
                supportsVision = false
            ),
            ModelCandidate(
                provider = ModelProvider.DEEPSEEK,
                modelId = "deepseek-chat",
                displayName = "DeepSeek V3 Chat",
                supportsVision = false
            )
        )

        fun resolveGeminiCandidateModels(modelTier: AiModelTier): List<String> {
            val preferredPrimary = when (modelTier) {
                AiModelTier.GEMINI_3_8_FLASH -> "gemini-3.8-flash"
                AiModelTier.GEMINI_3_7_FLASH -> "gemini-3.7-flash"
                AiModelTier.GEMINI_3_6_FLASH -> "gemini-3.6-flash"
                AiModelTier.GEMINI_3_5_FLASH -> "gemini-3.5-flash"
                AiModelTier.GEMINI_3_5_FLASH_LITE -> "gemini-3.5-flash-lite"
                AiModelTier.GEMINI_2_5_FLASH -> "gemini-2.5-flash"
                AiModelTier.GEMINI_4_ARGON -> "gemini-2.5-pro"
                AiModelTier.GEMINI_2_5_FLASH_LITE -> "gemini-2.5-flash-lite"
                AiModelTier.GEMINI_2_0_FLASH -> "gemini-2.0-flash"
                AiModelTier.GEMMA_3_27B -> "gemma-3-27b-it"
                AiModelTier.GEMMA_3_12B -> "gemma-3-12b-it"
                else -> "gemini-3.8-flash"
            }
            return buildList {
                add(preferredPrimary)
                ALL_FREE_GEMINI_CANDIDATES.forEach { candidate ->
                    if (candidate.modelId !in this) add(candidate.modelId)
                }
            }
        }

        fun resolveDeepSeekCandidates(
            modelTier: AiModelTier,
            hasOpenRouterKey: Boolean,
            hasDeepSeekKey: Boolean
        ): List<ModelCandidate> {
            val orById = ALL_FREE_OPENROUTER_DEEPSEEK_CANDIDATES.associateBy { it.modelId }
            val dsById = DIRECT_DEEPSEEK_CANDIDATES.associateBy { it.modelId }
            val ordered = mutableListOf<ModelCandidate>()

            fun addOr(id: String) {
                if (hasOpenRouterKey) orById[id]?.let { ordered.add(it) }
            }
            fun addDs(id: String) {
                if (hasDeepSeekKey) dsById[id]?.let { ordered.add(it) }
            }

            when (modelTier) {
                AiModelTier.DEEPSEEK_V3_FREE -> {
                    addOr("deepseek/deepseek-chat-v3-0324:free")
                    addDs("deepseek-chat")
                    addOr("deepseek/deepseek-r1-0528:free")
                    addOr("tngtech/deepseek-r1t2-chimera:free")
                    addDs("deepseek-reasoner")
                    addOr("deepseek/deepseek-r1:free")
                    addOr("openrouter/free")
                }
                AiModelTier.DEEPSEEK_CHIMERA_FREE -> {
                    addOr("tngtech/deepseek-r1t2-chimera:free")
                    addOr("deepseek/deepseek-r1-0528:free")
                    addOr("deepseek/deepseek-chat-v3-0324:free")
                    addDs("deepseek-reasoner")
                    addDs("deepseek-chat")
                    addOr("deepseek/deepseek-r1:free")
                    addOr("openrouter/free")
                }
                else -> {
                    addOr("deepseek/deepseek-r1-0528:free")
                    addDs("deepseek-reasoner")
                    addOr("deepseek/deepseek-r1:free")
                    addOr("tngtech/deepseek-r1t2-chimera:free")
                    addOr("deepseek/deepseek-chat-v3-0324:free")
                    addDs("deepseek-chat")
                    addOr("openrouter/free")
                }
            }
            return ordered.distinctBy { it.provider to it.modelId }
        }

        fun buildCrossProviderFallbackChain(
            requestedTier: AiModelTier,
            effectiveTier: AiModelTier,
            capabilityMode: CapabilityMode,
            hasImageAttachment: Boolean,
            requiresGeminiTools: Boolean,
            preferFastGroq: Boolean,
            hasGeminiKey: Boolean,
            hasOpenRouterKey: Boolean,
            hasDeepSeekKey: Boolean,
            hasGroqKey: Boolean
        ): List<ModelCandidate> {
            val geminiCandidates = if (hasGeminiKey) {
                val orderedIds = resolveGeminiCandidateModels(effectiveTier)
                val byId = ALL_FREE_GEMINI_CANDIDATES.associateBy { it.modelId }
                orderedIds.mapNotNull { byId[it] }
            } else emptyList()

            val deepSeekCandidates = if (!hasImageAttachment && (hasOpenRouterKey || hasDeepSeekKey)) {
                resolveDeepSeekCandidates(effectiveTier, hasOpenRouterKey, hasDeepSeekKey)
            } else emptyList()

            val groqCandidates = if (hasGroqKey) {
                val effectiveGroqCap = if (capabilityMode == CapabilityMode.STANDARD && requestedTier.isDeepSeekTier) {
                    CapabilityMode.DEEP_THINK
                } else {
                    capabilityMode
                }
                selectGroqFallbackModels(
                    capabilityMode = effectiveGroqCap,
                    modelTier = effectiveTier,
                    hasImageAttachment = hasImageAttachment
                ).map { id ->
                    ModelCandidate(
                        provider = ModelProvider.GROQ,
                        modelId = id,
                        displayName = groqFriendlyName(id),
                        supportsVision = id.contains("llama-4-scout")
                    )
                }
            } else emptyList()

            val combined = when {
                requestedTier.isDeepSeekTier -> {
                    deepSeekCandidates + groqCandidates + geminiCandidates
                }
                requestedTier != AiModelTier.AUTO_ROUTER -> {
                    geminiCandidates + deepSeekCandidates + groqCandidates
                }
                hasGroqKey && !requiresGeminiTools && (preferFastGroq || effectiveTier == AiModelTier.GEMINI_3_5_FLASH_LITE) -> {
                    groqCandidates + geminiCandidates + deepSeekCandidates
                }
                else -> {
                    geminiCandidates + deepSeekCandidates + groqCandidates
                }
            }

            return combined.distinctBy { it.provider to it.modelId }
        }

        private fun groqFriendlyName(modelId: String): String = when {
            modelId.contains("gpt-oss-120b") -> "Groq GPT-OSS 120B"
            modelId.contains("gpt-oss-20b") -> "Groq GPT-OSS 20B"
            modelId.contains("llama-3.3-70b") -> "Groq Llama 3.3 70B"
            modelId.contains("llama-3.1-8b") -> "Groq Llama 3.1 8B"
            modelId.contains("llama-4-scout") -> "Groq Llama 4 Scout Vision"
            else -> "Groq Fast AI"
        }

        fun hasFreshWebCue(prompt: String): Boolean {
            val lower = prompt.lowercase()
            if (Regex("https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(prompt)) return true
            if (Regex("[\\u0900-\\u097F]").containsMatchIn(prompt) &&
                Regex("(आज|आजचा|आजचे|ताज्या|बातम्या|भाव|किंमत|कोण|जिंकले|हवामान|स्कोर|नवीन|सध्या)").containsMatchIn(prompt)
            ) return true

            return Regex(
                "\\b(latest|today|todays|today's|yesterday|tonight|now|right now|currently|current|recent|recently|breaking|live|newest|upcoming|this week|this month|this year|2025|2026|2027|news|headlines|price|prices|cost|rate|rates|stock|stocks|nifty|sensex|crypto|bitcoin|btc|eth|gold|silver|usd to inr|exchange rate|weather|forecast|temperature|aqi|score|scores|match|matches|winner|won|standings|points table|ipl|world cup|election|elections|result|results|release|released|launch|launched|version|versions|update|updates|ceo|president|prime minister|chief minister|who is|who won|aaj|aajcha|aajche|aajchi|kal|kalcha|ata|sadhya|taaja|taza|bhav|kimmat|kon jinkla|kon jinkle|kon ahe|kya chal raha|havaman)\\b"
            ).containsMatchIn(lower)
        }

        fun classifyAutoRoute(request: AiRequest): AutoRouteDecision {
            if (request.capabilityMode == CapabilityMode.DEEP_RESEARCH) {
                return AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_4_ARGON,
                    resolvedCapability = CapabilityMode.DEEP_RESEARCH,
                    useWebSearch = true,
                    routeLabel = "Auto · Deep Research"
                )
            }

            val lower = request.prompt.lowercase()
            val hasCodeCue = Regex("\\b(kotlin|compose|android|jetpack|python|java|typescript|javascript|react|nextjs|node|rust|golang|cpp|c\\+\\+|html|css|tailwind|sql|bug|debug|crash|exception|stacktrace|nullpointer|refactor|architect|coroutine|stateflow|room|hilt|retrofit|ktor|gradle|api|diff|function|class|algorithm|dsa|leetcode|shader|docker|regex|unittest|junit|compile|code)\\b")
                .containsMatchIn(lower)
            val hasMathCue = Regex("(\\\\int|\\\\frac|\\\\sum|integral|derivative|theorem|prove|olympiad|jee|matrix|eigen|calculus|probability|equation)")
                .containsMatchIn(lower)
            val hasBatchQuestions = Regex("(?i)(\\bq\\s*1\\b|\\bq\\s*5\\b|1\\).*2\\).*3\\)|solve all|all questions)")
                .containsMatchIn(lower) || request.extraAttachmentUris.isNotEmpty()
            val hasFreshWebCue = hasFreshWebCue(request.prompt)

            return when {
                hasCodeCue -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_3_8_FLASH,
                    resolvedCapability = CapabilityMode.CODE_ARCHITECT,
                    useWebSearch = request.useWebSearch || hasFreshWebCue,
                    routeLabel = "Auto · Code Studio"
                )
                hasMathCue || hasBatchQuestions || request.prompt.length > 260 -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_4_ARGON,
                    resolvedCapability = CapabilityMode.DEEP_THINK,
                    useWebSearch = request.useWebSearch || hasFreshWebCue,
                    routeLabel = "Auto · Deep Reasoning"
                )
                hasFreshWebCue -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_3_8_FLASH,
                    resolvedCapability = request.capabilityMode,
                    useWebSearch = true,
                    routeLabel = "Auto · Live Web"
                )
                request.attachmentIsImage -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_3_8_FLASH,
                    resolvedCapability = request.capabilityMode,
                    useWebSearch = request.useWebSearch,
                    routeLabel = "Auto · Vision Pro"
                )
                request.prompt.length < 95 && request.capabilityMode == CapabilityMode.STANDARD -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_3_5_FLASH_LITE,
                    resolvedCapability = CapabilityMode.STANDARD,
                    useWebSearch = request.useWebSearch,
                    routeLabel = "Auto · Ultra Fast"
                )
                else -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_3_8_FLASH,
                    resolvedCapability = request.capabilityMode,
                    useWebSearch = request.useWebSearch,
                    routeLabel = "Auto · Balanced"
                )
            }
        }

        fun selectGroqFallbackModels(
            capabilityMode: CapabilityMode,
            modelTier: AiModelTier,
            hasImageAttachment: Boolean
        ): List<String> {
            return when {
                hasImageAttachment -> listOf(
                    "meta-llama/llama-4-scout-17b-16e-instruct"
                )
                capabilityMode == CapabilityMode.DEEP_THINK ||
                    capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                    capabilityMode == CapabilityMode.DEEP_RESEARCH ||
                    modelTier == AiModelTier.GEMINI_4_ARGON ||
                    modelTier.isDeepSeekTier -> listOf(
                    "openai/gpt-oss-120b",
                    "openai/gpt-oss-20b",
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant"
                )
                else -> listOf(
                    "openai/gpt-oss-20b",
                    "llama-3.1-8b-instant",
                    "openai/gpt-oss-120b",
                    "llama-3.3-70b-versatile"
                )
            }
        }

        fun isTransientOrRecoverableErrorMessage(rawMessage: String): Boolean {
            val msg = rawMessage.lowercase()
            return msg.contains("503") ||
                msg.contains("unavailable") ||
                msg.contains("overloaded") ||
                msg.contains("500") ||
                msg.contains("502") ||
                msg.contains("504") ||
                msg.contains("internal") ||
                msg.contains("deadline") ||
                msg.contains("busy") ||
                msg.contains("try again") ||
                msg.contains("quota") ||
                msg.contains("429") ||
                msg.contains("rate") ||
                msg.contains("exceeded") ||
                msg.contains("resource_exhausted") ||
                msg.contains("billing") ||
                msg.contains("blaze") ||
                msg.contains("balance") ||
                msg.contains("401") ||
                msg.contains("402") ||
                msg.contains("403") ||
                msg.contains("unauthorized") ||
                msg.contains("permission") ||
                msg.contains("not found") ||
                msg.contains("decommissioned") ||
                msg.contains("404") ||
                msg.contains("400") ||
                msg.contains("invalid_argument") ||
                msg.contains("unsupported") ||
                msg.contains("empty response") ||
                msg.contains("limit:") ||
                msg.contains("tool") ||
                msg.contains("grounding") ||
                msg.contains("eof") ||
                msg.contains("reset") ||
                msg.contains("connect") ||
                msg.contains("unable to resolve") ||
                msg.contains("timeout")
        }

        fun compactConversationContext(history: List<MessageEntity>, recentKeepCount: Int = 12): String {
            val valid = history.filter { !it.isError && it.content.isNotBlank() }
            if (valid.isEmpty()) return ""

            val recentSet = valid.takeLast(recentKeepCount).map { it.id }.toSet()
            val pinnedOutsideRecent = valid.filter { it.isPinned && it.id !in recentSet }
            val olderMessages = if (valid.size > recentKeepCount) valid.dropLast(recentKeepCount) else emptyList()

            val sb = StringBuilder()
            if (pinnedOutsideRecent.isNotEmpty()) {
                sb.append("### [Pinned Thread Rules & Specs]\n")
                pinnedOutsideRecent.forEach { msg ->
                    val speaker = if (msg.role == "user") "User" else "Aura AI"
                    sb.append("- [$speaker PINNED]: ${msg.content.replace(Regex("\\s+"), " ").trim().take(360)}\n")
                }
                sb.append("\n")
            }

            if (olderMessages.isNotEmpty()) {
                sb.append("### [Earlier Conversation Context Digest (${olderMessages.size} prior turns)]\n")
                val codeFiles = Regex("// File:\\s*([^\\s]+)").findAll(olderMessages.joinToString("\n") { it.content })
                    .map { it.groupValues[1] }
                    .distinct()
                    .take(10)
                    .toList()
                if (codeFiles.isNotEmpty()) {
                    sb.append("- Discussed Files: ${codeFiles.joinToString(", ")}\n")
                }
                val perTurnBudget = (1800 / olderMessages.size.coerceAtLeast(1)).coerceIn(85, 220)
                olderMessages.forEach { msg ->
                    if (!msg.isPinned) {
                        val speaker = if (msg.role == "user") "User" else "Aura AI"
                        val snippet = msg.content.replace(Regex("```[\\s\\S]*?```"), "[code]")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(perTurnBudget)
                        if (snippet.isNotBlank()) {
                            sb.append("- $speaker: $snippet\n")
                        }
                    }
                }
            }
            return sb.toString().trim()
        }
    }

    private fun buildGroqMessagesArray(
        systemText: String,
        history: List<MessageEntity>,
        promptText: String,
        attachedBitmaps: List<Bitmap>
    ): JSONArray {
        val contextDigest = compactConversationContext(history, recentKeepCount = 12)
        val effectiveSystemText = if (contextDigest.isNotBlank()) {
            "$systemText\n\n$contextDigest"
        } else {
            systemText
        }

        val validMessages = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(12)

        val alternating = mutableListOf<Pair<String, String>>()
        var expectRole = "user"
        for (msg in validMessages) {
            val role = if (msg.role == "user") "user" else "assistant"
            if (role == expectRole) {
                alternating.add(role to msg.content)
                expectRole = if (expectRole == "user") "assistant" else "user"
            }
        }
        if (alternating.lastOrNull()?.first == "user") {
            alternating.removeAt(alternating.lastIndex)
        }

        return JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", effectiveSystemText))
            alternating.forEach { (role, text) ->
                put(JSONObject().put("role", role).put("content", text))
            }
            if (attachedBitmaps.isNotEmpty()) {
                val contentParts = JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", promptText))
                    attachedBitmaps.forEach { bmp ->
                        val b64 = encodeBitmapJpegBase64(bmp)
                        put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$b64")
                            )
                        )
                    }
                }
                put(JSONObject().put("role", "user").put("content", contentParts))
            } else {
                put(JSONObject().put("role", "user").put("content", promptText))
            }
        }
    }

    private fun buildHistoryJsonArray(history: List<MessageEntity>): JSONArray {
        val contextDigest = compactConversationContext(history, recentKeepCount = 12)
        val validMessages = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(12)

        val alternating = mutableListOf<Pair<String, String>>()
        var expectRole = "user"
        for (msg in validMessages) {
            val role = if (msg.role == "user") "user" else "model"
            if (role == expectRole) {
                alternating.add(role to msg.content)
                expectRole = if (expectRole == "user") "model" else "user"
            }
        }
        if (alternating.lastOrNull()?.first == "user") {
            alternating.removeAt(alternating.lastIndex)
        }

        return JSONArray().apply {
            if (contextDigest.isNotBlank()) {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", contextDigest)))
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", "Understood. I will preserve all pinned specs and earlier thread context.")))
                    }
                )
            }
            alternating.forEach { (role, text) ->
                put(
                    JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }
                )
            }
        }
    }

    private fun buildUserPromptText(
        request: AiRequest,
        liveWebContextBlock: String = ""
    ): String {
        val sb = StringBuilder()
        if (liveWebContextBlock.isNotBlank()) {
            sb.append(liveWebContextBlock).append("\n\n")
        }
        if (request.vaultContextSnippets.isNotEmpty()) {
            sb.append("### Retrieved Private Document Vault Context:\n")
            request.vaultContextSnippets.forEach { chunk ->
                sb.append("[Vault: ${chunk.docTitle} · Part ${chunk.chunkIndex + 1}]\n${chunk.content}\n\n")
            }
        }
        val snippet = request.attachmentTextSnippet
        if (!snippet.isNullOrBlank() && request.attachmentName != null) {
            val label = if (request.attachmentIsImage) {
                "Extracted OCR / Scan Text (${request.attachmentName})"
            } else {
                "Attached file (${request.attachmentName})"
            }
            sb.append("$label:\n```\n$snippet\n```\n\n")
        }
        sb.append(request.prompt)
        return sb.toString()
    }

    private suspend fun loadAttachedBitmaps(request: AiRequest): List<Bitmap> {
        if (!request.attachmentIsImage) return emptyList()
        val uris = buildList {
            request.attachmentUri?.let { add(it) }
            addAll(request.extraAttachmentUris)
        }.distinct().take(6)
        if (uris.isEmpty()) return emptyList()
        val maxDim = if (uris.size > 1) 1280 else 1600
        return uris.mapNotNull { loadScaledBitmap(Uri.parse(it), maxDim) }
    }

    private fun encodeBitmapJpegBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun loadScaledBitmap(uri: Uri, maxDim: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = if (uri.scheme == "file" && uri.path != null) {
                BitmapFactory.decodeFile(uri.path)
            } else {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } ?: return@runCatching null

            if (raw.width <= maxDim && raw.height <= maxDim) {
                raw
            } else {
                val scale = maxDim.toFloat() / maxOf(raw.width, raw.height)
                val targetW = (raw.width * scale).toInt().coerceAtLeast(1)
                val targetH = (raw.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(raw, targetW, targetH, true)
            }
        }.getOrNull()
    }

    private suspend fun saveBitmapToInternalStorage(bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "generated_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "aura_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file.absolutePath
        }

    private data class ParsedThinking(
        val thinking: String?,
        val answer: String,
        val isStillInsideThinkingTag: Boolean
    )

    private fun splitThinkingAndContent(raw: String): ParsedThinking {
        val tagPairs = listOf("<thinking>" to "</thinking>", "<think>" to "</think>")
        val matchedPair = tagPairs.firstOrNull { (start, _) -> raw.contains(start) }
            ?: return ParsedThinking(thinking = null, answer = raw, isStillInsideThinkingTag = false)
        val (startTag, endTag) = matchedPair
        val startIdx = raw.indexOf(startTag)
        val contentStart = startIdx + startTag.length
        val endIdx = raw.indexOf(endTag, contentStart)
        return if (endIdx == -1) {
            ParsedThinking(
                thinking = raw.substring(contentStart).trim(),
                answer = "",
                isStillInsideThinkingTag = true
            )
        } else {
            val thinkingPart = raw.substring(contentStart, endIdx).trim()
            val answerPart = raw.substring(endIdx + endTag.length).trimStart()
            ParsedThinking(
                thinking = thinkingPart.ifBlank { null },
                answer = answerPart,
                isStillInsideThinkingTag = false
            )
        }
    }
}
