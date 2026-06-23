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
import com.example.clashmeta.data.ProxySelectionManager

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClashVpnService.ACTION_VPN_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(ClashVpnService.EXTRA_IS_RUNNING, false)
                Log.d("VpnTileService", "Received broadcast: isRunning=$isRunning")
                // 直接使用广播携带的值，避免重读 SharedPreferences 的竞态条件
                updateTileWithState(isRunning)
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
        updateTileWithState(ClashVpnService.isVpnRunning(this))
    }

    private fun updateTileWithState(isRunning: Boolean) {
        qsTile?.let { tile ->
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_f)

            val proxyName = ProxySelectionManager.getSelectedProxy(this)
            if (isRunning && !proxyName.isNullOrEmpty()) {
                tile.label = proxyName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "运行中"
                }
            } else {
                tile.label = "ClashMeta"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (isRunning) "运行中" else null
                }
            }

            tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

            tile.updateTile()
            Log.d("VpnTileService", "Tile updated: isRunning=$isRunning, proxy=$proxyName, state=${tile.state}")
        }
    }
}
