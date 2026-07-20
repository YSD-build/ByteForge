package com.example.aichat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private const val CHANNEL_ID = "byteforge_debian"

    private var filesPath = ""
    private var binPath = ""
    @Volatile private var stateInited = false

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        binPath = context.applicationInfo.nativeLibraryDir
        createNotifyChannel(context)
        refresh()
    }

    private fun refresh() { _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED }

    private fun proot()   = File(binPath, "libproot_bf.so")
    private fun busybox() = File(binPath, "libbusybox_bf.so")
    private fun rootDir() = File(filesPath, "debian-rootfs")

    private fun execBin(tool: String, bin: File, args: List<String>, cwd: String, timeoutMs: Long): ToolResult =
        CommandRunner.runArgs(listOf(bin.absolutePath, tool) + args, cwd, timeoutMs)
    private fun execBB(tool: String, args: List<String>, cwd: String, timeoutMs: Long): ToolResult =
        execBin(tool, busybox(), args, cwd, timeoutMs)

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
            binPath = context.applicationInfo.nativeLibraryDir
            _state.value = State.COPYING

            // busybox/proot 已在 jniLibs 中，安装时自动解到 nativeLibraryDir

            if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                rootDir().mkdirs()
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz")
                copyAsset(context, "debian-rootfs.tar.xz", archive.absolutePath)
                val a = archive.absolutePath; val r = rootDir().absolutePath

                var result = execBB("tar", listOf("-xJf", a, "-C", r), filesPath, 600_000)
                if (!result.ok) {
                    _progress.value = "xz 失败，试 gzip…"
                    result = execBB("tar", listOf("-xzf", a, "-C", r), filesPath, 600_000)
                }
                archive.delete()
                if (!result.ok) {
                    _progress.value = "解压失败：${result.output.take(300)}"
                    _state.value = State.ERROR; return@withContext false
                }

                val topDirs = rootDir().listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (topDirs.size == 1 && topDirs[0].name !in setOf("bin", "usr", "etc", "dev", "proc", "sys")) {
                    _progress.value = "整理目录…"
                    execBB("sh", listOf("-c", "cp -a '${topDirs[0].absolutePath}'/* '$r/' 2>/dev/null; rmdir '${topDirs[0].absolutePath}' 2>/dev/null"), filesPath, 60_000)
                }

                val lsOut = CommandRunner.runArgs(listOf(busybox().absolutePath, "ls", "-la", r), filesPath, 10_000).output.take(400)
                val findOut = CommandRunner.runArgs(listOf(busybox().absolutePath, "find", r, "-maxdepth", "2", "-type", "d"), filesPath, 10_000).output.take(600)
                if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                    _progress.value = "解压后无 usr/bin。tar退出=${result.ok}\n$lsOut\n\n$findOut"
                    _state.value = State.ERROR; return@withContext false
                }
            }

            val ok = isReady()
            _state.value = if (ok) State.READY else State.ERROR
            _progress.value = if (ok) "Debian 11 终端就绪" else "异常"
            if (ok) notify(context, true, "Debian 11 终端就绪，Agent 已可用")
            ok
        } catch (e: Exception) {
            _state.value = State.ERROR; _progress.value = "初始化失败：${e.message}"; false
        }
    }

    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化")
        val r = rootDir().absolutePath
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        return execBin("proot", proot(), listOf("-r", r, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/data:/data", "-b", "$cwd:$cwd", "--cwd=$cwd", "/bin/bash", "-c", esc), cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
    }

    private fun copyAsset(context: Context, asset: String, dest: String) {
        context.assets.open(asset).use { i -> FileOutputStream(File(dest)).use { o -> i.copyTo(o) } }
    }

    private fun createNotifyChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "终端环境", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notify(context: Context, success: Boolean, msg: String) {
        try {
            val title = if (success) "✅ Debian 终端就绪" else "⚠️ Debian 初始化失败"
            NotificationManagerCompat.from(context).notify(1001,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(msg)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
