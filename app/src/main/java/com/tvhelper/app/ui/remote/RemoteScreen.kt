package com.tvhelper.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvhelper.app.data.ConnectionState
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.TvKey
import com.tvhelper.app.ui.common.PillButton
import com.tvhelper.app.ui.common.RoundButton
import com.tvhelper.app.ui.common.SectionTitle
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

/** 触控板映射到的虚拟电视分辨率（AirKan 触摸是绝对坐标） */
private const val VIRTUAL_W = 1920f
private const val VIRTUAL_H = 1080f

@Composable
fun RemoteScreen(vm: TvViewModel) {
    val connection by vm.connection.collectAsState()
    val tvVolume by vm.tvVolume.collectAsState()
    val tvVolumeMax by vm.tvVolumeMax.collectAsState()
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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("按键", onClick = { trackpadMode = false }, selected = !trackpadMode)
                PillButton("触控板", onClick = { trackpadMode = true }, selected = trackpadMode)
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
                        SectionTitle("功能键")
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.BACK) },
                                enabled = connected,
                                size = 62,
                            ) { Icon(Icons.Default.KeyboardArrowLeft, "返回") }
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.HOME) },
                                enabled = connected,
                                size = 62,
                            ) { Icon(Icons.Default.Home, "主页") }
                            RoundButton(
                                onPress = { vm.sendKey(TvKey.MENU) },
                                enabled = connected,
                                size = 62,
                            ) { Icon(Icons.Default.Menu, "菜单") }
                            PowerButton(vm = vm, enabled = connected)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Text("返回", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("主页", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("菜单", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("电源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                TvCard {
                    Column {
                        SectionTitle("音量")
                        if (tvVolume != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "当前 $tvVolume / 最大 $tvVolumeMax",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            VolumeButton(
                                label = if (tvVolume != null) "− ($tvVolume)" else "音量 −",
                                enabled = connected,
                                modifier = Modifier.weight(1f),
                                onPress = { vm.adjustVolume(up = false) },
                            )
                            VolumeButton(
                                label = if (tvVolume != null) "＋ ($tvVolume)" else "音量 ＋",
                                enabled = connected,
                                modifier = Modifier.weight(1f),
                                onPress = { vm.adjustVolume(up = true) },
                            )
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
                    }
                }
            }

            item {
                TvCard {
                    Column {
                        SectionTitle("其他")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("静音", onClick = {
                                vm.sendKey(TvKey.MUTE)
                            }, enabled = connected)
                            PillButton("上一曲", onClick = { vm.sendKey(TvKey.MEDIA_PREVIOUS) }, enabled = connected)
                            PillButton("下一曲", onClick = { vm.sendKey(TvKey.MEDIA_NEXT) }, enabled = connected)
                        }
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
                if (connection.isConnected) "RC 30612" else "离线",
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

@Composable
private fun VolumeButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        RoundButton(
            onPress = onPress,
            onRelease = onRelease,
            enabled = enabled,
            size = 56,
            container = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PowerButton(vm: TvViewModel, enabled: Boolean) {
    RoundButton(
        onPress = { vm.sendKey(TvKey.POWER) },
        enabled = enabled,
        size = 62,
        container = TvColors.danger.copy(alpha = 0.16f),
        contentColor = TvColors.danger,
        borderColor = TvColors.danger.copy(alpha = 0.4f),
    ) {
        PowerIcon(tint = TvColors.danger, modifier = Modifier.size(26.dp))
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
