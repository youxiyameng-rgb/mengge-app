package com.aivoice.app.api

import android.os.Environment
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn"
    const val DEFAULT_MODEL = "fnlp/MOSS-TTSD-v0.5"

    // SiliconFlow 预设音色
    val VOICE_PRESETS = listOf(
        VoicePreset("alex", "Alex (男声)", "fnlp/MOSS-TTSD-v0.5:alex"),
        VoicePreset("anna", "Anna (女声)", "fnlp/MOSS-TTSD-v0.5:anna"),
        VoicePreset("bella", "Bella (女声)", "fnlp/MOSS-TTSD-v0.5:bella"),
        VoicePreset("benjamin", "Benjamin (男声)", "fnlp/MOSS-TTSD-v0.5:benjamin"),
        VoicePreset("charles", "Charles (男声)", "fnlp/MOSS-TTSD-v0.5:charles"),
        VoicePreset("claire", "Claire (女声)", "fnlp/MOSS-TTSD-v0.5:claire"),
        VoicePreset("david", "David (男声)", "fnlp/MOSS-TTSD-v0.5:david"),
        VoicePreset("diana", "Diana (女声)", "fnlp/MOSS-TTSD-v0.5:diana")
    )

    data class VoicePreset(val id: String, val name: String, val voiceValue: String)

    private fun getDownloadDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AIVoice")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * AI 配音 - TTS 语音合成
     * 兼容 SiliconFlow / OpenAI / Fish Speech 的 /v1/audio/speech 接口
     */
    suspend fun synthesizeSpeech(
        baseUrl: String, apiKey: String, text: String,
        model: String = DEFAULT_MODEL, voice: String = VOICE_PRESETS[0].voiceValue
    ): String? {
        val url = "${baseUrl.trimEnd('/')}/v1/audio/speech"
        val body = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val file = File(getDownloadDir(), "speech_${System.currentTimeMillis()}.mp3")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            return file.absolutePath
        }
        return null
    }

    /**
     * AI 翻唱 - 使用 Chat Completions 生成翻唱建议
     */
    suspend fun generateCover(baseUrl: String, apiKey: String, songName: String, lyrics: String, model: String = "Qwen/Qwen3-8B"): String? {
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个AI翻唱助手。根据用户提供的歌曲名和歌词，返回翻唱建议和编曲风格。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "歌曲：$songName\n歌词：$lyrics\n请生成翻唱建议，包括编曲风格、音色推荐和节奏建议。")
                })
            })
            put("max_tokens", 1000)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val json = JSONObject(responseBody)
            return json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
        return null
    }

    /**
     * 声音克隆 - 上传参考音频 + 用该声音合成
     * SiliconFlow 支持上传参考音频到 /v1/audio/voice
     * 然后用返回的 voice ID 进行 TTS
     */
    suspend fun cloneVoice(
        baseUrl: String, apiKey: String, text: String,
        referenceAudioPath: String? = null, model: String = DEFAULT_MODEL
    ): String? {
        val voiceId = if (!referenceAudioPath.isNullOrEmpty()) {
            val uploadedId = uploadReferenceAudio(baseUrl, apiKey, referenceAudioPath, model)
            uploadedId ?: return null
        } else {
            VOICE_PRESETS[0].voiceValue
        }

        val url = "${baseUrl.trimEnd('/')}/v1/audio/speech"
        val body = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voiceId)
            put("response_format", "mp3")
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val file = File(getDownloadDir(), "clone_${System.currentTimeMillis()}.mp3")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            return file.absolutePath
        }
        return null
    }

    /**
     * 上传参考音频到 SiliconFlow
     * POST /v1/audio/voice
     */
    private fun uploadReferenceAudio(baseUrl: String, apiKey: String, audioPath: String, model: String): String? {
        val url = "${baseUrl.trimEnd('/')}/v1/audio/voice"
        val audioFile = File(audioPath)
        if (!audioFile.exists()) return null

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("customName", "custom_voice")
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val json = JSONObject(responseBody)
            // SiliconFlow returns { "uri": "xxxx" }
            return json.optString("uri", json.optString("id", json.optString("voice_id", null)))
        }
        return null
    }

    /**
     * 伴奏分离建议
     */
    suspend fun separateTracks(baseUrl: String, apiKey: String, model: String = "Qwen/Qwen3-8B"): String? {
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个音频分离助手。帮助用户理解伴奏分离的流程和工具推荐。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请告诉我如何使用当前API进行伴奏分离，推荐可用的分离工具和方法。")
                })
            })
            put("max_tokens", 800)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val json = JSONObject(responseBody)
            return json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
        return null
    }
}
