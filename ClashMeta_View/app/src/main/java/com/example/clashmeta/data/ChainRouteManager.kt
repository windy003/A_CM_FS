package com.example.clashmeta.data

import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml

/**
 * 指定域名走「自己的 VPS」，并让 VPS 这一跳经由机场节点拨出（链式代理）。
 *
 * 目标链路：
 *     手机 ──► 机场节点(前置跳) ──► 自己的 VPS(出口) ──► 目标站点
 *
 * 为什么要两跳：VPS 的 IP 可能被 GFW 封掉、手机直连不通，所以第一跳借机场翻出去；
 * 第二跳用自己的 VPS，是为了拿机场给不了的带宽和线路。若手机本来就能直连 VPS，
 * 把「前置跳」留空即可退化成单跳（更快，没有中间损耗）。
 *
 * 靠内核的 `dialer-proxy` 实现：给出口节点加一行，它自己的出站连接就会先从指定代理拨出。
 * 见 clash-core/component/proxydialer/proxydialer.go —— 那里用 `tunnel.Proxies()` 查名字，
 * 而这张表**把代理组也当成 proxy 收录**，所以 dialer-proxy 可以直接填组名。
 * 填组名比填具体节点名强得多：节点被封时组会自己测速切换，前置跳跟着自动换，链路自愈。
 *
 * ## 注入顺序
 * 必须在 [QuicRuleManager.patchRules] 和 [TikTokRuleManager.patchRules] **之后**调用。
 * 本类把规则插到 rules 最顶端，排在那条「境外 UDP 443 → 兜底组」之上，否则目标站点的
 * QUIC(HTTP/3) 流量会被它先抓走送去机场兜底组，只有 TCP 走 VPS——速度上不去还极难排查。
 *
 * ## 为什么用文本改写而不是 YAML 重排
 * 与 [AutoGroupSanitizer] 同理：整份 load/dump 会把 flow 风格的节点行重排成 block 风格，
 * 让 [AutoGroupSanitizer] 的行级正则再也匹配不上，它的清洗会静默失效。所以这里只做定向
 * 的行改写，其余字节保持不变。
 *
 * ## 幂等
 * 每次注入前先回滚上一次的改动：
 *   - dialer-proxy：从**所有**节点上剥掉（本 app 别处不用这个字段，全局剥离最省心，
 *     且天然覆盖「换了出口节点」「关掉了功能」这些情况，不会留下孤儿）。
 *   - 规则行：删掉上次注入时逐字记下的那几行（存在 SharedPreferences），
 *     外加删掉所有指向当前出口节点的域名规则。两者取并集，改域名/改节点/清空都能干净回滚。
 *     不用文件内的注释做标记，是因为 [ProxyClipboard] 写回时走 YAML dump 会丢掉全部注释。
 */
object ChainRouteManager {

    private const val TAG = "ChainRouteManager"
    private const val PREF_NAME = "chain_route"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXIT = "exit_proxy"
    private const val KEY_DIALER = "dialer_proxy"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_LAST_RULES = "last_rules"

    /** 域名输入里用这个前缀表示关键词匹配，例如 `keyword:surrit`。 */
    private const val KEYWORD_PREFIX = "keyword:"

    // ---------------------------------------------------------------- 设置读写

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    /** 出口节点名（自己的 VPS）。 */
    fun getExit(ctx: Context): String = prefs(ctx).getString(KEY_EXIT, "") ?: ""

    /** 前置跳：机场的代理组名；空表示不套机场、手机直连 VPS。 */
    fun getDialer(ctx: Context): String = prefs(ctx).getString(KEY_DIALER, "") ?: ""

    /** 用户填的域名，一行一个，已去空行去重。 */
    fun getDomains(ctx: Context): List<String> = parseDomains(prefs(ctx).getString(KEY_DOMAINS, ""))

    /** 域名原文（供输入框回显，保留用户的换行排版）。 */
    fun getDomainsRaw(ctx: Context): String = prefs(ctx).getString(KEY_DOMAINS, "") ?: ""

