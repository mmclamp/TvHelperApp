package com.tvhelper.app.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvhelper.app.data.ConnectionPhase
import com.tvhelper.app.data.ConnectionState
import com.tvhelper.app.data.PinnedApp
import com.tvhelper.app.data.toPinned
import com.tvhelper.app.protocol.udt.RemoteApp
import com.tvhelper.app.ui.common.EmptyHint
import com.tvhelper.app.ui.common.PillButton
import com.tvhelper.app.ui.common.SectionTitle
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

@Composable
fun HomeScreen(vm: TvViewModel, onGoConnect: () -> Unit, onGoInstall: () -> Unit) {
    val connection by vm.connection.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val loading by vm.appsLoading.collectAsState()
    val pinned by vm.pinnedApps.collectAsState()
    val icons by vm.appIcons.collectAsState()
    val autoConnect by vm.autoConnect.collectAsState()

    var query by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<PinnedApp?>(null) }
    var appActionTarget by remember { mutableStateOf<RemoteApp?>(null) }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    val pinnedPkgs = remember(pinned) { pinned.map { it.packageName }.toSet() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DeviceStatusCard(
            connection = connection,
            onDisconnect = { vm.disconnect() },
            onGoConnect = onGoConnect,
            onGoInstall = onGoInstall,
        ) }

        item {
            SectionTitle("快捷打开", trailing = {
                Text(
                    "${pinned.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            })
        }

        item {
            if (pinned.isEmpty()) {
                TvCard {
                    EmptyHint("还没有固定应用。在下方列表里点 ☆ 固定常用电视 App 到这里")
                }
            } else {
                PinnedGrid(
                    pinned = pinned,
                    icons = icons,
                    onOpen = { vm.openPinned(it) },
                    onLongPress = { actionTarget = it },
                    onEnsureIcon = { vm.ensureIcon(it) },
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("电视上的应用", trailing = null)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索应用") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                PillButton(
                    text = if (loading) "读取中" else "刷新",
                    onClick = { vm.refreshApps() },
                    enabled = !loading,
                )
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }

        if (!connection.udtConnected) {
            item {
                TvCard {
                    Text(
                        "应用管理需要 UDT 通道（6093）—— 当前未连通，无法读取或打开应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvColors.warn,
                    )
                }
            }
        } else if (apps.isEmpty() && !loading) {
            item {
                TvCard { EmptyHint("点“刷新”读取电视上已安装的应用") }
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    iconBytes = icons[app.packageName],
                    pinned = app.packageName in pinnedPkgs,
                    onOpen = { vm.openApp(app) },
                    onTogglePin = { vm.togglePin(app) },
                    onEnsureIcon = { vm.ensureIcon(app.packageName) },
                    onLongPress = { appActionTarget = app },
                )
            }
        }
    }

    // 快捷打开长按菜单（排序/取消固定）
    actionTarget?.let { target ->
        val index = pinned.indexOfFirst { it.packageName == target.packageName }
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.displayName) },
            text = {
                Column {
                    Text(
                        target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        actionTarget = null
                        vm.openPinned(target)
                    }) { Text("打开") }
                    TextButton(onClick = {
                        actionTarget = null
                        vm.togglePin(target)
                    }) { Text("取消固定") }
                    Row {
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                actionTarget = null
                                vm.movePin(index, index - 1)
                            },
                        ) { Text("前移") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = index >= 0 && index < pinned.size - 1,
                            onClick = {
                                actionTarget = null
                                vm.movePin(index, index + 1)
                            },
                        ) { Text("后移") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("关闭") }
            },
        )
    }

    // 应用列表长按菜单（打开/固定/卸载）
    appActionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { appActionTarget = null },
            title = { Text(target.displayName) },
            text = {
                Column {
                    Text(
                        target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        appActionTarget = null
                        vm.openApp(target)
                    }) { Text("打开") }
                    TextButton(onClick = {
                        appActionTarget = null
                        vm.togglePin(target.toPinned())
                    }) { Text(if (target.packageName in pinnedPkgs) "取消固定" else "固定到快捷") }
                    TextButton(onClick = {
                        appActionTarget = null
                        vm.uninstallApp(target)
                    }) {
                        Text("卸载", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { appActionTarget = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun DeviceStatusCard(
    connection: ConnectionState,
    onDisconnect: () -> Unit,
    onGoConnect: () -> Unit,
    onGoInstall: () -> Unit,
) {
    // 连接状态渐变英雄卡：连接中用品牌蓝渐变，未连接用柔和表面色
    val connected = connection.isConnected
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (connected) {
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            Color(0xFF35C6E8),
                        )
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                }
            )
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (connected) Color.White.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "TV",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (connected) Color.White
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        connection.device?.displayName ?: "未连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (connected) Color.White
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connected)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                connected -> connection.device?.host ?: ""
                                connection.phase == ConnectionPhase.CONNECTING -> "正在连接…"
                                else -> "请先连接电视"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) Color.White.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("安装 APK", onClick = onGoInstall)
                        PillButton("断开", onClick = onDisconnect)
                    }
                } else {
                    PillButton("去连接", onClick = onGoConnect)
                }
            }

            if (connected) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip("遥控", connection.rcConnected)
                    HeroChip("应用", connection.udtConnected)
                    HeroChip("加密", connection.encrypted)
                    connection.lastHeartbeatAt.takeIf { it > 0 }?.let {
                        HeroChip("心跳", true, value = formatTime(it))
                    }
                }
                connection.tvProtocolVersion?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "协议版本 0x${Integer.toHexString(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** 英雄卡上的状态小徽章 */
