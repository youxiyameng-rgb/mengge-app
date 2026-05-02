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
        .readTimeout(600, TimeUnit.SECONDS)  // 翻唱可能需要较长时间
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .addInterceptor(RetryInterceptor(maxRetries = 3))
        .build()

    const val DEFAULT_BASE_URL = "https://api.siliconflow.cn"
    const val DEFAULT_MODEL = "fnlp/MOSS-TTSD-v0.5"

    // MiniMax API 基础地址（国内直连，无需翻墙）
    const val MINIMAX_BASE_URL = "https://api.minimaxi.com"

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

    // ===== AI 翻唱 - MiniMax Music Cover（国内直连）=====

    /**
     * 将文件读取为 base64 字符串
     */
    private fun fileToBase64(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) throw Exception("文件不存在: $filePath")
        if (file.length() > 50 * 1024 * 1024) throw Exception("文件太大（超过50MB），请使用较短的音频")
        val bytes = file.readBytes()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * 将 hex 编码字符串解码为字节数组
     */
    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * AI 翻唱 - MiniMax Music Generation API（一步翻唱）
     *
     * 官方文档：https://platform.minimaxi.com/docs/api-reference/music-generation.md
     *
     * 流程：
     * 1. 将音频文件编码为 base64
     * 2. 调用 POST /v1/music_generation（同步接口，阻塞等待结果）
     * 3. 返回 hex 编码的音频 → 解码保存为文件
     *
     * 模型选择：
     * - music-cover-free：免费版，所有注册用户可用，RPM 较低
     * - music-cover：付费版，需要 Token Plan，RPM 较高
     */
    suspend fun generateCover(
        minimaxApiKey: String,
        songAudioPath: String,
        stylePrompt: String,
        model: String = "music-cover-free"
    ): String? {
        val apiKey = cleanToken(minimaxApiKey)
        if (apiKey.isEmpty()) throw Exception("MiniMax API Key 为空")

        // Step 1: 编码音频文件为 base64
        val audioBase64 = fileToBase64(songAudioPath)

        // Step 2: 调用音乐翻唱 API（同步接口，直接返回结果）
        val result = executeWithRetry(taskName = "AI翻唱") {
            val body = JSONObject().apply {
                put("model", model)                  // 支持 music-cover-free 和 music-cover
                put("prompt", stylePrompt)          // 目标风格描述（必填，10-300字符）
                put("audio_base64", audioBase64)    // 参考音频 base64
                put("output_format", "url")         // 返回 URL 而非 hex（方便下载）
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", 44100)
                    put("bitrate", 256000)
                    put("format", "mp3")
                })
            }

            val request = Request.Builder()
                .url("$MINIMAX_BASE_URL/v1/music_generation")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                // 友好的错误提示
                val errMsg = try {
                    val json = JSONObject(responseBody)
                    val baseResp = json.optJSONObject("base_resp")
                    val code = baseResp?.optInt("status_code", response.code) ?: response.code
                    val msg = baseResp?.optString("status_msg", responseBody) ?: responseBody
                    when (code) {
                        1008 -> "账号余额不足（请使用 music-cover-free 免费模型）"
                        1004 -> "API Key 无效，请检查设置"
                        2013 -> "参数错误: $msg"
                        1002 -> "请求过于频繁，请稍后重试"
                        else -> "MiniMax 错误 ($code): $msg"
                    }
                } catch (_: Exception) {
                    "MiniMax API错误 (${response.code}): $responseBody"
                }
                throw Exception(errMsg)
            }
            JSONObject(responseBody)
        }

        // Step 3: 检查返回状态
        val baseResp = result.optJSONObject("base_resp")
        if (baseResp != null) {
            val statusCode = baseResp.optInt("status_code", -1)
            if (statusCode != 0) {
                val statusMsg = baseResp.optString("status_msg", "unknown")
                throw Exception("MiniMax 翻唱失败 ($statusCode): $statusMsg")
            }
        }

        val data = result.optJSONObject("data") ?: throw Exception("MiniMax 返回数据为空: $result")
        val status = data.optInt("status", 0)

        // status: 1=合成中, 2=已完成
        // 使用 output_format=url 时，音频 URL 在 data.audio 字段中
        if (status == 2) {
            // 完成！提取音频
            return extractAudioFromResponse(data)
        } else if (status == 1) {
            // 仍在合成中（理论上同步接口不应该出现这种情况，但以防万一）
            // 重新查询... 但 MiniMax 音乐接口没有 query endpoint
            // 所以直接等待一下再返回
            throw Exception("翻唱任务仍在处理中，请稍后重试")
        } else {
            throw Exception("翻唱返回未知状态: $status, data: $data")
        }
    }

    /**
     * 从 MiniMax 音乐 API 响应中提取音频
     * - output_format=url 时，data.audio 是 URL 字符串
     * - output_format=hex 时，data.audio 是 hex 编码的音频数据
     */
    private fun extractAudioFromResponse(data: JSONObject): String? {
        val audioField = data.optString("audio", "")

        if (audioField.isEmpty()) {
            throw Exception("翻唱完成但未返回音频数据")
        }

        // 判断是 URL 还是 hex 编码
        if (audioField.startsWith("http://") || audioField.startsWith("https://")) {
            // URL 格式，下载音频
            return downloadAndSave(audioField, "cover_${System.currentTimeMillis()}.mp3")
        } else {
            // hex 编码格式，直接解码
            try {
                val bytes = hexToBytes(audioField)
                val file = File(getDownloadDir(), "cover_${System.currentTimeMillis()}.mp3")
                FileOutputStream(file).use { it.write(bytes) }
                return file.absolutePath
            } catch (e: Exception) {
                throw Exception("音频数据解码失败: ${e.message}")
            }
        }
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
