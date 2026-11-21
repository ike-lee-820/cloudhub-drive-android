package com.cloudhub.drive.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class UploadRequest(
    val uri: Uri,
    val name: String,
    val size: Long,
    val parentId: String
)

class Uploader(
    private val ctx: Context,
    private val api: Api,
    private val repo: Repo
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val progress = ConcurrentHashMap<String, TaskItem>()
    private val partLoaded = ConcurrentHashMap<String, Long>()
    private val calls = ConcurrentHashMap<String, MutableList<Call>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val fileSem = Semaphore(8)
    private var partSem = Semaphore(32)
    private var currentThreads = 32

    init {
        startReporter()
        startSpeedMeter()
    }

    private fun publish() {
        _tasks.value = progress.values.sortedByDescending { it.createdAt }
    }

    private fun emit(
        id: String,
        name: String,
        total: Long,
        loaded: Long,
        speed: Long,
        status: String,
        message: String
    ) {
        progress[id] = TaskItem(id, name, total, loaded, speed, status, message, System.currentTimeMillis())
        publish()
    }

    private fun update(id: String, transform: (TaskItem) -> TaskItem) {
        while (true) {
            val old = progress[id] ?: return
            val next = transform(old)
            if (progress.replace(id, old, next)) break
        }
        publish()
    }

    private fun markRunning(id: String) = update(id) { it.copy(status = "running", message = "") }

    private fun markDone(id: String) {
        update(id) { it.copy(status = "done", loaded = it.total, speed = 0L, message = "") }
        progress[id]?.let { reportNow(listOf(it)) }
    }

    private fun markError(id: String, message: String) {
        update(id) { it.copy(status = "error", speed = 0L, message = message) }
        progress[id]?.let { reportNow(listOf(it)) }
    }

    private fun taskMap(t: TaskItem): Map<String, Any?> = mapOf(
        "id" to t.id,
        "name" to t.name,
        "total" to t.total,
        "loaded" to t.loaded,
        "speed" to t.speed,
        "status" to t.status,
        "message" to t.message
    )

    private fun reportNow(items: List<TaskItem>) {
        if (items.isEmpty()) return
        scope.launch {
            runCatching { api.taskReport(items.map { taskMap(it) }) }
        }
    }

    private fun startReporter() {
        scope.launch {
            while (isActive) {
                delay(1200)
                val snapshot = progress.values.filter { it.status == "queued" || it.status == "running" }
                if (snapshot.isNotEmpty()) runCatching { api.taskReport(snapshot.map { taskMap(it) }) }
            }
        }
    }

    private fun startSpeedMeter() {
        scope.launch {
            while (isActive) {
                delay(500)
                progress.values.forEach { t ->
                    if (t.status != "running") return@forEach
                    val before = speedSnapshot[t.id] ?: t.loaded
                    val delta = (t.loaded - before).coerceAtLeast(0L)
                    speedSnapshot[t.id] = t.loaded
                    update(t.id) { it.copy(speed = delta * 2L) }
                }
            }
        }
    }

    private val speedSnapshot = ConcurrentHashMap<String, Long>()

    fun enqueue(items: List<UploadRequest>) {
        items.forEach { req ->
            val id = UUID.randomUUID().toString()
            emit(id, req.name, req.size, 0L, 0L, "queued", "")
            scope.launch {
                fileSem.withPermit { runUpload(id, req) }
            }
        }
    }

    private suspend fun runUpload(taskId: String, req: UploadRequest) {
        if (cancelled.contains(taskId)) return
        val cfg = try {
            api.uploadConfig()
        } catch (e: Exception) {
            markError(taskId, "读取上传配置失败：${e.message}")
            return
        }
        synchronized(this) {
            if (cfg.maxThreads != currentThreads) {
                partSem = Semaphore(cfg.maxThreads)
                currentThreads = cfg.maxThreads
            }
        }
        val accounts = orderAccounts(cfg)
        if (accounts.isEmpty()) {
            markError(taskId, "没有可用的存储账号")
            return
        }
        markRunning(taskId)
        accounts.forEachIndexed { index, acc ->
            val ssid = Util.newSsid()
            try {
                uploadWithAccount(taskId, req, cfg, acc, ssid, null)
                markDone(taskId)
                repo.refresh()
                return
            } catch (e: Exception) {
                if (e is CancellationException || cancelled.contains(taskId)) {
                    update(taskId) { it.copy(status = "cancelled", speed = 0L, message = "已取消") }
                    return
                }
                if (index == accounts.lastIndex) {
                    markError(taskId, e.message ?: "上传失败")
                    return
                }
                clearParts(taskId)
                speedSnapshot[taskId] = 0L
                update(taskId) {
                    it.copy(loaded = 0L, speed = 0L, message = "主账号失败，切换备用账号重试")
                }
            }
        }
    }

    private fun orderAccounts(cfg: UploadConfig): List<GithubAccount> {
        val primary = cfg.accounts.firstOrNull { it.role.equals("primary", true) }
            ?: cfg.accounts.firstOrNull { it.key == 1 }
            ?: cfg.accounts.firstOrNull()
        val backup = cfg.accounts.firstOrNull { it.role.equals("backup", true) }
            ?: cfg.accounts.firstOrNull { it.key == 2 }
            ?: cfg.accounts.firstOrNull { it !== primary }
        return listOfNotNull(primary, backup).distinctBy { it.token }.filter { it.token.isNotBlank() }
    }

    private suspend fun uploadWithAccount(
        taskId: String,
        req: UploadRequest,
        cfg: UploadConfig,
        acc: GithubAccount,
        ssid: String,
        updateNodeId: String?
    ) {
        createRepo(cfg, acc, ssid)
        uploadParts(taskId, req, cfg, acc, ssid)
        val fields = mutableMapOf<String, Any?>(
            "ssid" to ssid,
            "name" to req.name,
            "parent_id" to req.parentId,
            "size" to req.size,
            "mime" to Util.mime(req.name),
            "chunks" to max(1, ceil(req.size.toDouble() / cfg.chunkSize.toDouble()).toInt()),
            "chunk_size" to cfg.chunkSize,
            "repo_owner" to acc.owner,
            "repo_name" to ssid,
            "storage" to if (acc.key > 0) acc.key else 1,
            "branch" to cfg.branch
        )
        if (updateNodeId != null) fields["id"] = updateNodeId
        val r = api.fsRegister(fields)
        if (!r.ok) throw IOException("注册文件节点失败：${r.msg}")
    }

    private suspend fun uploadParts(
        taskId: String,
        req: UploadRequest,
        cfg: UploadConfig,
        acc: GithubAccount,
        ssid: String
    ) {
        val count = max(1, ceil(req.size.toDouble() / cfg.chunkSize.toDouble()).toInt())
        if (!putPart(taskId, acc, cfg, ssid, req.uri, 1, 0L, min(cfg.chunkSize, req.size))) {
            throw IOException("首片上传失败，无法建立 main 分支")
        }
        if (count > 1) {
            coroutineScope {
                (2..count).map { index ->
                    async {
                        partSem.withPermit {
                            val offset = (index - 1).toLong() * cfg.chunkSize
                            val length = min(cfg.chunkSize, req.size - offset)
                            if (!putPart(taskId, acc, cfg, ssid, req.uri, index, offset, length)) {
                                throw IOException("分片 $index 上传失败")
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun putPart(
        taskId: String,
        acc: GithubAccount,
        cfg: UploadConfig,
        ssid: String,
        uri: Uri,
        index: Int,
        offset: Long,
        length: Long
    ): Boolean {
        val part = Util.partName(index)
        var lastError: Exception? = null
        for (attempt in 0 until 4) {
            if (cancelled.contains(taskId)) throw CancellationException("task cancelled")
            val sha = if (attempt > 0) querySha(acc, cfg, ssid, part) else null
            val body = PartBody(
                ctx = ctx,
                uri = uri,
                offset = offset,
                rawSize = length,
                message = "upload part $index",
                sha = sha,
                branch = cfg.branch
            ) { sent ->
                addLoaded(taskId, index, sent)
            }
            val path = "${acc.apiBase.trimEnd('/')}/repos/${acc.owner}/$ssid/contents/$ssid/$part"
            val request = Request.Builder()
                .url(path)
                .put(body)
                .header("Authorization", "Bearer ${acc.token}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", cfg.apiVersion)
                .build()
            val call = client.newCall(request)
            val list = calls.computeIfAbsent(taskId) { CopyOnWriteArrayList() }
            list.add(call)
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} ${resp.body?.string()?.take(180).orEmpty()}")
                    }
                }
                return true
            } catch (e: Exception) {
                lastError = e
                if (e is CancellationException || cancelled.contains(taskId)) throw e
                if (attempt < 3) delay(600L * (1L shl attempt))
            } finally {
                list.remove(call)
            }
        }
        throw lastError ?: IOException("分片 $index 上传失败")
    }

    private fun clearParts(taskId: String) {
        val prefix = "$taskId:"
        partLoaded.keys.toList().forEach { if (it.startsWith(prefix)) partLoaded.remove(it) }
    }

    private fun addLoaded(taskId: String, index: Int, sent: Long) {
        partLoaded["$taskId:$index"] = sent
        var sum = 0L
        partLoaded.forEach { (k, v) -> if (k.startsWith("$taskId:")) sum += v }
        update(taskId) { it.copy(loaded = min(sum, it.total)) }
    }

    private fun querySha(acc: GithubAccount, cfg: UploadConfig, ssid: String, part: String): String? {
        val url = "${acc.apiBase.trimEnd('/')}/repos/${acc.owner}/$ssid/contents/$ssid/$part?ref=${cfg.branch}"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${acc.token}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", cfg.apiVersion)
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    JSONObject(resp.body?.string().orEmpty()).optString("sha").takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }

    private fun createRepo(cfg: UploadConfig, acc: GithubAccount, ssid: String) {
        val payload = JSONObject().apply {
            put("name", ssid)
            put("private", true)
            put("auto_init", false)
            put("description", "cf-gh-drive storage")
        }
        val request = Request.Builder()
            .url("${acc.apiBase.trimEnd('/')}/user/repos")
            .post(payload.toString().toRequestBody(jsonType))
            .header("Authorization", "Bearer ${acc.token}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", cfg.apiVersion)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 422) {
                throw IOException("创建私有仓库失败：HTTP ${resp.code}")
            }
        }
    }

    private fun deleteRepoAsync(acc: GithubAccount, cfg: UploadConfig, repoName: String) {
        if (repoName.isBlank()) return
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url("${acc.apiBase.trimEnd('/')}/repos/${acc.owner}/$repoName")
                    .delete()
                    .header("Authorization", "Bearer ${acc.token}")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", cfg.apiVersion)
                    .build()
                client.newCall(request).execute().use { }
            }
        }
    }

    fun saveText(node: Node, text: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = File(ctx.cacheDir, "edit_" + System.currentTimeMillis() + ".txt")
        file.writeBytes(bytes)
        val req = UploadRequest(Uri.fromFile(file), node.name, bytes.size.toLong(), node.parentId)
        val id = UUID.randomUUID().toString()
        emit(id, node.name, bytes.size.toLong(), 0L, 0L, "queued", "编辑保存")
        scope.launch {
            markRunning(id)
            if (node.isInline) {
                val r = runCatching { api.fsWrite(node.id, text) }.getOrNull()
                if (r != null && r.ok) {
                    update(id) { it.copy(loaded = it.total, status = "done", speed = 0L) }
                    progress[id]?.let { reportNow(listOf(it)) }
                    repo.refresh()
                    onResult?.invoke(true, "")
                } else {
                    val msg = r?.msg ?: "保存失败"
                    markError(id, msg)
                    onResult?.invoke(false, msg)
                }
                return@launch
            }
            val cfg = try {
                api.uploadConfig()
            } catch (e: Exception) {
                val msg = "读取上传配置失败：${e.message}"
                markError(id, msg)
                onResult?.invoke(false, msg)
                return@launch
            }
            val accounts = orderAccounts(cfg)
            if (accounts.isEmpty()) {
                val msg = "没有可用的存储账号"
                markError(id, msg)
                onResult?.invoke(false, msg)
                return@launch
            }
            val acc = accounts.first()
            val ssid = Util.newSsid()
            try {
                createRepo(cfg, acc, ssid)
                if (!putPart(id, acc, cfg, ssid, req.uri, 1, 0L, req.size)) {
                    throw IOException("文本重新上传失败")
                }
                val fields = mapOf<String, Any?>(
                    "id" to node.id,
                    "ssid" to ssid,
                    "name" to node.name,
                    "parent_id" to node.parentId,
                    "size" to req.size,
                    "mime" to Util.mime(node.name),
                    "chunks" to 1,
                    "chunk_size" to cfg.chunkSize,
                    "repo_owner" to acc.owner,
                    "repo_name" to ssid,
                    "storage" to if (acc.key > 0) acc.key else 1,
                    "branch" to cfg.branch
                )
                val r = api.fsRegister(fields)
                if (!r.ok) throw IOException("更新节点失败：${r.msg}")
                val oldAcc = cfg.accounts.firstOrNull { it.key == node.storage } ?: acc
                deleteRepoAsync(oldAcc, cfg, node.repoName)
                markDone(id)
                repo.refresh()
                onResult?.invoke(true, "")
            } catch (e: Exception) {
                val msg = e.message ?: "保存失败"
                markError(id, msg)
                onResult?.invoke(false, msg)
            }
        }
    }

    fun cancel(ids: List<String>) {
        ids.forEach { id ->
            cancelled.add(id)
            calls[id]?.forEach { runCatching { it.cancel() } }
            update(id) { it.copy(status = "cancelled", speed = 0L, message = "已取消") }
        }
        scope.launch { runCatching { api.taskCancel(ids) } }
    }

    fun deleteTasks(ids: List<String>) {
        ids.forEach { id ->
            progress.remove(id)
            calls.remove(id)
            cancelled.remove(id)
            clearParts(id)
        }
        publish()
        scope.launch { runCatching { api.taskDelete(ids) } }
    }

    fun clear(all: Int = 0) {
        val remove = progress.values.filter {
            all == 1 || it.status in setOf("done", "cancelled", "error")
        }.map { it.id }
        deleteTasks(remove)
        scope.launch { runCatching { api.taskClear(all) } }
    }

    fun refreshServer() {
        scope.launch {
            val list = runCatching { api.taskList() }.getOrDefault(emptyList())
            list.forEach { t -> progress.putIfAbsent(t.id, t) }
            publish()
        }
    }

    private class PartBody(
        private val ctx: Context,
        private val uri: Uri,
        private val offset: Long,
        private val rawSize: Long,
        private val message: String,
        private val sha: String?,
        private val branch: String,
        private val onBytes: (Long) -> Unit
    ) : RequestBody() {

        private val prefix = buildString {
            append("{\"message\":")
            append(JSONObject.quote(message))
            append(",\"content\":\"")
        }

        private val suffix = buildString {
            append("\"")
            if (sha != null) {
                append(",\"sha\":")
                append(JSONObject.quote(sha))
            }
            append(",\"branch\":")
            append(JSONObject.quote(branch))
            append("}")
        }

        override fun contentType() = "application/json; charset=utf-8".toMediaType()

        override fun contentLength(): Long =
            prefix.toByteArray(Charsets.UTF_8).size.toLong() +
                4L * ((rawSize + 2L) / 3L) +
                suffix.toByteArray(Charsets.UTF_8).size.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(prefix)
            val stream = ctx.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开待上传文件")
            BufferedInputStream(stream).use { input ->
                skipFully(input, offset)
                val buffer = ByteArray(3 * 8192)
                var remain = rawSize
                var sent = 0L
                while (remain > 0L) {
                    val want = min(buffer.size.toLong(), remain).toInt()
                    readFully(input, buffer, want)
                    sink.writeUtf8(Base64.encodeToString(buffer, 0, want, Base64.NO_WRAP))
                    sent += want
                    remain -= want
                    onBytes(sent)
                }
            }
            sink.writeUtf8(suffix)
        }

        private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
            var off = 0
            while (off < count) {
                val n = input.read(buffer, off, count - off)
                if (n < 0) throw EOFException("分片数据提前结束")
                off += n
            }
        }

        private fun skipFully(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) {
                    if (input.read() < 0) throw EOFException("分片偏移越界")
                    left--
                } else {
                    left -= n
                }
            }
        }
    }
}
