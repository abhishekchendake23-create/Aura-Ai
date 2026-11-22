package com.example.data.repository

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import com.example.data.model.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
private const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"

class AudioRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): Result<File> {
        return runCatching {
            stopRecordingSilently()
            val dir = File(context.cacheDir, "whisper_tmp").apply {
                if (!exists()) mkdirs()
            }
            val file = File(dir, "aura_voice_${UUID.randomUUID()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            outputFile = file
            file
        }
    }

    fun stopAndGetUri(): Uri? {
        val file = outputFile
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            if (file != null && file.exists() && file.length() > 0L) {
                Uri.fromFile(file)
            } else {
                null
            }
        } catch (_: Throwable) {
            stopRecordingSilently()
            null
        }
    }

    fun cancel() {
        stopRecordingSilently()
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun stopRecordingSilently() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }
}

class GroqWhisperClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(
        audioUri: Uri,
        groqApiKey: String,
        language: AppLanguage = AppLanguage.AUTO
    ): Result<String> = withContext(Dispatchers.IO) {
        if (groqApiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Add your Groq Fast Engine Key in Settings → API Keys to transcribe audio.")
            )
        }

        val tempFile = copyUriToTempFile(audioUri)
            ?: return@withContext Result.failure(IOException("Unable to read selected audio file."))

        try {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
                )
                .addFormDataPart("model", GROQ_WHISPER_MODEL)
                .addFormDataPart("response_format", "json")

            when (language) {
                AppLanguage.MARATHI -> multipartBuilder.addFormDataPart("language", "mr")
                AppLanguage.HINDI -> multipartBuilder.addFormDataPart("language", "hi")
                AppLanguage.ENGLISH -> multipartBuilder.addFormDataPart("language", "en")
                AppLanguage.AUTO -> Unit
            }

            val request = Request.Builder()
                .url(GROQ_WHISPER_URL)
                .header("Authorization", "Bearer ${groqApiKey.trim()}")
                .post(multipartBuilder.build())
                .build()

            val responseJson = executeRequest(request)
            val transcript = responseJson.optString("text").trim()
            if (transcript.isBlank()) {
                Result.failure(IOException("No speech detected in the audio recording."))
            } else {
                Result.success(transcript)
            }
        } catch (t: Throwable) {
            val msg = (t.message ?: "").lowercase()
            val friendly = when {
                msg.contains("401") || msg.contains("invalid_api_key") ->
                    "Invalid Groq key. Please check Settings → Fast Engine Key (Groq)."
                msg.contains("429") || msg.contains("rate") ->
                    "Audio transcription rate limit reached. Try again in a moment."
                else -> "Could not transcribe audio. Check your connection and try again."
            }
            Result.failure(IOException(friendly))
        } finally {
            if (tempFile.parentFile?.name == "whisper_tmp_upload") {
                runCatching { tempFile.delete() }
            }
        }
    }

    private suspend fun executeRequest(request: Request): JSONObject = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        if (cont.isActive) {
                            cont.resumeWithException(IOException("HTTP ${res.code}: $raw"))
                        }
                        return
                    }
                    runCatching { JSONObject(raw) }
                        .onSuccess { if (cont.isActive) cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resumeWithException(it) }
                }
            }
        })
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        if (uri.scheme == "file" && uri.path != null) {
            val direct = File(uri.path!!)
            if (direct.exists()) return direct
        }
        val dir = File(context.cacheDir, "whisper_tmp_upload").apply {
            if (!exists()) mkdirs()
        }
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "m4a"
        val dest = File(dir, "upload_${UUID.randomUUID()}.$ext")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out)
                }
            }
            dest.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }
}
