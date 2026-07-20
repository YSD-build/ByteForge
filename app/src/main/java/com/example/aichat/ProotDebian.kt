package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Debian 11 真终端环境管理。
 * 使用 proot + busybox 在应用沙盒内运行一个真正的 Debian 11 轻量容器。
 * 首次使用需在 Settings 中"初始化终端环境"（下载 rootfs）。
 *
 * 整体体积：proot(~200KB) + busybox(~1MB) + rootfs(~400MB 解压) ≈ 400-500MB < 900MB。
 */
object ProotDebian {

    enum class State { NOT_INITIALIZED, DOWNLOADING, EXTRACTING, READY, ERROR }

    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    private var rootfsDir: String = ""
    private var binDir: String = ""
    private var prootBin: String = ""
    private var busyboxBin: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS) // rootfs 下载可能较久
        .build()

    // ===== 15+ 镜像源（官方 + 国内） =====
    private val prootMirrors = listOf(
        // 官方 / GitHub raw
        "https://raw.githubusercontent.com/ZhymabekRoman/proot-static/main/proot_static",
        "https://github.com/termux/termux-packages/releases/download/proot-5.1.107/proot-aarch64",
        "https://raw.githubusercontent.com/termux/proot-distro/master/proot-static-aarch64",
        // 国内镜像
        "https://mirrors.aliyun.com/termux/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "https://mirrors.ustc.edu.cn/termux/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "https://mirrors.bfsu.edu.cn/termux/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "https://mirrors.nju.edu.cn/termux/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb"
    )
    private val busyboxMirrors = listOf(
        // 官方
        "https://busybox.net/downloads/binaries/1.35.0-arm64v8/busybox",
        "https://busybox.net/downloads/binaries/1.36.1-arm64v8/busybox",
        // GitHub
        "https://github.com/termux/termux-packages/releases/download/busybox-1.35.0/busybox-aarch64",
        "https://raw.githubusercontent.com/termux/termux-packages/master/packages/busybox/busybox-aarch64",
        // 国内镜像
        "https://mirrors.aliyun.com/termux/termux-main/pool/main/b/busybox/busybox_1.35.0_aarch64.deb",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/termux-main/pool/main/b/busybox/busybox_1.35.0_aarch64.deb",
        "https://mirrors.ustc.edu.cn/termux/termux-main/pool/main/b/busybox/busybox_1.35.0_aarch64.deb"
    )
    private val rootfsMirrors = listOf(
        // Linux Containers 官方镜像（最可靠）
        "https://images.linuxcontainers.org/images/debian/bullseye/arm64/default/20231217_05:24/rootfs.tar.xz",
        "https://images.linuxcontainers.org/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        // 清华 TUNA LXC 镜像
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20231217_05:24/rootfs.tar.xz",
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        // 阿里云 OSS
        "https://mirrors.aliyun.com/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        // USTC 镜像
        "https://mirrors.ustc.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        // 南京大学镜像
        "https://mirrors.nju.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz"
    )

    /** 检查是否已初始化 */
    fun isReady(): Boolean = prootBin.isNotEmpty() && File(prootBin).exists() &&
            busyboxBin.isNotEmpty() && File(busyboxBin).exists() &&
            rootfsDir.isNotEmpty() && File(rootfsDir, "bin").exists()

    /** 准备目录结构，使 proot/busybox 二进制可用 */
    fun prepareBins(context: Context) {
        binDir = File(context.filesDir, "bin").apply { mkdirs() }.absolutePath
        prootBin = File(binDir, "proot").absolutePath
        busyboxBin = File(binDir, "busybox").absolutePath
        rootfsDir = File(context.filesDir, "debian-rootfs").absolutePath
    }

    /** 下载并初始化完整 Debian 环境。在协程中调用。 */
    suspend fun initialize(context: Context): Boolean {
        try {
            prepareBins(context)
            _state.value = State.DOWNLOADING

            // 1. 下载 proot 二进制（多镜像降级）
            if (!File(prootBin).exists()) {
                _progress.value = "下载 proot…"
                tryMirrors(prootMirrors, prootBin, "proot")
                File(prootBin).setExecutable(true)
            }

            // 2. 下载 busybox（多镜像降级）
            if (!File(busyboxBin).exists()) {
                _progress.value = "下载 busybox…"
                tryMirrors(busyboxMirrors, busyboxBin, "busybox")
                File(busyboxBin).setExecutable(true)
            }

            // 3. 下载 Debian rootfs（仅当未解压）
            if (!File(rootfsDir, "bin").exists()) {
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz").absolutePath
                if (!File(archive).exists()) {
                    _progress.value = "下载 Debian 11 rootfs (~120MB)…"
                    tryMirrors(rootfsMirrors, archive, "rootfs")
                }

                // 4. 用 busybox tar 解压
                _state.value = State.EXTRACTING
                _progress.value = "解压 rootfs（约 400MB，需几分钟）…"
                File(rootfsDir).mkdirs()
                val result = CommandRunner.runBare(
                    "$busyboxBin tar -xJf $archive -C $rootfsDir",
                    context.filesDir.absolutePath,
                    600_000 // 10 分钟超时
                )
                if (!result.ok) {
                    _state.value = State.ERROR
                    _progress.value = "解压失败：${result.output}"
                    return false
                }
            }

            _state.value = State.READY
            _progress.value = "Debian 11 终端就绪"
            return true
        } catch (e: Exception) {
            _state.value = State.ERROR
            _progress.value = "初始化失败：${e.message}"
            return false
        }
    }

    /**
     * 在 Debian 11 环境中运行一条命令。
     * @param cmd    要执行的 shell 命令
     * @param cwd    命令工作目录（会被 bind 进 proot）
     * @param timeoutMs 超时
     */
    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化，请先在设置中初始化终端环境。")

        // 构建 proot 命令：挂载必要节点 + 设定工作目录
        val fullCmd = buildString {
            append(prootBin)
            append(" -r ").append(rootfsDir)
            append(" -b /dev -b /proc -b /sys -b /data:/data")
            // 将 Android 工作目录 bind 进去
            append(" -b ").append(cwd).append(":").append(cwd)
            append(" --cwd=").append(cwd)
            append(" /bin/bash -c \"").append(cmd.replace("\"", "\\\"").replace("\n", "; ")).append("\"")
        }
        return CommandRunner.runBare(fullCmd, cwd, timeoutMs)
    }

    /** 检查命令是否高风险 */
    fun isHighRisk(cmd: String): Boolean {
        val patterns = listOf(
            "rm -rf", "rmdir", ":(){", "mkfs", "dd if=", "fdisk",
            "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt"
        )
        return patterns.any { cmd.contains(it, ignoreCase = true) }
    }

    // ===== internal =====

    /** 依次尝试多个镜像 URL，直到下载成功 */
    private suspend fun tryMirrors(urls: List<String>, destPath: String, label: String) {
        var lastError: String? = null
        for ((i, url) in urls.withIndex()) {
            _progress.value = "下载 $label…（尝试镜像 ${i + 1}/${urls.size}）"
            try {
                download(url, destPath)
                return // 成功
            } catch (e: Exception) {
                lastError = "${e.message}"
            }
        }
        throw RuntimeException("所有 $label 镜像均下载失败：$lastError。请在设置中添加自定义镜像。")
    }

    private suspend fun download(url: String, destPath: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("响应体为空")
            BufferedInputStream(body.byteStream()).use { input ->
                FileOutputStream(File(destPath)).use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total % (2 * 1024 * 1024) == 0L) {
                            _progress.value = "已下载 ${total / 1048576} MB"
                        }
                    }
                }
            }
        }
    }
}
