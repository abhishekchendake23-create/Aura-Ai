package com.example.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import com.example.data.local.PersonaEntity
import com.example.data.model.AuraVoiceCharacter
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AuraLiveVoice"
private val LIVE_MODEL_CANDIDATES = listOf(
    "gemini-3.1-flash-live-preview",
    "gemini-2.5-flash-native-audio-preview-12-2025"
)

sealed interface LiveVoiceStatus {
    data object Idle : LiveVoiceStatus
    data object Connecting : LiveVoiceStatus
    data class Active(val voice: AuraVoiceCharacter) : LiveVoiceStatus
    data class Error(val message: String) : LiveVoiceStatus
}

@OptIn(PublicPreviewAPI::class)
class LiveVoiceManager {
    private var activeSession: LiveSession? = null

    @SuppressLint("MissingPermission")
    suspend fun startConversation(
        voice: AuraVoiceCharacter,
        persona: PersonaEntity?,
        onStatusChanged: (LiveVoiceStatus) -> Unit
    ) {
        stopConversation()
        onStatusChanged(LiveVoiceStatus.Connecting)

        val personaInstruction = persona?.systemPrompt
            ?: "You are a warm, natural, conversational AI partner."
        val systemPrompt =
            "Your name is Aura AI, created by Abhi at Abhynex. If anyone asks who made or created you, proudly say Abhi built you at Abhynex. Never mention underlying model names or Google/Gemini; speak naturally and intelligently as Aura AI (${voice.displayName}, ${voice.trait} tone). $personaInstruction"

        var lastError: Throwable? = null

        for ((index, modelId) in LIVE_MODEL_CANDIDATES.withIndex()) {
            try {
                val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                    modelName = modelId,
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(voice = Voice(voice.apiName))
                    },
                    systemInstruction = content { text(systemPrompt) }
                )

                val session = withContext(Dispatchers.IO) {
                    liveModel.connect()
                }
                activeSession = session
                onStatusChanged(LiveVoiceStatus.Active(voice))
                session.startAudioConversation()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission missing for live voice", e)
                onStatusChanged(
                    LiveVoiceStatus.Error("Microphone permission is required for Aura Live Voice.")
                )
                return
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Failed to start Aura Live Voice on $modelId: ${t.message}", t)
                stopConversation()
                if (index < LIVE_MODEL_CANDIDATES.lastIndex) {
                    continue
                }
            }
        }

        val raw = lastError?.localizedMessage ?: lastError?.message ?: ""
        val sanitized = when {
            raw.contains("quota", ignoreCase = true) || raw.contains("429") ->
                "Aura Live Voice daily capacity has been reached. Please try again later."
            raw.contains("403") || raw.contains("app check", ignoreCase = true) ->
                "Aura Live Voice security check failed. Please check internet connection and retry."
            else ->
                raw.replace(Regex("https?://\\S+"), "")
                    .replace(Regex("gemini[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura Live Voice")
                    .replace(Regex("google", RegexOption.IGNORE_CASE), "Abhynex")
                    .trim()
                    .ifBlank { "Could not connect to Aura Live Voice. Please tap Retry." }
        }
        onStatusChanged(LiveVoiceStatus.Error(sanitized))
    }

    suspend fun stopConversation() {
        val session = activeSession ?: return
        activeSession = null
        runCatching {
            session.stopAudioConversation()
        }.onFailure { Log.w(TAG, "stopAudioConversation warning: ${it.message}") }
        runCatching {
            withContext(Dispatchers.IO) {
                session.close()
            }
        }.onFailure { Log.w(TAG, "session.close warning: ${it.message}") }
    }
}
