package com.cloudhub.drive.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSec: Long): String = size(bytesPerSec) + "/s"

    fun time(ms: Long): String {
        if (ms <= 0) return "永不"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (e: Exception) {
            ms.toString()
        }
    }

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase(Locale.US)
    }

    fun mime(name: String): String {
        return when (ext(name)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "md" -> "text/markdown"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "csv" -> "text/csv"
            "js" -> "text/javascript"
            "ts" -> "text/typescript"
            "py", "go", "rs", "java", "c", "cpp", "h", "php", "rb", "lua", "sql", "sh",
            "yaml", "yml", "ini", "conf", "log", "txt", "kt", "swift", "dart", "groovy" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    fun category(name: String, mime: String): String {
        val e = ext(name)
        val m = mime.lowercase(Locale.US)
        return when {
            m.startsWith("image/") || e in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "svg") -> "image"
            m.startsWith("video/") || e in setOf("mp4", "webm", "mov", "mkv", "avi") -> "video"
            m.startsWith("audio/") || e in setOf("mp3", "wav", "flac", "m4a", "ogg", "aac") -> "audio"
            e == "pdf" || m == "application/pdf" -> "pdf"
            e == "docx" -> "word"
            e in setOf("xlsx", "xls") -> "excel"
            e in setOf("pptx", "ppt", "doc") -> "office"
            m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("javascript") ||
                e in setOf(
                    "js", "ts", "py", "go", "rs", "java", "c", "cpp", "h", "php", "rb", "lua", "sql",
                    "sh", "html", "htm", "css", "json", "xml", "yaml", "yml", "md", "txt", "csv", "log",
                    "ini", "conf", "kt", "swift", "dart", "groovy"
                ) -> "text"
            else -> "other"
        }
    }

    fun isEditable(name: String, mime: String): Boolean = category(name, mime) == "text"

    fun newSsid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder("d")
        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    fun partName(index: Int): String = String.format(Locale.US, "part_%04d.bin", index)

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(ctx, "已复制$label")
    }

    fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "未命名文件"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) name = n
                }
            }
        } catch (e: Exception) {
            // 使用 URI 末段作为回退名称
        }
        if (name.contains("/")) name = name.substringAfterLast('/')
        return name
    }

    fun uriSize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (e: Exception) {
            // 回退到流式长度
        }
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun readText(ctx: Context, uri: Uri, max: Long = 8L * 1024 * 1024): String {
        return try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (true) {
                    val n = r?.read(buf) ?: -1
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                    if (total >= max) break
                }
                sb.toString()
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun percent(loaded: Long, total: Long): Float =
        if (total <= 0) 0f else (loaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
