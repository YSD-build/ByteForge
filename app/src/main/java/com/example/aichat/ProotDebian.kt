package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ByteForge Debian 11 终端环境。
 * busybox 和 proot 二进制已预置在 APK assets 中，初始化时直接复制（零网络）。
 * 仅 Debian rootfs 需要下载（~120MB，从 LXC 镜像）。
 */
object ProotDebian {

    enum class State { NOT_INITIALIZED, DOWNLOADING, EXTRACTING, READY, ERROR }
    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val progress: StateFlow<String> get() = _progress
    private val _progress = MutableStateFlow("")

    private var rootfsDir = ""
    private var binDir = ""
    private var prootBin = ""
    private var busyboxBin = ""

    // rootfs 下载源（LXC 官方 + 国内镜像）
    private val rootfsMirrors = listOf(
        "https://images.linuxcontainers.org/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.aliyun.com/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.ustc.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(900, TimeUnit.SECONDS).build()

    fun isReady(): Boolean =
        prootBin.isNotEmpty() && File(prootBin).exists() &&
        busyboxBin.isNotEmpty() && File(busyboxBin).exists() &&
        rootfsDir.isNotEmpty() && File(rootfsDir, "bin").exists()

    fun prepareDirs(context: Context) {
        binDir = File(context.filesDir, "bin").apply { mkdirs() }.absolutePath
        prootBin = File(binDir, "proot").absolutePath
        busyboxBin = File(binDir, "busybox").absolutePath
        rootfsDir = File(context.filesDir, "debian-rootfs").absolutePath
    }

    /** 完整初始化：预置 busybox/proot 秒级就绪 → 下载解压 Debian rootfs */
    suspend fun initialize(context: Context): Boolean {
        try {
            prepareDirs(context)
            _state.value = State.DOWNLOADING

            // 1. 从 APK assets 复制 busybox（预置，零网络）
            if (!File(busyboxBin).exists()) {
                _progress.value = "解压 busybox…"
                copyAsset(context, "busybox", busyboxBin)
                File(busyboxBin).setExecutable(true)
            }
            // 2. 从 APK assets 复制 proot（预置，零网络）
            if (!File(prootBin).exists()) {
                _progress.value = "解压 proot…"
                copyAsset(context, "proot", prootBin)
                File(prootBin).setExecutable(true)
            }

            // 3. 下载并解压 Debian rootfs
            if (!File(rootfsDir, "bin").exists()) {
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz").absolutePath
                if (!File(archive).exists()) {
                    _progress.value = "下载 Debian 11 rootfs (~120MB)…"
                    tryMirrors(archive)
                }
                _state.value = State.EXTRACTING
                _progress.value = "解压 rootfs（约 400MB）…"
                File(rootfsDir).mkdirs()
                val r = CommandRunner.runBare(
                    "$busyboxBin tar -xJf $archive -C $rootfsDir",
                    context.filesDir.absolutePath, 600_000
                )
                if (!r.ok) { _state.value = State.ERROR; _progress.value = "解压失败：${r.output}"; return false }
            }

            _state.value = State.READY; _progress.value = "Debian 11 终端就绪"; return true
        } catch (e: Exception) {
            _state.value = State.ERROR; _progress.value = "初始化失败：${e.message}"; return false
        }
    }

    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化")
        val esc = cmd.replace("\"", "\\\"").replace("\n", "; ")
        val full = "$prootBin -r $rootfsDir -b /dev -b /proc -b /sys -b /data:/data -b $cwd:$cwd --cwd=$cwd /bin/bash -c \"$esc\""
        return CommandRunner.runBare(full, cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
    }

    // ===== internal =====

    private fun copyAsset(context: Context, assetName: String, dest: String) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(File(dest)).use { out -> input.copyTo(out) }
        }
    }

    private suspend fun tryMirrors(dest: String) {
        var last: String? = null
        for ((i, url) in rootfsMirrors.withIndex()) {
            _progress.value = "下载 rootfs（${i + 1}/${rootfsMirrors.size}）…"
            try { downloadToFile(url, File(dest)); return } catch (e: Exception) { last = e.message }
        }
        throw RuntimeException("所有 rootfs 镜像下载失败：$last")
    }

    private suspend fun downloadToFile(url: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            } ?: throw RuntimeException("空响应体")
        }
    }
}
