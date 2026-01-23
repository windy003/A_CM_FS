package com.example.clashmeta.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.clashmeta.core.ClashVpnService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(ClashVpnService.isVpnRunning(context)) }

    // 设备信息
    val deviceInfo = remember {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val modelNormalized = model.lowercase().replace("-", "")
        val isLGWing = manufacturer.lowercase().contains("lg") &&
                      (modelNormalized.contains("wing") || modelNormalized.contains("lmf100"))
        buildString {
            append("当前设备: $manufacturer $model")
            if (isLGWing) append(" (LG Wing)")
        }
    }

    // 磁贴配置信息
    val tileConfigInfo = remember {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase().replace("-", "")
        val isLGWing = manufacturer.contains("lg") &&
                      (model.contains("wing") || model.contains("lmf100"))
        "磁贴状态配置: ${if (isLGWing) "反转模式 (LG Wing)" else "正常模式"}"
    }

    // 定时刷新状态
    LaunchedEffect(Unit) {
        while (true) {
            isConnected = ClashVpnService.isVpnRunning(context)
            delay(1000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 连接按钮
            ConnectionButton(
                isConnected = isConnected,
                onClick = {
                    if (isConnected) {
                        onStopVpn()
                    } else {
                        onStartVpn()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 状态文本
            Text(
                text = if (isConnected) "已连接" else "未连接",
                fontSize = 18.sp,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 设备信息
            Text(
                text = deviceInfo,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 磁贴配置信息
            Text(
                text = tileConfigInfo,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ConnectionButton(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "buttonColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "iconColor"
    )

    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Power,
            contentDescription = if (isConnected) "断开连接" else "连接",
            tint = iconColor,
            modifier = Modifier.size(64.dp)
        )
    }
}
