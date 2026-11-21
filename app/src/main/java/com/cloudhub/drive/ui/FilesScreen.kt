package com.cloudhub.drive.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.DirectInfo
import com.cloudhub.drive.core.Downloader
import com.cloudhub.drive.core.Node
import com.cloudhub.drive.core.Resp
import com.cloudhub.drive.core.UploadRequest
import com.cloudhub.drive.core.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface FileDialog {
    object None : FileDialog
    data class NewFolder(val initial: String = "") : FileDialog
    data class NewFileName(val initial: String = "") : FileDialog
    data class NewFileContent(val name: String) : FileDialog
    data class Mkdirs(val initial: String = "") : FileDialog
    data class Rename(val node: Node) : FileDialog
    data class Delete(val ids: List<String>, val label: String) : FileDialog
    data class Target(val action: String, val ids: List<String>) : FileDialog
    data class NodeActions(val node: Node) : FileDialog
    data class DirectLinks(val node: Node, val info: DirectInfo) : FileDialog
    data class NodeInfo(val node: Node, val text: String) : FileDialog
    data class ShareQuick(val node: Node) : FileDialog
    data class Message(val title: String, val text: String) : FileDialog
}

private fun collectFolder(root: DocumentFile, prefix: String, out: MutableList<Pair<Uri, String>>) {
    root.listFiles().forEach { f ->
        val name = f.name ?: return@forEach
        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
        if (f.isDirectory) {
            collectFolder(f, rel, out)
        } else if (f.isFile) {
            out.add(f.uri to rel)
        }
    }
}

