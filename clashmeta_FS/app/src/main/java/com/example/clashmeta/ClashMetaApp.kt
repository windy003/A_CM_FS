package com.example.clashmeta

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.example.clashmeta.data.BackupManager
import mobile.Mobile
import java.io.File

class ClashMetaApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "clash_vpn_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ClashMetaApp"

        lateinit var instance: ClashMetaApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 尝试从外部存储恢复备份（卸载重装后）
        restoreBackupIfNeeded()

        // 初始化 Clash 核心
        initClashCore()

        // 创建通知渠道
        createNotificationChannel()
    }

    /**
     * 检查并从外部存储恢复备份
     */
    private fun restoreBackupIfNeeded() {
        try {
            val clashDir = File(filesDir, "clash")
            if (!clashDir.exists()) {
                clashDir.mkdirs()
            }
            val restored = BackupManager.restoreFromBackup(this, clashDir)
            if (restored) {
                Log.d(TAG, "Data restored from external backup")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup", e)
        }
    }

    private fun initClashCore() {
        val clashDir = File(filesDir, "clash")
        if (!clashDir.exists()) {
            clashDir.mkdirs()
        }

        try {
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

    fun getClashDir(): File = File(filesDir, "clash")

    fun getConfigFile(): File = File(getClashDir(), "config.yaml")
}
