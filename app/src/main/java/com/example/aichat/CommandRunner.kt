package com.example.aichat

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在指定工作目录内运行 shell 命令，捕获输出并限时。 */
object CommandRunner {

    /** Agent 调用入口 — 若 Debian 已初始化则走 proot，否则走 Android 原生 sh */
    fun run(cmd: String, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        if (cmd.isBlank()) return ToolResult(false, "命令为空")
        return if (ProotDebian.isReady()) {
            ProotDebian.runInDebian(cmd, workDir, timeoutMs)
        } else {
            runBare(cmd, workDir, timeoutMs)
        }
    }

    /** 直接在 Android 原生 sh 进程中执行（不经过 proot/Debian） */
    fun runBare(cmd: String, workDir: String, timeoutMs: Long = 30_000): ToolResult {
        if (cmd.isBlank()) return ToolResult(false, "命令为空")
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd)
                .directory(File(workDir))
                .redirectErrorStream(true)
            val proc = pb.start()
            val out = StringBuilder()
            val reader = proc.inputStream.bufferedReader()
            val t = Thread {
                try {
                    reader.forEachLine { out.appendLine(it) }
                } catch (_: Exception) {
                }
            }
            t.start()
            val latch = CountDownLatch(1)
            val watcher = Thread {
                try {
                    proc.waitFor()
                } finally {
                    latch.countDown()
                }
            }
            watcher.start()
            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                t.join(2000)
                return ToolResult(
                    false,
                    "命令超时（${timeoutMs}ms）已终止：\n${out.toString().take(6000)}"
                )
            }
            t.join(2000)
            val text = out.toString()
            val shown = if (text.length > 8000) text.take(8000) + "\n…(输出已截断)" else text
            ToolResult(true, "退出码 ${proc.exitValue()}：\n$shown")
        } catch (e: Exception) {
            ToolResult(false, "命令执行失败：${e.message}")
        }
    }
}
