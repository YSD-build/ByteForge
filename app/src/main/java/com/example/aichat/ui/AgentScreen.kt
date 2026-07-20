package com.example.aichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aichat.ChatMessage
import com.example.aichat.ChatViewModel

/**
 * Agent 工作台：显示自主智能体的对话（目标→思考→动作→结果循环），
 * 底部可展开真实终端面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    vm: ChatViewModel,
    workDir: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val messages by vm.messages.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    var text by remember { mutableStateOf("") }

    fun onSend() {
        val t = text.trim()
        if (t.isNotEmpty() && !isStreaming) {
            vm.sendAgentGoal(t)
            text = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent 工作台") },
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("给 Agent 一个目标指令…") },
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
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送目标")
                        }
                    }
                }
            }
        }
    ) { padding ->
        // 消息列表（目标→思考→动作→结果）
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(messages, key = { it.id }) { msg ->
                AgentMessageItem(msg)
            }
        }
    }
}

@Composable
private fun AgentMessageItem(msg: ChatMessage) {
    if (msg.role == "tool") {
        ToolResultItem(msg)
    } else {
        // user / assistant → 复用聊天气泡（含深度思考推理卡片）
        MessageBubble(msg)
    }
}

@Composable
private fun ToolResultItem(msg: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.95f)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "🛠 工具执行结果",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg.content.ifEmpty { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
