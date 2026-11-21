package com.cloudhub.drive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.Store
import com.cloudhub.drive.core.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(AppGraph.store.baseUrl) }
    var password by remember { mutableStateOf(Store.LOGIN_PASSWORD) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val saved = AppGraph.store.token
        if (saved.isNotBlank()) {
            val r = withContext(Dispatchers.IO) {
                runCatching { AppGraph.api.checkAuth(saved) }.getOrNull()
            }
            val valid = r?.ok == true && r.obj()?.optBoolean("valid") != false
            if (valid) {
                onSuccess()
                return@LaunchedEffect
            }
            AppGraph.store.logout()
        }
    }

    fun login() {
        if (base.isBlank()) {
            error = "请填写接口根地址"
            return
        }
        busy = true
        error = ""
        scope.launch {
            AppGraph.store.baseUrl = base
            val r = withContext(Dispatchers.IO) {
                runCatching { AppGraph.api.login(password) }.getOrNull()
            }
            busy = false
            if (r == null) {
                error = "无法连接服务，请检查网络与接口根地址"
                return@launch
            }
            if (!r.ok) {
                error = r.msg.ifBlank { "登录失败" }
                return@launch
            }
            val d = r.obj()
            val token = d?.optString("token")?.takeIf { it.isNotBlank() }
                ?: (r.data as? String).orEmpty()
            if (token.isBlank()) {
                error = "服务未返回凭证"
                return@launch
            }
            AppGraph.store.token = token
            onSuccess()
        }
    }

    fun healthCheck() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { AppGraph.api.health() }.getOrNull()
            }
            info = r?.raw?.toString() ?: "健康检查失败，可能是网络策略限制"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "云枢网盘",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "连接你的文件、分享与上传任务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabeledInput(base, { base = it }, "接口根地址", keyboardType = KeyboardType.Uri)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("访问密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                PrimaryAction(
                    "登录",
                    onClick = { login() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                TonalAction(
                    "服务健康检查",
                    onClick = { healthCheck() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (info.isNotBlank()) {
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (info.startsWith("{") && info.length > 40) {
        InfoDialog("服务状态", info) { info = "" }
    }
}
