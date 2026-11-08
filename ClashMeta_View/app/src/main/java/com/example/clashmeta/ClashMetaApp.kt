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
        // 默认使用外部存储目录（View 版使用独立目录，避免与 Compose 原版共用同一份
        // clash home 目录导致 fake-ip/DNS 缓存与节点选择状态互相冲突）。
        // 用户可在设置页改成任意可写目录，选择会记在 [PREFS_NAME] 里。
        const val CLASH_DIR_PATH = "/sdcard/1/clashMeta_View"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_CLASH_DIR = "clash_dir"

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
     * 用户在设置页选择的目录；没选过返回 null（使用默认目录）。
     */
    fun getSavedClashDirPath(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_CLASH_DIR, null)

    /**
     * 切换数据目录并持久化。传 null 表示恢复默认目录。
     *
     * 会立刻用新目录重新初始化内核；如果 VPN 正在运行，调用方应先停掉 VPN，
     * 否则运行中的内核会继续使用旧目录里的配置。
     */
    fun setClashDir(path: String?) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .apply {
                if (path.isNullOrBlank()) remove(KEY_CLASH_DIR) else putString(KEY_CLASH_DIR, path)
            }
            .apply()
        resolvedClashDir = null
        initClashCore()
    }

    /**
     * 数据目录。优先用用户自定义目录，其次用外置的 [CLASH_DIR_PATH]（卸载后保留）。
     * 但 Android 11+ 若拿不到「所有文件访问权限」（Android 13+ 侧载安装常被系统的
     * “受限设置”挡住，部分机型/系统版本直接禁用该开关），这些目录不可写：
     * 此时必须退回应用内部目录，否则写 config.yaml 会抛 EACCES，
     * 表现为「VPN 开关一打开就自动关闭」。
     */
    fun getClashDir(): File {
        val cached = resolvedClashDir
        if (cached != null) {
            // 已经用上外置/自定义目录就不再重新判定
            if (cached.absolutePath != internalFallbackDir().absolutePath) return cached
            // 退回过内部目录：仅当用户后来授予了权限才重新判定
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) return cached
        }
        val dir = resolveClashDir()
        resolvedClashDir = dir
        return dir
    }

    private fun internalFallbackDir(): File = File(filesDir, "clash")

    private fun resolveClashDir(): File {
        // 1) 用户在设置页指定的目录
        val savedPath = getSavedClashDirPath()
        if (!savedPath.isNullOrBlank()) {
            val custom = File(savedPath)
            try {
                if (!custom.exists()) custom.mkdirs()
                if (custom.isDirectory && isWritable(custom)) return custom
            } catch (e: Exception) {
                Log.w(TAG, "Custom clash dir unusable: ${e.message}")
            }
            Log.w(TAG, "Custom clash dir not writable, falling back: $savedPath")
        }

        // 2) 默认外置目录
        val external = File(CLASH_DIR_PATH)
        try {
            if (!external.exists()) external.mkdirs()
            if (external.isDirectory && isWritable(external)) return external
        } catch (e: Exception) {
            Log.w(TAG, "External clash dir unusable: ${e.message}")
        }

        val fallback = internalFallbackDir()
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

    /** 供设置页校验用户选中的目录 */
    fun isClashDirWritable(dir: File): Boolean = dir.isDirectory && isWritable(dir)

    /**
     * 把 [from] 里的用户数据（config.yaml、订阅、节点、分应用设置等）复制到 [to]。
     *
     * 只在目标目录还没有 config.yaml 时调用：用户主动选了一个已有配置的目录，
     * 说明他就是想用那份配置，不能覆盖。缓存文件不搬（内核会重建）。
     *
     * @return 实际复制的文件数
     */
    fun migrateUserData(from: File, to: File): Int {
        if (from.absolutePath == to.absolutePath) return 0
        val files = from.listFiles() ?: return 0
        var copied = 0
        for (file in files) {
            if (!file.isFile) continue
            if (file.name == "cache.db" || file.name == ".write_probe") continue
            val dest = File(to, file.name)
            if (dest.exists()) continue
            try {
                file.copyTo(dest, overwrite = false)
                copied++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy ${file.name} to custom dir: ${e.message}")
            }
        }
        return copied
    }

    fun getConfigFile(): File = File(getClashDir(), "config.yaml")
}
