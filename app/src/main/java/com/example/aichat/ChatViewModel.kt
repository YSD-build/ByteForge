package com.example.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

const val AGENT_MAX_STEPS = 14
const val AGENT_NO_ACTION_LIMIT = 3

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: AppSettings,
    private val client: OpenAiClient,
    private val usageStore: TokenUsageStore
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _conversationType = MutableStateFlow("chat")
    val conversationType: StateFlow<String> = _conversationType.asStateFlow()

    private val _workDir = MutableStateFlow<String?>(null)
    val workDir: StateFlow<String?> = _workDir.asStateFlow()

    private var conversationId: String = ""
    private var currentTitle: String = "新对话"
    private var allConversations: List<Conversation> = emptyList()
    private var currentAssistantId: String = ""
    private var assistantCreatedAt: Long = 0
    private var currentType: String = "chat"
    private var currentWorkDir: String? = null
    private var job: Job? = null
    private var startedAt: Long = 0
    private var loaded = false

    fun load(conversationId: String) {
        // 已加载过同一对话时跳过，避免退出再进时覆盖流式进行中的消息
        if (loaded && this.conversationId == conversationId) return
        this.conversationId = conversationId
        viewModelScope.launch {
            allConversations = repo.loadConversations()
            val conv = allConversations.find { it.id == conversationId }
            _messages.value = conv?.messages ?: emptyList()
            currentTitle = conv?.title ?: "新对话"
            currentType = conv?.type ?: "chat"
            currentWorkDir = conv?.workDir
            _conversationType.value = currentType
            _workDir.value = currentWorkDir
            loaded = true
        }
    }

    // ---------- 普通聊天（深度思考始终开启） ----------
    fun send(userText: String) {
        if (_isStreaming.value) return
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userText,
            createdAt = System.currentTimeMillis()
        )
        currentAssistantId = UUID.randomUUID().toString()
        assistantCreatedAt = System.currentTimeMillis()
        val assistantMsg = ChatMessage(
            id = currentAssistantId,
            role = "assistant",
            content = "",
            createdAt = assistantCreatedAt
        )
        val withUser = _messages.value + userMsg
        _messages.value = withUser + assistantMsg
        _isStreaming.value = true
        startedAt = System.currentTimeMillis()

        job = viewModelScope.launch {
            val s = settings.get()
            // 依据上下文窗口裁剪最近的消息
            val recent = withUser.takeLast(s.maxContextMessages)
            val reqMessages = recent.map { ChatReqMessage(role = it.role, content = it.content) }
            val contentSb = StringBuilder()
            val reasoningSb = StringBuilder()
            var usage: Usage? = null
            try {
                client.streamChat(s.baseUrl, s.apiKey, s.model, reqMessages, s.temperature)
                    .cancellable()
                    .collect { ev ->
                        when (ev) {
                            is StreamEvent.Reasoning -> {
                                reasoningSb.append(ev.text)
                                updateAssistant { copy(reasoningContent = reasoningSb.toString()) }
                            }
                            is StreamEvent.Content -> {
                                contentSb.append(ev.text)
                                updateAssistant { copy(content = contentSb.toString()) }
                            }
                            is StreamEvent.Done -> usage = ev.usage
                        }
                    }
                val duration = System.currentTimeMillis() - startedAt
                val finalUsage = usage?.let {
                    TokenUsage(it.promptTokens, it.completionTokens, it.totalTokens)
                } ?: estimateUsage(recent, contentSb.length)
                val finalMsg = ChatMessage(
                    id = currentAssistantId,
                    role = "assistant",
                    content = contentSb.toString(),
                    createdAt = assistantCreatedAt,
                    reasoningContent = reasoningSb.toString(),
                    usage = finalUsage,
                    durationMs = duration
                )
                persist(withUser, finalMsg, userText)
                usageStore.add(finalUsage)
            } catch (e: Exception) {
                val err = "⚠️ 错误：${e.message}"
                val duration = System.currentTimeMillis() - startedAt
                val finalMsg = ChatMessage(
                    id = currentAssistantId,
                    role = "assistant",
                    content = err,
                    createdAt = System.currentTimeMillis(),
                    durationMs = duration
                )
                persist(withUser, finalMsg, userText)
            } finally {
                _isStreaming.value = false
            }
        }
    }

    // ---------- Agent：自主智能体循环 ----------
    fun sendAgentGoal(goal: String) {
        if (_isStreaming.value) return
        val dir = currentWorkDir
        if (dir == null) {
            val m = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = "⚠️ 该 Agent 对话缺少工作目录，无法执行。",
                createdAt = System.currentTimeMillis()
            )
            _messages.value = _messages.value + m
            persistAll(_messages.value)
            return
        }
        // Debian 环境未初始化时禁止使用 Agent
        if (!ProotDebian.isReady()) {
            val m = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = "⚠️ Debian 终端环境尚未初始化。\n请到「设置 → 终端环境」中点击「初始化 Debian 终端」完成下载后再使用 Agent。",
                createdAt = System.currentTimeMillis()
            )
            _messages.value = _messages.value + m
            persistAll(_messages.value)
            return
        }
        val engine = AgentEngine(dir)
        val goalMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = goal,
            createdAt = System.currentTimeMillis()
        )
        _messages.value = _messages.value + goalMsg
        _isStreaming.value = true
        startedAt = System.currentTimeMillis()

        job = viewModelScope.launch {
            val s = settings.get()
            val sys = engine.systemPrompt()
            val reqHistory = mutableListOf(
                ChatReqMessage("system", sys),
                ChatReqMessage("user", goal)
            )
            var noActionStreak = 0
            try {
                loop@ for (step in 0 until AGENT_MAX_STEPS) {
                    currentAssistantId = UUID.randomUUID().toString()
                    assistantCreatedAt = System.currentTimeMillis()
                    _messages.value = _messages.value + ChatMessage(
                        id = currentAssistantId,
                        role = "assistant",
                        content = "（思考中…）",
                        createdAt = assistantCreatedAt
                    )
                    val reasoningSb = StringBuilder()
                    val full = StringBuilder()
                    var usage: Usage? = null
                    try {
                        client.streamChat(
                            s.baseUrl, s.apiKey, s.model, reqHistory.toList(), s.temperature
                        ).cancellable().collect { ev ->
                            when (ev) {
                                is StreamEvent.Reasoning -> reasoningSb.append(ev.text)
                                is StreamEvent.Content -> full.append(ev.text)
                                is StreamEvent.Done -> usage = ev.usage
                            }
                        }
                    } catch (e: Exception) {
                        updateAssistant { copy(content = "⚠️ 错误：${e.message}") }
                        break@loop
                    }

                    val parsed = engine.extractAction(full.toString())
                    if (parsed == null) {
                        // 本轮没有动作：把整段作为思考落定，继续下一轮
                        updateAssistant {
                            copy(
                                content = full.toString().ifBlank { "（无输出）" },
                                durationMs = System.currentTimeMillis() - startedAt
                            )
                        }
                        reqHistory += ChatReqMessage("assistant", full.toString())
                        noActionStreak++
                        if (noActionStreak >= AGENT_NO_ACTION_LIMIT) break@loop
                        continue@loop
                    }
                    noActionStreak = 0
                    val (action, thinking) = parsed
                    // thinking 字段混有推理内容——只显示真正跟动作相关的部分
                    val cleanThinking = thinking.lines()
                        .filter { !it.startsWith("思考：") && !it.startsWith("思考:") && it.isNotBlank() }
                        .joinToString("\n").trim()
                    updateAssistant {
                        copy(
                            content = cleanThinking.ifBlank { "（已执行动作）" },
                            durationMs = System.currentTimeMillis() - startedAt
                        )
                    }
                    if (action.tool == "done") {
                        reqHistory += ChatReqMessage("assistant", full.toString())
                        break@loop
                    }
                    val result = engine.execute(action)
                    val toolMsg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = "tool",
                        content = result.render(),
                        createdAt = System.currentTimeMillis()
                    )
                    _messages.value = _messages.value + toolMsg
                    reqHistory += ChatReqMessage("assistant", full.toString())
                    reqHistory += ChatReqMessage("tool", result.render())
                }
                persistAll(_messages.value)
            } finally {
                _isStreaming.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        _isStreaming.value = false
    }

    /** 当真实用量缺失时，按字符数粗略估算（约 4 字符 = 1 token） */
    private fun estimateUsage(
        recent: List<ChatMessage>,
        completionLen: Int
    ): TokenUsage {
        val promptChars = recent.sumOf { it.content.length }
        val promptTokens = (promptChars / 4).coerceAtLeast(1)
        val completionTokens = (completionLen / 4).coerceAtLeast(1)
        return TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens)
    }

    private fun updateAssistant(transform: ChatMessage.() -> ChatMessage) {
        _messages.value = _messages.value.map {
            if (it.id == currentAssistantId) it.transform() else it
        }
    }

    private fun persist(
        userMessages: List<ChatMessage>,
        assistantMsg: ChatMessage,
        firstUserText: String
    ) {
        val finalMessages = userMessages + assistantMsg
        val title = if (currentTitle == "新对话" && firstUserText.isNotBlank()) {
            firstUserText.take(20)
        } else {
            currentTitle
        }
        val prev = allConversations.find { it.id == conversationId }
        val updated = Conversation(
            id = conversationId,
            title = title,
            createdAt = prev?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = finalMessages,
            type = currentType,
            workDir = currentWorkDir
        )
        allConversations = (allConversations.filter { it.id != conversationId } + updated)
            .sortedByDescending { it.updatedAt }
        viewModelScope.launch { repo.saveConversations(allConversations) }
    }

    private fun persistAll(list: List<ChatMessage>) {
        val title = if (currentTitle == "新对话" || currentTitle == "Agent 任务") {
            list.firstOrNull { it.role == "user" }?.content?.take(20) ?: currentTitle
        } else {
            currentTitle
        }
        val prev = allConversations.find { it.id == conversationId }
        val updated = Conversation(
            id = conversationId,
            title = title,
            createdAt = prev?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = list,
            type = currentType,
            workDir = currentWorkDir
        )
        allConversations = (allConversations.filter { it.id != conversationId } + updated)
            .sortedByDescending { it.updatedAt }
        viewModelScope.launch { repo.saveConversations(allConversations) }
    }

    /** ViewModel 销毁时（如进程被终止）兜底保存已有消息，防止流式中断丢数据 */
    override fun onCleared() {
        super.onCleared()
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val title = if (currentTitle == "新对话" || currentTitle == "Agent 任务") {
            msgs.firstOrNull { it.role == "user" }?.content?.take(20) ?: currentTitle
        } else currentTitle
        val prev = allConversations.find { it.id == conversationId }
        val updated = Conversation(
            id = conversationId,
            title = title,
            createdAt = prev?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = msgs,
            type = currentType,
            workDir = currentWorkDir
        )
        allConversations = (allConversations.filter { it.id != conversationId } + updated)
            .sortedByDescending { it.updatedAt }
        runBlocking {
            repo.saveConversations(allConversations)
        }
    }
}
