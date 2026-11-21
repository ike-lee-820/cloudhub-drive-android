package com.cloudhub.drive.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.Downloader
import com.cloudhub.drive.core.Node
import com.cloudhub.drive.core.PreviewHtml
import com.cloudhub.drive.core.Util
import org.json.JSONObject

private class PreviewBridge(
    private val node: Node,
    private val web: WebView,
    private val onMessage: (String) -> Unit
) {
    @JavascriptInterface
    fun save(text: String) {
        AppGraph.uploader.saveText(node, text) { ok, msg ->
            web.post {
                web.evaluateJavascript(
                    "window.previewSaveDone($ok, ${JSONObject.quote(msg)})",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun notify(message: String) {
        onMessage(message)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(node: Node, editable: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val api = AppGraph.api
    val previewUrl = remember(node.id) {
        if (node.ssid.isNotBlank()) api.previewUrl(ssid = node.ssid, name = node.name)
        else api.previewUrl(id = node.id, name = node.name)
    }
    val downloadUrl = remember(node.id) {
        if (node.ssid.isNotBlank()) api.downloadUrl(ssid = node.ssid, name = node.name)
        else api.downloadUrl(id = node.id, name = node.name)
    }
    val directUrl = remember(node.id) {
        if (node.ssid.isNotBlank()) api.directPath(node.ssid, node.name)
        else api.previewUrl(id = node.id, name = node.name)
    }
    val html = remember(node.id, editable) {
        PreviewHtml.build(node, previewUrl, downloadUrl, directUrl, editable)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (editable) {
                    Text(
                        "编辑",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { Downloader.download(ctx, api, node) }) {
                    Icon(CommonIcons.Download, contentDescription = "下载")
                }
                IconButton(onClick = { Downloader.shareText(ctx, node.name, directUrl) }) {
                    Icon(CommonIcons.Share, contentDescription = "分享直链")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context: Context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        addJavascriptInterface(
                            PreviewBridge(node, this) { msg -> Util.toast(context, msg) },
                            "AndroidBridge"
                        )
                        loadDataWithBaseURL(AppGraph.store.baseUrl, html, "text/html", "UTF-8", null)
                    }
                },
                update = { }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageScreen(url: String, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { Util.copy(ctx, "链接", url) }) {
                    Icon(CommonIcons.Copy, contentDescription = "复制链接")
                }
                IconButton(onClick = { Downloader.openLink(ctx, url) }) {
                    Icon(CommonIcons.Link, contentDescription = "外部打开")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context: Context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(url)
                    }
                },
                update = { }
            )
        }
    }
}
