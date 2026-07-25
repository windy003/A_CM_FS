package com.example.clashmeta.core

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.MainActivity
import com.example.clashmeta.R
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode
import com.example.clashmeta.data.ProxySelectionManager
import kotlinx.coroutines.*
import mobile.Mobile
import java.io.File

private const val TAG = "ClashVpnService"

class ClashVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.clashmeta.START_VPN"
        const val ACTION_STOP = "com.example.clashmeta.STOP_VPN"
        const val ACTION_VPN_STATE_CHANGED = "com.example.clashmeta.VPN_STATE_CHANGED"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.clashmeta.UPDATE_NOTIFICATION"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_PROXY_NAME = "proxy_name"

        private const val PREFS_NAME = "vpn_state"
        private const val KEY_IS_RUNNING = "is_running"

        // TUN MTU：VPN 接口(setMtu) 与 sing-tun 协议栈(startTun) 必须一致。
        // 用 9000（与官方 CMFA 一致）：配合 gvisor 栈可减少分包、提升 TCP 吞吐。
        // 注：QUIC 自带路径 MTU 探测，数据报始终限制在 ~1200-1400 字节，不会因接口 MTU=9000
        // 就发超大包，故不影响 TikTok 等走 QUIC 的加载（CMFA 同款配置即证明）。
        private const val TUN_MTU = 9000

        var isRunning = false
            private set(value) {
                field = value
            }

        // 防止重复启动的标志
        @Volatile
        private var isStarting = false

        // 跨进程读取 VPN 运行状态
        fun isVpnRunning(context: android.content.Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_IS_RUNNING, false)
        }

        /**
         * 仅把配置加载进内核（不建立 VPN/TUN），用于未开启 VPN 时预览节点列表。
         * - 内核未运行：setConfig + startWithPath 加载配置（GetProxies 即可返回节点）
         * - 内核已运行但 VPN 未开（预览态）：reloadConfig 刷新为最新订阅
         * - VPN 正在运行：无需处理，直接返回 true
         * 返回 true 表示内核中已有可用代理数据。注意：需在 IO 线程调用。
         */
        fun ensureCoreLoadedForPreview(context: android.content.Context): Boolean {
            return try {
                val configFile = ClashMetaApp.instance.getConfigFile()
                if (!configFile.exists()) return false
                // 与正式启动保持一致的配置注入，避免解析差异
                com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                if (Mobile.isRunning()) {
                    // 已在运行；若只是预览态则刷新配置以反映最新订阅
                    if (!isVpnRunning(context)) {
                        Mobile.setConfig(configFile.absolutePath)
                        Mobile.reloadConfig()
                    }
                } else {
                    Mobile.setConfig(configFile.absolutePath)
                    Mobile.startWithPath(configFile.absolutePath)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "ensureCoreLoadedForPreview failed", e)
                false
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // 专门用于停止时拆除 TUN 的作用域：绝不能在 onDestroy 里被取消，
    // 否则 Mobile.stopTun() 可能在被调度前就取消，导致原生 TUN 不释放、锁图标残留。
    private val stopScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 防止重复拆除
    @Volatile
    private var isStopping = false

    // 标记 gomobile 是否已接管 TUN 的 fd。
    // Mobile.startTun() 会直接接管并在 stopTun() 中关闭该 fd，
    // 因此启动成功后 Java 侧不能再 close，否则会 double-close 导致 fdsan abort。
    @Volatile
    private var tunStarted = false

    private fun setRunningState(running: Boolean) {
        isRunning = running
        // 保存到 SharedPreferences 供磁贴服务跨进程读取
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_RUNNING, running)
            .apply()

        // 发送广播通知状态变化（面板打开时磁贴可直接收到）
        val intent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, running)
            setPackage(packageName) // 限制在本应用内
        }
        sendBroadcast(intent)
        // 同时用 requestListeningState 唤醒磁贴（面板未打开时广播无法收到）
        requestTileUpdate()
        Log.d(TAG, "VPN running state set to: $running, broadcast sent")
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_UPDATE_NOTIFICATION -> {
                val proxyName = intent.getStringExtra(EXTRA_PROXY_NAME)
                updateNotification(proxyName)
                // requestListeningState 让磁贴服务主动重新读取节点名并刷新（ACTIVE_TILE=true 专用）
                requestTileUpdate()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "VPN already running")
            return
        }

        if (isStarting) {
            Log.d(TAG, "VPN is already starting, ignoring duplicate request")
            return
        }

        isStarting = true
        isStopping = false
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
                    isStarting = false
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

                    // 按局域网代理开关注入 allow-lan / bind-address
                    com.example.clashmeta.data.LanProxyManager.applyToConfigFile()

                    Log.d(TAG, "Config content preview: ${configFile.readText().take(500)}")

                    // 设置配置文件路径 (用于 reloadConfig)
                    Mobile.setConfig(configFile.absolutePath)

                    // 内核可能已被“预览节点”提前加载，重复 startWithPath 会报错；
                    // 已运行则改为重载当前配置，未运行才启动。
                    if (Mobile.isRunning()) {
                        Mobile.reloadConfig()
                        Log.d(TAG, "Clash core already running (preview), reloaded config")
                    } else {
                        Mobile.startWithPath(configFile.absolutePath)
                        Log.d(TAG, "Clash core started successfully!")
                    }

                    // 启动 TUN 设备，将 VPN 流量转发到 Clash
                    val fd = vpnInterface?.fd ?: -1
                    Log.d(TAG, "Starting TUN with fd: $fd")
                    if (fd > 0) {
                        Mobile.startTun(fd.toLong(), TUN_MTU.toLong())
                        tunStarted = true  // gomobile 已接管 fd，停止时由 stopTun 关闭
                        Log.d(TAG, "TUN started successfully!")
                    } else {
                        Log.e(TAG, "Invalid VPN file descriptor: $fd")
                    }

                    setRunningState(true)
                    isStarting = false

                    // 验证代理加载
                    val proxiesJson = Mobile.getProxies()
                    Log.d(TAG, "Loaded proxies: $proxiesJson")

                    // 恢复之前选择的节点
                    restoreProxySelection()
                } else {
                    Log.d(TAG, "Config not found, using default config...")
                    val defaultConfig = createDefaultConfig()
                    configFile.writeText(defaultConfig)
                    // 按局域网代理开关注入 allow-lan / bind-address
                    com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                    if (Mobile.isRunning()) {
                        Mobile.reloadConfig()
                    } else {
                        Mobile.startWithPath(configFile.absolutePath)
                    }

                    // 启动 TUN 设备
                    val fd = vpnInterface?.fd ?: -1
                    if (fd > 0) {
                        Mobile.startTun(fd.toLong(), TUN_MTU.toLong())
                        tunStarted = true  // gomobile 已接管 fd，停止时由 stopTun 关闭
                    }

                    setRunningState(true)
                    isStarting = false
                    Log.d(TAG, "Clash started with default config!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN", e)
                isStarting = false
                stopVpn()
            }
        }
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("ClashMeta")
            .setMtu(TUN_MTU)
            .setBlocking(false)
            .addAddress("172.19.0.1", 30)
            .addDnsServer("172.19.0.2")  // TUN portal 地址，由 Clash 拦截处理 DNS

        // 添加路由，排除局域网流量（用于 Miracast 投屏等）
        // 使用手动添加公网路由的方式，避开私有 IP 段
        addPublicNetworkRoutes(builder)
        // 为 DNS 地址单独加路由，确保 DNS 查询一定走 TUN
        builder.addRoute("172.19.0.2", 32)
        Log.d(TAG, "Using public routes for LAN bypass")

        // 应用分应用代理设置
        try {
            val config = AppProxyManager.loadConfig()
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

        // 设置系统 HTTP 代理，供 Opera 等读取系统代理的浏览器使用
        // 端口与 config.yaml 注入的 mixed-port 保持一致（用户可自定义）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proxyPort = com.example.clashmeta.data.LanProxyManager.getPort(this)
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    "127.0.0.1", proxyPort,
                    listOf("localhost", "127.*", "10.*", "172.*", "192.168.*")
                )
            )
        }

        return builder.establish()
    }

    /**
     * 为 Android 12 及以下版本添加公网路由，排除私有 IP 段
     * 这样局域网流量（如 Miracast 投屏）不会走 VPN
     */
    private fun addPublicNetworkRoutes(builder: Builder) {
        // 排除以下私有/保留 IP 段:
        // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4

        // 0.0.0.0/8 - 保留
        builder.addRoute("1.0.0.0", 8)
        builder.addRoute("2.0.0.0", 7)
        builder.addRoute("4.0.0.0", 6)
        builder.addRoute("8.0.0.0", 7)
        // 跳过 10.0.0.0/8
        builder.addRoute("11.0.0.0", 8)
        builder.addRoute("12.0.0.0", 6)
        builder.addRoute("16.0.0.0", 4)
        builder.addRoute("32.0.0.0", 3)
        builder.addRoute("64.0.0.0", 2)
        builder.addRoute("128.0.0.0", 3)
        builder.addRoute("160.0.0.0", 5)
        // 跳过 169.254.0.0/16 (link-local)
        builder.addRoute("168.0.0.0", 8)
        builder.addRoute("170.0.0.0", 7)
        // 跳过 172.16.0.0/12
        builder.addRoute("172.0.0.0", 12)
        builder.addRoute("172.32.0.0", 11)
        builder.addRoute("172.64.0.0", 10)
        builder.addRoute("172.128.0.0", 9)
        builder.addRoute("173.0.0.0", 8)
        builder.addRoute("174.0.0.0", 7)
        builder.addRoute("176.0.0.0", 4)
        builder.addRoute("192.0.0.0", 9)
        builder.addRoute("192.128.0.0", 11)
        builder.addRoute("192.160.0.0", 13)
        // 跳过 192.168.0.0/16
        builder.addRoute("192.169.0.0", 16)
        builder.addRoute("192.170.0.0", 15)
        builder.addRoute("192.172.0.0", 14)
        builder.addRoute("192.176.0.0", 12)
        builder.addRoute("192.192.0.0", 10)
        builder.addRoute("193.0.0.0", 8)
        builder.addRoute("194.0.0.0", 7)
        builder.addRoute("196.0.0.0", 6)
        builder.addRoute("200.0.0.0", 5)
        builder.addRoute("208.0.0.0", 4)
        // 跳过 224.0.0.0/4 (多播)
        // 跳过 240.0.0.0/4 (保留)
    }

    private fun stopVpn() {
        // 幂等：ACTION_STOP 已触发拆除时，onDestroy/onRevoke 的重复调用直接跳过
        if (isStopping) {
            Log.d(TAG, "stopVpn already in progress, skip")
            return
        }
        isStopping = true

        setRunningState(false)
        isStarting = false

        // 在不会被 onDestroy 取消的 stopScope 上拆除，确保 Mobile.stopTun() 一定执行。
        // 先释放原生 TUN 并关闭 fd（这才能让系统及时移除状态栏 VPN 锁图标），
        // 再在主线程移除前台通知并停止服务，最后才停 Clash 核心。
        val nativeOwnsFd = tunStarted
        tunStarted = false

        stopScope.launch {
            // 1) 释放 TUN —— gomobile 会在此关闭它接管的 fd，系统随即撤销 VPN、锁图标消失
            if (nativeOwnsFd) {
                try {
                    Mobile.stopTun()
                    Log.d(TAG, "TUN stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping TUN", e)
                }
                // fd 已由原生侧关闭：仅解除 Java 侧所有权，避免 double-close 触发 fdsan abort
                try {
                    vpnInterface?.detachFd()
                } catch (e: Exception) {
                    Log.e(TAG, "Error detaching vpn fd", e)
                }
            } else {
                // TUN 从未启动，fd 仍归 Java 所有，需自行关闭
                try {
                    vpnInterface?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing vpn interface", e)
                }
            }
            vpnInterface = null

            // 3) 收尾：移除前台通知、停止服务（切回主线程）
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            // 4) 最后停 Clash 核心，慢一点也不再影响锁图标
            try {
                Mobile.stop()
                Log.d(TAG, "Clash stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Clash", e)
            }
        }
    }

    /**
     * 恢复之前保存的节点选择
     */
    private fun restoreProxySelection() {
        val savedProxy = ProxySelectionManager.getSelectedProxy(this)
        if (savedProxy.isNullOrEmpty()) {
            Log.d(TAG, "No saved proxy selection to restore")
            return
        }

        val groupName = ProxySelectionManager.getProxyGroup(this)
        Log.d(TAG, "Restoring proxy selection: $savedProxy in group: $groupName")

        serviceScope.launch {
            // 等待一小段时间确保 Clash 核心完全启动
            delay(500)
            try {
                try {
                    Mobile.selectProxy(groupName, savedProxy)
                    Log.d(TAG, "Successfully restored proxy: $savedProxy in group: $groupName")
                } catch (e: Exception) {
                    // 如果第一个组失败，尝试 GLOBAL
                    Mobile.selectProxy("GLOBAL", savedProxy)
                    Log.d(TAG, "Successfully restored proxy: $savedProxy in group: GLOBAL")
                }
                // 恢复成功后更新通知和磁贴
                updateNotification(savedProxy)
                requestTileUpdate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore proxy selection: $savedProxy", e)
            }
        }
    }

    private fun buildNotification(proxyName: String? = null): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val displayProxy = proxyName ?: ProxySelectionManager.getSelectedProxy(this)
        val contentText = if (!displayProxy.isNullOrEmpty()) "节点: $displayProxy" else "VPN 正在运行"

        return NotificationCompat.Builder(this, ClashMetaApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ClashMeta")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
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

    private fun updateNotification(proxyName: String? = null) {
        val notification = buildNotification(proxyName)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(ClashMetaApp.NOTIFICATION_ID, notification)
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

    /**
     * 通知快速设置磁贴主动刷新自身（适用于 ACTIVE_TILE=true）
     * 面板未打开时广播无法被收到，使用此方法代替广播触发磁贴更新
     */
    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                this,
                ComponentName(this, VpnTileService::class.java)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 先确保拆除逻辑已启动（stopScope 不受下面的取消影响），再取消其它任务
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        stopVpn()
    }
}
