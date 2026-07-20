package com.example.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: AppSettings,
    private val usageStore: TokenUsageStore
) : ViewModel() {

    val settingsData: StateFlow<AppSettingsData> = settings.data.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettingsData("https://api.openai.com/v1", "", "gpt-3.5-turbo")
    )

    val tokenTotals: StateFlow<TokenTotals> = usageStore.totals.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TokenTotals()
    )

    fun save(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxContextMessages: Int
    ) {
        viewModelScope.launch {
            settings.update(baseUrl, apiKey, model, temperature, maxContextMessages)
        }
    }

    fun resetUsage() {
        viewModelScope.launch { usageStore.reset() }
    }
}
