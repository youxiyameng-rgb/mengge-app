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
     * AI 翻唱 - 使用 Replicate RVC voice conversion
     * 上传原唱音频 + 目标声音参考音频 → 输出翻唱音频
     */
    suspend fun generateCover(
        replicateToken: String,
        songAudioPath: String,
        targetVoicePath: String
    ): String? {
        // Step 1: Upload song file to Replicate
        val songUrl = uploadFileToReplicate(replicateToken, songAudioPath) ?: return null
        // Step 2: Upload target voice file
        val voiceUrl = uploadFileToReplicate(replicateToken, targetVoicePath) ?: return null

        // Step 3: Create prediction using RVC voice conversion model
        val createBody = JSONObject().apply {
            put("version", "951a50b3a415d10643c75e0337b9b6d7c1a1b8f7e45d0f72c6c0c7b5b3b0a9f8")
            put("input", JSONObject().apply {
                put("song_input", songUrl)
                put("voice_ref", voiceUrl)
                put("f0_method", "rmvpe")
                put("protect", 0.33)
            })
        }

        val createRequest = Request.Builder()
            .url("https://api.replicate.com/v1/predictions")
            .addHeader("Authorization", "Bearer $replicateToken")
            .addHeader("Content-Type", "application/json")
            .post(createBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val createResponse = client.newCall(createRequest).execute()
        if (!createResponse.isSuccessful) return null
        val createResult = JSONObject(createResponse.body?.string() ?: return null)
        val predictionId = createResult.getString("id")

        // Step 4: Poll for completion
        return pollReplicateResult(replicateToken, predictionId, "cover_${System.currentTimeMillis()}.wav")
    }

    /**
     * 伴奏分离 - 使用 Replicate Demucs model
     * 上传歌曲音频 → 输出分离后的人声和伴奏
     */
    suspend fun separateTracks(
        replicateToken: String,
        songAudioPath: String
    ): String? {
        // Step 1: Upload song file
        val songUrl = uploadFileToReplicate(replicateToken, songAudioPath) ?: return null

        // Step 2: Create prediction using Demucs model
        val createBody = JSONObject().apply {
            put("version", "0712bdbb26d1bc43c48aa0cdc0a9e2c2b8c2a0fe56a4e5cc1c0c1f2e3e5b0aa0")
            put("input", JSONObject().apply {
                put("audio", songUrl)
                put("model", "htdemucs")
                put("stem", "vocals")
            })
        }

        val createRequest = Request.Builder()
            .url("https://api.replicate.com/v1/predictions")
            .addHeader("Authorization", "Bearer $replicateToken")
            .addHeader("Content-Type", "application/json")
            .post(createBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val createResponse = client.newCall(createRequest).execute()
        if (!createResponse.isSuccessful) return null
        val createResult = JSONObject(createResponse.body?.string() ?: return null)
        val predictionId = createResult.getString("id")

        // Step 3: Poll for completion
        return pollReplicateResult(replicateToken, predictionId, "separated_${System.currentTimeMillis()}.wav")
    }

    /**
     * 声音克隆 - 上传参考音频 + 用该声音合成（SiliconFlow）
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

    private fun uploadFileToReplicate(token: String, filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        // Try direct file upload
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "content", file.name,
                file.asRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.replicate.com/v1/files")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val json = JSONObject(response.body?.string() ?: return null)
            val urlsObj = json.optJSONObject("urls")
            return urlsObj?.optString("get", null)
                ?: json.optString("url", null)
        }
        return null
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
                    "succeeded" -> {
                        val output = json.get("output")
                        val downloadUrl = when (output) {
                            is JSONArray -> output.getString(0)
                            is String -> output
                            else -> return null
                        }
                        return downloadAndSave(downloadUrl, fileName)
                    }
                    "failed", "canceled" -> return null
                }
            }
        }
        return null
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
