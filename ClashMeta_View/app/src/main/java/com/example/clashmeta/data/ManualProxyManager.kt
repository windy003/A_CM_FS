package com.example.clashmeta.data

import android.util.Log
import com.example.clashmeta.ClashMetaApp
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 手动添加节点（[ProxyClipboard] 粘贴进来的，典型就是「自己的 VPS」）的独立持久化 + 回注。
 *
 * ## 为什么需要它
 * 粘贴节点是直接写进 `config.yaml` 的，而 `config.yaml` 每次切换/更新订阅都会被
 * `sub_xxxx.yaml` **整份覆盖**（见 ProfileFragment）。于是手动节点连同它在 select 组里的
 * 成员身份一起消失，表现为：
 *   - 节点列表里少了自己的 VPS；
 *   - [ChainRouteManager] 找不到出口节点，静默跳过注入，VPS 中继总开关看着开着却不生效；
 *   - 内核用 cache.db 里的旧选中名恢复不上，代理组落回第一个节点。
 *
 * 所以把这些节点单独存一份（`manual_proxies.yaml`），在每次重建 config.yaml 时重新注入。
 * 注入点在 [LanProxyManager.applyToConfigFile]，且**必须排在 [ChainRouteManager] 之前**——
 * 后者要能在 proxies 里找到出口节点名才会注入 dialer-proxy 和 MATCH。
 *
 * ## 为什么用行改写而不是 YAML 重排
 * 与 [ChainRouteManager] / [AutoGroupSanitizer] 同理：整份 load/dump 会把 flow 风格的节点行
 * 重排成 block 风格，让 [AutoGroupSanitizer] 的行级正则再也匹配不上，清洗静默失效。
 * 这里只做定向插入，其余字节保持不变。
 *
 * ## 幂等
 * 注入前按名字去重：订阅里已经有同名节点就跳过（既不重复插入，也不覆盖订阅自己的节点）。
 */
object ManualProxyManager {

    private const val TAG = "ManualProxyManager"
    private const val STORE_FILE = "manual_proxies.yaml"

    private val TOP_LEVEL_PROXIES = Regex("^proxies:\\s*$")
    private val INLINE_EMPTY_PROXIES = Regex("^proxies:\\s*\\[\\s*]\\s*$")
    private val TOP_LEVEL_GROUPS = Regex("^proxy-groups:\\s*$")
    private val ENTRY_START = Regex("^(\\s*)-\\s+\\S.*$")
    private val LIST_ITEM_INDENT = Regex("^(\\s*)-.*$")
    private val SELECT_TYPE = Regex("\\btype:\\s*select\\b", RegexOption.IGNORE_CASE)

    /** 组行内的 `proxies: [ ... ]`（flow 风格组，或 block 组里的内联成员表）。 */
    private val PROXIES_ARRAY = Regex("(proxies:\\s*\\[)([^\\]]*)(\\])")

    /** 组里独占一行的 `proxies:`，成员在下面逐行列出。 */
    private val PROXIES_BLOCK = Regex("^(\\s*)proxies:\\s*$")

    /** 从 `name: 'x'` / `name: "x"` / `name: x,` 提取名字。与 [AutoGroupSanitizer] 同款。 */
    private val NAME_FIELD = Regex("\\bname:\\s*('([^']*)'|\"([^\"]*)\"|([^,}]+))")

    // ----------------------------------------------------------------- 持久化

    private fun storeFile(): File = File(ClashMetaApp.instance.getClashDir(), STORE_FILE)

