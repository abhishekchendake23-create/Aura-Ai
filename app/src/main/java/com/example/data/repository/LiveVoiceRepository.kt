package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.data.local.PersonaEntity
import com.example.data.model.AppLanguage
import com.example.data.model.AuraVoiceCharacter
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import kotlin.math.sqrt

private const val TAG = "AuraLiveVoice"
private const val LIVE_WS_ENDPOINT =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

private val LIVE_MODEL_CANDIDATES = listOf(
    "gemini-2.5-flash-native-audio-preview-12-2025",
    "gemini-live-2.5-flash-native-audio",
    "gemini-3.1-flash-live-preview",
    "gemini-2.0-flash-live-001",
    "gemini-2.0-flash-exp"
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
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var isMicMuted: Boolean = false
    @Volatile
    private var sessionToken: Long = 0L

    private var voiceScope: CoroutineScope? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var playbackChannel: Channel<ByteArray>? = null
    private var micJob: Job? = null
    private var playbackJob: Job? = null

    fun setMuted(muted: Boolean) {
        isMicMuted = muted
    }

    fun sendCameraFrame(jpegBytes: ByteArray): Boolean {
        val ws = activeWebSocket ?: return false
        if (jpegBytes.isEmpty()) return false
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val chunkJson = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(
                    JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", b64)
                    }
                )
            )
        )
        return ws.send(chunkJson.toString())
    }

    @SuppressLint("MissingPermission")
    suspend fun startConversation(
        voice: AuraVoiceCharacter,
        persona: PersonaEntity?,
        language: AppLanguage = AppLanguage.AUTO,
        onStatusChanged: (LiveVoiceStatus) -> Unit,
        onTranscriptUpdate: (String) -> Unit = {},
        onAudioLevel: (Float) -> Unit = {}
    ) {
        stopConversation()
        val myToken = ++sessionToken
        isMicMuted = false
        onStatusChanged(LiveVoiceStatus.Connecting)
        onTranscriptUpdate("")
        onAudioLevel(0f)

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
            ?: "You are a warm, affectionate, witty, and caring conversational partner who speaks like a close companion."

        val systemPrompt = buildString {
            append("Your name is Aura AI, created by Abhi at Abhynex. ")
            append("If anyone asks who built or created you, warmly say Abhi built you at Abhynex. ")
            append("Never mention underlying model names, Google, or Gemini; you are exclusively Aura AI by Abhynex. ")
            append("Active voice character: ${voice.displayName} (${voice.trait} tone — ${voice.description}). ")
            append("If live camera frames are streamed to you, observe what is shown clearly and describe or help solve it in natural speech. ")
            append("Keep spoken replies warm, expressive, natural, and conversational — avoid stiff robotic phrasing. ")
            append(language.promptInstruction)
            append(" ")
            append(personaInstruction)
        }

        connectCandidate(
            token = myToken,
            candidateIndex = 0,
            apiKey = apiKey,
            voice = voice,
            systemPrompt = systemPrompt,
            includeTranscriptions = true,
            onStatusChanged = onStatusChanged,
            onTranscriptUpdate = onTranscriptUpdate,
            onAudioLevel = onAudioLevel
        )
    }

    private fun connectCandidate(
        token: Long,
        candidateIndex: Int,
        apiKey: String,
        voice: AuraVoiceCharacter,
        systemPrompt: String,
        includeTranscriptions: Boolean,
        onStatusChanged: (LiveVoiceStatus) -> Unit,
        onTranscriptUpdate: (String) -> Unit,
        onAudioLevel: (Float) -> Unit
    ) {
        if (token != sessionToken) return
        if (candidateIndex >= LIVE_MODEL_CANDIDATES.size) {
            onStatusChanged(LiveVoiceStatus.Error("Could not connect to Aura Live Voice. Please tap Retry."))
            return
        }

        val modelId = LIVE_MODEL_CANDIDATES[candidateIndex]
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        voiceScope = scope

        var setupCompleted = false
        var terminalAuthError: String? = null
        val transcriptBuffer = StringBuilder()
        val wsUrl = "$LIVE_WS_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (token != sessionToken) {
                    webSocket.close(1000, "Stale session")
                    return
                }
                val setupBody = JSONObject().apply {
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
                    if (includeTranscriptions) {
                        put("inputAudioTranscription", JSONObject())
                        put("outputAudioTranscription", JSONObject())
                    }
                    put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", systemPrompt))
                        )
                    )
                }
                val setupJson = JSONObject().put("setup", setupBody)
                webSocket.send(setupJson.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(webSocket, bytes.utf8())
            }

            private fun handleIncomingMessage(webSocket: WebSocket, rawJson: String) {
                if (token != sessionToken) return
                val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return

                json.optJSONObject("error")?.let { err ->
                    val code = err.optInt("code")
                    val msg = err.optString("message")
                    Log.e(TAG, "Server error JSON on $modelId: $code $msg")
                    if (code == 401 || code == 403 || msg.contains("API key", ignoreCase = true)) {
                        terminalAuthError = "$code $msg"
                    }
                    return
                }

                if (!setupCompleted && json.has("setupComplete")) {
                    setupCompleted = true
                    onStatusChanged(LiveVoiceStatus.Active(voice))
                    startAudioPipeline(scope, webSocket, onStatusChanged, onAudioLevel)
                    return
                }

                val serverContent = json.optJSONObject("serverContent") ?: return
                if (serverContent.optBoolean("interrupted", false)) {
                    drainPlaybackQueue()
                    runCatching {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    }
                    onAudioLevel(0f)
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    onAudioLevel(0f)
                }

                val outText = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
                if (outText.isNotEmpty()) {
                    if (transcriptBuffer.length > 420) {
                        transcriptBuffer.delete(0, transcriptBuffer.length - 260)
                    }
                    transcriptBuffer.append(outText)
                    onTranscriptUpdate(transcriptBuffer.toString().trim())
                }

                val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    val b64 = inline?.optString("data").orEmpty()
                    if (b64.isNotBlank()) {
                        val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                        if (pcm != null && pcm.isNotEmpty()) {
                            onAudioLevel(computeNormalizedLevel(pcm, pcm.size))
                            playbackChannel?.trySend(pcm)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (token != sessionToken) return
                Log.w(TAG, "Live WS closing ($modelId): code=$code reason=$reason setup=$setupCompleted")
                handleDisconnect(reason.ifBlank { terminalAuthError ?: "HTTP $code" }, setupCompleted)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val resBody = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                val resCode = response?.code ?: 0
                Log.e(TAG, "Aura Live Voice failure on $modelId (http=$resCode): ${t.message} | $resBody", t)
                if (token != sessionToken) return
                val combinedErr = terminalAuthError ?: listOfNotNull(
                    if (resCode > 0) "HTTP $resCode" else null,
                    resBody.takeIf { it.isNotBlank() },
                    t.localizedMessage ?: t.message
                ).joinToString(" · ")
                handleDisconnect(combinedErr, setupCompleted)
            }

            private fun handleDisconnect(errText: String, wasSetupCompleted: Boolean) {
                if (token != sessionToken) return
                cleanupAudioAndScopeOnly()
                val errLower = errText.lowercase()
                val isAuthFatal = errLower.contains("401") || errLower.contains("403") ||
                    errLower.contains("api_key") || errLower.contains("api key not valid")

                if (!wasSetupCompleted && !isAuthFatal) {
                    // If transcription fields triggered a 400 invalid argument on this candidate, retry same model without them
                    if (includeTranscriptions && (errLower.contains("400") || errLower.contains("invalid") || errLower.contains("transcription"))) {
                        connectCandidate(
                            token = token,
                            candidateIndex = candidateIndex,
                            apiKey = apiKey,
                            voice = voice,
                            systemPrompt = systemPrompt,
                            includeTranscriptions = false,
                            onStatusChanged = onStatusChanged,
                            onTranscriptUpdate = onTranscriptUpdate,
                            onAudioLevel = onAudioLevel
                        )
                        return
                    }
                    if (candidateIndex + 1 < LIVE_MODEL_CANDIDATES.size) {
                        connectCandidate(
                            token = token,
                            candidateIndex = candidateIndex + 1,
                            apiKey = apiKey,
                            voice = voice,
                            systemPrompt = systemPrompt,
                            includeTranscriptions = true,
                            onStatusChanged = onStatusChanged,
                            onTranscriptUpdate = onTranscriptUpdate,
                            onAudioLevel = onAudioLevel
                        )
                        return
                    }
                }

                if (wasSetupCompleted) {
                    onStatusChanged(LiveVoiceStatus.Idle)
                } else {
                    onStatusChanged(LiveVoiceStatus.Error(sanitizeLiveError(errText)))
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline(
        scope: CoroutineScope,
        webSocket: WebSocket,
        onStatusChanged: (LiveVoiceStatus) -> Unit,
        onAudioLevel: (Float) -> Unit
    ) {
        try {
            val outRate = 24000
            val outMinBuf = AudioTrack.getMinBufferSize(
                outRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
                .setBufferSizeInBytes(outMinBuf * 3)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = trackBuilder.build()
            track.play()
            audioTrack = track

            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            playbackChannel = channel
            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    for (pcm in channel) {
                        if (!isActive) break
                        track.write(pcm, 0, pcm.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio playback loop ended: ${t.message}")
                }
            }

            val inRate = 16000
            val inMinBuf = AudioRecord.getMinBufferSize(
                inRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val record = createBestAudioRecord(inRate, inMinBuf * 2)
            if (record == null) {
                onStatusChanged(LiveVoiceStatus.Error("Unable to access device microphone."))
                return
            }

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching {
                    AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                }.getOrNull()
            }

            record.startRecording()
            audioRecord = record

            micJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(1600) // ~50ms at 16kHz 16-bit mono
                try {
                    while (isActive) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            if (isMicMuted) {
                                onAudioLevel(0f)
                                delay(25)
                                continue
                            }
                            val level = computeNormalizedLevel(buffer, read)
                            if (level > 0.08f) {
                                onAudioLevel(level)
                            }
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

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate.release() }
        }
        return null
    }

    private fun computeNormalizedLevel(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val norm = sample / 32768.0
            sumSq += norm * norm
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples).toFloat()
        return (rms * 3.8f).coerceIn(0f, 1f)
    }

    private fun drainPlaybackQueue() {
        val ch = playbackChannel ?: return
        while (ch.tryReceive().isSuccess) {
            // discard queued PCM on barge-in
        }
    }

    private fun sanitizeLiveError(raw: String?): String {
        val msg = (raw ?: "").lowercase()
        return when {
            msg.contains("401") || msg.contains("api_key") || msg.contains("api key not valid") || msg.contains("unauthenticated") ->
                "Invalid API Key. Open Settings → API Key inside the app to update and save a valid key."
            msg.contains("quota") || msg.contains("429") || msg.contains("resource_exhausted") ->
                "Aura Live Voice capacity reached. Please retry in a moment."
            msg.contains("503") || msg.contains("unavailable") ->
                "Aura Live Voice servers are temporarily busy. Please tap Retry."
            msg.contains("403") ->
                "Aura Live Voice access denied (403). Check your API key permissions in Settings."
            msg.contains("unable to resolve host") || msg.contains("connect") || msg.contains("timeout") ->
                "Check your internet connection and tap Retry."
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
        playbackJob?.cancel()
        playbackJob = null
        playbackChannel?.close()
        playbackChannel = null
        runCatching {
            echoCanceler?.release()
        }
        echoCanceler = null
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
        sessionToken++
        val ws = activeWebSocket
        activeWebSocket = null
        runCatching { ws?.close(1000, "User ended session") }
        cleanupAudioAndScopeOnly()
    }
}
