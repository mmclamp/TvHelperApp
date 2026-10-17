package com.tvhelper.app.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvhelper.app.data.ConnectionState
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.TvKey
import com.tvhelper.app.ui.common.PillButton
import com.tvhelper.app.ui.common.RoundButton
import kotlinx.coroutines.launch

import com.tvhelper.app.ui.common.SectionTitle
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

/** 触控板映射到的虚拟电视分辨率（AirKan 触摸是绝对坐标） */
private const val VIRTUAL_W = 1920f
private const val VIRTUAL_H = 1080f

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: TvViewModel, onGoConnect: () -> Unit = {}) {
    val connection by vm.connection.collectAsState()
    val tvVolume by vm.tvVolume.collectAsState()
    val tvVolumeMax by vm.tvVolumeMax.collectAsState()
    val tvMuted by vm.tvMuted.collectAsState()
    val connected = connection.isConnected
    var trackpadMode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RemoteHeader(connection) }

        // 未连接：整页控件禁用前先给出路（Material 空状态行动指引）
        if (!connected) {
            item {
                Button(
                    onClick = onGoConnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("去连接电视") }
            }
        }

        item {
            // 单选切换用平台组件：语义与无障碍优于手搓胶囊
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !trackpadMode,
                    onClick = { trackpadMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("按键") }
                SegmentedButton(
                    selected = trackpadMode,
                    onClick = { trackpadMode = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("触控板") }
            }
        }

        if (trackpadMode) {
            item {
                TvCard {
                    Column {
                        Text(
                            "在下方区域滑动 = 直接向电视注入触摸（绝对坐标映射 1920×1080）。部分固件不开放触摸注入，属正常现象。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Trackpad(enabled = connected, vm = vm)
                    }
                }
            }
        } else {
            // 卡片顺序按使用频率：方向键 → 功能键（含音量/静音）→ 播放控制
            item {
                TvCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionTitle("方向键")
                        Spacer(Modifier.height(6.dp))
                        DPad(vm = vm, enabled = connected)
                    }
                }
            }

            item {
                TvCard {
                    Column {
                        SectionTitle("功能键与音量")
                        Spacer(Modifier.height(10.dp))
                        // 按钮和标签包在同一 Column，保证每个标签严格居中于自己的按钮下方
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            LabeledKey("返回", enabled = connected, onPress = { vm.sendKey(TvKey.BACK) }) {
                                Icon(Icons.Default.KeyboardArrowLeft, "返回")
                            }
                            LabeledKey("主页", enabled = connected, onPress = { vm.sendKey(TvKey.HOME) }) {
                                Icon(Icons.Default.Home, "主页")
                            }
                            LabeledKey("菜单", enabled = connected, onPress = { vm.sendKey(TvKey.MENU) }) {
                                Icon(Icons.Default.Menu, "菜单")
                            }
                            PowerButton(vm = vm, enabled = connected)
                        }
                        // 音量排：当前音量值显示在减/加之间；静音=红色态
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            LabeledKey("音量减", enabled = connected, onPress = { vm.adjustVolume(up = false) }) {
                                Text("−", style = MaterialTheme.typography.headlineSmall)
                            }
                            // 当前音量：纯数字「4 / 100」上下两行，无引导文本
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.height(62.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            tvVolume?.toString() ?: "—",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            if (tvVolume != null) "/$tvVolumeMax" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            LabeledKey("音量加", enabled = connected, onPress = { vm.adjustVolume(up = true) }) {
                                Text("＋", style = MaterialTheme.typography.headlineSmall)
                            }
                            LabeledKey(
                                label = "静音",
                                enabled = connected,
                                onPress = { vm.toggleMute() },
                                container = if (tvMuted == true) TvColors.danger.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (tvMuted == true) TvColors.danger
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                borderColor = if (tvMuted == true) TvColors.danger.copy(alpha = 0.4f) else null,
                            ) {
                                MuteIcon(muted = tvMuted == true, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }

            item {
                TvCard {
                    Column {
                        SectionTitle("播放控制")
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.MEDIA_PREVIOUS) },
                                enabled = connected,
                                borderColor = MaterialTheme.colorScheme.outline,
                            ) { Text("上一集", style = MaterialTheme.typography.labelSmall) }
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.MEDIA_PLAY_PAUSE) },
                                enabled = connected,
                                size = 72,
                                container = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ) { Icon(Icons.Default.PlayArrow, "播放暂停", modifier = Modifier.size(30.dp)) }
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.MEDIA_NEXT) },
                                enabled = connected,
                                borderColor = MaterialTheme.colorScheme.outline,
                            ) { Text("下一集", style = MaterialTheme.typography.labelSmall) }
                        }
                        // 原「其他」卡并入：同一语义的上一曲/下一曲已由上方按键覆盖，只留增量键
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("快退", onClick = { vm.sendKey(TvKey.MEDIA_REWIND) }, enabled = connected)
                            PillButton("快进", onClick = { vm.sendKey(TvKey.MEDIA_FAST_FORWARD) }, enabled = connected)
                            PillButton("设置", onClick = { vm.sendKey(TvKey.SETTINGS) }, enabled = connected)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "方向键支持长按连发；音量键单击一次 ±1（长按无连发）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteHeader(connection: ConnectionState) {
    TvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(connection.isConnected)
            Spacer(Modifier.width(8.dp))
            Text(
                connection.device?.displayName ?: "未连接",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (connection.isConnected) "已连接" else "离线",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DPad(vm: TvViewModel, enabled: Boolean) {
    val key = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatKeyButton(vm, TvKey.DPAD_UP, enabled, container = key, contentColor = fg) {
            Icon(Icons.Default.KeyboardArrowUp, "上", modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatKeyButton(vm, TvKey.DPAD_LEFT, enabled, container = key, contentColor = fg) {
                Icon(Icons.Default.KeyboardArrowLeft, "左", modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(10.dp))
            RoundButton(
                onPress = { vm.sendKey(TvKey.DPAD_CENTER) },
                enabled = enabled,
                size = 78,
                container = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                contentColor = MaterialTheme.colorScheme.primary,
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            ) {
                Text("OK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            RepeatKeyButton(vm, TvKey.DPAD_RIGHT, enabled, container = key, contentColor = fg) {
                Icon(Icons.Default.KeyboardArrowRight, "右", modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        RepeatKeyButton(vm, TvKey.DPAD_DOWN, enabled, container = key, contentColor = fg) {
            Icon(Icons.Default.KeyboardArrowDown, "下", modifier = Modifier.size(30.dp))
        }
    }
}


/** 支持长按连发的按键 */
@Composable
private fun RepeatKeyButton(
    vm: TvViewModel,
    keyCode: Int,
    enabled: Boolean,
    container: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    RoundButton(
        onPress = { vm.startKeyRepeat(keyCode) },
        onRelease = { vm.stopKeyRepeat() },
        enabled = enabled,
        container = container,
        contentColor = contentColor,
        content = content,
    )
}

/** 圆形按键 + 下方文字标签：同一 Column 保证标签严格居中于按钮下方 */
@Composable
private fun LabeledKey(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color? = null,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(
            onPress = onPress,
            enabled = enabled,
            size = 62,
            container = container,
            contentColor = contentColor,
            borderColor = borderColor,
        ) { content() }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

@Composable
private fun PowerButton(vm: TvViewModel, enabled: Boolean) {
    // 电源是高代价操作（可能直接关电视）：按下抬起后弹确认框，防误触
    var confirmPower by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(
            onPress = {},
            onRelease = { confirmPower = true },
            enabled = enabled,
            size = 62,
            container = TvColors.danger.copy(alpha = 0.16f),
            contentColor = TvColors.danger,
            borderColor = TvColors.danger.copy(alpha = 0.4f),
        ) {
            PowerIcon(tint = TvColors.danger, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("电源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmPower) {
        AlertDialog(
            onDismissRequest = { confirmPower = false },
            title = { Text("发送电源键") },
            text = { Text("将向电视发送电源键，电视可能关机或熄屏。") },
            confirmButton = {
                TextButton(onClick = { confirmPower = false; vm.sendKey(TvKey.POWER) }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPower = false }) { Text("取消") }
            },
        )
    }
}

/** 自绘静音图标：喇叭 + 声波（未静音）/ 喇叭 + 叉（已静音）。icons-core 无 Volume 系列，Canvas 自绘 */
@Composable
private fun MuteIcon(muted: Boolean, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.9f * u,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        // 喇叭本体（实心梯形+矩形）
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(o(4f, 9.6f).x, o(4f, 9.6f).y)
            lineTo(o(7.5f, 9.6f).x, o(7.5f, 9.6f).y)
            lineTo(o(12f, 5.4f).x, o(12f, 5.4f).y)
            lineTo(o(12f, 18.6f).x, o(12f, 18.6f).y)
            lineTo(o(7.5f, 14.4f).x, o(7.5f, 14.4f).y)
            lineTo(o(4f, 14.4f).x, o(4f, 14.4f).y)
            close()
        }
        drawPath(body, tint)
        if (!muted) {
            // 声波：两道右向弧线
            listOf(3.2f, 5.8f).forEach { r ->
                drawArc(
                    color = tint,
                    startAngle = -48f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = o(12.4f - r, 12f - r),
                    size = androidx.compose.ui.geometry.Size(2f * r * u, 2f * r * u),
                    style = stroke,
                )
            }
        } else {
            // 叉：两道斜线
            drawLine(tint, o(15.2f, 9.4f), o(20.2f, 14.6f), strokeWidth = 1.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(tint, o(20.2f, 9.4f), o(15.2f, 14.6f), strokeWidth = 1.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** 自绘电源图标：顶部缺口圆环 + 竖线（icons-core 无 PowerSettingsNew） */
@Composable
private fun PowerIcon(tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.115f
        val cx = size.width / 2f
        val r = (size.minDimension - stroke) / 2f
        val arcStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // 圆环：从顶部留出缺口（-50° 到 230°，缺口朝上）
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r, size.height / 2f - r),
            size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
            style = arcStyle,
        )
        // 竖线：从顶部中间向下到圆心上方
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(cx, stroke * 0.4f),
            end = androidx.compose.ui.geometry.Offset(cx, size.height * 0.52f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * 触控板：把手指在手机上的绝对位置映射到电视虚拟屏幕，
 * 走 RC type 6 SendTouch（MotionEvent 全量序列化）。
 */
@Composable
private fun Trackpad(enabled: Boolean, vm: TvViewModel) {
    var boxSize by remember { mutableStateOf(Offset(1f, 1f)) }
    var downTime by remember { mutableStateOf(0L) }

    fun tvX(x: Float) = (x / boxSize.x.coerceAtLeast(1f)) * VIRTUAL_W
    fun tvY(y: Float) = (y / boxSize.y.coerceAtLeast(1f)) * VIRTUAL_H

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { boxSize = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        downTime = System.currentTimeMillis()
                        vm.sendTouch(
                            RcTouchCodec.ACTION_DOWN,
                            tvX(offset.x), tvY(offset.y),
                            downTime, System.currentTimeMillis(),
                        )
                    },
                    onDrag = { change, _ ->
                        val p = change.position
                        vm.sendTouch(
                            RcTouchCodec.ACTION_MOVE,
                            tvX(p.x), tvY(p.y),
                            downTime, System.currentTimeMillis(),
                        )
                    },
                    onDragEnd = {
                        vm.sendTouch(
                            RcTouchCodec.ACTION_UP,
                            0f, 0f,
                            downTime, System.currentTimeMillis(),
                        )
                    },
                    onDragCancel = {
                        vm.sendTouch(
                            RcTouchCodec.ACTION_CANCEL,
                            0f, 0f,
                            downTime, System.currentTimeMillis(),
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) "在此滑动以操控电视" else "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
