package com.example.clashmeta.data

import android.content.Context
import android.util.Log
import com.example.clashmeta.ClashMetaApp
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网 HTTP 代理开关 + 自定义端口管理。
 *
 * 会把 clash 的 config.yaml 顶层注入：
 *   mixed-port: <自定义端口>      （始终注入，本机/局域网共用同一代理端口）
 *   allow-lan: true              （仅开关开启时）
 *   bind-address: '*'            （仅开关开启时）
 * 开启后该端口会监听 0.0.0.0，同一局域网内的其他设备即可用
 * 「本机IP:端口」作为 HTTP/SOCKS 代理。
 */
object LanProxyManager {
    private const val TAG = "LanProxyManager"
    private const val PREF_NAME = "lan_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"

    /** 默认代理端口 */
    const val DEFAULT_PORT = 7890

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** 获取自定义代理端口，未设置时为 DEFAULT_PORT */
    fun getPort(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)
    }

    fun setPort(context: Context, port: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, port)
            .apply()
    }

    /**
     * 根据当前开关与端口，把 mixed-port / allow-lan / bind-address 写入正在使用的 config.yaml。
     * 在 VPN 启动、切换配置、切换开关/端口前调用。
     */
    fun applyToConfigFile() {
        try {
            val file = ClashMetaApp.instance.getConfigFile()
            if (!file.exists()) return
            val ctx = ClashMetaApp.instance
            var patched = patchConfig(file.readText(), isEnabled(ctx), getPort(ctx))
            // QUIC(UDP 443) 分流：境外 QUIC 转发到代理(照抄 Xray)、国内 QUIC 直连。
            // TikTok 直连裸 IP 的 QUIC 无域名，靠这条按 IP 归属地放行走代理，修复缩略图加载。
            patched = QuicRuleManager.patchRules(patched)
            // TikTok 域名规则(负责有域名的 TCP，如搜索接口)，插到 QUIC 规则之上。
            patched = TikTokRuleManager.patchRules(patched)
            // 把 server 为 8.8.8.8 的假节点从自动选择/故障转移组里剔除，避免自动选中死节点
            patched = AutoGroupSanitizer.patch(patched)
            file.writeText(patched)
            Log.d(TAG, "Applied LAN setting to config.yaml (enabled=${isEnabled(ctx)}, port=${getPort(ctx)})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LAN setting to config", e)
        }
    }

    /**
     * 在 YAML 文本里设置顶层 mixed-port，并按需注入 allow-lan / bind-address。
     * 只处理顶层（行首无缩进）的键，避免误改 dns 等嵌套段落里的同名键。
     * 同时移除顶层 port / socks-port，确保只有 mixed-port 一个代理端口、避免冲突。
     */
    fun patchConfig(configText: String, enabled: Boolean, port: Int): String {
        val filtered = configText.split("\n").filterNot { line ->
            line.matches(Regex("^allow-lan:.*")) ||
                line.matches(Regex("^bind-address:.*")) ||
                line.matches(Regex("^mixed-port:.*")) ||
                line.matches(Regex("^port:.*")) ||
                line.matches(Regex("^socks-port:.*"))
        }.toMutableList()

        // 找插入位置：跳过开头的文档标记 "---"
        var insertAt = 0
        if (filtered.isNotEmpty() && filtered[0].trim() == "---") {
            insertAt = 1
        }

        // 代理端口始终设置
        filtered.add(insertAt, "mixed-port: $port")
        // 仅在开启局域网时对外开放
        if (enabled) {
            filtered.add(insertAt, "bind-address: '*'")
            filtered.add(insertAt, "allow-lan: true")
        }

        return filtered.joinToString("\n")
    }

    /**
     * 获取本机局域网 IPv4 地址（192.168.x / 10.x / 172.16-31.x），找不到返回 null。
     */
    fun getLanIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get LAN IP", e)
        }
        return null
    }
}
