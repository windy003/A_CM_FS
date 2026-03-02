package com.example.clashmeta.ui.apps

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.clashmeta.data.AppInfo
import com.example.clashmeta.data.AppProxyConfig
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var config by remember { mutableStateOf(AppProxyConfig()) }
    var isLoading by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showModeDialog by remember { mutableStateOf(false) }

    // 加载应用列表和配置
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            config = AppProxyManager.loadConfig()
            apps = AppProxyManager.getInstalledApps(context)
        }
        isLoading = false
    }

    // 保存配置
    fun saveConfig() {
        scope.launch {
            withContext(Dispatchers.IO) {
                AppProxyManager.saveConfig(config)
            }
            Toast.makeText(context, "已保存，重启 VPN 生效", Toast.LENGTH_SHORT).show()
        }
    }

    // 切换应用选中状态
    fun toggleApp(packageName: String) {
        config = config.copy(
            selectedApps = config.selectedApps.toMutableSet().apply {
                if (contains(packageName)) {
                    remove(packageName)
                } else {
                    add(packageName)
                }
            }
        )
        saveConfig()
    }

    // 过滤并排序应用列表（选中的置顶）
    val filteredApps = apps
        .filter { app ->
            (showSystemApps || !app.isSystemApp) &&
            (searchQuery.isEmpty() ||
             app.appName.contains(searchQuery, ignoreCase = true) ||
             app.packageName.contains(searchQuery, ignoreCase = true))
        }
        .sortedWith(compareBy(
            { !config.selectedApps.contains(it.packageName) }, // 选中的排在前面
            { it.isSystemApp }, // 非系统应用排在前面
            { it.appName.lowercase() } // 按名称排序
        ))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分应用代理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 显示/隐藏系统应用
                    IconButton(onClick = { showSystemApps = !showSystemApps }) {
                        Icon(
                            if (showSystemApps) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showSystemApps) "隐藏系统应用" else "显示系统应用"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 代理模式选择
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { showModeDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "代理模式",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (config.mode) {
                                ProxyMode.PROXY_ALL -> "代理所有应用"
                                ProxyMode.BYPASS_SELECTED -> "绕过选中的应用（${config.selectedApps.size}个）"
                                ProxyMode.ONLY_SELECTED -> "仅代理选中的应用（${config.selectedApps.size}个）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null
                    )
                }
            }

            // 搜索框和应用列表（仅在非代理所有模式下显示）
            if (config.mode != ProxyMode.PROXY_ALL) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索应用") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 应用列表
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredApps) { app ->
                            AppItem(
                                app = app,
                                isSelected = config.selectedApps.contains(app.packageName),
                                onClick = { toggleApp(app.packageName) }
                            )
                        }
                    }
                }
            } else {
                // 代理所有应用模式
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "当前模式：代理所有应用",
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "所有应用流量都会经过代理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    // 代理模式选择对话框
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择代理模式") },
            text = {
                Column {
                    ProxyMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    config = config.copy(mode = mode)
                                    saveConfig()
                                    showModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = config.mode == mode,
                                onClick = {
                                    config = config.copy(mode = mode)
                                    saveConfig()
                                    showModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = when (mode) {
                                        ProxyMode.PROXY_ALL -> "代理所有应用"
                                        ProxyMode.BYPASS_SELECTED -> "绕过选中的应用"
                                        ProxyMode.ONLY_SELECTED -> "仅代理选中的应用"
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = when (mode) {
                                        ProxyMode.PROXY_ALL -> "所有应用都走代理"
                                        ProxyMode.BYPASS_SELECTED -> "选中的应用直连，其他走代理"
                                        ProxyMode.ONLY_SELECTED -> "只有选中的应用走代理"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AppItem(
    app: AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            icon = AppProxyManager.getAppIcon(context, app.packageName)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                icon?.let {
                    Image(
                        bitmap = it.toBitmap(40, 40).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } ?: Icon(
                    Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() }
            )
        }
    }
}
