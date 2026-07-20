package com.example.aichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichat.AIChatApplication
import com.example.aichat.AppSettings
import com.example.aichat.ProotDebian
import com.example.aichat.SettingsViewModel
import com.example.aichat.Skill
import com.example.aichat.SkillRepository
import com.example.aichat.TokenUsageStore
import com.example.aichat.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    usageStore: TokenUsageStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AIChatApplication
    val vm: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(settings, usageStore) as T
        }
    )
    val data by vm.settingsData.collectAsState()
    val totals by vm.tokenTotals.collectAsState()
    var baseUrl by remember { mutableStateOf(data.baseUrl) }
    var apiKey by remember { mutableStateOf(data.apiKey) }
    var model by remember { mutableStateOf(data.model) }
    var temperature by remember { mutableStateOf(data.temperature) }
    var maxContext by remember { mutableStateOf(data.maxContextMessages) }
    var showReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 终端环境状态
    val debState by ProotDebian.state.collectAsState(initial = ProotDebian.State.NOT_INITIALIZED)
    val debProgress by ProotDebian.progress.collectAsState(initial = "")

    // Skill 商店
    val skillRepo = remember { app.skillRepository }
    val installedSkills by skillRepo.skills.collectAsState(initial = emptyList())
    var skillDialogOpen by remember { mutableStateOf(false) }

    // 更新检查
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    LaunchedEffect(data) {
        baseUrl = data.baseUrl; apiKey = data.apiKey; model = data.model
        temperature = data.temperature; maxContext = data.maxContextMessages
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = { vm.save(baseUrl, apiKey, model, temperature, maxContext); onBack() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .navigationBarsPadding()
                ) { Text("保存") }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            // === API 配置 ===
            SectionTitle("API 配置")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("API 地址") }, placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        label = { Text("模型名称") }, placeholder = { Text("gpt-3.5-turbo") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            // === 对话参数 ===
            Spacer(Modifier.height(24.dp))
            SectionTitle("对话参数")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("对话强度（Temperature）：${"%.1f".format(temperature)}", style = MaterialTheme.typography.labelLarge)
                    Slider(value = temperature.toFloat(), onValueChange = { temperature = it.toDouble() },
                        valueRange = 0f..2f, steps = 19)
                    Text("数值越低越保守严谨，越高越发散有创意。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                    Text("上下文窗口：$maxContext 条消息", style = MaterialTheme.typography.labelLarge)
                    Slider(value = maxContext.toFloat(), onValueChange = { maxContext = it.toInt() },
                        valueRange = 4f..40f, steps = 35)
                    Text("每次请求只携带最近的 N 条历史消息，越大越能记住前文但更耗 token。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // === 终端环境（Debian 11） ===
            Spacer(Modifier.height(24.dp))
            SectionTitle("终端环境")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Debian 11 真容器（proot）", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (debState) {
                            ProotDebian.State.NOT_INITIALIZED -> "未初始化 — 所有组件已预置，解压后约 400 MB"
                            ProotDebian.State.COPYING -> "⏳ $debProgress"
                            ProotDebian.State.EXTRACTING -> "📦 $debProgress"
                            ProotDebian.State.READY -> "✅ Debian 11 终端就绪"
                            ProotDebian.State.ERROR -> "⚠️ 初始化失败：${debProgress.ifBlank { "请重试" }}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val initReady = debState != ProotDebian.State.COPYING && debState != ProotDebian.State.EXTRACTING
                    if (debState != ProotDebian.State.READY) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scope.launch { ProotDebian.initialize(context) } }, enabled = initReady) {
                            Text(if (debState == ProotDebian.State.ERROR) "重新初始化" else "初始化 Debian 终端")
                        }
                    }
                }
            }

            // === Skill 商店 ===
            Spacer(Modifier.height(24.dp))
            SectionTitle("Skill 商店")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("已安装 ${installedSkills.count { it.installed }} 个 Skill", style = MaterialTheme.typography.bodyLarge)
                    installedSkills.filter { it.installed }.forEach { skill ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(skill.description.take(40), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (skill.enabled) Text("✓", color = MaterialTheme.colorScheme.primary) else Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { skillDialogOpen = true }) { Text("浏览 Skill 商店") }
                }
            }

            // === 软件更新 ===
            Spacer(Modifier.height(24.dp))
            SectionTitle("软件更新")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前版本：v2.0", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            val info = UpdateChecker.checkUpdate(5)
                            updateInfo = info
                        }
                    }) { Text("检查更新") }
                }
            }

            // === 用量统计 ===
            Spacer(Modifier.height(24.dp))
            SectionTitle("用量统计")
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    StatRow("输入（prompt）", "%,d".format(totals.promptTokens))
                    Spacer(Modifier.height(8.dp))
                    StatRow("输出（completion）", "%,d".format(totals.completionTokens))
                    Spacer(Modifier.height(8.dp))
                    StatRow("合计", "%,d".format(totals.totalTokens), highlight = true)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showReset = true }) { Text("重置统计") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "支持任意 OpenAI 兼容接口（含 DeepSeek / 本地 Ollama 等）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // --- 弹窗 ---
    if (showReset) {
        AlertDialog(onDismissRequest = { showReset = false },
            title = { Text("重置统计") },
            text = { Text("确定清空 Token 消耗统计吗？不可恢复。") },
            confirmButton = { TextButton(onClick = { showReset = false; vm.resetUsage() }) { Text("重置") } },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("取消") } })
    }

    if (skillDialogOpen) {
        SkillStoreDialog(skillRepo = skillRepo, onDismiss = { skillDialogOpen = false })
    }

    if (updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本 ${info.tagName}") },
            text = { Text(info.body.ifBlank { "有新版本可用。" }) },
            confirmButton = {
                TextButton(onClick = {
                    updateInfo = null
                    scope.launch {
                        try { UpdateChecker.downloadAndInstall(context, info) } catch (e: Exception) { }
                    }
                }) { Text("下载更新 (${info.size / 1048576}MB)") }
            },
            dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("跳过") } })
    }
}

