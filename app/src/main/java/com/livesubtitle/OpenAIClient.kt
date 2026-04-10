package com.livesubtitle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * OpenAI API 客户端
 * 1. Whisper API - 语音识别
 * 2. GPT-4o-mini - 翻译
 */
object OpenAIClient {

    private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
    private const val CHAT_API_URL = "https://api.openai.com/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 语音识别 - 调用 Whisper API
     * @param audioData PCM 音频数据
     * @param language 语言代码 (japanese, english, etc.)
     */
    suspend fun transcribe(audioData: ByteArray, language: String = "japanese"): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 转换为 WAV 格式
                val wavData = createWavHeader(audioData.size)
                val fullWav = wavData + audioData

                val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
                val multipartBody = buildMultipartBody(fullWav, boundary, language)

                val request = Request.Builder()
                    .url(WHISPER_API_URL)
                    .header("Authorization", "Bearer ${MainActivity.apiKey}")
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .post(multipartBody.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val transcription = gson.fromJson(responseBody, TranscriptionResponse::class.java)
                    Result.success(transcription.text)
                } else {
                    Result.failure(Exception("Whisper API 错误: ${response.code} - $responseBody"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("语音识别失败: ${e.message}"))
            }
        }
    }

    /**
     * 翻译 - 调用 GPT-4o-mini
     * @param text 原文
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "Japanese",
        targetLang: String = "Chinese"
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val systemPrompt = """
                    你是一个专业翻译助手。请将用户提供的${sourceLang}文本翻译成${targetLang}。
                    要求：
                    1. 准确传达原文意思
                    2. 符合中文表达习惯
                    3. 保持简洁，不要添加任何解释
                    4. 如果是口语对话，使用自然的口语化表达
                """.trimIndent()

                val requestBody = ChatRequest(
                    model = "gpt-4o-mini",
                    messages = listOf(
                        Message(role = "system", content = systemPrompt),
                        Message(role = "user", content = text)
                    ),
                    temperature = 0.3
                )

                val jsonBody = gson.toJson(requestBody)

                val request = Request.Builder()
                    .url(CHAT_API_URL)
                    .header("Authorization", "Bearer ${MainActivity.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                    val translatedText = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                    Result.success(translatedText)
                } else {
                    Result.failure(Exception("翻译API错误: ${response.code} - $responseBody"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("翻译失败: ${e.message}"))
            }
        }
    }

    /**
     * 一站式：语音识别 + 翻译
     */
    suspend fun transcribeAndTranslate(
        audioData: ByteArray,
        sourceLang: String = "japanese",
        targetLang: String = "chinese"
    ): Pair<String?, String?> {
        // 语音识别
        val transcriptionResult = transcribe(audioData, sourceLang)
        val originalText = transcriptionResult.getOrNull() ?: return Pair(null, null)

        if (originalText.isBlank()) {
            return Pair(null, null)
        }

        // 翻译
        val translateResult = translate(
            originalText,
            sourceLang.replaceFirstChar { it.uppercase() },
            targetLang.replaceFirstChar { it.uppercase() }
        )
        val translatedText = translateResult.getOrNull() ?: originalText

        return Pair(originalText, translatedText)
    }

    /**
     * 构建 Multipart 请求体（用于 Whisper API）
     */
    private fun buildMultipartBody(audioData: ByteArray, boundary: String, language: String): ByteArray {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        sb.append("Content-Type: audio/wav\r\n\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)

        val langPart = "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n$language\r\n".toByteArray(Charsets.UTF_8)
        val modelPart = "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n".toByteArray(Charsets.UTF_8)
        val endPart = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        return headerBytes + audioData + langPart + modelPart + endPart
    }

    /**
     * 创建 WAV 文件头
     */
    private fun createWavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // ChunkSize
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xFF).toByte()
        header[5] = ((fileSize shr 8) and 0xFF).toByte()
        header[6] = ((fileSize shr 16) and 0xFF).toByte()
        header[7] = ((fileSize shr 24) and 0xFF).toByte()

        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1Size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // AudioFormat (1 = PCM)
        header[20] = 1
        header[21] = 0

        // NumChannels
        header[22] = channels.toByte()
        header[23] = 0

        // SampleRate
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate shr 8) and 0xFF).toByte()
        header[26] = ((sampleRate shr 16) and 0xFF).toByte()
        header[27] = ((sampleRate shr 24) and 0xFF).toByte()

        // ByteRate
        val byteRate = sampleRate * channels * bitsPerSample / 8
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte()
        header[31] = ((byteRate shr 24) and 0xFF).toByte()

        // BlockAlign
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        header[32] = (blockAlign.toInt() and 0xFF).toByte()
        header[33] = ((blockAlign.toInt() shr 8) and 0xFF).toByte()

        // BitsPerSample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Subchunk2Size
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = ((dataSize shr 8) and 0xFF).toByte()
        header[42] = ((dataSize shr 16) and 0xFF).toByte()
        header[43] = ((dataSize shr 24) and 0xFF).toByte()

        return header
    }

    // Data classes for API responses
    data class TranscriptionResponse(
        val text: String
    )

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.3
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val choices: List<Choice>
    )

    data class Choice(
        val message: Message
    )
}
