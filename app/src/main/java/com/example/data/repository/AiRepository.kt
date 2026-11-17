package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import com.example.data.model.AiModelTier
import com.example.data.model.CapabilityMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.CodeExecutionResultPart
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ExecutableCodePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "AuraAiRepository"
private const val INFERENCE_TIMEOUT_MS = 60_000L

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

class FirebaseAiRepository(private val appContext: Context) : AiRepository {

    override suspend fun enhancePrompt(rawPrompt: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = rawPrompt.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a prompt to enhance."))
        }
        val candidates = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
        var lastErr: Throwable? = null
        for (modelId in candidates) {
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = modelId,
                    systemInstruction = content {
                        text(
                            "You are a frontier prompt engineer for Aura AI (Abhynex). Rewrite the user's rough prompt into a crystal-clear, high-leverage, structured prompt specifying objective, constraints, edge cases, and desired output format. Keep it concise (max 4-6 lines), preserve the user's original intent and language style, and return ONLY the rewritten prompt text with no quotes or preamble."
                        )
                    }
                )
                val response = withTimeout(15_000L) {
                    model.generateContent("Rewrite and sharpen this prompt:\n$trimmed")
                }
                val enhanced = response.text?.trim().orEmpty()
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
        val startMs = System.currentTimeMillis()
        val systemText = buildSystemInstruction(
            persona = request.persona,
            capabilityMode = request.capabilityMode,
            useWebSearch = request.useWebSearch,
            useCodeExecution = request.useCodeExecution
        )
        val chatHistory = buildHistoryContents(request.history)
        val attachedBitmap = if (request.attachmentIsImage && request.attachmentUri != null) {
            loadScaledBitmap(Uri.parse(request.attachmentUri))
        } else null

        val promptText = buildUserPromptText(request)
        val hasUrlInPrompt = Regex("https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(promptText)
        val userContent = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(promptText)
        }

        val candidateModels = buildList {
            add(request.modelTier.id)
            add("gemini-3.8-flash")
            add("gemini-3.5-flash-lite")
            add("gemini-3.1-flash-lite")
        }.distinct()

        val activeTools = buildList {
            when {
                request.useCodeExecution -> add(Tool.codeExecution())
                request.useWebSearch && hasUrlInPrompt -> {
                    add(Tool.googleSearch())
                    add(Tool.urlContext())
                }
                request.useWebSearch -> add(Tool.googleSearch())
                hasUrlInPrompt -> add(Tool.urlContext())
            }
        }