    fun save(ctx: Context, exit: String, dialer: String, domainsRaw: String) {
        prefs(ctx).edit()
            .putString(KEY_EXIT, exit)
            .putString(KEY_DIALER, dialer)
            .putString(KEY_DOMAINS, domainsRaw)
            .apply()
    }

    private fun parseDomains(raw: String?): List<String> =
        (raw ?: "").split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

    // ---------------------------------------------------------- 供 UI 的下拉数据

    /**
     * 读出 config.yaml 里的节点名。只读解析，不写回，所以不会引起 YAML 重排。
     * 解析失败（文件缺失/订阅格式异常）返回空列表，UI 据此提示用户先导入节点。
     */
    @Suppress("UNCHECKED_CAST")
    fun listProxyNames(configText: String): List<String> = try {
        val root = Yaml().load<Map<String, Any?>>(configText)
        (root?.get("proxies") as? List<Map<String, Any?>>)
            ?.mapNotNull { it["name"]?.toString() }.orEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "listProxyNames failed", e)
        emptyList()
    }

    /** 读出代理组名，供「前置跳」下拉选择。 */
    @Suppress("UNCHECKED_CAST")
    fun listGroupNames(configText: String): List<String> = try {
        val root = Yaml().load<Map<String, Any?>>(configText)
        (root?.get("proxy-groups") as? List<Map<String, Any?>>)
            ?.mapNotNull { it["name"]?.toString() }.orEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "listGroupNames failed", e)
        emptyList()
    }

    // ------------------------------------------------------------------ 注入

    private val TOP_LEVEL_RULES = Regex("^rules:\\s*$")
    private val INLINE_EMPTY_RULES = Regex("^rules:\\s*\\[\\s*]\\s*$")
    private val TOP_LEVEL_PROXIES = Regex("^proxies:\\s*$")
    private val LIST_ITEM_INDENT = Regex("^(\\s*)-.*$")
    private val ENTRY_START = Regex("^(\\s*)-\\s+\\S.*$")

    /** block 风格里独占一行的 dialer-proxy。 */
    private val BLOCK_DIALER_LINE = Regex("^\\s+dialer-proxy:\\s*.*$")

    /**
     * flow 风格节点行里的 dialer-proxy 片段。分「前面有逗号」和「紧贴左括号」两种，
     * 先删前者再删后者，才能把逗号一起收干净、不留 `{, type: ss}` 这种坏 YAML。
     *
     * 注意：Android 的 ICU 正则引擎对裸的右花括号会报语法错误，必须转义成 \\}。
     */
    private val FLOW_DIALER_TAIL =
        Regex(",\\s*dialer-proxy:\\s*(\"[^\"]*\"|'[^']*'|[^,\\}]*)")
    private val FLOW_DIALER_HEAD =
        Regex("dialer-proxy:\\s*(\"[^\"]*\"|'[^']*'|[^,\\}]*)\\s*,\\s*")

    /** 从 `name: 'x'` / `name: "x"` / `name: x,` 提取名字。与 [AutoGroupSanitizer] 同款。 */
    private val NAME_FIELD =
        Regex("\\bname:\\s*('([^']*)'|\"([^\"]*)\"|([^,\\}]+))")

    /** [patch] 的结果：改写后的文本，以及本次注入的规则行（供下次回滚时逐字删除）。 */
    data class Patched(val text: String, val injectedRules: List<String>)

    /**
     * 按当前设置改写 config.yaml 文本。读写 SharedPreferences，是 [patch] 的薄封装。
     */
    fun patchRules(configText: String, ctx: Context): String {
        return try {
            val lastRules = (prefs(ctx).getString(KEY_LAST_RULES, "") ?: "")
                .split("\n").filter { it.isNotBlank() }.toSet()

            val r = patch(
                configText = configText,
                exit = getExit(ctx).trim(),
                dialer = getDialer(ctx).trim(),
                domains = getDomains(ctx),
                enabled = isEnabled(ctx),
                lastRules = lastRules
            )

            prefs(ctx).edit().apply {
                if (r.injectedRules.isEmpty()) remove(KEY_LAST_RULES)
                else putString(KEY_LAST_RULES, r.injectedRules.joinToString("\n"))
            }.apply()

            r.text
        } catch (e: Exception) {
            // 注入是锦上添花，出错绝不能连累 VPN 启动，原样返回
            Log.e(TAG, "patchRules failed", e)
            configText
        }
    }

    /**
     * 纯函数版注入：不碰 Context，所有输入显式传进来，便于单测覆盖
     * flow/block 两种节点写法、幂等回滚、名字对不上时的降级。
     *
     * 关闭功能或没选出口节点时，只做回滚（剥 dialer-proxy + 删旧规则）后返回，
     * 保证「关掉开关」能立刻恢复成没动过的样子。
     */
    fun patch(
        configText: String,
        exit: String,
        dialer: String,
        domains: List<String>,
        enabled: Boolean,
        lastRules: Set<String>
    ): Patched {
        // 1) 回滚上一次注入
        var lines = rollback(configText, lastRules, exit).toMutableList()

        val active = enabled && exit.isNotEmpty() && domains.isNotEmpty()
        if (!active) return Patched(lines.joinToString("\n"), emptyList())

        // 2) 出口节点必须真实存在，否则内核会因为 dialer-proxy 指向不存在的名字而起不来
        val text = lines.joinToString("\n")
        if (!listProxyNames(text).contains(exit)) {
            Log.w(TAG, "exit proxy '$exit' not found in config, skip injection")
            return Patched(text, emptyList())
        }

        // 3) 给出口节点挂上前置跳。dialer 为空 = 不套机场，手机直连 VPS
        if (dialer.isNotEmpty()) {
            if (listGroupNames(text).contains(dialer) || listProxyNames(text).contains(dialer)) {
                lines = attachDialer(lines, exit, dialer).toMutableList()
            } else {
                // 组名对不上（多半是订阅更新后改名了）。此时宁可退化成单跳也不要注入
                // 一个查不到的名字——那会让整个节点初始化失败，连直连 VPS 都用不了。
                Log.w(TAG, "dialer proxy '$dialer' not found, fall back to single hop")
            }
        }

        // 4) 域名规则插到 rules 顶端
        val newRules = buildRules(lines, domains, exit)
        lines = insertRules(lines, newRules).toMutableList()
        return Patched(lines.joinToString("\n"), newRules)
    }

    /** 剥掉所有 dialer-proxy，并删除上次注入的规则行 + 所有指向 exit 的域名规则。 */
    private fun rollback(configText: String, lastRules: Set<String>, exit: String): List<String> {
        // 指向当前出口节点的域名规则：出口是用户自己的 VPS，订阅规则不可能引用它，
        // 所以按目标名匹配是安全且精确的。
        val byTarget = if (exit.isEmpty()) null else Regex(
            "^\\s*-\\s*DOMAIN(?:-SUFFIX|-KEYWORD)?,[^,]+,\\s*" + Regex.escape(exit) + "\\s*$",
            RegexOption.IGNORE_CASE
        )

        return configText.split("\n").mapNotNull { line ->
            when {
                BLOCK_DIALER_LINE.matches(line) -> null
                lastRules.contains(line) -> null
                byTarget != null && byTarget.matches(line) -> null
                line.contains("dialer-proxy") ->
                    FLOW_DIALER_HEAD.replace(FLOW_DIALER_TAIL.replace(line, ""), "")
                else -> line
            }
        }
    }

    /**
     * 给名为 [exit] 的节点加上 `dialer-proxy: [dialer]`。
     * flow（单行花括号）与 block（多行字段）两种写法都要支持——订阅原文多是 flow，
     * 但经 [ProxyClipboard] 导入节点后整份会被 dump 成 block。
     */
    private fun attachDialer(lines: List<String>, exit: String, dialer: String): List<String> {
        val out = lines.toMutableList()
        val quoted = "\"" + dialer.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val range = proxiesBlockRange(out) ?: return out
        var i = range.first
        while (i <= range.last) {
            val m = ENTRY_START.find(out[i])
            if (m == null) {
                i++
                continue
            }

            val entryIndent = m.groupValues[1]
            // 本条目的范围：到下一个同级条目或块尾为止
            var end = i + 1
            while (end <= range.last) {
                val nm = ENTRY_START.find(out[end])
                if (nm != null && nm.groupValues[1].length <= entryIndent.length) break
                end++
            }

            val nameLineIdx = (i until end).firstOrNull { idx -> nameOf(out[idx]) == exit }
            if (nameLineIdx != null) {
                if (out[i].contains("{")) {
                    // flow：塞在左花括号后面，位置无所谓，YAML 映射无序
                    out[i] = out[i].replaceFirst("{", "{dialer-proxy: $quoted, ")
                } else {
                    // block：与兄弟字段同缩进。字段列 = 条目缩进 + "- " 的两格
                    val fieldIndent = " ".repeat(entryIndent.length + 2)
                    out.add(nameLineIdx + 1, "${fieldIndent}dialer-proxy: $quoted")
                }
                return out
            }
            i = end
        }
        return out
    }

    /** 取 `proxies:` 块的行号区间（不含 `proxies:` 那行本身）；没有该块返回 null。 */
    private fun proxiesBlockRange(lines: List<String>): IntRange? {
        val start = lines.indexOfFirst { TOP_LEVEL_PROXIES.matches(it) }
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

    private fun nameOf(line: String): String? {
        val m = NAME_FIELD.find(line) ?: return null
        val raw = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        return raw.trim().ifEmpty { null }
    }

    /** 把用户填的域名转成规则行。`keyword:` 前缀走关键词匹配，其余按后缀匹配。 */
    private fun buildRules(lines: List<String>, domains: List<String>, exit: String): List<String> {
        val blockIdx = lines.indexOfFirst { TOP_LEVEL_RULES.matches(it) }
        val indent = if (blockIdx >= 0) detectIndent(lines, blockIdx) else "  "
        return domains.map { d ->
            if (d.startsWith(KEYWORD_PREFIX, ignoreCase = true)) {
                val kw = d.substring(KEYWORD_PREFIX.length).trim()
                "$indent- DOMAIN-KEYWORD,$kw,$exit"
            } else {
                "$indent- DOMAIN-SUFFIX,$d,$exit"
            }
        }
    }

    /** 插到 rules 顶端；块状 / 内联空 / 整个缺失 三种情况都兜住。与 TikTokRuleManager 同款。 */
    private fun insertRules(lines: List<String>, newRules: List<String>): List<String> {
        val out = lines.toMutableList()
        val blockIdx = out.indexOfFirst { TOP_LEVEL_RULES.matches(it) }
        if (blockIdx >= 0) {
            out.addAll(blockIdx + 1, newRules)
            return out
        }
        val inlineIdx = out.indexOfFirst { INLINE_EMPTY_RULES.matches(it) }
        if (inlineIdx >= 0) {
            out[inlineIdx] = "rules:"
            out.addAll(inlineIdx + 1, newRules)
            return out
        }
        if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
        out.add("rules:")
        out.addAll(newRules)
        return out
    }

    /**
     * 取 rules 块里出现次数最多的列表项缩进（多数表决），取不到默认 2 空格。
     * 用多数表决而非「看第一行」，是因为第一行可能是别的 Manager 上次注入、缩进本身就有问题的行。
     */
    private fun detectIndent(lines: List<String>, rulesIdx: Int): String {
        val counts = HashMap<String, Int>()
        for (i in (rulesIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val m = LIST_ITEM_INDENT.matchEntire(line) ?: break
            counts[m.groupValues[1]] = (counts[m.groupValues[1]] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: "  "
    }
}
