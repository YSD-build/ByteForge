<p align="center">
  <img src="icon.png" width="128" alt="ByteForge Logo" />
</p>

<h1 align="center">ByteForge</h1>
<p align="center">
  <strong>Android 上的自主 AI Agent · 内置 Debian 11 真容器 · Skill 生态</strong>
</p>

<p align="center">
  <a href="https://github.com/YSD-build/ByteForge/releases/latest"><img src="https://img.shields.io/github/v/release/YSD-build/ByteForge?label=latest" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/YSD-build/ByteForge" /></a>
  <a href="#"><img src="https://img.shields.io/badge/minSdk-24-blue" /></a>
  <a href="#"><img src="https://img.shields.io/badge/platform-Android-brightgreen" /></a>
</p>

---

## ✨ 特性

 - **🤖 自主 Agent**：给一个目标，Agent 自动规划 → 写文件 → 跑命令 → 直到完成（最多 14 步自动循环）
 - **🐧 Debian 11 真容器**：应用内置 proot + busybox，一键初始化 Debian 11 bullseye（ARM64），Agent 在真实 Linux 环境中执行
 - **🧠 深度思考**：始终开启推理过程，模型思考完全保留在上下文中
 - **🛠 Skill 商店**：免费下���聚合 Skill（Python 开发 / Web 前端 / 系统运维 / 数据分析），叠加系统提示扩展 Agent 能力
 - **📡 应用内更新**：走 GitHub Releases API 检查新版本，一键下载安装
 - **🌙 毛玻璃 UI**：深色渐变 + 半透明卡片 + 大圆角，全局统一风格
 - **📜 开源 MIT**：完全开放，社区共建

## 📸 截图

> 截图暂缺，欢迎 PR 贡献！

## 🚀 快速开始

### 下载安装

[**⬇️ 下载最新 APK**](https://github.com/YSD-build/ByteForge/releases/latest)

> APK ~110 MB（含 Debian 11 rootfs），解压后 Debian 环境 ~400 MB。

### 首次使用

1. **配置 API**：设置 → API 配置 → 填入你的 OpenAI 兼容接口地址和 Key
2. **初始化终端**：设置 → 终端环境 → 点击「初始化 Debian 终端」（约 3-5 分钟）
3. **新建 Agent**：主页点 + → 选择「Agent」→ 输入目标即可

### 普通聊天

主页点 + → 选择「聊天」→ 直接对话，深度思考始终开启。

## 🏗️ 构建

```bash
# 环境：JDK 17+, Android SDK 34, Gradle 8.9
export ANDROID_SDK_ROOT=/path/to/android-sdk
./gradlew assembleDebug
```

| 项 | 值 |
|---|---|
| compileSdk | 34 |
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 |
| Kotlin | 1.9.24 |
| Compose | BOM 2024.09.00 |

## 📂 项目结构

```
app/src/main/java/com/example/aichat/
├── AgentEngine.kt          # Agent 工具（写/读/删文件 + 跑命令）+ 风险检测
├── ChatViewModel.kt        # 聊天 + Agent 自主循环
├── CommandRunner.kt        # 进程执行器（ProcessBuilder + 限时）
├── ProotDebian.kt          # Debian 环境管理（proot + rootfs 解压）
├── UpdateChecker.kt        # GitHub Releases 版本更新
├── Skill.kt / SkillRepository.kt  # Skill 数据模型 + 存储
├── model.kt                # ChatMessage / Conversation 数据类
├── OpenAiClient.kt         # OpenAI SSE 流式客户端
└── ui/
    ├── ChatScreen.kt       # 聊天界面（气泡 + 智能滚动 + 推理卡片）
    ├── AgentScreen.kt      # Agent 工作台（目标→思考→动作日志）
    ├── ConversationsScreen.kt  # 对话列表 + 新建选择器
    ├── SettingsScreen.kt   # 设置（API/参数/终端/Skill/更新）
    ├── TerminalView.kt     # 交互式 sh 终端组件
    ├── Theme.kt            # 毛玻璃深色主题
    └── AppNavigation.kt    # 路由
```

## 📦 依赖

| 库 | 用途 |
|---|---|
| Jetpack Compose + Material3 | UI |
| OkHttp | SSE 流式 + 文件下载 |
| Gson | JSON 序列化 |
| DataStore | 本地持久化 |
| Navigation Compose | 路由 |
| tukaani/xz | 纯 Java XZ 解压 |
| Termux proot + loader | Debian 容器运行时 |

## 🤝 贡献

欢迎 PR！Issue 和 Discussion 都可以。

## 📜 许可证

MIT © 2026 ByteForge

---

<p align="center">Made with ❤️ by YSD-build</p>
