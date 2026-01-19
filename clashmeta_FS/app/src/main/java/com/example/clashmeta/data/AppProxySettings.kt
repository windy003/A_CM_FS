package com.example.clashmeta.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

data class AppProxyConfig(
    var mode: ProxyMode = ProxyMode.PROXY_ALL,
    var selectedApps: MutableSet<String> = mutableSetOf()
)

enum class ProxyMode {
    PROXY_ALL,      // 代理所有应用
    BYPASS_SELECTED, // 绕过选中的应用（选中的不走代理）
    ONLY_SELECTED    // 仅代理选中的应用（只有选中的走代理）
}

object AppProxyManager {
    private const val CONFIG_FILE = "app_proxy_config.json"
    private val gson = Gson()

    private fun getConfigFile(context: Context): File {
        return File(context.filesDir, "clash/$CONFIG_FILE")
    }

    fun loadConfig(context: Context): AppProxyConfig {
        val file = getConfigFile(context)
        if (!file.exists()) {
            return AppProxyConfig()
        }
        return try {
            val json = file.readText()
            gson.fromJson(json, AppProxyConfig::class.java) ?: AppProxyConfig()
        } catch (e: Exception) {
            AppProxyConfig()
        }
    }

    fun saveConfig(context: Context, config: AppProxyConfig) {
        val file = getConfigFile(context)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config))
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return packages.map { appInfo ->
            AppInfo(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
}
