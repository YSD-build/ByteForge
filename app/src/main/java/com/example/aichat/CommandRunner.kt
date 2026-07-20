package com.example.aichat

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CommandRunner {

    fun run(cmd: String, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        if (cmd.isBlank()) return ToolResult(false, "命令为空")
        return if (ProotDebian.isReady()) ProotDebian.runInDebian(cmd, workDir, timeoutMs)
        else runBare(cmd, workDir, timeoutMs)
    }

    fun runBare(cmd: String, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        if (cmd.isBlank()) return ToolResult(false, "命令为空")
        return runArgs(listOf("sh", "-c", cmd), workDir, timeoutMs)
    }

    /** 通过 ProcessBuilder 直接传参数数组执行，不经过 shell */
    fun runArgs(args: List<String>, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        return try {
            val pb = ProcessBuilder(args).directory(File(workDir)).redirectErrorStream(true)
            exec(pb, timeoutMs)
        } catch (e: Exception) {
            ToolResult(false, "命令执行失败：${e.message}")
        }
    }

    /** 带环境变量的命令执行 */
    fun runBareEnv(args: List<String>, env: Map<String, String>, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        return try {
            val pb = ProcessBuilder(args).directory(File(workDir)).redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            exec(pb, timeoutMs)
        } catch (e: Exception) {
            ToolResult(false, "命令执行失败：${e.message}")
        }
    }

    private fun exec(pb: ProcessBuilder, timeoutMs: Long): ToolResult {
        val proc = pb.start()
        val out = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        val t = Thread { try { reader.forEachLine { out.appendLine(it) } } catch (_: Exception) {} }
        t.start()
        val latch = CountDownLatch(1)
        val watcher = Thread { try { proc.waitFor() } finally { latch.countDown() } }
        watcher.start()
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) { proc.destroy(); t.join(2000); return ToolResult(false, "超时（${timeoutMs}ms）：\n${out.toString().take(6000)}") }
        t.join(2000)
        val text = out.toString()
        val shown = if (text.length > 8000) text.take(8000) + "\n…(已截断)" else text
        return ToolResult(true, "退出码 ${proc.exitValue()}：\n$shown")
    }
}
