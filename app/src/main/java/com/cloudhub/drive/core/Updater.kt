package com.cloudhub.drive.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.min

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val publishedAt: String
)

data class DownloadState(
    val phase: String,
    val loaded: Long = 0L,
    val total: Long = 0L,
    val speed: Long = 0L,
    val message: String = ""
)

object Updater {

    private const val OWNER = "ike-lee-820"
    private const val REPO = "cloudhub-drive-android"
    private const val PROXY = "https://v6.gh-proxy.com/"
    private const val PROXY_ALT = "https://v6.gh-proxy.com/github.com/"

    const val THREADS = 64

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val loaded = AtomicLong(0L)
    private val totalBytes = AtomicLong(0L)
    private val tickLoaded = AtomicLong(0L)
    private val cancelled = AtomicBoolean(false)
    private val activeCalls = CopyOnWriteArrayList<okhttp3.Call>()

    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(500)
                val cur = loaded.get()
                val prev = tickLoaded.getAndSet(cur)
                val st = _downloadState.value ?: continue
                if (st.phase == "下载中") {
                    _downloadState.value = st.copy(
                        loaded = cur,
                        total = totalBytes.get(),
                        speed = (cur - prev).coerceAtLeast(0L) * 2L
                    )
                }
            }
        }
    }

    private fun candidates(raw: String): List<String> = listOf(
        PROXY + raw,
        PROXY_ALT + raw,
        raw
    )

    fun accelerated(raw: String): String = candidates(raw).first()

    private fun versionTriple(v: String): Triple<Int, Int, Int> {
        val p = v.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        return Triple(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }, p.getOrElse(2) { 0 })
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = versionTriple(remote)
        val b = versionTriple(local)
        return when {
            a.first != b.first -> a.first > b.first
            a.second != b.second -> a.second > b.second
            else -> a.third > b.third
        }
    }

    private fun parseRelease(body: String, current: String): UpdateInfo? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = o.optString("tag_name").trim().removePrefix("v")
        if (version.isBlank() || !isNewer(version, current)) return null
        val assets = o.optJSONArray("assets") ?: return null
        var url = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.endsWith(".apk", true)) {
                url = a.optString("browser_download_url")
                size = a.optLong("size", 0L)
                break
            }
        }
        if (url.isBlank()) return null
        return UpdateInfo(
            version = version,
            notes = o.optString("body").ifBlank { "修复已知问题并优化体验" },
            apkUrl = url,
            size = size,
            publishedAt = o.optString("published_at")
        )
    }

    suspend fun check(current: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val api = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        var last: Exception? = null
        for (url in candidates(api)) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CloudHubDrive-Android")
                    .build()
                val call = client.newCall(req)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        last = IOException("HTTP ${resp.code}")
                        return@use
                    }
                    val text = resp.body?.string().orEmpty()
                    if (!text.contains("tag_name")) {
                        last = IOException("返回内容不是 Release 数据")
                        return@use
                    }
                    val info = parseRelease(text, current)
                    return@withContext Result.success(info)
                }
            } catch (e: Exception) {
                last = e
            } finally {
                // 检查请求不纳入下载取消列表
            }
        }
        Result.failure(last ?: IOException("无法连接更新服务"))
    }

    fun cancelDownload() {
        cancelled.set(true)
        activeCalls.toList().forEach { runCatching { it.cancel() } }
        activeCalls.clear()
        _downloadState.value = DownloadState("已取消", loaded.get(), totalBytes.get(), 0L, "下载已取消")
    }

    fun reset() {
        _downloadState.value = null
    }

    private fun validApk(file: File): Boolean = try {
        ZipFile(file).use { z -> z.getEntry("AndroidManifest.xml") != null }
    } catch (e: Exception) {
        false
    }

    suspend fun download(ctx: Context, info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        cancelled.set(false)
        loaded.set(0L)
        totalBytes.set(info.size)
        tickLoaded.set(0L)
        activeCalls.clear()

        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "CloudHubDrive-${info.version}.apk")
        if (out.exists() && (info.size <= 0 || out.length() == info.size) && validApk(out)) {
            _downloadState.value = DownloadState("完成", out.length(), out.length(), 0L, "已存在安装包")
            return@withContext Result.success(out)
        }

        var last: Exception? = null
        for (url in candidates(info.apkUrl)) {
            _downloadState.value = DownloadState("准备", 0L, info.size, 0L, "正在连接加速节点")
            try {
                loaded.set(0L)
                tickLoaded.set(0L)
                if (downloadFrom(ctx, url, out, info.size, dir)) {
                    if (!validApk(out)) throw IOException("安装包校验失败")
                    _downloadState.value = DownloadState("完成", out.length(), out.length(), 0L, "下载完成")
                    return@withContext Result.success(out)
                }
            } catch (e: Exception) {
                last = e
                out.delete()
                if (cancelled.get()) break
            }
        }
        _downloadState.value = DownloadState(
            "失败", loaded.get(), totalBytes.get(), 0L,
            last?.message ?: "下载失败"
        )
        Result.failure(last ?: IOException("下载失败"))
    }

    private suspend fun downloadFrom(
        ctx: Context,
        url: String,
        out: File,
        declaredSize: Long,
        dir: File
    ): Boolean {
        // 先用一个 1 字节 Range 请求探测是否支持分段；返回 206 即启用 64 线程
        val probe = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
            .header("User-Agent", "CloudHubDrive-Android")
            .build()
        val probeCall = client.newCall(probe)
        activeCalls.add(probeCall)
        val total: Long
        val supportsRange: Boolean
        probeCall.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            if (resp.code == 206) {
                val cr = resp.header("Content-Range").orEmpty()
                total = cr.substringAfterLast('/', "").toLongOrNull()
                    ?: declaredSize.takeIf { it > 0 }
                    ?: throw IOException("无法解析文件长度")
                supportsRange = true
            } else {
                // 不支持 Range：直接用这次响应流式写入，单线程回退
                total = resp.header("Content-Length")?.toLongOrNull() ?: declaredSize
                totalBytes.set(total)
                _downloadState.value = DownloadState("下载中", 0L, total, 0L, "单线程下载")
                FileOutputStream(out).use { fo ->
                    val stream = resp.body?.byteStream() ?: throw IOException("响应体为空")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) throw IOException("已取消")
                        val n = stream.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                        loaded.addAndGet(n.toLong())
                        _downloadState.value = DownloadState("下载中", loaded.get(), total, 0L, "单线程下载")
                    }
                }
                return true
            }
        }
        activeCalls.remove(probeCall)

        totalBytes.set(total)
        _downloadState.value = DownloadState("下载中", 0L, total, 0L, "64 线程下载中")
        val per = ceil(total.toDouble() / THREADS.toDouble()).toLong().coerceAtLeast(1L)
        val parts = Array(THREADS) { File(dir, "p_${infoVersion(out)}_$it") }

        coroutineScope {
            (0 until THREADS).map { index ->
                async(Dispatchers.IO) {
                    val start = index * per
                    if (start >= total) return@async
                    val end = min(total - 1, (index + 1) * per - 1)
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .header("Range", "bytes=$start-$end")
                        .header("User-Agent", "CloudHubDrive-Android")
                        .build()
                    val call = client.newCall(req)
                    activeCalls.add(call)
                    try {
                        call.execute().use { resp ->
                            if (resp.code != 206) throw IOException("分片 $index 返回 HTTP ${resp.code}")
                            val stream = resp.body?.byteStream() ?: throw IOException("分片 $index 响应体为空")
                            FileOutputStream(parts[index]).use { fo ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancelled.get()) throw IOException("已取消")
                                    val n = stream.read(buf)
                                    if (n <= 0) break
                                    fo.write(buf, 0, n)
                                    loaded.addAndGet(n.toLong())
                                }
                            }
                        }
                    } finally {
                        activeCalls.remove(call)
                    }
                }
            }.awaitAll()
        }
        _downloadState.value = DownloadState("合并中", total, total, 0L, "正在合并分片")

        FileOutputStream(out).use { fo ->
            val buf = ByteArray(256 * 1024)
            parts.forEach { part ->
                if (!part.exists()) throw IOException("分片缺失")
                part.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                    }
                }
                part.delete()
            }
        }
        if (out.length() != total) throw IOException("合并后长度不一致")
        return true
    }

    private fun infoVersion(out: File): String =
        out.name.removePrefix("CloudHubDrive-").removeSuffix(".apk").replace(Regex("[^0-9.]"), "")

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        runCatching {
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    fun install(ctx: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
        }
        ctx.startActivity(i)
    }
}
