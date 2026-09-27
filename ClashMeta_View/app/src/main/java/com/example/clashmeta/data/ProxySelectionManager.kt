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
    fun saveSelectedProxy(context: Context, proxyName: String, groupName: String) {
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
     * 获取之前保存的代理组名称；没有记录时返回空串。
     *
     * 这里以前默认 `🚀 节点选择`——那是国内订阅生成器的约定俗成名字，不是所有机场都用
     * （实测 BoostNet 的组就叫 `BoostNet`）。猜错了就会把选择落到不存在的组、
     * 进而退到规则模式下根本不参与路由的 GLOBAL，表现为「选了节点没反应」。
     * 现在组名一律由内核给出，不再猜。
     */
    fun getProxyGroup(context: Context): String {
        return getPrefs(context).getString(KEY_PROXY_GROUP, "") ?: ""
    }

    /**
     * 清除保存的节点选择
     */
    fun clearSelection(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "Cleared proxy selection")
    }
}
