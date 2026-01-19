package com.example.clashmeta.core

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.clashmeta.R

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (ClashVpnService.isRunning) {
            // 停止 VPN
            val intent = Intent(this, ClashVpnService::class.java).apply {
                action = ClashVpnService.ACTION_STOP
            }
            startService(intent)
        } else {
            // 启动 VPN
            val intent = Intent(this, ClashVpnService::class.java).apply {
                action = ClashVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // 延迟更新磁贴状态
        qsTile?.let { tile ->
            tile.state = if (ClashVpnService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_f)
            tile.label = "ClashMeta"
            tile.state = if (ClashVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}
