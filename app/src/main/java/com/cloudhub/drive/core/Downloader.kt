package com.cloudhub.drive.core

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

object Downloader {

    fun download(ctx: Context, api: Api, node: Node) {
        val url = if (node.ssid.isNotBlank()) {
            api.downloadUrl(ssid = node.ssid, name = node.name)
        } else {
            api.downloadUrl(id = node.id, name = node.name)
        }
        val safeName = node.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download.bin" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(node.name)
            .setDescription("云枢网盘下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val mime = node.mime.ifBlank { Util.mime(node.name) }
        runCatching { request.setMimeType(mime) }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Util.toast(ctx, "已加入下载队列：${node.name}")
    }

    fun openLink(ctx: Context, url: String) {
        runCatching {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure { Util.copy(ctx, "链接", url) }
    }

    fun shareText(ctx: Context, title: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Util.copy(ctx, "内容", text) }
    }
}
