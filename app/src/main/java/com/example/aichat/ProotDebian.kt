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
 * ByteForge Debian 11 真终端环境。
 * 使用 Alpine CDN 下载 busybox-static / proot-static，再用 busybox 解压 Debian rootfs。
 * 首次使用在 Settings → 终端环境 点击初始化。
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

    // Alpine CDN (已实测可用 HTTP 200)
    private val apkBase = "https://dl-cdn.alpinelinux.org/alpine/edge"
    private val apkMirrors = listOf(
        apkBase,
        "https://mirrors.aliyun.com/alpine/edge",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/edge",
        "https://mirrors.ustc.edu.cn/alpine/edge"
    )
    // 每个 apk 在仓库中的相对路径
    private val busyboxPath = "main/aarch64/busybox-static-1.38.0-r1.apk"
    private val prootPath = "community/aarch64/proot-static-5.4.0-r2.apk"
    // rootfs 下载源（LXC 官方 + 国内镜像）
    private val rootfsMirrors = listOf(
        "https://images.linuxcontainers.org/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.aliyun.com/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz",
        "https://mirrors.ustc.edu.cn/lxc-images/images/debian/bullseye/arm64/default/20240325_05:24/rootfs.tar.xz"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(900, TimeUnit.SECONDS)
        .build()

    fun isReady(): Boolean = prootBin.isNotEmpty() && File(prootBin).exists() &&
            busyboxBin.isNotEmpty() && File(busyboxBin).exists() &&
            rootfsDir.isNotEmpty() && File(rootfsDir, "bin").exists()

    fun prepareBins(context: Context) {
        binDir = File(context.filesDir, "bin").apply { mkdirs() }.absolutePath
        prootBin = File(binDir, "proot").absolutePath
        busyboxBin = File(binDir, "busybox").absolutePath
        rootfsDir = File(context.filesDir, "debian-rootfs").absolutePath
    }

    /** 完整初始化流程 */
    suspend fun initialize(context: Context): Boolean {
        try {
            prepareBins(context)
            _state.value = State.DOWNLOADING

            // 1. busybox（自举：无需外部工具即可解 APK）
            if (!File(busyboxBin).exists()) {
                _progress.value = "下载 busybox…"
                downloadApkAsset(busyboxPath, busyboxBin, "bin/busybox.static", "busybox")
                File(busyboxBin).setExecutable(true)
            }

            // 2. proot
            if (!File(prootBin).exists()) {
                _progress.value = "下载 proot…"
                downloadApkAsset(prootPath, prootBin, "usr/bin/proot", "proot")
                File(prootBin).setExecutable(true)
            }

            // 3. Debian rootfs
            if (!File(rootfsDir, "bin").exists()) {
                val archive = File(context.cacheDir, "debian-rootfs.tar.xz").absolutePath
                if (!File(archive).exists()) {
                    _progress.value = "下载 Debian 11 rootfs (~120MB)…"
                    tryMirrors(rootfsMirrors, archive, "rootfs")
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

    /** 在 Debian 中执行命令 */
    fun runInDebian(cmd: String, cwd: String, timeoutMs: Long = 60_000): ToolResult {
        if (!isReady()) return ToolResult(false, "Debian 环境未初始化")
        val full = "$prootBin -r $rootfsDir -b /dev -b /proc -b /sys -b /data:/data -b $cwd:$cwd --cwd=$cwd /bin/bash -c \"${cmd.replace("\"","\\\"").replace("\n","; ")}\""
        return CommandRunner.runBare(full, cwd, timeoutMs)
    }

    fun isHighRisk(cmd: String): Boolean {
        val p = listOf("rm -rf", "mkfs", "dd if=", "fdisk", "> /dev/sd", "chmod 777", "shutdown", "reboot", "halt")
        return p.any { cmd.contains(it, true) }
    }

    // ===== internal =====

    /** 从 Alpine .apk 包中提取指定二进制文件（纯 Java，无需外部工具） */
    private suspend fun downloadApkAsset(pkgPath: String, dest: String, tarEntry: String, label: String) {
        var lastErr: String? = null
        for (mirror in apkMirrors) {
            val url = "$mirror/$pkgPath"
            _progress.value = "下载 $label（${mirror.take(30)}…）"
            try {
                val apkFile = File(binDir, "${label}.apk")
                downloadToFile(url, apkFile)
                extractFromApk(apkFile, tarEntry, File(dest))
                apkFile.delete()
                return
            } catch (e: Exception) { lastErr = e.message }
        }
        throw RuntimeException("$label 所有镜像下载/提取失败：$lastErr")
    }

    /** 从 APK 中提取文件：扫描 GZIP 头 → 解压 → 解析 tar → 找到目标条目 */
    private fun extractFromApk(apk: File, targetPath: String, dest: File) {
        // 扫描 apk 文件，找到所有 gzip 头部偏移
        val gzipOffsets = mutableListOf<Long>()
        RandomAccessFile(apk, "r").use { raf ->
            var pos = 0L
            while (pos < raf.length() - 3) {
                raf.seek(pos)
                val b1 = raf.read(); val b2 = raf.read()
                if (b1 == 0x1F && b2 == 0x8B.toInt()) gzipOffsets.add(pos)
                pos++
            }
        }
        // 取最后一个 gzip 流（data.tar.gz）
        val dataOffset = gzipOffsets.lastOrNull()
            ?: throw RuntimeException("apk 中没有找到 gzip 数据")
        // 从该偏移解压 tar，找到目标文件
        RandomAccessFile(apk, "r").use { raf ->
            raf.seek(dataOffset)
            GZIPInputStream(object : InputStream() {
                override fun read(): Int = raf.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
            }).use { gz ->
                val buf = ByteArray(512)
                while (true) {
                    var n = gz.read(buf); if (n <= 0) break
                    // 补满 512 字节
                    while (n < 512) { val r = gz.read(buf, n, 512 - n); if (r < 0) break; n += r }
                    if (n < 512) break
                    val name = String(buf, 0, 100).trimEnd('\u0000')
                    val sizeStr = String(buf, 124, 12).trimEnd('\u0000', ' ')
                    val size = sizeStr.toLongOrNull(8) ?: 0L // octal
                    if (name == targetPath || name == "./$targetPath") {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            val dbuf = ByteArray(8192)
                            var remain = size
                            while (remain > 0) {
                                val chunk = gz.read(dbuf, 0, minOf(remain, dbuf.size.toLong()).toInt())
                                if (chunk < 0) break
                                out.write(dbuf, 0, chunk); remain -= chunk
                            }
                        }
                        return
                    }
                    // 跳过条目数据（512 对齐）
                    val skip = ((size + 511L) / 512L) * 512L
                    var skipped = 0L
                    val sbuf = ByteArray(8192)
                    while (skipped < skip) {
                        val chunk = gz.read(sbuf, 0, minOf(skip - skipped, sbuf.size.toLong()).toInt())
                        if (chunk < 0) break; skipped += chunk
                    }
                }
            }
        }
        throw RuntimeException("在 apk 的 tar 中未找到 $targetPath")
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

    private suspend fun tryMirrors(urls: List<String>, destPath: String, label: String) {
        var last: String? = null
        for ((i, url) in urls.withIndex()) {
            _progress.value = "下载 $label（${i + 1}/${urls.size}）…"
            try { downloadToFile(url, File(destPath)); return } catch (e: Exception) { last = e.message }
        }
        throw RuntimeException("所有 $label 镜像下载失败：$last")
    }

    private fun String.toLongOrNull(radix: Int): Long? =
        runCatching { this.trim().takeIf { it.isNotEmpty() }?.toLong(radix) }.getOrNull()
}
