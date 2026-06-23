package com.example.clashmeta.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, starting VPN service...")

        // 检查是否已经获得过 VPN 权限（prepare 返回 null 表示已授权）
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            Log.w(TAG, "VPN permission not granted, cannot auto start")
            return
        }

        val serviceIntent = Intent(context, ClashVpnService::class.java).apply {
            action = ClashVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