        val genConfig = generationConfig {
            thinkingConfig = thinkingConfig {
                when {
                    request.capabilityMode == CapabilityMode.DEEP_THINK ||
                        request.capabilityMode == CapabilityMode.CODE_ARCHITECT ||
                        request.modelTier == AiModelTier.GEMINI_4_ARGON -> {
                        includeThoughts = true
                        thinkingLevel = ThinkingLevel.HIGH
                    }
                    request.capabilityMode == CapabilityMode.CONCISE -> {
                        includeThoughts = false
                        thinkingLevel = ThinkingLevel.LOW
                    }
                    else -> {
                        includeThoughts = true
                        thinkingLevel = ThinkingLevel.MEDIUM
                    }
                }
            }
        }

        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            val rawAccumulated = StringBuilder()
            val nativeThinkingAccumulated = StringBuilder()
            val collectedSources = mutableListOf<SourceCitation>()
            try {
                val aiBackend = Firebase.ai(backend = GenerativeBackend.googleAI())
                val model = if (activeTools.isNotEmpty()) {
                    aiBackend.generativeModel(
                        modelName = modelId,
                        generationConfig = genConfig,
                        systemInstruction = content { text(systemText) },
                        tools = activeTools
                    )
                } else {
                    aiBackend.generativeModel(
                        modelName = modelId,
                        generationConfig = genConfig,
                        systemInstruction = content { text(systemText) }
                    )
                }

                Log.d(
                    TAG,
                    "Starting stream request: model=$modelId, webSearch=${request.useWebSearch}, codeExec=${request.useCodeExecution}, urlCtx=$hasUrlInPrompt, history=${chatHistory.size}"
                )
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    val stream = if (chatHistory.isEmpty()) {
                        model.generateContentStream(userContent)
                    } else {
                        val chat = model.startChat(history = chatHistory)
                        chat.sendMessageStream(userContent)
                    }
                    stream.collect { chunk ->
                        chunk.candidates.firstOrNull()?.groundingMetadata?.groundingChunks?.forEach { gChunk ->
                            val web = gChunk.web
                            val uri = web?.uri?.trim().orEmpty()
                            if (uri.isNotBlank() && collectedSources.none { it.uri == uri }) {
                                val title = web?.title?.trim().orEmpty().ifBlank {
                                    Uri.parse(uri).host?.removePrefix("www.") ?: "Web Source"
                                }
                                collectedSources.add(SourceCitation(title = title, uri = uri))
                            }
                        }

                        val thoughtChunk = chunk.thoughtSummary.orEmpty()
                        if (thoughtChunk.isNotEmpty()) {
                            nativeThinkingAccumulated.append(thoughtChunk)
                            emitEvent(
                                AiStreamEvent.ThinkingChunk(
                                    thinkingText = nativeThinkingAccumulated.toString().trim(),
                                    elapsedMs = System.currentTimeMillis() - startMs
                                )
                            )
                        }

                        val chunkBuffer = StringBuilder()
                        val parts = chunk.candidates.firstOrNull()?.content?.parts.orEmpty()
                        if (parts.isNotEmpty()) {
                            parts.forEach { part ->
                                when (part) {
                                    is ExecutableCodePart -> {
                                        val lang = part.language.lowercase().ifBlank { "python" }
                                        val codeBody = part.code.trimEnd()
                                        if (codeBody.isNotBlank()) {
                                            chunkBuffer.append("\n\n```$lang\n$codeBody\n```\n\n")
                                        }
                                    }
                                    is CodeExecutionResultPart -> {
                                        val out = part.output.orEmpty().trim()
                                        val outcome = part.outcome.toString()
                                        if (out.isNotBlank()) {
                                            chunkBuffer.append("\n```sandbox-output\n[$outcome]\n$out\n```\n\n")
                                        }
                                    }
                                    is TextPart -> {
                                        chunkBuffer.append(part.text)
                                    }
                                }
                            }
                        } else {
                            chunkBuffer.append(chunk.text.orEmpty())
                        }

                        val piece = chunkBuffer.toString()
                        if (piece.isNotEmpty()) {
                            rawAccumulated.append(piece)
                            val parsed = splitThinkingAndContent(rawAccumulated.toString())
                            val combinedThinking = listOfNotNull(
                                nativeThinkingAccumulated.toString().trim().ifBlank { null },
                                parsed.thinking?.ifBlank { null }
                            ).joinToString("\n\n").ifBlank { null }

                            if (combinedThinking != null) {
                                emitEvent(
                                    AiStreamEvent.ThinkingChunk(
                                        thinkingText = combinedThinking,
                                        elapsedMs = System.currentTimeMillis() - startMs
                                    )
                                )
                            }
                            if (parsed.answer.isNotEmpty() || !parsed.isStillInsideThinkingTag) {
                                emitEvent(AiStreamEvent.ContentChunk(parsed.answer))
                            }
                        }
                    }
                }

