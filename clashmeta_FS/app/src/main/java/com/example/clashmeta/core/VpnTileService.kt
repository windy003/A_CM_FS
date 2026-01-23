package com.example.clashmeta.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.clashmeta.MainActivity
import com.example.clashmeta.R

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    // 检测是否是 LG Wing（需要反转状态的设备）
    private val isLGWing: Boolean by lazy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase().replace("-", "")
        val isLG = manufacturer.contains("lg") && (model.contains("wing") || model.contains("lmf100"))
        Log.d("VpnTileService", "Device: $manufacturer ${Build.MODEL}, normalized: $model, isLGWing: $isLG")
        isLG
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClashVpnService.ACTION_VPN_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(ClashVpnService.EXTRA_IS_RUNNING, false)
                Log.d("VpnTileService", "Received broadcast: isRunning=$isRunning")
                updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // 注册广播接收器
        val filter = IntentFilter(ClashVpnService.ACTION_VPN_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        Log.d("VpnTileService", "Broadcast receiver registered")
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        // 注销广播接收器
        try {
            unregisterReceiver(stateReceiver)
            Log.d("VpnTileService", "Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.w("VpnTileService", "Failed to unregister receiver", e)
        }
    }

    override fun onClick() {
        super.onClick()

        val isRunning = ClashVpnService.isVpnRunning(this)
        if (isRunning) {
            // 停止 VPN
            Log.d("VpnTileService", "Stopping VPN from tile")
            val intent = Intent(this, ClashVpnService::class.java).apply {
                action = ClashVpnService.ACTION_STOP
            }
            startService(intent)
        } else {
            // 检查 VPN 权限
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // 没有权限，打开主界面让用户授权
                Log.d("VpnTileService", "VPN permission not granted, opening MainActivity")
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(mainIntent)
                return
            }

            // 启动 VPN
            Log.d("VpnTileService", "Starting VPN from tile")
            val intent = Intent(this, ClashVpnService::class.java).apply {
                action = ClashVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // 状态会由广播自动同步
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            // 使用跨进程方法读取 VPN 状态
            val isRunning = ClashVpnService.isVpnRunning(this)

            // 始终使用同一个图标，通过 STATE 控制高亮/灰色
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_f)
            tile.label = if (isRunning) "ClashMeta (运行中)" else "ClashMeta"

            // LG Wing 的状态显示逻辑与其他设备相反
            tile.state = if (isLGWing) {
                // LG Wing: 运行时显示 INACTIVE（灰色），停止时显示 ACTIVE（高亮）
                if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            } else {
                // 其他设备: 运行时显示 ACTIVE（高亮），停止时显示 INACTIVE（灰色）
                if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }

            tile.updateTile()
            Log.d("VpnTileService", "Tile updated: isRunning=$isRunning, isLGWing=$isLGWing, state=${tile.state}")
        }
    }
}
