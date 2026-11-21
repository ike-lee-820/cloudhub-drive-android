package com.cloudhub.drive.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import com.cloudhub.drive.core.AppGraph
import com.cloudhub.drive.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private val FallbackAccent = Color(0xFF0A84FF)

fun accentColor(value: String): Color = rememberAccent(value)

private fun rememberAccent(value: String): Color =
    runCatching { Color(AndroidColor.parseColor(value)) }.getOrDefault(FallbackAccent)

private fun resolveTypeface(ctx: Context, s: AppSettings): Typeface? {
    val local = AppGraph.store.localFontPath
    if (!local.isNullOrBlank()) {
        runCatching { Typeface.createFromFile(local) }.getOrNull()?.let { return it }
    }
    if (s.fontUrl.isBlank()) return null
    val ext = s.fontUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
    if (ext.startsWith("woff")) return null
    return runCatching {
        val file = File(ctx.cacheDir, "remote_font_" + s.fontUrl.hashCode() + "." + ext)
        if (!file.exists() || file.length() == 0L) {
            val client = OkHttpClient()
            val request = Request.Builder().url(s.fontUrl).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) file.writeBytes(resp.body?.bytes() ?: ByteArray(0))
            }
        }
        Typeface.createFromFile(file)
    }.getOrNull()
}

@Composable
fun CloudHubTheme(content: @Composable () -> Unit) {
    val s by AppGraph.settingsFlow.collectAsState()
    val ctx = LocalContext.current
    val dark = s.theme == "dark"
    val accent = remember(s.accent) { rememberAccent(s.accent) }
    val base = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val surface = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val onSurface = if (dark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E)

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            secondary = accent,
            background = base,
            surface = surface,
            onSurface = onSurface,
            onPrimary = Color.White
        )
    } else {
        lightColorScheme(
            primary = accent,
            secondary = accent,
            background = base,
            surface = surface,
            onSurface = onSurface,
            onPrimary = Color.White
        )
    }

    val typeface by produceState<Typeface?>(null, s.fontUrl, s.fontName, AppGraph.store.localFontPath) {
        value = withContext(Dispatchers.IO) { resolveTypeface(ctx, s) }
    }
    val typography = remember(typeface) {
        val family = typeface?.let { FontFamily(it) } ?: FontFamily.Default
        Typography(defaultFontFamily = family)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content
    )
}

private fun gradientColors(css: String, base: Color, accent: Color): List<Color> {
    val found = Regex("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}").findAll(css)
        .map { runCatching { Color(AndroidColor.parseColor(it.value)) }.getOrNull() }
        .filterNotNull()
        .toList()
    return when {
        found.size >= 2 -> found
        found.size == 1 -> listOf(found[0], accent)
        else -> listOf(accent, base)
    }
}

@Composable
fun appBackground(s: AppSettings): Modifier {
    val base = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    return when (s.bgMode) {
        "color" -> Modifier.background(
            runCatching { Color(AndroidColor.parseColor(s.bgValue)) }.getOrDefault(base)
        )
        "gradient" -> Modifier.background(
            Brush.linearGradient(gradientColors(s.bgValue, base, accent))
        )
        else -> Modifier.background(base)
    }
}
