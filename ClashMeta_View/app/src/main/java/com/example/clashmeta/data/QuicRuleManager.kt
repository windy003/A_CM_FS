package com.example.clashmeta.data

/**
 * QUIC(HTTP/3, UDP 443) 分流规则注入 —— 让 QUIC「转发到代理」，而不是屏蔽。
 *
 * 背景(实测日志确认)：TikTok 的网络库(TNC)会绕过系统 DNS，直接对 CDN 裸 IP 发 QUIC。
 * 这类连接没有域名，域名规则匹配不上；若再用「REJECT UDP 443」全局屏蔽，客户端又**不回退
 * TCP**，图片/视频缩略图就全灭。而靠 sniffer 反查域名并不可靠(mihomo QUIC 嗅探时灵时不灵)。
 *
 * 修法(照抄 Xray/v2rayNG 的做法)：不屏蔽 QUIC，而是把「境外的 UDP 443」整条转发到代理组，
 * 由节点原生转发 UDP，QUIC 端到端握手即可完成——裸 IP 也无所谓，因为按 IP 归属地分流即可：
 *   - 国内 QUIC(GEOIP,CN) → DIRECT，直连不绕路；
 *   - 其余(境外)QUIC     → 代理组(TikTok 的裸 IP CDN 走这条)。
 * 代理组名自动取自最终 MATCH/FINAL 规则(兜底组)。
 *
 * 注入到 rules 顶部(最高优先级)。注入点统一收敛在 [LanProxyManager.applyToConfigFile]。
 */
object QuicRuleManager {

    private val TOP_LEVEL_RULES = Regex("^rules:\\s*$")
    private val INLINE_EMPTY_RULES = Regex("^rules:\\s*\\[\\s*]\\s*$")
    private val LIST_ITEM_INDENT = Regex("^(\\s*)-.*$")

    /**
     * 从最终 MATCH/FINAL 规则取兜底代理组名，例如 `MATCH,CoffeeCloud` → CoffeeCloud。
     * 组名可能带空格(如 `🐟 漏网之鱼`)，故捕获到行尾为止，而非遇到空白就截断。
     */
    private val FINAL_TARGET =
        Regex("(?:MATCH|FINAL)\\s*,\\s*(.+)", RegexOption.IGNORE_CASE)

    /** 去掉捕获到的目标名两端的引号与空白，例如 `'🐟 漏网之鱼'` → `🐟 漏网之鱼`。 */
    private fun cleanTarget(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 &&
            ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))
        ) {
            s = s.substring(1, s.length - 1)
        }
        return s
    }

    /**
     * 匹配历史注入的任意 QUIC(UDP 443) 规则行，用于幂等：先删后插。
     * 覆盖旧版 REJECT 写法与新版 GEOIP/代理写法。
     */
    private val EXISTING_RULE = Regex(
        "^\\s*-\\s*AND,\\(\\(NETWORK,UDP\\),\\(DST-PORT,443\\).*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 把 QUIC 分流规则插到 rules 顶部。幂等：多次调用不会累积重复行。
     * 若找不到可用兜底代理组(如 MATCH,DIRECT)，退化为仅「国内直连 + 其余 REJECT」以保底。
     */
    fun patchRules(configText: String): String {
        val target = FINAL_TARGET.find(configText)?.groupValues?.get(1)?.let(::cleanTarget)
        val abroadAction = if (target == null || target.equals("DIRECT", true) ||
            target.equals("REJECT", true)
        ) "REJECT" else target

        // 1) 先删除历史注入
        val lines = configText.split("\n")
            .filterNot { EXISTING_RULE.matches(it) }
            .toMutableList()

        // 2) 生成两条规则：国内直连 + 境外走代理
        val blockIdx = lines.indexOfFirst { TOP_LEVEL_RULES.matches(it) }
        val indent = if (blockIdx >= 0) detectIndent(lines, blockIdx) else "  "
        val newRules = listOf(
            "$indent- AND,((NETWORK,UDP),(DST-PORT,443),(GEOIP,CN)),DIRECT",
            "$indent- AND,((NETWORK,UDP),(DST-PORT,443)),$abroadAction",
        )

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

    /**
     * 取 rules 块里出现次数最多的列表项缩进（多数表决），取不到时默认 2 空格。
     * 用多数表决而非"看第一行"，是因为第一行可能恰好是本类/[TikTokRuleManager] 上次注入、
     * 缩进本身就有问题的行；订阅原始规则数量远多于注入规则，多数表决能自我纠正、不被污染。
     */
    private fun detectIndent(lines: List<String>, rulesIdx: Int): String {
        val counts = HashMap<String, Int>()
        for (i in (rulesIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val m = LIST_ITEM_INDENT.matchEntire(line) ?: break
            val indent = m.groupValues[1]
            counts[indent] = (counts[indent] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: "  "
    }
}
