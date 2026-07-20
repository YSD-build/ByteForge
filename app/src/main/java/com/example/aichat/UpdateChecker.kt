package com.example.aichat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：检查 GitHub Releases，下载新版本 APK 并触发安装。
 * 仓库地址可在 Settings 中配置。
 */
object UpdateChecker {

    data class ReleaseInfo(
        val tagName: String,
        val versionCode: Int,
        val body: String,
        val apkUrl: String,
        val size: Long
    )

    // 默认仓库 — 可在 Settings 中覆盖
    var repoOwner = "user"
    var repoName = "agentforge"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 检查 GitHub Releases 中是否有比当前更新的版本。返回 null 表示已是最新。 */
    suspend fun checkUpdate(currentVersionCode: Int): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: return@withContext null }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val vc = tag.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            if (vc <= currentVersionCode) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0)
                    break
                }
            }
            if (apkUrl.isEmpty()) return@withContext null
            ReleaseInfo(tagName = tag, versionCode = vc,
                body = json.optString("body", "").take(300),
                apkUrl = apkUrl, size = size)
        } catch (_: Exception) {
            null
        }
    }

    /** 下载 APK 并通过 FileProvider 触发安装 */
    suspend fun downloadAndInstall(context: Context, info: ReleaseInfo): String = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update_${info.tagName}.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载 APK 失败 HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            } ?: throw RuntimeException("响应体为空")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "下载完成，正在安装 ${info.tagName}"
    }
}
