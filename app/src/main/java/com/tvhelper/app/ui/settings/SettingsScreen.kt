package com.tvhelper.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvhelper.app.BuildConfig
import com.tvhelper.app.data.SettingsStore
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.vm.TvViewModel

/** 设置页：外观（深浅色）+ 连接（自动连接） */
@Composable
fun SettingsScreen(vm: TvViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val accent by vm.accentColor.collectAsState()
    val autoConnect by vm.autoConnect.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // ---- 外观
        SectionLabel("外观")
        TvCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("深浅色模式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsStore.ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text("强调色", style = MaterialTheme.typography.titleSmall)
                AccentPicker(selected = accent, onSelect = { vm.setAccentColor(it) })
            }
        }

        // ---- 连接
        SectionLabel("连接")
        TvCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动连接上次电视", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "打开应用时自动连接最近一次成功连接的电视",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoConnect, onCheckedChange = { vm.setAutoConnect(it) })
            }
        }

        // ---- 后台保活
        SectionLabel("后台保活")
        BatteryWhitelistCard()

        // ---- 关于
        SectionLabel("关于")
        TvCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingRow("版本", BuildConfig.VERSION_NAME)
                SettingRow("协议", "AirKan / milink UDT（加密通道）")
                SettingRow("支持设备", "小米 / 红米 MIUI TV")
            }
        }
    }
}

/** 强调色色板：圆形色块，选中画勾 + 描边 */
@Composable
private fun AccentPicker(
    selected: SettingsStore.AccentColor,
    onSelect: (SettingsStore.AccentColor) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsStore.AccentColor.entries.forEach { c ->
            val isSel = selected == c
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(c.dark))
                    .border(
                        width = if (isSel) 2.5.dp else 1.dp,
                        color = if (isSel) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSel) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = c.label,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 后台保活：申请忽略电池优化。ColorOS 等会把未加白的应用进程冻结，掐断所有连接 */
@Composable
private fun BatteryWhitelistCard() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    val ignoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !ignoring) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (ignoring) "后台保活：已开启" else "申请忽略电池优化",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (ignoring) {
                    "已加入电池优化白名单，息屏后连接更稳定"
                } else {
                    "点按弹窗选择「允许」，否则息屏后系统会冻结应用断开连接"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
