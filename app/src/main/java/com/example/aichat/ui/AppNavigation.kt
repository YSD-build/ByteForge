package com.example.aichat.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aichat.AppSettings
import com.example.aichat.ChatRepository
import com.example.aichat.OpenAiClient
import com.example.aichat.TokenUsageStore

@Composable
fun AppNavigation(
    repo: ChatRepository,
    settings: AppSettings,
    client: OpenAiClient,
    usageStore: TokenUsageStore
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "conversations") {
        composable("conversations") {
            ConversationsScreen(
                repo = repo,
                onNewChat = { id -> nav.navigate("chat/$id") },
                onOpenChat = { id -> nav.navigate("chat/$id") },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable(
            route = "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { back ->
            val id = back.arguments?.getString("conversationId") ?: ""
            ChatScreen(
                conversationId = id,
                repo = repo,
                settings = settings,
                client = client,
                usageStore = usageStore,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                usageStore = usageStore,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
