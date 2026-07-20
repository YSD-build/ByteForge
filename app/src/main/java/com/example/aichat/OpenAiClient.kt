package com.example.aichat

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatReqMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatReqMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.7,
    // 请求服务端在流末尾返回一个 usage 块，用于统计 token 消耗
    @SerializedName("stream_options") val streamOptions: Map<String, Boolean>? = mapOf("include_usage" to true)
)

data class Delta(
    val content: String? = null,
    // 深度思考模型（如 DeepSeek-R1）返回的推理过程，始终开启
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

data class Choice(
    val delta: Delta? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0
)

data class StreamChunk(
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ApiError? = null
)

data class ApiError(
    val message: String? = null,
    val type: String? = null
)

/** 流式响应事件：推理过程、内容、结束（携带用量） */
sealed class StreamEvent {
    data class Reasoning(val text: String) : StreamEvent()
    data class Content(val text: String) : StreamEvent()
    data class Done(val usage: Usage?) : StreamEvent()
}

class OpenAiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatReqMessage>,
        temperature: Double = 0.7
    ): Flow<StreamEvent> = flow {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = gson.toJson(
            ChatRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = temperature
            )
        )
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            val errText = response.body?.string().orEmpty()
            throw RuntimeException("API 错误 ${response.code}：$errText")
        }
        val source = response.body?.source() ?: throw RuntimeException("空响应")
        var lastUsage: Usage? = null
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            runCatching {
                val chunk = gson.fromJson(data, StreamChunk::class.java)
                chunk.error?.message?.let { throw RuntimeException("API 返回错误：$it") }
                chunk.usage?.let { lastUsage = it }
                val delta = chunk.choices?.firstOrNull()?.delta
                delta?.reasoningContent?.let { emit(StreamEvent.Reasoning(it)) }
                delta?.content?.let { emit(StreamEvent.Content(it)) }
            }
        }
        emit(StreamEvent.Done(lastUsage))
    }.flowOn(Dispatchers.IO)
}
