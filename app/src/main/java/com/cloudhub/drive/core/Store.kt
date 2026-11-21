package com.cloudhub.drive.core

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("cloudhub_drive_v1", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE)!!.ifBlank { DEFAULT_BASE }
        set(value) = sp.edit().putString("base_url", value.trim().trimEnd('/') + "/").apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value).apply()

    var settings: AppSettings
        get() = AppSettings.from(
            runCatching { JSONObject(sp.getString("settings", "") ?: "") }.getOrNull()
        )
        set(value) = sp.edit().putString("settings", value.toJson().toString()).apply()

    var localFontPath: String?
        get() = sp.getString("local_font_path", null)
        set(value) = sp.edit().putString("local_font_path", value).apply()

    fun logout() {
        sp.edit().remove("token").apply()
    }

    companion object {
        const val DEFAULT_BASE = "https://cloud.iobit.de5.net/"
        const val LOGIN_PASSWORD = "liyikui917"
    }
}

class DriveApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
    }

    companion object {
        lateinit var instance: DriveApp
            private set
    }
}

object AppGraph {
    val store: Store get() = DriveApp.instance.store
    val api: Api by lazy { Api(store) }
    val repo: Repo by lazy { Repo(api) }
    val uploader: Uploader by lazy { Uploader(DriveApp.instance, api, repo) }
    val settingsFlow: MutableStateFlow<AppSettings> by lazy { MutableStateFlow(store.settings) }
}
