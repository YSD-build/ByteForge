package com.example.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ConversationsViewModel(private val repo: ChatRepository) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _conversations.value = repo.loadConversations().sortedByDescending { it.updatedAt }
        }
    }

    fun createConversation(type: String = "chat"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val workDir = if (type == "agent") repo.agentWorkspace(id) else null
        val title = if (type == "agent") "Agent 任务" else "新对话"
        val conv = Conversation(
            id = id, title = title, createdAt = now, updatedAt = now,
            type = type, workDir = workDir
        )
        val list = (_conversations.value + conv).sortedByDescending { it.updatedAt }
        _conversations.value = list
        viewModelScope.launch { repo.saveConversations(list) }
        return id
    }

    fun deleteConversation(id: String) {
        val list = _conversations.value.filter { it.id != id }
        _conversations.value = list
        viewModelScope.launch { repo.saveConversations(list) }
    }
}
