package com.example.aichat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import com.google.gson.reflect.TypeToken
import java.io.File

private val Context.chatStore by preferencesDataStore(name = "aichat_chat")

class ChatRepository(private val context: Context) {

    private val store = context.applicationContext.chatStore
    private val gson = Gson()
    private val listType = object : TypeToken<List<Conversation>>() {}.type

    suspend fun loadConversations(): List<Conversation> {
        val raw = store.data.first()[KEY_CONVERSATIONS] ?: return emptyList()
        return runCatching { gson.fromJson<List<Conversation>>(raw, listType) }
            .getOrDefault(emptyList())
    }

    suspend fun saveConversations(list: List<Conversation>) {
        store.edit { prefs ->
            prefs[KEY_CONVERSATIONS] = gson.toJson(list)
        }
    }

    /** 为指定 Agent 对话创建（或取回）应用私有工作目录，返回真实文件系统路径 */
    fun agentWorkspace(id: String): String {
        val dir = File(context.applicationContext.filesDir, "agent/$id")
        dir.mkdirs()
        return dir.absolutePath
    }

    companion object {
        private val KEY_CONVERSATIONS = stringPreferencesKey("conversations")
    }
}
