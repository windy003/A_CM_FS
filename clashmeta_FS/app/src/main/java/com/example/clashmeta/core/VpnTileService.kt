package com.example.clashmeta.core

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.clashmeta.MainActivity
import com.example.clashmeta.R

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTile()
            handler.postDelayed(this, 1000) // 每秒更新一次
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        handler.post(updateRunnable) // 启动定时更新
    }

    override fun onStopListening() {
        super.onStopListening()
        handler.removeCallbacks(updateRunnable) // 停止定时更新
    }

    override fun onClick() {
        super.onClick()

        if (ClashVpnService.isRunning) {
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

        // 状态会由定时更新自动同步
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable) // 清理资源
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_f)
            tile.label = "ClashMeta"
            val isRunning = ClashVpnService.isRunning
            // VPN 运行时磁贴为灰色(INACTIVE)，未运行时为高亮(ACTIVE)
            // 根据实际测试调整状态映射
            tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
            Log.d("VpnTileService", "Tile updated: isRunning=$isRunning, state=${tile.state}")
        }
    }
}
