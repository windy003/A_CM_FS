package com.example.clashmeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * [ManualProxyManager.inject] 的注入逻辑测试。
 *
 * 和 [ChainRouteManagerTest] 同样的关注点：改完的 YAML 内核还能不能解析、反复注入会不会累积。
 * 这段写错的表现是「切换订阅后自己的 VPS 节点没回来」或「配置解析失败、VPN 起不来」。
 */
class ManualProxyManagerTest {

    private val vps = mapOf<String, Any?>(
        "name" to "MyVPS",
        "type" to "ss",
        "server" to "1.2.3.4",
        "port" to 8388,
        "cipher" to "aes-128-gcm",
        "password" to "pw",
        "udp" to true
    )

    /** 机场订阅最常见的写法：节点 flow 单行，组成员 block 列表。 */
    private val blockGroupConfig = """
        mixed-port: 7890
        proxies:
          - {name: 机场HK, type: ss, server: hk.airport.com, port: 443, cipher: aes-128-gcm, password: pw1}
        proxy-groups:
          - name: 🚀 节点选择
            type: select
            proxies:
              - ♻️ 自动选择
              - 机场HK
          - name: ♻️ 自动选择
            type: url-test
            proxies:
              - 机场HK
        rules:
          - MATCH,🚀 节点选择
    """.trimIndent()

