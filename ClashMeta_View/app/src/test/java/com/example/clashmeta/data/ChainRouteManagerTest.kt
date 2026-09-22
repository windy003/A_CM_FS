package com.example.clashmeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * [ChainRouteManager] 的注入逻辑测试。
 *
 * 重点不在「字符串长得对不对」，而在两件事：
 *   1. 改完的 YAML 内核还能不能解析（所以每个用例都真的 load 一遍再断言字段）；
 *   2. 幂等——反复注入不能累积，关掉开关要能干净还原。
 * 这段代码一旦写错，用户的表现是「VPN 起不来」，比功能不生效严重得多。
 */
class ChainRouteManagerTest {

    private val group = "♻️ 自动选择"

    /** 机场订阅最常见的写法：节点是单行 flow 风格。 */
    private val flowConfig = """
        mixed-port: 7890
        mode: rule
        proxies:
          - {name: 机场HK, type: ss, server: hk.airport.com, port: 443, cipher: aes-128-gcm, password: pw1, udp: true}
          - {name: MyVPS, type: ss, server: 1.2.3.4, port: 8388, cipher: 2022-blake3-aes-128-gcm, password: pw2, udp: true}
        proxy-groups:
          - name: ♻️ 自动选择
            type: url-test
            proxies:
              - 机场HK
          - name: 🐟 漏网之鱼
            type: select
            proxies:
              - ♻️ 自动选择
        rules:
          - AND,((NETWORK,UDP),(DST-PORT,443)),🐟 漏网之鱼
          - GEOIP,CN,DIRECT
          - MATCH,🐟 漏网之鱼
    """.trimIndent()

    /** 经 ProxyClipboard 导入节点后，整份会被 dump 成 block 风格。 */
    private val blockConfig = """
        mixed-port: 7890
        proxies:
          - name: 机场HK
            type: ss
            server: hk.airport.com
            port: 443
            cipher: aes-128-gcm
            password: pw1
            udp: true
          - name: MyVPS
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: 2022-blake3-aes-128-gcm
            password: pw2
            udp: true
        proxy-groups:
          - name: ♻️ 自动选择
            type: url-test
            proxies:
              - 机场HK
        rules:
          - GEOIP,CN,DIRECT
          - MATCH,♻️ 自动选择
    """.trimIndent()

    // ------------------------------------------------------------------ 工具

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> =
        Yaml().load<Map<String, Any?>>(text) ?: error("YAML 解析结果为空")

    @Suppress("UNCHECKED_CAST")
    private fun proxy(text: String, name: String): Map<String, Any?> =
        (parse(text)["proxies"] as List<Map<String, Any?>>).first { it["name"] == name }

    @Suppress("UNCHECKED_CAST")
    private fun rules(text: String): List<String> =
        (parse(text)["rules"] as List<Any?>).map { it.toString() }

    private fun run(
        config: String,
        exit: String = "MyVPS",
        dialer: String = group,
        domains: List<String> = listOf("missav.ws"),
        enabled: Boolean = true,
        last: Set<String> = emptySet()
    ) = ChainRouteManager.patch(config, exit, dialer, domains, enabled, last)

    // ------------------------------------------------------------------ 用例

    @Test
    fun `flow 风格节点挂上 dialer-proxy 且 YAML 仍可解析`() {
        val out = run(flowConfig).text

        assertEquals(group, proxy(out, "MyVPS")["dialer-proxy"])
        // 只能挂在出口节点上，不能污染别的节点
        assertNull(proxy(out, "机场HK")["dialer-proxy"])
        // 其余字段一个都不能丢
        assertEquals(8388, proxy(out, "MyVPS")["port"])
        assertEquals("pw2", proxy(out, "MyVPS")["password"])
    }

    @Test
    fun `block 风格节点挂上 dialer-proxy 且 YAML 仍可解析`() {
        val out = run(blockConfig).text

        assertEquals(group, proxy(out, "MyVPS")["dialer-proxy"])
        assertNull(proxy(out, "机场HK")["dialer-proxy"])
        assertEquals("2022-blake3-aes-128-gcm", proxy(out, "MyVPS")["cipher"])
    }

    @Test
    fun `域名规则必须排在 QUIC 规则之上`() {
        // 这条是本功能最容易踩的坑：排在 QUIC 规则之下，目标站的 UDP 443 会被它先抓走，
        // 只剩 TCP 走 VPS，速度上不去还极难排查。
        val out = run(flowConfig).text
        val r = rules(out)

        assertEquals("DOMAIN-SUFFIX,missav.ws,MyVPS", r[0])
        val quicIdx = r.indexOfFirst { it.contains("DST-PORT,443") }
        assertTrue("QUIC 规则应当存在", quicIdx > 0)
        assertTrue("域名规则必须在 QUIC 规则之前", 0 < quicIdx)
    }

