package com.example.clashmeta.ui.proxy

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import com.example.clashmeta.core.ClashVpnService

data class ProxyInfo(
    val name: String = "",
    val type: String = "",
    val alive: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen() {
    var proxies by remember { mutableStateOf<Map<String, ProxyInfo>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedProxy by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var delayResults by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var testingProxy by remember { mutableStateOf<String?>(null) }
    var isTestingAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 测试单个节点延迟
    fun testProxyDelay(proxyName: String) {
        scope.launch {
            testingProxy = proxyName
            try {
                val delay = withContext(Dispatchers.IO) {
                    Mobile.testDelay(proxyName, "https://www.gstatic.com/generate_204", 5000)
                }
                delayResults = delayResults + (proxyName to delay.toInt())
                Log.d("ProxyScreen", "Delay for $proxyName: ${delay}ms")
            } catch (e: Exception) {
                Log.e("ProxyScreen", "Failed to test delay for $proxyName", e)
                delayResults = delayResults + (proxyName to -1) // -1 表示超时或失败
            } finally {
                testingProxy = null
            }
        }
    }

    // 测试所有节点延迟
    fun testAllDelays(proxyList: List<String>) {
        scope.launch {
            isTestingAll = true
            for (proxyName in proxyList) {
                try {
                    val delay = withContext(Dispatchers.IO) {
                        Mobile.testDelay(proxyName, "https://www.gstatic.com/generate_204", 5000)
                    }
                    delayResults = delayResults + (proxyName to delay.toInt())
                } catch (e: Exception) {
                    delayResults = delayResults + (proxyName to -1)
                }
            }
            isTestingAll = false
        }
    }

    // 选择代理的函数
    fun selectProxy(proxyName: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        Mobile.selectProxy("🚀 节点选择", proxyName)
                        Log.d("ProxyScreen", "Selected proxy '$proxyName' in group '🚀 节点选择'")
                    } catch (e: Exception) {
                        Mobile.selectProxy("GLOBAL", proxyName)
                        Log.d("ProxyScreen", "Selected proxy '$proxyName' in group 'GLOBAL'")
                    }
                }
                selectedProxy = proxyName
            } catch (e: Exception) {
                Log.e("ProxyScreen", "Failed to select proxy: $proxyName", e)
                errorMessage = "切换节点失败: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val isVpnRunning = ClashVpnService.isRunning
                Log.d("ProxyScreen", "VPN running: $isVpnRunning")

                if (isVpnRunning) {
                    val json = withContext(Dispatchers.IO) {
                        Mobile.getProxies()
                    }
                    Log.d("ProxyScreen", "Proxies JSON: $json")

                    if (json.isNullOrBlank() || json == "null" || json == "{}") {
                        Log.w("ProxyScreen", "Empty proxies response")
                        errorMessage = "代理列表为空，配置可能未正确加载"
                    } else {
                        val type = object : TypeToken<Map<String, ProxyInfo>>() {}.type
                        val parsedProxies: Map<String, ProxyInfo>? = Gson().fromJson(json, type)
                        proxies = parsedProxies ?: emptyMap()
                        Log.d("ProxyScreen", "Proxies count: ${proxies.size}")
                        if (proxies.isNotEmpty()) {
                            errorMessage = null
                        }

                        // 获取当前选中的节点
                        val currentSelected = withContext(Dispatchers.IO) {
                            try {
                                Mobile.getSelectedProxy("🚀 节点选择").takeIf { it.isNotEmpty() }
                                    ?: Mobile.getSelectedProxy("GLOBAL")
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (!currentSelected.isNullOrEmpty()) {
                            selectedProxy = currentSelected
                            Log.d("ProxyScreen", "Current selected proxy: $currentSelected")
                        }
                    }
                } else {
                    Log.d("ProxyScreen", "VPN not running, skipping proxy fetch")
                    errorMessage = "VPN 未运行，请先启动 VPN"
                }
            } catch (e: Exception) {
                Log.e("ProxyScreen", "Error fetching proxies", e)
                errorMessage = "获取代理失败: ${e.message}"
            }
            isLoading = false
            delay(3000)
        }
    }

    // 过滤出实际的代理节点
    val filteredProxies = proxies.entries
        .filter { it.value.type !in listOf("Selector", "Direct", "Reject", "Compatible", "Pass", "URLTest", "Fallback", "LoadBalance") }
        .toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代理节点") },
                actions = {
                    // 测试全部按钮
                    if (filteredProxies.isNotEmpty()) {
                        IconButton(
                            onClick = { testAllDelays(filteredProxies.map { it.key }) },
                            enabled = !isTestingAll
                        ) {
                            if (isTestingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Speed, contentDescription = "测试全部")
                            }
                        }
                    }
                    IconButton(onClick = { isLoading = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading && proxies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredProxies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无代理节点",
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = errorMessage ?: "请先导入配置并启动 VPN",
                        color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredProxies) { (name, info) ->
                    ProxyItem(
                        name = name,
                        type = info.type,
                        isAlive = info.alive,
                        isSelected = selectedProxy == name,
                        delay = delayResults[name],
                        isTesting = testingProxy == name,
                        onClick = { selectProxy(name) },
                        onTestDelay = { testProxyDelay(name) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProxyItem(
    name: String,
    type: String,
    isAlive: Boolean,
    isSelected: Boolean,
    delay: Int?,
    isTesting: Boolean,
    onClick: () -> Unit,
    onTestDelay: () -> Unit
) {
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
            // 状态指示点
            Box(
                modifier = Modifier.size(8.dp)
            ) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = null,
                    tint = when {
                        delay != null && delay > 0 && delay < 300 -> Color(0xFF4CAF50) // 绿色 - 快
                        delay != null && delay in 300..600 -> Color(0xFFFF9800) // 橙色 - 中等
                        delay != null && delay > 600 -> Color(0xFFF44336) // 红色 - 慢
                        delay == -1 -> Color(0xFF9E9E9E) // 灰色 - 超时
                        isAlive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(8.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 延迟显示
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else if (delay != null) {
                Text(
                    text = if (delay == -1) "超时" else "${delay}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        delay == -1 -> Color(0xFF9E9E9E)
                        delay < 300 -> Color(0xFF4CAF50)
                        delay < 600 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    },
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 测试按钮
            IconButton(
                onClick = onTestDelay,
                modifier = Modifier.size(32.dp),
                enabled = !isTesting
            ) {
                Icon(
                    Icons.Default.NetworkCheck,
                    contentDescription = "测试延迟",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            // 选中图标
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
