package com.example.clashmeta.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.data.ProxyMode
import com.example.clashmeta.data.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToApps: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appProxyInfo by remember { mutableStateOf("") }
    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // 导出文件选择器
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            scope.launch {
                isBackingUp = true
                val success = withContext(Dispatchers.IO) {
                    exportBackup(context, uri)
                }
                isBackingUp = false
                if (success) {
                    Toast.makeText(context, "备份成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "备份失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 导入文件选择器
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isRestoring = true
                val success = withContext(Dispatchers.IO) {
                    importBackup(context, uri)
                }
                isRestoring = false
                if (success) {
                    Toast.makeText(context, "恢复成功，请重启应用", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "恢复失败，文件格式错误", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

            Spacer(modifier = Modifier.height(16.dp))

            // 数据备份与恢复
            Text(
                text = "数据备份",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "导出配置文件，卸载重装后可导入恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 导出按钮
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_TITLE, "clashmeta_backup.zip")
                                    // 允许选择任意位置
                                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                                        Uri.parse("content://com.android.externalstorage.documents/document/primary:"))
                                }
                                exportLauncher.launch(intent)
                            },
                            enabled = !isBackingUp && !isRestoring,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBackingUp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出")
                        }

                        // 导入按钮
                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            enabled = !isBackingUp && !isRestoring,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isRestoring) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导入")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导出备份到 zip 文件
 */
private fun exportBackup(context: android.content.Context, uri: Uri): Boolean {
    return try {
        val clashDir = ClashMetaApp.instance.getClashDir()
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return false

        ZipOutputStream(outputStream).use { zip ->
            // 备份 subscriptions.json
            val subscriptionsFile = SubscriptionManager.getSubscriptionsFile(context)
            if (subscriptionsFile.exists()) {
                zip.putNextEntry(ZipEntry("subscriptions.json"))
                zip.write(subscriptionsFile.readBytes())
                zip.closeEntry()
            }

            // 备份 config.yaml
            val configFile = File(clashDir, "config.yaml")
            if (configFile.exists()) {
                zip.putNextEntry(ZipEntry("config.yaml"))
                zip.write(configFile.readBytes())
                zip.closeEntry()
            }

            // 备份所有订阅文件 sub_*.yaml
            clashDir.listFiles()?.filter { it.name.startsWith("sub_") && it.name.endsWith(".yaml") }?.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }

            // 备份 active_config.txt
            val activeConfigFile = File(clashDir, "active_config.txt")
            if (activeConfigFile.exists()) {
                zip.putNextEntry(ZipEntry("active_config.txt"))
                zip.write(activeConfigFile.readBytes())
                zip.closeEntry()
            }

            // 备份分应用代理配置
            val appProxyFile = File(clashDir, "app_proxy_config.json")
            if (appProxyFile.exists()) {
                zip.putNextEntry(ZipEntry("app_proxy_config.json"))
                zip.write(appProxyFile.readBytes())
                zip.closeEntry()
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * 从 zip 文件导入备份
 */
private fun importBackup(context: android.content.Context, uri: Uri): Boolean {
    return try {
        val clashDir = ClashMetaApp.instance.getClashDir()
        if (!clashDir.exists()) clashDir.mkdirs()

        val inputStream = context.contentResolver.openInputStream(uri) ?: return false

        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            var hasContent = false

            while (entry != null) {
                val fileName = entry.name
                val destFile = when {
                    fileName == "subscriptions.json" -> SubscriptionManager.getSubscriptionsFile(context)
                    fileName == "config.yaml" -> File(clashDir, "config.yaml")
                    fileName == "active_config.txt" -> File(clashDir, "active_config.txt")
                    fileName == "app_proxy_config.json" -> File(clashDir, "app_proxy_config.json")
                    fileName.startsWith("sub_") && fileName.endsWith(".yaml") -> File(clashDir, fileName)
                    else -> null
                }

                if (destFile != null) {
                    destFile.parentFile?.mkdirs()
                    destFile.writeBytes(zip.readBytes())
                    hasContent = true
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
            hasContent
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
