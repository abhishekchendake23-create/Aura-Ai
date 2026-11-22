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
private const val STREAM_EMIT_THROTTLE_MS = 45L
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
    val useCodeExecution: Boolean = false
)

data class AutoRouteDecision(
    val resolvedModel: AiModelTier,
    val resolvedCapability: CapabilityMode,
    val useWebSearch: Boolean,
    val routeLabel: String
)

sealed interface AiStreamEvent {
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
        val sources: List<SourceCitation> = emptyList()
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
        val geminiKey = prefs.getEffectiveApiKey()
        val groqKey = prefs.getEffectiveGroqApiKey()
        val preferFastGroq = prefs.preferFastGroq.first()

        if (geminiKey.isBlank() && groqKey.isBlank()) {
            return@withContext Result.failure(Exception(sanitizeAuraError("MISSING_API_KEY", isImage = false)))
        }

        val systemPrompt = "You are a frontier prompt engineer for Aura AI (Abhynex). Rewrite the user's rough prompt into a crystal-clear, high-leverage, structured prompt specifying objective, constraints, edge cases, and desired output format. Keep it concise (max 4-6 lines), preserve the user's original intent and language style, and return ONLY the rewritten prompt text with no quotes or preamble."

        var lastErr: Throwable? = null
        val groqFirst = groqKey.isNotBlank() && (preferFastGroq || geminiKey.isBlank())

        if (groqFirst) {
            val groqResult = enhancePromptViaGroq(groqKey, systemPrompt, trimmed)
            groqResult.onSuccess { return@withContext Result.success(it) }
            lastErr = groqResult.exceptionOrNull()
        }

        if (geminiKey.isNotBlank()) {
            val geminiResult = enhancePromptViaGemini(geminiKey, systemPrompt, trimmed)
            geminiResult.onSuccess { return@withContext Result.success(it) }
            lastErr = geminiResult.exceptionOrNull()
        }

        if (!groqFirst && groqKey.isNotBlank()) {
            val groqResult = enhancePromptViaGroq(groqKey, systemPrompt, trimmed)
            groqResult.onSuccess { return@withContext Result.success(it) }
            lastErr = groqResult.exceptionOrNull()
        }

        Result.failure(Exception(sanitizeAuraError(lastErr?.localizedMessage ?: lastErr?.message, isImage = false)))
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
        val geminiKey = prefs.getEffectiveApiKey()
        val groqKey = prefs.getEffectiveGroqApiKey()
        val exaKey = prefs.getEffectiveExaApiKey()
        val preferFastGroq = prefs.preferFastGroq.first()

