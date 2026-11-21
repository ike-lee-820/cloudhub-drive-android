package com.cloudhub.drive.core

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Resp(
    val code: Int,
    val msg: String,
    val data: Any?,
    val httpCode: Int,
    val raw: JSONObject
) {
    val ok: Boolean get() = code == 0

    fun obj(): JSONObject? = data as? JSONObject ?: raw.optJSONObject("data")

    fun arr(): JSONArray? = data as? JSONArray ?: raw.optJSONArray("data")

    fun text(): String = when (data) {
        null -> msg
        is String -> data
        else -> data.toString()
    }
}

class Api(private val store: Store) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun base(): String = store.baseUrl.trimEnd('/')

    fun absolute(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
        pathOrUrl.startsWith("/") -> base() + pathOrUrl
        else -> base() + "/" + pathOrUrl
    }

    private fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> v
        is Map<*, *> -> {
            val o = JSONObject()
            v.forEach { (k, value) -> if (k != null) o.put(k.toString(), jsonValue(value)) }
            o
        }
        is Iterable<*> -> {
            val a = JSONArray()
            v.forEach { a.put(jsonValue(it)) }
            a
        }
        is Array<*> -> {
            val a = JSONArray()
            v.forEach { a.put(jsonValue(it)) }
            a
        }
        else -> v.toString()
    }

    private fun queryValue(v: Any?): String = when (v) {
        null -> ""
        is Iterable<*> -> v.joinToString(",") { it?.toString().orEmpty() }
        is Array<*> -> v.joinToString(",") { it?.toString().orEmpty() }
        is Map<*, *> -> JSONObject().apply {
            v.forEach { (k, value) -> if (k != null) put(k.toString(), jsonValue(value)) }
        }.toString()
        else -> v.toString()
    }

    private fun request(
        path: String,
        params: Map<String, Any?> = emptyMap(),
        post: Boolean = false,
        auth: Boolean = true,
        bodyOverride: RequestBody? = null
    ): Resp {
        val urlBuilder = absolute(path).toHttpUrl().newBuilder()
        if (!post) {
            params.forEach { (k, v) -> if (v != null) urlBuilder.addQueryParameter(k, queryValue(v)) }
        }
        val rb = Request.Builder().url(urlBuilder.build())
        if (auth && store.token.isNotBlank()) rb.header("X-Auth", store.token)
        if (post) {
            val payload = JSONObject()
            params.forEach { (k, v) -> if (v != null) payload.put(k, jsonValue(v)) }
            rb.post(bodyOverride ?: payload.toString().toRequestBody(jsonType))
        } else {
            rb.get()
        }
        client.newCall(rb.build()).execute().use { response: Response ->
            val text = response.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            val code = if (o.has("code")) o.optInt("code") else if (response.isSuccessful) 0 else response.code
            val msg = if (o.has("msg")) o.optString("msg") else if (response.isSuccessful) "ok" else "HTTP ${response.code}"
            val data = if (o.has("data") && !o.isNull("data")) o.get("data") else null
            return Resp(code, msg, data, response.code, o)
        }
    }

    private fun get(path: String, params: Map<String, Any?> = emptyMap(), auth: Boolean = true) =
        request(path, params, post = false, auth = auth)

    private fun post(path: String, params: Map<String, Any?> = emptyMap(), auth: Boolean = true) =
        request(path, params, post = true, auth = auth)

    private fun nodesFrom(r: Resp): List<Node> {
        val d = r.obj()
        val arr = when {
            d == null -> r.arr()
            d.optJSONArray("items") != null -> d.getJSONArray("items")
            d.optJSONArray("list") != null -> d.getJSONArray("list")
            d.optJSONArray("nodes") != null -> d.getJSONArray("nodes")
            d.optJSONArray("all") != null -> d.getJSONArray("all")
            d.optJSONArray("files") != null -> d.getJSONArray("files")
            else -> r.arr() ?: JSONArray()
        }
        return parseNodes(arr)
    }

    private fun sharesFrom(r: Resp): List<ShareItem> {
        val d = r.obj()
        val arr = when {
            d == null -> r.arr()
            d.optJSONArray("items") != null -> d.getJSONArray("items")
            d.optJSONArray("list") != null -> d.getJSONArray("list")
            d.optJSONArray("shares") != null -> d.getJSONArray("shares")
            else -> r.arr() ?: JSONArray()
        }
        return parseShares(arr)
    }

    private fun tasksFrom(r: Resp): List<TaskItem> {
        val d = r.obj()
        val arr = when {
            d == null -> r.arr()
            d.optJSONArray("items") != null -> d.getJSONArray("items")
            d.optJSONArray("list") != null -> d.getJSONArray("list")
            d.optJSONArray("tasks") != null -> d.getJSONArray("tasks")
            else -> r.arr() ?: JSONArray()
        }
        return parseTasks(arr)
    }

    // 一、系统与认证
    fun health() = get("/api/health", auth = false)
    fun initState() = get("/api/init")
    fun login(password: String) = post("/api/auth/login", mapOf("password" to password), auth = false)
    fun checkAuth(token: String) = get("/api/auth/check", mapOf("token" to token), auth = false)
    fun uploadConfig(): UploadConfig = UploadConfig.from(get("/api/config/upload").obj())
    fun proxyConfig() = get("/api/config/proxy")
    fun systemStatus() = get("/api/system/status")
    fun deleteRepo(owner: String, repo: String, storage: Int) =
        post("/api/repo/delete", mapOf("owner" to owner, "repo" to repo, "storage" to storage))

    // 二、文件树
    fun fsList(parentId: String) = get("/api/fs/list", mapOf("parent_id" to parentId)).let { nodesFrom(it) }

    fun fsTree(): TreeData {
        val r = get("/api/fs/tree")
        val d = r.obj()
        val allArr = when {
            d == null -> r.arr()
            d.optJSONArray("all") != null -> d.getJSONArray("all")
            d.optJSONArray("nodes") != null -> d.getJSONArray("nodes")
            d.optJSONArray("items") != null -> d.getJSONArray("items")
            else -> r.arr() ?: JSONArray()
        }
        val folderArr = d?.optJSONArray("folders")
        val all = parseNodes(allArr)
        val folders = parseNodes(folderArr).ifEmpty { all.filter { it.isFolder } }
        return TreeData(all, folders)
    }

    fun fsStat(id: String = "", ssid: String = ""): Node? {
        val p = mutableMapOf<String, Any?>()
        if (id.isNotBlank()) p["id"] = id
        if (ssid.isNotBlank()) p["ssid"] = ssid
        val r = get("/api/fs/stat", p)
        val d = r.obj() ?: return null
        val inner = d.optJSONObject("node") ?: d.optJSONObject("item") ?: d
        return runCatching { nodeOf(inner) }.getOrNull()
    }

    fun fsSearch(q: String): List<Node> {
        val r = get("/api/fs/search", mapOf("q" to q, "keyword" to q))
        return nodesFrom(r)
    }

    fun fsMkdir(name: String, parentId: String) =
        post("/api/fs/mkdir", mapOf("name" to name, "parent_id" to parentId))

    fun fsMkdirs(paths: List<String>, parentId: String): Resp =
        post("/api/fs/mkdirs", mapOf("paths" to paths, "parent_id" to parentId))

    fun fsTouch(name: String, parentId: String, content: String) =
        post("/api/fs/touch", mapOf("name" to name, "parent_id" to parentId, "content" to content))

    fun fsWrite(id: String, content: String) =
        post("/api/fs/write", mapOf("id" to id, "content" to content, "text" to content))

    fun fsRegister(fields: Map<String, Any?>) =
        post("/api/fs/register", fields)

    fun fsRename(id: String, name: String) =
        post("/api/fs/rename", mapOf("id" to id, "name" to name))

    fun fsDelete(ids: List<String>, purgeRepos: Int = 0) =
        post("/api/fs/delete", mapOf("ids" to ids, "purge_repos" to purgeRepos))

    fun fsCopy(ids: List<String>, targetId: String) =
        post("/api/fs/copy", mapOf("ids" to ids, "target_id" to targetId))

    fun fsMove(ids: List<String>, targetId: String) =
        post("/api/fs/move", mapOf("ids" to ids, "target_id" to targetId))

    fun fileDirect(id: String = "", ssid: String = "", name: String = ""): DirectInfo {
        val p = mutableMapOf<String, Any?>("name" to name)
        if (id.isNotBlank()) p["id"] = id
        if (ssid.isNotBlank()) p["ssid"] = ssid
        return DirectInfo.from(get("/api/file/direct", p).obj())
    }

    // 三、分发地址
    fun downloadUrl(id: String = "", ssid: String = "", name: String = ""): String {
        val b = absolute("/api/file/download").toHttpUrl().newBuilder()
        if (id.isNotBlank()) b.addQueryParameter("id", id) else b.addQueryParameter("ssid", ssid)
        b.addQueryParameter("name", name)
        return b.build().toString()
    }

    fun previewUrl(id: String = "", ssid: String = "", name: String = ""): String {
        val b = absolute("/api/file/preview").toHttpUrl().newBuilder()
        if (id.isNotBlank()) b.addQueryParameter("id", id) else b.addQueryParameter("ssid", ssid)
        b.addQueryParameter("name", name)
        return b.build().toString()
    }

    fun directPath(ssid: String, name: String): String =
        absolute("/d/" + Uri.encode(ssid) + "/" + Uri.encode(name))

    fun sharePage(sid: String): String = absolute("/s/" + Uri.encode(sid))

    fun shareFetchUrl(sid: String, code: String, name: String, inline: Int = 0): String {
        val b = absolute("/api/share/fetch").toHttpUrl().newBuilder()
        b.addQueryParameter("sid", sid)
        b.addQueryParameter("code", code)
        b.addQueryParameter("name", name)
        b.addQueryParameter("inline", inline.toString())
        return b.build().toString()
    }

    fun docsUrl(): String = absolute("/API.md")
    fun healthUrl(): String = absolute("/api/health")

    // 四、分享
    fun shareCreate(fields: Map<String, Any?>) = post("/api/share/create", fields)
    fun shareList(): List<ShareItem> = sharesFrom(get("/api/share/list"))
    fun shareUpdate(fields: Map<String, Any?>) = post("/api/share/update", fields)
    fun shareDelete(ids: List<String>) = post("/api/share/delete", mapOf("ids" to ids))
    fun shareInfo(sid: String) = get("/api/share/info", mapOf("id" to sid, "sid" to sid), auth = false)
    fun shareAccess(sid: String, code: String) =
        get("/api/share/access", mapOf("id" to sid, "sid" to sid, "code" to code), auth = false)

    // 五、上传任务
    fun taskList(): List<TaskItem> = tasksFrom(get("/api/task/list"))
    fun taskReport(tasks: List<Map<String, Any?>>) =
        post("/api/task/report", mapOf("tasks" to tasks))
    fun taskCancel(ids: List<String>) = post("/api/task/cancel", mapOf("ids" to ids))
    fun taskDelete(ids: List<String>) = post("/api/task/delete", mapOf("ids" to ids))
    fun taskClear(all: Int = 0) = post("/api/task/clear", mapOf("all" to all))

    // 六、设置
    fun settingsGet(): AppSettings = AppSettings.from(get("/api/settings/get").obj())
    fun settingsSave(s: AppSettings) = post(
        "/api/settings/save",
        mapOf(
            "accent" to s.accent,
            "theme" to s.theme,
            "font_url" to s.fontUrl,
            "font_name" to s.fontName,
            "font_stack" to s.fontStack,
            "bg_mode" to s.bgMode,
            "bg_value" to s.bgValue
        )
    )
    fun settingsReset(purgeRepos: Int = 0) =
        post("/api/settings/reset", mapOf("purge_repos" to purgeRepos))
}
