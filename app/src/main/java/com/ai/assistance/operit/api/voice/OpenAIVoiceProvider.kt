package com.ai.assistance.operit.api.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAIVoiceProvider(
    private val context: Context,
    private val endpointUrl: String,
    private val apiKey: String,
    private val model: String,
    initialVoiceId: String
) : VoiceService {

    companion object {
        private const val TAG = "OpenAIVoiceProvider"
        private const val DEFAULT_TIMEOUT_SECONDS = 30

        val AVAILABLE_VOICES = listOf(
            VoiceService.Voice("alloy", "alloy", "en-US", "NEUTRAL"),
            VoiceService.Voice("echo", "echo", "en-US", "NEUTRAL"),
            VoiceService.Voice("fable", "fable", "en-US", "NEUTRAL"),
            VoiceService.Voice("onyx", "onyx", "en-US", "NEUTRAL"),
            VoiceService.Voice("nova", "nova", "en-US", "NEUTRAL"),
            VoiceService.Voice("shimmer", "shimmer", "en-US", "NEUTRAL")
        )
    }

    @Serializable
    private data class OpenAiSpeechRequest(
        val model: String,
        val input: String,
        val voice: String,
        val response_format: String? = null,
        val speed: Double? = null
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private var voiceId: String = initialVoiceId

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: Boolean
        get() = _isInitialized.value

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: Boolean
        get() = _isSpeaking.value

    override val speakingStateFlow: Flow<Boolean> = _isSpeaking.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (endpointUrl.isBlank()) {
                throw TtsException("OpenAI TTS URL 未设置，请填写完整接口地址（例如 https://xxx/v1/audio/speech）。")
            }
            if (!endpointUrl.startsWith("http://") && !endpointUrl.startsWith("https://")) {
                throw TtsException("OpenAI TTS URL 必须以 http:// 或 https:// 开头。")
            }
            if (!endpointUrl.contains("/audio/speech")) {
                throw TtsException("OpenAI TTS URL 必须包含 /v1/audio/speech（请填写完整到 audio/speech）。")
            }
            if (apiKey.isBlank()) {
                throw TtsException("API Key 未设置，请在设置中填写。")
            }
            if (model.isBlank()) {
                throw TtsException("OpenAI TTS model 未设置，请在设置中填写（例如 tts-1）。")
            }
            if (voiceId.isBlank()) {
                throw TtsException("OpenAI TTS voice 未设置，请在设置中填写（例如 alloy）。")
            }

            _isInitialized.value = true
            true
        } catch (e: Exception) {
            _isInitialized.value = false
            AppLogger.e(TAG, "OpenAI TTS initialize failed", e)
            if (e is TtsException) throw e
            throw TtsException("初始化 OpenAI TTS 服务时发生意外错误", cause = e)
        }
    }

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float,
        pitch: Float,
        extraParams: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val initOk = initialize()
            if (!initOk) return@withContext false
        }

        try {
            if (interrupt && isSpeaking) {
                stop()
            }

            _isSpeaking.value = true

            val requestModel = extraParams["model"]?.takeIf { it.isNotBlank() } ?: model
            val requestVoice = extraParams["voice"]?.takeIf { it.isNotBlank() } ?: voiceId
            val responseFormat =
                extraParams["response_format"]?.takeIf { it.isNotBlank() } ?: "mp3"
            val speed = (extraParams["speed"]?.toDoubleOrNull() ?: rate.toDouble())
                .coerceIn(0.25, 4.0)

            val payload = OpenAiSpeechRequest(
                model = requestModel,
                input = text,
                voice = requestVoice,
                response_format = responseFormat,
                speed = speed
            )

            val bodyJson = Json.encodeToString(payload)

            val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpointUrl)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                throw TtsException("请求 OpenAI TTS 失败", cause = e)
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                response.close()
                _isSpeaking.value = false
                throw TtsException(
                    message = "OpenAI TTS request failed with code ${response.code}",
                    httpStatusCode = response.code,
                    errorBody = errorBody
                )
            }

            val safeExt = when (responseFormat.lowercase()) {
                "mp3", "opus", "aac", "flac", "wav", "pcm" -> responseFormat.lowercase()
                else -> "mp3"
            }
            val tempFile = File(context.cacheDir, "openai_tts_${UUID.randomUUID()}.$safeExt")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            response.close()

            withContext(Dispatchers.Main) {
                playAudioFile(tempFile)
            }

            true
        } catch (e: Exception) {
            _isSpeaking.value = false
            AppLogger.e(TAG, "OpenAI TTS speak failed", e)
            if (e is TtsException) throw e
            throw TtsException("OpenAI TTS speak failed", cause = e)
        }
    }

    private fun playAudioFile(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _isSpeaking.value = false
                    file.delete()
                }
                setOnErrorListener { _, what, extra ->
                    AppLogger.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _isSpeaking.value = false
                    file.delete()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Play audio failed", e)
            _isSpeaking.value = false
            file.delete()
        }
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
            _isSpeaking.value = false
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Stop failed", e)
            false
        }
    }

    override suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            mediaPlayer?.pause()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pause failed", e)
            false
        }
    }

    override suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            mediaPlayer?.start()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Resume failed", e)
            false
        }
    }

    override fun shutdown() {
        mediaPlayer?.release()
        mediaPlayer = null
        _isSpeaking.value = false
        _isInitialized.value = false
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> {
        return AVAILABLE_VOICES
    }

    override suspend fun setVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        this@OpenAIVoiceProvider.voiceId = voiceId
        true
    }
}
