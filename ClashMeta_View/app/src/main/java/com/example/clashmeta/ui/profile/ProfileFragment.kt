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

    private suspend fun downloadSubscription(url: String): String {
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ClashMetaForAndroid/2.10.1")
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
                withContext(Dispatchers.IO) {
                    val subFile = File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                    val configFile = ClashMetaApp.instance.getConfigFile()
                    subFile.copyTo(configFile, overwrite = true)
                    File(ClashMetaApp.instance.getClashDir(), "active_config.txt")
                        .writeText(subscription.id)
                    // 切换配置后重新注入局域网代理设置
                    com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                }
                activeConfigId = subscription.id
                adapter.submit(subscriptions, activeConfigId)
                try {
                    withContext(Dispatchers.IO) { Mobile.reloadConfig() }
                    Toast.makeText(ctx, "已切换到: ${subscription.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "已切换配置，重启VPN生效", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    withContext(Dispatchers.IO) {
                        File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                            .copyTo(ClashMetaApp.instance.getConfigFile(), overwrite = true)
                        // 更新配置后重新注入局域网代理设置
                        com.example.clashmeta.data.LanProxyManager.applyToConfigFile()
                    }
                    try {
                        withContext(Dispatchers.IO) { Mobile.reloadConfig() }
                    } catch (e: Exception) {
                        // VPN 可能没运行
                    }
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
