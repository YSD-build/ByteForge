package com.example.aichat.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.AIChatApplication
import com.example.aichat.AppSettings
import com.example.aichat.ChatMessage
import com.example.aichat.ChatRepository
import com.example.aichat.ChatViewModel
import com.example.aichat.OpenAiClient
import com.example.aichat.TokenUsageStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    repo: ChatRepository,
    settings: AppSettings,
    client: OpenAiClient,
    usageStore: TokenUsageStore,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val activity = LocalContext.current as ComponentActivity
    val vm: ChatViewModel = viewModel(
        viewModelStoreOwner = activity,
        key = "chat_$conversationId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(repo, settings, client, usageStore) as T
        }
    )
    val conversationType by vm.conversationType.collectAsState()
    val workDir by vm.workDir.collectAsState()

    LaunchedEffect(conversationId) { vm.load(conversationId) }

    if (conversationType == "agent") {
        AgentScreen(
            vm = vm,
            workDir = workDir,
            onBack = onBack,
            onOpenSettings = onOpenSettings
        )
    } else {
        ChatHome(
            vm = vm,
            onBack = onBack,
            onOpenSettings = onOpenSettings
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHome(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val messages by vm.messages.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    // 是否贴底：仅在用户已位于底部时才自动跟随，避免打断上滑阅读
    val isAtBottom by remember {
        derivedStateOf {
            val lastIndex = messages.lastIndex
            if (lastIndex < 0) true
            else {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                lastVisible.index >= lastIndex &&
                    lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 4
            }
        }
    }
    val shouldFollow = remember { mutableStateOf(true) }
    LaunchedEffect(isAtBottom) { shouldFollow.value = isAtBottom }

    // 新消息到达：平滑滚到底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && shouldFollow.value) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    // 流式输出过程中：持续贴底（瞬时滚动，避免逐字抖动）
    val lastLen = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(lastLen) {
        if (lastLen > 0 && shouldFollow.value && isStreaming) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    fun onSend() {
        val t = text.trim()
        if (t.isNotEmpty() && !isStreaming) {
            shouldFollow.value = true
            vm.send(t)
            text = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.widthIn(8.dp))
                    if (isStreaming) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Filled.Close, contentDescription = "停止")
                        }
                    } else {
                        FilledIconButton(
                            enabled = text.isNotBlank(),
                            onClick = { onSend() }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyChat()
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = padding,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                }
            }

            // 上滑阅读时出现的「回到底部」按钮
            AnimatedVisibility(
                visible = !isAtBottom && messages.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FilledIconButton(
                    onClick = {
                        shouldFollow.value = true
                        scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                    }
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到底部")
                }
            }
        }
    }
}

@Composable
private fun EmptyChat() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.height(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "开始一段新对话",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "在下方输入消息，与 AI 聊聊。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.82f)) {
            // 深度思考的推理过程卡片（始终开启）
            if (!isUser && msg.reasoningContent.isNotBlank()) {
                ReasoningCard(text = msg.reasoningContent)
                Spacer(Modifier.height(4.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        if (isUser)
                            RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)   // 尾巴在右下
                        else
                            RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)   // 尾巴在左下
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val display = if (msg.content.isEmpty()) "…" else msg.content
                Text(
                    text = display,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            // 本次回复的 token 消耗与耗时
            if (!isUser && (msg.usage != null || msg.durationMs != null)) {
                Spacer(Modifier.height(4.dp))
                Footer(msg)
            }
        }
    }
}

@Composable
fun ReasoningCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = "💡 思考过程",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
fun Footer(msg: ChatMessage) {
    val tokens = msg.usage?.totalTokens
    val seconds = msg.durationMs?.let { "%.1f".format(it / 1000.0) }
    val parts = buildList {
        add("本次回复")
        if (tokens != null && tokens > 0) add("· $tokens tokens")
        if (seconds != null) add("· $seconds 秒")
    }
    Text(
        text = parts.joinToString(" "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp)
    )
}
