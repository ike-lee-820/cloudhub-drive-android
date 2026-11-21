package com.cloudhub.drive.core

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.str(vararg keys: String): String {
    for (k in keys) {
        if (has(k) && !isNull(k)) {
            val v = get(k)
            if (v !== JSONObject.NULL) return v.toString()
        }
    }
    return ""
}

fun JSONObject.lng(vararg keys: String): Long {
    for (k in keys) {
        if (has(k) && !isNull(k)) {
            return when (val v = get(k)) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
    }
    return 0L
}

fun JSONObject.intg(vararg keys: String): Int = lng(*keys).toInt()

fun JSONObject.flag(vararg keys: String): Boolean {
    for (k in keys) {
        if (has(k) && !isNull(k)) {
            return when (val v = get(k)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v == "1" || v.equals("true", true) || v.equals("enabled", true)
                else -> false
            }
        }
    }
    return false
}

fun parseNodes(arr: JSONArray?): List<Node> {
    if (arr == null) return emptyList()
    val out = ArrayList<Node>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(nodeOf(o))
    }
    return out
}

fun nodeOf(o: JSONObject): Node = Node(
    id = o.str("id", "node_id", "nodeId"),
    parentId = o.str("parent_id", "parentId"),
    name = o.str("name", "file_name", "fileName"),
    type = o.str("type", "node_type").ifEmpty { "file" },
    size = o.lng("size", "bytes", "file_size"),
    mime = o.str("mime", "mime_type", "content_type"),
    ssid = o.str("ssid", "storage_id"),
    repoOwner = o.str("repo_owner", "owner", "repoOwner"),
    repoName = o.str("repo_name", "repo", "repoName"),
    storage = o.intg("storage", "account"),
    chunks = o.intg("chunks", "chunk_count").coerceAtLeast(1),
    chunkSize = o.lng("chunk_size", "chunkSize"),
    branch = o.str("branch").ifEmpty { "main" },
    inlineText = if (o.has("inline_text") && !o.isNull("inline_text")) o.getString("inline_text") else null,
    createdAt = o.lng("created_at", "createdAt"),
    updatedAt = o.lng("updated_at", "updatedAt")
)

data class Node(
    val id: String,
    val parentId: String,
    val name: String,
    val type: String,
    val size: Long,
    val mime: String,
    val ssid: String,
    val repoOwner: String,
    val repoName: String,
    val storage: Int,
    val chunks: Int,
    val chunkSize: Long,
    val branch: String,
    val inlineText: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isFolder: Boolean get() = type == "folder" || type == "dir" || type == "directory"
    val isInline: Boolean get() = repoName.isBlank()
}

data class TreeData(val all: List<Node>, val folders: List<Node>)

data class ShareItem(
    val id: String,
    val sid: String,
    val nodeId: String,
    val fileName: String,
    val remark: String,
    val code: String,
    val enabled: Boolean,
    val maxViews: Int,
    val views: Int,
    val expiresAt: Long,
    val createdAt: Long,
    val url: String
)

fun shareOf(o: JSONObject): ShareItem = ShareItem(
    id = o.str("id", "share_id", "shareId"),
    sid = o.str("sid", "id", "share_id"),
    nodeId = o.str("node_id", "nodeId"),
    fileName = o.str("file_name", "fileName", "name", "node_name"),
    remark = o.str("remark", "note", "description"),
    code = o.str("code", "verify_code"),
    enabled = if (o.has("enabled")) o.flag("enabled") else true,
    maxViews = o.intg("max_views", "maxViews", "max_views_limit"),
    views = o.intg("views", "view_count", "viewCount"),
    expiresAt = o.lng("expires_at", "expiresAt", "expires", "expire_at"),
    createdAt = o.lng("created_at", "createdAt"),
    url = o.str("url", "link", "path", "share_url")
)

fun parseShares(arr: JSONArray?): List<ShareItem> {
    if (arr == null) return emptyList()
    val out = ArrayList<ShareItem>(arr.length())
    for (i in 0 until arr.length()) {
        out.add(shareOf(arr.optJSONObject(i) ?: continue))
    }
    return out
}

data class TaskItem(
    val id: String,
    val name: String,
    val total: Long,
    val loaded: Long,
    val speed: Long,
    val status: String,
    val message: String,
    val createdAt: Long
)

