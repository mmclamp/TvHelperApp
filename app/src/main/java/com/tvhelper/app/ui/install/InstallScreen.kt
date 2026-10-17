package com.tvhelper.app.ui.install

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvhelper.app.data.InstallState
import com.tvhelper.app.ui.common.SectionTitle
import com.tvhelper.app.ui.common.StatusDot
import com.tvhelper.app.ui.common.TvCard
import com.tvhelper.app.ui.theme.TvColors
import com.tvhelper.app.vm.TvViewModel

@Composable
fun InstallScreen(vm: TvViewModel) {
    val connection by vm.connection.collectAsState()
    val state by vm.installState.collectAsState()
    val context = LocalContext.current

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var pickedSize by remember { mutableStateOf(0L) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            pickedName = queryDisplayName(context, uri)
            pickedSize = querySize(context, uri)
            vm.resetInstall()
        }
    }

    val udtReady = connection.udtConnected
    val busy = state is InstallState.Preparing || state is InstallState.Pushing

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TvCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connection.isConnected)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            connection.device?.displayName ?: "未连接电视",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (udtReady) "UDT 6093 可用" else "UDT 不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (udtReady) MaterialTheme.colorScheme.onSurfaceVariant else TvColors.warn,
                        )
                    }
                }
            }
        }

        item { SectionTitle("安装本地 APK") }

        item {
            TvCard {
                Column {
                    Text(
                        "原理：手机在局域网内起一个 HTTP 服务托管所选 APK，再通过 UDT 让电视自行下载安装。" +
                            "电视端会弹出官方确认框，属系统安全机制，需要你在电视上点确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            // 整块可点：最大区域不该是死的
                            .clickable(enabled = !busy) {
                                picker.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            }
                            .padding(14.dp),
                    ) {
                        Column {
                            Text(
                                pickedName ?: "未选择文件",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (pickedSize > 0) formatSize(pickedSize) else "点下方按钮从文件中选择 .apk",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                picker.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("选择 APK 文件") }

                        Button(
                            onClick = {
                                pickedUri?.let { vm.installApk(it, pickedName) }
                            },
                            enabled = pickedUri != null && !busy && udtReady,
                        ) { Text(if (busy) "推送中…" else "推送到电视") }
                    }

                    if (!udtReady && pickedUri != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "UDT 通道未连通，无法推送安装。请回到首页确认 6093 端口状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvColors.warn,
                        )
                    }
                }
            }
        }

        item { InstallProgressCard(state) }

        item {
            TvCard {
                Column {
                    Text("排查提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "· 手机与电视必须在同一网段（同一 Wi-Fi / 同一路由器）\n" +
                            "· 若电视端一直不弹确认框，检查电视是否开启了「安装未知来源应用」\n" +
                            "· 若长时间 0 字节，可能是电视侧安全软件拦截了局域网 HTTP，可改用 U 盘安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(state: InstallState) {
    when (state) {
        is InstallState.Idle -> Unit

        is InstallState.Preparing -> TvCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在准备 ${state.fileName}…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        is InstallState.Pushing -> TvCard {
            Column {
                Text(
                    "电视正在下载",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatSize(state.bytesServed)} / ${formatSize(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (com.tvhelper.app.BuildConfig.DEBUG) {
                    // 推送 URL 属调试信息，release 不展示
                    Text(
                        state.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is InstallState.AwaitingConfirm -> TvCard {
            Column {
                Text(
                    "下载完成，等待电视确认",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TvColors.warn,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "已推送 ${formatSize(state.bytesServed)}。请到电视上点「安装」完成最后一步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is InstallState.Done -> TvCard {
            Column {
                Text(
                    "已完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TvColors.online,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is InstallState.Failed -> TvCard {
            Column {
                Text(
                    "失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TvColors.danger,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()

private fun querySize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
    } ?: 0L
}.getOrDefault(0L)

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
