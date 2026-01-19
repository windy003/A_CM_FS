package com.example.clashmeta.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToApps: () -> Unit = {}
) {
    val context = LocalContext.current
    var appProxyInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // 加载分应用代理配置信息
        val config = AppProxyManager.loadConfig(context)
        appProxyInfo = when (config.mode) {
            ProxyMode.PROXY_ALL -> "代理所有应用"
            ProxyMode.BYPASS_SELECTED -> "绕过 ${config.selectedApps.size} 个应用"
            ProxyMode.ONLY_SELECTED -> "仅代理 ${config.selectedApps.size} 个应用"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToApps() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "分应用代理",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = appProxyInfo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
