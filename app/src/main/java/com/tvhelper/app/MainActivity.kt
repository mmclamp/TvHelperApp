package com.tvhelper.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvhelper.app.data.SettingsStore
import com.tvhelper.app.ui.connect.ConnectScreen
import com.tvhelper.app.ui.home.HomeScreen
import com.tvhelper.app.ui.install.InstallScreen
import com.tvhelper.app.ui.remote.RemoteScreen
import com.tvhelper.app.ui.settings.SettingsScreen
import com.tvhelper.app.ui.theme.TvHelperTheme
import com.tvhelper.app.vm.TvViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+ 通知权限：前台保活服务的常驻通知需要它才能显示
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppRoot()
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    REMOTE("遥控", Icons.Default.PlayArrow),
    INSTALL("安装", Icons.Default.Add),
    CONNECT("连接", Icons.Default.Search),
    SETTINGS("设置", Icons.Default.Settings);

    /** 不在底部栏显示，从首页「安装 APK」按钮进入 */
    val showInBar: Boolean get() = this != INSTALL
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as TvHelperApp

    val vm: TvViewModel = viewModel(
        factory = TvViewModel.factory(app.container.repository, context)
    )

    // 深浅色：设置项 > 跟随系统
    val themeMode by vm.themeMode.collectAsState()
    val darkTheme = when (themeMode) {
        SettingsStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        SettingsStore.ThemeMode.LIGHT -> false
        SettingsStore.ThemeMode.DARK -> true
    }

    // 回前台（热启动）时若已断线且开启了自动连接，直接重连，不跳连接页
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.tryAutoConnect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TvHelperTheme(darkTheme = darkTheme) {
        AppContent(vm)
    }
    PairingDialog(vm)
}

@Composable
private fun AppContent(vm: TvViewModel) {
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    // Notice → Toast（一次性消费）
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        Toast.makeText(
            context,
            m.text,
            if (m.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
        vm.consumeMessage()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.filter { it.showInBar }.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    vm = vm,
                    onGoConnect = { tab = Tab.CONNECT },
                    onGoInstall = { tab = Tab.INSTALL },
                )
                Tab.REMOTE -> RemoteScreen(vm = vm)
                Tab.INSTALL -> InstallScreen(vm = vm)
                Tab.CONNECT -> ConnectScreen(vm = vm)
                Tab.SETTINGS -> SettingsScreen(vm = vm)
            }
        }
    }
}

/**
 * AirKan 授权配对弹窗：电视收到 requestAuth 后会显示 6 位授权码，
 * 用户在此输入后完成密钥交换，启用 RC/UDT 加密通道。
 */
@Composable
private fun PairingDialog(vm: TvViewModel) {
    val pairing by vm.pairingState.collectAsState()
    when (pairing.phase) {
        com.tvhelper.app.data.PairingPhase.AWAITING_CODE,
        com.tvhelper.app.data.PairingPhase.COMPLETING,
        com.tvhelper.app.data.PairingPhase.FAILED,
        -> {
            var code by rememberSaveable(pairing.phase) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.dismissPairing() },
                title = { Text("电视授权配对") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            when (pairing.phase) {
                                com.tvhelper.app.data.PairingPhase.AWAITING_CODE ->
                                    "电视屏幕上正在显示 6 位授权码，请在下方输入："
                                com.tvhelper.app.data.PairingPhase.COMPLETING ->
                                    "正在完成配对…"
                                else ->
                                    pairing.error ?: "配对失败"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (pairing.phase == com.tvhelper.app.data.PairingPhase.AWAITING_CODE) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("6 位授权码") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (pairing.phase == com.tvhelper.app.data.PairingPhase.AWAITING_CODE) {
                        androidx.compose.material3.TextButton(
                            onClick = { vm.submitPairingCode(code) },
                            enabled = code.length == 6,
                        ) { Text("确认") }
                    } else if (pairing.phase == com.tvhelper.app.data.PairingPhase.FAILED) {
                        androidx.compose.material3.TextButton(onClick = { vm.retryPairing() }) {
                            Text("重试")
                        }
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.dismissPairing() }) {
                        Text("取消")
                    }
                },
            )
        }
        else -> Unit
    }
}
