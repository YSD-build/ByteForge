package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ProotDebian {

    enum class State { NOT_INITIALIZED, COPYING, EXTRACTING, READY, ERROR }
    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val progress: StateFlow<String> get() = _progress
    private val _progress = MutableStateFlow("")

    private var filesPath = ""
    // 二进制存放目录：Android 10+ 优先用 nativeLibraryDir（可执行），否则用 filesDir/bin
    private var binPath = ""
    @Volatile private var stateInited = false

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        binPath = pickBinDir(context).apply { File(this).mkdirs() }.absolutePath
        refresh()
    }

    private fun pickBinDir(ctx: Context): File {
        val native = File(ctx.applicationInfo.nativeLibraryDir)
        return if (native.exists()) File(native, "byteforge") else File(filesPath, "bin")
    }

    private fun refresh() { _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED }

    private fun proot()   = File(binPath, "proot")
    private fun busybox() = File(binPath, "busybox")
    private fun rootDir() = File(filesPath, "debian-rootfs")

    fun isReady(): Boolean {
        if (filesPath.isEmpty() || binPath.isEmpty()) return false
        if (!proot().exists()) return false
        if (!busybox().exists()) return false
        val r = rootDir()
        return File(r, "bin").exists() || File(r, "usr/bin").exists()
    }

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            filesPath = context.filesDir.absolutePath
            binPath = pickBinDir(context).apply { mkdirs() }.absolutePath
            _state.value = State.COPYING

            if (!busybox().exists()) {
                _progress.value = "复制 busybox…"
                copyAsset(context, "busybox", busybox().absolutePath)
                busybox().setExecutable(true)
            }
            if (!proot().exists()) {
                _progress.value = "复制 proot…"
                copyAsset(context, "proot", proot().absolutePath)
                proot().setExecutable(true)
            }

            if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                rootDir().mkdirs()
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz")
                copyAsset(context, "debian-rootfs.tar.xz", archive.absolutePath)

                var r = CommandRunner.runBare(
                    "${busybox().absolutePath} tar -xJf ${archive.absolutePath} -C ${rootDir().absolutePath}",
                    filesPath, 600_000
                )
                if (!r.ok) {
                    _progress.value = "xz 失败，试 gzip…"
                    r = CommandRunner.runBare(
                        "${busybox().absolutePath} tar -xzf ${archive.absolutePath} -C ${rootDir().absolutePath}",
                        filesPath, 600_000
                    )
                }
                archive.delete()

                if (!r.ok) {
                    _progress.value = "解压失败：${r.output.take(300)}"
                    _state.value = State.ERROR; return@withContext false
                }

                // 子目录扁平化
                val topDirs = rootDir().listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (topDirs.size == 1 && topDirs[0].name !in setOf("bin", "usr", "etc", "dev", "proc", "sys")) {
                    _progress.value = "整理目录…"
                    CommandRunner.runBare(
                        "${busybox().absolutePath} sh -c 'cd \"${topDirs[0].absolutePath}\" && mv * .* \"${rootDir().absolutePath}/\" 2>/dev/null' ; rmdir \"${topDirs[0].absolutePath}\" 2>/dev/null",
                        filesPath, 30_000
                    )
                }

                if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                    _progress.value = "解压后无 usr/bin 目录。退出码=${r.ok} 输出=${r.output.take(300)}"
                    _state.value = State.ERROR; return@withContext false
                }
            }

            val ok = isReady()
            _state.value = if (ok) State.READY else State.ERROR
            _progress.value = if (ok) "Debian 11 终端就绪"
                else "初始化异常：proot=${proot().exists()} busybox=${busybox().exists()} bin=${File(rootDir(), "usr/bin").exists()}"
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
