package com.example.clashmeta.data

/**
 * 清洗代理组成员：剔除「假节点」与「核心不支持的 anytls 幽灵节点」。
 *
 * 背景一（假节点）：机场订阅常把「剩余流量 / 套餐到期 / 官网地址」等信息塞成节点，其 server 填
 * 占位 IP 8.8.8.8:8，根本连不通。若留在 url-test(自动选择) / fallback(故障转移) 组里，自动逻辑
 * 可能选中它们，导致大量请求 dial 8.8.8.8:8 超时。
 *
 * 背景二（anytls）：本 app 内置的 clash.aar 核心**未编译 anytls 协议**(二进制里无该符号)。订阅
 * 里的 anytls 节点会被核心丢弃，既不显示在节点列表、也无法承载流量；但它们的**名字仍留在各代理组的
 * proxies 列表里**，一旦被「自动选择」选中，流量进去就石沉大海(表现为 Google/TikTok 等突然打不开)。
 *
 * 方案：
 *   - 假节点(8.8.8.8)：只从 url-test / fallback 自动组剔除，select 组保留(用户仍能看订阅信息)。
 *   - anytls 节点：从**所有**代理组剔除(它是选中即失败的幽灵，没有保留价值)。
 *
 * 实现为「按行的定向文本改写」：只重写命中的那一行组定义，其余字节保持不变，避免整份 YAML 重排、
 * 丢注释，也不与 [LanProxyManager] / [QuicRuleManager] 的行级补丁冲突。幂等：再跑一次是 no-op。
 *
 * 假设：节点名里不含逗号（机场信息节点均满足）；含逗号会影响该组的成员切分。
 */
object AutoGroupSanitizer {

    /** 占位/假节点的 server 特征。 */
    private val DEAD_SERVER = Regex("\\bserver:\\s*8\\.8\\.8\\.8\\b")

    /** 核心不支持的 anytls 协议节点。 */
    private val TYPE_ANYTLS = Regex("\\btype:\\s*anytls\\b", RegexOption.IGNORE_CASE)

    /** 一条 proxy 定义行（flow 风格）：- { name: xxx, ... }
     *  注意：Android 的 ICU 正则引擎对裸 `}` 会报语法错误，必须转义成 `\}`。 */
    private val PROXY_LINE = Regex("^\\s*-\\s*\\{.*\\bname:\\s*.*\\}\\s*$")

    /** 是否为自动组（url-test / fallback）——假节点仅从这类组剔除。 */
    private val AUTO_GROUP_TYPE = Regex("\\btype:\\s*(url-test|fallback)\\b")

    /** 组行内的 proxies: [ ... ] 数组。 */
    private val PROXIES_ARRAY = Regex("(proxies:\\s*\\[)([^\\]]*)(\\])")

    fun patch(configText: String): String {
        val lines = configText.split("\n")

        // 1) 收集要清理的节点名
        val deadNames = HashSet<String>()   // 8.8.8.8 假节点：仅从自动组剔除
        val anytlsNames = HashSet<String>() // anytls 幽灵节点：从所有组剔除
        for (line in lines) {
            if (!PROXY_LINE.matches(line)) continue
            val name = extractName(line) ?: continue
            if (DEAD_SERVER.containsMatchIn(line)) deadNames.add(name)
            if (TYPE_ANYTLS.containsMatchIn(line)) anytlsNames.add(name)
        }
        if (deadNames.isEmpty() && anytlsNames.isEmpty()) return configText

        // 2) 逐行改写代理组的 proxies:[...]
        val out = lines.map { line ->
            if (!PROXIES_ARRAY.containsMatchIn(line)) return@map line
            val isAutoGroup = AUTO_GROUP_TYPE.containsMatchIn(line)
            PROXIES_ARRAY.replace(line) { m ->
                val kept = splitTopLevel(m.groupValues[2]).filter { token ->
                    val nm = unquote(token.trim())
                    // anytls 从所有组删；dead 仅从自动组删
                    nm !in anytlsNames && !(isAutoGroup && nm in deadNames)
                }
                // 兜底：全删空会让组非法（proxies 不能为空），此时保持原样
                if (kept.isEmpty()) m.value
                else m.groupValues[1] + kept.joinToString(", ") + m.groupValues[3]
            }
        }
        return out.joinToString("\n")
    }

    /** 从 `name: 'x'` / `name: "x"` / `name: x,` 提取节点名。 */
    private fun extractName(line: String): String? {
        val m = Regex("\\bname:\\s*('([^']*)'|\"([^\"]*)\"|([^,}]+))").find(line) ?: return null
        val raw = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        return raw.trim().ifEmpty { null }
    }

    /** 去掉包裹的单/双引号。 */
    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    /** 按顶层逗号切分（节点名不含逗号的前提下等价于普通 split，保留原始 token）。 */
    private fun splitTopLevel(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