    /** 组成员写成行内数组的订阅。 */
    private val inlineGroupConfig = """
        mixed-port: 7890
        proxies:
          - {name: 机场HK, type: ss, server: hk.airport.com, port: 443, cipher: aes-128-gcm, password: pw1}
        proxy-groups:
          - {name: 🚀 节点选择, type: select, proxies: [机场HK]}
        rules:
          - MATCH,🚀 节点选择
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun proxyNames(text: String): List<String> {
        val root = Yaml().load<Map<String, Any?>>(text)
        return (root["proxies"] as List<Map<String, Any?>>).map { it["name"].toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun groupMembers(text: String, group: String): List<String> {
        val root = Yaml().load<Map<String, Any?>>(text)
        val groups = root["proxy-groups"] as List<Map<String, Any?>>
        val g = groups.first { it["name"] == group }
        return (g["proxies"] as List<Any?>).map { it.toString() }
    }

    @Test
    fun `注入到 block 列表的 select 组`() {
        val out = ManualProxyManager.inject(blockGroupConfig, listOf(vps))

        assertTrue(proxyNames(out).contains("MyVPS"))
        assertTrue(groupMembers(out, "🚀 节点选择").contains("MyVPS"))
        // 自动组不是 select，不应被塞入手动节点（避免自动测速选中自己的 VPS）
        assertTrue(!groupMembers(out, "♻️ 自动选择").contains("MyVPS"))
        // 节点字段完整保留
        @Suppress("UNCHECKED_CAST")
        val node = (Yaml().load<Map<String, Any?>>(out)["proxies"] as List<Map<String, Any?>>)
            .first { it["name"] == "MyVPS" }
        assertEquals("ss", node["type"])
        assertEquals(8388, node["port"])
    }

    @Test
    fun `注入到行内数组的 select 组`() {
        val out = ManualProxyManager.inject(inlineGroupConfig, listOf(vps))

        assertTrue(proxyNames(out).contains("MyVPS"))
        assertEquals(listOf("机场HK", "MyVPS"), groupMembers(out, "🚀 节点选择"))
    }

    @Test
    fun `反复注入不累积`() {
        val once = ManualProxyManager.inject(blockGroupConfig, listOf(vps))
        val twice = ManualProxyManager.inject(once, listOf(vps))

        assertEquals(once, twice)
        assertEquals(1, proxyNames(twice).count { it == "MyVPS" })
        assertEquals(1, groupMembers(twice, "🚀 节点选择").count { it == "MyVPS" })
    }

    @Test
    fun `订阅里已有同名节点时跳过`() {
        val same = mapOf<String, Any?>("name" to "机场HK", "type" to "ss", "server" to "other.com")
        val out = ManualProxyManager.inject(blockGroupConfig, listOf(same))

        assertEquals(blockGroupConfig, out)
        // 不能覆盖订阅自己的节点
        @Suppress("UNCHECKED_CAST")
        val node = (Yaml().load<Map<String, Any?>>(out)["proxies"] as List<Map<String, Any?>>)
            .first { it["name"] == "机场HK" }
        assertEquals("hk.airport.com", node["server"])
    }

    @Test
    fun `保留订阅原有字节以免破坏其它行级补丁`() {
        val out = ManualProxyManager.inject(blockGroupConfig, listOf(vps))
        // 只做插入：原文每一行都还在（AutoGroupSanitizer 等行级正则依赖这一点）
        for (line in blockGroupConfig.split("\n")) {
            assertTrue("丢了行: $line", out.split("\n").contains(line))
        }
    }

    @Test
    fun `没有 proxies 块时也能兜住`() {
        val minimal = "mixed-port: 7890\nproxies: []\nrules:\n  - MATCH,DIRECT"
        val out = ManualProxyManager.inject(minimal, listOf(vps))
        assertEquals(listOf("MyVPS"), proxyNames(out))
    }

    @Test
    fun `空列表原样返回`() {
        assertEquals(blockGroupConfig, ManualProxyManager.inject(blockGroupConfig, emptyList()))
    }

    /**
     * ProxyClipboard 用 snakeyaml 整份 dump 写回配置后的样子：列表项顶格写 `- xxx`。
     * 曾经的 bug 是把顶格的 `-` 当成下一个顶层键，看不到已有节点，于是每次启动都再注入
     * 一遍，内核报 "proxy xxx is the duplicate name"、VPN 起不来。
     */
    private val snakeyamlConfig = """
        mixed-port: 7890
        proxies:
        - {name: 机场HK, type: ss, server: hk.airport.com, port: 443, cipher: aes-128-gcm, password: pw1}
        proxy-groups:
        - name: 🚀 节点选择
          type: select
          proxies:
          - ♻️ 自动选择
          - 机场HK
        - name: ♻️ 自动选择
          type: url-test
          proxies:
          - 机场HK
        rules:
        - MATCH,🚀 节点选择
    """.trimIndent()

    @Test
    fun `snakeyaml block 风格配置反复注入不累积`() {
        val once = ManualProxyManager.inject(snakeyamlConfig, listOf(vps))
        val twice = ManualProxyManager.inject(once, listOf(vps))

        assertEquals(once, twice)
        assertEquals(1, proxyNames(twice).count { it == "MyVPS" })
        assertEquals(1, groupMembers(twice, "🚀 节点选择").count { it == "MyVPS" })
        // 原有成员不能被挤到组外（缩进错位会让它们变成 proxy-groups 的另一个条目）
        assertEquals(listOf("♻️ 自动选择", "机场HK", "MyVPS"), groupMembers(twice, "🚀 节点选择"))
    }

    @Test
    fun `已经重复的手动节点会被自动清理`() {
        val duplicated = """
            mixed-port: 7890
            proxies:
            - {name: MyVPS, type: ss, server: 1.2.3.4, port: 8388, cipher: aes-128-gcm, password: pw}
            - name: MyVPS
              type: ss
              server: 1.2.3.4
              port: 8388
              cipher: aes-128-gcm
              password: pw
            rules:
            - MATCH,DIRECT
        """.trimIndent()

        val out = ManualProxyManager.inject(duplicated, listOf(vps))

        assertEquals(1, proxyNames(out).count { it == "MyVPS" })
    }

    /**
     * 机场订阅的真实写法（wgetcloud）：条目是独占一行的 `-`，字段在下面缩进列出。
     * 旧版 ENTRY_START 要求横杠后紧跟内容，识别不出这种条目，扫描组时就地错位——把上一个
     * 组的最后一个成员当成下一个组的开头，新成员按 `proxies:` 键的缩进插入，配置变成
     * `proxies:\n    - "MyVPS"\n      - DIRECT`，内核报
     * `yaml: line N: did not find expected key`，VPN 完全起不来。
     */
    private val bareDashEntryConfig = """
        mixed-port: 7890
        proxies:
          -
            name: 机场HK
            type: ss
            server: hk.airport.com
            port: 443
            cipher: aes-128-gcm
            password: pw1
        proxy-groups:
          -
            name: 🚀 节点选择
            type: select
            proxies:
              - DIRECT
              - 🚀 节点选择
          -
            name: 🐟 漏网之鱼
            type: select
            proxies:
              - 🚀 节点选择
              - DIRECT
        rules:
          - MATCH,🐟 漏网之鱼
    """.trimIndent()

    @Test
    fun `横杠独占一行的组条目也能正确注入`() {
        val out = ManualProxyManager.inject(bareDashEntryConfig, listOf(vps))

        // 成员缩进必须和订阅原有成员一致，否则整份 YAML 解析失败（这里 load 本身就是断言）
        assertTrue(proxyNames(out).contains("MyVPS"))
        assertEquals(listOf("DIRECT", "🚀 节点选择", "MyVPS"), groupMembers(out, "🚀 节点选择"))
        assertEquals(listOf("🚀 节点选择", "DIRECT", "MyVPS"), groupMembers(out, "🐟 漏网之鱼"))
        assertTrue("成员不能被插到 proxies: 键的缩进上", out.contains("\n      - \"MyVPS\""))
    }

    @Test
    fun `老版本写坏的成员缩进会自愈`() {
        // 旧版注入留下的现场：成员列表里混着 4 空格和 6 空格两种缩进，内核拒绝解析
        val corrupted = """
            mixed-port: 7890
            proxies:
              - {name: MyVPS, type: ss, server: 1.2.3.4, port: 8388, cipher: aes-128-gcm, password: pw}
            proxy-groups:
              -
                name: 🚀 节点选择
                type: select
                proxies:
                - "MyVPS"
                  - DIRECT
                  - 🚀 节点选择
            rules:
              - MATCH,🚀 节点选择
        """.trimIndent()
        assertThrows(Exception::class.java) { Yaml().load<Any?>(corrupted) }

        val out = ManualProxyManager.inject(corrupted, listOf(vps))

        assertEquals(listOf("MyVPS", "DIRECT", "🚀 节点选择"), groupMembers(out, "🚀 节点选择"))
    }
}
