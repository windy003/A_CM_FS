package com.example.clashmeta.ui.proxy

/**
 * 内核返回的代理信息（[mobile.Mobile.getProxies] 的 JSON 逐字对应）。
 *
 * [now] / [all] / [selectable] 只有代理组才有值。它们必须来自内核而不是 UI 解析 config.yaml，
 * 否则组成员被内核清洗、或订阅重载之后，界面显示的和实际生效的会对不上。
 */
data class ProxyInfo(
    val name: String = "",
    val type: String = "",
    val alive: Boolean = false,
    val server: String = "",
    /** 组当前选中的成员；非组为 null */
    val now: String? = null,
    /** 组成员（配置顺序）；非组为 null */
    val all: List<String>? = null,
    /** 能否手动选择：url-test / fallback 这类自动组为 false */
    val selectable: Boolean = false
)
