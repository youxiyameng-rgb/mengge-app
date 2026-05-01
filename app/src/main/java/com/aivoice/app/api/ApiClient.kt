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
     * 清理API Token：去除隐藏Unicode字符、空白、换行
     */
    private fun cleanToken(token: String): String {
        return token.trim().filter { ch ->
            ch.code in 0x20..0x7E // 只保留标准ASCII可见字符
        }
    }

    /**
     * AI 翻唱 - 使用 Replicate 进行声音转换
     * 方案: 使用 RVC (Retrieval-based Voice Conversion)
     * 输入: 原唱音频 + 目标声音参考音频
     */
    suspend fun generateCover(
        replicateToken: String,
        songAudioPath: String,
        targetVoicePath: String
    ): String? {
        val token = cleanToken(replicateToken)
        // Step 1: 上传两个音频文件
        val songDataUri = readFileAsBase64DataUri(songAudioPath) ?: return null
        val voiceDataUri = readFileAsBase64DataUri(targetVoicePath) ?: return null

        // Step 2: 创建预测任务（使用模型名，不指定版本号）
        val createBody = JSONObject().apply {
            put("model", "lucataco/so-vits-svc")
            put("input", JSONObject().apply {
                put("audio", songDataUri)
                put("vc_audio", voiceDataUri)
                put("f0_method", "rmvpe")
                put("f0_up_key", 0)
                put("protect", 0.33)
            })
            put("webhook_completed", JSONObject.NULL)
        }

        val createRequest = Request.Builder()
            .url("https://api.replicate.com/v1/predictions")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "wait=60")
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

        // 如果已经完成
        if (status == "succeeded") {
            return extractOutputAndSave(createResult, "cover_${System.currentTimeMillis()}.wav")
        }
        if (status == "failed" || status == "canceled") {
            throw Exception("翻唱失败: ${createResult.optString("error", "unknown")}")
        }

        // Step 3: 轮询等待完成
        return pollReplicateResult(token, predictionId, "cover_${System.currentTimeMillis()}.wav")
    }

    /**
     * 伴奏分离 - 使用 Replicate Demucs
     */
    suspend fun separateTracks(
        replicateToken: String,
        songAudioPath: String
    ): String? {
        val token = cleanToken(replicateToken)
        val audioDataUri = readFileAsBase64DataUri(songAudioPath) ?: return null

        val createBody = JSONObject().apply {
            put("model", "nateraw/music-separation")
            put("input", JSONObject().apply {
                put("audio", audioDataUri)
            })
            put("webhook_completed", JSONObject.NULL)
        }

        val createRequest = Request.Builder()
            .url("https://api.replicate.com/v1/predictions")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "wait=60")
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

    /**
     * 将本地音频文件读取为 data URI (base64)
     */
    private fun readFileAsBase64DataUri(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val mimeType = when {
            filePath.endsWith(".mp3") -> "audio/mpeg"
            filePath.endsWith(".wav") -> "audio/wav"
            filePath.endsWith(".ogg") -> "audio/ogg"
            filePath.endsWith(".flac") -> "audio/flac"
            filePath.endsWith(".m4a") -> "audio/mp4"
            else -> "audio/wav"
        }
        return "data:$mimeType;base64,$base64"
    }

    /**
     * 提取 Replicate 输出并保存
     */
    private fun extractOutputAndSave(json: JSONObject, fileName: String): String? {
        val output = json.opt("output") ?: return null
        val downloadUrl = when (output) {
            is JSONArray -> output.optString(0, null)
            is String -> if (output.startsWith("http")) output else null
            else -> null
        }
        return if (downloadUrl != null) downloadAndSave(downloadUrl, fileName) else null
    }

    /**
     * 轮询 Replicate 预测状态
     */
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
                    // processing, starting -> 继续等待
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