// --- Skill 商店弹窗 ---
@Composable
private fun SkillStoreDialog(skillRepo: SkillRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaultSkills = remember {
        listOf(
            Skill("py", "Python 开发", "Python 项目开发、pip/venv 管理、代码规范", "开发",
                "你是一个 Python 专家。请遵循 PEP 8 规范，善用 pip / venv 工具，编写清晰可读的代码。"),
            Skill("web", "Web 前端", "HTML/CSS/JS 开发、React/Vue 组件编写", "开发",
                "你是一个 Web 前端专家。善用现代 HTML/CSS/JS，输出结构化、可访问的代码。"),
            Skill("ops", "系统运维", "Linux 运维、Shell 脚本、服务部署", "运维",
                "你是一个 Linux 运维专家。善用 systemd、docker、nginx 等工具，输出安全的运维脚本。"),
            Skill("data", "数据分析", "数据处理、pandas/NumPy、可视化", "数据",
                "你是一个数据分析专家。善用 Python pandas、NumPy、Matplotlib 进行数据清理和可视化。")
        )
    }
    val installed by skillRepo.skills.collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skill 商店") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("免费下载 · 开源社区贡献", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                defaultSkills.forEach { sk ->
                    val isInstalled = installed.any { it.id == sk.id && it.installed }
                    ElevatedCard {
                        Column(Modifier.padding(12.dp)) {
                            Text("${sk.name}  ·  ${sk.category}", style = MaterialTheme.typography.labelLarge)
                            Text(sk.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                scope.launch { skillRepo.installSkill(sk) }
                            }, enabled = !isInstalled) {
                                Text(if (isInstalled) "已安装" else "免费下载")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StatRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (highlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
