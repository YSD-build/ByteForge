package com.example.aichat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * ByteForge Debian 11 终端环境。
 * 所有组件（busybox / proot / Debian rootfs）均预置在 APK assets 中，
 * 初始化时直接复制到本地，零网络请求。
 */
object ProotDebian {

    enum class State { NOT_INITIALIZED, COPYING, EXTRACTING, READY, ERROR }
    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val progress: StateFlow<String> get() = _progress
    private val _progress = MutableStateFlow("")

    private var rootfsDir = ""
    private var binDir = ""
    private var prootBin = ""
    private var busyboxBin = ""

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

    /** 完全离线初始化：从 APK assets 复制所有组件，解压 rootfs */
    suspend fun initialize(context: Context): Boolean {
        try {
            prepareDirs(context)
            _state.value = State.COPYING

            // 1. busybox（APK assets → filesDir，零网络）
            if (!File(busyboxBin).exists()) {
                _progress.value = "复制 busybox…"
                copyAsset(context, "busybox", busyboxBin)
                File(busyboxBin).setExecutable(true)
            }
            // 2. proot（APK assets → filesDir，零网络）
            if (!File(prootBin).exists()) {
                _progress.value = "复制 proot…"
                copyAsset(context, "proot", prootBin)
                File(prootBin).setExecutable(true)
            }

            // 3. Debian rootfs（APK assets 预置 87MB rootfs.tar.xz，解压 ~400MB）
            if (!File(rootfsDir, "bin").exists()) {
                _state.value = State.EXTRACTING
                _progress.value = "解压 Debian 11 rootfs（约 400MB，需 3-5 分钟）…"
                File(rootfsDir).mkdirs()
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz").absolutePath
                copyAsset(context, "debian-rootfs.tar.xz", archive)
                val r = CommandRunner.runBare(
                    "$busyboxBin tar -xJf $archive -C $rootfsDir",
                    context.filesDir.absolutePath, 600_000
                )
                File(archive).delete() // 释放缓存空间
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
}
