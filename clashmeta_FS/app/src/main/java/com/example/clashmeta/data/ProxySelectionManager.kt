package com.example.clashmeta.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 管理节点选择的持久化存储
 * 用于在VPN重启后恢复用户之前选择的节点
 */
object ProxySelectionManager {
    private const val TAG = "ProxySelectionManager"
    private const val PREF_NAME = "proxy_selection"
    private const val KEY_SELECTED_PROXY = "selected_proxy"
    private const val KEY_PROXY_GROUP = "proxy_group"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 保存用户选择的节点
     */
    fun saveSelectedProxy(context: Context, proxyName: String, groupName: String = "🚀 节点选择") {
        getPrefs(context).edit().apply {
            putString(KEY_SELECTED_PROXY, proxyName)
            putString(KEY_PROXY_GROUP, groupName)
            apply()
        }
        Log.d(TAG, "Saved selected proxy: $proxyName in group: $groupName")
    }

    /**
     * 获取之前保存的节点名称
     */
    fun getSelectedProxy(context: Context): String? {
        return getPrefs(context).getString(KEY_SELECTED_PROXY, null)
    }

    /**
     * 获取之前保存的代理组名称
     */
    fun getProxyGroup(context: Context): String {
        return getPrefs(context).getString(KEY_PROXY_GROUP, "🚀 节点选择") ?: "🚀 节点选择"
    }

    /**
     * 清除保存的节点选择
     */
    fun clearSelection(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "Cleared proxy selection")
    }
}
