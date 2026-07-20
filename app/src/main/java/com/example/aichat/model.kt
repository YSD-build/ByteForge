package com.example.aichat

/** 一次回复的 token 用量统计 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class ChatMessage(
    val id: String,
    val role: String,      // "user" | "assistant" | "tool"
    val content: String,
    val createdAt: Long,
    // 深度思考模式下模型返回的推理过程（reasoning_content），默认始终开启
    val reasoningContent: String = "",
    // 本次回复消耗的 token 统计（真实用量或估算）
    val usage: TokenUsage? = null,
    // 本次回复耗时（毫秒）
    val durationMs: Long? = null
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    // 对话类型："chat" 普通聊天 | "agent" 自主智能体
    val type: String = "chat",
    // Agent 工作目录的真实文件系统路径（仅 type=agent 时有值）
    val workDir: String? = null
)
