package com.cloudhub.drive.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class Repo(private val api: Api) {

    private val _tree = MutableStateFlow<List<Node>>(emptyList())
    val tree: StateFlow<List<Node>> = _tree.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _busy.value = true
            _tree.value = api.fsTree().all
            Unit
        }.also { _busy.value = false }
    }

    fun children(parentId: String): List<Node> = _tree.value
        .filter { it.parentId == parentId }
        .sortedWith(compareByDescending<Node> { it.isFolder }.thenBy { it.name.lowercase() })

    fun all(): List<Node> = _tree.value

    fun node(id: String): Node? = _tree.value.firstOrNull { it.id == id }

    fun pathTo(parentId: String): List<Node> {
        val out = ArrayList<Node>()
        var cur = parentId
        var guard = 0
        while (cur.isNotBlank() && guard < 64) {
            val n = node(cur) ?: break
            out.add(0, n)
            cur = n.parentId
            guard++
        }
        return out
    }

    fun descendants(ids: Collection<String>): Set<String> {
        val byParent = _tree.value.groupBy { it.parentId }
        val result = LinkedHashSet<String>(ids)
        val queue = ArrayDeque<String>(ids)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            byParent[p]?.forEach { child ->
                if (result.add(child.id)) queue.addLast(child.id)
            }
        }
        return result
    }

    fun folderChoices(excludeIds: Collection<String>): List<Node> {
        val excluded = descendants(excludeIds)
        return _tree.value.filter { it.isFolder && it.id !in excluded }
    }

    fun localSearch(q: String): List<Node> {
        if (q.isBlank()) return emptyList()
        return _tree.value.filter { it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<Node> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    suspend fun remoteSearch(q: String): List<Node> = withContext(Dispatchers.IO) {
        runCatching { api.fsSearch(q) }.getOrDefault(emptyList())
    }

    suspend fun stat(id: String = "", ssid: String = ""): Node? = withContext(Dispatchers.IO) {
        runCatching { api.fsStat(id, ssid) }.getOrNull()
    }

    suspend fun mutate(remote: suspend () -> Resp): Resp {
        val r = withContext(Dispatchers.IO) { remote() }
        if (r.ok) refresh()
        return r
    }

    suspend fun optimistic(local: (MutableList<Node>) -> Unit, remote: suspend () -> Resp): Resp {
        val previous = _tree.value
        val next = previous.toMutableList()
        local(next)
        _tree.value = next
        val r = withContext(Dispatchers.IO) { remote() }
        if (r.ok) refresh() else _tree.value = previous
        return r
    }

    suspend fun mkdir(name: String, parentId: String) = mutate { api.fsMkdir(name, parentId) }

    suspend fun mkdirs(paths: List<String>, parentId: String): Pair<Resp, Map<String, String>> {
        val r = withContext(Dispatchers.IO) { api.fsMkdirs(paths, parentId) }
        val map = HashMap<String, String>()
        val d = r.obj()
        val candidate = d?.optJSONObject("map") ?: d?.optJSONObject("paths") ?: d
        candidate?.keys()?.forEach { k -> map[k] = candidate.optString(k) }
        if (r.ok) refresh()
        return r to map
    }

    suspend fun touch(name: String, parentId: String, content: String) = mutate {
        api.fsTouch(name, parentId, content)
    }

    suspend fun write(id: String, content: String) = mutate { api.fsWrite(id, content) }

    suspend fun rename(id: String, name: String) = optimistic(
        local = { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = list[i].copy(name = name, updatedAt = System.currentTimeMillis())
        },
        remote = { api.fsRename(id, name) }
    )

    suspend fun delete(ids: List<String>, purgeRepos: Int = 0): Resp {
        val remove = descendants(ids)
        return optimistic(
            local = { list -> list.removeAll { it.id in remove } },
            remote = { api.fsDelete(ids, purgeRepos) }
        )
    }

    suspend fun copy(ids: List<String>, targetId: String) = mutate { api.fsCopy(ids, targetId) }
    suspend fun move(ids: List<String>, targetId: String) = mutate { api.fsMove(ids, targetId) }

    suspend fun register(fields: Map<String, Any?>) = mutate { api.fsRegister(fields) }

    suspend fun direct(id: String = "", ssid: String = "", name: String = ""): DirectInfo =
        withContext(Dispatchers.IO) { runCatching { api.fileDirect(id, ssid, name) }.getOrDefault(DirectInfo("", "", "", ssid, name)) }

    suspend fun shareCreate(fields: Map<String, Any?>) = mutate { api.shareCreate(fields) }
    suspend fun shareList() = withContext(Dispatchers.IO) { runCatching { api.shareList() }.getOrDefault(emptyList()) }
    suspend fun shareUpdate(fields: Map<String, Any?>) = mutate { api.shareUpdate(fields) }
    suspend fun shareDelete(ids: List<String>) = mutate { api.shareDelete(ids) }

    suspend fun settingsGet(): AppSettings = withContext(Dispatchers.IO) {
        runCatching { api.settingsGet() }.getOrDefault(AppGraph.store.settings)
    }

    suspend fun settingsSave(s: AppSettings): Resp {
        AppGraph.store.settings = s
        return mutate { api.settingsSave(s) }
    }

    suspend fun systemStatusText(): String = withContext(Dispatchers.IO) {
        runCatching { JSONObject(api.systemStatus().raw.toString()).toString(2) }.getOrDefault("无法读取账号状态")
    }

    suspend fun initStatusText(): String = withContext(Dispatchers.IO) {
        runCatching { api.initState().raw.toString(2) }.getOrDefault("无法读取初始化状态")
    }
}
