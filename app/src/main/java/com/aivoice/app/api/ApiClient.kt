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
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn"
    const val DEFAULT_MODEL = "fnlp/MOSS-TTSD-v0.5"

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
     * 清理API Token：去除隐藏Unicode字符、空白、换行
     */
    private fun cleanToken(token: String): String {
        return token.trim().filter { ch ->
            ch.code in 0x20..0x7E // 只保留标准ASCII可见字符
        }
    }

    /**
     * AI 配音 - TTS 语音合成（SiliconFlow）
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
     * 上传本地文件到 Replicate，返回公开可访问的 URL
     * POST https://api.replicate.com/v1/files
     */
    private fun uploadToReplicate(token: String, filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) throw Exception("文件不存在: $filePath")

        val mimeType = when {
            filePath.endsWith(".mp3") -> "audio/mpeg"
            filePath.endsWith(".wav") -> "audio/wav"
            filePath.endsWith(".ogg") -> "audio/ogg"
            filePath.endsWith(".flac") -> "audio/flac"
            filePath.endsWith(".m4a") -> "audio/mp4"
            else -> "audio/wav"
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody(mimeType.toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.replicate.com/v1/files")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "unknown error"
            throw Exception("文件上传失败 (${response.code}): $errorBody")
        }

        val json = JSONObject(response.body?.string() ?: throw Exception("上传响应为空"))
        val getUrl = json.optJSONObject("urls")?.optString("get")
            ?: throw Exception("上传成功但未返回URL: $json")
        return getUrl
    }

    /**
     * AI 翻唱 - 使用 MiniMax Music Cover（Replicate 官方模型）
     * 模型: minimax/music-cover
     * 输入: 歌曲URL + 风格描述文字
     * 输出: 翻唱后的音频文件
     */
    suspend fun generateCover(
        replicateToken: String,
        songAudioPath: String,
        stylePrompt: String
    ): String? {
        val token = cleanToken(replicateToken)

        // Step 1: 上传歌曲文件到 Replicate 获取公开 URL
        val songUrl = uploadToReplicate(token, songAudioPath)

        // Step 2: 调用 minimax/music-cover 模型
        val createBody = JSONObject().apply {
            put("input", JSONObject().apply {
                put("audio_url", songUrl)
                put("prompt", stylePrompt)
                put("audio_format", "mp3")
                put("sample_rate", 44100)
                put("bitrate", 256000)
            })
        }

        val modelUrl = "https://api.replicate.com/v1/models/minimax/music-cover/predictions"
        val createRequest = Request.Builder()
            .url(modelUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "wait=120")
            .post(createBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val createResponse = client.newCall(createRequest).execute()
        if (!createResponse.isSuccessful) {
            val errorBody = createResponse.body?.string() ?: "unknown error"
            throw Exception("Replicate API error (${createResponse.code}): $errorBody")
        }
        val createResult = JSONObject(createResponse.body?.string() ?: return null)
        val status = createResult.optString("status", "")
        val predictionId = createResult.optString("id", "")

        if (status == "succeeded") {
            return extractOutputAndSave(createResult, "cover_${System.currentTimeMillis()}.mp3")
        }
        if (status == "failed" || status == "canceled") {
            throw Exception("翻唱失败: ${createResult.optString("error", "unknown")}")
        }

        // Step 3: 轮询等待结果
        return pollReplicateResult(token, predictionId, "cover_${System.currentTimeMillis()}.mp3")
    }

    /**
     * 伴奏分离 - 使用 Replicate 音频分离模型
     * 模型: james30/audio-separator
     */
    suspend fun separateTracks(
        replicateToken: String,
        songAudioPath: String
    ): String? {
        val token = cleanToken(replicateToken)

        // 上传音频文件获取公开 URL
        val audioUrl = uploadToReplicate(token, songAudioPath)

        val createBody = JSONObject().apply {
            put("input", JSONObject().apply {
                put("audio", audioUrl)
            })
        }

        // 尝试 james30/audio-separator
        val modelUrl = "https://api.replicate.com/v1/models/james30/audio-separator/predictions"
        val createRequest = Request.Builder()
            .url(modelUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "wait=120")
            .post(createBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val createResponse = client.newCall(createRequest).execute()
        if (!createResponse.isSuccessful) {
            val errorBody = createResponse.body?.string() ?: "unknown error"
            throw Exception("Replicate API error (${createResponse.code}): $errorBody\n\n如伴奏分离模型不可用，请在 Replicate 上搜索 'vocal separation' 找到可用模型。")
        }
        val createResult = JSONObject(createResponse.body?.string() ?: return null)
        val status = createResult.optString("status", "")
        val predictionId = createResult.optString("id", "")

        if (status == "succeeded") {
            return extractOutputAndSave(createResult, "vocals_${System.currentTimeMillis()}.wav")
        }
        if (status == "failed" || status == "canceled") {
            throw Exception("分离失败: ${createResult.optString("error", "unknown")}")
        }

        return pollReplicateResult(token, predictionId, "vocals_${System.currentTimeMillis()}.wav")
    }

    /**
     * 声音克隆 - SiliconFlow
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

    // ========== Replicate helpers ==========

    private fun extractOutputAndSave(json: JSONObject, fileName: String): String? {
        val output = json.opt("output") ?: return null
        val downloadUrl = when (output) {
            is JSONArray -> output.optString(0, null)
            is String -> if (output.startsWith("http")) output else null
            else -> null
        }
        return if (downloadUrl != null) downloadAndSave(downloadUrl, fileName) else null
    }

    private fun pollReplicateResult(token: String, predictionId: String, fileName: String): String? {
        for (i in 0..60) {
            Thread.sleep(5000)
            val request = Request.Builder()
                .url("https://api.replicate.com/v1/predictions/$predictionId")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: continue)
                val status = json.getString("status")
                when (status) {
                    "succeeded" -> return extractOutputAndSave(json, fileName)
                    "failed" -> {
                        val error = json.optString("error", "unknown error")
                        throw Exception("Replicate任务失败: $error")
                    }
                    "canceled" -> throw Exception("Replicate任务被取消")
                }
            }
        }
        throw Exception("等待超时（5分钟）")
    }

    private fun downloadAndSave(url: String, fileName: String): String? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val file = File(getDownloadDir(), fileName)
            response.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            return file.absolutePath
        }
        return null
    }

    // ========== SiliconFlow helpers ==========

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
            return json.optString("uri", json.optString("id", json.optString("voice_id", null)))
        }
        return null
    }
}