                val finalRaw = rawAccumulated.toString().trim()
                if (finalRaw.isBlank()) {
                    emitEvent(
                        AiStreamEvent.Failed(
                            "Aura AI returned an empty response. Please try again."
                        )
                    )
                } else {
                    val parsed = splitThinkingAndContent(finalRaw)
                    val combinedThinking = listOfNotNull(
                        nativeThinkingAccumulated.toString().trim().ifBlank { null },
                        parsed.thinking?.ifBlank { null }
                    ).joinToString("\n\n").ifBlank { null }
                    emitEvent(
                        AiStreamEvent.Completed(
                            fullText = parsed.answer.ifBlank { finalRaw },
                            thinkingText = combinedThinking,
                            thinkingDurationMs = if (combinedThinking != null) {
                                (System.currentTimeMillis() - startMs).coerceAtLeast(300L)
                            } else 0L,
                            sources = collectedSources.toList()
                        )
                    )
                }
                return
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Streaming timed out on $modelId", e)
                emitEvent(
                    AiStreamEvent.Failed(
                        "Aura AI request timed out after 60 seconds. Please retry."
                    )
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Aura AI streaming failed on $modelId: ${t.message}", t)
                val hasNext = index < candidateModels.lastIndex
                if (hasNext && rawAccumulated.isEmpty() && isRecoverableModelError(t)) {
                    Log.w(TAG, "Switching to next live Aura model after quota/availability error on $modelId")
                    continue
                }
                emitEvent(
                    AiStreamEvent.Failed(
                        sanitizeAuraError(t.localizedMessage ?: t.message, isImage = false)
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
                val config = generationConfig {
                    responseModalities = listOf(ResponseModality.IMAGE)
                }
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = modelId,
                    generationConfig = config
                )

                val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                    if (attachedBitmap != null) {
                        model.generateContent(
                            content {
                                image(attachedBitmap)
                                text(request.prompt)
                            }
                        )
                    } else {
                        model.generateContent(request.prompt)
                    }
                }

                val imagePart = response.candidates.firstOrNull()
                    ?.content?.parts?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()

                val bitmap = imagePart?.image
                return if (bitmap == null) {
                    AiStreamEvent.Failed(
                        "Aura AI could not generate an image for this prompt. Please try again."
                    )
                } else {
                    val savedPath = saveBitmapToInternalStorage(bitmap)
                    val caption = if (attachedBitmap != null) {
                        "Edited image by **Aura AI · Abhynex**."
                    } else {
                        "Created by **Aura AI · Abhynex**."
                    }
                    AiStreamEvent.Completed(
                        fullText = caption,
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
            msg.contains("quota") || msg.contains("429") || msg.contains("rate") ||
                msg.contains("exceeded") || msg.contains("resource_exhausted") -> {
                if (isImage) {
                    "Aura Imagine capacity is currently limited or requires an upgraded backend plan. Please retry shortly."
                } else {
                    "Aura AI daily request capacity has been reached. Please wait a moment and tap Retry."
                }
            }
            msg.contains("billing") || msg.contains("blaze") -> {
                "This Aura AI mode requires an upgraded Firebase Blaze plan on the backend project."
            }
            msg.contains("403") || msg.contains("app check") || msg.contains("permission") ||
                msg.contains("attestation") -> {
                "Aura AI security verification failed. Ensure internet access and reinstall the latest build."
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

    private fun buildHistoryContents(history: List<MessageEntity>): List<Content> {
        val validMessages = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(14)

        val alternating = mutableListOf<Content>()
        var expectRole = "user"
        for (msg in validMessages) {
            val role = if (msg.role == "user") "user" else "model"
            if (role == expectRole) {
                alternating.add(content(role) { text(msg.content) })
                expectRole = if (expectRole == "user") "model" else "user"
            }
        }
        // History passed to startChat must end with a "model" turn because the new message is "user".
        if (alternating.lastOrNull()?.role == "user") {
            alternating.removeAt(alternating.lastIndex)
        }
        return alternating
    }

    private fun buildUserPromptText(request: AiRequest): String {
        val snippet = request.attachmentTextSnippet
        return if (!request.attachmentIsImage && !snippet.isNullOrBlank() && request.attachmentName != null) {
            "Attached file (${request.attachmentName}):\n```\n$snippet\n```\n\n${request.prompt}"
        } else {
            request.prompt
        }
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
