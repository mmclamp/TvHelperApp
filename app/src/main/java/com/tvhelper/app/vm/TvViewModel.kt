package com.tvhelper.app.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tvhelper.app.data.ConnectionPhase
import com.tvhelper.app.data.ConnectionState
import com.tvhelper.app.data.InstallState
import com.tvhelper.app.data.Notice
import com.tvhelper.app.data.PairingState
import com.tvhelper.app.data.PinnedApp
import com.tvhelper.app.data.SettingsStore
import com.tvhelper.app.data.TvDevice
import com.tvhelper.app.data.TvRepository
import com.tvhelper.app.data.toPinned
import com.tvhelper.app.net.DiscoveredDevice
import com.tvhelper.app.net.MdnsDiscovery
import com.tvhelper.app.net.NetUtils
import com.tvhelper.app.net.TvApi
import com.tvhelper.app.protocol.rc.RcKeyCodec
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.udt.RemoteApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvViewModel(
    private val repo: TvRepository,
    private val appContext: Context,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = repo.connection
    val installedApps: StateFlow<List<RemoteApp>> = repo.installedApps
    val appsLoading: StateFlow<Boolean> = repo.appsLoading
    val installState: StateFlow<InstallState> = repo.install
    val pairingState: StateFlow<PairingState> = repo.pairing
    val appIcons: StateFlow<Map<String, ByteArray>> = repo.appIcons

    val pinnedApps: StateFlow<List<PinnedApp>> =
        repo.pinnedApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val autoConnect: StateFlow<Boolean> =
        repo.autoConnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themeMode: StateFlow<SettingsStore.ThemeMode> =
        repo.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.ThemeMode.SYSTEM)

    val knownDevices: StateFlow<List<TvDevice>> =
        repo.knownDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastDevice: StateFlow<TvDevice?> =
        repo.lastDevice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------- 扫描

    private val _scanResults = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredDevice>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    // ---------------- 提示

    private val _message = MutableStateFlow<Notice?>(null)
    val message: StateFlow<Notice?> = _message.asStateFlow()

    /** 本机局域网 IP。枚举网卡涉及系统调用，放到 IO 线程算完再回填，避免主线程抖动 */
    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    init {
        viewModelScope.launch {
            repo.notices.collect { _message.value = it }
        }
        viewModelScope.launch {
            _localIp.value = withContext(Dispatchers.IO) { NetUtils.localIpv4() }
        }
        // 自动连接：勾选了开关且记住了上次设备（force=false 防止与 ON_RESUME 重入并发建连）
        viewModelScope.launch {
            val target = repo.autoConnectTarget() ?: return@launch
            repo.connect(target, force = false)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _scanResults.value = emptyList()
        scanJob = viewModelScope.launch {
            runCatching {
                MdnsDiscovery(appContext).discover().collect { d ->
                    _scanResults.value = (_scanResults.value.filterNot { it.host == d.host } + d)
                }
            }.onFailure {
                _message.value = Notice("mDNS 发现失败：${it.message}", true)
            }
        }
        // 15s 自动停
        viewModelScope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    // ---------------- 连接

    fun connect(device: TvDevice) {
        viewModelScope.launch {
            stopScan()
            repo.connect(device)
        }
    }

    fun connectDiscovered(d: DiscoveredDevice) {
        connect(repo.deviceFrom(d))
    }

    fun connectManualIp(ip: String) {
        viewModelScope.launch {
            stopScan()
            repo.probeAndConnect(ip.trim())
        }
    }

    fun disconnect() {
        repo.disconnect()
    }

    fun setAutoConnect(enabled: Boolean) {
        repo.setAutoConnect(enabled)
    }

    /**
     * 回前台/热启动时的自动连接：未连接且记住了设备时直接重连，
     * 不跳转连接页。连接中或已连接时为空操作。
     */
    fun tryAutoConnect() {
        viewModelScope.launch {
            val state = repo.connection.value
            if (state.isConnected || state.phase == ConnectionPhase.CONNECTING) return@launch
            val target = repo.autoConnectTarget() ?: return@launch
            // force=false：与 init 自动连接并发时，谁先建连成功谁生效，后到者直接跳过
            repo.connect(target, force = false)
        }
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    // ---------------- 应用图标

    fun ensureIcon(pkg: String) {
        repo.ensureIcon(pkg)
    }

    // ---------------- 授权配对

    fun submitPairingCode(code: String) {
        repo.submitPairingCode(code)
    }

    fun dismissPairing() {
        repo.dismissPairing()
    }

    fun retryPairing() {
        repo.retryPairing()
    }

    // ---------------- 遥控

    fun sendKey(keyCode: Int) {
        viewModelScope.launch { repo.sendKey(keyCode) }
    }

    private var repeatJob: Job? = null

    /** 长按中（等待松手发 UP）的按键码；null = 无长按 */
    private var repeatKeyCode: Int? = null

    /**
     * 长按连发：对齐物理遥控器的标准 repeat 模式——
     * 按下发一次 DOWN(repeat=0)，之后每 180ms 发一次 DOWN（repeatCount 递增），
     * 松手时补一次 UP。相比旧实现（每 90ms 发完整 DOWN+UP 对）不再洪泛
     * 电视端输入队列（洪泛会导致电视输入系统卡死，物理遥控器也失灵）。
     * 任一帧发送失败立即停止，避免失败后继续轰炸。
     */
    fun startKeyRepeat(keyCode: Int) {
        stopKeyRepeat()
        repeatKeyCode = keyCode
        repeatJob = viewModelScope.launch {
            val downTime = android.os.SystemClock.uptimeMillis()
            if (!repo.sendKeyDown(keyCode, downTime, repeatCount = 0, eventTime = downTime)) {
                repeatKeyCode = null
                return@launch
            }
            var repeat = 1
            while (isActive) {
                delay(180)
                val now = android.os.SystemClock.uptimeMillis()
                if (!repo.sendKeyDown(keyCode, downTime, repeatCount = repeat, eventTime = now)) break
                repeat++
            }
        }
    }

    fun stopKeyRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        repeatKeyCode?.let { code ->
            repeatKeyCode = null
            viewModelScope.launch {
                repo.sendKeyUp(code, downTime = android.os.SystemClock.uptimeMillis())
            }
        }
    }

    fun sendTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        viewModelScope.launch {
            repo.sendTouch(
                RcTouchCodec.TouchEvent(
                    action = action,
                    downTime = downTime,
                    eventTime = eventTime,
                    pointers = listOf(RcTouchCodec.TouchPointer(x = x, y = y)),
                )
            )
        }
    }

    // ---------------- 应用管理

    fun refreshApps() {
        viewModelScope.launch { repo.refreshInstalledApps() }
    }

    fun openApp(app: RemoteApp) {
        viewModelScope.launch { repo.openApp(app.packageName, app.displayName) }
    }

    fun openPinned(app: PinnedApp) {
        viewModelScope.launch { repo.openApp(app.packageName, app.displayName) }
    }

    fun togglePin(app: RemoteApp) {
        viewModelScope.launch {
            val added = repo.togglePin(app.toPinned())
            _message.value = Notice(
                if (added) "已固定到首页：${app.displayName}" else "已取消固定：${app.displayName}"
            )
        }
    }

    fun togglePin(app: PinnedApp) {
        viewModelScope.launch {
            val added = repo.togglePin(app)
            _message.value = Notice(
                if (added) "已固定到首页：${app.displayName}" else "已取消固定：${app.displayName}"
            )
        }
    }

    fun movePin(from: Int, to: Int) {
        viewModelScope.launch { repo.movePin(from, to) }
    }

    fun uninstallApp(app: RemoteApp) {
        viewModelScope.launch { repo.uninstallApp(app.packageName) }
    }

    // ---------------- 安装

    fun installApk(uri: Uri, displayName: String?) {
        viewModelScope.launch { repo.installApk(uri, displayName) }
    }

    fun resetInstall() = repo.resetInstallState()

    companion object {
        fun factory(repo: TvRepository, context: Context): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { TvViewModel(repo, context.applicationContext) }
            }
    }
}
