package com.example.clashmeta

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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

    fun getClashDir(): File = File(CLASH_DIR_PATH)

    fun getConfigFile(): File = File(getClashDir(), "config.yaml")
}
