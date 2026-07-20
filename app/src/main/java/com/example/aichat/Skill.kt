package com.example.aichat

/** Skill：可叠加的系统提示模板，赋予 Agent 特定领域能力 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val systemPrompt: String,
    val version: Int = 1,
    val installed: Boolean = false,
    val enabled: Boolean = false
)
