package com.example.clashmeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `anytls://` 分享链接的解析/生成测试。
 *
 * 用例取自 anytls-go/docs/uri_scheme.md 的官方示例（含省略端口、IPv6、insecure）。
 * 这段写错的表现是「粘贴节点后连不上」或「节点端口/密码悄悄错了」，比报错更难查。
 */
class ShareLinkAnyTLSTest {

    @Test
    fun `官方示例一 省略端口默认 443`() {
        val m = ShareLink.decode("anytls://letmein@example.com/?sni=real.example.com")!!
        assertEquals("anytls", m["type"])
        assertEquals("example.com", m["server"])
        assertEquals(443, m["port"])
        assertEquals("letmein", m["password"])
        assertEquals("real.example.com", m["sni"])
        assertNull(m["skip-cert-verify"])
        assertEquals(true, m["udp"])
    }

    @Test
    fun `官方示例二 insecure=1`() {
        val m = ShareLink.decode("anytls://letmein@example.com/?sni=127.0.0.1&insecure=1")!!
        assertEquals("127.0.0.1", m["sni"])
        assertEquals(true, m["skip-cert-verify"])
    }

    @Test
    fun `官方示例三 IPv6 带端口`() {
        val m = ShareLink.decode(
            "anytls://0fdf77d7-d4ba-455e-9ed9-a98dd6d5489a@[2409:8a71:6a00:1953::615]:8964/?insecure=1"
        )!!
        assertEquals("2409:8a71:6a00:1953::615", m["server"])
        assertEquals(8964, m["port"])
        assertEquals("0fdf77d7-d4ba-455e-9ed9-a98dd6d5489a", m["password"])
        assertEquals(true, m["skip-cert-verify"])
    }

    @Test
    fun `片段作为节点名并做 URL 解码`() {
        val m = ShareLink.decode("anytls://pw@1.2.3.4:8443/#my%20tag%20with%20spaces")!!
        assertEquals("my tag with spaces", m["name"])
    }

    @Test
    fun `没有片段时用 host冒号port 兜底命名`() {
        val m = ShareLink.decode("anytls://pw@1.2.3.4:8443")!!
        assertEquals("1.2.3.4:8443", m["name"])
        assertEquals(8443, m["port"])
    }

    @Test
    fun `密码里的特殊字符按百分号编码还原`() {
        val m = ShareLink.decode("anytls://p%40ss%3Aword@1.2.3.4:8443")!!
        assertEquals("p@ss:word", m["password"])
    }

    @Test
    fun `allowInsecure 这种别家写法也认`() {
        val m = ShareLink.decode("anytls://pw@1.2.3.4:8443/?allowInsecure=1")!!
        assertEquals(true, m["skip-cert-verify"])
    }

    @Test
    fun `被识别为分享链接`() {
        assertTrue(ShareLink.isShareLink("anytls://pw@1.2.3.4:8443"))
        assertTrue(ShareLink.containsShareLink("第一行\nanytls://pw@1.2.3.4:8443\n"))
    }

    @Test
    fun `encode 与 decode 往返一致`() {
        val node = linkedMapOf<String, Any?>(
            "name" to "我的 AnyTLS",
            "type" to "anytls",
            "server" to "example.com",
            "port" to 8443,
            "password" to "letmein",
            "sni" to "real.example.com",
            "skip-cert-verify" to true,
            "udp" to true
        )
        val uri = ShareLink.encode(node)!!
        assertTrue(uri.startsWith("anytls://"))

        val back = ShareLink.decode(uri)!!
        for (k in listOf("type", "server", "port", "password", "sni", "skip-cert-verify", "udp")) {
            assertEquals("字段 $k 往返后不一致", node[k], back[k])
        }
        assertEquals("我的 AnyTLS", back["name"])
    }

    /**
     * 分享链接里没有 udp 字段，但导入后必须默认打开：内核里 udp 默认 false，节点被判成不支持
     * UDP 后「境外 UDP 443 → 代理」这条 QUIC 规则会被整条跳过并回落直连，只走 QUIC 的 TikTok
     * 直接打不开（YouTube 会退回 TCP，所以看起来像“只有 TikTok 坏了”）。
     */
    @Test
    fun `ss 分享链接导入默认开启 UDP`() {
        val m = ShareLink.decode("ss://YWVzLTI1Ni1nY206cHc=@1.2.3.4:8388#node")!!
        assertEquals("ss", m["type"])
        assertEquals(true, m["udp"])
    }

    @Test
    fun `trojan 分享链接导入默认开启 UDP`() {
        val m = ShareLink.decode("trojan://pw@example.com:443?sni=baidu.com#node")!!
        assertEquals("trojan", m["type"])
        assertEquals(true, m["udp"])
    }

    @Test
    fun `encode 给 IPv6 加方括号`() {
        val uri = ShareLink.encode(
            linkedMapOf<String, Any?>(
                "type" to "anytls", "server" to "2409:8a71::615", "port" to 8964, "password" to "pw"
            )
        )!!
        assertTrue("IPv6 未加方括号: $uri", uri.contains("[2409:8a71::615]:8964"))
        assertEquals("2409:8a71::615", ShareLink.decode(uri)!!["server"])
    }
}
