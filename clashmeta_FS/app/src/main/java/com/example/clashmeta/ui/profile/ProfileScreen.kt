package com.example.clashmeta.ui.profile

import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.clashmeta.ClashMetaApp
import com.example.clashmeta.data.Subscription
import com.example.clashmeta.data.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<Subscription?>(null) }
    var subscriptions by remember { mutableStateOf<List<Subscription>>(emptyList()) }
    var activeConfigId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    // 加载订阅列表
    LaunchedEffect(Unit) {
        subscriptions = SubscriptionManager.loadSubscriptions(context)
        // 读取当前激活的配置ID
        val activeFile = File(ClashMetaApp.instance.getClashDir(), "active_config.txt")
        if (activeFile.exists()) {
            activeConfigId = activeFile.readText().trim()
        }
    }

    // 下载订阅内容
    suspend fun downloadSubscription(url: String): String {
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ClashMetaForAndroid/2.10.1")
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.inputStream.bufferedReader().readText()
        }
    }

    // 保存为当前配置
    fun setAsActiveConfig(subscription: Subscription) {
        scope.launch {
            try {
                val subFile = File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                val configFile = ClashMetaApp.instance.getConfigFile()

                withContext(Dispatchers.IO) {
                    subFile.copyTo(configFile, overwrite = true)
                    // 保存激活的配置ID
                    File(ClashMetaApp.instance.getClashDir(), "active_config.txt")
                        .writeText(subscription.id)
                }

                activeConfigId = subscription.id

                try {
                    Mobile.reloadConfig()
                    Toast.makeText(context, "已切换到: ${subscription.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "已切换配置，重启VPN生效", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 更新订阅
    fun updateSubscription(subscription: Subscription) {
        scope.launch {
            updatingId = subscription.id
            try {
                val content = downloadSubscription(subscription.url)

                withContext(Dispatchers.IO) {
                    val file = File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                    file.writeText(content)
                }

                // 更新时间
                subscription.updatedAt = System.currentTimeMillis()
                SubscriptionManager.updateSubscription(context, subscription)
                subscriptions = SubscriptionManager.loadSubscriptions(context)

                // 如果是当前激活的配置，也更新config.yaml
                if (activeConfigId == subscription.id) {
                    val configFile = ClashMetaApp.instance.getConfigFile()
                    withContext(Dispatchers.IO) {
                        File(ClashMetaApp.instance.getClashDir(), subscription.fileName)
                            .copyTo(configFile, overwrite = true)
                    }
                    try {
                        Mobile.reloadConfig()
                    } catch (e: Exception) {
                        // VPN可能没运行
                    }
                }

                Toast.makeText(context, "更新成功: ${subscription.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                updatingId = null
            }
        }
    }

    // 删除订阅
    fun deleteSubscription(subscription: Subscription) {
        scope.launch {
            try {
                // 删除配置文件
                withContext(Dispatchers.IO) {
                    File(ClashMetaApp.instance.getClashDir(), subscription.fileName).delete()
                }
                // 从列表中移除
                SubscriptionManager.deleteSubscription(context, subscription.id)
                subscriptions = SubscriptionManager.loadSubscriptions(context)

                // 如果删除的是当前激活的配置，清除激活状态
                if (activeConfigId == subscription.id) {
                    activeConfigId = null
                    File(ClashMetaApp.instance.getClashDir(), "active_config.txt").delete()
                }

                Toast.makeText(context, "已删除: ${subscription.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置管理") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加配置")
                    }
                }
            )
        }
    ) { padding ->
        if (subscriptions.isEmpty()) {
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
                        Icons.Default.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无订阅",
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加订阅")
                    }
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
                items(subscriptions, key = { it.id }) { subscription ->
                    SubscriptionItem(
                        subscription = subscription,
                        isActive = activeConfigId == subscription.id,
                        isUpdating = updatingId == subscription.id,
                        onActivate = { setAsActiveConfig(subscription) },
                        onUpdate = { updateSubscription(subscription) },
                        onEdit = {
                            editingSubscription = subscription
                            showEditDialog = true
                        },
                        onDelete = { deleteSubscription(subscription) }
                    )
                }
            }
        }
    }

    // 添加订阅对话框
    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                isLoading = true
                scope.launch {
                    try {
                        val content = downloadSubscription(url)

                        val id = UUID.randomUUID().toString()
                        val fileName = "sub_${id.take(8)}.yaml"
                        val file = File(ClashMetaApp.instance.getClashDir(), fileName)

                        withContext(Dispatchers.IO) {
                            file.writeText(content)
                        }

                        val subscription = Subscription(
                            id = id,
                            name = name,
                            url = url,
                            fileName = fileName
                        )

                        SubscriptionManager.addSubscription(context, subscription)
                        subscriptions = SubscriptionManager.loadSubscriptions(context)

                        // 如果是第一个订阅，自动设为当前配置
                        if (subscriptions.size == 1) {
                            setAsActiveConfig(subscription)
                        }

                        Toast.makeText(context, "添加成功: $name", Toast.LENGTH_SHORT).show()
                        showAddDialog = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        isLoading = false
                    }
                }
            },
            isLoading = isLoading
        )
    }

    // 编辑订阅对话框
    if (showEditDialog && editingSubscription != null) {
        EditSubscriptionDialog(
            subscription = editingSubscription!!,
            onDismiss = {
                showEditDialog = false
                editingSubscription = null
            },
            onConfirm = { name, url ->
                scope.launch {
                    val sub = editingSubscription!!
                    sub.name = name
                    sub.url = url
                    SubscriptionManager.updateSubscription(context, sub)
                    subscriptions = SubscriptionManager.loadSubscriptions(context)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    showEditDialog = false
                    editingSubscription = null
                }
            }
        )
    }
}

@Composable
fun SubscriptionItem(
    subscription: Subscription,
    isActive: Boolean,
    isUpdating: Boolean,
    onActivate: () -> Unit,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onActivate() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Description,
                contentDescription = null,
                tint = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subscription.url.take(40) + if (subscription.url.length > 40) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (isActive) {
                    Text(
                        text = "当前使用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 更新按钮
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = onUpdate) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "更新",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 更多菜单
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多"
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${subscription.name}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
    isLoading: Boolean
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("添加订阅") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("我的订阅") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅地址") },
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.ifBlank { "订阅 ${System.currentTimeMillis() % 10000}" }
                    onConfirm(finalName, url)
                },
                enabled = !isLoading && url.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
fun EditSubscriptionDialog(
    subscription: Subscription,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf(subscription.name) }
    var url by remember { mutableStateOf(subscription.url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑订阅") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
