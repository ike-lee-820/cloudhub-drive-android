package com.cloudhub.drive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cloudhub.drive.core.UpdateInfo
import com.cloudhub.drive.core.Updater
import com.cloudhub.drive.core.Util
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.downloadState.collectAsState()
    var ready by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val canInstall = Updater.canInstall(ctx)

    fun startDownload() {
        busy = true
        error = ""
        ready = null
        Updater.reset()
        scope.launch {
            val r = Updater.download(ctx, info)
            busy = false
            r.onSuccess { file ->
                ready = file
                if (Updater.canInstall(ctx)) {
                    runCatching { Updater.install(ctx, file) }
                        .onFailure { error = "无法拉起安装界面：${it.message}" }
                }
            }.onFailure { e ->
                error = e.message ?: "下载失败"
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("发现新版本", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${info.version} · ${Util.size(info.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    info.notes.ifBlank { "修复已知问题并优化体验" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "经 v6.gh-proxy.com 加速 · ${Updater.THREADS} 线程分段下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state?.let { s ->
                    LinearProgressIndicator(
                        progress = Util.percent(s.loaded, s.total),
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Text(
                        "${s.phase} · ${Util.size(s.loaded)} / ${Util.size(s.total)} · ${Util.speed(s.speed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.message.isNotBlank()) {
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (!canInstall) {
                    Text(
                        "需要先允许本应用安装未知来源应用，否则无法完成更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TonalAction("开启安装权限", onClick = {
                        Updater.openInstallSettings(ctx)
                    }, modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        if (!busy) {
                            Updater.reset()
                            onDismiss()
                        }
                    }) { Text("稍后") }
                    Spacer(Modifier.padding(4.dp))
                    when {
                        busy -> TonalAction("取消下载", onClick = {
                            Updater.cancelDownload()
                            busy = false
                        })
                        ready != null -> PrimaryAction("立即安装", onClick = {
                            if (Updater.canInstall(ctx)) {
                                runCatching { Updater.install(ctx, ready!!) }
                                    .onFailure { error = "无法拉起安装界面：${it.message}" }
                            } else {
                                Updater.openInstallSettings(ctx)
                            }
                        })
                        else -> PrimaryAction("下载并安装", onClick = { startDownload() })
                    }
                }
            }
        }
    }
}
