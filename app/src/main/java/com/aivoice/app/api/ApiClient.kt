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

    // Fish Speech 预设音色
    val VOICE_PRESETS = listOf(
        VoicePreset("alex", "Alex (英文男声)"),
        VoicePreset("anna", "Anna (英文女声)"),
        VoicePreset("bella", "Bella (英文女声)"),
        VoicePreset("elliot", "Elliot (英文男声)"),
        VoicePreset("josh", "Josh (英文男声)"),
        VoicePreset("nicole", "Nicole (英文女声)"),
        VoicePreset("sam", "Sam (英文男声)"),
        VoicePreset("custom", "🎭 自定义参考音频")
    )

    data class VoicePreset(val id: String, val name: String)

    private fun getDownloadDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AIVoice")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * AI 配音 - TTS 语音合成
     * 兼容 Fish Speech 和 OpenAI 的 /v1/audio/speech 接口
     */
    suspend fun synthesizeSpeech(
        baseUrl: String, apiKey: String, text: String,
        model: String = "fish-speech-1.5", voice: String = "alex"
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
    suspend fun generateCover(baseUrl: String, apiKey: String, songName: String, lyrics: String, model: String = "gpt-4o-mini"): String? {
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
     * 声音克隆 - Fish Speech 方式：
     * 1. 上传参考音频获取 voice_id
     * 2. 用 voice_id 进行 TTS 合成
     * 
     * 如果参考音频为空，则使用预设音色
     */
    suspend fun cloneVoice(
        baseUrl: String, apiKey: String, text: String,
        referenceAudioPath: String? = null, model: String = "fish-speech-1.5"
    ): String? {
        val voiceId = if (!referenceAudioPath.isNullOrEmpty()) {
            // 上传参考音频获取 voice ID
            val uploadedId = uploadReferenceAudio(baseUrl, apiKey, referenceAudioPath)
            uploadedId ?: return null
        } else {
            "alex" // 默认音色
        }

        // 使用 voice ID 进行 TTS
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
     * 上传参考音频到 Fish Speech 服务器
     * 返回 voice_id
     */
    private fun uploadReferenceAudio(baseUrl: String, apiKey: String, audioPath: String): String? {
        val url = "${baseUrl.trimEnd('/')}/v1/audio/voice"
        val audioFile = File(audioPath)
        if (!audioFile.exists()) return null

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio", audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("name", "custom_voice_${System.currentTimeMillis()}")
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
            return json.optString("id", json.optString("voice_id", null))
        }
        return null
    }

    /**
     * 伴奏分离建议
     */
    suspend fun separateTracks(baseUrl: String, apiKey: String, model: String = "gpt-4o-mini"): String? {
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
