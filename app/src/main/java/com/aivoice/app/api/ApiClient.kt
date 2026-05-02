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
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .addInterceptor(RetryInterceptor(maxRetries = 3))
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

    private fun cleanToken(token: String): String {
        return token.trim().filter { ch -> ch.code in 0x20..0x7E }
    }

    /**
     * 判断是否为网络可重试的异常
     */
    private fun isNetworkRetryable(e: Exception): Boolean {
        return e is SocketException
            || e is SocketTimeoutException
            || e is UnknownHostException
            || e is SSLException
            || e is IOException && (e.message?.contains("reset", true) == true
                || e.message?.contains("broken pipe", true) == true
                || e.message?.contains("unexpected end", true) == true
                || e.message?.contains("connect", true) == true)
    }

    /**
     * 带重试的网络请求包装器
     */
    private fun <T> executeWithRetry(
        maxRetries: Int = 3,
        taskName: String = "请求",
        block: () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (isNetworkRetryable(e) && attempt < maxRetries) {
                    val delay = attempt * 3000L // 3s, 6s, 9s
                    Thread.sleep(delay)
                    continue
                }
                // 非网络错误或最后一次重试，直接抛出
                if (isNetworkRetryable(e)) {
                    throw Exception(
                        "网络连接不稳定（已重试${maxRetries}次）。\n" +
                        "建议：\n" +
                        "1. 检查网络连接，建议使用 WiFi\n" +
                        "2. 如果使用移动数据，尝试切换到其他网络\n" +
                        "3. Replicate 服务器在国内访问可能不稳定，稍后重试\n" +
                        "4. 如有条件，可使用网络代理\n\n" +
                        "原始错误: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                throw e
            }
        }
        throw lastException ?: Exception("$taskName 失败")
    }

    /**
     * AI 配音 - TTS 语音合成（SiliconFlow）
     */
    suspend fun synthesizeSpeech(
        baseUrl: String, apiKey: String, text: String,
        model: String = DEFAULT_MODEL, voice: String = VOICE_PRESETS[0].voiceValue
    ): String? {
        return executeWithRetry(taskName = "配音") {
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
                file.absolutePath
            } else {
                throw Exception("配音API错误 (${response.code}): ${response.body?.string() ?: ""}")
            }
        }
    }

    /**
     * 上传本地文件到 Replicate（带重试）
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
            filePath.endsWith(".aac") -> "audio/aac"
            else -> "audio/wav"
        }

        return executeWithRetry(taskName = "文件上传") {
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
            json.optJSONObject("urls")?.optString("get")
                ?: throw Exception("上传成功但未返回URL: $json")
        }
    }

    /**
     * 创建 Replicate prediction（带重试）
     */
    private fun createReplicatePrediction(token: String, modelPath: String, inputBody: JSONObject): JSONObject {
        return executeWithRetry(taskName = "创建任务") {
            val body = JSONObject().apply {
                put("input", inputBody)
            }

            val request = Request.Builder()
                .url("https://api.replicate.com/v1/models/$modelPath/predictions")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "wait=120")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "unknown error"
                throw Exception("Replicate API error (${response.code}): $errorBody")
            }
            JSONObject(response.body?.string() ?: throw Exception("响应为空"))
        }
    }

    /**
     * AI 翻唱 - MiniMax Music Cover
     */
    suspend fun generateCover(
        replicateToken: String,
        songAudioPath: String,
        stylePrompt: String
    ): String? {
        val token = cleanToken(replicateToken)

        // Step 1: 上传歌曲
        val songUrl = uploadToReplicate(token, songAudioPath)

        // Step 2: 创建翻唱任务
        val input = JSONObject().apply {
            put("audio_url", songUrl)
            put("prompt", stylePrompt)
            put("audio_format", "mp3")
            put("sample_rate", 44100)
            put("bitrate", 256000)
        }

        val createResult = createReplicatePrediction(token, "minimax/music-cover", input)
        val status = createResult.optString("status", "")
        val predictionId = createResult.optString("id", "")

        if (status == "succeeded") {
            return extractOutputAndSave(createResult, "cover_${System.currentTimeMillis()}.mp3")
        }
        if (status == "failed" || status == "canceled") {
            throw Exception("翻唱失败: ${createResult.optString("error", "unknown")}")
        }

        // Step 3: 轮询等待
        return pollReplicateResult(token, predictionId, "cover_${System.currentTimeMillis()}.mp3")
    }

    /**
     * 伴奏分离
     */
    suspend fun separateTracks(
        replicateToken: String,
        songAudioPath: String
    ): String? {
        val token = cleanToken(replicateToken)

        val audioUrl = uploadToReplicate(token, songAudioPath)

        val input = JSONObject().apply {
            put("audio", audioUrl)
        }

        val createResult = createReplicatePrediction(token, "james30/audio-separator", input)
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
        return executeWithRetry(taskName = "声音克隆") {
            val voiceId = if (!referenceAudioPath.isNullOrEmpty()) {
                uploadReferenceAudio(baseUrl, apiKey, referenceAudioPath, model) ?: throw Exception("参考音频上传失败")
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
                file.absolutePath
            } else {
                throw Exception("克隆API错误 (${response.code}): ${response.body?.string() ?: ""}")
            }
        }
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
            val result = executeWithRetry(maxRetries = 3, taskName = "查询结果") {
                val request = Request.Builder()
                    .url("https://api.replicate.com/v1/predictions/$predictionId")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    JSONObject(response.body?.string() ?: "{}")
                } else {
                    throw IOException("查询状态失败: ${response.code}")
                }
            }

            val status = result.optString("status", "")
            when (status) {
                "succeeded" -> return extractOutputAndSave(result, fileName)
                "failed" -> throw Exception("任务失败: ${result.optString("error", "unknown")}")
                "canceled" -> throw Exception("任务被取消")
                // processing / starting → 继续轮询
            }
        }
        throw Exception("等待超时（5分钟）")
    }

    private fun downloadAndSave(url: String, fileName: String): String? {
        return executeWithRetry(taskName = "下载结果") {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val file = File(getDownloadDir(), fileName)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                file.absolutePath
            } else {
                throw IOException("下载失败: ${response.code}")
            }
        }
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

    /**
     * OkHttp 拦截器：自动重试网络错误
     */
    private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var lastException: IOException? = null
            for (attempt in 1..maxRetries) {
                try {
                    return chain.proceed(chain.request())
                } catch (e: IOException) {
                    lastException = e
                    if (attempt < maxRetries) {
                        Thread.sleep(attempt * 2000L)
                        continue
                    }
                }
            }
            throw lastException ?: IOException("请求失败")
        }
    }
}
