package com.example.clashmeta.ui.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.data.ProxyClipboard
import com.example.clashmeta.data.ProxySelectionManager
import com.example.clashmeta.databinding.FragmentProxyBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mobile.Mobile

class ProxyFragment : Fragment() {

    private var _binding: FragmentProxyBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ProxyAdapter

    private var proxies: Map<String, ProxyInfo> = emptyMap()
    private var errorMessage: String? = null
    private var isLoading = true

    // 弹出菜单打开期间暂停自动刷新，避免列表重排导致 PopupMenu 位置乱跳
    private var activePopup: PopupMenu? = null
    private var isTestingAll = false

    private var loopJob: Job? = null

    // 刚粘贴导入的节点名：下次渲染后自动滚动到它，避免因列表按名称排序（emoji/中文
    // 排在末尾）而让用户以为“没导入”。
    private var pendingScrollTo: String? = null

    private val groupTypes = listOf(
        "Selector", "Direct", "Reject", "Compatible", "Pass",
        "URLTest", "Fallback", "LoadBalance"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProxyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProxyAdapter(
            onSelect = { selectProxy(it) },
            onTest = { testProxyDelay(it) },
            onMenu = { name, anchor -> showProxyMenu(name, anchor) }
        )
        binding.recyclerProxy.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProxy.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                com.example.clashmeta.R.id.action_test_all -> {
                    testAllDelays(filteredProxies())
                    true
                }
                com.example.clashmeta.R.id.action_refresh -> {
                    isLoading = proxies.isEmpty()
                    render()
                    refresh()
                    true
                }
                com.example.clashmeta.R.id.action_paste_proxy -> {
                    pasteProxyFromClipboard()
                    true
                }
                else -> false
            }
        }
    }

    /** 节点右侧 3 点菜单：复制分享链接 / 删除节点 */
    private fun showProxyMenu(name: String, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val idCopy = 1
        val idDelete = 2
        popup.menu.add(0, idCopy, 0, "复制分享链接")
        popup.menu.add(0, idDelete, 1, "删除节点")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                idCopy -> copyProxyToClipboard(name)
                idDelete -> confirmDeleteProxy(name)
            }
            true
        }
        popup.setOnDismissListener { if (activePopup === it) activePopup = null }
        activePopup = popup
        popup.show()
    }

    private fun confirmDeleteProxy(name: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("删除节点")
            .setMessage("确定要删除节点“$name”吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteProxy(name) }
            .show()
    }

    private fun deleteProxy(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val configFile = ClashMetaApp.instance.getConfigFile()
                val r = ProxyClipboard.deleteProxy(configFile, name)
                if (r.getOrDefault(false) && Mobile.isRunning()) {
                    try { Mobile.reloadConfig() } catch (e: Exception) {
                        Log.e("ProxyFragment", "reloadConfig after delete failed", e)
                    }
                }
                r
            }
            val c = context ?: return@launch
            result.onSuccess { deleted ->
                if (deleted) {
                    // 若删除的是当前选中节点，清掉本地选中态
                    if (adapter.selectedProxy == name) adapter.selectedProxy = null
                    Toast.makeText(c, "已删除节点", Toast.LENGTH_SHORT).show()
                    fetchProxies()
                } else {
                    Toast.makeText(c, "未找到该节点", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(c, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyProxyToClipboard(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val yaml = withContext(Dispatchers.IO) {
                ProxyClipboard.exportProxy(ClashMetaApp.instance.getConfigFile(), name)
            }
            val ctx = context ?: return@launch
            if (yaml.isNullOrBlank()) {
                Toast.makeText(ctx, "复制失败：未找到该节点的配置", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("proxy", yaml))
            Toast.makeText(ctx, "已复制节点信息", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteProxyFromClipboard() {
        val ctx = context ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (clip.isNullOrBlank()) {
            Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val configFile = ClashMetaApp.instance.getConfigFile()
                // 备份导入前的配置：若内核加载失败需回滚，避免把坏配置留在磁盘上，
                // 否则后续预览刷新/重启都会解析失败，导致所有节点都加载不出来。
                val backup = if (configFile.exists()) configFile.readText() else null

                val r = ProxyClipboard.importProxy(configFile, clip)
                if (r.isFailure) return@withContext r
                val importedNames = r.getOrDefault(emptyList())

                // 真正把新配置加载进内核，以此确认粘贴的节点确实可用。
                // reloadConfig()/startWithPath() 在解析失败时会抛异常（如某个节点
                // 字段非法会导致整份配置解析失败），此处必须捕获并如实反馈，
                // 不能像原来那样吞掉错误却仍提示“导入成功”。
                val loadError: Throwable? = try {
                    Mobile.setConfig(configFile.absolutePath)
                    if (Mobile.isRunning()) Mobile.reloadConfig()
                    else Mobile.startWithPath(configFile.absolutePath)
                    null
                } catch (e: Exception) {
                    Log.e("ProxyFragment", "reloadConfig after paste failed", e)
                    e
                }

                // reload 成功也要核实：从内核实际读回代理列表，确认粘贴的节点真的在里面。
                // 这样能区分“内核没加载成功”与“加载了但界面没刷新/被覆盖”等不同原因。
                val loadedIntoCore: Boolean = if (loadError == null) {
                    try {
                        val json = Mobile.getProxies() ?: ""
                        importedNames.all { name -> json.contains("\"${name}\"") }
                    } catch (e: Exception) { false }
                } else false

                if (loadError != null || !loadedIntoCore) {
                    // 回滚到导入前的配置，并让内核恢复到可用状态
                    if (backup != null) {
                        try {
                            configFile.writeText(backup)
                            if (Mobile.isRunning()) {
                                Mobile.setConfig(configFile.absolutePath)
                                Mobile.reloadConfig()
                            }
                        } catch (e: Exception) {
                            Log.e("ProxyFragment", "rollback config after failed paste failed", e)
                        }
                    }
                    val reason = loadError?.message ?: "内核已重载但节点未出现（配置可能被订阅覆盖或写入未生效）"
                    return@withContext Result.failure(IllegalArgumentException("节点未能真正导入：$reason"))
                }
                r
            }
            val c = context ?: return@launch
            result.onSuccess { names ->
                Toast.makeText(c, "已粘贴 ${names.size} 个节点", Toast.LENGTH_SHORT).show()
                // 记住刚导入的节点，渲染后滚动过去让用户立刻看到
                pendingScrollTo = names.firstOrNull()
                isLoading = proxies.isEmpty()
                fetchProxies()
            }.onFailure { e ->
                Toast.makeText(c, "粘贴失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入页面拉取一次；之后仅在用户操作（刷新/测试/选择/粘贴）时按需刷新，不再轮询
        if (!isHidden) refresh()
    }

    // 本页与其它 Tab 通过 show/hide 切换，切 Tab 不会触发 onResume，
    // 需在此处于“被显示”时刷新，以反映刚切换的订阅配置等变化。
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            activePopup?.dismiss()
            activePopup = null
            loopJob?.cancel()
        } else {
            refresh()
        }
    }

    /** 按需刷新代理列表 */
    private fun refresh() {
        loopJob?.cancel()
        loopJob = viewLifecycleOwner.lifecycleScope.launch {
            fetchProxies()
        }
    }

    override fun onPause() {
        super.onPause()
        loopJob?.cancel()
        loopJob = null
        activePopup?.dismiss()
        activePopup = null
    }

    private fun filteredProxies(): List<ProxyRow> {
        return proxies.entries
            .filter { it.value.type !in groupTypes }
            .map { ProxyRow(it.key, it.value) }
    }

    private suspend fun fetchProxies() {
        val ctx = context ?: return
        try {
            // 注意：不能用持久化的 isVpnRunning(ctx)（SharedPreferences）来判断内核是否已加载配置——
            // 该标志跨进程重启不会自动清零（例如上次进程被系统杀掉/崩溃时 VPN 恰好在跑），
            // 导致重启 App 后仍读到"运行中"，从而跳过下面的加载步骤，使代理列表一直为空。
            // VpnService 与 UI 同进程，原生层 Mobile.isRunning() 是本进程内的实时状态，不存在这个问题。
            val nativeRunning = withContext(Dispatchers.IO) { Mobile.isRunning() }
            // 未开启 VPN 时也能预览节点：仅把配置加载进内核（不建立隧道）
            val coreReady = if (nativeRunning) true
                else withContext(Dispatchers.IO) { ClashVpnService.ensureCoreLoadedForPreview(ctx) }
            if (coreReady) {
                val json = withContext(Dispatchers.IO) { Mobile.getProxies() }
                if (json.isNullOrBlank() || json == "null" || json == "{}") {
                    errorMessage = "代理列表为空，配置可能未正确加载"
                } else {
                    val type = object : TypeToken<Map<String, ProxyInfo>>() {}.type
                    val parsed: Map<String, ProxyInfo>? = Gson().fromJson(json, type)
                    proxies = parsed ?: emptyMap()
                    if (proxies.isNotEmpty()) errorMessage = null

                    val currentSelected = withContext(Dispatchers.IO) {
                        try {
                            Mobile.getSelectedProxy("🚀 节点选择").takeIf { it.isNotEmpty() }
                                ?: Mobile.getSelectedProxy("GLOBAL")
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (!currentSelected.isNullOrEmpty()) {
                        adapter.selectedProxy = currentSelected
                    }
                }
            } else {
                errorMessage = "未找到配置，请先导入订阅"
            }
        } catch (e: Exception) {
            Log.e("ProxyFragment", "Error fetching proxies", e)
            errorMessage = "获取代理失败: ${e.message}"
        }
        isLoading = false
        render()
    }

    private fun render() {
        if (_binding == null) return
        val list = filteredProxies()

        when {
            isLoading && proxies.isEmpty() -> {
                binding.progressLoading.visibility = View.VISIBLE
                binding.recyclerProxy.visibility = View.GONE
                binding.emptyView.visibility = View.GONE
            }
            list.isEmpty() -> {
                binding.progressLoading.visibility = View.GONE
                binding.recyclerProxy.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.textEmptyHint.text = errorMessage ?: "请先导入配置并启动 VPN"
            }
            else -> {
                binding.progressLoading.visibility = View.GONE
                binding.recyclerProxy.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                adapter.submit(list)

                // 刚粘贴导入的节点：滚动到它的位置，让用户立刻看到（列表按名称字节序
                // 排序，emoji/中文名会排到末尾，否则容易被误以为“没导入”）。
                pendingScrollTo?.let { target ->
                    val idx = list.indexOfFirst { it.name == target }
                    if (idx >= 0) {
                        binding.recyclerProxy.post {
                            binding.recyclerProxy.smoothScrollToPosition(idx)
                        }
                    }
                    pendingScrollTo = null
                }
            }
        }
    }

    private fun selectProxy(proxyName: String) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var usedGroup = "🚀 节点选择"
                withContext(Dispatchers.IO) {
                    try {
                        Mobile.selectProxy("🚀 节点选择", proxyName)
                    } catch (e: Exception) {
                        Mobile.selectProxy("GLOBAL", proxyName)
                        usedGroup = "GLOBAL"
                    }
                }
                adapter.selectedProxy = proxyName
                adapter.notifyDataSetChanged()
                ProxySelectionManager.saveSelectedProxy(ctx, proxyName, usedGroup)

                if (ClashVpnService.isVpnRunning(ctx)) {
                    val intent = Intent(ctx, ClashVpnService::class.java).apply {
                        action = ClashVpnService.ACTION_UPDATE_NOTIFICATION
                        putExtra(ClashVpnService.EXTRA_PROXY_NAME, proxyName)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent)
                    } else {
                        ctx.startService(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e("ProxyFragment", "Failed to select proxy: $proxyName", e)
            }
        }
    }

    private fun testProxyDelay(proxyName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.testingProxy = proxyName
            adapter.notifyDataSetChanged()
            try {
                val d = withContext(Dispatchers.IO) {
                    Mobile.testDelay(proxyName, "https://www.gstatic.com/generate_204", 5000)
                }
                adapter.delayResults[proxyName] = d.toInt()
            } catch (e: Exception) {
                adapter.delayResults[proxyName] = -1
            } finally {
                adapter.testingProxy = null
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun testAllDelays(proxyList: List<ProxyRow>) {
        if (isTestingAll) return
        // 跳过服务器地址为占位符 8.8.8.8 的节点（通常是流量/到期信息节点）
        val targets = proxyList.filter { it.info.server != PLACEHOLDER_SERVER }
        if (targets.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            isTestingAll = true
            // 限制并发数，避免一次性发起过多连接拖垮内核
            val semaphore = Semaphore(MAX_CONCURRENT_TESTS)
            coroutineScope {
                targets.map { row ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val name = row.name
                            val d = try {
                                Mobile.testDelay(name, "https://www.gstatic.com/generate_204", 5000).toInt()
                            } catch (e: Exception) {
                                -1
                            }
                            adapter.delayResults[name] = d
                            withContext(Dispatchers.Main) {
                                if (_binding != null) adapter.notifyDataSetChanged()
                            }
                        }
                    }
                }.awaitAll()
            }
            isTestingAll = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 占位节点的服务器地址（流量/到期等信息节点），测试延迟时跳过 */
        private const val PLACEHOLDER_SERVER = "8.8.8.8"
        /** 一键测速时的最大并发数 */
        private const val MAX_CONCURRENT_TESTS = 16
    }
}
