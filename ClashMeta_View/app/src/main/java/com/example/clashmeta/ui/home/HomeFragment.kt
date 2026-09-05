package com.example.clashmeta.ui.home

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.clashmeta.MainActivity
import com.example.clashmeta.databinding.FragmentHomeBinding
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.data.ProxySelectionManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var refreshJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textDevice.text = buildDeviceInfo()
        // 长按错误信息复制，方便反馈
        binding.textError.setOnLongClickListener {
            val text = binding.textError.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                val cm = requireContext()
                    .getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
                android.widget.Toast
                    .makeText(requireContext(), "已复制错误信息", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
            true
        }
        binding.textTile.text = buildTileConfigInfo()

        binding.btnConnect.setOnClickListener {
            val connected = ClashVpnService.isVpnRunning(requireContext())
            val activity = activity as? MainActivity ?: return@setOnClickListener
            if (connected) {
                activity.stopVpnService()
            } else {
                activity.requestVpnPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 定时刷新状态（对应 Compose 的 LaunchedEffect + delay(1000)）
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateUi()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun updateUi() {
        val ctx = context ?: return
        val isConnected = ClashVpnService.isVpnRunning(ctx)
        val selectedProxy = ProxySelectionManager.getSelectedProxy(ctx)

        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val outline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
        val surfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceVariant)
        val onPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        // 连接按钮颜色
        binding.btnConnect.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (isConnected) primary else surfaceVariant)
        binding.iconPower.imageTintList =
            android.content.res.ColorStateList.valueOf(if (isConnected) onPrimary else onSurfaceVariant)

        // 状态文本
        binding.textStatus.text = if (isConnected) "已连接" else "未连接"
        binding.textStatus.setTextColor(if (isConnected) primary else outline)

        // 当前节点
        if (isConnected && !selectedProxy.isNullOrEmpty()) {
            binding.textProxy.visibility = View.VISIBLE
            binding.textProxy.text = "节点: $selectedProxy"
        } else {
            binding.textProxy.visibility = View.GONE
        }

        // 启动失败原因：开关自己弹回去时把真实原因显示出来
        val lastError = ClashVpnService.getLastError(ctx)
        if (!isConnected && !lastError.isNullOrEmpty()) {
            binding.textError.visibility = View.VISIBLE
            binding.textError.text = "启动失败: $lastError"
        } else {
            binding.textError.visibility = View.GONE
        }
    }

    private fun buildDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val modelNormalized = model.lowercase().replace("-", "")
        val isLGWing = manufacturer.lowercase().contains("lg") &&
                (modelNormalized.contains("wing") || modelNormalized.contains("lmf100"))
        return buildString {
            append("当前设备: $manufacturer $model")
            if (isLGWing) append(" (LG Wing)")
            append("\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("\n数据目录: ${com.example.clashmeta.ClashMetaApp.instance.getClashDir().absolutePath}")
        }
    }

    private fun buildTileConfigInfo(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase().replace("-", "")
        val isLGWing = manufacturer.contains("lg") &&
                (model.contains("wing") || model.contains("lmf100"))
        val isSamsung = manufacturer.contains("samsung")
        val modeLabel = when {
            isLGWing -> "反转模式 (LG Wing)"
            isSamsung -> "反转模式 (Samsung)"
            else -> "正常模式"
        }
        return "磁贴状态配置: $modeLabel"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
