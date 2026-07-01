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
import kotlinx.coroutines.launch
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
                    testAllDelays(filteredProxies().map { it.name })
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
                val r = ProxyClipboard.importProxy(configFile, clip)
                // 导入成功后让内核重新加载配置（预览态或 VPN 运行态都刷新）
                if (r.isSuccess && Mobile.isRunning()) {
                    try { Mobile.reloadConfig() } catch (e: Exception) {
                        Log.e("ProxyFragment", "reloadConfig after paste failed", e)
                    }
                }
                r
            }
            val c = context ?: return@launch
            result.onSuccess { names ->
                Toast.makeText(c, "已粘贴 ${names.size} 个节点", Toast.LENGTH_SHORT).show()
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
            val isVpnRunning = ClashVpnService.isVpnRunning(ctx)
            // 未开启 VPN 时也能预览节点：仅把配置加载进内核（不建立隧道）
            val coreReady = if (isVpnRunning) true
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

    private fun testAllDelays(proxyList: List<String>) {
        if (isTestingAll) return
        viewLifecycleOwner.lifecycleScope.launch {
            isTestingAll = true
            for (proxyName in proxyList) {
                try {
                    val d = withContext(Dispatchers.IO) {
                        Mobile.testDelay(proxyName, "https://www.gstatic.com/generate_204", 5000)
                    }
                    adapter.delayResults[proxyName] = d.toInt()
                } catch (e: Exception) {
                    adapter.delayResults[proxyName] = -1
                }
                adapter.notifyDataSetChanged()
            }
            isTestingAll = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
