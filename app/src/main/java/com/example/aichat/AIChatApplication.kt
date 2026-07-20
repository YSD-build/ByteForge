package com.example.aichat

import android.app.Application

class AIChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProotDebian.initState(this)
    }
    val settings by lazy { AppSettings(this) }
    val chatRepository by lazy { ChatRepository(this) }
    val tokenUsageStore by lazy { TokenUsageStore(this) }
    val skillRepository by lazy { SkillRepository(this) }
}
