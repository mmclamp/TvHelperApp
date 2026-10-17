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
import com.tvhelper.app.protocol.rc.TvKey
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
    val tvVolume: StateFlow<Int?> = repo.tvVolume
    val tvVolumeMax: StateFlow<Int> = repo.tvVolumeMax
    val tvMuted: StateFlow<Boolean?> = repo.tvMuted

    val pinnedApps: StateFlow<List<PinnedApp>> =
        repo.pinnedApps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val autoConnect: StateFlow<Boolean> =
        repo.autoConnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themeMode: StateFlow<SettingsStore.ThemeMode> =
        repo.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.ThemeMode.SYSTEM)

    val accentColor: StateFlow<SettingsStore.AccentColor> =
        repo.accentColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.AccentColor.BLUE)

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
        // 重连成功后兜底释放卡键：长按期间连接断开会导致 UP 帧发不出去，
        // 电视端按键一直处于按下状态，输入系统被楔死（物理遥控器也失灵）
        viewModelScope.launch {
            var wasRcConnected = false
            repo.connection.collect { st ->
                if (st.rcConnected && !wasRcConnected && repeatKeyCode != null) {
                    releaseRepeatedKey()
                }
                wasRcConnected = st.rcConnected
            }
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

    fun setAccentColor(color: SettingsStore.AccentColor) {
        viewModelScope.launch { repo.setAccentColor(color) }
    }

    fun forgetDevice(device: com.tvhelper.app.data.TvDevice) {
        repo.forgetDevice(device.id)
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

    /** 长按中（等待松手发 UP）的按键码与按下时刻；keyCode null = 无长按 */
    private var repeatKeyCode: Int? = null
    private var repeatDownTime: Long = 0L

    /**
     * 长按连发：对齐物理遥控器的标准 repeat 模式——
     * 按下发一次 DOWN(repeat=0)，之后每 180ms 发一次 DOWN（repeatCount 递增），
     * 松手时补一次 UP。UP 帧必须复用按下的 downTime（电视端按 downTime 配对 DOWN/UP，
     * 配不上会造成按键卡死、电视输入系统被楔死），eventTime 用松手时刻。
     * 任一帧发送失败立即停止，避免失败后继续轰炸。
     */
    fun startKeyRepeat(keyCode: Int) {
        stopKeyRepeat()
        repeatKeyCode = keyCode
        repeatJob = viewModelScope.launch {
            val downTime = android.os.SystemClock.uptimeMillis()
            repeatDownTime = downTime
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
        releaseRepeatedKey()
    }

    private var volumeJob: Job? = null

    /**
     * 音量单步调节：原版 SocialRequest.setVolume 走 UDT 通道直接设绝对值（逆向确认，
     * RC 按键/播放控制帧电视都不理）。每次点击 ±1，不做长按连发。
     * 电视响应到达后回填真实值（Repository 内 NonCancellable，不会丢响应结论）。
     */
    fun adjustVolume(up: Boolean) {
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            val max = repo.tvVolumeMax.value
            // 以缓存里的电视真实值为基准，自校准防漂移
            val base = repo.tvVolume.value ?: (max / 2)
            val v = if (up) minOf(base + 1, max) else maxOf(base - 1, 0)
            repo.setVolume(v)
        }
    }

    /**
     * 切换静音：Social setMute 电视实测不支持，走 RC MUTE 键（实测有效）。
     * 状态由 Repository 本地跟踪（调音量自动视为解除静音）。
     */
    fun toggleMute() {
        viewModelScope.launch {
            repo.toggleMuteKey()
        }
    }

    /** 补发 UP 帧释放长按；失败（连接断开等）自动重试，重连成功后再兜底一次 */
    private fun releaseRepeatedKey(attempt: Int = 0) {
        val code = repeatKeyCode ?: return
        val downTime = repeatDownTime
        repeatKeyCode = null
        viewModelScope.launch {
            val now = android.os.SystemClock.uptimeMillis()
            val ok = repo.sendKeyUp(code, downTime, eventTime = now)
            if (!ok && attempt < 3) {
                delay(500)
                repeatKeyCode = code
                repeatDownTime = downTime
                releaseRepeatedKey(attempt + 1)
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
