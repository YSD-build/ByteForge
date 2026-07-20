package com.example.aichat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
    private var binPath = ""
    @Volatile private var stateInited = false
    private const val CHANNEL_ID = "byteforge_debian"

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        binPath = getBinDir(context).apply { mkdirs() }.absolutePath
        createNotifyChannel(context)
        refresh()
    }

    /** 可执行目录：外存优先（通常不设 noexec），否则 filesDir/bin */
    private fun getBinDir(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null && ext.exists()) return File(ext, "toolbin")
        return File(filesPath, "bin")
    }

    /** 用 ProcessBuilder 直接执行二进制（不走 shell，避免 linker64 兼容问题） */
    private fun execBin(tool: String, bin: File, args: List<String>, cwd: String, timeoutMs: Long): ToolResult =
        CommandRunner.runArgs(listOf(bin.absolutePath, tool) + args, cwd, timeoutMs)
    private fun execBB(tool: String, args: List<String>, cwd: String, timeoutMs: Long): ToolResult =
        execBin(tool, busybox(), args, cwd, timeoutMs)

    private fun refresh() { _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED }
    private fun proot()   = File(binPath, "proot")
    private fun busybox() = File(binPath, "busybox")
    private fun rootDir() = File(filesPath, "debian-rootfs")

    fun isReady(): Boolean {
        if (filesPath.isEmpty()) return false
        if (!proot().exists()) return false
        if (!busybox().exists()) return false
        val r = rootDir()
        return File(r, "bin").exists() || File(r, "usr/bin").exists()
    }

    /** 检查是否有存储权限 */
    fun hasStoragePerm(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            filesPath = context.filesDir.absolutePath
            binPath = getBinDir(context).apply { mkdirs() }.absolutePath
            _state.value = State.COPYING

            if (!busybox().exists()) {
                _progress.value = "复制 busybox…"
                copyAsset(context, "busybox", busybox().absolutePath)
            }
            if (!proot().exists()) {
                _progress.value = "复制 proot…"
                copyAsset(context, "proot", proot().absolutePath)
            }

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

                if (!result.ok) { notify(context, false, result.output.take(200)); _progress.value = "解压失败：${result.output.take(300)}"; _state.value = State.ERROR; return@withContext false }

                val topDirs = rootDir().listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (topDirs.size == 1 && topDirs[0].name !in setOf("bin", "usr", "etc", "dev", "proc", "sys")) {
                    _progress.value = "整理目录…"
                    execBB("sh", listOf("-c", "cp -a '${topDirs[0].absolutePath}'/* '$r/' && rmdir '${topDirs[0].absolutePath}'"), filesPath, 60_000)
                    topDirs[0].deleteRecursively()
                }

                // 诊断：列出解压产物
                val lsOut = CommandRunner.runArgs(listOf(busybox().absolutePath, "ls", "-la", r), filesPath, 10_000).output.take(400)
                val findOut = CommandRunner.runArgs(listOf(busybox().absolutePath, "find", r, "-maxdepth", "2", "-type", "d"), filesPath, 10_000).output.take(600)

                if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                    _progress.value = "解压后无 usr/bin。tar退出=${result.ok}\n目录列表:\n$lsOut\n\n目录树:\n$findOut"
                    _state.value = State.ERROR; return@withContext false
                }
            }

            val ok = isReady()
            _state.value = if (ok) State.READY else State.ERROR
            _progress.value = if (ok) "Debian 11 终端就绪" else "异常：proot=${proot().exists()} busybox=${busybox().exists()} bin=${File(rootDir(), "usr/bin").exists()}"
            if (ok) notify(context, true, "Debian 11 终端就绪，Agent 已可用")
            else notify(context, false, _progress.value)
            ok
        } catch (e: Exception) {
            _state.value = State.ERROR; _progress.value = "初始化失败：${e.message}"; notify(context, false, e.message ?: ""); false
        }
    }

    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化")
        val r = rootDir().absolutePath
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        val args = listOf("-r", r, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/data:/data", "-b", "$cwd:$cwd", "--cwd=$cwd", "/bin/bash", "-c", esc)
        return execBin("proot", proot(), args, cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
    }

    // ===== internal =====

    private fun copyAsset(context: Context, asset: String, dest: String) {
        context.assets.open(asset).use { i -> FileOutputStream(File(dest)).use { o -> i.copyTo(o) } }
    }

    private fun createNotifyChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "终端环境", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Debian 初始化进度"
                }
            )
        }
    }

    private fun notify(context: Context, success: Boolean, msg: String) {
        try {
            val title = if (success) "✅ Debian 终端就绪" else "⚠️ Debian 初始化失败"
            NotificationManagerCompat.from(context).notify(1001,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(msg).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true).build()
            )
        } catch (_: Exception) {}
    }
}
