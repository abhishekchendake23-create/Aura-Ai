package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import com.example.data.model.AiModelTier
import com.example.data.model.CapabilityMode
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AuraAiRepository"
private const val INFERENCE_TIMEOUT_MS = 60_000L
private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
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
    val persona: PersonaEntity?,
    val history: List<MessageEntity>,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val attachmentTextSnippet: String? = null,
    val useWebSearch: Boolean = false,
    val useCodeExecution: Boolean = false
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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private suspend fun requireApiKey(): String {
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
        val apiKey = try {
            requireApiKey()
        } catch (t: Throwable) {
            return@withContext Result.failure(Exception(sanitizeAuraError(t.message, isImage = false)))
        }

        val candidates = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
        var lastErr: Throwable? = null
        for (modelId in candidates) {
            try {
                val payload = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "You are a frontier prompt engineer for Aura AI (Abhynex). Rewrite the user's rough prompt into a crystal-clear, high-leverage, structured prompt specifying objective, constraints, edge cases, and desired output format. Keep it concise (max 4-6 lines), preserve the user's original intent and language style, and return ONLY the rewritten prompt text with no quotes or preamble."
                                )
                            )
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
                                        JSONObject().put("text", "Rewrite and sharpen this prompt:\n$trimmed")
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
                    return@withContext Result.success(enhanced)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastErr = t
                if (!isRecoverableModelError(t)) break
            }
        }
        Result.failure(Exception(sanitizeAuraError(lastErr?.localizedMessage ?: lastErr?.message, isImage = false)))
    }

    override fun streamResponse(request: AiRequest): Flow<AiStreamEvent> = flow {
        if (request.modelTier.isImageModel) {
            emit(generateOrEditImage(request))
        } else {
            streamChatOrVision(request) { event -> emit(event) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamChatOrVision(
        request: AiRequest,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val apiKey = try {
            requireApiKey()
        } catch (t: Throwable) {
            emitEvent(AiStreamEvent.Failed(sanitizeAuraError(t.message, isImage = false)))
            return
        }

        val startMs = System.currentTimeMillis()
        val systemText = buildSystemInstruction(
            persona = request.persona,
            capabilityMode = request.capabilityMode,
            useWebSearch = request.useWebSearch,
            useCodeExecution = request.useCodeExecution
        )
        val chatHistory = buildHistoryJsonArray(request.history)
        val attachedBitmap = if (request.attachmentIsImage && request.attachmentUri != null) {
            loadScaledBitmap(Uri.parse(request.attachmentUri))
        } else null

        val promptText = buildUserPromptText(request)
        val hasUrlInPrompt = Regex("https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(promptText)

        val userTurnParts = JSONArray().apply {
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
            put(JSONObject().put("text", promptText))
        }
        chatHistory.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", userTurnParts)
            }
        )

        val candidateModels = buildList {
            add(request.modelTier.id)
            add("gemini-3.8-flash")
            add("gemini-3.5-flash-lite")
            add("gemini-3.1-flash-lite")
        }.distinct()

        val toolsJson = buildToolsJsonArray(
            useCodeExecution = request.useCodeExecution,
            useWebSearch = request.useWebSearch,
            hasUrlInPrompt = hasUrlInPrompt
        )

        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            val rawAccumulated = StringBuilder()
            val nativeThinkingAccumulated = StringBuilder()
            val collectedSources = linkedMapOf<String, SourceCitation>()
            var lastRenderedContent = ""
            var lastThinkingText = ""

            // Try with full thinkingConfig first; if a specific model rejects thinkingConfig with HTTP 400, retry cleanly once without thinkingConfig.
            val attemptConfigs = listOf(true, false)
            var succeeded = false

            for (includeThinkingConfig in attemptConfigs) {
                rawAccumulated.clear()
                nativeThinkingAccumulated.clear()
                collectedSources.clear()
                lastRenderedContent = ""
                lastThinkingText = ""

                try {
                    val requestPayload = buildChatRequestJson(
                        systemText = systemText,
                        contentsArray = chatHistory,
                        capabilityMode = request.capabilityMode,
                        modelTier = request.modelTier,
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

                            val nativeThinkingNow = nativeThinkingAccumulated.toString().trim()
                            val elapsed = System.currentTimeMillis() - startMs

                            if (nativeThinkingNow.isNotEmpty()) {
                                if (hasNewThinking && nativeThinkingNow != lastThinkingText) {
                                    lastThinkingText = nativeThinkingNow
                                    emitEvent(
                                        AiStreamEvent.ThinkingChunk(
                                            thinkingText = nativeThinkingNow,
                                            elapsedMs = elapsed
                                        )
                                    )
                                }
                                if (hasNewContent) {
                                    val cleanContent = rawAccumulated.toString()
                                    if (cleanContent != lastRenderedContent) {
                                        lastRenderedContent = cleanContent
                                        emitEvent(AiStreamEvent.ContentChunk(cleanContent))
                                    }
                                }
                            } else if (hasNewContent || hasNewThinking) {
                                val parsed = splitThinkingAndContent(rawAccumulated.toString())
                                if (!parsed.thinking.isNullOrBlank() && parsed.thinking != lastThinkingText) {
                                    lastThinkingText = parsed.thinking
                                    emitEvent(
                                        AiStreamEvent.ThinkingChunk(
                                            thinkingText = parsed.thinking,
                                            elapsedMs = elapsed
                                        )
                                    )
                                }
                                if (parsed.answer.isNotEmpty() && parsed.answer != lastRenderedContent) {
                                    lastRenderedContent = parsed.answer
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
                        return
                    } else {
                        emitEvent(
                            AiStreamEvent.Failed("Aura AI returned an empty response. Please try again.")
                        )
                        return
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Aura AI stream timed out on $modelId", e)
                    emitEvent(
                        AiStreamEvent.Failed("Aura AI request timed out after 60 seconds. Please tap Retry.")
                    )
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastError = t
                    Log.e(TAG, "Aura AI streaming failed on $modelId (thinking=$includeThinkingConfig): ${t.message}", t)
                    val msgLower = (t.message ?: "").lowercase()
                    if (includeThinkingConfig && rawAccumulated.isEmpty() &&
                        (msgLower.contains("thinking") || msgLower.contains("400") || msgLower.contains("invalid_argument"))
                    ) {
                        continue // retry same model without thinkingConfig
                    }
                    break
                }
            }

            if (succeeded) return
            val hasNext = index < candidateModels.lastIndex
            val err = lastError
            if (hasNext && rawAccumulated.isEmpty() && err != null && isRecoverableModelError(err)) {
                Log.w(TAG, "Switching to next live Aura model after error on $modelId")
                continue
            } else {
                emitEvent(
                    AiStreamEvent.Failed(
                        sanitizeAuraError(err?.localizedMessage ?: err?.message, isImage = false)
                    )
                )
                return
            }
        }

        emitEvent(
            AiStreamEvent.Failed(
                sanitizeAuraError(lastError?.localizedMessage ?: lastError?.message, isImage = false)
            )
        )
    }

    private suspend fun generateOrEditImage(request: AiRequest): AiStreamEvent {
        val apiKey = try {
            requireApiKey()
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
        bodyJson: JSONObject
    ): JSONObject = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

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
        onChunk: suspend (JSONObject) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

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
        toolsArray: JSONArray?,
        includeThinking: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemText))
                )
            )
            put("contents", contentsArray)
            if (includeThinking) {
                val thinkingObj = JSONObject().apply {
                    when {
                        capabilityMode == CapabilityMode.DEEP_THINK ||
                            capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                            modelTier == AiModelTier.GEMINI_4_ARGON -> {
                            put("includeThoughts", true)
                            put("thinkingLevel", "HIGH")
                        }
                        capabilityMode == CapabilityMode.CONCISE -> {
                            put("includeThoughts", false)
                            put("thinkingLevel", "LOW")
                        }
                        else -> {
                            put("includeThoughts", true)
                            put("thinkingLevel", "MEDIUM")
                        }
                    }
                }
                put("generationConfig", JSONObject().put("thinkingConfig", thinkingObj))
            }
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
        return msg.contains("quota") ||
            msg.contains("429") ||
            msg.contains("rate") ||
            msg.contains("exceeded") ||
            msg.contains("resource_exhausted") ||
            msg.contains("billing") ||
            msg.contains("blaze") ||
            msg.contains("not found") ||
            msg.contains("404") ||
            msg.contains("limit:") ||
            msg.contains("tool") ||
            msg.contains("grounding")
    }

    private fun sanitizeAuraError(raw: String?, isImage: Boolean): String {
        val msg = (raw ?: "").lowercase()
        return when {
            msg.contains("missing_gemini_api_key") -> {
                "API Key is not set yet. Tap the Key icon at the top (or Drawer → Settings) to paste and save your API key."
            }
            msg.contains("api_key_invalid") || msg.contains("api key not valid") ||
                msg.contains("401") || msg.contains("unauthenticated") -> {
                "Invalid API Key. Open Settings → API Key to update and save a valid key."
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
                "Aura AI access denied (403). Verify your GEMINI_API_KEY permissions and try again."
            }
            msg.contains("unable to resolve host") || msg.contains("network") ||
                msg.contains("connect") || msg.contains("timeout") -> {
                "Unable to reach Aura AI servers. Check your internet connection and tap Retry."
            }
            raw.isNullOrBlank() -> "Aura AI could not complete the request. Please tap Retry."
            else -> {
                raw.replace(Regex("https?://\\S+"), "")
                    .replace(Regex("gemini[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                    .replace(Regex("generativelanguage\\.googleapis\\.com\\S*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("google", RegexOption.IGNORE_CASE), "Abhynex")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "Aura AI could not complete the request. Please tap Retry." }
            }
        }
    }

    private fun buildSystemInstruction(
        persona: PersonaEntity?,
        capabilityMode: CapabilityMode,
        useWebSearch: Boolean,
        useCodeExecution: Boolean
    ): String {
        val brandRule = """
            Your name is **Aura AI**, created and engineered by **Abhi** (founder of **Abhynex**).
            If anyone asks who created, built, owns, or made you, clearly and proudly state that **Abhi** built you at **Abhynex**.
            Never reveal or mention Gemini, Google, OpenAI, Claude, Anthropic, or any underlying model IDs; always represent yourself exclusively as **Aura AI** by **Abhynex**.

            Aura Apex Intelligence & Zero-Error Engineering Protocol:
            1. **Action-First Mastery**: Deliver complete, structured, authoritative answers immediately. If the user asks for a hard/practice problem, pick a classic challenging problem and solve it fully — never ask them to send one first. Match the user's language naturally (English, Marathi, Romanized Marathi, or Hindi).
            2. **Staff-Engineer Multi-Pass Coding**: Before finalizing any code, mentally trace types, nullability, concurrency/threads, coroutine cancellation, resource cleanup, security boundaries, and edge-case inputs. Deliver 100% complete, compile-ready code inside fenced language blocks (e.g., ```kotlin, ```python, ```ts, or ```diff for patches) with full imports and defensive error handling — NEVER use `// TODO`, ellipsis (`...`), or pseudo-code. When auditing or reviewing existing code, diagnose root causes first, then provide clean unified ` ```diff ` patches and drop-in hardened code + unit tests.
            3. **KaTeX / LaTeX Mathematics**: Format ALL equations, calculus, linear algebra, and physics cleanly with KaTeX delimiters: `${'$'}...${'$'}` inline and standalone `${'$'}${'$'}...${'$'}${'$'}` display blocks for every derivation step (`\int`, `\frac{a}{b}`, `\lim`, `\sum`, `\sqrt`, matrices), and place final answers inside `${'$'}${'$'}\boxed{...}${'$'}${'$'}` with a sanity check.
        """.trimIndent()

        val searchDirective = if (useWebSearch) {
            "\n\nLive Web Search Grounding & URL Context ON: verify recent facts, library versions, benchmarks, and official docs before answering, and prioritize verified current sources."
        } else ""

        val sandboxDirective = if (useCodeExecution) {
            "\n\nLive Python Code Execution Sandbox ON: whenever exact computation, symbolic math, numerical verification, algorithm testing, or data analysis is needed, write and execute Python in the sandbox tool to prove and double-check your result before presenting the final answer."
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
            CapabilityMode.CONCISE ->
                "Be direct, crisp, and scannable using concise bullet points, clean equations, and zero filler."
        }

        return "$brandRule$searchDirective$sandboxDirective\n\nPersona Focus: $personaPrompt\n\nMode: $modeInstruction"
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

    private fun buildUserPromptText(request: AiRequest): String {
        val snippet = request.attachmentTextSnippet
        return if (!request.attachmentIsImage && !snippet.isNullOrBlank() && request.attachmentName != null) {
            "Attached file (${request.attachmentName}):\n```\n$snippet\n```\n\n${request.prompt}"
        } else {
            request.prompt
        }
    }

    private fun encodeBitmapJpegBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun loadScaledBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = if (uri.scheme == "file" && uri.path != null) {
                BitmapFactory.decodeFile(uri.path)
            } else {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } ?: return@runCatching null

            val maxDim = 1600
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
        val startTag = "<thinking>"
        val endTag = "</thinking>"
        val startIdx = raw.indexOf(startTag)
        if (startIdx == -1) {
            return ParsedThinking(thinking = null, answer = raw, isStillInsideThinkingTag = false)
        }
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
