package com.example.aichat

import android.app.Application

class AIChatApplication : Application() {
    val settings by lazy { AppSettings(this) }
    val chatRepository by lazy { ChatRepository(this) }
    val tokenUsageStore by lazy { TokenUsageStore(this) }
    val skillRepository by lazy { SkillRepository(this) }
}
