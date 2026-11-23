package com.example.clashmeta.data

/**
 * QUIC(HTTP/3, UDP 443) 分流规则注入 —— 让 QUIC「转发到代理」，而不是屏蔽。
 *
 * 背景(实测日志确认)：TikTok 的网络库(TNC)会绕过系统 DNS，直接对 CDN 裸 IP 发 QUIC。
 * 这类连接没有域名，域名规则匹配不上；若再用「REJECT UDP 443」全局屏蔽，客户端又**不回退
 * TCP**，图片/视频缩略图就全灭。而靠 sniffer 反查域名并不可靠(mihomo QUIC 嗅探时灵时不灵)。
 *
 * 修法(照抄 Xray/v2rayNG 的做法)：不屏蔽 QUIC，而是把「前面规则都没匹配到的 UDP 443」整条
 * 转发到代理组，由节点原生转发 UDP，QUIC 端到端握手即可完成——裸 IP 也无所谓。
 * 代理组名由 [RuleTargetResolver] 决定：优先订阅自己给 TikTok 指定的组，其次域名规则里
 * 最常用的主代理组，最后才退回 MATCH/FINAL 的兜底组（兜底组常被手动节点占据，不能盲信）。
 *
 * 注意本类**不使用 GEOIP**。旧版注入过
 * `AND,((NETWORK,UDP),(DST-PORT,443),(GEOIP,CN)),DIRECT` 来让国内 QUIC 直连，但 GEOIP 规则
 * 要求数据目录里存在 geoip.metadb；该文件缺失时内核会在启动解析阶段按 geox-url 从 GitHub
 * 下载，而此时 VPN 还没建立、墙内直连 GitHub 必然失败，整份配置解析失败、VPN 起不来。
 * 现在只保留一条兜底规则，放在最后一条 MATCH/FINAL 之上：订阅自己的域名/GEOSITE/GEOIP 规则
 * 排在它前面，国内流量（含 QUIC）照常 DIRECT；只有前面全都没匹配到的 QUIC（基本都是境外
 * 裸 IP）才落到这条走代理。区分国内/境外的责任交还给订阅规则。
 *
 * 注入点统一收敛在 [LanProxyManager.applyToConfigFile]。
 */
object QuicRuleManager {

    private val TOP_LEVEL_RULES = Regex("^rules:\\s*$")
    private val INLINE_EMPTY_RULES = Regex("^rules:\\s*\\[\\s*]\\s*$")
    private val LIST_ITEM_INDENT = Regex("^(\\s*)-.*$")

    /** 一条 MATCH / FINAL 兜底规则（整串可能被引号包裹）。 */
    private val MATCH_RULE = Regex(
        "^\\s*-\\s*['\"]?(MATCH|FINAL)\\s*,",
        RegexOption.IGNORE_CASE
    )

    /**
     * 匹配历史注入的任意 QUIC(UDP 443) 规则行，用于幂等：先删后插。
     * 覆盖旧版 GEOIP/REJECT 写法与新版代理写法。
     */
    private val EXISTING_RULE = Regex(
        "^\\s*-\\s*AND,\\(\\(NETWORK,UDP\\),\\(DST-PORT,443\\).*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 把 QUIC 分流规则插到 rules 末尾、最后一条 MATCH/FINAL 之前。幂等：多次调用不会累积重复行。
     * 目标见 [RuleTargetResolver]（优先订阅自己的 TikTok 分流目标，其次主代理组，最后兜底组）。
     * 若完全找不到可用代理目标(如 MATCH,DIRECT)，退化为 REJECT 以保底。
     *
     * 为什么是「最后一条 MATCH 之前」：
     *  - 不能插在最顶端：那样会抢在订阅的域名/GEOIP 规则之前，国内流量也被迫走代理；
     *  - 不能插在第一条 MATCH 前：[ChainRouteManager] 的总开关会在 rules 顶端插一条
     *    `MATCH,<出口节点>`，那条是故意覆盖全部分流的，我们的规则必须留在它下面；
     *  - 不能插在最后一条 MATCH 之后：MATCH 是兜底，后面的规则永远不会命中。
     */
    fun patchRules(configText: String): String {
        val target = RuleTargetResolver.resolve(configText)
        val abroadAction = target ?: "REJECT"

        // 1) 先删除历史注入
        val lines = configText.split("\n")
            .filterNot { EXISTING_RULE.matches(it) }
            .toMutableList()

        // 2) 生成唯一一条规则：前面规则都没匹配到的 QUIC 走代理
        fun newRule(indent: String) =
            "$indent- AND,((NETWORK,UDP),(DST-PORT,443)),$abroadAction"

        // 3) 插入(块状 / 内联空 / 缺失 三种情况)
        val blockIdx = lines.indexOfFirst { TOP_LEVEL_RULES.matches(it) }
        if (blockIdx >= 0) {
            val indent = detectIndent(lines, blockIdx)
            val blockEnd = rulesBlockEnd(lines, blockIdx)
            // 用 containsMatchIn 而不是 matches：MATCH_RULE 只锚定行首（后面还有目标名），
            // matches 要求整行完全匹配，会永远找不到 MATCH，最终把规则追加到文件末尾。
            val lastMatchIdx = (blockIdx + 1 until blockEnd)
                .lastOrNull { MATCH_RULE.containsMatchIn(lines[it]) }
            lines.add(lastMatchIdx ?: blockEnd, newRule(indent))
            return lines.joinToString("\n")
        }
        val inlineIdx = lines.indexOfFirst { INLINE_EMPTY_RULES.matches(it) }
        if (inlineIdx >= 0) {
            lines[inlineIdx] = "rules:"
            lines.add(inlineIdx + 1, newRule("  "))
            return lines.joinToString("\n")
        }
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add("rules:")
        lines.add(newRule("  "))
        return lines.joinToString("\n")
    }

    /**
     * rules 块的结束行号(不含)。顶格的普通行才是下一个顶层键；
     * 顶格的 `- xxx` 仍是本块的列表项（snakeyaml block dump 的写法），空行跳过。
     */
    private fun rulesBlockEnd(lines: List<String>, rulesIdx: Int): Int {
        for (i in (rulesIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (!line.first().isWhitespace() && !isListItem(line)) return i
        }
        return lines.size
    }

    /** YAML 顶格列表项（`- ` 或单独一个 `-`）。 */
    private fun isListItem(line: String): Boolean =
        line == "-" || line.startsWith("- ")

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
