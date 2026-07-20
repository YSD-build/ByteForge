package com.example.aichat

import org.json.JSONObject
import java.io.File
import kotlin.text.RegexOption

/** Agent 单步动作 */
data class Action(
    val tool: String,
    val path: String? = null,
    val content: String? = null,
    val cmd: String? = null
)

/** 工具执行结果 */
data class ToolResult(val ok: Boolean, val output: String) {
    fun render(): String = if (ok) "✅ $output" else "⚠️ $output"
}

/**
 * Agent 执行引擎：把模型输出解析成单步动作，并在工作目录内安全地执行。
 * 工具：write_file / read_file / delete_file / run_command / done。
 */
class AgentEngine(private val workDir: String) {

    /** 给模型的系统提示：定义角色、工具与 ACTION 协议 */
    fun systemPrompt(): String = """
你是一个运行在用户设备上的「自主智能体（Agent）」。你拥有一个真实的工作目录（即你当前所在的目录），可以在其中创建、读取、修改、删除文件，并运行 shell 命令。

你的目标：根据用户给出的目标，自主规划并一步步完成，直到目标达成。

每一步你必须输出：
1. 一段简短的中文思考（你在想什么、准备做什么）；
2. 紧接着恰好一行动作指令（格式严格如下，不要输出多行 JSON，也不要在动作前后附加额外说明）：

ACTION: {"tool":"工具名","参数名":"值"}

可用工具：
- 写文件：ACTION: {"tool":"write_file","path":"相对路径/文件名","content":"文件内容"}
- 读文件：ACTION: {"tool":"read_file","path":"相对路径/文件名"}
- 删文件：ACTION: {"tool":"delete_file","path":"相对路径/文件名"}
- 运行命令：ACTION: {"tool":"run_command","cmd":"要在工作目录执行的 shell 命令"}
- 完成：ACTION: {"tool":"done"}

规则：
- 路径一律使用相对于工作目录的「相对路径」，禁止使用 ".." 或绝对路径。
- 每轮只输出一个 ACTION。
- 完成全部工作后再输出 ACTION: {"tool":"done"}。
- 用中文与用户沟通，保持简洁。
""".trimIndent()

    /** 从模型完整输出中解析出 ACTION 与思考文本。解析失败返回 null。 */
    fun extractAction(full: String): Pair<Action, String>? {
        if (full.isBlank()) return null
        val regex = Regex("""ACTION:\s*(\{.*\})""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(full) ?: return null
        val jsonStr = match.groupValues[1]
        val thinking = full.substring(0, match.range.first).trim()
        return try {
            val o = JSONObject(jsonStr)
            val action = Action(
                tool = o.optString("tool", ""),
                path = if (o.has("path")) o.optString("path") else null,
                content = if (o.has("content")) o.optString("content") else null,
                cmd = if (o.has("cmd")) o.optString("cmd") else null
            )
            action to thinking
        } catch (_: Exception) {
            null
        }
    }

    /** 执行一个动作 */
    fun execute(action: Action): ToolResult {
        return when (action.tool) {
            "write_file" -> writeFile(action.path, action.content)
            "read_file" -> readFile(action.path)
            "delete_file" -> deleteFile(action.path)
            "run_command" -> CommandRunner.run(action.cmd ?: "", workDir)
            "done" -> ToolResult(true, "任务完成。")
            else -> ToolResult(false, "未知工具：${action.tool}")
        }
    }

    /** 把相对路径解析为工作目录内的安全文件，拒绝越界 */
    private fun safeFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val rel = path.trim().removePrefix("/").replace("\\", "/")
        if (rel.contains("..")) return null
        val base = File(workDir).canonicalFile
        val target = File(base, rel).canonicalFile
        val basePath = base.path + File.separator
        if (target.path != base.path && !target.path.startsWith(basePath)) return null
        return target
    }

    private fun writeFile(path: String?, content: String?): ToolResult {
        val f = safeFile(path) ?: return ToolResult(false, "路径不合法或越界：$path")
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content ?: "")
            val rel = f.relativeTo(File(workDir))?.path ?: f.path
            ToolResult(true, "已写入 $rel（${content?.length ?: 0} 字符）")
        } catch (e: Exception) {
            ToolResult(false, "写入失败：${e.message}")
        }
    }

    private fun readFile(path: String?): ToolResult {
        val f = safeFile(path) ?: return ToolResult(false, "路径不合法或越界：$path")
        if (!f.exists()) return ToolResult(false, "文件不存在：$path")
        if (f.isDirectory) return ToolResult(false, "是目录不是文件：$path\n目录内容：\n${f.listFiles()?.joinToString("\n") { "  ${it.name}" } ?: "(空)"}")
        return try {
            val text = f.readText()
            val preview = if (text.length > 4000) text.take(4000) + "\n…(已截断)" else text
            val rel = f.relativeTo(File(workDir))?.path ?: f.path
            ToolResult(true, "读取 $rel：\n$preview")
        } catch (e: Exception) {
            ToolResult(false, "读取失败：${e.message}")
        }
    }

    private fun deleteFile(path: String?): ToolResult {
        val f = safeFile(path) ?: return ToolResult(false, "路径不合法或越界：$path")
        if (!f.exists()) return ToolResult(false, "文件不存在：$path")
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (ok) ToolResult(true, "已删除：$path") else ToolResult(false, "删除失败：$path")
    }
}
