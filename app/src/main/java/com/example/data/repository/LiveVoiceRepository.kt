package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.example.data.local.PersonaEntity
import com.example.data.model.AuraVoiceCharacter
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AuraLiveVoice"
private const val LIVE_WS_ENDPOINT =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
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

class LiveVoiceManager(appContext: Context) {
    private val prefs = PreferencesManager(appContext)
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var voiceScope: CoroutineScope? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var micJob: Job? = null

    @SuppressLint("MissingPermission")
    suspend fun startConversation(
        voice: AuraVoiceCharacter,
        persona: PersonaEntity?,
        onStatusChanged: (LiveVoiceStatus) -> Unit
    ) {
        stopConversation()
        onStatusChanged(LiveVoiceStatus.Connecting)

        val apiKey = prefs.getEffectiveApiKey()
        if (apiKey.isBlank()) {
            onStatusChanged(
                LiveVoiceStatus.Error(
                    "API Key is not set yet. Open Settings → API Key in the app to paste and save your key."
                )
            )
            return
        }

        val personaInstruction = persona?.systemPrompt
            ?: "You are a warm, natural, conversational AI partner."
        val systemPrompt =
            "Your name is Aura AI, created by Abhi at Abhynex. If anyone asks who made or created you, proudly say Abhi built you at Abhynex. Never mention underlying model names or Google/Gemini; speak naturally and intelligently as Aura AI (${voice.displayName}, ${voice.trait} tone). $personaInstruction"

        connectCandidate(
            candidateIndex = 0,
            apiKey = apiKey,
            voice = voice,
            systemPrompt = systemPrompt,
            onStatusChanged = onStatusChanged
        )
    }

    private fun connectCandidate(
        candidateIndex: Int,
        apiKey: String,
        voice: AuraVoiceCharacter,
        systemPrompt: String,
        onStatusChanged: (LiveVoiceStatus) -> Unit
    ) {
        if (candidateIndex >= LIVE_MODEL_CANDIDATES.size) {
            onStatusChanged(LiveVoiceStatus.Error("Could not connect to Aura Live Voice. Please tap Retry."))
            return
        }
        val modelId = LIVE_MODEL_CANDIDATES[candidateIndex]
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        voiceScope = scope

        var setupCompleted = false
        val wsUrl = "$LIVE_WS_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupJson = JSONObject().put(
                    "setup",
                    JSONObject().apply {
                        put("model", "models/$modelId")
                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put("responseModalities", JSONArray().put("AUDIO"))
                                put(
                                    "speechConfig",
                                    JSONObject().put(
                                        "voiceConfig",
                                        JSONObject().put(
                                            "prebuiltVoiceConfig",
                                            JSONObject().put("voiceName", voice.apiName)
                                        )
                                    )
                                )
                            }
                        )
                        put(
                            "systemInstruction",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", systemPrompt))
                            )
                        )
                    }
                )
                webSocket.send(setupJson.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(webSocket, bytes.utf8())
            }

            private fun handleIncomingMessage(webSocket: WebSocket, rawJson: String) {
                val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
                if (!setupCompleted && json.has("setupComplete")) {
                    setupCompleted = true
                    onStatusChanged(LiveVoiceStatus.Active(voice))
                    startAudioPipeline(scope, webSocket, onStatusChanged)
                    return
                }

                val serverContent = json.optJSONObject("serverContent") ?: return
                if (serverContent.optBoolean("interrupted", false)) {
                    runCatching {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    }
                }
                val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    val b64 = inline?.optString("data").orEmpty()
                    if (b64.isNotBlank()) {
                        val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                        if (pcm != null && pcm.isNotEmpty()) {
                            runCatching { audioTrack?.write(pcm, 0, pcm.size) }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!setupCompleted && candidateIndex + 1 < LIVE_MODEL_CANDIDATES.size) {
                    cleanupAudioAndScopeOnly()
                    connectCandidate(candidateIndex + 1, apiKey, voice, systemPrompt, onStatusChanged)
                } else if (setupCompleted) {
                    cleanupAudioAndScopeOnly()
                    onStatusChanged(LiveVoiceStatus.Idle)
                } else {
                    cleanupAudioAndScopeOnly()
                    onStatusChanged(LiveVoiceStatus.Error(sanitizeLiveError(reason)))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Aura Live Voice failure on $modelId: ${t.message}", t)
                if (!setupCompleted && candidateIndex + 1 < LIVE_MODEL_CANDIDATES.size) {
                    cleanupAudioAndScopeOnly()
                    connectCandidate(candidateIndex + 1, apiKey, voice, systemPrompt, onStatusChanged)
                } else {
                    cleanupAudioAndScopeOnly()
                    onStatusChanged(LiveVoiceStatus.Error(sanitizeLiveError(t.localizedMessage ?: t.message)))
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline(
        scope: CoroutineScope,
        webSocket: WebSocket,
        onStatusChanged: (LiveVoiceStatus) -> Unit
    ) {
        try {
            val outRate = 24000
            val outMinBuf = AudioTrack.getMinBufferSize(
                outRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(outMinBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track

            val inRate = 16000
            val inMinBuf = AudioRecord.getMinBufferSize(
                inRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                inRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                inMinBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onStatusChanged(LiveVoiceStatus.Error("Unable to access device microphone."))
                return
            }
            record.startRecording()
            audioRecord = record

            micJob = scope.launch {
                val buffer = ByteArray(1600)
                try {
                    while (isActive) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                            val chunkJson = JSONObject().put(
                                "realtimeInput",
                                JSONObject().put(
                                    "mediaChunks",
                                    JSONArray().put(
                                        JSONObject().apply {
                                            put("mimeType", "audio/pcm;rate=16000")
                                            put("data", b64)
                                        }
                                    )
                                )
                            )
                            webSocket.send(chunkJson.toString())
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    onStatusChanged(LiveVoiceStatus.Error("Microphone permission is required for Aura Live Voice."))
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio capture loop ended: ${t.message}")
                }
            }
        } catch (e: SecurityException) {
            onStatusChanged(LiveVoiceStatus.Error("Microphone permission is required for Aura Live Voice."))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed starting audio pipeline", t)
            onStatusChanged(LiveVoiceStatus.Error("Unable to initialize voice audio stream."))
        }
    }

    private fun sanitizeLiveError(raw: String?): String {
        val msg = (raw ?: "").lowercase()
        return when {
            msg.contains("401") || msg.contains("api_key") || msg.contains("unauthenticated") ->
                "Invalid API key configured. Update GEMINI_API_KEY and rebuild."
            msg.contains("quota") || msg.contains("429") ->
                "Aura Live Voice daily capacity has been reached. Please try again later."
            msg.contains("403") ->
                "Aura Live Voice access denied (403). Check your API key and network."
            else ->
                (raw ?: "").replace(Regex("https?://\\S+"), "")
                    .replace(Regex("gemini[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura Live Voice")
                    .replace(Regex("google", RegexOption.IGNORE_CASE), "Abhynex")
                    .trim()
                    .ifBlank { "Could not connect to Aura Live Voice. Please tap Retry." }
        }
    }

    private fun cleanupAudioAndScopeOnly() {
        micJob?.cancel()
        micJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        runCatching { voiceScope?.cancel() }
        voiceScope = null
    }

    suspend fun stopConversation() {
        val ws = activeWebSocket
        activeWebSocket = null
        runCatching { ws?.close(1000, "User ended session") }
        cleanupAudioAndScopeOnly()
    }
}
