package com.tvhelper.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tvhelper.app.apkserver.ApkHttpServer
import com.tvhelper.app.net.AirkanAuthClient
import com.tvhelper.app.net.AirkanAuthException
import com.tvhelper.app.protocol.airkan.AirkanCrypto
import com.tvhelper.app.net.DiscoveredDevice
import com.tvhelper.app.net.IpProbe
import com.tvhelper.app.net.NetUtils
import com.tvhelper.app.net.RcClient
import com.tvhelper.app.net.RcEvent
import com.tvhelper.app.net.RcException
import com.tvhelper.app.net.TvApi
import com.tvhelper.app.net.UdtClient
import com.tvhelper.app.net.UdtEncryption
import com.tvhelper.app.net.UdtException
import com.tvhelper.app.net.UdtPush
import com.tvhelper.app.net.RequestAuthResult
import com.tvhelper.app.protocol.rc.RcKeyCodec
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.RcVersionCodec
import com.tvhelper.app.protocol.udt.RemoteApp
import com.tvhelper.app.service.ConnectionService
import com.tvhelper.app.data.PairingPhase
import com.tvhelper.app.data.PairingState
import com.tvhelper.app.data.SettingsStore
import com.tvhelper.app.data.TvPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局电视会话编排：连接、遥控、应用管理、APK 推送。
 *
 * 连接策略（对齐原实现的双通道）：
 * 1. RC 通道（30612）必须成功 —— 遥控与心跳依赖它
 * 2. UDT 通道（6093）尽力而为 —— 应用管理依赖它，失败不影响遥控
 */
class TvRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val pairingStore: PairingStore,
) {

    companion object {
        private const val TAG = "TvRepository"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var rc: RcClient? = null
    private var udt: UdtClient? = null
    private var api: TvApi? = null
    private var eventJob: Job? = null

    /** 用户主动断开时不做自动重连 */
    @Volatile
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val reconnectBackoffMs = longArrayOf(2_000L, 5_000L, 10_000L)

    val apkServer = ApkHttpServer()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection

    private val _installedApps = MutableStateFlow<List<RemoteApp>>(emptyList())
    val installedApps: StateFlow<List<RemoteApp>> = _installedApps

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 32)
    val notices: SharedFlow<Notice> = _notices

    private val _pairing = MutableStateFlow(PairingState())
    val pairing: StateFlow<PairingState> = _pairing

    /** 电视应用图标缓存（pkg → PNG 字节），懒加载 + 磁盘缓存 */
    private val _appIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val appIcons: StateFlow<Map<String, ByteArray>> = _appIcons

    /** 当前挂起的 requestAuth 结果（completeAuth 时使用） */
    private var pendingAuth: RequestAuthResult? = null

    val pinnedApps: Flow<List<PinnedApp>> = settings.pinnedApps
    val knownDevices: Flow<List<TvDevice>> = settings.knownDevices
    val lastDevice: Flow<TvDevice?> = settings.lastDevice
    val autoConnect: Flow<Boolean> = settings.autoConnect

    val themeMode: Flow<SettingsStore.ThemeMode> = settings.themeMode

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        scope.launch { settings.setThemeMode(mode) }
    }

    /** 自动连接：勾选了开关且记住了上次设备时返回该设备 */
    suspend fun autoConnectTarget(): TvDevice? {
        if (!settings.autoConnectOnce()) return null
        return settings.lastDevice.first()
    }

    // ------------------------------------------------------------ 连接

    fun deviceFrom(d: DiscoveredDevice) = TvDevice(
        id = d.host,
        name = d.name,
        host = d.host,
        rcPort = d.rcPort,
        udtPort = d.udtPort,
        model = d.model,
    )

    /**
     * 手输 IP：先探测端口，再握手。
     *
     * 全程在 IO 线程执行 —— 网络操作不能碰主线程。
     */
    suspend fun probeAndConnect(ip: String): Boolean = withContext(Dispatchers.IO) {
        val target = ip.trim()
        if (!NetUtils.isValidIpv4(target)) {
            notice("IP 格式不正确：$ip", true)
            return@withContext false
        }
        val local = NetUtils.localIpv4()
        if (!NetUtils.sameSubnet(local, target)) {
            notice("提示：手机($local)与输入 IP 不在同一网段，可能连不上", false)
        }
        _connection.value = ConnectionState(
            phase = ConnectionPhase.CONNECTING,
            message = "正在探测 $target ...",
        )

        val candidates = (IpProbe.RC_PORT_FALLBACKS + IpProbe.UDT_PORT_FALLBACKS).distinct()
        val open = IpProbe.openPorts(target, candidates)
        if (open.isEmpty()) {
            val msg = "电视不可达：端口 ${candidates.joinToString(" / ")} 均连不上。" +
                "请确认电视已开机、与手机在同一 Wi-Fi，且路由器未开启「AP 隔离 / 设备隔离」"
            _connection.value = ConnectionState(phase = ConnectionPhase.FAILED, message = msg)
            notice(msg, true)
            return@withContext false
        }
        Log.i(TAG, "$target 开放端口：$open")

        // 用真正探测到的端口，而不是硬编码默认值
        val rcPort = IpProbe.RC_PORT_FALLBACKS.firstOrNull { it in open } ?: IpProbe.PORT_RC
        val udtPort = IpProbe.UDT_PORT_FALLBACKS.firstOrNull { it in open } ?: IpProbe.PORT_UDT
        connect(
            TvDevice(
                id = target,
                name = "电视 $target",
                host = target,
                rcPort = rcPort,
                udtPort = udtPort,
                model = "开放端口 ${open.joinToString(",")}",
            )
        )
    }

    /** 串行化连接：防止 init 自动连接与 ON_RESUME 重入并发建双套通道 */
    private val connectMutex = Mutex()

    suspend fun connect(device: TvDevice, force: Boolean = true): Boolean =
        connectMutex.withLock {
            withContext(Dispatchers.IO) {
                val st = _connection.value
                if (!force) {
                    // 非强制（自动重连/自动连接场景）：已连接或正在建连都直接跳过，
                    // 避免后到者把刚建好的连接 kill 掉（日志里「连接成功 1s 后 Socket closed」的元凶）
                    if (st.isConnected || st.phase == ConnectionPhase.CONNECTING) return@withContext true
                }
                connectInternal(device)
            }
        }

    private suspend fun connectInternal(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        userDisconnected = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal()
        val existingPairing = pairingStore.pairingFor(device.host)
        _pairing.value = PairingState(knownPaired = existingPairing != null)
        _connection.value = ConnectionState(
            phase = ConnectionPhase.CONNECTING,
            device = device,
            message = "正在连接 ${device.displayName} ...",
        )

        // ---- 1) RC 通道（必须成功）
        // 版本请求带 {"deviceId":"<publicKeyMd5>"} extra（h2.h.L），电视据此下发 secretKey
        val keyPair = pairingStore.ensureKeyPair()
        val rcClient = try {
            connectRcWithFallback(device.host, device.rcPort, keyPair.publicKeyMd5)
        } catch (t: Throwable) {
            _connection.value = ConnectionState(
                phase = ConnectionPhase.FAILED,
                device = device,
                message = t.message ?: "RC 通道连接失败",
            )
            notice("连接失败：${t.message}", true)
            return@withContext false
        }
        rc = rcClient
        eventJob = scope.launch {
            rcClient.events.collect { ev ->
                when (ev) {
                    is RcEvent.ServerHeartbeat ->
                        _connection.value = _connection.value.copy(lastHeartbeatAt = System.currentTimeMillis())
                    is RcEvent.Closed -> {
                        if (userDisconnected) return@collect
                        // 旧客户端的 Closed 事件（已被新连接取代）不处理，防止重连风暴
                        if (rcClient !== rc) return@collect
                        // RC socket 被系统/电视断开：自动重连（退避 2s/5s/10s…永不放弃）
                        scheduleReconnect(device, ev.cause)
                    }
                    else -> Unit
                }
            }
        }

        val version = rcClient.negotiated ?: RcVersionCodec.RcVersionResponse(
            RcVersionCodec.CODE_RESPONSE,
            RcVersionCodec.DEFAULT_VERSION,
            null,
            null,
        )
        // secretKey = RSA 加密的会话密钥，解出后 RC 进入加密模式
        var encrypted = false
        version.secretKey?.let { sk ->
            encrypted = rcClient.applySecretKey(sk, keyPair.privateKey)
        }
        if (!encrypted && existingPairing != null && version.needsCrypto) {
            // 已配对但首个响应没带密钥：重发版本请求再等一次
            val resp = rcClient.resendVersionRequestAndWait()
            resp?.secretKey?.let { sk -> encrypted = rcClient.applySecretKey(sk, keyPair.privateKey) }
        }

        _connection.value = ConnectionState(
            phase = ConnectionPhase.RC_READY,
            device = device,
            tvProtocolVersion = version.version,
            encrypted = encrypted,
            rcConnected = true,
            message = if (encrypted) "遥控通道已就绪（加密）" else "遥控通道已就绪",
            lastHeartbeatAt = System.currentTimeMillis(),
        )

        // ---- 2) UDT 通道（尽力而为）
        val udtClient = try {
            connectUdtWithFallback(device.host, device.udtPort)
        } catch (t: Throwable) {
            Log.w(TAG, "UDT 连接失败：${t.message}")
            null
        }
        if (udtClient != null) {
            if (encrypted && existingPairing != null) {
                udtClient.setEncryption(
                    UdtEncryption(
                        tvAuthPublicKey = existingPairing.tvAuthPublicKey,
                        ourDeviceId = keyPair.publicKeyMd5,
                        cbcKey = existingPairing.cbcKey,
                        cbcIv = existingPairing.cbcIv,
                    )
                )
            }
            udt = udtClient
            api = TvApi(udtClient)
            scope.launch {
                udtClient.pushes.collect { push -> handlePush(push) }
            }
            _connection.value = _connection.value.copy(
                phase = ConnectionPhase.READY,
                udtConnected = true,
                message = if (encrypted) "已连接（加密通道）" else "已连接",
            )
        } else {
            _connection.value = _connection.value.copy(
                udtConnected = false,
                message = "遥控已就绪；应用管理不可用（UDT 6093 未连通）",
            )
        }

        settings.setLastDevice(device)
        notice("已连接 ${device.displayName}")
        ConnectionService.start(appContext)
        // ---- 连接成功自动刷新应用列表（静默，失败不打扰用户）
        if (udtClient != null) {
            runCatching { refreshInstalledApps(quiet = true) }
        }

        // ---- 3) 固件要求加密但还没配对：自动发起授权配对（电视会弹 6 位授权码）
        if (!encrypted && version.needsCrypto && existingPairing == null) {
            notice("该电视固件要求加密通道，正在发起授权配对…")
            startPairingInternal(device.host)
        }
        true
    }

    /**
     * 依次尝试 RC 候选端口。
     *
     * 端口来源见 [IpProbe.rcCandidates]：优先 mDNS `_rc._tcp` 广播的端口，
     * 失败再回落 30612 / 6091。全部失败才抛错。
     */
    private suspend fun connectRcWithFallback(host: String, advertised: Int, deviceId: String?): RcClient {
        val candidates = IpProbe.rcCandidates(advertised)
        var last: Throwable? = null
        for (port in candidates) {
            val client = RcClient(host, port, deviceId = deviceId)
            try {
                client.connect()
                if (port != advertised) Log.i(TAG, "RC 通道回落到端口 $port（广播端口 $advertised 不可用）")
                return client
            } catch (t: Throwable) {
                client.shutdown()
                last = t
                Log.w(TAG, "RC 端口 $port 不可用：${t.message}")
            }
        }
        throw RcException(
            "电视 $host 的遥控通道不可达（已尝试端口 ${candidates.joinToString(" / ")}）：" +
                (last?.message ?: "未知错误"),
            last,
        )
    }

    /** 依次尝试 UDT 候选端口 */
    private suspend fun connectUdtWithFallback(host: String, advertised: Int): UdtClient {
        val candidates = IpProbe.udtCandidates(advertised)
        var last: Throwable? = null
        for (port in candidates) {
            val client = UdtClient(host, port)
            try {
                client.connect()
                if (port != advertised) Log.i(TAG, "UDT 通道回落到端口 $port")
                return client
            } catch (t: Throwable) {
                client.shutdown()
                last = t
                Log.w(TAG, "UDT 端口 $port 不可用：${t.message}")
            }
        }
        throw UdtException(
            "UDT 通道不可达（已尝试端口 ${candidates.joinToString(" / ")}）",
            last,
        )
    }

    /**
     * RC 断线后的自动重连：指数退避（2s/5s/10s 封顶），永不放弃——
     * 存活持久化：只要未主动断开就持续重试，直到连上或用户主动断开。
     */
    private fun scheduleReconnect(device: TvDevice, cause: Throwable? = null) {
        Log.i(TAG, "RC 断线 cause=${cause?.javaClass?.simpleName}: ${cause?.message}")
        val attempt = ++reconnectAttempts
        val backoff = reconnectBackoffMs[minOf(attempt - 1, reconnectBackoffMs.size - 1)]
        Log.i(TAG, "RC 断线，${backoff / 1000}s 后自动重连（第 $attempt 次）")
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.CONNECTING,
            rcConnected = false,
            message = "连接断开，正在自动重连（第 $attempt 次）…",
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            if (userDisconnected) return@launch
            // 先清空引用再连接：connectInternal 会 cancel reconnectJob，
            // 不清空会把自己 cancel 掉（日志「自动重连失败：StandaloneCoroutine was cancelled」）
            reconnectJob = null
            runCatching { connect(device, force = false) }
                .onFailure { Log.w(TAG, "自动重连失败：${it.message}") }
        }
    }

    private fun handlePush(push: UdtPush) {
        Log.i(TAG, "收到电视推送：${push.methodName}")
    }

    // ------------------------------------------------------------ AirKan 授权配对

    /**
     * 发起授权配对：调 requestAuth，电视屏幕会显示 6 位授权码，
     * 之后用户在弹窗中输入，由 [submitPairingCode] 完成配对。
     */
    fun startPairing() {
        val host = _connection.value.device?.host ?: _pairing.value.host ?: return
        scope.launch { startPairingInternal(host) }
    }

    private suspend fun startPairingInternal(host: String) {
        val kp = pairingStore.ensureKeyPair()
        _pairing.value = PairingState(
            phase = PairingPhase.AWAITING_CODE,
            host = host,
            knownPaired = true,
        )
        try {
            val auth = try {
                AirkanAuthClient.requestAuth(host, kp.publicKeyMd5)
            } catch (e: AirkanAuthException) {
                if (e.code == 60006) {
                    // 电视上有别的挂起会话，取消后重试
                    AirkanAuthClient.cancelAuth(host, kp.publicKeyMd5)
                    delay(800)
                    AirkanAuthClient.requestAuth(host, kp.publicKeyMd5)
                } else {
                    throw e
                }
            }
            if (auth.publicKeyBlob == null || auth.verifyCodeAdditional == null) {
                // 已配对过（无需再次授权）：直接重协商
                pendingAuth = null
                _pairing.value = _pairing.value.copy(phase = PairingPhase.PAIRED)
                notice("电视侧已有配对记录，正在启用加密通道…")
                enableEncryption(host)
            } else {
                pendingAuth = auth
                notice("请在电视上查看 6 位授权码并输入")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestAuth 失败：${t.message}")
            _pairing.value = PairingState(
                phase = PairingPhase.FAILED,
                host = host,
                error = t.message ?: "发起配对失败",
            )
            notice("发起配对失败：${t.message}", true)
        }
    }

    /** 用户输入电视上的 6 位授权码后回调 */
    fun submitPairingCode(code: String) {
        val state = _pairing.value
        val host = state.host ?: return
        val auth = pendingAuth
        if (auth == null) {
            _pairing.value = state.copy(phase = PairingPhase.FAILED, error = "无挂起的配对会话")
            return
        }
        _pairing.value = state.copy(phase = PairingPhase.COMPLETING)
        scope.launch {
            try {
                val kp = pairingStore.ensureKeyPair()
                val result = AirkanAuthClient.completeAuth(
                    host = host,
                    deviceId = kp.publicKeyMd5,
                    ourPublicKeyCustomB64 = kp.publicKey,
                    blobEncrypted = auth.publicKeyBlob!!,
                    verifyCodeAdditional = auth.verifyCodeAdditional!!,
                    code = code.trim(),
                )
                pairingStore.savePairing(
                    host,
                    TvPairing(
                        deviceId = kp.publicKeyMd5,
                        tvId = result.tvId,
                        tvAuthPublicKey = result.tvAuthPublicKey,
                        cbcKey = result.cbcKey,
                        cbcIv = result.cbcIv,
                    ),
                )
                pendingAuth = null
                _pairing.value = PairingState(PairingPhase.PAIRED, host, knownPaired = true)
                notice("配对成功，正在启用加密通道…")
                enableEncryption(host)
            } catch (t: Throwable) {
                Log.w(TAG, "completeAuth 失败：${t.message}")
                _pairing.value = _pairing.value.copy(
                    phase = PairingPhase.FAILED,
                    error = t.message ?: "配对失败",
                )
                notice("配对失败：${t.message}", true)
            }
        }
    }

    fun dismissPairing() {
        pendingAuth = null
        _pairing.value = PairingState()
    }

    /** 重试配对（失败后） */
    fun retryPairing() {
        val host = _connection.value.device?.host ?: _pairing.value.host ?: return
        scope.launch { startPairingInternal(host) }
    }

    /** 配对成功后启用两条通道的加密 */
    private suspend fun enableEncryption(host: String) {
        val keyPair = pairingStore.ensureKeyPair()
        val pairingInfo = pairingStore.pairingFor(host) ?: return

        // 1) RC：重发版本请求拿 secretKey → AES/ECB
        var rcEncrypted = false
        val rcClient = rc
        if (rcClient != null && rcClient.isConnected) {
            val resp = rcClient.resendVersionRequestAndWait()
            resp?.secretKey?.let { sk ->
                rcEncrypted = rcClient.applySecretKey(sk, keyPair.privateKey)
            }
        }

        // 2) UDT：重连并启用加密（旧连接按明文建立，电视可能已拒绝后续帧）
        val device = _connection.value.device
        if (device != null) {
            runCatching {
                udt?.shutdown()
                val udtClient = UdtClient(device.host, device.udtPort)
                udtClient.connect()
                udtClient.setEncryption(
                    UdtEncryption(
                        tvAuthPublicKey = pairingInfo.tvAuthPublicKey,
                        ourDeviceId = keyPair.publicKeyMd5,
                        cbcKey = pairingInfo.cbcKey,
                        cbcIv = pairingInfo.cbcIv,
                    )
                )
                udt = udtClient
                api = TvApi(udtClient)
                scope.launch { udtClient.pushes.collect { handlePush(it) } }
                _connection.value = _connection.value.copy(udtConnected = true)
            }.onFailure { Log.w(TAG, "UDT 重连失败：${it.message}") }
        }

        _connection.value = _connection.value.copy(
            encrypted = rcEncrypted,
            phase = if (_connection.value.udtConnected) ConnectionPhase.READY else ConnectionPhase.RC_READY,
            message = if (rcEncrypted) "已连接（加密通道）" else "已配对（加密待生效，建议重连）",
        )
        if (rcEncrypted) notice("加密通道已启用")
    }

    fun setAutoConnect(enabled: Boolean) {
        scope.launch { settings.setAutoConnect(enabled) }
    }

    // ------------------------------------------------------------ 应用图标

    private val iconDir: File by lazy { File(appContext.cacheDir, "tvicons").apply { mkdirs() } }
    private val iconInFlight = ConcurrentHashMap<String, Boolean>()
    private val iconSemaphore = Semaphore(4)

    /** 确保某个应用的图标已请求（命中内存/磁盘缓存直接回填，否则入队懒加载） */
    fun ensureIcon(pkg: String) {
        if (pkg.isEmpty()) return
        if (_appIcons.value.containsKey(pkg)) return
        val cached = File(iconDir, AirkanCrypto.md5Hex(pkg.toByteArray()))
        if (cached.exists() && cached.length() > 0) {
            runCatching {
                _appIcons.value = _appIcons.value + (pkg to cached.readBytes())
            }
            return
        }
        if (iconInFlight.putIfAbsent(pkg, true) != null) return
        scope.launch {
            iconSemaphore.withPermit {
                try {
                    val bytes = api?.getPackageIcon(pkg)
                    if (bytes != null && bytes.isNotEmpty()) {
                        runCatching { File(iconDir, AirkanCrypto.md5Hex(pkg.toByteArray())).writeBytes(bytes) }
                        _appIcons.value = _appIcons.value + (pkg to bytes)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "图标获取失败 $pkg：${t.message}")
                } finally {
                    iconInFlight.remove(pkg)
                }
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal()
        _connection.value = ConnectionState()
        ConnectionService.stop(appContext)
    }

    private fun disconnectInternal() {
        eventJob?.cancel()
        eventJob = null
        runCatching { rc?.shutdown() }
        runCatching { udt?.shutdown() }
        rc = null
        udt = null
        api = null
        _installedApps.value = emptyList()
    }

    // ------------------------------------------------------------ 遥控

    suspend fun sendKey(keyCode: Int): Boolean {
        val client = rc ?: run {
            notice("尚未连接电视", true)
            return false
        }
        return try {
            client.sendKey(keyCode)
            true
        } catch (t: Throwable) {
            notice("按键发送失败：${t.message}", true)
            false
        }
    }

    /** 长按连发：标准 repeat 模式 —— DOWN 帧 repeatCount 递增，松手发一次 UP */
    suspend fun sendKeyDown(keyCode: Int, downTime: Long, repeatCount: Int, eventTime: Long): Boolean {
        val client = rc ?: return false
        return try {
            client.sendKeyRawFrames(
                listOf(RcKeyCodec.frameDown(keyCode, downTime, eventTime = eventTime, repeatCount = repeatCount))
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "按键 DOWN 发送失败：${t.message}")
            false
        }
    }

    suspend fun sendKeyUp(keyCode: Int, downTime: Long, eventTime: Long = downTime + 120): Boolean {
        val client = rc ?: return false
        return try {
            client.sendKeyRawFrames(listOf(RcKeyCodec.frameUp(keyCode, downTime, eventTime = eventTime)))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "按键 UP 发送失败：${t.message}")
            false
        }
    }

    suspend fun sendTouch(event: RcTouchCodec.TouchEvent) {
        val client = rc ?: return
        runCatching { client.sendTouch(event) }
            .onFailure { Log.w(TAG, "触摸发送失败：${it.message}") }
    }

    // ------------------------------------------------------------ 应用管理

    suspend fun refreshInstalledApps(quiet: Boolean = false): Boolean {
        val a = api
        if (a == null) {
            if (!quiet) notice("应用管理需要 UDT 通道，当前不可用", true)
            return false
        }
        _appsLoading.value = true
        return try {
            val list = a.getInstalledPackages()
            _installedApps.value = list
            if (!quiet) notice("已读取 ${list.size} 个应用")
            true
        } catch (t: Throwable) {
            if (!quiet) notice("读取应用列表失败：${t.message}", true)
            false
        } finally {
            _appsLoading.value = false
        }
    }

    suspend fun openApp(pkg: String, label: String? = null): Boolean {
        val a = api
        if (a == null) {
            notice("打开应用需要 UDT 通道，当前不可用", true)
            return false
        }
        return try {
            val status = a.openApp(pkg)
            if (status == 0) {
                notice("已打开 ${label ?: pkg}")
                true
            } else {
                notice("电视返回 status=$status，未能打开 ${label ?: pkg}", true)
                false
            }
        } catch (t: Throwable) {
            notice("打开失败：${t.message}", true)
            false
        }
    }

    suspend fun togglePin(app: PinnedApp): Boolean = settings.togglePin(app)

    suspend fun movePin(from: Int, to: Int) = settings.movePin(from, to)

    suspend fun uninstallApp(pkg: String): Boolean {
        val a = api ?: return false
        return try {
            val status = a.uninstallApp(pkg)
            if (status == 0) {
                notice("已请求卸载 $pkg")
                refreshInstalledApps()
                true
            } else {
                notice("卸载失败 status=$status", true)
                false
            }
        } catch (t: Throwable) {
            notice("卸载失败：${t.message}", true)
            false
        }
    }

    // ------------------------------------------------------------ APK 推送安装

    /**
     * 把本地 APK 推到电视安装。
     *
     * 手机侧起 HTTP 服务托管 APK，再让电视通过 `installUrlApp(url)` 自行拉取。
     * 电视端会弹官方确认框，这是系统安全机制，无法跳过。
     */
    suspend fun installApk(source: Uri, displayName: String?): Boolean = withContext(Dispatchers.IO) {
        val a = api
        if (a == null) {
            _install.value = InstallState.Failed(displayName, "需要 UDT 通道，当前不可用")
            return@withContext false
        }
        val localIp = NetUtils.localIpv4()
        if (localIp == null) {
            _install.value = InstallState.Failed(displayName, "未获取到手机局域网 IP，请确认已连接 Wi-Fi")
            return@withContext false
        }

        val name = displayName?.takeIf { it.isNotBlank() } ?: "upload.apk"
        if (!name.lowercase().endsWith(".apk")) {
            _install.value = InstallState.Failed(name, "请选择 .apk 文件")
            return@withContext false
        }
        _install.value = InstallState.Preparing(name)

        // 1) 复制到缓存，便于托管与统计进度
        val local = try {
            val dir = File(appContext.cacheDir, "apkpush").apply { mkdirs() }
            val dst = File(dir, sanitize(name))
            appContext.contentResolver.openInputStream(source)?.use { ins ->
                dst.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw IllegalStateException("无法读取所选文件")
            dst
        } catch (t: Throwable) {
            _install.value = InstallState.Failed(name, "读取文件失败：${t.message}")
            return@withContext false
        }

        if (local.length() <= 0L) {
            _install.value = InstallState.Failed(name, "文件为空")
            return@withContext false
        }

        // 2) 起 HTTP 服务
        val boundPort = try {
            apkServer.clear()
            apkServer.start()
        } catch (t: Throwable) {
            _install.value = InstallState.Failed(name, "本地 HTTP 服务启动失败：${t.message}")
            return@withContext false
        }
        val path = apkServer.register("/apk/${apkServer.encodePath(local.name)}", local)
        val url = "http://$localIp:$boundPort$path"

        _install.value = InstallState.Pushing(name, url, 0L, local.length())

        // 3) 监听下载进度
        val progressJob = scope.launch {
            apkServer.bytesServed.collect { served ->
                val cur = _install.value
                if (cur is InstallState.Pushing) {
                    if (served <= 0L) return@collect
                    if (served >= cur.totalBytes) {
                        _install.value = InstallState.AwaitingConfirm(cur.fileName, served, cur.totalBytes)
                    } else {
                        _install.value = cur.copy(bytesServed = served)
                    }
                }
            }
        }

        // 4) 让电视拉取安装
        return@withContext try {
            val msg = a.installUrlApp(url, 180_000L)
            val status = msg.status ?: -1
            val served = apkServer.bytesServed.value
            if (status == 0) {
                _install.value = InstallState.Done(
                    name,
                    if (served > 0) "电视已下载 ${served / 1024} KB，请在电视上确认安装"
                    else "电视已受理，请在电视上确认安装",
                )
                notice("已推送安装：$name")
                true
            } else {
                val err = msg.errorMsg ?: "status=$status"
                _install.value = InstallState.Failed(name, "电视拒绝安装：$err")
                notice("安装被拒绝：$err", true)
                false
            }
        } catch (t: Throwable) {
            val served = apkServer.bytesServed.value
            val state = if (served > 0) {
                InstallState.Done(name, "已下载 ${served / 1024} KB 但未收到结果回执，请在电视上确认")
            } else {
                InstallState.Failed(name, t.message ?: "推送失败")
            }
            _install.value = state
            notice("安装流程结束：${t.message}", state is InstallState.Failed)
            state is InstallState.Done
        } finally {
            progressJob.cancel()
            // 安装确认后延迟关闭服务，避免电视还没拉完
            scope.launch {
                delay(20_000)
                if (apkServer.isRunning) apkServer.clear()
            }
        }
    }

    fun resetInstallState() {
        _install.value = InstallState.Idle
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")

    private suspend fun notice(text: String, isError: Boolean = false) {
        _notices.tryEmit(Notice(text, isError))
    }

    fun shutdown() {
        disconnectInternal()
        apkServer.stop()
        scope.cancel()
    }
}
