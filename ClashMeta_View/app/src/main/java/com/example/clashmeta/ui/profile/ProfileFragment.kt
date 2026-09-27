package com.example.clashmeta.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.databinding.DialogSubscriptionBinding
import com.example.clashmeta.databinding.FragmentProfileBinding
import com.example.clashmeta.data.Subscription
import com.example.clashmeta.data.SubscriptionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SubscriptionAdapter

    private var subscriptions: List<Subscription> = emptyList()
    private var activeConfigId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SubscriptionAdapter(
            onActivate = { setAsActiveConfig(it) },
            onUpdate = { updateSubscription(it) },
            onMore = { anchor, sub -> showMoreMenu(anchor, sub) }
        )
        binding.recyclerProfile.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProfile.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == com.example.clashmeta.R.id.action_add) {
                showAddDialog()
                true
            } else false
        }
        binding.btnAddEmpty.setOnClickListener { showAddDialog() }

        loadSubscriptions()
    }

    private fun loadSubscriptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val (subs, activeId) = withContext(Dispatchers.IO) {
                val list = SubscriptionManager.loadSubscriptions(ctx)
                val activeFile = File(ClashMetaApp.instance.getClashDir(), "active_config.txt")
                val id = if (activeFile.exists()) activeFile.readText().trim() else null
                list to id
            }
            subscriptions = subs
            activeConfigId = activeId
            render()
        }
    }

    private fun render() {
        if (_binding == null) return
        if (subscriptions.isEmpty()) {
            binding.recyclerProfile.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
        } else {
            binding.recyclerProfile.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
            adapter.submit(subscriptions, activeConfigId)
        }
    }

    /**
     * 订阅请求的 User-Agent。
     *
     * 机场普遍按 UA 决定「给哪份配置」：认不出或判定版本过旧时，不会报错，而是回一份**占位配置**
     * ——节点全指向 127.0.0.1:1，名字拼成「当前客户端太旧 / 请升级客户端到最新版本」之类的提示
     * （实测 sub.boost1.shop 就是这样）。表现为「订阅成功但一个能用的节点都没有」，极易误判成 App 的 bug。
     *
     * 所以这里报 CMFA 的真实 UA 格式与一个够新的版本号；同时它也告诉机场本客户端是 Meta 内核，
     * 能收到 anytls / hysteria2 / vless 这些新协议的节点（本核心已支持，见
     * clash-core/adapter/outbound/）。改动这个值前先用 curl 对比一下拿到的节点数量。
     */
    private val SUBSCRIPTION_UA = "ClashMetaForAndroid/2.11.15.Meta"

    private suspend fun downloadSubscription(url: String): String {
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", SUBSCRIPTION_UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.inputStream.bufferedReader().readText()
        }
    }

    private fun setAsActiveConfig(subscription: Subscription) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            try {
                val configFile = ClashMetaApp.instance.getConfigFile()
                withContext(Dispatchers.IO) {
                    val subFile = File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                    subFile.copyTo(configFile, overwrite = true)
                    File(ClashMetaApp.instance.getClashDir(), "active_config.txt")
                        .writeText(subscription.id)
                    // 切换配置后重新注入手动节点 / 局域网代理 / 分流规则
                    com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                }
                activeConfigId = subscription.id
                adapter.submit(subscriptions, activeConfigId)
                val reload = reloadCore(configFile)
                reconcileSelection(ctx)
                // 内核加载失败多半是订阅本身不是合法的 clash 配置，必须如实说出来：
                // 静默提示「切换成功」会让用户在后面 VPN 起不来时完全无从排查
                val err = reload.exceptionOrNull()
                val msg = when {
                    err != null -> "已切换但内核加载失败: ${err.message}"
                    reload.getOrDefault(false) -> "已切换到: ${subscription.name}"
                    else -> "已切换到: ${subscription.name}（开启 VPN 后生效）"
                }
                Toast.makeText(ctx, msg, if (err != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 让内核用上磁盘上的新配置。返回 true 表示内核已重载（即当下就生效）。
     *
     * 两个容易踩的点：
     *  - 必须先 setConfig：C.Path.Config() 默认是相对路径 "config.yaml"，本进程没启动过 VPN
     *    时 reloadConfig 会因为找不到文件直接抛异常（见 clash-core/constant/path.go）。
     *  - 重载后要断掉旧连接：否则已建立的会话继续走旧订阅的节点，看着像「切了没用」。
     */
    private suspend fun reloadCore(configFile: File): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Mobile.setConfig(configFile.absolutePath)
            if (!Mobile.isRunning()) return@withContext Result.success(false)
            Mobile.reloadConfig()
            Mobile.closeAllConnections()
            Result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("ProfileFragment", "reloadCore failed", e)
            Result.failure(e)
        }
    }

    /**
     * 订阅换了之后原来选中的节点可能已经不存在：内核会静默落回代理组的第一个节点，
     * 而本地记录和通知栏还显示旧节点名。这里对齐三方——节点还在就重新选上，
     * 不在就清掉记录并让通知栏别再显示一个不存在的节点。
     */
    private suspend fun reconcileSelection(ctx: android.content.Context) {
        val saved = com.example.clashmeta.data.ProxySelectionManager.getSelectedProxy(ctx)
        if (saved.isNullOrEmpty()) return
        val group = com.example.clashmeta.data.ProxySelectionManager.getProxyGroup(ctx)
        val stillThere = withContext(Dispatchers.IO) {
            try {
                if (!Mobile.isRunning()) return@withContext true
                val json = Mobile.getProxies() ?: ""
                if (!json.contains("\"$saved\"")) return@withContext false
                try {
                    Mobile.selectProxy(group, saved)
                } catch (e: Exception) {
                    try { Mobile.selectProxy("GLOBAL", saved) } catch (e2: Exception) {
                        android.util.Log.w("ProfileFragment", "reselect $saved failed", e2)
                    }
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("ProfileFragment", "reconcileSelection failed", e)
                true
            }
        }
        if (!stillThere) {
            com.example.clashmeta.data.ProxySelectionManager.clearSelection(ctx)
        }
        if (com.example.clashmeta.core.ClashVpnService.isVpnRunning(ctx)) {
            val intent = android.content.Intent(ctx, com.example.clashmeta.core.ClashVpnService::class.java).apply {
                action = com.example.clashmeta.core.ClashVpnService.ACTION_UPDATE_NOTIFICATION
                if (stillThere) {
                    putExtra(com.example.clashmeta.core.ClashVpnService.EXTRA_PROXY_NAME, saved)
                }
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("ProfileFragment", "update notification failed", e)
            }
        }
    }

    private fun updateSubscription(subscription: Subscription) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            adapter.updatingId = subscription.id
            adapter.notifyDataSetChanged()
            try {
                val content = downloadSubscription(subscription.url)
                withContext(Dispatchers.IO) {
                    File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                        .writeText(content)
                }
                subscription.updatedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    SubscriptionManager.updateSubscription(ctx, subscription)
                }
                subscriptions = withContext(Dispatchers.IO) {
                    SubscriptionManager.loadSubscriptions(ctx)
                }

                if (activeConfigId == subscription.id) {
                    val configFile = ClashMetaApp.instance.getConfigFile()
                    withContext(Dispatchers.IO) {
                        File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                            .copyTo(configFile, overwrite = true)
                        // 更新配置后重新注入手动节点 / 局域网代理 / 分流规则
                        com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                    }
                    reloadCore(configFile)
                    reconcileSelection(ctx)
                }
                Toast.makeText(ctx, "更新成功: ${subscription.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                adapter.updatingId = null
                render()
            }
        }
    }

    private fun deleteSubscription(subscription: Subscription) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    File(ClashMetaApp.instance.getClashDir(), subscription.fileName).delete()
                    SubscriptionManager.deleteSubscription(ctx, subscription.id)
                }
                subscriptions = withContext(Dispatchers.IO) {
                    SubscriptionManager.loadSubscriptions(ctx)
                }
                if (activeConfigId == subscription.id) {
                    activeConfigId = null
                    withContext(Dispatchers.IO) {
                        File(ClashMetaApp.instance.getClashDir(), "active_config.txt").delete()
                    }
                }
                Toast.makeText(ctx, "已删除: ${subscription.name}", Toast.LENGTH_SHORT).show()
                render()
            } catch (e: Exception) {
                Toast.makeText(ctx, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMoreMenu(anchor: View, subscription: Subscription) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "编辑")
        popup.menu.add(0, 2, 1, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showEditDialog(subscription); true }
                2 -> { confirmDelete(subscription); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(subscription: Subscription) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除「${subscription.name}」吗？")
            .setPositiveButton("删除") { _, _ -> deleteSubscription(subscription) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddDialog() {
        val dialogBinding = DialogSubscriptionBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加订阅")
            .setView(dialogBinding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val name = dialogBinding.editName.text?.toString()?.trim().orEmpty()
                val url = dialogBinding.editUrl.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    dialogBinding.layoutUrl.error = "请输入订阅地址"
                    return@setOnClickListener
                }
                val finalName = name.ifBlank { "订阅 ${System.currentTimeMillis() % 10000}" }
                addSubscription(finalName, url, dialog)
            }
        }
        dialog.show()
    }

    private fun addSubscription(name: String, url: String, dialog: androidx.appcompat.app.AlertDialog) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            try {
                val content = downloadSubscription(url)
                val id = UUID.randomUUID().toString()
                val fileName = "sub_${id.take(8)}.yaml"
                withContext(Dispatchers.IO) {
                    File(ClashMetaApp.instance.getClashDir(), fileName).writeText(content)
                }
                val subscription = Subscription(id = id, name = name, url = url, fileName = fileName)
                withContext(Dispatchers.IO) {
                    SubscriptionManager.addSubscription(ctx, subscription)
                }
                subscriptions = withContext(Dispatchers.IO) {
                    SubscriptionManager.loadSubscriptions(ctx)
                }
                if (subscriptions.size == 1) {
                    setAsActiveConfig(subscription)
                }
                Toast.makeText(ctx, "添加成功: $name", Toast.LENGTH_SHORT).show()
                render()
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(ctx, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditDialog(subscription: Subscription) {
        val dialogBinding = DialogSubscriptionBinding.inflate(layoutInflater)
        dialogBinding.editName.setText(subscription.name)
        dialogBinding.editUrl.setText(subscription.url)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑订阅")
            .setView(dialogBinding.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val name = dialogBinding.editName.text?.toString()?.trim().orEmpty()
                val url = dialogBinding.editUrl.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || url.isBlank()) return@setOnClickListener
                viewLifecycleOwner.lifecycleScope.launch {
                    val ctx = context ?: return@launch
                    subscription.name = name
                    subscription.url = url
                    withContext(Dispatchers.IO) {
                        SubscriptionManager.updateSubscription(ctx, subscription)
                    }
                    subscriptions = withContext(Dispatchers.IO) {
                        SubscriptionManager.loadSubscriptions(ctx)
                    }
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                    render()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
