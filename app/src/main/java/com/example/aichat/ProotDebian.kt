package com.example.aichat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * ByteForge Debian 11 终端环境。所有组件预置在 APK assets，零网络。
 */
object ProotDebian {

    enum class State { NOT_INITIALIZED, COPYING, EXTRACTING, READY, ERROR }
    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val progress: StateFlow<String> get() = _progress
    private val _progress = MutableStateFlow("")

    // 持久化记录：App 重启后能从 filesDir 推断状态
    private var filesPath = ""
    @Volatile private var stateInited = false

    /** 初始化状态流（App 启动时调用一次，读取已存在的文件系统状态） */
    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED
    }

    private fun binDir()  = File(filesPath, "bin")
    private fun proot()   = File(binDir(), "proot")
    private fun busybox() = File(binDir(), "busybox")
    private fun rootDir() = File(filesPath, "debian-rootfs")

    fun isReady(): Boolean = filesPath.isNotEmpty()
            && proot().exists() && busybox().exists()
            && File(rootDir(), "bin").exists()

    /** 完全离线初始化：从 APK assets 复制所有组件，解压 rootfs */
    suspend fun initialize(context: Context): Boolean {
        try {
            filesPath = context.filesDir.absolutePath
            binDir().mkdirs()
            _state.value = State.COPYING

            // 1. busybox
            if (!busybox().exists()) {
                _progress.value = "复制 busybox…"
                copyAsset(context, "busybox", busybox().absolutePath)
                busybox().setExecutable(true)
            }
            // 2. proot
            if (!proot().exists()) {
                _progress.value = "复制 proot…"
                copyAsset(context, "proot", proot().absolutePath)
                proot().setExecutable(true)
            }

            // 3. Debian rootfs（从 assets 复制 tar.xz → 解压）
            if (!File(rootDir(), "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                rootDir().mkdirs()
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz")
                copyAsset(context, "debian-rootfs.tar.xz", archive.absolutePath)
                val r = CommandRunner.runBare(
                    "${busybox().absolutePath} tar -xJf ${archive.absolutePath} -C ${rootDir().absolutePath}",
                    filesPath, 600_000
                )
                archive.delete()
                if (!r.ok) { _state.value = State.ERROR; _progress.value = "解压失败：${r.output}"; return false }
            }

            _state.value = State.READY; _progress.value = "Debian 11 终端就绪"; return true
        } catch (e: Exception) {
            _state.value = State.ERROR; _progress.value = "初始化失败：${e.message}"; return false
        }
    }

    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化")
        val p = proot().absolutePath; val r = rootDir().absolutePath
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        val full = "$p -r $r -b /dev -b /proc -b /sys -b /data:/data -b $cwd:$cwd --cwd=$cwd /bin/bash -c \"$esc\""
        return CommandRunner.runBare(full, cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
    }

    private fun copyAsset(context: Context, asset: String, dest: String) {
        context.assets.open(asset).use { i -> FileOutputStream(File(dest)).use { o -> i.copyTo(o) } }
    }
}
