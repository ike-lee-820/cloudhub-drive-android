package com.cloudhub.drive.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.AppSettings
import com.cloudhub.drive.core.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val palette = listOf(
    "#0A84FF", "#5E5CE6", "#FF3B30", "#34C759", "#FF9500",
    "#AF52DE", "#30B0C7", "#8E8E93", "#007AFF", "#FF2D55"
)

private fun parseColor(value: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color(0xFF0A84FF))

@Composable
fun SettingsScreen(
    onWeb: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = AppGraph.api
    val repo = AppGraph.repo
    val store = AppGraph.store

    var draft by remember { mutableStateOf(AppGraph.settingsFlow.value) }
    var base by remember { mutableStateOf(store.baseUrl) }
    var message by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var resetPurge by remember { mutableStateOf<Int?>(null) }
    var baseDialog by remember { mutableStateOf(false) }
    var repoDelete by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf("") }
    var repoName by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("1") }

    LaunchedEffect(Unit) {
        val s = repo.settingsGet()
        draft = s
        AppGraph.settingsFlow.value = s
        base = store.baseUrl
    }

    val pickFont = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(ctx.filesDir, "local_font.ttf")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    store.localFontPath = target.absolutePath
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                draft = draft.copy(fontName = "本机字体")
                AppGraph.settingsFlow.value = draft
                message = "本机字体已保存，仅在当前设备生效"
            } else {
                message = "字体文件读取失败"
            }
        }
    }

    fun save() {
        scope.launch {
            val r = repo.settingsSave(draft)
            AppGraph.settingsFlow.value = draft
            message = if (r.ok) "设置已保存" else r.msg
        }
    }

    fun showStatus(title: String, block: suspend () -> String) {
        scope.launch {
            info = title to withContext(Dispatchers.IO) { block() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HigHeader("设置", "主题、字体、背景与存储状态") {
            PrimaryAction("保存设置", onClick = { save() }, icon = CommonIcons.Check)
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = if (message.startsWith("设置已")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        GroupCard("外观") {
            Text("主题色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            palette.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { c ->
                        val active = draft.accent.equals(c, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(parseColor(c))
                                .then(
                                    if (active) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { draft = draft.copy(accent = c) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (active) {
                                Icon(
                                    CommonIcons.Check,
                                    contentDescription = "已选中",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            Text("界面明暗", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SegmentedControl(
                listOf("浅色", "深色"),
                if (draft.theme == "dark") 1 else 0,
                { draft = draft.copy(theme = if (it == 1) "dark" else "light") }
            )
            LabeledInput(draft.fontUrl, { draft = draft.copy(fontUrl = it) }, "字体 CDN 地址（TTF / OTF / WOFF2）", keyboardType = KeyboardType.Uri)
            LabeledInput(draft.fontName, { draft = draft.copy(fontName = it) }, "字体名称")
            LabeledInput(draft.fontStack, { draft = draft.copy(fontStack = it) }, "字体回退栈")
            TonalAction("上传本机字体文件", onClick = { pickFont.launch("*/*") }, icon = CommonIcons.Upload)
            Text("文件列表背景", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SegmentedControl(
                listOf("默认", "纯色", "渐变", "图片"),
                when (draft.bgMode) { "color" -> 1; "gradient" -> 2; "image" -> 3; else -> 0 },
                {
                    draft = draft.copy(
                        bgMode = when (it) { 1 -> "color"; 2 -> "gradient"; 3 -> "image"; else -> "default" }
                    )
                }
            )
            LabeledInput(draft.bgValue, { draft = draft.copy(bgValue = it) }, "颜色值 / 渐变 CSS / 图片 URL")
            PrimaryAction("保存设置", onClick = { save() }, modifier = Modifier.fillMaxWidth())
        }

        GroupCard("存储") {
            SettingRow(
                "存储账号状态",
                "探测两个 GitHub 账号可用性（GET /user）",
                onClick = { showStatus("存储账号状态") { repo.systemStatusText() } },
                icon = CommonIcons.Info
            )
            SettingRow(
                "D1 初始化状态",
                "查看各表行数与初始化信息",
                onClick = { showStatus("D1 初始化状态") { repo.initStatusText() } },
                icon = CommonIcons.Info
            )
            SettingRow(
                "上传与并发配置",
                "查看分片大小、并发数与账号角色（不展示凭据）",
                onClick = {
                    showStatus("上传配置") {
                        val c = runCatching { api.uploadConfig() }.getOrNull()
                        if (c == null) "无法读取上传配置" else buildString {
                            append("分片大小：").append(Util.size(c.chunkSize)).append('\n')
                            append("文件并发：").append(c.maxTasks).append('\n')
                            append("分片并发：").append(c.maxThreads).append('\n')
                            append("分支：").append(c.branch).append('\n')
                            append("API 版本：").append(c.apiVersion).append('\n')
                            c.accounts.forEach {
                                append("账号 ").append(it.key).append("：").append(it.owner)
                                append("（").append(it.role.ifBlank { "unknown" }).append("）\n")
                            }
                        }
                    }
                },
                icon = CommonIcons.Upload
            )
            SettingRow(
                "手动删除存储仓库",
                "按 owner、repo 与账号序号删除",
                onClick = { repoDelete = true },
                icon = CommonIcons.Delete
            )
        }

        GroupCard("开发者 / API") {
            SettingRow(
                "接口根地址",
                base,
                onClick = { baseDialog = true },
                icon = CommonIcons.Link
            )
            SettingRow(
                "复制 X-Auth 凭证",
                if (store.token.isBlank()) "尚未登录" else store.token.take(6) + "……" + store.token.takeLast(4),
                onClick = { Util.copy(ctx, "X-Auth", store.token) },
                icon = CommonIcons.Lock
            )
            SettingRow(
                "接口文档",
                api.docsUrl(),
                onClick = { onWeb(api.docsUrl(), "接口文档") },
                icon = CommonIcons.Info
            )
            SettingRow(
                "健康检查",
                api.healthUrl(),
                onClick = {
                    showStatus("健康检查") {
                        runCatching { api.health().raw.toString() }.getOrDefault("健康检查失败")
                    }
                },
                icon = CommonIcons.CheckCircle
            )
            SettingRow(
                "分发代理配置",
                "两个代理前缀与首选来源",
                onClick = {
                    showStatus("分发代理配置") {
                        runCatching { api.proxyConfig().raw.toString() }.getOrDefault("无法读取代理配置")
                    }
                },
                icon = CommonIcons.Link
            )
        }

        GroupCard("数据") {
            SettingRow(
                "重置 D1",
                "清空节点、分享、任务与设置",
                onClick = { resetPurge = 0 },
                icon = CommonIcons.Delete
            )
            SettingRow(
                "重置并清理仓库",
                "重置 D1 并删除关联 GitHub 仓库",
                onClick = { resetPurge = 1 },
                icon = CommonIcons.Delete
            )
            SettingRow(
                "退出登录",
                "清除本机保存的凭证",
                onClick = onLogout,
                icon = CommonIcons.Back
            )
        }

        Spacer(Modifier.height(28.dp))
    }

    info?.let { (title, text) -> InfoDialog(title, text) { info = null } }

    if (baseDialog) {
        InputDialog(
            "接口根地址",
            "例如 https://cloud.iobit.de5.net/",
            base,
            onDismiss = { baseDialog = false },
            onConfirm = { value ->
                baseDialog = false
                store.baseUrl = value
                base = store.baseUrl
                message = "接口根地址已更新"
            }
        )
    }

    resetPurge?.let { purge ->
        ConfirmDialog(
            title = "重置数据",
            message = if (purge == 1) "将清空全部数据并异步删除关联仓库，操作不可撤销。" else "将清空节点、分享、任务与设置，操作不可撤销。",
            confirmText = "重置",
            onConfirm = {
                resetPurge = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { api.settingsReset(purge) }.getOrNull()
                    }
                    if (r?.ok == true) {
                        repo.refresh()
                        message = "重置完成"
                    } else {
                        message = r?.msg ?: "重置失败"
                    }
                }
            },
            onDismiss = { resetPurge = null }
        )
    }

    if (repoDelete) {
        Dialog(onDismissRequest = { repoDelete = false }) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("手动删除存储仓库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    LabeledInput(owner, { owner = it }, "owner")
                    LabeledInput(repoName, { repoName = it }, "repo")
                    LabeledInput(storage, { storage = it }, "storage（1 或 2）", keyboardType = KeyboardType.Number)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { repoDelete = false }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            repoDelete = false
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { api.deleteRepo(owner, repoName, storage.toIntOrNull() ?: 1) }.getOrNull()
                                }
                                message = r?.msg ?: "删除请求失败"
                            }
                        }, shape = RoundedCornerShape(12.dp)) { Text("删除") }
                    }
                }
            }
        }
    }
}
