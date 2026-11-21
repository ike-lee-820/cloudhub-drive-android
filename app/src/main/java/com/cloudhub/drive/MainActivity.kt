package com.cloudhub.drive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.DriveApp
import com.cloudhub.drive.core.Node
import com.cloudhub.drive.core.Util
import com.cloudhub.drive.core.UpdateInfo
import com.cloudhub.drive.core.Updater
import com.cloudhub.drive.ui.AppBottomBar
import com.cloudhub.drive.ui.CloudHubTheme
import com.cloudhub.drive.ui.FilesScreen
import com.cloudhub.drive.ui.LoginScreen
import com.cloudhub.drive.ui.PreviewScreen
import com.cloudhub.drive.ui.SettingsScreen
import com.cloudhub.drive.ui.SharesScreen
import com.cloudhub.drive.ui.TasksScreen
import com.cloudhub.drive.ui.UpdateDialog
import com.cloudhub.drive.ui.WebPageScreen
import com.cloudhub.drive.ui.appBackground
import kotlinx.coroutines.launch

data class PreviewArgs(val node: Node, val editable: Boolean)
data class WebArgs(val url: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudHubTheme {
                RootShell()
            }
        }
    }
}

@Composable
private fun RootShell() {
    val s by AppGraph.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val currentVersion = BuildConfig.VERSION_NAME

    var authed by remember { mutableStateOf(AppGraph.store.token.isNotBlank()) }
    var tab by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<PreviewArgs?>(null) }
    var web by remember { mutableStateOf<WebArgs?>(null) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf(DriveApp.instance.crashLog()) }

    fun checkUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) updateMessage = ""
        scope.launch {
            runCatching {
                val r = Updater.check(currentVersion)
                r.onSuccess { info ->
                    if (info == null) {
                        if (manual) updateMessage = "已是最新版本 $currentVersion"
                    } else {
                        updateInfo = info
                        showUpdate = true
                    }
                }.onFailure { e ->
                    if (manual) updateMessage = "检查失败：${e.message}"
                }
            }
            checkingUpdate = false
        }
    }

    LaunchedEffect(authed) {
        if (authed) runCatching { checkUpdate(false) }
    }

    Box(Modifier.fillMaxSize().then(appBackground(s))) {
        if (s.bgMode == "image" && s.bgValue.isNotBlank()) {
            AsyncImage(
                model = s.bgValue,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!authed) {
            LoginScreen(onSuccess = { authed = true })
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { AppBottomBar(tab) { tab = it } }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> FilesScreen(
                            onPreview = { node, editable -> preview = PreviewArgs(node, editable) },
                            onWeb = { url, title -> web = WebArgs(url, title) }
                        )
                        1 -> SharesScreen(
                            onWeb = { url, title -> web = WebArgs(url, title) }
                        )
                        2 -> TasksScreen()
                        else -> SettingsScreen(
                            onWeb = { url, title -> web = WebArgs(url, title) },
                            onLogout = {
                                AppGraph.store.logout()
                                authed = false
                                tab = 0
                            },
                            currentVersion = currentVersion,
                            onCheckUpdate = { checkUpdate(true) },
                            checkingUpdate = checkingUpdate,
                            updateMessage = updateMessage
                        )
                    }
                }
            }
        }

        preview?.let { args ->
            PreviewScreen(node = args.node, editable = args.editable, onClose = { preview = null })
        }
        web?.let { args ->
            WebPageScreen(url = args.url, title = args.title, onClose = { web = null })
        }
    }

    if (showUpdate) {
        updateInfo?.let { info ->
            UpdateDialog(info = info, onDismiss = { showUpdate = false })
        }
    }

    if (crashText.isNotBlank()) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { crashText = "" },
            title = { Text("上次启动异常") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        Text(crashText.take(4000), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Util.copy(ctx, "崩溃日志", crashText)
                    DriveApp.instance.clearCrashLog()
                    crashText = ""
                }) { Text("复制并清除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    DriveApp.instance.clearCrashLog()
                    crashText = ""
                }) { Text("忽略") }
            }
        )
    }
}
