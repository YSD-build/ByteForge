package com.example.aichat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "aichat_settings")

data class AppSettingsData(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    // 对话强度：0.0（保守）~ 2.0（发散）
    val temperature: Double = 0.7,
    // 上下文窗口：每次请求携带的最近消息条数
    val maxContextMessages: Int = 20
)

class AppSettings(context: Context) {

    private val store = context.applicationContext.settingsStore

    val data: Flow<AppSettingsData> = store.data.map { prefs ->
        AppSettingsData(
            baseUrl = prefs[KEY_BASE] ?: "https://api.openai.com/v1",
            apiKey = prefs[KEY_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: "gpt-3.5-turbo",
            temperature = prefs[KEY_TEMP]?.toDoubleOrNull() ?: 0.7,
            maxContextMessages = prefs[KEY_CTX]?.toIntOrNull() ?: 20
        )
    }

    suspend fun get(): AppSettingsData = data.first()

    suspend fun update(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxContextMessages: Int
    ) {
        store.edit { prefs ->
            prefs[KEY_BASE] = baseUrl.trim()
            prefs[KEY_KEY] = apiKey.trim()
            prefs[KEY_MODEL] = model.trim()
            prefs[KEY_TEMP] = temperature.toString()
            prefs[KEY_CTX] = maxContextMessages.toString()
        }
    }

    companion object {
        private val KEY_BASE = stringPreferencesKey("base_url")
        private val KEY_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_TEMP = stringPreferencesKey("temperature")
        private val KEY_CTX = stringPreferencesKey("max_context_messages")
    }
}
