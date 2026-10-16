package com.tvhelper.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvhelper.app.ui.connect.ConnectScreen
import com.tvhelper.app.ui.home.HomeScreen
import com.tvhelper.app.ui.install.InstallScreen
import com.tvhelper.app.ui.remote.RemoteScreen
import com.tvhelper.app.ui.theme.TvHelperTheme
import com.tvhelper.app.vm.TvViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TvHelperTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    REMOTE("遥控", Icons.Default.PlayArrow),
    INSTALL("安装", Icons.Default.Add),
    CONNECT("连接", Icons.Default.Search),
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as TvHelperApp

    val vm: TvViewModel = viewModel(
        factory = TvViewModel.factory(app.container.repository, context)
    )

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
                Tab.entries.forEach { t ->
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
                Tab.HOME -> HomeScreen(vm = vm, onGoConnect = { tab = Tab.CONNECT })
                Tab.REMOTE -> RemoteScreen(vm = vm)
                Tab.INSTALL -> InstallScreen(vm = vm)
                Tab.CONNECT -> ConnectScreen(vm = vm)
            }
        }
    }

    PairingDialog(vm)
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
