package com.example.aichat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.usageStore by preferencesDataStore(name = "aichat_usage")

/** 累计 token 消耗（跨所有对话） */
data class TokenTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0
)

class TokenUsageStore(private val context: Context) {

    private val store = context.applicationContext.usageStore

    val totals: Flow<TokenTotals> = store.data.map { prefs ->
        TokenTotals(
            promptTokens = prefs[KEY_PROMPT] ?: 0,
            completionTokens = prefs[KEY_COMPLETION] ?: 0,
            totalTokens = prefs[KEY_TOTAL] ?: 0
        )
    }

    suspend fun add(usage: TokenUsage) {
        store.edit { prefs ->
            prefs[KEY_PROMPT] = (prefs[KEY_PROMPT] ?: 0) + usage.promptTokens
            prefs[KEY_COMPLETION] = (prefs[KEY_COMPLETION] ?: 0) + usage.completionTokens
            prefs[KEY_TOTAL] = (prefs[KEY_TOTAL] ?: 0) + usage.totalTokens
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    companion object {
        private val KEY_PROMPT = longPreferencesKey("prompt_tokens")
        private val KEY_COMPLETION = longPreferencesKey("completion_tokens")
        private val KEY_TOTAL = longPreferencesKey("total_tokens")
    }
}
