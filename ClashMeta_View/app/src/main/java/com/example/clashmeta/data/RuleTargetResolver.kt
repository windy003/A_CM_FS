package com.example.clashmeta.data

/**
 * 为 [TikTokRuleManager] / [QuicRuleManager] 注入的规则挑一个真正可用的代理目标。
 *
 * 早先这两处注入一律指向「兜底组」——rules 末尾 MATCH/FINAL 的目标。订阅自己带 TikTok 规则时
 * 这是错的：机场通常把 `tiktokv.com` 送去 `🚀 节点选择`（用户手选的节点），注入后却被改道到
 * `🐟 漏网之鱼`。而兜底组是 select 类型，内核在没有缓存选择时取**第一个成员**；用户手动粘贴的
 * VPS 节点经常就排在那里，它一旦挂掉或者没有 `udp: true`，TikTok 的 TCP 走死节点、QUIC 又因
 * 目标不支持 UDP 被内核跳过并回落到直连——表现就是「只有 TikTok 打不开，YouTube/Google 正常」。
 *
 * 按优先级取目标：
 *  1. 域名规则里出现次数最多的非 DIRECT 目标（事实上的「主代理组」，例如 `🚀 节点选择`）；
 *  2. 兜底组（原逻辑），仅当它不是 DIRECT/REJECT 这类不可代理目标。
 *
 * 不直接采用「订阅给 TikTok 写的规则目标」是因为本类旧版本会先删掉那些规则再重新注入，
 * 磁盘上的配置里往往已经只剩我们自己注入的那一份，无法再还原订阅的原意；而主代理组
 * 由成百上千条域名规则投票得出，即使被注入行污染也不会被带偏。
 */
object RuleTargetResolver {

    /** 一条规则列表项，取出项的内容（不含 `- ` 前缀）。与 [FallbackGroup] 同款。 */
    private val LIST_ITEM = Regex("^\\s*-\\s+(.*\\S)\\s*$")

    /** `DOMAIN-KEYWORD,x,目标` / `DOMAIN-SUFFIX,x,目标` / `DOMAIN,x,目标`。 */
    private val DOMAIN_RULE = Regex(
        "^(DOMAIN(?:-SUFFIX|-KEYWORD)?)\\s*,\\s*([^,]+?)\\s*,\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** 不可作为注入目标的动作名。GLOBAL 在规则模式下不参与路由，也排除。 */
    private val NON_PROXY_TARGETS = setOf(
        "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL"
    )

    /**
     * 选目标；返回 null 表示配置里找不到任何可用于注入的代理目标。
     * 调用方应先剔除自己上一次注入的规则，避免把旧目标当成订阅的意图。
     */
    fun resolve(configText: String): String? {
        // 1) 域名规则里最常出现的非 DIRECT 目标 = 事实上的主代理组。
        val domainTargets = parseDomainRules(configText).map { it.third }
        if (domainTargets.isNotEmpty()) return mostCommon(domainTargets)

        // 2) 兜底组。
        return FallbackGroup.resolve(configText)?.takeIf { isProxyTarget(it) }
    }

    /** 解析出所有 DOMAIN 类规则，元素为 (类型, payload, 目标)。 */
    private fun parseDomainRules(configText: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        for (raw in configText.split("\n")) {
            // 我们自己注入过的 TikTok 行不能算作「订阅的意图」，否则会把上一轮的错误目标沿袭下来
            if (TikTokRuleManager.isInjectedRule(raw)) continue
            val item = LIST_ITEM.matchEntire(raw)?.groupValues?.get(1) ?: continue
            val m = DOMAIN_RULE.matchEntire(unquote(item.trim())) ?: continue
            val type = m.groupValues[1].uppercase()
            val payload = unquote(m.groupValues[2].trim())
            val target = unquote(m.groupValues[3].trim())
            if (payload.isEmpty() || !isProxyTarget(target)) continue
            out.add(Triple(type, payload, target))
        }
        return out
    }

    private fun isProxyTarget(target: String): Boolean =
        target.isNotEmpty() && target.uppercase() !in NON_PROXY_TARGETS

    private fun mostCommon(values: List<String>): String =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

    /** 去掉两端成对的单/双引号。 */
    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
