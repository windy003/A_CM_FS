package com.example.clashmeta.core

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.MainActivity
import com.example.clashmeta.R
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode
import kotlinx.coroutines.*
import mobile.Mobile
import java.io.File

private const val TAG = "ClashVpnService"

class ClashVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.clashmeta.START_VPN"
        const val ACTION_STOP = "com.example.clashmeta.STOP_VPN"

        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "VPN already running")
            return
        }

        Log.d(TAG, "Starting VPN...")

        // 启动前台通知
        startForegroundNotification()

        serviceScope.launch {
            try {
                // 建立 VPN 接口
                Log.d(TAG, "Establishing VPN interface...")
                vpnInterface = establishVpn()
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopSelf()
                    return@launch
                }
                Log.d(TAG, "VPN interface established: ${vpnInterface?.fd}")

                // 初始化 Clash 核心
                val clashDir = ClashMetaApp.instance.getClashDir()
                Log.d(TAG, "Initializing Clash with home dir: ${clashDir.absolutePath}")
                try {
                    Mobile.init(clashDir.absolutePath)
                    Log.d(TAG, "Clash initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Clash", e)
                }

                // 启动 Clash 核心
                val configFile = ClashMetaApp.instance.getConfigFile()
                Log.d(TAG, "Config file: ${configFile.absolutePath}, exists: ${configFile.exists()}")

                if (configFile.exists()) {
                    Log.d(TAG, "Starting Clash with config...")
                    Log.d(TAG, "Config content preview: ${configFile.readText().take(500)}")

                    // 设置配置文件路径 (用于 reloadConfig)
                    Mobile.setConfig(configFile.absolutePath)

                    Mobile.startWithPath(configFile.absolutePath)
                    Log.d(TAG, "Clash core started successfully!")

                    // 启动 TUN 设备，将 VPN 流量转发到 Clash
                    val fd = vpnInterface?.fd ?: -1
                    Log.d(TAG, "Starting TUN with fd: $fd")
                    if (fd > 0) {
                        Mobile.startTun(fd.toLong(), 9000L)
                        Log.d(TAG, "TUN started successfully!")
                    } else {
                        Log.e(TAG, "Invalid VPN file descriptor: $fd")
                    }

                    isRunning = true

                    // 验证代理加载
                    val proxiesJson = Mobile.getProxies()
                    Log.d(TAG, "Loaded proxies: $proxiesJson")
                } else {
                    Log.d(TAG, "Config not found, using default config...")
                    val defaultConfig = createDefaultConfig()
                    configFile.writeText(defaultConfig)
                    Mobile.startWithPath(configFile.absolutePath)

                    // 启动 TUN 设备
                    val fd = vpnInterface?.fd ?: -1
                    if (fd > 0) {
                        Mobile.startTun(fd.toLong(), 9000L)
                    }

                    isRunning = true
                    Log.d(TAG, "Clash started with default config!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN", e)
                stopVpn()
            }
        }
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("ClashMeta")
            .setMtu(9000)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // 应用分应用代理设置
        try {
            val config = AppProxyManager.loadConfig(this)
            Log.d(TAG, "App proxy mode: ${config.mode}, selected apps: ${config.selectedApps.size}")

            when (config.mode) {
                ProxyMode.PROXY_ALL -> {
                    // 代理所有应用，只排除自身
                    builder.addDisallowedApplication(packageName)
                    Log.d(TAG, "Proxy all apps, excluding self")
                }
                ProxyMode.BYPASS_SELECTED -> {
                    // 绕过选中的应用（选中的不走代理）
                    builder.addDisallowedApplication(packageName) // 始终排除自身
                    for (pkg in config.selectedApps) {
                        try {
                            builder.addDisallowedApplication(pkg)
                            Log.d(TAG, "Bypassing app: $pkg")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add disallowed app: $pkg", e)
                        }
                    }
                }
                ProxyMode.ONLY_SELECTED -> {
                    // 仅代理选中的应用（只有选中的走代理）
                    if (config.selectedApps.isNotEmpty()) {
                        for (pkg in config.selectedApps) {
                            if (pkg != packageName) { // 不能包含自身
                                try {
                                    builder.addAllowedApplication(pkg)
                                    Log.d(TAG, "Only proxy app: $pkg")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to add allowed app: $pkg", e)
                                }
                            }
                        }
                    } else {
                        // 如果没有选中任何应用，排除自身（相当于代理所有）
                        builder.addDisallowedApplication(packageName)
                        Log.d(TAG, "No apps selected, proxy all except self")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply app proxy settings", e)
            // 出错时默认排除自身
            builder.addDisallowedApplication(packageName)
        }

        return builder.establish()
    }

    private fun stopVpn() {
        isRunning = false

        serviceScope.launch {
            try {
                // 先停止 TUN
                Mobile.stopTun()
                Log.d(TAG, "TUN stopped")
                // 再停止 Clash 核心
                Mobile.stop()
                Log.d(TAG, "Clash stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Clash", e)
            }
        }

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ClashMetaApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ClashMeta")
            .setContentText("VPN 正在运行")
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ClashMetaApp.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ClashMetaApp.NOTIFICATION_ID, notification)
        }
    }

    private fun createDefaultConfig(): String {
        return """
            mixed-port: 7890
            allow-lan: false
            mode: Rule
            log-level: info
            external-controller: 127.0.0.1:9090

            dns:
              enable: true
              listen: 0.0.0.0:1053
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              nameserver:
                - 223.5.5.5
                - 119.29.29.29
              fallback:
                - 8.8.8.8
                - 1.1.1.1

            proxies: []

            proxy-groups: []

            rules:
              - MATCH,DIRECT
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopVpn()
    }

    override fun onRevoke() {
        stopVpn()
    }
}
