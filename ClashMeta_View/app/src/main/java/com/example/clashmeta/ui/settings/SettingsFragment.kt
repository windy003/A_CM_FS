package com.example.clashmeta.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.data.AppProxyManager
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
