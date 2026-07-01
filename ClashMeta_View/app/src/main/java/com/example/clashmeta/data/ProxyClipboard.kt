package com.example.clashmeta.data

import android.util.Log
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 节点复制/粘贴：直接读写 config.yaml。
 * - 复制：把某个节点的完整配置导出为单行 YAML（便于分享/再导入）。
 * - 粘贴：把剪贴板中的节点（YAML 或 JSON）追加进 config.yaml，并加入 select 分组。
 *
 * 说明：写回 config.yaml 会重排格式并丢弃注释（如 #!MANAGED-CONFIG 头）；
 * 由于订阅内容由 App 单独管理，粘贴的节点属于“手动临时添加”，下次刷新订阅会被覆盖。
 */
object ProxyClipboard {

    private const val TAG = "ProxyClipboard"

    private fun loader(): Yaml = Yaml()

    private fun flowDumper(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.FLOW
            isPrettyFlow = false
            width = Int.MAX_VALUE
        }
        return Yaml(opts)
    }

    private fun blockDumper(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        return Yaml(opts)
    }

    /**
     * 导出指定名称节点为分享链接（ss:// / vmess:// / trojan:// / vless://）。
     * 若该协议无法转成分享链接（如 hysteria2/tuic/ssr），回退为单行 YAML。
     * 找不到节点返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    fun exportProxy(configFile: File, name: String): String? {
        return try {
            if (!configFile.exists()) return null
            val root = loader().load<Map<String, Any?>>(configFile.readText()) ?: return null
            val proxies = root["proxies"] as? List<Map<String, Any?>> ?: return null
            val p = proxies.firstOrNull { it["name"]?.toString() == name } ?: return null
            ShareLink.encode(p) ?: flowDumper().dump(p).trim()
        } catch (e: Exception) {
            Log.e(TAG, "exportProxy failed", e)
            null
        }
    }

    /**
     * 从剪贴板文本解析出代理映射列表。
     * 优先按分享链接解析（ss:// 等，可多行多个）；否则回退 YAML/JSON。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseClip(text: String): List<Map<String, Any?>> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 分享链接优先
        if (ShareLink.containsShareLink(trimmed)) {
            val links = ShareLink.decodeMany(trimmed)
            if (links.isNotEmpty()) return links
        }

        val loaded = loader().load<Any?>(trimmed) ?: return emptyList()
        return when (loaded) {
            is Map<*, *> -> {
                val m = loaded as Map<String, Any?>
                val inner = m["proxies"]
                when {
                    inner is List<*> -> inner.filterIsInstance<Map<String, Any?>>()
                    m.containsKey("name") && m.containsKey("type") -> listOf(m)
                    else -> emptyList()
                }
            }
            is List<*> -> loaded.filterIsInstance<Map<String, Any?>>()
            else -> emptyList()
        }
    }

    /**
     * 把剪贴板中的节点导入 config.yaml。
     * - 追加到 proxies（同名则覆盖）
     * - 加入所有 select 类型的 proxy-group，使其可见且可选择
     * 返回导入成功的节点名列表；失败返回 Result.failure。
     */
    @Suppress("UNCHECKED_CAST")
    fun importProxy(configFile: File, clip: String): Result<List<String>> {
        return try {
            if (!configFile.exists()) {
                return Result.failure(IllegalStateException("配置文件不存在，请先导入订阅"))
            }
            val incoming = parseClip(clip)
            val valid = incoming.filter { it["name"] != null && it["type"] != null }
            if (valid.isEmpty()) {
                return Result.failure(IllegalArgumentException("剪贴板内容不是有效的节点配置"))
            }

            val root = (loader().load<Map<String, Any?>>(configFile.readText())
                ?: linkedMapOf()).toMutableMap()

            val proxies = (root["proxies"] as? List<Map<String, Any?>>)?.toMutableList()
                ?: mutableListOf()

            val importedNames = mutableListOf<String>()
            for (p in valid) {
                val pName = p["name"].toString()
                val idx = proxies.indexOfFirst { it["name"]?.toString() == pName }
                if (idx >= 0) proxies[idx] = p else proxies.add(p)
                importedNames.add(pName)
            }
            root["proxies"] = proxies

            // 加入 select 类型分组
            val groups = (root["proxy-groups"] as? List<Map<String, Any?>>)
                ?.map { it.toMutableMap() }?.toMutableList()
            if (groups != null) {
                for (g in groups) {
                    if (g["type"]?.toString() == "select") {
                        val gp = (g["proxies"] as? List<Any?>)?.map { it.toString() }?.toMutableList()
                            ?: mutableListOf()
                        for (n in importedNames) if (!gp.contains(n)) gp.add(n)
                        g["proxies"] = gp
                    }
                }
                root["proxy-groups"] = groups
            }

            configFile.writeText(blockDumper().dump(root))
            Result.success(importedNames)
        } catch (e: Exception) {
            Log.e(TAG, "importProxy failed", e)
            Result.failure(e)
        }
    }

    /**
     * 从 config.yaml 删除指定名称的节点，并移除各 proxy-group 中对它的引用。
     * 返回 true 表示确有删除；false 表示未找到该节点。
     */
    @Suppress("UNCHECKED_CAST")
    fun deleteProxy(configFile: File, name: String): Result<Boolean> {
        return try {
            if (!configFile.exists()) {
                return Result.failure(IllegalStateException("配置文件不存在"))
            }
            val root = (loader().load<Map<String, Any?>>(configFile.readText())
                ?: linkedMapOf()).toMutableMap()

            val proxies = (root["proxies"] as? List<Map<String, Any?>>)?.toMutableList()
                ?: mutableListOf()
            val removed = proxies.removeAll { it["name"]?.toString() == name }
            if (!removed) return Result.success(false)
            root["proxies"] = proxies

            // 移除各分组里对该节点的引用
            val groups = (root["proxy-groups"] as? List<Map<String, Any?>>)
                ?.map { it.toMutableMap() }?.toMutableList()
            if (groups != null) {
                for (g in groups) {
                    val gp = (g["proxies"] as? List<Any?>)?.map { it.toString() }?.toMutableList()
                    if (gp != null && gp.remove(name)) {
                        g["proxies"] = gp
                    }
                }
                root["proxy-groups"] = groups
            }

            configFile.writeText(blockDumper().dump(root))
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "deleteProxy failed", e)
            Result.failure(e)
        }
    }
}
