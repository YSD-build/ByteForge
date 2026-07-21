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
import org.tukaani.xz.XZInputStream
import java.io.*
import java.util.zip.GZIPInputStream

object ProotDebian {

    enum class State { NOT_INITIALIZED, COPYING, EXTRACTING, READY, ERROR }
    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val progress: StateFlow<String> get() = _progress
    private val _progress = MutableStateFlow("")
    private const val CHANNEL_ID = "byteforge_debian"

    private var filesPath = ""
    @Volatile private var stateInited = false

    fun initState(context: Context) {
        if (stateInited) return
        stateInited = true
        filesPath = context.filesDir.absolutePath
        createNotifyChannel(context)
        refresh()
    }

    private fun refresh() { _state.value = if (isReady()) State.READY else State.NOT_INITIALIZED }
    private fun rootDir() = File(filesPath, "debian-rootfs")

    /** 只看 rootfs 是否解压完成——这设备 jniLibs 不提取，只能走 Android sh */
    fun isReady(): Boolean {
        if (filesPath.isEmpty()) return false
        val r = rootDir()
        return File(r, "bin").exists() || File(r, "usr/bin").exists()
    }

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            filesPath = context.filesDir.absolutePath
            _state.value = State.COPYING

            if (!File(rootDir(), "usr/bin").exists() && !File(rootDir(), "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                rootDir().mkdirs()
                extractRootfs(context)
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

    private fun extractRootfs(context: Context) {
        val r = rootDir()
        val xz = try { XZInputStream(context.assets.open("debian-rootfs.tar.xz")) }
            catch (_: Exception) { null }
        val input: InputStream = xz ?: GZIPInputStream(context.assets.open("debian-rootfs.tar.xz"))
        input.use { untar(it, r) }

        val topDirs = r.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (topDirs.size == 1 && topDirs[0].name !in setOf("bin", "usr", "etc", "dev", "proc", "sys")) {
            _progress.value = "整理目录…"
            topDirs[0].listFiles()?.forEach { f -> f.renameTo(File(r, f.name)) }
            topDirs[0].delete()
        }
        if (!File(r, "usr/bin").exists() && !File(r, "bin").exists()) {
            val lsDirs = r.listFiles()?.map { "${if(it.isDirectory)"D" else "F"} ${it.name}" }?.joinToString("\n") ?: "(空)"
            throw RuntimeException("解压后无 usr/bin。根目录内容:\n$lsDirs")
        }
    }

    private fun untar(input: InputStream, dest: File) {
        val buf = ByteArray(512)
        var totalFiles = 0
        while (true) {
            var n = 0; while (n < 512) { val r = input.read(buf, n, 512 - n); if (r < 0) break; n += r }
            if (n < 512) break
            val name = String(buf, 0, 100).trimEnd('\u0000')
            if (name.isEmpty()) break
            val type = buf[156]
            val sizeStr = String(buf, 124, 12).trimEnd('\u0000', ' ')
            val size  = sizeStr.toLongOrNull(8) ?: 0L
            val padded = ((size + 511L) / 512L) * 512L
            if (totalFiles == 0) _progress.value = "解压中: $name"
            val entry = File(dest, name.removePrefix("./"))
            when (type.toInt()) {
                '5'.code -> { entry.mkdirs(); skip(input, padded) }
                '0'.code, 0 -> {
                    entry.parentFile?.mkdirs()
                    FileOutputStream(entry).use { out ->
                        var remain = size; val dbuf = ByteArray(8192)
                        while (remain > 0) {
                            val chunk = input.read(dbuf, 0, minOf(remain, dbuf.size.toLong()).toInt())
                            if (chunk < 0) break; out.write(dbuf, 0, chunk); remain -= chunk
                        }
                    }
                    skip(input, padded - size)
                }
                else -> skip(input, padded)
            }
            if (totalFiles++ % 1000 == 0) _progress.value = "解压中: ${totalFiles} 文件…"
        }
        _progress.value = "解压完成：$totalFiles 文件"
    }

    private fun skip(input: InputStream, count: Long) {
        var s = 0L; val buf = ByteArray(8192)
        while (s < count) { val r = input.read(buf, 0, minOf(count - s, buf.size.toLong()).toInt()); if (r < 0) break; s += r }
    }

    private fun String.toLongOrNull(radix: Int): Long? =
        runCatching { trim().takeIf { it.isNotEmpty() }?.toLong(radix) }.getOrNull()

    /** 命令执行——该设备 filesDir 全 noexec，直接用 Android 系统 sh */
    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        return CommandRunner.runBare("cd '$cwd' && $esc", cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
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