        if (geminiKey.isBlank() && groqKey.isBlank()) {
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
                geminiKey = geminiKey,
                groqKey = groqKey,
                exaKey = exaKey,
                startMs = startMs,
                emitEvent = emitEvent
            )
            return
        }

        var liveWebContext: LiveWebContext? = null
        var webSearchThinkingPrefix = ""
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
                    exaApiKey = exaKey,
                    preferLiveCrawl = autoFreshWeb || request.useWebSearch
                )
            }.getOrNull()

            if (liveWebContext != null && liveWebContext.results.isNotEmpty()) {
                webSearchThinkingPrefix = "Live Web Search: checked ${liveWebContext.results.size} current sources (${liveWebContext.executedQueries.joinToString(" · ")})\n"
                emitEvent(
                    AiStreamEvent.ThinkingChunk(
                        thinkingText = webSearchThinkingPrefix,
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
            geminiKey.isBlank() ->
                "### LIVE WEB SEARCH STATUS\nLive web search returned 0 reachable results right now (device may be offline or search endpoints blocked). Do NOT guess or invent current prices, scores, news, or post-cutoff facts; state clearly what could not be verified live."
            else -> ""
        }
        val promptText = buildUserPromptText(request, liveWebContextBlock = livePromptNotice)
        val liveSources = liveWebContext?.sourceCitations.orEmpty()

        val hasExaResults = exaKey.isNotBlank() && !liveWebContext?.results.isNullOrEmpty()
        val requiresGeminiTools = request.useCodeExecution ||
            attachedBitmaps.size > 1 ||
            (request.useWebSearch && !hasExaResults)
        val useGroqPrimary = groqKey.isNotBlank() && (
            geminiKey.isBlank() ||
                (!requiresGeminiTools && (preferFastGroq || request.modelTier == AiModelTier.GEMINI_3_5_FLASH_LITE))
            )

        var lastError: Throwable? = null

        if (useGroqPrimary) {
            val groqFailure = streamViaGroqChain(
                groqKey = groqKey,
                request = request,
                systemText = systemText,
                promptText = promptText,
                attachedBitmaps = attachedBitmaps,
                startMs = startMs,
                initialSources = liveSources,
                initialThinkingPrefix = webSearchThinkingPrefix,
                emitEvent = emitEvent
            )
            if (groqFailure == null) return
            lastError = groqFailure

            if (geminiKey.isNotBlank()) {
                Log.w(TAG, "Groq fast chain failed (${groqFailure.message}); falling back to primary cloud engine")
                val geminiFailure = streamViaGeminiChain(
                    apiKey = geminiKey,
                    request = request,
                    systemText = systemText,
                    promptText = promptText,
                    attachedBitmaps = attachedBitmaps,
                    startMs = startMs,
                    initialSources = liveSources,
                    initialThinkingPrefix = webSearchThinkingPrefix,
                    emitEvent = emitEvent
                )
                if (geminiFailure == null) return
                lastError = geminiFailure
            }
        } else {
            val geminiFailure = streamViaGeminiChain(
                apiKey = geminiKey,
                request = request,
                systemText = systemText,
                promptText = promptText,
                attachedBitmaps = attachedBitmaps,
                startMs = startMs,
                initialSources = liveSources,
                initialThinkingPrefix = webSearchThinkingPrefix,
                emitEvent = emitEvent
            )
            if (geminiFailure == null) return
            lastError = geminiFailure

            if (groqKey.isNotBlank()) {
                Log.w(TAG, "Primary cloud chain failed (${geminiFailure.message}); falling back to Groq fast model chain")
                val groqPromptNotice = if (request.useWebSearch && livePromptNotice.isBlank()) {
                    "### LIVE WEB SEARCH STATUS\nLive web search returned 0 reachable results right now. Do NOT guess or invent current prices, scores, news, or post-cutoff facts; state clearly what could not be verified live."
                } else {
                    livePromptNotice
                }
                val groqPromptText = if (groqPromptNotice != livePromptNotice) {
                    buildUserPromptText(request, liveWebContextBlock = groqPromptNotice)
                } else {
                    promptText
                }
                val groqFailure = streamViaGroqChain(
                    groqKey = groqKey,
                    request = request,
                    systemText = systemText,
                    promptText = groqPromptText,
                    attachedBitmaps = attachedBitmaps,
                    startMs = startMs,
                    initialSources = liveSources,
                    initialThinkingPrefix = webSearchThinkingPrefix,
                    emitEvent = emitEvent
                )
                if (groqFailure == null) return
                lastError = groqFailure
            }
        }

        emitEvent(
            AiStreamEvent.Failed(
                sanitizeAuraError(lastError?.localizedMessage ?: lastError?.message, isImage = false)
            )
        )
    }

    private suspend fun streamDeepResearch(
        request: AiRequest,
        geminiKey: String,
        groqKey: String,
        exaKey: String,
        startMs: Long,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val thinkingLog = StringBuilder("Deep Research Agent · Phase 1/3: Designing research plan & sub-queries...\n")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val subQueries = planResearchQueries(request.prompt, geminiKey, groqKey)
        thinkingLog.append("Research Angles:\n")
        subQueries.forEachIndexed { idx, q ->
            thinkingLog.append("  ${idx + 1}. $q\n")
        }
        thinkingLog.append("\nPhase 2/3: Gathering live verified facts & cross-checking sources...\n")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val collectedSources = linkedMapOf<String, SourceCitation>()
        val researchNotes = StringBuilder()

        for ((idx, subQ) in subQueries.withIndex()) {
            val liveStepContext = runCatching {
                liveWebSearchEngine.gatherLiveContext(
                    queries = listOf(subQ),
                    maxResults = 4,
                    exaApiKey = exaKey,
                    preferLiveCrawl = true
                )
            }.getOrNull()

            liveStepContext?.sourceCitations?.forEach { (uri, src) ->
                collectedSources[uri] = src
            }
            val liveBlock = liveStepContext?.formattedPromptBlock.orEmpty()

            var stepFindings = ""
            var geminiStepSources = 0
            if (geminiKey.isNotBlank()) {
                runCatching {
                    val (findings, sources) = runGroundedResearchStep(geminiKey, subQ, liveBlock)
                    sources.forEach { src -> collectedSources[src.uri] = src }
                    geminiStepSources = sources.size
                    stepFindings = findings
                }.onFailure { err ->
                    Log.w(TAG, "Deep research Gemini step failed for '$subQ': ${err.message}")
                }
            }

            if (stepFindings.isBlank() && groqKey.isNotBlank()) {
                runCatching {
                    stepFindings = runGroqResearchStep(groqKey, subQ, liveBlock)
                }.onFailure { err ->
                    Log.w(TAG, "Deep research Groq step failed for '$subQ': ${err.message}")
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

        thinkingLog.append("\nPhase 3/3: Synthesizing structured executive report with comparisons & takeaways...")
        emitEvent(AiStreamEvent.ThinkingChunk(thinkingLog.toString(), System.currentTimeMillis() - startMs))

        val synthesisPrompt = buildString {
            append("Original Research Request:\n${request.prompt}\n\n")
            if (researchNotes.isNotBlank()) {
                append("Verified Multi-Angle Research Briefs:\n$researchNotes\n\n")
            } else {
                append("Notice: Live research angles returned sparse results; stick strictly to verified facts and clearly flag anything uncertain.\n\n")
            }
            append(
                "Synthesize a comprehensive, publication-grade executive research report answering the original request. " +
                    "Include: (1) Executive Summary, (2) Structured Markdown Comparison Table where relevant, " +
                    "(3) Deep Technical / Practical Breakdown with risks & trade-offs, and (4) Clear Actionable Recommendations."
            )
        }

        val synthesisRequest = request.copy(
            prompt = synthesisPrompt,
            modelTier = AiModelTier.GEMINI_4_ARGON,
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

        var err: Throwable? = null
        if (geminiKey.isNotBlank()) {
            err = streamViaGeminiChain(
                apiKey = geminiKey,
                request = synthesisRequest,
                systemText = systemText,
                promptText = buildUserPromptText(synthesisRequest),
                attachedBitmaps = bitmaps,
                startMs = startMs,
                initialSources = collectedSources,
                initialThinkingPrefix = thinkingLog.toString(),
                emitEvent = emitEvent
            )
        }
        if (err != null || geminiKey.isBlank()) {
            if (groqKey.isNotBlank()) {
                err = streamViaGroqChain(
                    groqKey = groqKey,
                    request = synthesisRequest,
                    systemText = systemText,
                    promptText = buildUserPromptText(synthesisRequest),
                    attachedBitmaps = bitmaps,
                    startMs = startMs,
                    initialSources = collectedSources,
                    initialThinkingPrefix = thinkingLog.toString(),
                    emitEvent = emitEvent
                )
            }
        }
        if (err != null) {
            emitEvent(
                AiStreamEvent.Failed(
                    sanitizeAuraError(err.localizedMessage ?: err.message, isImage = false)
                )
            )
        }
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

    private suspend fun streamViaGeminiChain(
        apiKey: String,
        request: AiRequest,
        systemText: String,
        promptText: String,
        attachedBitmaps: List<Bitmap>,
        startMs: Long,
        initialSources: Map<String, SourceCitation> = emptyMap(),
        initialThinkingPrefix: String = "",
        emitEvent: suspend (AiStreamEvent) -> Unit
    ): Throwable? {
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
            put(JSONObject().put("text", promptText))
        }
        chatHistory.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", userTurnParts)
            }
        )

        val candidateModels = resolveGeminiCandidateModels(request.modelTier)

        val toolsJson = buildToolsJsonArray(
            useCodeExecution = request.useCodeExecution,
            useWebSearch = request.useWebSearch,
            hasUrlInPrompt = hasUrlInPrompt
        )

        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            val rawAccumulated = StringBuilder()
            val nativeThinkingAccumulated = StringBuilder(
                if (initialThinkingPrefix.isNotBlank()) "$initialThinkingPrefix\n\n" else ""
            )
            val collectedSources = linkedMapOf<String, SourceCitation>().apply { putAll(initialSources) }
            var lastRenderedContent = ""
            var lastThinkingText = ""
            var lastContentEmitMs = 0L
            var lastThinkingEmitMs = 0L

            // First try with thinkingConfig (and retry once after brief backoff if transient 503/429);
            // if a model rejects thinkingConfig with HTTP 400, jump straight to thinking=false.
            val attemptPlan = listOf(
                true to 0L,
                true to 650L,
                false to 0L
            )

            var skipNextAttempt = false
            for ((attemptIdx, plan) in attemptPlan.withIndex()) {
                if (skipNextAttempt) {
                    skipNextAttempt = false
                    continue
                }
                val (includeThinkingConfig, preDelayMs) = plan
                if (preDelayMs > 0L) delay(preDelayMs)

                rawAccumulated.clear()
                nativeThinkingAccumulated.clear()
                if (initialThinkingPrefix.isNotBlank()) {
                    nativeThinkingAccumulated.append(initialThinkingPrefix).append("\n\n")
                }
                collectedSources.clear()
                collectedSources.putAll(initialSources)
                lastRenderedContent = ""
                lastThinkingText = ""
                lastContentEmitMs = 0L
                lastThinkingEmitMs = 0L

                try {
                    val requestPayload = buildChatRequestJson(
                        systemText = systemText,
                        contentsArray = chatHistory,
                        capabilityMode = request.capabilityMode,
                        modelTier = request.modelTier,
                        modelId = modelId,
                        toolsArray = toolsJson,
                        includeThinking = includeThinkingConfig
                    )

                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        executeSseStreamRequest(
                            url = "$GEMINI_API_BASE/$modelId:streamGenerateContent?alt=sse&key=$apiKey",
                            bodyJson = requestPayload
                        ) { chunkJson ->
                            val candidates = chunkJson.optJSONArray("candidates")
                            val candidate = candidates?.optJSONObject(0) ?: return@executeSseStreamRequest

                            // Grounding web citations
                            candidate.optJSONObject("groundingMetadata")
                                ?.optJSONArray("groundingChunks")
                                ?.let { chunks ->
                                    for (i in 0 until chunks.length()) {
                                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                                        val uri = web.optString("uri").trim()
                                        if (uri.isNotBlank()) {
                                            val title = web.optString("title").trim().ifBlank { uri }
                                            collectedSources[uri] = SourceCitation(title = title, uri = uri)
                                        }
                                    }
                                }

                            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                                ?: return@executeSseStreamRequest

                            var hasNewThinking = false
                            var hasNewContent = false

                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                when {
                                    part.optBoolean("thought", false) -> {
                                        val t = part.optString("text")
                                        if (t.isNotEmpty()) {
                                            nativeThinkingAccumulated.append(t)
                                            hasNewThinking = true
                                        }
                                    }
                                    part.has("executableCode") -> {
                                        val ec = part.optJSONObject("executableCode")
                                        if (ec != null) {
                                            val lang = ec.optString("language", "python")
                                                .lowercase()
                                                .ifBlank { "python" }
                                            val code = ec.optString("code")
                                            if (code.isNotBlank()) {
                                                rawAccumulated.append("\n\n```$lang\n$code\n```\n")
                                                hasNewContent = true
                                            }
                                        }
                                    }
                                    part.has("codeExecutionResult") -> {
                                        val res = part.optJSONObject("codeExecutionResult")
                                        if (res != null) {
                                            val outcome = res.optString("outcome", "OUTCOME_OK")
                                            val output = res.optString("output")
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
                                        val textPart = part.optString("text")
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
                    val finalThinking = nativeFinalThoughts ?: parsed.thinking
                    val finalAnswer = if (nativeFinalThoughts != null) {
                        rawAccumulated.toString().trim()
                    } else {
                        parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                    }
                    val totalDuration = System.currentTimeMillis() - startMs

                    if (finalAnswer.isNotBlank()) {
                        emitEvent(
                            AiStreamEvent.Completed(
                                fullText = finalAnswer,
                                thinkingText = finalThinking,
                                thinkingDurationMs = if (finalThinking != null) totalDuration else 0L,
                                sources = collectedSources.values.toList()
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
                        emitEvent(
                            AiStreamEvent.Completed(
                                fullText = partialAnswer,
                                thinkingText = parsed.thinking,
                                thinkingDurationMs = System.currentTimeMillis() - startMs,
                                sources = collectedSources.values.toList()
                            )
                        )
                        return null
                    }
                    lastError = e
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastError = t
                    Log.e(TAG, "Aura AI streaming failed on $modelId (attempt=$attemptIdx, thinking=$includeThinkingConfig): ${t.message}", t)
                    if (rawAccumulated.isNotEmpty()) {
                        // Stream dropped mid-answer after producing output: don't discard output, let user keep partial or retry cleanly
                        val parsed = splitThinkingAndContent(rawAccumulated.toString())
                        val partial = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                        if (partial.isNotBlank()) {
                            emitEvent(
                                AiStreamEvent.Completed(
                                    fullText = partial,
                                    thinkingText = parsed.thinking,
                                    thinkingDurationMs = System.currentTimeMillis() - startMs,
                                    sources = collectedSources.values.toList()
                                )
                            )
                            return null
                        }
                    }
                    val msgLower = (t.message ?: "").lowercase()
                    val isConfigIssue = includeThinkingConfig &&
                        (msgLower.contains("thinking") || msgLower.contains("400") || msgLower.contains("invalid_argument"))
                    val isTransientBusy = attemptIdx == 0 && (
                        msgLower.contains("503") || msgLower.contains("unavailable") ||
                            msgLower.contains("overloaded") || msgLower.contains("429") ||
                            msgLower.contains("500")
                        )
                    if (isTransientBusy) {
                        continue // next attempt has 650ms backoff
                    } else if (isConfigIssue && attemptIdx < attemptPlan.lastIndex) {
                        if (attemptIdx == 0) {
                            skipNextAttempt = true // skip duplicate thinking=true retry and jump straight to thinking=false
                        }
                        continue
                    } else {
                        break
                    }
                }
            }

            val hasNext = index < candidateModels.lastIndex
            val err = lastError
            if (hasNext && err != null && isRecoverableModelError(err)) {
                Log.w(TAG, "Switching to next live Aura model after error on $modelId: ${err.message}")
                continue
            } else {
                return err ?: IOException("Cloud stream failed")
            }
        }

        return lastError ?: IOException("Cloud stream failed")
    }

    private suspend fun streamViaGroqChain(
        groqKey: String,
        request: AiRequest,
        systemText: String,
        promptText: String,
        attachedBitmaps: List<Bitmap>,
        startMs: Long,
        initialSources: Map<String, SourceCitation> = emptyMap(),
        initialThinkingPrefix: String = "",
        emitEvent: suspend (AiStreamEvent) -> Unit
    ): Throwable? {
        val effectiveGroqCapability = if (request.useWebSearch && request.capabilityMode == CapabilityMode.STANDARD) {
            CapabilityMode.DEEP_THINK
        } else {
            request.capabilityMode
        }
        val candidateModels = selectGroqFallbackModels(
            capabilityMode = effectiveGroqCapability,
            modelTier = request.modelTier,
            hasImageAttachment = attachedBitmaps.isNotEmpty()
        )
        val messagesArray = buildGroqMessagesArray(
            systemText = systemText,
            history = request.history,
            promptText = promptText,
            attachedBitmaps = attachedBitmaps
        )
        val headers = mapOf("Authorization" to "Bearer $groqKey")
        val resolvedSources = initialSources.values.toList()
        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            val rawAccumulated = StringBuilder()
            val nativeThinkingAccumulated = StringBuilder(
                if (initialThinkingPrefix.isNotBlank()) "$initialThinkingPrefix\n\n" else ""
            )
            var lastRenderedContent = ""
            var lastThinkingText = ""
            var lastContentEmitMs = 0L
            var lastThinkingEmitMs = 0L

            try {
                val maxTokens = when {
                    modelId.contains("8b-instant") || modelId.contains("llama-4-scout") -> 8192
                    else -> 16384
                }
                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("messages", messagesArray)
                    put("stream", true)
                    put(
                        "temperature",
                        when {
                            request.useWebSearch -> 0.25
                            request.capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                                request.capabilityMode == CapabilityMode.CONCISE -> 0.35
                            else -> 0.65
                        }
                    )
                    put("max_completion_tokens", maxTokens)
                }

                withTimeout(INFERENCE_TIMEOUT_MS) {
                    executeSseStreamRequest(
                        url = GROQ_API_URL,
                        bodyJson = payload,
                        headers = headers
                    ) { chunkJson ->
                        val choice = chunkJson.optJSONArray("choices")?.optJSONObject(0)
                            ?: return@executeSseStreamRequest
                        val delta = choice.optJSONObject("delta") ?: return@executeSseStreamRequest

                        val reasoningDelta = delta.optString("reasoning")
                            .ifEmpty { delta.optString("reasoning_content") }
                        var hasNewThinking = false
                        var hasNewContent = false

                        if (reasoningDelta.isNotEmpty()) {
                            nativeThinkingAccumulated.append(reasoningDelta)
                            hasNewThinking = true
                        }
                        val contentDelta = delta.optString("content")
                        if (contentDelta.isNotEmpty()) {
                            rawAccumulated.append(contentDelta)
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
                            sources = resolvedSources
                        )
                    )
                    return null
                } else {
                    lastError = IOException("Empty response from Groq model $modelId")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Groq stream timed out on $modelId", e)
                if (rawAccumulated.isNotBlank()) {
                    val parsed = splitThinkingAndContent(rawAccumulated.toString())
                    val partialAnswer = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                    emitEvent(
                        AiStreamEvent.Completed(
                            fullText = partialAnswer,
                            thinkingText = parsed.thinking,
                            thinkingDurationMs = System.currentTimeMillis() - startMs,
                            sources = resolvedSources
                        )
                    )
                    return null
                }
                lastError = e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Groq streaming failed on $modelId: ${t.message}", t)
                if (rawAccumulated.isNotEmpty()) {
                    val parsed = splitThinkingAndContent(rawAccumulated.toString())
                    val partial = parsed.answer.ifBlank { rawAccumulated.toString().trim() }
                    if (partial.isNotBlank()) {
                        emitEvent(
                            AiStreamEvent.Completed(
                                fullText = partial,
                                thinkingText = parsed.thinking,
                                thinkingDurationMs = System.currentTimeMillis() - startMs,
                                sources = resolvedSources
                            )
                        )
                        return null
                    }
                }
            }

            val hasNext = index < candidateModels.lastIndex
            if (hasNext && isRecoverableModelError(lastError ?: IOException())) {
                Log.w(TAG, "Switching to next fast Groq model after error on $modelId: ${lastError?.message}")
                continue
            } else {
                return lastError ?: IOException("Fast engine request failed")
            }
        }

        return lastError ?: IOException("Fast engine request failed")
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
        includeThinking: Boolean
    ): JSONObject {
        val isProModel = modelId.contains("2.5-pro", ignoreCase = true)
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemText))
                )
            )
            put("contents", contentsArray)
            val generationConfig = JSONObject().apply {
                put("maxOutputTokens", 65536)
                if (includeThinking) {
                    val thinkingObj = JSONObject().apply {
                        when {
                            capabilityMode == CapabilityMode.DEEP_THINK ||
                                capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                                capabilityMode == CapabilityMode.DEEP_RESEARCH ||
                                modelTier == AiModelTier.GEMINI_4_ARGON -> {
                                put("includeThoughts", true)
                                put("thinkingBudget", 8192)
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
                    .replace(Regex("groq[-a-z0-9./]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("(meta-)?llama[-a-z0-9./]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("(openai/)?gpt-oss[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("openai", RegexOption.IGNORE_CASE), "Abhynex")
                    .replace(Regex("generativelanguage\\.googleapis\\.com\\S*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("api\\.groq\\.com\\S*", RegexOption.IGNORE_CASE), "")
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
            Never reveal or mention Gemini, Groq, Llama, OpenAI, GPT, Google, Claude, Anthropic, or any underlying model IDs; always represent yourself exclusively as **Aura AI** by **Abhynex**.

            Aura Apex Intelligence & Zero-Error Engineering Protocol:
            1. **Action-First Mastery**: Deliver complete, structured, authoritative answers immediately. If the user asks for a hard/practice problem, pick a classic challenging problem and solve it fully — never ask them to send one first.
            2. **Language Mastery**: ${language.promptInstruction} Always support English, Devanagari Marathi (मराठी), Romanized Marathi, Devanagari Hindi (हिन्दी), and Hinglish seamlessly.
            3. **Staff-Engineer Multi-Pass Coding**: Before finalizing any code, mentally trace types, nullability, concurrency/threads, coroutine cancellation, resource cleanup, security boundaries, and edge-case inputs. Deliver 100% complete, compile-ready code inside fenced language blocks (e.g., ```kotlin, ```python, ```ts, or ```diff for patches) with full imports and defensive error handling — NEVER use `// TODO`, ellipsis (`...`), or pseudo-code.
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
                "Provide well-structured Markdown answers with clear headings, KaTeX math blocks when relevant, and clean code."
            CapabilityMode.DEEP_THINK ->
                "Reason rigorously from first principles, check edge cases and adversarial failure modes, and present a complete verified Markdown/KaTeX/code solution."
            CapabilityMode.CODE_ARCHITECT ->
                "Architect complete, zero-error, production-grade multi-file solutions with full imports, strict typing, thread/null safety, edge-case coverage, and verification unit tests."
            CapabilityMode.DEEP_RESEARCH ->
                "Operate as an autonomous Deep Research Analyst: synthesize cross-verified facts into executive summaries, Markdown comparison matrices, risk analyses, and cited recommendations."
            CapabilityMode.CONCISE ->
                "Be direct, crisp, and scannable using concise bullet points, clean equations, and zero filler."
        }

        return "$brandRule$searchDirective$sandboxDirective$memoryBlock$vaultDirective\n\nPersona Focus: $personaPrompt\n\nMode: $modeInstruction"
    }

    private fun currentFormattedDateOnly(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    companion object {
        fun resolveGeminiCandidateModels(modelTier: AiModelTier): List<String> {
            return when (modelTier) {
                AiModelTier.GEMINI_4_ARGON -> listOf("gemini-2.5-pro", "gemini-2.5-flash")
                AiModelTier.GEMINI_3_5_FLASH_LITE -> listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")
                else -> listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
            }
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
            val hasCodeCue = Regex("\\b(kotlin|compose|python|java|typescript|react|sql|bug|debug|crash|refactor|architect|coroutine|room|gradle|api|diff|function|class)\\b")
                .containsMatchIn(lower)
            val hasMathCue = Regex("(\\\\int|\\\\frac|\\\\sum|integral|derivative|theorem|prove|olympiad|jee|matrix|eigen|calculus|probability|equation)")
                .containsMatchIn(lower)
            val hasBatchQuestions = Regex("(?i)(\\bq\\s*1\\b|\\bq\\s*5\\b|1\\).*2\\).*3\\)|solve all|all questions)")
                .containsMatchIn(lower) || request.extraAttachmentUris.isNotEmpty()
            val hasFreshWebCue = hasFreshWebCue(request.prompt)

            return when {
                hasCodeCue -> AutoRouteDecision(
                    resolvedModel = AiModelTier.GEMINI_4_ARGON,
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
                    modelTier == AiModelTier.GEMINI_4_ARGON -> listOf(
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
                msg.contains("not found") ||
                msg.contains("404") ||
                msg.contains("400") ||
                msg.contains("invalid_argument") ||
                msg.contains("unsupported") ||
                msg.contains("limit:") ||
                msg.contains("tool") ||
                msg.contains("grounding") ||
                msg.contains("eof") ||
                msg.contains("reset") ||
                msg.contains("timeout")
        }
    }

    private fun buildGroqMessagesArray(
        systemText: String,
        history: List<MessageEntity>,
        promptText: String,
        attachedBitmaps: List<Bitmap>
    ): JSONArray {
        val validMessages = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(14)

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
            put(JSONObject().put("role", "system").put("content", systemText))
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
        val validMessages = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(14)

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
