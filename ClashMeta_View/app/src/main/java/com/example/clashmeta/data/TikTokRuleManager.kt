package com.example.clashmeta.data

/**
 * TikTok 分流规则注入 —— 让 TikTok 的图片/视频/搜索 CDN 正确走代理。
 *
 * 背景(实测日志确认)：新版 TikTok 客户端对 CDN 几乎只用 QUIC(UDP 443)，且 QUIC 被 REJECT
 * 后**不回退 TCP**，会一直重试。于是 [QuicRuleManager] 那条全局 REJECT 会把 TikTok 的图片/视频
 * 全部堵死(表现为"搜索页缩略图加载不出来")。同时机场规则通常没有 TikTok 专属条目，域名会掉到
 * `GEOIP,CN,DIRECT` 被误判直连。
 *
 * 修法：把 TikTok 相关域名规则注入到 rules **最顶端**(在 QUIC REJECT 之上)，目标指向"兜底代理组"
 * (取自最终 MATCH/FINAL 规则)。这样：
 *   - TikTok 的 UDP 443 先命中域名规则 → 走代理节点(节点均 udp:true，能转发 QUIC)，无需回退 TCP；
 *   - 其它 QUIC 仍落到下面的 REJECT，保持原有屏蔽行为。
 * fake-ip 下 clash 能识别 UDP 连接的真实域名(日志里 UDP 行直接打印 v31-sg.tiktokcdn.com)，故
 * DOMAIN 规则对 UDP 同样生效。
 *
 * 必须在 [QuicRuleManager.patchRules] **之后**调用，才能保证 TikTok 规则位于 REJECT 之上。
 */
object TikTokRuleManager {

    /** 关键词匹配(覆盖 *.tiktokcdn.com / tiktokv.com / v31-sg.tiktokcdn.com 等一切含该词的域名)。 */
    private val KEYWORDS = listOf("tiktok", "byteoversea")

    /** 后缀匹配(补齐不含 "tiktok" 字样的字节跳动 图片/视频 CDN)。 */
    private val SUFFIXES = listOf(
        "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com",
        "ibytedtos.com", "ibyteimg.com", "byteimg.com", "ipstatp.com",
        "muscdn.com", "musical.ly", "ttwstatic.com", "sgpstatic.com",
        "ttlivecdn.com", "byteoversea.com"
    )

    private val TOP_LEVEL_RULES = Regex("^rules:\\s*$")
    private val INLINE_EMPTY_RULES = Regex("^rules:\\s*\\[\\s*]\\s*$")
    private val LIST_ITEM_INDENT = Regex("^(\\s+)-.*$")

    /** 从最终 MATCH/FINAL 规则取"兜底代理组"名，例如 `MATCH,CoffeeCloud` → CoffeeCloud。 */
    private val FINAL_TARGET =
        Regex("(?:MATCH|FINAL)\\s*,\\s*['\"]?([^,'\"\\s]+)", RegexOption.IGNORE_CASE)

    /** 匹配本类注入过的规则行，用于幂等：先删后插。 */
    private val INJECTED = Regex(
        "^\\s*-\\s*['\"]?DOMAIN(?:-KEYWORD|-SUFFIX),(?:" +
            (KEYWORDS + SUFFIXES).joinToString("|") { Regex.escape(it) } +
            ")\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 把 TikTok 域名规则插到 rules 顶部。幂等：多次调用不累积。
     * 若找不到可用的兜底代理组(如最终是 MATCH,DIRECT)，则不注入(没有代理目标可用)。
     */
    fun patchRules(configText: String): String {
        val target = FINAL_TARGET.find(configText)?.groupValues?.get(1)
        if (target == null || target.equals("DIRECT", true) || target.equals("REJECT", true)) {
            return configText
        }

        // 1) 先删历史注入
        val lines = configText.split("\n")
            .filterNot { INJECTED.matches(it) }
            .toMutableList()

        // 2) 生成规则行
        val blockIdx = lines.indexOfFirst { TOP_LEVEL_RULES.matches(it) }
        val indent = if (blockIdx >= 0) detectIndent(lines, blockIdx) else "  "
        val newRules = buildList {
            KEYWORDS.forEach { add("$indent- DOMAIN-KEYWORD,$it,$target") }
            SUFFIXES.forEach { add("$indent- DOMAIN-SUFFIX,$it,$target") }
        }

        // 3) 插入(块状 / 内联空 / 缺失 三种情况)
        if (blockIdx >= 0) {
            lines.addAll(blockIdx + 1, newRules)
            return lines.joinToString("\n")
        }
        val inlineIdx = lines.indexOfFirst { INLINE_EMPTY_RULES.matches(it) }
        if (inlineIdx >= 0) {
            lines[inlineIdx] = "rules:"
            lines.addAll(inlineIdx + 1, newRules)
            return lines.joinToString("\n")
        }
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add("rules:")
        lines.addAll(newRules)
        return lines.joinToString("\n")
    }

    /** 取 rules 下第一个列表项的缩进；取不到默认 2 空格。 */
    private fun detectIndent(lines: List<String>, rulesIdx: Int): String {
        for (i in (rulesIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val m = LIST_ITEM_INDENT.matchEntire(line)
            return m?.groupValues?.get(1) ?: "  "
        }
        return "  "
    }
}
