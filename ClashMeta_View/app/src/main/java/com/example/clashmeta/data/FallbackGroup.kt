package com.example.clashmeta.data

/**
 * 解析「兜底代理组」名 —— 即 rules 里最终那条 MATCH/FINAL 规则的目标。
 * [QuicRuleManager] 与 [TikTokRuleManager] 都要把自己注入的规则打到这个目标上。
 *
 * 独立成一个类是因为解析错了的后果很重：目标名带上一个杂字符，注进去的规则就指向一个不存在的
 * 代理，**整份配置解析失败**，表现为「切换订阅后内核加载失败、VPN 起不来」，而规则行本身看着
 * 一点问题都没有，极难排查。曾经踩过的两个坑都在这里一次性收掉：
 *
 *  1. 机场常把整条规则写成带引号的字符串：`- 'MATCH,CoffeeCloud'`。早先的实现用
 *     `MATCH\s*,\s*(.+)` 一路捕到行尾，拿到的是 `CoffeeCloud'`——尾引号属于列表项自身的引号，
 *     却被算进了组名，且"只剥成对引号"的清洗对这种单边残留无效。所以这里先剥列表项的引号，
 *     再匹配规则内容，最后再剥一次目标名自己的引号（`- MATCH,'🐟 漏网之鱼'` 这种写法）。
 *  2. 取**最后**一条而不是第一条：[ChainRouteManager] 会把 `- MATCH,<自己的VPS>` 插到 rules
 *     顶端，重复注入时若取第一条，兜底目标就会被改写成 VPS 节点。订阅自己的 MATCH 总在末尾。
 */
object FallbackGroup {

    /** 一条规则列表项，取出项的内容（不含 `- ` 前缀）。 */
    private val LIST_ITEM = Regex("^\\s*-\\s+(.*\\S)\\s*$")

    /**
     * MATCH/FINAL 规则。目标名可能带空格（如 `🐟 漏网之鱼`），故捕获到结尾为止，
     * 而非遇到空白就截断。
     */
    private val FINAL_RULE = Regex("^(?:MATCH|FINAL)\\s*,\\s*(.+)$", RegexOption.IGNORE_CASE)

    /**
     * 兜底代理组名；没有 MATCH/FINAL 规则时返回 null。
     * `DIRECT` / `REJECT` 原样返回，是否可用作代理目标由调用方判断。
     */
    fun resolve(configText: String): String? {
        for (line in configText.split("\n").asReversed()) {
            val item = LIST_ITEM.matchEntire(line)?.groupValues?.get(1) ?: continue
            val m = FINAL_RULE.matchEntire(unquote(item.trim())) ?: continue
            return unquote(m.groupValues[1].trim()).ifEmpty { null }
        }
        return null
    }

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
