package com.example.clashmeta.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.R
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ChainRouteManager
import com.example.clashmeta.data.LanProxyManager
import com.example.clashmeta.data.ProxyMode
import com.example.clashmeta.databinding.FragmentSettingsBinding
import com.example.clashmeta.ui.apps.AppsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textDataPath.text = ClashMetaApp.instance.getClashDir().absolutePath

        binding.cardApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java))
        }

        // 局域网代理开关
        binding.switchLan.isChecked = LanProxyManager.isEnabled(requireContext())
        binding.editPort.setText(LanProxyManager.getPort(requireContext()).toString())
        updateLanStatus()
        binding.switchLan.setOnCheckedChangeListener { _, isChecked ->
            onLanToggled(isChecked)
        }
        binding.btnApplyPort.setOnClickListener { applyPort() }

        setupChainRoute()
    }

    // ------------------------------------------------ 全部流量走自己的 VPS（链式）

    /** 前置跳下拉里「不套机场、手机直连 VPS」那一项的显示文案，对应存储值是空串。 */
    private val chainNoDialer = "不使用（手机直连 VPS）"

    private fun setupChainRoute() {
        val ctx = requireContext()

        binding.switchChain.isChecked = ChainRouteManager.isEnabled(ctx)
        binding.layoutChainDetail.visibility =
            if (binding.switchChain.isChecked) View.VISIBLE else View.GONE
        updateChainStatus()
        loadChainOptions()

        binding.switchChain.setOnCheckedChangeListener { _, isChecked ->
            ChainRouteManager.setEnabled(ctx, isChecked)
            binding.layoutChainDetail.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) loadChainOptions()
            updateChainStatus()
            applyChainRoute(if (isChecked) "已开启" else "已关闭")
        }
        binding.btnChainSave.setOnClickListener { saveChainRoute() }
    }

    /**
     * 从 config.yaml 读出节点名和代理组名填进两个下拉。
     * 每次展开都重新读——订阅更新后节点和组名都可能变，缓存住会让用户选到已经不存在的名字。
     */
    private fun loadChainOptions() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val (proxies, groups) = withContext(Dispatchers.IO) {
                val file = ClashMetaApp.instance.getConfigFile()
                if (!file.exists()) return@withContext emptyList<String>() to emptyList<String>()
                val text = file.readText()
                ChainRouteManager.listProxyNames(text) to ChainRouteManager.listGroupNames(text)
            }
            if (_binding == null) return@launch

            binding.dropdownChainExit.setAdapter(
                ArrayAdapter(ctx, R.layout.item_dropdown, proxies)
            )
            binding.dropdownChainDialer.setAdapter(
                ArrayAdapter(ctx, R.layout.item_dropdown, listOf(chainNoDialer) + groups)
            )

            // 回显已保存的选择。setText 第二参数必须传 false，否则 AutoCompleteTextView
            // 会把它当成输入并立刻弹出下拉过滤列表。
            val savedExit = ChainRouteManager.getExit(ctx)
            binding.dropdownChainExit.setText(savedExit, false)
            val savedDialer = ChainRouteManager.getDialer(ctx)
            binding.dropdownChainDialer.setText(
                if (savedDialer.isEmpty()) chainNoDialer else savedDialer, false
            )

            binding.layoutChainExit.error =
                if (proxies.isEmpty()) "还没有节点，请先导入订阅或粘贴 VPS 节点" else null
            // 存着的名字在订阅更新后可能已经不存在了，直接提示而不是等注入时静默跳过
            if (savedExit.isNotEmpty() && !proxies.contains(savedExit)) {
                binding.layoutChainExit.error = "节点「$savedExit」已不存在，请重新选择"
            }
            if (savedDialer.isNotEmpty() && !groups.contains(savedDialer)) {
                binding.layoutChainDialer.error = "组「$savedDialer」已不存在，请重新选择"
            } else {
                binding.layoutChainDialer.error = null
            }
        }
    }

    private fun saveChainRoute() {
        val ctx = context ?: return
        val exit = binding.dropdownChainExit.text?.toString()?.trim().orEmpty()
        val dialerRaw = binding.dropdownChainDialer.text?.toString()?.trim().orEmpty()
        val dialer = if (dialerRaw == chainNoDialer) "" else dialerRaw

        if (exit.isEmpty()) {
            binding.layoutChainExit.error = "请选择出口节点"
            return
        }
        binding.layoutChainExit.error = null

        ChainRouteManager.save(ctx, exit, dialer)
        updateChainStatus()
        applyChainRoute("已保存")
    }

    /** 写回 config.yaml 并在 VPN 运行时热重载，与局域网开关那边同一套流程。 */
    private fun applyChainRoute(prefix: String) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LanProxyManager.applyToConfigFile()
                if (ClashVpnService.isVpnRunning(ctx)) {
                    try {
                        Mobile.reloadConfig()
                    } catch (e: Exception) {
                        // VPN 可能没运行
                    }
                }
            }
            if (_binding == null) return@launch
            val tip = if (ClashVpnService.isVpnRunning(ctx)) {
                "$prefix，若未生效请重启 VPN"
            } else {
                "$prefix，启动 VPN 后生效"
            }
            Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChainStatus() {
        if (_binding == null) return
        val ctx = requireContext()
        if (!ChainRouteManager.isEnabled(ctx)) {
            binding.textChainStatus.text = "关闭。开启后所有流量都从自己的 VPS 出去"
            return
        }
        val exit = ChainRouteManager.getExit(ctx)
        val dialer = ChainRouteManager.getDialer(ctx)
        binding.textChainStatus.text = when {
            exit.isEmpty() -> "已开启，但还没选出口节点"
            dialer.isEmpty() -> "全部流量 → $exit（直连，单跳）"
            else -> "全部流量 → $dialer → $exit"
        }
    }

    private fun applyPort() {
        val ctx = context ?: return
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.layoutPort.error = "端口需在 1-65535"
            return
        }
        binding.layoutPort.error = null
        LanProxyManager.setPort(ctx, port)
        updateLanStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LanProxyManager.applyToConfigFile()
                if (ClashVpnService.isVpnRunning(ctx)) {
                    try {
                        Mobile.reloadConfig()
                    } catch (e: Exception) {
                        // VPN 可能没运行
                    }
                }
            }
            val tip = if (ClashVpnService.isVpnRunning(ctx)) {
                "端口已设为 $port，若未生效请重启 VPN"
            } else {
                "端口已设为 $port，启动 VPN 后生效"
            }
            Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAppProxyInfo()
        updateLanStatus()
        updateChainStatus()
        // 用户可能刚去节点页导入了 VPS 节点或刷新了订阅，回来要能选到新名字
        if (binding.switchChain.isChecked) loadChainOptions()
    }

    private fun onLanToggled(enabled: Boolean) {
        val ctx = context ?: return
        LanProxyManager.setEnabled(ctx, enabled)
        updateLanStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            // 把 allow-lan / bind-address 写入 config.yaml，并在 VPN 运行时热重载
            withContext(Dispatchers.IO) {
                LanProxyManager.applyToConfigFile()
                if (ClashVpnService.isVpnRunning(ctx)) {
                    try {
                        Mobile.reloadConfig()
                    } catch (e: Exception) {
                        // VPN 可能没运行
                    }
                }
            }
            val tip = if (enabled) {
                if (ClashVpnService.isVpnRunning(ctx)) "已开启，局域网设备现在可连接" else "已开启，启动 VPN 后生效"
            } else {
                "已关闭"
            }
            Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLanStatus() {
        if (_binding == null) return
        val port = LanProxyManager.getPort(requireContext())
        if (LanProxyManager.isEnabled(requireContext())) {
            val ip = LanProxyManager.getLanIp()
            binding.textLanStatus.text = if (ip != null) {
                "其他设备代理地址：$ip:$port"
            } else {
                "已开启（未获取到局域网 IP，请连接 WiFi）"
            }
        } else {
            binding.textLanStatus.text = "关闭。开启后其他设备可经本机上网"
        }
    }

    private fun loadAppProxyInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { AppProxyManager.loadConfig() }
            if (_binding == null) return@launch
            binding.textAppProxyInfo.text = when (config.mode) {
                ProxyMode.PROXY_ALL -> "代理所有应用"
                ProxyMode.BYPASS_SELECTED -> "绕过 ${config.selectedApps.size} 个应用"
                ProxyMode.ONLY_SELECTED -> "仅代理 ${config.selectedApps.size} 个应用"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
