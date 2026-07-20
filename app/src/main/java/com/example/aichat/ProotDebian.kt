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
    private var linker = "/system/bin/linker64"
    @Volatile private var stateInited = false
    private const val CHANNEL_ID = "byteforge_debian"

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        binPath = File(filesPath, "bin").apply { mkdirs() }.absolutePath
        if (!File(linker).exists()) linker = "/system/bin/linker"
        createNotifyChannel(context)
        refresh()
    }

    /** 用系统动态链接器执行二进制，绕过 noexec 文件系统限制 */
    private fun ldCmd(bin: String, args: String): String = "$linker $bin $bin $args"
    private fun ldRun(bin: String, args: String, cwd: String, timeoutMs: Long) =
        CommandRunner.runBare(ldCmd(bin, args), cwd, timeoutMs)

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
            binPath = File(filesPath, "bin").apply { mkdirs() }.absolutePath
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

                var result = ldRun(busybox().absolutePath, "tar -xJf $a -C $r", filesPath, 600_000)
                if (!result.ok) {
                    _progress.value = "xz 失败，试 gzip…"
                    result = ldRun(busybox().absolutePath, "tar -xzf $a -C $r", filesPath, 600_000)
                }
                archive.delete()

                if (!result.ok) { notify(context, false, result.output.take(200)); _progress.value = "解压失败：${result.output.take(300)}"; _state.value = State.ERROR; return@withContext false }

                val topDirs = rootDir().listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (topDirs.size == 1 && topDirs[0].name !in setOf("bin", "usr", "etc", "dev", "proc", "sys")) {
                    _progress.value = "整理目录…"
                    ldRun(busybox().absolutePath, "tar -C \"${topDirs[0].absolutePath}\" -c . | tar -xC \"$r\"", filesPath, 60_000)
                    topDirs[0].deleteRecursively()
                }

                if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                    _progress.value = "解压后无 usr/bin 目录。"; _state.value = State.ERROR; notify(context, false, "解压后无 usr/bin"); return@withContext false
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
        val p = proot().absolutePath; val r = rootDir().absolutePath
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        return ldRun(p, "-r $r -b /dev -b /proc -b /sys -b /data:/data -b $cwd:$cwd --cwd=$cwd /bin/bash -c \"$esc\"", cwd, timeoutMs)
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
