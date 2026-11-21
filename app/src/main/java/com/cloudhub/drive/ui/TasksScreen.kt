package com.cloudhub.drive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.TaskItem
import com.cloudhub.drive.core.Util

private fun statusLabel(status: String): String = when (status) {
    "queued" -> "等待中"
    "running" -> "上传中"
    "done" -> "已完成"
    "error" -> "失败"
    "cancelled" -> "已取消"
    else -> status
}

@Composable
fun TasksScreen() {
    val uploader = AppGraph.uploader
    val tasks by uploader.tasks.collectAsState()
    var clearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { uploader.refreshServer() }

    Column(Modifier.fillMaxSize()) {
        HigHeader("上传任务", "实时速度与进度") {
            PrimaryAction("手动刷新", onClick = { uploader.refreshServer() }, icon = CommonIcons.Refresh)
        }

        GroupCard {
            val totalSpeed = tasks.filter { it.status == "running" }.sumOf { it.speed }
            Text("合计 ${Util.speed(totalSpeed)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "共 ${tasks.size} 个任务 · 进行中 ${tasks.count { it.status == "running" || it.status == "queued" }} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TonalAction("去除已完成", onClick = { uploader.clear(0) }, icon = CommonIcons.Clear)
                TonalAction("清空全部", onClick = { clearAll = true }, icon = CommonIcons.Delete)
            }
        }

        Box(Modifier.weight(1f)) {
            if (tasks.isEmpty()) {
                EmptyState(
                    title = "暂无上传任务",
                    message = "从文件页选择文件后，这里会显示实时速度与进度"
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    tasks.forEach { t ->
                        item(key = t.id) {
                            TaskCard(
                                task = t,
                                onCancel = { uploader.cancel(listOf(t.id)) },
                                onDelete = { uploader.deleteTasks(listOf(t.id)) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (clearAll) {
        ConfirmDialog(
            title = "清空全部任务",
            message = "将删除全部任务记录，包括正在进行的任务显示。实际上传由客户端中断。",
            confirmText = "清空",
            onConfirm = {
                clearAll = false
                uploader.clear(1)
            },
            onDismiss = { clearAll = false }
        )
    }
}

@Composable
private fun TaskCard(task: TaskItem, onCancel: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileGlyph(
                    com.cloudhub.drive.core.Node(
                        task.id, "", task.name, "file", task.total, "", "", "", "", 0, 1, 0, "main", null, 0, 0
                    ),
                    38
                )
                Spacer(Modifier.padding(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        statusLabel(task.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (task.status) {
                            "error" -> MaterialTheme.colorScheme.error
                            "done" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            LinearProgressIndicator(
                progress = Util.percent(task.loaded, task.total),
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${Util.size(task.loaded)} / ${Util.size(task.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Util.speed(task.speed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (task.message.isNotBlank()) {
                Text(task.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status == "running" || task.status == "queued") {
                    TonalAction("取消", onClick = onCancel)
                }
                TonalAction("删除记录", onClick = onDelete)
            }
        }
    }
}