    @Test
    fun `keyword 前缀生成关键词规则`() {
        val out = run(flowConfig, domains = listOf("missav.ws", "keyword:surrit")).text
        val r = rules(out)

        assertEquals("DOMAIN-SUFFIX,missav.ws,MyVPS", r[0])
        assertEquals("DOMAIN-KEYWORD,surrit,MyVPS", r[1])
    }

    @Test
    fun `重复注入不累积`() {
        val first = run(flowConfig)
        val second = run(flowConfig, last = first.injectedRules.toSet())
        // 第二次的输入是「已经注入过的文本」，才是真实场景
        val third = ChainRouteManager.patch(
            first.text, "MyVPS", group, listOf("missav.ws"), true, first.injectedRules.toSet()
        )

        assertEquals(first.text, third.text)
        assertEquals(1, rules(third.text).count { it.startsWith("DOMAIN-SUFFIX,missav.ws") })
        assertEquals(group, proxy(third.text, "MyVPS")["dialer-proxy"])
        assertEquals(first.text, second.text)
    }

    @Test
    fun `关掉开关能干净还原`() {
        val on = run(flowConfig)
        val off = ChainRouteManager.patch(
            on.text, "MyVPS", group, listOf("missav.ws"), false, on.injectedRules.toSet()
        )

        assertEquals(flowConfig, off.text)
        assertTrue(off.injectedRules.isEmpty())
    }

    @Test
    fun `换域名后旧规则被删掉`() {
        val on = run(flowConfig)
        val changed = ChainRouteManager.patch(
            on.text, "MyVPS", group, listOf("example.com"), true, on.injectedRules.toSet()
        )
        val r = rules(changed.text)

        assertEquals("DOMAIN-SUFFIX,example.com,MyVPS", r[0])
        assertFalse(r.any { it.contains("missav.ws") })
    }

    @Test
    fun `换出口节点后旧节点的 dialer-proxy 被剥掉`() {
        val on = run(flowConfig)
        val changed = ChainRouteManager.patch(
            on.text, "机场HK", group, listOf("missav.ws"), true, on.injectedRules.toSet()
        )

        assertNull("旧出口节点必须被剥干净", proxy(changed.text, "MyVPS")["dialer-proxy"])
        assertEquals(group, proxy(changed.text, "机场HK")["dialer-proxy"])
    }

    @Test
    fun `出口节点不存在时整个跳过注入`() {
        // 订阅更新后节点没了。宁可不生效，也不能注入一个查不到的名字让内核起不来。
        val out = run(flowConfig, exit = "已经没有的节点")

        assertEquals(flowConfig, out.text)
        assertTrue(out.injectedRules.isEmpty())
    }

    @Test
    fun `前置跳不存在时降级成单跳但规则照常生效`() {
        val out = run(flowConfig, dialer = "订阅改名后没有的组").text

        assertNull(proxy(out, "MyVPS")["dialer-proxy"])
        assertEquals("DOMAIN-SUFFIX,missav.ws,MyVPS", rules(out)[0])
    }

    @Test
    fun `前置跳留空就是单跳`() {
        val out = run(flowConfig, dialer = "").text

        assertNull(proxy(out, "MyVPS")["dialer-proxy"])
        assertEquals("DOMAIN-SUFFIX,missav.ws,MyVPS", rules(out)[0])
    }

    @Test
    fun `前置跳也可以直接填节点名`() {
        val out = run(flowConfig, dialer = "机场HK").text

        assertEquals("机场HK", proxy(out, "MyVPS")["dialer-proxy"])
    }

    @Test
    fun `没填域名就不注入`() {
        val out = run(flowConfig, domains = emptyList())

        assertEquals(flowConfig, out.text)
        assertTrue(out.injectedRules.isEmpty())
    }

    @Test
    fun `缩进跟随订阅原有风格`() {
        // 有的订阅 rules 用 4 空格缩进，注入的行要跟上，否则 YAML 结构被破坏
        val fourSpace = flowConfig.replace("\n  - AND,", "\n    - AND,")
            .replace("\n  - GEOIP,CN,DIRECT", "\n    - GEOIP,CN,DIRECT")
            .replace("\n  - MATCH,", "\n    - MATCH,")
        val out = run(fourSpace).text

        assertTrue(out.contains("\n    - DOMAIN-SUFFIX,missav.ws,MyVPS"))
        assertEquals("DOMAIN-SUFFIX,missav.ws,MyVPS", rules(out)[0])
    }
}
