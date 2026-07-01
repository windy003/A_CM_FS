package com.example.clashmeta.data

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 分享链接 <-> Clash 节点配置 互转。
 * 支持：ss:// vmess:// trojan:// vless://
 * - decode: 分享链接 -> Clash 代理映射（可直接写入 config.yaml 的 proxies）
 * - encode: Clash 代理映射 -> 分享链接
 */
object ShareLink {

    private const val TAG = "ShareLink"

    private val schemes = listOf("ss://", "vmess://", "trojan://", "vless://")

    fun isShareLink(text: String): Boolean {
        val t = text.trim()
        return schemes.any { t.startsWith(it, ignoreCase = true) }
    }

    /** 文本中是否包含至少一行分享链接 */
    fun containsShareLink(text: String): Boolean =
        text.split('\n', '\r').any { isShareLink(it) }

    /** 解析可能包含多行的分享链接文本 */
    fun decodeMany(text: String): List<Map<String, Any?>> =
        text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isShareLink(it) }
            .mapNotNull { decode(it) }

    fun decode(uri: String): Map<String, Any?>? {
        return try {
            when {
                uri.startsWith("ss://", true) -> decodeSS(uri)
                uri.startsWith("vmess://", true) -> decodeVmess(uri)
                uri.startsWith("trojan://", true) -> decodeTrojan(uri)
                uri.startsWith("vless://", true) -> decodeVless(uri)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode failed: $uri", e)
            null
        }
    }

    /** Clash 代理映射 -> 分享链接；不支持的类型返回 null */
    fun encode(p: Map<String, Any?>): String? {
        return try {
            when (p["type"]?.toString()) {
                "ss" -> encodeSS(p)
                "vmess" -> encodeVmess(p)
                "trojan" -> encodeTrojan(p)
                "vless" -> encodeVless(p)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "encode failed", e)
            null
        }
    }

    // ---------------- helpers ----------------

    private fun b64Decode(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun b64EncodeUrl(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8),
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)

    private fun b64EncodeStd(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    private fun urlEncode(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (kv in q.split('&')) {
            if (kv.isEmpty()) continue
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            m[urlDecode(k)] = urlDecode(v)
        }
        return m
    }

    private fun buildQuery(q: Map<String, String>): String =
        q.entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }

    // ---------------- Shadowsocks ----------------

    private fun decodeSS(uri: String): Map<String, Any?> {
        var s = uri.substring(5)
        val name = if (s.contains('#')) urlDecode(s.substringAfterLast('#')) else ""
        s = s.substringBefore('#')
        var query = ""
        if (s.contains('?')) { query = s.substringAfter('?'); s = s.substringBefore('?') }
        s = s.trimEnd('/')

        val method: String; val password: String; val host: String; val portStr: String
        if (s.contains('@')) {
            val userinfoRaw = s.substringBeforeLast('@')
            val hostport = s.substringAfterLast('@')
            val userinfo = if (userinfoRaw.contains(':')) userinfoRaw else b64Decode(userinfoRaw)
            method = userinfo.substringBefore(':')
            password = userinfo.substringAfter(':')
            host = hostport.substringBeforeLast(':')
            portStr = hostport.substringAfterLast(':')
        } else {
            val decoded = b64Decode(s) // method:password@host:port
            val ui = decoded.substringBeforeLast('@')
            val hp = decoded.substringAfterLast('@')
            method = ui.substringBefore(':')
            password = ui.substringAfter(':')
            host = hp.substringBeforeLast(':')
            portStr = hp.substringAfterLast(':')
        }

        val m = LinkedHashMap<String, Any?>()
        m["name"] = name.ifEmpty { "$host:$portStr" }
        m["type"] = "ss"
        m["server"] = host
        m["port"] = portStr.toIntOrNull() ?: portStr
        m["cipher"] = method
        m["password"] = password

        parseQuery(query)["plugin"]?.let { if (it.isNotEmpty()) applySSPlugin(m, it) }
        return m
    }

    private fun applySSPlugin(m: MutableMap<String, Any?>, plugin: String) {
        val parts = plugin.split(';')
        val head = parts[0]
        val opts = LinkedHashMap<String, String>()
        for (i in 1 until parts.size) {
            val p = parts[i]
            if (p.contains('=')) opts[p.substringBefore('=')] = p.substringAfter('=') else opts[p] = "true"
        }
        when {
            head.contains("obfs") -> {
                m["plugin"] = "obfs"
                val po = LinkedHashMap<String, Any?>()
                po["mode"] = opts["obfs"] ?: "http"
                opts["obfs-host"]?.let { po["host"] = it }
                m["plugin-opts"] = po
            }
            head.contains("v2ray") -> {
                m["plugin"] = "v2ray-plugin"
                val po = LinkedHashMap<String, Any?>()
                po["mode"] = opts["mode"] ?: "websocket"
                opts["host"]?.let { po["host"] = it }
                opts["path"]?.let { po["path"] = it }
                if (opts.containsKey("tls")) po["tls"] = true
                m["plugin-opts"] = po
            }
        }
    }

    private fun encodeSS(p: Map<String, Any?>): String {
        val method = p["cipher"]?.toString() ?: ""
        val password = p["password"]?.toString() ?: ""
        val host = p["server"]?.toString() ?: ""
        val port = p["port"]?.toString() ?: ""
        val sb = StringBuilder("ss://").append(b64EncodeUrl("$method:$password"))
            .append('@').append(host).append(':').append(port)
        val plugin = p["plugin"]?.toString()
        if (!plugin.isNullOrEmpty()) {
            val ps = buildSSPluginString(plugin, p["plugin-opts"] as? Map<*, *>)
            if (ps.isNotEmpty()) sb.append("?plugin=").append(urlEncode(ps))
        }
        p["name"]?.toString()?.let { if (it.isNotEmpty()) sb.append('#').append(urlEncode(it)) }
        return sb.toString()
    }

    private fun buildSSPluginString(plugin: String, opts: Map<*, *>?): String {
        val o = opts ?: emptyMap<Any, Any>()
        return when (plugin) {
            "obfs" -> StringBuilder("obfs-local").apply {
                o["mode"]?.let { append(";obfs=").append(it) }
                o["host"]?.let { append(";obfs-host=").append(it) }
            }.toString()
            "v2ray-plugin" -> StringBuilder("v2ray-plugin").apply {
                o["mode"]?.let { append(";mode=").append(it) }
                if (o["tls"] == true) append(";tls")
                o["host"]?.let { append(";host=").append(it) }
                o["path"]?.let { append(";path=").append(it) }
            }.toString()
            else -> ""
        }
    }

    // ---------------- VMess ----------------

    private fun decodeVmess(uri: String): Map<String, Any?> {
        val json = b64Decode(uri.substring(8))
        val o = JsonParser.parseString(json).asJsonObject
        fun str(k: String): String? = if (o.has(k) && !o.get(k).isJsonNull) o.get(k).asString else null

        val m = LinkedHashMap<String, Any?>()
        m["name"] = str("ps") ?: "${str("add")}:${str("port")}"
        m["type"] = "vmess"
        m["server"] = str("add")
        m["port"] = str("port")?.toIntOrNull() ?: str("port")
        m["uuid"] = str("id")
        m["alterId"] = str("aid")?.toIntOrNull() ?: 0
        m["cipher"] = str("scy")?.ifEmpty { null } ?: "auto"
        val net = str("net") ?: "tcp"
        m["network"] = net
        if (str("tls") == "tls" || str("tls") == "reality") {
            m["tls"] = true
            (str("sni")?.ifEmpty { null } ?: str("host")?.ifEmpty { null })?.let { m["servername"] = it }
        }
        val host = str("host"); val path = str("path")
        when (net) {
            "ws" -> {
                val ws = LinkedHashMap<String, Any?>()
                if (!path.isNullOrEmpty()) ws["path"] = path
                if (!host.isNullOrEmpty()) ws["headers"] = linkedMapOf("Host" to host)
                if (ws.isNotEmpty()) m["ws-opts"] = ws
            }
            "grpc" -> if (!path.isNullOrEmpty()) m["grpc-opts"] = linkedMapOf("grpc-service-name" to path)
            "h2" -> {
                val h = LinkedHashMap<String, Any?>()
                if (!path.isNullOrEmpty()) h["path"] = path
                if (!host.isNullOrEmpty()) h["host"] = listOf(host)
                if (h.isNotEmpty()) m["h2-opts"] = h
            }
        }
        return m
    }

    private fun encodeVmess(p: Map<String, Any?>): String {
        val o = JsonObject()
        o.addProperty("v", "2")
        o.addProperty("ps", p["name"]?.toString() ?: "")
        o.addProperty("add", p["server"]?.toString() ?: "")
        o.addProperty("port", p["port"]?.toString() ?: "")
        o.addProperty("id", p["uuid"]?.toString() ?: "")
        o.addProperty("aid", p["alterId"]?.toString() ?: "0")
        o.addProperty("scy", p["cipher"]?.toString() ?: "auto")
        val net = p["network"]?.toString() ?: "tcp"
        o.addProperty("net", net)
        o.addProperty("type", "none")
        o.addProperty("tls", if (p["tls"] == true) "tls" else "")
        p["servername"]?.toString()?.let { o.addProperty("sni", it) }
        when (net) {
            "ws" -> {
                val ws = p["ws-opts"] as? Map<*, *>
                o.addProperty("path", ws?.get("path")?.toString() ?: "")
                o.addProperty("host", (ws?.get("headers") as? Map<*, *>)?.get("Host")?.toString() ?: "")
            }
            "grpc" -> o.addProperty("path", (p["grpc-opts"] as? Map<*, *>)?.get("grpc-service-name")?.toString() ?: "")
            "h2" -> {
                val h = p["h2-opts"] as? Map<*, *>
                o.addProperty("path", h?.get("path")?.toString() ?: "")
                o.addProperty("host", (h?.get("host") as? List<*>)?.firstOrNull()?.toString() ?: "")
            }
        }
        return "vmess://" + b64EncodeStd(o.toString())
    }

    // ---------------- Trojan ----------------

    private fun decodeTrojan(uri: String): Map<String, Any?> {
        var s = uri.substring(9)
        val name = if (s.contains('#')) urlDecode(s.substringAfterLast('#')) else ""
        s = s.substringBefore('#')
        var query = ""
        if (s.contains('?')) { query = s.substringAfter('?'); s = s.substringBefore('?') }
        val password = urlDecode(s.substringBeforeLast('@'))
        val hostport = s.substringAfterLast('@')
        val host = hostport.substringBeforeLast(':')
        val portStr = hostport.substringAfterLast(':')
        val qp = parseQuery(query)

        val m = LinkedHashMap<String, Any?>()
        m["name"] = name.ifEmpty { "$host:$portStr" }
        m["type"] = "trojan"
        m["server"] = host
        m["port"] = portStr.toIntOrNull() ?: portStr
        m["password"] = password
        (qp["sni"] ?: qp["peer"])?.let { if (it.isNotEmpty()) m["sni"] = it }
        if (qp["allowInsecure"] == "1" || qp["allowInsecure"] == "true") m["skip-cert-verify"] = true
        val net = qp["type"]
        if (!net.isNullOrEmpty() && net != "tcp") {
            m["network"] = net
            when (net) {
                "ws" -> {
                    val ws = LinkedHashMap<String, Any?>()
                    qp["path"]?.let { ws["path"] = it }
                    qp["host"]?.let { ws["headers"] = linkedMapOf("Host" to it) }
                    if (ws.isNotEmpty()) m["ws-opts"] = ws
                }
                "grpc" -> qp["serviceName"]?.let { m["grpc-opts"] = linkedMapOf("grpc-service-name" to it) }
            }
        }
        return m
    }

    private fun encodeTrojan(p: Map<String, Any?>): String {
        val password = p["password"]?.toString() ?: ""
        val host = p["server"]?.toString() ?: ""
        val port = p["port"]?.toString() ?: ""
        val q = LinkedHashMap<String, String>()
        p["sni"]?.toString()?.let { if (it.isNotEmpty()) q["sni"] = it }
        if (p["skip-cert-verify"] == true) q["allowInsecure"] = "1"
        val net = p["network"]?.toString()
        if (!net.isNullOrEmpty() && net != "tcp") {
            q["type"] = net
            when (net) {
                "ws" -> {
                    val ws = p["ws-opts"] as? Map<*, *>
                    ws?.get("path")?.toString()?.let { q["path"] = it }
                    (ws?.get("headers") as? Map<*, *>)?.get("Host")?.toString()?.let { q["host"] = it }
                }
                "grpc" -> (p["grpc-opts"] as? Map<*, *>)?.get("grpc-service-name")?.toString()?.let { q["serviceName"] = it }
            }
        }
        val sb = StringBuilder("trojan://")
            .append(urlEncode(password)).append('@').append(host).append(':').append(port)
        if (q.isNotEmpty()) sb.append('?').append(buildQuery(q))
        p["name"]?.toString()?.let { if (it.isNotEmpty()) sb.append('#').append(urlEncode(it)) }
        return sb.toString()
    }

    // ---------------- VLESS ----------------

    private fun decodeVless(uri: String): Map<String, Any?> {
        var s = uri.substring(8)
        val name = if (s.contains('#')) urlDecode(s.substringAfterLast('#')) else ""
        s = s.substringBefore('#')
        var query = ""
        if (s.contains('?')) { query = s.substringAfter('?'); s = s.substringBefore('?') }
        val uuid = s.substringBeforeLast('@')
        val hostport = s.substringAfterLast('@')
        val host = hostport.substringBeforeLast(':')
        val portStr = hostport.substringAfterLast(':')
        val qp = parseQuery(query)

        val m = LinkedHashMap<String, Any?>()
        m["name"] = name.ifEmpty { "$host:$portStr" }
        m["type"] = "vless"
        m["server"] = host
        m["port"] = portStr.toIntOrNull() ?: portStr
        m["uuid"] = uuid
        val security = qp["security"] ?: "none"
        qp["flow"]?.let { if (it.isNotEmpty()) m["flow"] = it }
        if (security == "tls" || security == "reality") {
            m["tls"] = true
            (qp["sni"] ?: qp["peer"])?.let { if (it.isNotEmpty()) m["servername"] = it }
            qp["fp"]?.let { if (it.isNotEmpty()) m["client-fingerprint"] = it }
            if (security == "reality") {
                val ro = LinkedHashMap<String, Any?>()
                qp["pbk"]?.let { ro["public-key"] = it }
                qp["sid"]?.let { ro["short-id"] = it }
                if (ro.isNotEmpty()) m["reality-opts"] = ro
            }
        }
        val net = qp["type"]
        if (!net.isNullOrEmpty() && net != "tcp") {
            m["network"] = net
            when (net) {
                "ws" -> {
                    val ws = LinkedHashMap<String, Any?>()
                    qp["path"]?.let { ws["path"] = it }
                    qp["host"]?.let { ws["headers"] = linkedMapOf("Host" to it) }
                    if (ws.isNotEmpty()) m["ws-opts"] = ws
                }
                "grpc" -> qp["serviceName"]?.let { m["grpc-opts"] = linkedMapOf("grpc-service-name" to it) }
            }
        }
        return m
    }

    private fun encodeVless(p: Map<String, Any?>): String {
        val uuid = p["uuid"]?.toString() ?: ""
        val host = p["server"]?.toString() ?: ""
        val port = p["port"]?.toString() ?: ""
        val q = LinkedHashMap<String, String>()
        q["encryption"] = "none"
        val isReality = p["reality-opts"] != null
        q["security"] = if (isReality) "reality" else if (p["tls"] == true) "tls" else "none"
        p["servername"]?.toString()?.let { if (it.isNotEmpty()) q["sni"] = it }
        p["client-fingerprint"]?.toString()?.let { if (it.isNotEmpty()) q["fp"] = it }
        p["flow"]?.toString()?.let { if (it.isNotEmpty()) q["flow"] = it }
        (p["reality-opts"] as? Map<*, *>)?.let {
            it["public-key"]?.toString()?.let { v -> q["pbk"] = v }
            it["short-id"]?.toString()?.let { v -> q["sid"] = v }
        }
        val net = p["network"]?.toString() ?: "tcp"
        q["type"] = net
        when (net) {
            "ws" -> {
                val ws = p["ws-opts"] as? Map<*, *>
                ws?.get("path")?.toString()?.let { q["path"] = it }
                (ws?.get("headers") as? Map<*, *>)?.get("Host")?.toString()?.let { q["host"] = it }
            }
            "grpc" -> (p["grpc-opts"] as? Map<*, *>)?.get("grpc-service-name")?.toString()?.let { q["serviceName"] = it }
        }
        val sb = StringBuilder("vless://")
            .append(uuid).append('@').append(host).append(':').append(port)
            .append('?').append(buildQuery(q))
        p["name"]?.toString()?.let { if (it.isNotEmpty()) sb.append('#').append(urlEncode(it)) }
        return sb.toString()
    }
}
