package com.example.clashmeta.ui.apps

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.clashmeta.R
import com.example.clashmeta.data.AppInfo
import com.example.clashmeta.data.AppProxyConfig
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode
import com.example.clashmeta.databinding.ActivityAppsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsBinding
    private lateinit var adapter: AppsAdapter

    private var apps: List<AppInfo> = emptyList()
    private var config = AppProxyConfig()
    private var isLoading = true
    private var showSystemApps = false
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_toggle_system) {
                showSystemApps = !showSystemApps
                item.setIcon(
                    if (showSystemApps) R.drawable.ic_visibility else R.drawable.ic_visibility_off
                )
                render()
                true
            } else false
        }

        adapter = AppsAdapter(
            scope = lifecycleScope,
            isSelected = { config.selectedApps.contains(it) },
            onToggle = { toggleApp(it) }
        )
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        binding.cardMode.setOnClickListener { showModeDialog() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val (cfg, list) = withContext(Dispatchers.IO) {
                AppProxyManager.loadConfig() to AppProxyManager.getInstalledApps(this@AppsActivity)
            }
            config = cfg
            apps = list
            isLoading = false
            render()
        }
    }

    private fun saveConfig() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AppProxyManager.saveConfig(config) }
            Toast.makeText(this@AppsActivity, "已保存，重启 VPN 生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleApp(packageName: String) {
        val newSet = config.selectedApps.toMutableSet().apply {
            if (contains(packageName)) remove(packageName) else add(packageName)
        }
        config = config.copy(selectedApps = newSet)
        saveConfig()
        render()
    }

    private fun filteredApps(): List<AppInfo> {
        return apps
            .filter { app ->
                (showSystemApps || !app.isSystemApp) &&
                    (searchQuery.isEmpty() ||
                        app.appName.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true))
            }
            .sortedWith(
                compareBy(
                    { !config.selectedApps.contains(it.packageName) },
                    { it.isSystemApp },
                    { it.appName.lowercase() }
                )
            )
    }

    private fun render() {
        binding.textMode.text = when (config.mode) {
            ProxyMode.PROXY_ALL -> "代理所有应用"
            ProxyMode.BYPASS_SELECTED -> "绕过选中的应用（${config.selectedApps.size}个）"
            ProxyMode.ONLY_SELECTED -> "仅代理选中的应用（${config.selectedApps.size}个）"
        }

        if (config.mode == ProxyMode.PROXY_ALL) {
            // 代理所有应用：隐藏搜索和列表，显示提示
            binding.layoutSearch.visibility = android.view.View.GONE
            binding.recyclerApps.visibility = android.view.View.GONE
            binding.progressLoading.visibility = android.view.View.GONE
            binding.proxyAllView.visibility = android.view.View.VISIBLE
            return
        }

        binding.proxyAllView.visibility = android.view.View.GONE
        binding.layoutSearch.visibility = android.view.View.VISIBLE

        if (isLoading) {
            binding.progressLoading.visibility = android.view.View.VISIBLE
            binding.recyclerApps.visibility = android.view.View.GONE
        } else {
            binding.progressLoading.visibility = android.view.View.GONE
            binding.recyclerApps.visibility = android.view.View.VISIBLE
            adapter.submit(filteredApps())
        }
    }

    private fun showModeDialog() {
        val modes = ProxyMode.values()
        val labels = modes.map {
            when (it) {
                ProxyMode.PROXY_ALL -> "代理所有应用"
                ProxyMode.BYPASS_SELECTED -> "绕过选中的应用"
                ProxyMode.ONLY_SELECTED -> "仅代理选中的应用"
            }
        }.toTypedArray()
        val checked = modes.indexOf(config.mode)

        MaterialAlertDialogBuilder(this)
            .setTitle("选择代理模式")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                config = config.copy(mode = modes[which])
                saveConfig()
                render()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