@Composable
fun FilesScreen(
    onPreview: (Node, Boolean) -> Unit,
    onWeb: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = AppGraph.repo
    val api = AppGraph.api
    val tree by repo.tree.collectAsState()
    val busy by repo.busy.collectAsState()

    var parent by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var remoteResults by remember { mutableStateOf<List<Node>>(emptyList()) }
    var messageBar by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<FileDialog>(FileDialog.None) }

    LaunchedEffect(Unit) {
        repo.refresh().exceptionOrNull()?.let { messageBar = "刷新失败：${it.message}" }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            remoteResults = emptyList()
        } else {
            delay(300)
            remoteResults = repo.remoteSearch(query)
        }
    }

    val items = remember(tree, parent, query, remoteResults) {
        if (query.isBlank()) {
            repo.children(parent)
        } else {
            (repo.localSearch(query) + remoteResults).distinctBy { it.id }
        }
    }
    val path = remember(tree, parent) { repo.pathTo(parent) }
    val selectionMode = selected.isNotEmpty()

    fun perform(block: suspend () -> Resp, onDone: (() -> Unit)? = null) {
        scope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                messageBar = "操作失败：${e.message}"
                return@launch
            }
            if (r.ok) {
                messageBar = ""
                onDone?.invoke()
            } else {
                messageBar = r.msg
            }
        }
    }

    fun doRefresh() {
        scope.launch {
            repo.refresh().exceptionOrNull()?.let { messageBar = "刷新失败：${it.message}" }
        }
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val reqs = uris.map {
            UploadRequest(it, Util.displayName(ctx, it), Util.uriSize(ctx, it), parent)
        }
        AppGraph.uploader.enqueue(reqs)
        messageBar = "已加入 ${reqs.size} 个上传任务，可在任务页查看"
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val root = DocumentFile.fromTreeUri(ctx, uri)
            if (root == null) {
                messageBar = "无法读取所选文件夹"
                return@launch
            }
            val files = withContext(Dispatchers.IO) {
                mutableListOf<Pair<Uri, String>>().also { collectFolder(root, "", it) }
            }
            if (files.isEmpty()) {
                messageBar = "所选文件夹为空"
                return@launch
            }
            val dirs = files.map { it.second.substringBeforeLast('/', "") }
                .filter { it.isNotBlank() }
                .distinct()
            val (r, map) = repo.mkdirs(dirs, parent)
            if (!r.ok) {
                messageBar = r.msg
                return@launch
            }
            val reqs = files.map { (u, rel) ->
                val dir = rel.substringBeforeLast('/', "")
                val target = map[dir] ?: parent
                UploadRequest(u, rel.substringAfterLast('/'), Util.uriSize(ctx, u), target)
            }
            AppGraph.uploader.enqueue(reqs)
            messageBar = "已加入 ${reqs.size} 个上传任务"
        }
    }

    BackHandler(enabled = parent.isNotBlank() || selectionMode || query.isNotBlank()) {
        when {
            query.isNotBlank() -> query = ""
            selectionMode -> selected = emptySet()
            parent.isNotBlank() -> parent = repo.node(parent)?.parentId ?: ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        HigHeader(
            title = "文件",
            subtitle = if (query.isBlank()) {
                val folderName = path.lastOrNull()?.name ?: "根目录"
                "$folderName · ${items.size} 项"
            } else {
                "搜索结果 · ${items.size} 项"
            }
        ) {
            IconButton(onClick = { doRefresh() }) {
                Icon(CommonIcons.Refresh, contentDescription = "刷新文件树")
            }
            PrimaryAction("上传", onClick = { pickFiles.launch(arrayOf("*/*")) }, icon = CommonIcons.Upload)
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索文件和文件夹") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(CommonIcons.Clear, contentDescription = "清除搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { parent = ""; selected = emptySet() }) { Text("根目录") }
            path.forEach { n ->
                Icon(CommonIcons.More, contentDescription = null, modifier = Modifier.size(14.dp))
                TextButton(onClick = { parent = n.id; selected = emptySet() }) { Text(n.name) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TonalAction("上传文件夹", onClick = { pickFolder.launch(null) }, icon = CommonIcons.Upload)
            TonalAction("新建文件夹", onClick = { dialog = FileDialog.NewFolder() }, icon = Icons.Filled.Folder)
            TonalAction("新建文件", onClick = { dialog = FileDialog.NewFileName() }, icon = CommonIcons.Edit)
            TonalAction("批量建目录", onClick = { dialog = FileDialog.Mkdirs() }, icon = Icons.Filled.Folder)
            TonalAction(if (grid) "列表视图" else "网格视图", onClick = { grid = !grid }, icon = if (grid) CommonIcons.List else CommonIcons.Grid)
            TonalAction("全选", onClick = { selected = items.map { it.id }.toSet() }, icon = CommonIcons.Check)
        }

        if (selectionMode) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("已选 ${selected.size} 项", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TonalAction("复制", onClick = { dialog = FileDialog.Target("copy", selected.toList()) })
                        TonalAction("移动", onClick = { dialog = FileDialog.Target("move", selected.toList()) })
                        TonalAction("删除", onClick = { dialog = FileDialog.Delete(selected.toList(), "已选 ${selected.size} 项") })
                        TonalAction("取消选择", onClick = { selected = emptySet() })
                    }
                }
            }
        }

        if (messageBar.isNotBlank()) {
            Text(
                messageBar,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        BusyOverlay(busy)

        Box(Modifier.weight(1f)) {
            if (items.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) "这里还没有文件" else "没有匹配的文件",
                    message = if (query.isBlank()) "上传文件或新建文件夹以开始" else "尝试更换关键词",
                    actionText = if (query.isBlank()) "上传文件" else "",
                    onAction = { pickFiles.launch(arrayOf("*/*")) }
                )
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items.forEach { node ->
                        item(key = node.id) {
                        GridItem(node, node.id in selected, selectionMode,
                            onToggle = { toggle(node.id) },
                            onClick = {
                                if (selectionMode) toggle(node.id)
                                else if (node.isFolder) parent = node.id
                                else onPreview(node, Util.isEditable(node.name, node.mime))
                            },
                            onMore = { dialog = FileDialog.NodeActions(node) }
                        )
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items.forEach { node ->
                        item(key = node.id) {
                        FileRow(node, node.id in selected, selectionMode,
                            onToggle = { toggle(node.id) },
                            onClick = {
                                if (selectionMode) toggle(node.id)
                                else if (node.isFolder) parent = node.id
                                else onPreview(node, Util.isEditable(node.name, node.mime))
                            },
                            onMore = { dialog = FileDialog.NodeActions(node) }
                        )
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        FileDialog.None -> Unit
        is FileDialog.NewFolder -> InputDialog(
            "新建文件夹", "文件夹名称", d.initial,
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { name ->
                dialog = FileDialog.None
                if (name.isNotBlank()) perform({ repo.mkdir(name, parent) })
            }
        )
        is FileDialog.NewFileName -> InputDialog(
            "新建文件", "文件名称，例如 note.txt", d.initial,
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { name ->
                dialog = if (name.isBlank()) FileDialog.None else FileDialog.NewFileContent(name)
            }
        )
        is FileDialog.NewFileContent -> InputDialog(
            "编辑 ${d.name}", "初始内容", multiline = true, confirmText = "创建",
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { content ->
                dialog = FileDialog.None
                perform({ repo.touch(d.name, parent, content) })
            }
        )
        is FileDialog.Mkdirs -> InputDialog(
            "批量建目录", "每行一个相对路径，例如 a/b/c", multiline = true, confirmText = "创建",
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { text ->
                dialog = FileDialog.None
                val paths = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (paths.isNotEmpty()) {
                    scope.launch {
                        val (r, _) = repo.mkdirs(paths, parent)
                        if (!r.ok) messageBar = r.msg
                    }
                }
            }
        )
        is FileDialog.Rename -> InputDialog(
            "重命名", "新名称", d.node.name,
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { name ->
                dialog = FileDialog.None
                if (name.isNotBlank()) perform({ repo.rename(d.node.id, name) })
            }
        )
        is FileDialog.Delete -> DeleteDialog(
            label = d.label,
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { purge ->
                dialog = FileDialog.None
                perform({ repo.delete(d.ids, purge) }) { selected = emptySet() }
            }
        )
        is FileDialog.Target -> {
            val folders = repo.folderChoices(d.ids)
            OptionsDialog(
                title = if (d.action == "copy") "复制到" else "移动到",
                options = listOf("根目录") + folders.map { it.name },
                onDismiss = { dialog = FileDialog.None },
                onSelect = { index ->
                    dialog = FileDialog.None
                    val target = if (index == 0) "" else folders[index - 1].id
                    perform({
                        if (d.action == "copy") repo.copy(d.ids, target) else repo.move(d.ids, target)
                    }) { selected = emptySet() }
                }
            )
        }
        is FileDialog.NodeActions -> {
            val n = d.node
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            labels.add(if (n.isFolder) "打开文件夹" else "预览")
            actions.add {
                if (n.isFolder) parent = n.id else onPreview(n, Util.isEditable(n.name, n.mime))
            }
            if (!n.isFolder && Util.isEditable(n.name, n.mime)) {
                labels.add("在线编辑")
                actions.add { onPreview(n, true) }
            }
            if (!n.isFolder) {
                labels.add("下载")
                actions.add { Downloader.download(ctx, api, n) }
                labels.add("获取直链")
                actions.add {
                    scope.launch {
                        val info = repo.direct(id = n.id, ssid = n.ssid, name = n.name)
                        dialog = FileDialog.DirectLinks(n, info)
                    }
                }
                labels.add("创建分享")
                actions.add { dialog = FileDialog.ShareQuick(n) }
            }
            labels.add("重命名")
            actions.add { dialog = FileDialog.Rename(n) }
            labels.add("复制")
            actions.add { dialog = FileDialog.Target("copy", listOf(n.id)) }
            labels.add("移动")
            actions.add { dialog = FileDialog.Target("move", listOf(n.id)) }
            labels.add("查看详情")
            actions.add {
                scope.launch {
                    val detail = repo.stat(id = n.id)
                    val text = buildString {
                        append("名称：").append(n.name).append('\n')
                        append("标识：").append(n.id).append('\n')
                        append("类型：").append(if (n.isFolder) "文件夹" else n.mime.ifBlank { "文件" }).append('\n')
                        append("大小：").append(Util.size(n.size)).append('\n')
                        append("分片：").append(n.chunks).append('\n')
                        append("仓库：").append(n.repoOwner).append('/').append(n.repoName).append('\n')
                        append("创建：").append(Util.time(n.createdAt)).append('\n')
                        append("更新：").append(Util.time(n.updatedAt)).append('\n')
                        if (detail != null) append("服务查询：成功")
                    }
                    dialog = FileDialog.NodeInfo(n, text)
                }
            }
            labels.add("删除")
            actions.add { dialog = FileDialog.Delete(listOf(n.id), n.name) }
            OptionsDialog(
                title = n.name,
                options = labels,
                onDismiss = { dialog = FileDialog.None },
                onSelect = { index ->
                    dialog = FileDialog.None
                    actions[index]()
                }
            )
        }
        is FileDialog.DirectLinks -> DirectDialog(
            node = d.node,
            info = d.info,
            onOpen = { url -> onWeb(url, "文件直链") },
            onDismiss = { dialog = FileDialog.None }
        )
        is FileDialog.NodeInfo -> InfoDialog(d.node.name, d.text) { dialog = FileDialog.None }
        is FileDialog.ShareQuick -> ShareCreateDialog(
            nodes = listOf(d.node),
            fixedNodeId = d.node.id,
            onDismiss = { dialog = FileDialog.None },
            onConfirm = { fields ->
                dialog = FileDialog.None
                perform({ repo.shareCreate(fields) }) {
                    messageBar = "分享已创建，可在分享页查看"
                }
            }
        )
        is FileDialog.Message -> InfoDialog(d.title, d.text) { dialog = FileDialog.None }
    }
}

@Composable
private fun FileRow(
    node: Node,
    checked: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            FileGlyph(node)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    if (node.isFolder) "文件夹 · ${Util.time(node.updatedAt)}"
                    else "${Util.size(node.size)} · ${Util.time(node.updatedAt)}"
                )
            }
            IconButton(onClick = onMore) {
                Icon(CommonIcons.More, contentDescription = "更多操作")
            }
        }
    }
}