    private fun flowDumper(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.FLOW
            isPrettyFlow = false
            width = Int.MAX_VALUE
        }
        return Yaml(opts)
    }

    /** 已保存的手动节点。解析失败返回空列表（宁可少注入，也不能让 VPN 起不来）。 */
    @Suppress("UNCHECKED_CAST")
    fun loadNodes(): List<Map<String, Any?>> = try {
        val f = storeFile()
        if (!f.exists()) {
            emptyList()
        } else {
            (Yaml().load<List<Map<String, Any?>>>(f.readText()) ?: emptyList())
                .filter { it["name"] != null && it["type"] != null }
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadNodes failed", e)
        emptyList()
    }

    fun names(): List<String> = loadNodes().mapNotNull { it["name"]?.toString() }

    private fun saveNodes(nodes: List<Map<String, Any?>>) {
        try {
            val f = storeFile()
            if (nodes.isEmpty()) {
                if (f.exists()) f.delete()
                return
            }
            f.parentFile?.mkdirs()
            f.writeText(nodes.joinToString("\n") { "- " + flowDumper().dump(it).trim() })
        } catch (e: Exception) {
            Log.e(TAG, "saveNodes failed", e)
        }
    }

    /** 收录（或按名字覆盖）一批节点。 */
    fun capture(nodes: List<Map<String, Any?>>) {
        if (nodes.isEmpty()) return
        val valid = nodes.filter { it["name"] != null && it["type"] != null }
        if (valid.isEmpty()) return
        val merged = LinkedHashMap<String, Map<String, Any?>>()
        for (n in loadNodes()) merged[n["name"].toString()] = n
        for (n in valid) merged[n["name"].toString()] = n
        saveNodes(merged.values.toList())
    }

    /** 从 config.yaml 里按名字取出刚粘贴进去的节点并收录。 */
    @Suppress("UNCHECKED_CAST")
    fun captureFromConfig(configFile: File, names: List<String>) {
        if (names.isEmpty()) return
        try {
            if (!configFile.exists()) return
            val root = Yaml().load<Map<String, Any?>>(configFile.readText()) ?: return
            val proxies = root["proxies"] as? List<Map<String, Any?>> ?: return
            capture(proxies.filter { it["name"]?.toString() in names })
        } catch (e: Exception) {
            Log.e(TAG, "captureFromConfig failed", e)
        }
    }

    // 注意：不要再加「自动收编 config.yaml 里订阅之外的节点」这类功能。
    //
    // 曾经有过一个 captureOrphans(config, 上一个订阅文件)：凡是 config.yaml 里有、而基准订阅
    // 文件里没有的节点就当成手动节点收录，用来兜住老版本留下的 VPS。它的前提是「config.yaml 的
    // 内容一定来自 activeConfigId 指向的那个订阅」——而 ProfileFragment 的 activeConfigId 只在
    // 创建时读一次，这个前提并不总成立。一旦基准取错，**整份机场的节点表**会被判成手动节点存进来，
    // 然后注入进之后每一份订阅、加进每个 select 组：实测把 wgetCloud 的 18 个 trojan 节点注进了
    // BoostNet 的配置，内核恢复选择时选中了其中一个，于是"选了 anytls 节点却走 trojan 出口"。
    //
    // 手动节点只在用户显式粘贴时收录（[captureFromConfig]），那条路径是确定的。宁可少收，不可误收。

    /** 用户在节点页删除了节点：同步从存档里移除，否则下次注入又会回来。 */
    fun remove(name: String) {
        val nodes = loadNodes()
        val kept = nodes.filter { it["name"]?.toString() != name }
        if (kept.size != nodes.size) saveNodes(kept)
    }

    // ------------------------------------------------------------------- 注入

    /** 把已保存的手动节点注入到配置文本里。 */
    fun injectInto(configText: String): String = inject(configText, loadNodes())

    /**
     * 纯函数版注入：不碰文件，便于单测覆盖 flow/block 两种写法与幂等。
     *
     * 做两件事：
     *   1. 把缺失的节点定义插到 `proxies:` 块顶部（flow 单行，保持 [AutoGroupSanitizer] 能识别）；
     *   2. 把节点名加进所有 select 组，否则用户在节点页点它时 `selectProxy` 会因为不是组成员而失败。
     */
    fun inject(configText: String, nodes: List<Map<String, Any?>>): String {
        if (nodes.isEmpty()) return configText
        return try {
            var lines = configText.split("\n").toMutableList()

            val existing = proxyNames(lines)
            val pending = nodes.filter {
                val n = it["name"]?.toString().orEmpty()
                n.isNotEmpty() && n !in existing
            }
            if (pending.isEmpty()) return configText

            lines = insertProxies(lines, pending).toMutableList()
            lines = addToSelectGroups(lines, pending.map { it["name"].toString() }).toMutableList()
            lines.joinToString("\n")
        } catch (e: Exception) {
            // 注入失败不能连累 VPN 启动，原样返回
            Log.e(TAG, "inject failed", e)
            configText
        }
    }

    /** 收集 `proxies:` 块里已有的节点名（行级扫描，不做整份解析）。 */
    private fun proxyNames(lines: List<String>): Set<String> {
        val range = blockRange(lines, TOP_LEVEL_PROXIES) ?: return emptySet()
        val out = HashSet<String>()
        for (i in range) nameOf(lines[i])?.let { out.add(it) }
        return out
    }

    /** 把节点定义插到 `proxies:` 块顶部；块为内联空表或整个缺失时也兜住。 */
    private fun insertProxies(lines: List<String>, nodes: List<Map<String, Any?>>): List<String> {
        val out = lines.toMutableList()
        val dumper = flowDumper()

        var blockIdx = out.indexOfFirst { TOP_LEVEL_PROXIES.matches(it) }
        if (blockIdx < 0) {
            val inlineIdx = out.indexOfFirst { INLINE_EMPTY_PROXIES.matches(it) }
            if (inlineIdx >= 0) {
                out[inlineIdx] = "proxies:"
                blockIdx = inlineIdx
            } else {
                if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
                out.add("proxies:")
                blockIdx = out.size - 1
            }
        }

        val indent = detectItemIndent(out, blockIdx)
        val entries = nodes.map { "$indent- " + dumper.dump(it).trim() }
        out.addAll(blockIdx + 1, entries)
        return out
    }

    /** 把节点名加进所有 select 组的成员表。 */
    private fun addToSelectGroups(lines: List<String>, names: List<String>): List<String> {
        val out = lines.toMutableList()
        val range = blockRange(out, TOP_LEVEL_GROUPS) ?: return out
        val quoted = names.map { yamlQuote(it) }

        var i = range.first
        var last = range.last
        while (i <= last) {
            val m = ENTRY_START.find(out[i])
            if (m == null) {
                i++
                continue
            }
            val entryIndent = m.groupValues[1]
            // 本条目的范围：到下一个同级条目或块尾为止
            var end = i + 1
            while (end <= last) {
                val nm = ENTRY_START.find(out[end])
                if (nm != null && nm.groupValues[1].length <= entryIndent.length) break
                end++
            }

            val isSelect = (i until end).any { SELECT_TYPE.containsMatchIn(out[it]) }
            if (isSelect) {
                val added = appendMembers(out, i, end, names, quoted)
                last += added
                end += added
            }
            i = end
        }
        return out
    }

    /**
     * 往 [from, to) 这个组条目的成员表里追加名字，返回新增的行数。
     * 成员表有两种写法：行内 `proxies: [a, b]`，或独占一行的 `proxies:` + 下面的列表项。
     */
    private fun appendMembers(
        out: MutableList<String>,
        from: Int,
        to: Int,
        names: List<String>,
        quoted: List<String>
    ): Int {
        // 写法一：行内数组
        val arrayIdx = (from until to).firstOrNull { PROXIES_ARRAY.containsMatchIn(out[it]) }
        if (arrayIdx != null) {
            out[arrayIdx] = PROXIES_ARRAY.replace(out[arrayIdx]) { m ->
                val body = m.groupValues[2].trim()
                val have = body.split(",").map { unquote(it.trim()) }.toSet()
                val add = names.indices.filter { names[it] !in have }.map { quoted[it] }
                if (add.isEmpty()) {
                    m.value
                } else {
                    val joined = if (body.isEmpty()) add.joinToString(", ")
                    else "$body, " + add.joinToString(", ")
                    m.groupValues[1] + joined + m.groupValues[3]
                }
            }
            return 0
        }

        // 写法二：block 列表
        val blockIdx = (from until to).firstOrNull { PROXIES_BLOCK.matches(out[it]) } ?: return 0
        val keyIndent = PROXIES_BLOCK.find(out[blockIdx])!!.groupValues[1]
        var memberEnd = blockIdx
        var memberIndent: String? = null
        for (i in (blockIdx + 1) until to) {
            val lm = LIST_ITEM_INDENT.matchEntire(out[i]) ?: break
            if (lm.groupValues[1].length <= keyIndent.length) break
            memberIndent = lm.groupValues[1]
            memberEnd = i
        }
        val have = (blockIdx + 1..memberEnd)
            .map { unquote(out[it].trim().removePrefix("-").trim()) }.toSet()
        val indent = memberIndent ?: "$keyIndent  "
        val add = names.indices.filter { names[it] !in have }.map { "$indent- " + quoted[it] }
        if (add.isEmpty()) return 0
        out.addAll(memberEnd + 1, add)
        return add.size
    }

    /** 取某个顶层块的行号区间（不含块头那行）；没有该块返回 null。 */
    private fun blockRange(lines: List<String>, header: Regex): IntRange? {
        val start = lines.indexOfFirst { header.matches(it) }
        if (start < 0) return null
        var end = start
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            // 空行不算块结束（订阅里常见空行分隔）；顶格的非空行才是下一个顶层键
            if (line.isNotBlank() && !line.first().isWhitespace()) break
            end = i
        }
        return if (end > start) (start + 1)..end else null
    }

    /** 块内列表项的缩进，取不到默认两空格。 */
    private fun detectItemIndent(lines: List<String>, blockIdx: Int): String {
        for (i in (blockIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val m = LIST_ITEM_INDENT.matchEntire(line) ?: break
            return m.groupValues[1]
        }
        return "  "
    }

    private fun nameOf(line: String): String? {
        val m = NAME_FIELD.find(line) ?: return null
        val raw = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        return raw.trim().ifEmpty { null }
    }

    /** 统一用双引号包裹：节点名常含 emoji / 空格 / 冒号，裸写会让 YAML 解析歪掉。 */
    private fun yamlQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