fun taskOf(o: JSONObject): TaskItem = TaskItem(
    id = o.str("id", "task_id"),
    name = o.str("name", "file_name"),
    total = o.lng("total", "size", "bytes_total"),
    loaded = o.lng("loaded", "uploaded", "bytes_loaded"),
    speed = o.lng("speed", "rate"),
    status = o.str("status", "state").ifEmpty { "queued" },
    message = o.str("message", "msg", "error"),
    createdAt = o.lng("created_at", "createdAt")
)

fun parseTasks(arr: JSONArray?): List<TaskItem> {
    if (arr == null) return emptyList()
    val out = ArrayList<TaskItem>(arr.length())
    for (i in 0 until arr.length()) {
        out.add(taskOf(arr.optJSONObject(i) ?: continue))
    }
    return out
}

data class AppSettings(
    val accent: String = "#0A84FF",
    val theme: String = "light",
    val fontUrl: String = "",
    val fontName: String = "",
    val fontStack: String = "",
    val bgMode: String = "default",
    val bgValue: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("accent", accent)
        put("theme", theme)
        put("font_url", fontUrl)
        put("font_name", fontName)
        put("font_stack", fontStack)
        put("bg_mode", bgMode)
        put("bg_value", bgValue)
    }

    companion object {
        fun from(o: JSONObject?): AppSettings {
            if (o == null) return AppSettings()
            val inner = o.optJSONObject("settings") ?: o.optJSONObject("data") ?: o
            return AppSettings(
                accent = inner.str("accent", "accent_color").ifEmpty { "#0A84FF" },
                theme = inner.str("theme").ifEmpty { "light" },
                fontUrl = inner.str("font_url", "fontUrl"),
                fontName = inner.str("font_name", "fontName"),
                fontStack = inner.str("font_stack", "fontStack"),
                bgMode = inner.str("bg_mode", "bgMode").ifEmpty { "default" },
                bgValue = inner.str("bg_value", "bgValue")
            )
        }
    }
}

data class GithubAccount(
    val key: Int,
    val owner: String,
    val token: String,
    val apiBase: String,
    val role: String
)

data class UploadConfig(
    val accounts: List<GithubAccount>,
    val chunkSize: Long,
    val maxTasks: Int,
    val maxThreads: Int,
    val apiVersion: String,
    val branch: String,
    val registerEndpoint: String
) {
    companion object {
        fun from(o: JSONObject?): UploadConfig {
            if (o == null) return UploadConfig(emptyList(), 52428800L, 8, 32, "2022-11-28", "main", "/api/fs/register")
            val inner = o.optJSONObject("data") ?: o
            val accounts = ArrayList<GithubAccount>()
            val arr = inner.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                accounts.add(
                    GithubAccount(
                        key = a.intg("key", "index"),
                        owner = a.str("owner", "login"),
                        token = a.str("token", "access_token"),
                        apiBase = a.str("api_base", "apiBase").ifEmpty { "https://api.github.com" },
                        role = a.str("role")
                    )
                )
            }
            return UploadConfig(
                accounts = accounts,
                chunkSize = inner.lng("chunk_size", "chunkSize").takeIf { it > 0 } ?: 52428800L,
                maxTasks = inner.intg("max_tasks", "maxTasks").takeIf { it > 0 } ?: 8,
                maxThreads = inner.intg("max_threads", "maxThreads").takeIf { it > 0 } ?: 32,
                apiVersion = inner.str("api_version", "apiVersion").ifEmpty { "2022-11-28" },
                branch = inner.str("branch").ifEmpty { "main" },
                registerEndpoint = inner.str("register_endpoint", "registerEndpoint").ifEmpty { "/api/fs/register" }
            )
        }
    }
}

data class DirectInfo(
    val direct: String,
    val download: String,
    val preview: String,
    val ssid: String,
    val name: String
) {
    companion object {
        fun from(o: JSONObject?): DirectInfo {
            val d = o?.optJSONObject("data") ?: o ?: JSONObject()
            return DirectInfo(
                direct = d.str("direct", "direct_url", "url", "link"),
                download = d.str("download", "download_url", "downloadUrl"),
                preview = d.str("preview", "preview_url", "previewUrl"),
                ssid = d.str("ssid"),
                name = d.str("name", "file_name")
            )
        }
    }
}
