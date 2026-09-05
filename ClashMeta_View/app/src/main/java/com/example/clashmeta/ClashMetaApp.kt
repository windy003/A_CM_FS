package com.example.clashmeta

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.google.android.material.color.DynamicColors
import mobile.Mobile
import java.io.File

class ClashMetaApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "clash_vpn_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ClashMetaApp"
        // 直接使用外部存储目录（View 版使用独立目录，避免与 Compose 原版共用同一份
        // clash home 目录导致 fake-ip/DNS 缓存与节点选择状态互相冲突）
        const val CLASH_DIR_PATH = "/sdcard/1/clashMeta_View"

        lateinit var instance: ClashMetaApp
            private set
    }

    @Volatile
    private var resolvedClashDir: File? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 动态取色（Android 12+），对应 Compose 版的 dynamicColor
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 初始化 Clash 核心
        initClashCore()

        // 创建通知渠道
        createNotificationChannel()
    }

    private fun initClashCore() {
        try {
            val clashDir = getClashDir()
            if (!clashDir.exists()) {
                clashDir.mkdirs()
            }
            Mobile.init(clashDir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ClashMeta VPN 运行状态"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 数据目录。优先用外置的 [CLASH_DIR_PATH]（卸载后保留），
     * 但 Android 11+ 若拿不到「所有文件访问权限」（Android 13+ 侧载安装常被系统的
     * “受限设置”挡住，部分机型/系统版本直接禁用该开关），该目录不可写：
     * 此时必须退回应用内部目录，否则写 config.yaml 会抛 EACCES，
     * 表现为「VPN 开关一打开就自动关闭」。
     */
    fun getClashDir(): File {
        val cached = resolvedClashDir
        if (cached != null) {
            // 已经用上外置目录就不再重新判定
            if (cached.absolutePath == CLASH_DIR_PATH) return cached
            // 退回过内部目录：仅当用户后来授予了权限才重新判定
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) return cached
        }
        val dir = resolveClashDir()
        resolvedClashDir = dir
        return dir
    }

    private fun resolveClashDir(): File {
        val external = File(CLASH_DIR_PATH)
        try {
            if (!external.exists()) external.mkdirs()
            if (external.isDirectory && isWritable(external)) return external
        } catch (e: Exception) {
            Log.w(TAG, "External clash dir unusable: ${e.message}")
        }

        val fallback = File(filesDir, "clash")
        if (!fallback.exists()) fallback.mkdirs()
        // 外置目录只读但可读时，把已有配置搬进来，避免用户看起来「订阅全没了」
        try {
            val externalConfig = File(external, "config.yaml")
            val fallbackConfig = File(fallback, "config.yaml")
            if (externalConfig.canRead() && !fallbackConfig.exists()) {
                externalConfig.copyTo(fallbackConfig, overwrite = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate config to internal dir: ${e.message}")
        }
        Log.w(TAG, "External storage not writable, using internal dir: ${fallback.absolutePath}")
        return fallback
    }

    /** 真正写一个探针文件来判断可写性：Android 11+ 下 canWrite() 会撒谎 */
    private fun isWritable(dir: File): Boolean = try {
        val probe = File(dir, ".write_probe")
        if (probe.exists()) probe.delete()
        val created = probe.createNewFile()
        if (created) probe.delete()
        created
    } catch (e: Exception) {
        false
    }

    fun getConfigFile(): File = File(getClashDir(), "config.yaml")
}
