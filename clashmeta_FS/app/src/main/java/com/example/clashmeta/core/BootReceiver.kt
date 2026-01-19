package com.example.clashmeta.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // TODO: 根据设置决定是否自动启动
            // val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            // if (prefs.getBoolean("auto_start", false)) {
            //     startVpnService(context)
            // }
        }
    }

    private fun startVpnService(context: Context) {
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
