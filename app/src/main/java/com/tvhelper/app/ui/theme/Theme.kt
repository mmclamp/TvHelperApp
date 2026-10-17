package com.tvhelper.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tvhelper.app.data.SettingsStore

// 品牌主色：深蓝 + 青（呼应电视屏幕与遥控器）
private val BrandBlue = Color(0xFF3D7EFF)
private val BrandCyan = Color(0xFF35C6E8)
private val BrandAmber = Color(0xFFFFB300)
private val BrandRed = Color(0xFFE5484D)
private val BrandGreen = Color(0xFF30A46C)

private val DarkScheme = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B2A4A),
    onPrimaryContainer = Color(0xFFCFE0FF),
    secondary = BrandCyan,
    onSecondary = Color(0xFF04202A),
    tertiary = BrandAmber,
    onTertiary = Color(0xFF2A1C00),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF222833),
    onSurfaceVariant = Color(0xFFB9C0CC),
    outline = Color(0xFF39414F),
    error = BrandRed,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F5FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF00174A),
    secondary = Color(0xFF0E7C99),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1C1F),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFFC3C8D2),
    error = Color(0xFFC0322F),
    onError = Color.White,
)

/** 业务语义色（状态点等） */
object TvColors {
    val online = BrandGreen
    val offline = Color(0xFF8A9099)
    val warn = BrandAmber
    val danger = BrandRed
}

/** 按强调色生成配色方案：替换 primary / primaryContainer 系列，其余沿用品牌基底 */
private fun accentScheme(dark: Boolean, accent: SettingsStore.AccentColor) = run {
    val base = if (dark) DarkScheme else LightScheme
    val primary = if (dark) Color(accent.dark) else Color(accent.light)
    // 琥珀亮度高，深色底上配白字对比不够，用深棕字
    val onPrimary = if (dark && accent == SettingsStore.AccentColor.AMBER) Color(0xFF2A1C00) else Color.White
    base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = lerp(base.surface, primary, if (dark) 0.30f else 0.16f),
        onPrimaryContainer = lerp(primary, if (dark) Color.White else Color.Black, if (dark) 0.72f else 0.78f),
    )
}

@Composable
fun TvHelperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: SettingsStore.AccentColor = SettingsStore.AccentColor.BLUE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accentScheme(darkTheme, accent)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
