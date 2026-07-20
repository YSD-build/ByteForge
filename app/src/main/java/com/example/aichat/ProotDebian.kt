package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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

    private var filesPath = ""
    @Volatile private var stateInited = false

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        refresh()
    }

    private fun refresh() {
        _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED
    }

    private fun binDir()  = File(filesPath, "bin")
    private fun proot()   = File(binDir(), "proot")
    private fun busybox() = File(binDir(), "busybox")
    private fun rootDir() = File(filesPath, "debian-rootfs")

    /** 判断 Debian 是否就绪：proot + busybox 存在 + rootfs 内有 bin（处理 Debian 11 的 bin→usr/bin 符号链接） */
    fun isReady(): Boolean {
        if (filesPath.isEmpty()) return false
        if (!proot().exists()) return false
        if (!busybox().exists()) return false
        val r = rootDir()
        // Debian 11 的 /bin 是指向 /usr/bin 的符号链接，两种都查
        return File(r, "bin").exists() || File(r, "usr/bin").exists()
    }

    /** 完全离线初始化：从 APK assets 复制所有组件，解压 rootfs */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
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

            // 3. Debian rootfs
            if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                rootDir().mkdirs()
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz")
                copyAsset(context, "debian-rootfs.tar.xz", archive.absolutePath)

                // 尝试 xz 和 gzip 两种压缩
                var r = CommandRunner.runBare(
                    "${busybox().absolutePath} tar -xJf ${archive.absolutePath} -C ${rootDir().absolutePath}",
                    filesPath, 600_000
                )
                if (!r.ok) {
                    _progress.value = "xz 解压失败，尝试 gzip…"
                    r = CommandRunner.runBare(
                        "${busybox().absolutePath} tar -xzf ${archive.absolutePath} -C ${rootDir().absolutePath}",
                        filesPath, 600_000
                    )
                }
                archive.delete()

                // 诊断：列出解压产物
                val listing = CommandRunner.runBare(
                    "${busybox().absolutePath} ls -la ${rootDir().absolutePath}/",
                    filesPath, 10_000
                ).output.take(500)
                val listingDeep = CommandRunner.runBare(
                    "${busybox().absolutePath} find ${rootDir().absolutePath}/ -maxdepth 3 -type d 2>/dev/null | head -30",
                    filesPath, 10_000
                ).output

                if (!r.ok) {
                    _progress.value = "解压失败：${r.output}\n目录内容：$listing"
                    _state.value = State.ERROR
                    return@withContext false
                }

                // 如果文件被包在一层目录里，挪出来
                val topDirs = rootDir().listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (topDirs.size == 1 && topDirs[0].name != "bin" && topDirs[0].name != "usr") {
                    val inner = topDirs[0]
                    _progress.value = "整理目录结构…"
                    CommandRunner.runBare(
                        "${busybox().absolutePath} mv ${inner.absolutePath}/* ${inner.absolutePath}/.* ${rootDir().absolutePath}/ 2>/dev/null; ${busybox().absolutePath} rmdir ${inner.absolutePath} 2>/dev/null",
                        filesPath, 30_000
                    )
                }

                // 如果仍是空的，打详细日志
                if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                    @Suppress("UNUSED_VARIABLE") val debug = listingDeep
                    _progress.value = "解压后无 usr/bin 目录。\ntar 输出：${r.output.take(200)}\n"
                    _state.value = State.ERROR
                    return@withContext false
                }
            }

            // 以文件系统实况为准，不盲设 READY
            val ok = isReady()
            _state.value = if (ok) State.READY else State.ERROR
            _progress.value = if (ok) "Debian 11 终端就绪"
                else "初始化异常：文件缺失。proot=${proot().exists()} busybox=${busybox().exists()} rootfsBin=${File(rootDir(), "usr/bin").exists()}"
            ok
        } catch (e: Exception) {
            _state.value = State.ERROR; _progress.value = "初始化失败：${e.message}"; false
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
