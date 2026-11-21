package com.cloudhub.drive.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.Downloader
import com.cloudhub.drive.core.Node
import com.cloudhub.drive.core.ShareItem
import com.cloudhub.drive.core.Util
import com.cloudhub.drive.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private sealed interface ShareDialog {
    object None : ShareDialog
    data class Edit(val item: ShareItem) : ShareDialog
    data class Delete(val item: ShareItem) : ShareDialog
    data class Info(val title: String, val text: String) : ShareDialog
    data class Access(val item: ShareItem) : ShareDialog
}

@Composable
private fun ShareFields(
    nodes: List<Node>,
    nodeId: String,
    onNodeId: (String) -> Unit,
    remark: String,
    onRemark: (String) -> Unit,
    expiryIndex: Int,
    onExpiry: (Int) -> Unit,
    maxViews: String,
    onMaxViews: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (nodes.size == 1) {
            Text(nodes.first().name, style = MaterialTheme.typography.bodyLarge)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                nodes.forEach { n ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onNodeId(n.id) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(n.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.id == nodeId) {
                            Icon(CommonIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    Text("暂无可分享的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LabeledInput(remark, onRemark, "备注")
        Text("有效期", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SegmentedControl(listOf("永不", "1 天", "7 天", "30 天"), expiryIndex, onExpiry)
        LabeledInput(maxViews, onMaxViews, "最大访问次数，0 表示不限", keyboardType = KeyboardType.Number)
        LabeledInput(code, onCode, "验证码，留空表示无需验证")
    }
}

private fun shareFields(
    nodeId: String,
    remark: String,
    expiryIndex: Int,
    maxViews: String,
    code: String
): Map<String, Any?> {
    val days = listOf(0L, 1L, 7L, 30L).getOrElse(expiryIndex) { 0L }
    val expiresAt = if (days == 0L) 0L else System.currentTimeMillis() + days * 86400000L
    return mapOf(
        "node_id" to nodeId,
        "remark" to remark,
        "expires_at" to expiresAt,
        "max_views" to (maxViews.toIntOrNull() ?: 0),
        "code" to code
    )
}

@Composable
fun ShareCreateDialog(
    nodes: List<Node>,
    fixedNodeId: String?,
    onConfirm: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit
) {
    var nodeId by remember { mutableStateOf(fixedNodeId ?: nodes.firstOrNull()?.id.orEmpty()) }
    var remark by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(0) }
    var maxViews by remember { mutableStateOf("0") }
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("创建分享", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                ShareFields(nodes, nodeId, { nodeId = it }, remark, { remark = it }, expiry, { expiry = it },
                    maxViews, { maxViews = it }, code, { code = it })
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(shareFields(nodeId, remark, expiry, maxViews, code)) },
                        enabled = nodeId.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("创建") }
                }
            }
        }
    }
}

