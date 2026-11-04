package com.tvhelper.app.ui.remote

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tvhelper.app.data.ConnectionState
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.TvKey
import com.tvhelper.app.ui.common.PillButton
import com.tvhelper.app.ui.common.RoundButton
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

/** 触控板映射到的虚拟电视分辨率（AirKan 触摸是绝对坐标） */
private const val VIRTUAL_W = 1920f
private const val VIRTUAL_H = 1080f

/**
 * 遥控界面本地调色板：对标小米/Redmi 极简暗色遥控——纯黑画布、悬浮深灰面板、
 * 中性灰圆形按键、唯一的红（电源/危险）与品牌蓝（播放键）点缀。明暗自适应。
 */
private data class RemotePalette(
    val bg: Color,
    val panel: Color,
    val keyFill: Color,
    val keyFillPressed: Color,
    val keyGlyph: Color,
    val okFill: Color,
    val okGlyph: Color,
    val label: Color,
    val sub: Color,
    val hairline: Color,
)

@Composable
private fun remotePalette(): RemotePalette {
    val dark = isSystemInDarkTheme()
    return if (dark) RemotePalette(
        bg = Color(0xFF000000),
        panel = Color(0xFF161618),
        keyFill = Color(0xFF2A2A2E),
        keyFillPressed = Color(0xFF3C3C42),
        keyGlyph = Color(0xFFD8D8DC),
        okFill = Color(0xFF3C3C42),
        okGlyph = Color(0xFFF4F4F6),
        label = Color(0xFF8A8A90),
        sub = Color(0xFF5A5A60),
        hairline = Color(0xFF3A3A40),
    ) else RemotePalette(
        bg = Color(0xFFF1F1F4),
        panel = Color(0xFFFFFFFF),
        keyFill = Color(0xFFE4E4E9),
        keyFillPressed = Color(0xFFD2D2D8),
        keyGlyph = Color(0xFF2A2A2E),
        okFill = Color(0xFFD2D2D8),
        okGlyph = Color(0xFF161618),
        label = Color(0xFF6A6A70),
        sub = Color(0xFF9A9AA0),
        hairline = Color(0xFFD8D8DD),
    )
}

@Composable
fun RemoteScreen(vm: TvViewModel, onGoConnect: () -> Unit = {}) {
    val connection by vm.connection.collectAsState()
    val tvVolume by vm.tvVolume.collectAsState()
    val tvVolumeMax by vm.tvVolumeMax.collectAsState()
    val tvMuted by vm.tvMuted.collectAsState()
    val connected = connection.isConnected
    // 单电源按钮不依赖连接/屏幕状态：分流下沉到仓库层 power()，按钮始终可用（对齐原版）
    val palette = remotePalette()
    var trackpadMode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RemoteHeader(connection, palette) }

        // 未连接：整页控件禁用前先给出行动指引
        if (!connected) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.panel,
                ) {
                    Button(
                        onClick = onGoConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) { Text("去连接电视") }
                }
            }
        }

        // 主控面板：一块悬浮深灰面板，承载全部遥控控件（对标参考图的整体卡片）
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = palette.panel,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 模式切换：按键 / 触控板（极简胶囊）
                    ModeToggle(
                        trackpadMode = trackpadMode,
                        onKey = { trackpadMode = false },
                        onPad = { trackpadMode = true },
                        palette = palette,
                    )

                    // 快捷行：电源 / 静音 / 音量减 / 音量加（四枚等距中性圆钮）
                    QuickRow(
                        vm = vm,
                        enabled = connected,
                        muted = tvMuted,
                        palette = palette,
                    )

                    // 音量条：细线 + 圆点滑块，两端显示当前/最大（对标参考图进度条语言）
                    VolumeBar(
                        vol = tvVolume,
                        max = tvVolumeMax,
                        muted = tvMuted,
                        enabled = connected,
                        palette = palette,
                    )

                    if (trackpadMode) {
                        Trackpad(enabled = connected, vm = vm, palette = palette)
                    } else {
                        DPad(vm = vm, enabled = connected, palette = palette)
                    }

                    // 底部键行：主页（圆）· 返回（宽胶囊）· 菜单（圆）
                    BottomRow(vm = vm, enabled = connected, palette = palette)

                    // 播放控制：上一集 / 播放暂停 / 下一集
                    PlaybackRow(vm = vm, enabled = connected, palette = palette)
                }
            }
        }
    }
}

