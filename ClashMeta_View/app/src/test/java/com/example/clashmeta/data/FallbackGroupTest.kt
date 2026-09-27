package com.example.clashmeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * 兜底代理组解析 + 两个规则注入器的目标名测试。
 *
 * 这里的回归点是线上真实踩过的坑：机场把整条规则写成带引号的字符串
 * `- 'MATCH,CoffeeCloud'`，旧解析拿到 `CoffeeCloud'`（尾引号没剥），注入的规则指向不存在的
 * 代理，内核报 `proxy [CoffeeCloud'] not found`、整份配置加载失败。
 */
class FallbackGroupTest {

    /** 机场实际下发的写法：每条规则都是单引号包裹的整串。 */
    private val quotedConfig = """
        mixed-port: 7890
        proxies:
          - {name: HK01, type: ss, server: hk.a.com, port: 443, cipher: aes-128-gcm, password: pw}
        proxy-groups:
          - name: CoffeeCloud
            type: select
            proxies:
              - HK01
        rules:
          - 'GEOIP,CN,DIRECT'
          - 'MATCH,CoffeeCloud'
    """.trimIndent()

    @Test
    fun `整条规则被引号包裹时不把尾引号算进组名`() {
        assertEquals("CoffeeCloud", FallbackGroup.resolve(quotedConfig))
    }

    @Test
    fun `目标名自己带引号也要剥掉`() {
        val text = "rules:\n  - MATCH,'🐟 漏网之鱼'"
        assertEquals("🐟 漏网之鱼", FallbackGroup.resolve(text))
    }

    @Test
    fun `组名含空格时捕获完整`() {
        val text = "rules:\n  - 'GEOIP,CN,DIRECT'\n  - MATCH,🐟 漏网之鱼"
        assertEquals("🐟 漏网之鱼", FallbackGroup.resolve(text))
    }

    @Test
    fun `取最后一条 MATCH 而不是顶部注入的那条`() {
        // ChainRouteManager 会把自己的 MATCH 插到 rules 顶端，不能把它当成订阅的兜底组
        val text = "rules:\n  - MATCH,MyVPS\n  - 'GEOIP,CN,DIRECT'\n  - 'MATCH,CoffeeCloud'"
        assertEquals("CoffeeCloud", FallbackGroup.resolve(text))
    }

    @Test
    fun `没有 MATCH 规则时返回 null`() {
        assertNull(FallbackGroup.resolve("rules:\n  - 'GEOIP,CN,DIRECT'"))
    }

    @Test
    fun `FINAL 同样识别`() {
        assertEquals("Proxy", FallbackGroup.resolve("rules:\n  - FINAL,Proxy"))
    }

    @Test
    fun `TikTok 注入的目标名不带杂字符且配置可解析`() {
        val out = TikTokRuleManager.patchRules(quotedConfig)

        // 注意不能直接断言 out 里不含 "CoffeeCloud'"：订阅原文那行 `- 'MATCH,CoffeeCloud'`
        // 本身就含这个子串。要看的是注入行的目标名。
        assertEquals(setOf("CoffeeCloud"), ruleTargets(out, "DOMAIN-KEYWORD,tiktok"))
        assertEquals(setOf("CoffeeCloud"), ruleTargets(out, "DOMAIN-SUFFIX,tiktokcdn.com"))
    }

    @Test
    fun `QUIC 注入的目标名不带杂字符`() {
        val out = QuicRuleManager.patchRules(quotedConfig)

        val injected = out.lines().filter { it.contains("DST-PORT,443") }
        assertEquals(2, injected.size)
        assertEquals(
            listOf("DIRECT", "CoffeeCloud"),
            injected.map { it.substringAfterLast(',').trim() }
        )
    }

    @Test
    fun `注入后整份配置仍能解析且规则数增加`() {
        val out = TikTokRuleManager.patchRules(QuicRuleManager.patchRules(quotedConfig))

        @Suppress("UNCHECKED_CAST")
        val rules = Yaml().load<Map<String, Any?>>(out)["rules"] as List<String>
        assertTrue(rules.size > 2)
        // 每条规则的目标都必须是配置里真实存在的名字，否则内核会拒绝整份配置
        val known = setOf("CoffeeCloud", "DIRECT", "REJECT")
        for (r in rules) {
            val target = r.substringAfterLast(',').trim()
            assertTrue("规则目标不存在: $r", target in known)
        }
    }

    /** 取出指定规则前缀的目标名集合。 */
    @Suppress("UNCHECKED_CAST")
    private fun ruleTargets(configText: String, prefix: String): Set<String> {
        val rules = Yaml().load<Map<String, Any?>>(configText)["rules"] as List<String>
        return rules.filter { it.startsWith(prefix) }
            .map { it.removePrefix("$prefix,") }
            .toSet()
    }
}
