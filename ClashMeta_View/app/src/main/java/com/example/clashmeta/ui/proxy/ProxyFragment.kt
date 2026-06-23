package com.example.clashmeta.ui.proxy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.data.ProxySelectionManager
import com.example.clashmeta.databinding.FragmentProxyBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
            onTest = { testProxyDelay(it) }
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
                    isLoading = true
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loopJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                fetchProxies()
                delay(3000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        loopJob?.cancel()
        loopJob = null
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
            if (isVpnRunning) {
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
                errorMessage = "VPN 未运行，请先启动 VPN"
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