@Composable
private fun ShareEditDialog(
    item: ShareItem,
    onConfirm: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit
) {
    var remark by remember { mutableStateOf(item.remark) }
    var expiry by remember { mutableStateOf(0) }
    var maxViews by remember { mutableStateOf(item.maxViews.toString()) }
    var views by remember { mutableStateOf(item.views.toString()) }
    var code by remember { mutableStateOf(item.code) }
    var enabled by remember { mutableStateOf(item.enabled) }
    var expiresAt by remember { mutableStateOf(item.expiresAt.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("编辑分享", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                LabeledInput(remark, { remark = it }, "备注")
                LabeledInput(expiresAt, { expiresAt = it }, "过期时间毫秒戳，0 表示永不", keyboardType = KeyboardType.Number)
                Text("相对有效期", style = MaterialTheme.typography.titleSmall)
                SegmentedControl(listOf("保持原值", "1 天", "7 天", "30 天", "永不过期"), expiry, { expiry = it })
                LabeledInput(maxViews, { maxViews = it }, "最大访问次数", keyboardType = KeyboardType.Number)
                LabeledInput(views, { views = it }, "已访问次数", keyboardType = KeyboardType.Number)
                LabeledInput(code, { code = it }, "验证码")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用此分享")
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val days = listOf(0L, 1L, 7L, 30L, -1L).getOrElse(expiry) { 0L }
                        val finalExpiry = when {
                            days == 0L -> expiresAt.toLongOrNull() ?: item.expiresAt
                            days == -1L -> 0L
                            else -> System.currentTimeMillis() + days * 86400000L
                        }
                        onConfirm(
                            mapOf(
                                "id" to item.id,
                                "remark" to remark,
                                "expires_at" to finalExpiry,
                                "max_views" to (maxViews.toIntOrNull() ?: 0),
                                "views" to (views.toIntOrNull() ?: 0),
                                "code" to code,
                                "enabled" to if (enabled) 1 else 0
                            )
                        )
                    }, shape = RoundedCornerShape(12.dp)) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun AccessDialog(
    item: ShareItem,
    onOpen: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = AppGraph.api
    var sid by remember { mutableStateOf(item.sid.ifBlank { item.id }) }
    var code by remember { mutableStateOf(item.code) }
    var result by remember { mutableStateOf("") }
    var urls by remember { mutableStateOf(Pair("", "")) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("访客校验", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                LabeledInput(sid, { sid = it }, "分享 ID")
                LabeledInput(code, { code = it }, "验证码")
                PrimaryAction("校验并消耗一次访问", onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { api.shareAccess(sid, code) }.getOrNull() }
                        if (r == null) {
                            result = "请求失败"
                            return@launch
                        }
                        result = r.raw.toString()
                        val d = r.obj()
                        urls = Pair(
                            d?.str("download", "download_url", "url").orEmpty(),
                            d?.str("preview", "preview_url").orEmpty()
                        )
                    }
                }, modifier = Modifier.fillMaxWidth())
                if (result.isNotBlank()) {
                    Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (urls.first.isNotBlank()) {
                            TonalAction("打开下载", onClick = { onOpen(urls.first, "分享下载") })
                        }
                        if (urls.second.isNotBlank()) {
                            TonalAction("打开预览", onClick = { onOpen(urls.second, "分享预览") })
                        }
                        TonalAction("分享分发", onClick = {
                            onOpen(api.shareFetchUrl(sid, code, item.fileName, 0), "分享分发")
                        })
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
fun SharesScreen(onWeb: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = AppGraph.api
    val repo = AppGraph.repo
    val tree by repo.tree.collectAsState()
    val files = remember(tree) { repo.all().filter { !it.isFolder } }

    var page by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<ShareItem>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<ShareDialog>(ShareDialog.None) }

    var nodeId by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(0) }
    var maxViews by remember { mutableStateOf("0") }
    var code by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            list = repo.shareList()
            AppGraph.uploader.refreshServer()
        }
    }

    LaunchedEffect(Unit) { load() }

    fun perform(block: suspend () -> com.cloudhub.drive.core.Resp, onDone: (() -> Unit)? = null) {
        scope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                message = "操作失败：${e.message}"
                return@launch
            }
            if (r.ok) {
                message = ""
                load()
                onDone?.invoke()
            } else {
                message = r.msg
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        HigHeader("分享", "管理全部链接、有效期、次数与验证码") {
            IconButton(onClick = { load() }) { Icon(CommonIcons.Refresh, contentDescription = "刷新分享列表") }
        }
        SegmentedControl(
            listOf("分享列表", "创建分享"),
            page,
            { page = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        if (page == 1) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                GroupCard("新建分享链接") {
                    ShareFields(files, nodeId, { nodeId = it }, remark, { remark = it }, expiry, { expiry = it },
                        maxViews, { maxViews = it }, code, { code = it })
                    PrimaryAction(
                        "创建分享",
                        onClick = {
                            val target = nodeId.ifBlank { files.firstOrNull()?.id.orEmpty() }
                            perform({ repo.shareCreate(shareFields(target, remark, expiry, maxViews, code)) }) {
                                remark = ""
                                code = ""
                                page = 0
                            }
                        },
                        enabled = files.isNotEmpty() || nodeId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            Box(Modifier.weight(1f)) {
                if (list.isEmpty()) {
                    EmptyState("还没有分享记录", "切换到创建分享，选择文件后生成链接", "切换到创建分享") { page = 1 }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        list.forEach { s ->
                            item(key = s.id) {
                                ShareCard(
                                    item = s,
                                    onCopy = { Util.copy(ctx, "分享链接", api.sharePage(s.sid.ifBlank { s.id })) },
                                    onOpen = { onWeb(api.sharePage(s.sid.ifBlank { s.id }), "分享页") },
                                    onEdit = { dialog = ShareDialog.Edit(s) },
                                    onInfo = {
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching { api.shareInfo(s.sid.ifBlank { s.id }) }.getOrNull()
                                            }
                                            dialog = ShareDialog.Info("分享公开信息", r?.raw?.toString() ?: "无法读取")
                                        }
                                    },
                                    onAccess = { dialog = ShareDialog.Access(s) },
                                    onDelete = { dialog = ShareDialog.Delete(s) },
                                    onToggle = { enabled ->
                                        perform({ repo.shareUpdate(mapOf("id" to s.id, "enabled" to if (enabled) 1 else 0)) })
                                    },
                                    onSend = {
                                        Downloader.shareText(ctx, s.fileName, api.sharePage(s.sid.ifBlank { s.id }))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        ShareDialog.None -> Unit
        is ShareDialog.Edit -> ShareEditDialog(
            d.item,
            onConfirm = { fields ->
                dialog = ShareDialog.None
                perform({ repo.shareUpdate(fields) })
            },
            onDismiss = { dialog = ShareDialog.None }
        )
        is ShareDialog.Delete -> ConfirmDialog(
            "取消分享",
            "将删除 ${d.item.fileName} 的分享链接，访客将无法继续访问。",
            confirmText = "删除",
            onConfirm = {
                dialog = ShareDialog.None
                perform({ repo.shareDelete(listOf(d.item.id)) })
            },
            onDismiss = { dialog = ShareDialog.None }
        )
        is ShareDialog.Info -> InfoDialog(d.title, d.text) { dialog = ShareDialog.None }
        is ShareDialog.Access -> AccessDialog(d.item, { url, title -> onWeb(url, title) }) { dialog = ShareDialog.None }
    }
}

@Composable
private fun ShareCard(
    item: ShareItem,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onInfo: () -> Unit,
    onAccess: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileGlyph(Node(item.nodeId, "", item.fileName, "file", 0, "", "", "", "", 0, 1, 0, "main", null, 0, 0), 38)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MetaText(if (item.remark.isBlank()) "无备注" else item.remark)
                }
                Switch(checked = item.enabled, onCheckedChange = onToggle)
            }
            val link = item.url.ifBlank { "/s/${item.sid.ifBlank { item.id }}" }
            Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetaText("有效期：${Util.time(item.expiresAt)}")
                MetaText("访问：${item.views}/${if (item.maxViews == 0) "不限" else item.maxViews}")
                MetaText(if (item.code.isBlank()) "无验证码" else "有验证码")
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TonalAction("复制链接", onClick = onCopy)
                TonalAction("打开页面", onClick = onOpen)
                TonalAction("编辑", onClick = onEdit)
                TonalAction("公开信息", onClick = onInfo)
                TonalAction("访客校验", onClick = onAccess)
                TonalAction("发送", onClick = onSend)
                TonalAction("删除", onClick = onDelete)
            }
        }
    }
}
