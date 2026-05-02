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

    // MiniMax API 基础地址（国内直连，无需翻墙）
    const val MINIMAX_BASE_URL = "https://api.minimax.chat"

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
                    Thread.sleep(attempt * 3000L)
                    continue
                }
                if (isNetworkRetryable(e)) {
                    throw Exception(
                        "网络连接不稳定（已重试${maxRetries}次）。\n" +
                        "建议：\n" +
                        "1. 检查网络连接，建议使用 WiFi\n" +
                        "2. 如果使用移动数据，尝试切换到其他网络\n\n" +
                        "原始错误: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                throw e
            }
        }
        throw lastException ?: Exception("$taskName 失败")
    }

    // ===== AI 配音 - SiliconFlow TTS =====

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

    // ===== AI 翻唱 - MiniMax 海螺音乐（国内直连）=====

    /**
     * 文件转 base64 字符串
     */
    private fun fileToBase64(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) throw Exception("文件不存在: $filePath")
        val bytes = file.readBytes()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * AI 翻唱 - MiniMax Music Cover API
     *
     * 流程：
     * 1. 将音频文件编码为 base64
     * 2. 调用 MiniMax 创建翻唱任务
     * 3. 轮询等待结果
     * 4. 下载并保存结果音频
     */
    suspend fun generateCover(
        minimaxApiKey: String,
        songAudioPath: String,
        stylePrompt: String
    ): String? {
        val apiKey = cleanToken(minimaxApiKey)
        if (apiKey.isEmpty()) throw Exception("MiniMax API Key 为空")

        // Step 1: 编码音频文件
        val audioBase64 = fileToBase64(songAudioPath)
        val mimeType = when {
            songAudioPath.endsWith(".mp3") -> "audio/mpeg"
            songAudioPath.endsWith(".wav") -> "audio/wav"
            songAudioPath.endsWith(".ogg") -> "audio/ogg"
            songAudioPath.endsWith(".flac") -> "audio/flac"
            songAudioPath.endsWith(".m4a") -> "audio/mp4"
            else -> "audio/wav"
        }
        val dataUri = "data:$mimeType;base64,$audioBase64"

        // Step 2: 创建翻唱任务
        val createResult = executeWithRetry(taskName = "创建翻唱任务") {
            val body = JSONObject().apply {
                put("model", "music-2.0")
                put("refer_audio_url", dataUri)
                put("refer_audio_format", "mp3")
                put("refer_audio_sample_rate", 44100)
                put("refer_audio_bitrate", 128000)
            }

            val request = Request.Builder()
                .url("$MINIMAX_BASE_URL/v1/music/cover")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw Exception("MiniMax API错误 (${response.code}): $responseBody")
            }
            JSONObject(responseBody)
        }

        // 检查创建状态
        val baseResp = createResult.optJSONObject("base_resp")
        if (baseResp != null && baseResp.optInt("status_code", 0) != 0) {
            throw Exception("MiniMax错误: ${baseResp.optString("status_msg", "unknown")}")
        }

        val taskId = createResult.optString("task_id", "")
        if (taskId.isEmpty()) {
            throw Exception("MiniMax未返回task_id: $createResult")
        }

        // Step 3: 轮询等待结果
        return pollMiniMaxResult(apiKey, taskId)
    }

    /**
     * 轮询 MiniMax 翻唱任务结果
     */
    private fun pollMiniMaxResult(apiKey: String, taskId: String): String? {
        for (i in 0..120) {  // 最多等 10 分钟
            Thread.sleep(5000)

            val result = executeWithRetry(maxRetries = 3, taskName = "查询翻唱结果") {
                val request = Request.Builder()
                    .url("$MINIMAX_BASE_URL/v1/music/cover/query?task_id=$taskId")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    throw IOException("查询失败 (${response.code}): $responseBody")
                }
                JSONObject(responseBody)
            }

            // 检查 base_resp
            val baseResp = result.optJSONObject("base_resp")
            if (baseResp != null && baseResp.optInt("status_code", 0) != 0) {
                val errMsg = baseResp.optString("status_msg", "unknown")
                throw Exception("翻唱任务失败: $errMsg")
            }

            val status = result.optString("status", "")
            when (status) {
                "completed", "done", "succeeded" -> {
                    // 从结果中提取音频并保存
                    return extractMiniMaxAudio(result)
                }
                "failed", "error", "canceled" -> {
                    throw Exception("翻唱任务失败: ${result.optString("error", status)}")
                }
                // "processing", "pending", "running" → 继续轮询
            }

            // 也检查 file 字段（有些版本直接返回 file）
            if (result.has("audio_file") || result.has("file") || result.has("audio_data")) {
                return extractMiniMaxAudio(result)
            }
        }
        throw Exception("翻唱超时（10分钟），请稍后重试")
    }

    /**
     * 从 MiniMax 响应中提取音频数据并保存
     */
    private fun extractMiniMaxAudio(json: JSONObject): String? {
        // 尝试多种可能的字段名
        val audioData = json.optString("audio_file", null)
            ?: json.optString("audio_data", null)
            ?: json.optJSONObject("file")?.optString("data", null)
            ?: json.optJSONObject("audio")?.optString("data", null)

        if (audioData != null && audioData.isNotEmpty()) {
            // base64 编码的音频数据
            val bytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)
            val file = File(getDownloadDir(), "cover_${System.currentTimeMillis()}.mp3")
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }

        // 尝试获取 URL
        val audioUrl = json.optString("audio_url", null)
            ?: json.optString("audio_file_url", null)
            ?: json.optJSONObject("file")?.optString("url", null)
            ?: json.optJSONObject("audio")?.optString("url", null)

        if (audioUrl != null && audioUrl.startsWith("http")) {
            return downloadAndSave(audioUrl, "cover_${System.currentTimeMillis()}.mp3")
        }

        throw Exception("翻唱完成但无法提取音频: $json")
    }

    // ===== 伴奏分离（暂用提示告知用户手动处理）=====

    suspend fun separateTracks(
        apiKey: String,
        songAudioPath: String
    ): String? {
        throw Exception(
            "伴奏分离功能升级中，暂时不可用。\n" +
            "建议使用以下工具进行伴奏分离：\n" +
            "1. 网页版: vocalremover.org\n" +
            "2. 网页版: moises.ai\n" +
            "3. 手机App: 小影、剪映（都有伴奏分离功能）"
        )
    }

    // ===== 声音克隆 - SiliconFlow =====

    suspend fun cloneVoice(
        baseUrl: String, apiKey: String, text: String,
        referenceAudioPath: String? = null, model: String = DEFAULT_MODEL
    ): String? {
        return executeWithRetry(taskName = "声音克隆") {
            val voiceId = if (!referenceAudioPath.isNullOrEmpty()) {
                uploadReferenceAudio(baseUrl, apiKey, referenceAudioPath, model)
                    ?: throw Exception("参考音频上传失败")
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

    // ===== 工具方法 =====

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
            val json = JSONObject(response.body?.string() ?: return null)
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