@Composable
private fun GridItem(
    node: Node,
    checked: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FileGlyph(node, 48)
            Spacer(Modifier.height(8.dp))
            Text(node.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                    Icon(CommonIcons.More, contentDescription = "更多操作", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(
    label: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var purge by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("将删除 $label 及其全部子项，同时取消关联的分享记录。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = purge, onCheckedChange = { purge = it })
                    Text("同时清理无引用的存储仓库")
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(if (purge) 1 else 0) }, shape = RoundedCornerShape(12.dp)) {
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectDialog(
    node: Node,
    info: DirectInfo,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                val links = listOf(
                    "能力直链" to info.direct.ifBlank { AppGraph.api.directPath(node.ssid, node.name) },
                    "下载地址" to info.download.ifBlank { AppGraph.api.downloadUrl(ssid = node.ssid, name = node.name) },
                    "预览地址" to info.preview.ifBlank { AppGraph.api.previewUrl(ssid = node.ssid, name = node.name) }
                )
                links.forEach { (label, url) ->
                    SettingRow(
                        title = label,
                        subtitle = url,
                        onClick = { Util.copy(ctx, label, url) },
                        icon = CommonIcons.Link
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { Downloader.shareText(ctx, node.name, links[0].second) }) { Text("发送直链") }
                    TextButton(onClick = { onOpen(links[0].second) }) { Text("打开") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
