package com.example.aichat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * ByteForge Debian 11 终端环境。
 * 从 Alpine Linux CDN 下载 busybox-static / proot-static (.apk)，
 * 用纯 Java 解包提取二进制，再用 busybox 下载解压 Debian rootfs。
 * —— 不依赖任何外部工具。
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

    // Alpine CDN 主站 + 国内镜像（已验证全部可访问）
    private val alpineMirrors = listOf(
        "https://dl-cdn.alpinelinux.org/alpine/edge",
        "https://mirrors.aliyun.com/alpine/edge",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/edge",
        "https://mirrors.ustc.edu.cn/alpine/edge"
    )
    private const val busyboxPkg = "main/aarch64/busybox-static-1.38.0-r1.apk"
    private const val prootPkg   = "community/aarch64/proot-static-5.4.0-r2.apk"
    // APK 内 tar 中的二进制路径
    private const val busyboxTarPath = "bin/busybox.static"
    private const val prootTarPath   = "usr/bin/proot"

    // rootfs 源（LXC 官方 + 国内镜像）
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

    fun prepareBins(context: Context) {
        binDir = File(context.filesDir, "bin").apply { mkdirs() }.absolutePath
        prootBin = File(binDir, "proot").absolutePath
        busyboxBin = File(binDir, "busybox").absolutePath
        rootfsDir = File(context.filesDir, "debian-rootfs").absolutePath
    }

    /** 完整初始化：busybox → proot → Debian rootfs */
    suspend fun initialize(context: Context): Boolean {
        try {
            prepareBins(context); _state.value = State.DOWNLOADING
            // 1. busybox（纯 Java 解 APK，无需任何外部工具）
            if (!File(busyboxBin).exists()) {
                _progress.value = "下载 busybox…"
                fetchApkBin(busyboxPkg, busyboxBin, busyboxTarPath, "busybox")
                File(busyboxBin).setExecutable(true)
            }
            // 2. proot
            if (!File(prootBin).exists()) {
                _progress.value = "下载 proot…"
                fetchApkBin(prootPkg, prootBin, prootTarPath, "proot")
                File(prootBin).setExecutable(true)
            }
            // 3. Debian rootfs
            if (!File(rootfsDir, "bin").exists()) {
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz").absolutePath
                if (!File(archive).exists()) {
                    _progress.value = "下载 Debian 11 rootfs (~120MB)…"
                    tryMirrors(archive, "rootfs")
                }
                _state.value = State.EXTRACTING
                _progress.value = "解压 rootfs（约 400MB，需几分钟）…"
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

    /** 从 Alpine .apk 中提取二进制 → dest。多镜像自动降级。 */
    private suspend fun fetchApkBin(pkgPath: String, dest: String, tarEntry: String, label: String) {
        var lastErr: String? = null
        for (mirror in alpineMirrors) {
            val url = "$mirror/$pkgPath"
            _progress.value = "下载 $label…（${mirror.substringAfter("://").take(25)}…）"
            try {
                val tmp = File(binDir, "${label}.apk")
                downloadToFile(url, tmp)
                extractFromApk(tmp, tarEntry, File(dest))
                tmp.delete(); return
            } catch (e: Exception) { lastErr = e.message }
        }
        throw RuntimeException("$label 所有镜像下载/提取失败：$lastErr")
    }

    /** 向文件下载 URL 内容 */
    private suspend fun downloadToFile(url: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            } ?: throw RuntimeException("空响应体")
        }
    }

    /** 从 Alpine .apk 中提取文件：扫描 GZIP 头 → 取最后一个（data.tar.gz）→ 解压 → 解析 tar → 找目标 */
    private fun extractFromApk(apk: File, targetPath: String, dest: File) {
        val offsets = mutableListOf<Long>()
        RandomAccessFile(apk, "r").use { raf ->
            var pos = 0L; val len = raf.length()
            while (pos < len - 2) {
                raf.seek(pos++)
                if (raf.read() == 0x1F && raf.read() == 0x8B.toInt()) offsets.add(pos - 1)
            }
        }
        val dataOff = offsets.lastOrNull() ?: throw RuntimeException("apk 中未找到 gzip 数据")
        RandomAccessFile(apk, "r").use { raf ->
            raf.seek(dataOff)
            GZIPInputStream(rafStream(raf)).use { gz -> tarExtract(gz, targetPath, dest) }
        }
    }

    /** 从 GZIP 流中解析 tar，找到名为 targetPath 的文件并写入 dest */
    private fun tarExtract(gz: GZIPInputStream, targetPath: String, dest: File) {
        val header = ByteArray(512)
        while (true) {
            var n = 0; while (n < 512) { val r = gz.read(header, n, 512 - n); if (r < 0) break; n += r }
            if (n < 512) break
            val name = String(header, 0, 100).trimEnd('\u0000')
            val sizeStr = String(header, 124, 12).trimEnd('\u0000', ' ')
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val padded = ((size + 511L) / 512L) * 512L
            if (name == targetPath || name == "./$targetPath") {
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { out ->
                    var remain = size; val buf = ByteArray(8192)
                    while (remain > 0) {
                        val chunk = gz.read(buf, 0, minOf(remain, buf.size.toLong()).toInt())
                        if (chunk < 0) break; out.write(buf, 0, chunk); remain -= chunk
                    }
                }
                return
            }
            // 跳过数据
            var skipped = 0L; val sbuf = ByteArray(8192)
            while (skipped < padded) {
                val chunk = gz.read(sbuf, 0, minOf(padded - skipped, sbuf.size.toLong()).toInt())
                if (chunk < 0) break; skipped += chunk
            }
        }
        throw RuntimeException("tar 中未找到 $targetPath")
    }

    /** 把 RandomAccessFile 包成 InputStream（从当前 pos 起读） */
    private fun rafStream(raf: RandomAccessFile): InputStream = object : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    }

    private suspend fun tryMirrors(dest: String, label: String) {
        var last: String? = null
        for ((i, url) in rootfsMirrors.withIndex()) {
            _progress.value = "下载 $label（${i + 1}/${rootfsMirrors.size}）…"
            try { downloadToFile(url, File(dest)); return } catch (e: Exception) { last = e.message }
        }
        throw RuntimeException("所有 $label 镜像下载失败：$last")
    }

    private fun String.toLongOrNull(radix: Int): Long? =
        runCatching { trim().takeIf { it.isNotEmpty() }?.toLong(radix) }.getOrNull()
}
