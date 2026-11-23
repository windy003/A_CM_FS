package com.example.clashmeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QUIC 规则注入的回归点：
 *  - 注入的规则里不能出现 GEOIP——它会迫使内核在启动时下载 geoip.metadb，墙内必失败；
 *  - 规则必须放在最后一条 MATCH/FINAL 之前，不能抢在订阅自己的分流规则前面；
 *  - 幂等：反复写入不累积。
 */
class QuicRuleManagerTest {

    private val quicHead = "AND,((NETWORK,UDP),(DST-PORT,443))"

    private fun List<String>.indexOfRule(text: String) = indexOfFirst { it.contains(text) }

    @Test
    fun `注入的QUIC规则不再使用GEOIP`() {
        val input = """
            rules:
              - DOMAIN-SUFFIX,tiktokv.com,🚀 节点选择
              - GEOIP,CN,DIRECT
              - MATCH,🐟 漏网之鱼
        """.trimIndent()

        val out = QuicRuleManager.patchRules(input)

        val quicLines = out.lines().filter { it.contains("AND,((NETWORK,UDP)") }
        assertEquals(1, quicLines.size)
        assertFalse(quicLines[0].contains("GEOIP"))
        // 订阅自己的 GEOIP,CN,DIRECT 保留不动，只是我们不再新增
        assertTrue(out.contains("GEOIP,CN,DIRECT"))
    }

    @Test
    fun `规则放在最后一条MATCH之前`() {
        val input = """
            rules:
              - DOMAIN-SUFFIX,tiktokv.com,🚀 节点选择
              - GEOIP,CN,DIRECT
              - MATCH,🐟 漏网之鱼
        """.trimIndent()

        val out = QuicRuleManager.patchRules(input)
        val lines = out.lines()

        // 目标取域名规则里最常用的代理组
        assertTrue(out.contains("- $quicHead,🚀 节点选择"))
        val quicIdx = lines.indexOfRule(quicHead)
        val matchIdx = lines.indexOfFirst { it.contains("MATCH,🐟 漏网之鱼") }
        assertEquals(matchIdx - 1, quicIdx)
    }

    @Test
    fun `总开关插在顶部的MATCH不会被当成注入点`() {
        // ChainRouteManager 的总开关会在 rules 顶端插 `MATCH,MyVPS`，有意覆盖全部分流
        val input = """
            rules:
              - MATCH,MyVPS
              - DOMAIN-SUFFIX,a.com,ProxyGroup
              - MATCH,CoffeeCloud
        """.trimIndent()

        val lines = QuicRuleManager.patchRules(input).lines()
        val topMatch = lines.indexOfRule("MATCH,MyVPS")
        val quic = lines.indexOfRule(quicHead)

        assertTrue("QUIC 规则必须在总开关 MATCH 之后", quic > topMatch)
        assertEquals(lines.indexOfRule("MATCH,CoffeeCloud") - 1, quic)
    }

    @Test
    fun `旧版GEOIP注入会被清掉且反复调用不累积`() {
        val input = """
            rules:
              - AND,((NETWORK,UDP),(DST-PORT,443),(GEOIP,CN)),DIRECT
              - AND,((NETWORK,UDP),(DST-PORT,443)),🚀 节点选择
              - DOMAIN-SUFFIX,a.com,🚀 节点选择
              - MATCH,🐟 漏网之鱼
        """.trimIndent()

        val once = QuicRuleManager.patchRules(input)
        val twice = QuicRuleManager.patchRules(once)

        assertEquals(once, twice)
        assertEquals(1, twice.lines().count { it.contains("AND,((NETWORK,UDP)") })
        assertFalse(twice.contains("(GEOIP,CN)"))
    }

    @Test
    fun `rules块后面还有其它顶层键时仍插在块内`() {
        val input = """
            rules:
              - DOMAIN-SUFFIX,a.com,ProxyGroup
              - MATCH,ProxyGroup
            proxy-providers:
              foo: {}
        """.trimIndent()

        val lines = QuicRuleManager.patchRules(input).lines()
        val quic = lines.indexOfRule(quicHead)

        assertTrue(quic > lines.indexOf("rules:"))
        assertTrue(quic < lines.indexOf("proxy-providers:"))
    }

    @Test
    fun `整条规则被引号包裹的MATCH也能识别`() {
        val input = """
            rules:
              - 'GEOIP,CN,DIRECT'
              - 'MATCH,CoffeeCloud'
        """.trimIndent()

        val lines = QuicRuleManager.patchRules(input).lines()
        assertEquals(lines.indexOfRule("'MATCH,CoffeeCloud'") - 1, lines.indexOfRule(quicHead))
        assertTrue(lines[lines.indexOfRule(quicHead)].contains(",CoffeeCloud"))
    }

    @Test
    fun `内联空rules也能注入`() {
        val out = QuicRuleManager.patchRules("mixed-port: 7890\nrules: []")
        assertTrue(out.contains("rules:\n  - $quicHead,"))
        assertFalse(out.contains("rules: []"))
    }
}
