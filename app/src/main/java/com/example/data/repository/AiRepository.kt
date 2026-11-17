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
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "FirebaseAiRepository"
private const val INFERENCE_TIMEOUT_MS = 60_000L

data class AiRequest(
    val prompt: String,
    val modelTier: AiModelTier,
    val capabilityMode: CapabilityMode,
    val persona: PersonaEntity?,
    val history: List<MessageEntity>,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val attachmentTextSnippet: String? = null
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
        val generatedImagePath: String? = null
    ) : AiStreamEvent

    data class Failed(
        val errorMessage: String
    ) : AiStreamEvent
}

interface AiRepository {
    fun streamResponse(request: AiRequest): Flow<AiStreamEvent>
}

class FirebaseAiRepository(private val appContext: Context) : AiRepository {

    override fun streamResponse(request: AiRequest): Flow<AiStreamEvent> = flow {
        if (request.modelTier.isImageModel) {
            generateOrEditImage(request)
                .let { emit(it) }
        } else {
            streamChatOrVision(request) { event -> emit(event) }
        }
    }

    private suspend fun streamChatOrVision(
        request: AiRequest,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val systemText = buildSystemInstruction(request.persona, request.capabilityMode)
        val model = Firebase.ai.generativeModel(
            modelName = request.modelTier.id,
            systemInstruction = content { text(systemText) }
        )

        val chatHistory = buildHistoryContents(request.history)
        val attachedBitmap = if (request.attachmentIsImage && request.attachmentUri != null) {
            loadScaledBitmap(Uri.parse(request.attachmentUri))
        } else null

        val promptText = buildUserPromptText(request)
        val userContent = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(promptText)
        }

        val startMs = System.currentTimeMillis()
        val rawAccumulated = StringBuilder()

        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                val chat = model.startChat(history = chatHistory)
                chat.sendMessageStream(userContent).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        rawAccumulated.append(chunkText)
                        val parsed = splitThinkingAndContent(rawAccumulated.toString())
                        if (parsed.thinking != null) {
                            emitEvent(
                                AiStreamEvent.ThinkingChunk(
                                    thinkingText = parsed.thinking,
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
                        "${request.modelTier.displayName} returned an empty response. Please try again."
                    )
                )
            } else {
                val parsed = splitThinkingAndContent(finalRaw)
                emitEvent(
                    AiStreamEvent.Completed(
                        fullText = parsed.answer.ifBlank { finalRaw },
                        thinkingText = parsed.thinking,
                        thinkingDurationMs = if (parsed.thinking != null) {
                            (System.currentTimeMillis() - startMs).coerceAtLeast(300L)
                        } else 0L
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Streaming timed out for ${request.modelTier.id}", e)
            emitEvent(
                AiStreamEvent.Failed(
                    "Request to ${request.modelTier.displayName} timed out after 60 seconds. Please retry."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed for ${request.modelTier.id}", e)
            emitEvent(
                AiStreamEvent.Failed(
                    e.localizedMessage ?: "Failed to generate response with ${request.modelTier.displayName}."
                )
            )
        }
    }

    private suspend fun generateOrEditImage(request: AiRequest): AiStreamEvent {
        return try {
            val config = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
            }
            val model = Firebase.ai.generativeModel(
                modelName = request.modelTier.id,
                generationConfig = config
            )

            val attachedBitmap = if (request.attachmentIsImage && request.attachmentUri != null) {
                loadScaledBitmap(Uri.parse(request.attachmentUri))
            } else null

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
            if (bitmap == null) {
                AiStreamEvent.Failed(
                    "${request.modelTier.displayName} did not return an image. Check prompt or billing plan."
                )
            } else {
                val savedPath = saveBitmapToInternalStorage(bitmap)
                val caption = if (attachedBitmap != null) {
                    "Edited image using **${request.modelTier.displayName}**."
                } else {
                    "Generated image with **${request.modelTier.displayName}**."
                }
                AiStreamEvent.Completed(
                    fullText = caption,
                    thinkingText = null,
                    thinkingDurationMs = 0L,
                    generatedImagePath = savedPath
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Image generation timed out for ${request.modelTier.id}", e)
            AiStreamEvent.Failed("Image generation timed out after 60 seconds. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image generation failed for ${request.modelTier.id}", e)
            AiStreamEvent.Failed(
                e.localizedMessage ?: "Image generation failed on ${request.modelTier.displayName}."
            )
        }
    }

    private fun buildSystemInstruction(
        persona: PersonaEntity?,
        capabilityMode: CapabilityMode
    ): String {
        val personaPrompt = persona?.systemPrompt
            ?: "You are Gemini, a helpful, accurate, and thoughtful AI assistant."

        val modeInstruction = when (capabilityMode) {
            CapabilityMode.STANDARD ->
                "Provide well-structured Markdown answers tailored to the user's language and intent."
            CapabilityMode.DEEP_THINK ->
                "Before giving your final answer, write concise step-by-step reasoning inside <thinking>...</thinking> tags, then provide your complete Markdown response after </thinking>."
            CapabilityMode.CODE_ARCHITECT ->
                "Focus on clean, production-grade architecture, edge cases, and idiomatic fenced code blocks."
            CapabilityMode.CONCISE ->
                "Be direct, crisp, and scannable using concise bullet points without unnecessary filler."
        }

        return "$personaPrompt\n\n$modeInstruction"
    }

    private fun buildHistoryContents(history: List<MessageEntity>): List<Content> {
        return history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(14)
            .map { msg ->
                val role = if (msg.role == "user") "user" else "model"
                content(role) { text(msg.content) }
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
            val file = File(dir, "gemini_${UUID.randomUUID()}.png")
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