@Composable
private fun HeroChip(label: String, ok: Boolean, value: String? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (ok) Color(0xFF9FF5C8) else Color.White.copy(alpha = 0.5f))
            )
            Text(
                value ?: label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedGrid(
    pinned: List<PinnedApp>,
    icons: Map<String, ByteArray>,
    onOpen: (PinnedApp) -> Unit,
    onLongPress: (PinnedApp) -> Unit,
    onEnsureIcon: (String) -> Unit,
) {
    // 图标懒加载：快捷区每个应用也请求一次（repo 内部有内存/磁盘缓存与去重）
    LaunchedEffect(pinned.map { it.packageName }) {
        pinned.forEach { app ->
            if (icons[app.packageName] == null) onEnsureIcon(app.packageName)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pinned.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { app ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.92f)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { onOpen(app) },
                                onLongClick = { onLongPress(app) },
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Column(
                            Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AppIconBox(
                                iconBytes = icons[app.packageName],
                                initial = app.displayName.take(1),
                                size = 40,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                app.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                repeat(4 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 应用图标：有真实图标显示图片，否则用首字母占位 */
@Composable
private fun AppIconBox(iconBytes: ByteArray?, initial: String, size: Int) {
    val bitmap = remember(iconBytes) {
        iconBytes?.takeIf { it.isNotEmpty() }?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (bitmap != null) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size((size * 0.82).dp),
            )
        } else {
            Text(
                initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AppRow(
    app: RemoteApp,
    iconBytes: ByteArray?,
    pinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onEnsureIcon: () -> Unit,
    onLongPress: () -> Unit,
) {
    // 图标懒加载：行可见且无图标时请求一次（repo 内部有缓存与去重）
    LaunchedEffect(app.packageName, iconBytes == null) {
        if (iconBytes == null) onEnsureIcon()
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBox(iconBytes = iconBytes, initial = app.initial, size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(app.packageName)
                        app.versionName?.let { append("  v").append(it) }
                        if (app.isSystem) append("  · 系统")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PillButton(
                text = if (pinned) "已固定" else "固定",
                onClick = onTogglePin,
                selected = pinned,
            )
            Spacer(Modifier.width(8.dp))
            PillButton("打开", onClick = onOpen)
        }
    }
}

private fun formatTime(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 3 -> "刚刚"
        s < 60 -> "${s}s 前"
        s < 3600 -> "${s / 60}min 前"
        else -> "${s / 3600}h 前"
    }
}
