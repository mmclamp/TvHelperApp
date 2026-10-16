package com.tvhelper.app.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.tvhelper.app.data.TvDevice
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
    val lastDevice by vm.lastDevice.collectAsState()

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
            lastDevice = lastDevice,
            onConnectLast = { lastDevice?.let { vm.connect(it) } },
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

    // 快捷打开长按菜单（排序/取消固定）——底部动作面板
    actionTarget?.let { target ->
        val index = pinned.indexOfFirst { it.packageName == target.packageName }
        ActionSheet(
            title = target.displayName,
            subtitle = target.packageName,
            onDismiss = { actionTarget = null },
            items = listOf(
                ActionSheetItem(Icons.Default.PlayArrow, "打开") {
                    vm.openPinned(target)
                },
                ActionSheetItem(Icons.Default.Close, "取消固定") {
                    vm.togglePin(target)
                },
                ActionSheetItem(
                    Icons.Default.KeyboardArrowUp, "前移",
                    enabled = index > 0,
                ) {
                    vm.movePin(index, index - 1)
                },
                ActionSheetItem(
                    Icons.Default.KeyboardArrowDown, "后移",
                    enabled = index >= 0 && index < pinned.size - 1,
                ) {
                    vm.movePin(index, index + 1)
                },
            ),
            afterAction = { actionTarget = null },
        )
    }

    // 应用列表长按菜单（打开/固定/卸载）——底部动作面板
    appActionTarget?.let { target ->
        val isPinned = target.packageName in pinnedPkgs
        ActionSheet(
            title = target.displayName,
            subtitle = target.packageName,
            onDismiss = { appActionTarget = null },
            items = listOf(
                ActionSheetItem(Icons.Default.PlayArrow, "在电视上打开") {
                    vm.openApp(target)
                },
                ActionSheetItem(
                    if (isPinned) Icons.Default.Close else Icons.Default.Star,
                    if (isPinned) "取消固定" else "固定到快捷",
                ) {
                    vm.togglePin(target.toPinned())
                },
                ActionSheetItem(
                    Icons.Default.Delete, "卸载",
                    tint = MaterialTheme.colorScheme.error,
                ) {
                    vm.uninstallApp(target)
                },
            ),
            afterAction = { appActionTarget = null },
        )
    }
}

/** 底部动作面板数据项 */
private data class ActionSheetItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val action: () -> Unit,
)

/** 底部动作面板：图标 + 文案的动作行列表（替代原 AlertDialog） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    title: String,
    subtitle: String,
    items: List<ActionSheetItem>,
    onDismiss: () -> Unit,
    afterAction: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = item.enabled) {
                            afterAction()
                            item.action()
                        }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = item.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            !item.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> item.tint ?: MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(
    connection: ConnectionState,
    lastDevice: TvDevice?,
    onConnectLast: () -> Unit,
    onDisconnect: () -> Unit,
    onGoConnect: () -> Unit,
    onGoInstall: () -> Unit,
) {
    // 连接状态渐变英雄卡：连接中用品牌蓝渐变，未连接用柔和表面色
    val connected = connection.isConnected
    val connecting = connection.phase == ConnectionPhase.CONNECTING
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
                        StatusDot(connected || connecting)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                connected -> connection.device?.host ?: ""
                                connecting -> connection.message ?: "正在连接…"
                                else -> "请先连接电视"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) Color.White.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                Spacer(Modifier.height(14.dp))
                // 底部操作行：等宽半透明按钮
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroActionButton(
                        icon = Icons.Default.Add,
                        label = "安装 APK",
                        modifier = Modifier.weight(1f),
                        onClick = onGoInstall,
                    )
                    HeroActionButton(
                        icon = Icons.Default.Close,
                        label = "断开连接",
                        modifier = Modifier.weight(1f),
                        onClick = onDisconnect,
                    )
                }
            } else {
                // 未连接：主 CTA —— 有记录直接重连，否则去连接页
                Spacer(Modifier.height(14.dp))
                PrimaryCta(
                    label = when {
                        connecting -> "正在连接…"
                        lastDevice != null -> "连接 ${lastDevice.displayName}"
                        else -> "连接电视"
                    },
                    hint = when {
                        connecting -> connection.message
                        lastDevice != null -> "点按直接重连上次设备 · 去连接其他电视"
                        else -> "扫描或手动输入 IP"
                    },
                    connecting = connecting,
                    onClick = {
                        if (!connecting) {
                            if (lastDevice != null) onConnectLast() else onGoConnect()
                        }
                    },
                )
            }
        }
    }
}

/** 英雄卡底部操作按钮：白色半透明底 + 描边，适配渐变背景 */
@Composable
private fun HeroActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.20f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/** 未连接状态的主 CTA：主色填充大按钮，连接中变为进度态 */
@Composable
private fun PrimaryCta(
    label: String,
    hint: String? = null,
    connecting: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !connecting, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (connecting) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.primary
        },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connecting) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connecting) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!connecting) {
                Text(
                    "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
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