@Composable
private fun RemoteHeader(connection: ConnectionState, palette: RemotePalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        StatusDot(connection.isConnected)
        Spacer(Modifier.width(8.dp))
        Text(
            connection.device?.displayName ?: "未连接",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (connection.isConnected) palette.okGlyph else palette.label,
            modifier = Modifier.weight(1f),
        )
        // 连接状态胶囊：在线绿 / 离线灰
        val chipBg = if (connection.isConnected) TvColors.online else palette.keyFill
        val chipFg = if (connection.isConnected) Color.White else palette.label
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(chipBg.copy(alpha = if (connection.isConnected) 1f else 0.6f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                if (connection.isConnected) "已连接" else "离线",
                style = MaterialTheme.typography.labelSmall,
                color = chipFg,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ModeToggle(
    trackpadMode: Boolean,
    onKey: () -> Unit,
    onPad: () -> Unit,
    palette: RemotePalette,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = palette.keyFill.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeChip("按键", selected = !trackpadMode, onClick = onKey, palette = palette)
            ModeChip("触控板", selected = trackpadMode, onClick = onPad, palette = palette)
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit, palette: RemotePalette) {
    val bg = if (selected) palette.okFill else Color.Transparent
    val fg = if (selected) palette.okGlyph else palette.label
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuickRow(
    vm: TvViewModel,
    enabled: Boolean,
    muted: Boolean?,
    palette: RemotePalette,
) {
    val muteActive = muted == true
    val muteUnknown = muted == null
    val muteContainer = when {
        muteActive -> TvColors.danger.copy(alpha = 0.18f)
        muteUnknown -> palette.keyFill.copy(alpha = 0.45f)
        else -> palette.keyFill
    }
    val muteContent = when {
        muteActive -> TvColors.danger
        muteUnknown -> palette.keyGlyph.copy(alpha = 0.5f)
        else -> palette.keyGlyph
    }
    val muteLabel = when {
        muteActive -> "已静音"
        muteUnknown -> "静音?"
        else -> "静音"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PowerButton(vm = vm, palette = palette)
        LabeledKey(
            label = muteLabel, enabled = enabled, palette = palette,
            container = muteContainer,
            contentColor = muteContent,
            pressColor = if (muteActive) TvColors.danger else palette.keyFillPressed,
            onPress = { vm.toggleMute() },
        ) { MuteIcon(muted = muteActive, tint = muteContent, modifier = Modifier.size(24.dp)) }
        LabeledKey(
            label = "音量−", enabled = enabled, palette = palette,
            pressColor = palette.keyFillPressed,
            onPress = { vm.adjustVolume(up = false) },
            contentColor = palette.keyGlyph,
        ) { Text("−", style = MaterialTheme.typography.headlineSmall, color = palette.keyGlyph) }
        LabeledKey(
            label = "音量＋", enabled = enabled, palette = palette,
            pressColor = palette.keyFillPressed,
            onPress = { vm.adjustVolume(up = true) },
            contentColor = palette.keyGlyph,
        ) { Text("＋", style = MaterialTheme.typography.headlineSmall, color = palette.keyGlyph) }
    }
}

@Composable
private fun VolumeBar(
    vol: Int?,
    max: Int?,
    muted: Boolean?,
    enabled: Boolean,
    palette: RemotePalette,
) {
    var trackW by remember { mutableStateOf(1) }
    val ratio = if (vol != null && max != null && max > 0) (vol.toFloat() / max).coerceIn(0f, 1f) else 0f
    val thumbAlpha = if (enabled) 1f else 0.4f

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            vol?.toString() ?: "--",
            style = MaterialTheme.typography.labelMedium,
            color = palette.sub,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .onSizeChanged { trackW = it.width },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.keyFill),
            )
            // 已填充
            Box(
                Modifier
                    .width((trackW * ratio).dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.keyGlyph.copy(alpha = 0.45f * thumbAlpha)),
            )
            // 滑块圆点（对标参考图白色圆点）
            Box(
                Modifier
                    .offset { IntOffset(((trackW - 12) * ratio).toInt(), 0) }
                    .size(12.dp)
                    .clip(CircleShape)
                    .background((if (muted == true) TvColors.danger else palette.keyGlyph).copy(alpha = thumbAlpha)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            max?.toString() ?: "--",
            style = MaterialTheme.typography.labelMedium,
            color = palette.sub,
            modifier = Modifier.width(30.dp),
        )
    }
}

/**
 * 方向键盘：对标参考图的大圆方向盘，但方向键放大为带方向箭头的完整热区。
 * - 外圈仅一道极淡描边，与面板融为一体；中央 OK 略亮；
 * - 上/下/左/右为放大的圆形热区 + 自绘方向箭头（清晰且易点），各自仍是完整可点区域。
 */
@Composable
private fun DPad(vm: TvViewModel, enabled: Boolean, palette: RemotePalette) {
    Box(
        Modifier
            .size(256.dp)
            .clip(CircleShape)
            .background(palette.panel)
            .border(1.dp, palette.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // 上
        Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            RepeatKeyButton(
                vm, TvKey.DPAD_UP, enabled, palette, size = 62,
                container = Color.Transparent, contentColor = palette.keyGlyph,
            ) { ArrowGlyph(Direction.UP, palette.keyGlyph, Modifier.size(22.dp)) }
        }
        // 下
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            RepeatKeyButton(
                vm, TvKey.DPAD_DOWN, enabled, palette, size = 62,
                container = Color.Transparent, contentColor = palette.keyGlyph,
            ) { ArrowGlyph(Direction.DOWN, palette.keyGlyph, Modifier.size(22.dp)) }
        }
        // 左
        Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            RepeatKeyButton(
                vm, TvKey.DPAD_LEFT, enabled, palette, size = 62,
                container = Color.Transparent, contentColor = palette.keyGlyph,
            ) { ArrowGlyph(Direction.LEFT, palette.keyGlyph, Modifier.size(22.dp)) }
        }
        // 右
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
            RepeatKeyButton(
                vm, TvKey.DPAD_RIGHT, enabled, palette, size = 62,
                container = Color.Transparent, contentColor = palette.keyGlyph,
            ) { ArrowGlyph(Direction.RIGHT, palette.keyGlyph, Modifier.size(22.dp)) }
        }
        // 中央 OK
        RoundButton(
            onPress = { vm.sendKey(TvKey.DPAD_CENTER) },
            enabled = enabled,
            size = 82,
            container = palette.okFill,
            contentColor = palette.okGlyph,
            pressColor = palette.keyFillPressed,
        ) {
            Text("OK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private enum class Direction { UP, DOWN, LEFT, RIGHT }

/** 自绘方向箭头（三角形）：icons-core 无 Navigation 箭头系列，Canvas 自绘，可随方向键放大 */
@Composable
private fun ArrowGlyph(direction: Direction, tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = size.minDimension
        val p = androidx.compose.ui.graphics.Path()
        when (direction) {
            Direction.UP -> { p.moveTo(cx, cy - s * 0.34f); p.lineTo(cx - s * 0.32f, cy + s * 0.20f); p.lineTo(cx + s * 0.32f, cy + s * 0.20f) }
            Direction.DOWN -> { p.moveTo(cx, cy + s * 0.34f); p.lineTo(cx - s * 0.32f, cy - s * 0.20f); p.lineTo(cx + s * 0.32f, cy - s * 0.20f) }
            Direction.LEFT -> { p.moveTo(cx - s * 0.34f, cy); p.lineTo(cx + s * 0.20f, cy - s * 0.32f); p.lineTo(cx + s * 0.20f, cy + s * 0.32f) }
            Direction.RIGHT -> { p.moveTo(cx + s * 0.34f, cy); p.lineTo(cx - s * 0.20f, cy - s * 0.32f); p.lineTo(cx - s * 0.20f, cy + s * 0.32f) }
        }
        p.close()
        drawPath(p, tint)
    }
}

@Composable
private fun BottomRow(vm: TvViewModel, enabled: Boolean, palette: RemotePalette) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 主页（圆）
        RoundButton(
            onPress = { vm.sendKey(TvKey.HOME) },
            enabled = enabled,
            size = 56,
            container = palette.keyFill,
            contentColor = palette.keyGlyph,
            pressColor = palette.keyFillPressed,
        ) { Icon(Icons.Default.Home, "主页", tint = palette.keyGlyph, modifier = Modifier.size(24.dp)) }
        // 返回（宽胶囊，对标参考图长条返回键）
        PillButton(
            text = "返回",
            onClick = { vm.sendKey(TvKey.BACK) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        // 菜单（圆）
        RoundButton(
            onPress = { vm.sendKey(TvKey.MENU) },
            enabled = enabled,
            size = 56,
            container = palette.keyFill,
            contentColor = palette.keyGlyph,
            pressColor = palette.keyFillPressed,
        ) { Icon(Icons.Default.Menu, "菜单", tint = palette.keyGlyph, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
private fun PlaybackRow(vm: TvViewModel, enabled: Boolean, palette: RemotePalette) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            onPress = { vm.sendKey(TvKey.MEDIA_PREVIOUS) },
            enabled = enabled,
            size = 56,
            container = palette.keyFill,
            contentColor = palette.keyGlyph,
            pressColor = palette.keyFillPressed,
        ) { Text("上集", style = MaterialTheme.typography.labelSmall, color = palette.keyGlyph) }
        RoundButton(
            onPress = { vm.sendKey(TvKey.MEDIA_PLAY_PAUSE) },
            enabled = enabled,
            size = 72,
            container = palette.keyFill,
            contentColor = palette.keyGlyph,
            pressColor = palette.keyFillPressed,
        ) { Icon(Icons.Default.PlayArrow, "播放暂停", tint = palette.keyGlyph, modifier = Modifier.size(30.dp)) }
        RoundButton(
            onPress = { vm.sendKey(TvKey.MEDIA_NEXT) },
            enabled = enabled,
            size = 56,
            container = palette.keyFill,
            contentColor = palette.keyGlyph,
            pressColor = palette.keyFillPressed,
        ) { Text("下集", style = MaterialTheme.typography.labelSmall, color = palette.keyGlyph) }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        PillButton("快退", onClick = { vm.sendKey(TvKey.MEDIA_REWIND) }, enabled = enabled)
        PillButton("快进", onClick = { vm.sendKey(TvKey.MEDIA_FAST_FORWARD) }, enabled = enabled)
        PillButton("设置", onClick = { vm.sendKey(TvKey.SETTINGS) }, enabled = enabled)
    }
}

/** 支持长按连发的按键（中性按压态） */
@Composable
private fun RepeatKeyButton(
    vm: TvViewModel,
    keyCode: Int,
    enabled: Boolean,
    palette: RemotePalette,
    size: Int = 62,
    container: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    RoundButton(
        onPress = { vm.startKeyRepeat(keyCode) },
        onRelease = { vm.stopKeyRepeat() },
        enabled = enabled,
        size = size,
        container = container,
        contentColor = contentColor,
        pressColor = palette.keyFillPressed,
        content = content,
    )
}

/** 圆形按键 + 下方细微文字标签 */
@Composable
private fun LabeledKey(
    label: String,
    enabled: Boolean,
    palette: RemotePalette,
    onPress: () -> Unit,
    pressColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color,
    container: Color = palette.keyFill,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(
            onPress = onPress,
            enabled = enabled,
            size = 58,
            container = container,
            contentColor = contentColor,
            pressColor = pressColor,
        ) { content() }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.label)
    }
}

/**
 * 单电源按钮（对齐原版 `MiboxRCActivity.java:621` 唯一按钮绑定 + `class m` 内部分流）。
 *
 * 原版只有一个电源键：未连接 → powerOn() 四路唤醒（红外第一）+ 后台 reconnectDevice(30)；
 * 已连接 → p(26) 注入 POWER 切换屏。按钮本身没有状态文案、没有「开机中」中间态、不禁用。
 *
 * 我们因 UDT 在息屏不断连、固件又不提供 isScreenOn，无法用连接态区分亮/息屏；
 * 分流下沉到仓库层 power()：明确亮屏走 UDT 注入，其余走红外切换键
 * （v1.8.26 真机实证唯一生效路径），红外不可用时自动兜底 WOL/BLE/SPP。
 * 电源是切换键：亮屏按=关屏、息屏/关机按=亮屏，仓库层 10s 冷却防连点「开了又关」。
 */
@Composable
private fun PowerButton(vm: TvViewModel, palette: RemotePalette) {
    val context = LocalContext.current
    // 蓝牙权限只影响红外失败后的 BLE/SPP 兜底；红外（主力）无需任何运行时权限。
    // 非阻塞申请：不得用权限弹窗挡住电源键。
    val btPerms = if (Build.VERSION.SDK_INT >= 31) listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    ) else emptyList()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 权限结果仅影响后续兜底路径；本轮电源键已发出，不重复发送 */ }

    fun firePower() {
        // 单按钮：按下瞬间由仓库层分流（注入 POWER / 红外切换 / 兜底唤醒）
        vm.power()
        if (btPerms.isNotEmpty() && btPerms.any {
                ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        ) {
            permLauncher.launch(btPerms.toTypedArray())
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(
            onPress = {},
            onRelease = { firePower() },
            enabled = true,
            size = 52,
            container = TvColors.danger.copy(alpha = 0.16f),
            contentColor = TvColors.danger,
            borderColor = TvColors.danger.copy(alpha = 0.4f),
            pressColor = TvColors.danger,
        ) { PowerIcon(tint = TvColors.danger, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(4.dp))
        Text("电源", style = MaterialTheme.typography.labelSmall, color = palette.label)
    }
}

/** 自绘静音图标：喇叭 + 声波（未静音）/ 喇叭 + 叉（已静音）。icons-core 无 Volume 系列，Canvas 自绘 */
@Composable
private fun MuteIcon(muted: Boolean, tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.9f * u,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
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
            drawLine(tint, o(15.2f, 9.4f), o(20.2f, 14.6f), strokeWidth = 1.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(tint, o(20.2f, 9.4f), o(15.2f, 14.6f), strokeWidth = 1.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** 自绘电源图标：顶部缺口圆环 + 竖线（icons-core 无 PowerSettingsNew） */
@Composable
private fun PowerIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.115f
        val cx = size.width / 2f
        val r = (size.minDimension - stroke) / 2f
        val arcStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r, size.height / 2f - r),
            size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
            style = arcStyle,
        )
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
private fun Trackpad(enabled: Boolean, vm: TvViewModel, palette: RemotePalette) {
    var boxSize by remember { mutableStateOf(Offset(1f, 1f)) }
    var downTime by remember { mutableStateOf(0L) }

    fun tvX(x: Float) = (x / boxSize.x.coerceAtLeast(1f)) * VIRTUAL_W
    fun tvY(y: Float) = (y / boxSize.y.coerceAtLeast(1f)) * VIRTUAL_H

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.keyFill)
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
                    .background(palette.keyGlyph.copy(alpha = 0.6f))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) "在此滑动以操控电视" else "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = palette.label,
            )
        }
    }
}
