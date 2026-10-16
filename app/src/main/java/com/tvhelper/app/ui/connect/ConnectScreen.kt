package com.tvhelper.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvhelper.app.data.ConnectionPhase
import com.tvhelper.app.net.NetUtils
import com.tvhelper.app.ui.common.EmptyHint
import com.tvhelper.app.ui.common.PillButton
import com.tvhelper.app.ui.common.SectionTitle
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

@Composable
fun ConnectScreen(vm: TvViewModel) {
    val connection by vm.connection.collectAsState()
    val scanResults by vm.scanResults.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val known by vm.knownDevices.collectAsState()
    val localIp by vm.localIp.collectAsState()

    var showManual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }

    val connecting = connection.phase == ConnectionPhase.CONNECTING

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "电视助手",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "遥控 · 应用管理 · APK 安装",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            TvCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "本机局域网地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                localIp ?: "未连接 Wi-Fi",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { if (scanning) vm.stopScan() else vm.startScan() },
                            enabled = !connecting,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("停止扫描")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("扫描电视")
                            }
                        }
                        Button(
                            onClick = { showManual = true },
                            enabled = !connecting,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("手动 IP")
                        }
                    }
                    if (localIp != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "手机与电视需在同一 Wi-Fi；电视开启“无线显示/远程控制”更易被发现",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (connecting || connection.message != null) {
            item {
                TvCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            StatusDot(connection.isConnected)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            connection.message ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (connection.phase == ConnectionPhase.FAILED) TvColors.danger
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        item { SectionTitle("发现的设备") }

        if (scanResults.isEmpty()) {
            item {
                TvCard {
                    EmptyHint(
                        if (scanning) "正在搜索局域网内的电视…" else "点“扫描电视”开始搜索，或直接输入电视 IP"
                    )
                }
            }
        } else {
            items(scanResults, key = { "scan_${it.id}" }) { d ->
                DeviceRow(
                    title = d.name,
                    subtitle = buildString {
                        append(d.host)
                        append(" · RC ").append(d.rcPort)
                        append(" / UDT ").append(d.udtPort)
                        d.model?.let { append(" · ").append(it) }
                    },
                    onClick = { vm.connectDiscovered(d) },
                    enabled = !connecting,
                )
            }
        }

        if (known.isNotEmpty()) {
            item { SectionTitle("最近连接") }
            items(known, key = { "known_${it.id}" }) { dev ->
                DeviceRow(
                    title = dev.displayName,
                    subtitle = "${dev.host} · RC ${dev.rcPort} / UDT ${dev.udtPort}",
                    onClick = { vm.connect(dev) },
                    enabled = !connecting,
                )
            }
        }

        item {
            TvCard {
                Column {
                    Text(
                        "支持的电视",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本应用复刻的是小米电视助手（多看 AirKan / milink UDT）协议，仅支持小米 / 红米 MIUI TV 及同协议电视。\n" +
                            "若电视固件要求加密通道（协议版本高于 0x01000200），按键可能无效 —— 连接后会在设备页给出提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("输入电视 IP") },
            text = {
                Column {
                    Text(
                        "在电视「设置 → 关于 → 网络信息」中查看局域网 IP。\n" +
                            "端口会自动探测（RC 30612/6091、UDT 6093），无需手填。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        singleLine = true,
                        label = { Text("例如 192.168.1.100") },
                        isError = manualIp.isNotEmpty() && !NetUtils.isValidIpv4(manualIp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManual = false
                        vm.connectManualIp(manualIp)
                    },
                    enabled = NetUtils.isValidIpv4(manualIp),
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "TV",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton("连接", onClick = onClick, enabled = enabled)
        }
    }
}
