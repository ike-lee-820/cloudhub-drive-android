package com.cloudhub.drive

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import com.cloudhub.drive.core.UpdateInfo
import com.cloudhub.drive.core.Updater
import com.cloudhub.drive.core.Util
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PreviewArgs(val node: Node, val editable: Boolean)
data class WebArgs(val url: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 上次启动若崩溃，用纯 View 显示日志，完全绕开 Compose，
        // 保证即使 Compose 自身有问题也能看到堆栈。
        val last = runCatching { DriveApp.instance.crashLog() }.getOrDefault("")
        if (last.isNotBlank()) {
            showCrashFallback(last)
            return
        }

        setContent {
            CloudHubTheme {
                RootShell()
            }
        }
    }

    private fun showCrashFallback(text: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(242, 242, 247))
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(28, 28, 30))
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val btn = Button(this).apply {
            this.text = "复制并清除日志，重新进入"
            setOnClickListener {
                Util.copy(this@MainActivity, "崩溃日志", text)
                DriveApp.instance.clearCrashLog()
                recreate()
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
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

    // 更新检查延后到界面稳定之后，避免参与启动链路
    LaunchedEffect(authed) {
        if (authed) {
            delay(1500)
            runCatching { checkUpdate(false) }
        }
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
}
