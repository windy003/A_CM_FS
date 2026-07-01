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

    companion object {
        // 点击后记录用户的“意图状态”，避免在 VPN 异步启动/停止的过渡期读到过时的
        // SharedPreferences 值。收到真实状态广播后清除。
        @Volatile
        private var pendingState: Boolean? = null
        @Volatile
        private var pendingStateTime: Long = 0L
        // 过渡窗口：超过此时间仍未收到真实状态广播，则认为意图已失效
        private const val PENDING_TIMEOUT_MS = 10_000L

        private fun setPending(state: Boolean) {
            pendingState = state
            pendingStateTime = System.currentTimeMillis()
        }

        private fun clearPending() {
            pendingState = null
        }

        /** 过渡期内返回意图状态，否则返回持久化的真实状态 */
        private fun effectiveRunning(context: Context): Boolean {
            val pending = pendingState
            if (pending != null &&
                System.currentTimeMillis() - pendingStateTime < PENDING_TIMEOUT_MS
            ) {
                return pending
            }
            return ClashVpnService.isVpnRunning(context)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ClashVpnService.ACTION_VPN_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(ClashVpnService.EXTRA_IS_RUNNING, false)
                Log.d("VpnTileService", "Received broadcast: isRunning=$isRunning")
                // 收到真实状态，清除意图并直接使用广播携带的值
                clearPending()
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

        // 用“有效状态”判断：过渡期内以上次点击的意图为准，避免读到过时的持久化值
        // 导致连点时把刚启动的 VPN 又停掉（或反之）。
        val isRunning = effectiveRunning(this)
        if (isRunning) {
            // 立即乐观更新磁贴为“已关闭”，给用户即时反馈，避免重复点击
            setPending(false)
            updateTileWithState(false)

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

            // 立即乐观更新磁贴为“已开启”，给用户即时反馈，避免重复点击
            setPending(true)
            updateTileWithState(true)

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

        // 真实状态最终会由广播/requestListeningState 同步并清除意图
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        // 过渡期内以意图状态为准，避免刷新时闪回过时状态
        updateTileWithState(effectiveRunning(this))
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
