package com.tvhelper.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvhelper.app.ui.theme.TvColors

/** 统一卡片容器 */
@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun StatusDot(online: Boolean, size: Int = 9) {
    val color by animateColorAsState(
        targetValue = if (online) TvColors.online else TvColors.offline,
        label = "statusDot",
    )
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

/**
 * 圆形遥控按键。
 *
 * 按下即回调 [onPress]（用于「按下立刻发一次按键，之后连发」），
 * 抬起回调 [onRelease]（用于停止连发）。
 */
@Composable
fun RoundButton(
    onPress: () -> Unit,
    onRelease: () -> Unit = {},
    size: Int = 62,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> container.copy(alpha = 0.4f)
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
            else -> container
        },
        label = "roundBg",
    )
    // 按压缩放反馈：按下去明显缩小 + 变色，松手回弹
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        label = "roundScale",
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) contentColor else contentColor.copy(alpha = 0.4f)
        ) { content() }
    }
}

/** 胶囊型文字按键（带按压缩放 + 变色反馈） */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        pressed || selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pillScale")
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = if (pressed) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
