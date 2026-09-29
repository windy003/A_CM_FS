package com.example.clashmeta

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.example.clashmeta.core.ClashVpnService
import com.example.clashmeta.databinding.ActivityMainBinding
import com.example.clashmeta.ui.home.HomeFragment
import com.example.clashmeta.ui.profile.ProfileFragment
import com.example.clashmeta.ui.proxy.ProxyFragment
import com.example.clashmeta.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var homeFragment: Fragment
    private lateinit var proxyFragment: Fragment
    private lateinit var profileFragment: Fragment
    private lateinit var settingsFragment: Fragment
    private lateinit var activeFragment: Fragment

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 请求存储权限（Android 11+ 需要管理所有文件权限）
        requestStoragePermission()

        setupFragments(savedInstanceState)
        setupBottomNav()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState != null) {
            // 进程被系统回收后重建：Fragment 已由 FragmentManager 自动恢复，只能按 tag 取回。
            // 如果这里再 add() 一份新实例，两个页面的 View 会同时叠在同一个容器里
            // （首页压在节点列表上）。
            homeFragment = fm.findFragmentByTag(TAG_HOME) ?: HomeFragment()
            proxyFragment = fm.findFragmentByTag(TAG_PROXY) ?: ProxyFragment()
            profileFragment = fm.findFragmentByTag(TAG_PROFILE) ?: ProfileFragment()
            settingsFragment = fm.findFragmentByTag(TAG_SETTINGS) ?: SettingsFragment()
        } else {
            homeFragment = HomeFragment()
            proxyFragment = ProxyFragment()
            profileFragment = ProfileFragment()
            settingsFragment = SettingsFragment()
        }

        // 把尚未在容器里的 Fragment 补齐；已恢复的不重复添加
        fm.beginTransaction().apply {
            addIfAbsent(this, settingsFragment, TAG_SETTINGS)
            addIfAbsent(this, profileFragment, TAG_PROFILE)
            addIfAbsent(this, proxyFragment, TAG_PROXY)
            addIfAbsent(this, homeFragment, TAG_HOME)
        }.commitNow()

        // 恢复后以“当前未被 hide 的那个”为准，保证与底部导航一致
        activeFragment = listOf(homeFragment, proxyFragment, profileFragment, settingsFragment)
            .firstOrNull { it.isAdded && !it.isHidden }
            ?: homeFragment

        fm.beginTransaction().apply {
            listOf(homeFragment, proxyFragment, profileFragment, settingsFragment).forEach {
                if (it === activeFragment) show(it) else hide(it)
            }
        }.commitNow()

        binding.bottomNav.selectedItemId = when (activeFragment) {
            proxyFragment -> R.id.nav_proxy
            profileFragment -> R.id.nav_profile
            settingsFragment -> R.id.nav_settings
            else -> R.id.nav_home
        }
    }

    private fun addIfAbsent(
        transaction: FragmentTransaction,
        fragment: Fragment,
        tag: String
    ) {
        if (!fragment.isAdded) {
            transaction.add(R.id.fragment_container, fragment, tag)
        }
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_PROXY = "proxy"
        const val TAG_PROFILE = "profile"
        const val TAG_SETTINGS = "settings"
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_home -> homeFragment
                R.id.nav_proxy -> proxyFragment
                R.id.nav_profile -> profileFragment
                R.id.nav_settings -> settingsFragment
                else -> return@setOnItemSelectedListener false
            }
            if (target !== activeFragment) {
                supportFragmentManager.beginTransaction()
                    .hide(activeFragment)
                    .show(target)
                    .commit()
                activeFragment = target
            }
            true
        }
    }

    // ---- 供 HomeFragment 调用的 VPN 控制 ----

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, ClashVpnService::class.java).apply {
            action = ClashVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun stopVpnService() {
        val intent = Intent(this, ClashVpnService::class.java).apply {
            action = ClashVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}
