package com.example.clashmeta.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import com.google.gson.Gson
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

    private fun getConfigFile(): File {
        return File("/sdcard/1/clashMeta_FS", CONFIG_FILE)
    }

    fun loadConfig(): AppProxyConfig {
        val file = getConfigFile()
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

    fun saveConfig(config: AppProxyConfig) {
        val file = getConfigFile()
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(config))
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val appMap = mutableMapOf<String, AppInfo>()

        // 方法1: 使用 getInstalledApplications（需要 QUERY_ALL_PACKAGES 权限）
        try {
            val packages: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }

            packages.forEach { appInfo ->
                appMap[appInfo.packageName] = AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            Log.d("AppProxyManager", "Method 1 (getInstalledApplications): ${packages.size} apps")
        } catch (e: Exception) {
            Log.e("AppProxyManager", "Method 1 failed", e)
        }

        // 方法2: 通过 LAUNCHER intent 查询有桌面图标的应用
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val launcherApps: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }

            launcherApps.forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (!appMap.containsKey(packageName)) {
                    try {
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(packageName, 0)
                        }
                        appMap[packageName] = AppInfo(
                            packageName = packageName,
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) {
                        // 忽略获取失败的应用
                    }
                }
            }
            Log.d("AppProxyManager", "Method 2 (LAUNCHER intent): ${launcherApps.size} apps, total now: ${appMap.size}")
        } catch (e: Exception) {
            Log.e("AppProxyManager", "Method 2 failed", e)
        }

        // 方法3: 查询有网络权限的应用（对 VPN 分应用更相关）
        try {
            val networkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://example.com")
            }

            val networkApps: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    networkIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(networkIntent, 0)
            }

            networkApps.forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (!appMap.containsKey(packageName)) {
                    try {
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(packageName, 0)
                        }
                        appMap[packageName] = AppInfo(
                            packageName = packageName,
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) {
                        // 忽略获取失败的应用
                    }
                }
            }
            Log.d("AppProxyManager", "Method 3 (network intent): ${networkApps.size} apps, total now: ${appMap.size}")
        } catch (e: Exception) {
            Log.e("AppProxyManager", "Method 3 failed", e)
        }

        Log.d("AppProxyManager", "Total unique apps: ${appMap.size}")

        return appMap.values.toList()
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
}
