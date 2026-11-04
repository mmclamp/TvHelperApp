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
import com.tvhelper.app.net.MdnsDiscovery
import com.tvhelper.app.net.NetUtils
import com.tvhelper.app.net.PowerOn
import com.tvhelper.app.net.RcClient
import com.tvhelper.app.net.RcEvent
import com.tvhelper.app.net.RcException
import com.tvhelper.app.net.TvApi
import com.tvhelper.app.net.UdtClient
import com.tvhelper.app.net.UdtEncryption
import com.tvhelper.app.net.UdtException
import com.tvhelper.app.net.UdtPush
import com.tvhelper.app.net.RequestAuthResult
import com.tvhelper.app.protocol.rc.RcCodec
import com.tvhelper.app.protocol.rc.RcKeyCodec
import com.tvhelper.app.protocol.rc.RcPlayControlCodec
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.TvKey
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
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

        /** Social 服务（音量等）：原版 v9.c UDTClient{port=9093, appId=1026} */
        private const val SOCIAL_PORT = 9093
        private const val SOCIAL_APP_ID = 1026

        /** 红外电源是「切换键」：冷却窗口内重复点击直接忽略，避免刚点亮又被第二帧关掉。
         *  v1.8.26 日志实证曾在 3.7s 内连发两帧红外（21:42:57.840 / 21:43:01.497）。 */
        private const val IR_COOLDOWN_MS = 10_000L
        /** 红外发射后「开机中…」的展示时长；本机固件不提供 isScreenOn，无法自动确认亮屏 */
        private const val IR_WAKING_MS = 8_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var rc: RcClient? = null
    private var udt: UdtClient? = null
    private var api: TvApi? = null

    /** Social 服务通道（端口 9093 / appId 1026）：音量 getVolume/setVolume 专用 */
    private var socialUdt: UdtClient? = null
    private var socialApi: TvApi? = null
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

    /** 当前电视音量（播放控制通道查询/设置，非按键）；null = 未知 */
    private val _tvVolume = MutableStateFlow<Int?>(null)
    val tvVolume: StateFlow<Int?> = _tvVolume

    /** 电视端音量最大值（部分机型 50，默认 100） */
    private val _tvVolumeMax = MutableStateFlow(100)
    val tvVolumeMax: StateFlow<Int> = _tvVolumeMax

    /** 电视静音状态：连接时经 getMute 查询真实态（三态 Boolean?，null=未知）；RC MUTE 键切换本地跟踪；调音量自动解除静音 → 回填 false */
    private val _tvMuted = MutableStateFlow<Boolean?>(null)
    val tvMuted: StateFlow<Boolean?> = _tvMuted

    /** 电视网络可达性状态（电源键行为依据）：可达(REACHABLE) / 不可达(UNREACHABLE) / 唤醒中(WAKING)。可达≠亮屏，息屏态网络也通 */
    private val _tvPowerState = MutableStateFlow(TvPowerState.UNREACHABLE)
    val tvPowerState: StateFlow<TvPowerState> = _tvPowerState

    /** 电视屏幕是否点亮：true=亮屏 / false=息屏待机 / null=未知（查询不支持或尚未返回）。
     *  关键分流依据——息屏态网络仍通(REACHABLE)但注入键被固件静默丢弃，必须靠它决定用「电源」(注入) 还是「打开」(BLE/蓝牙唤醒)。 */
    private val _tvScreenOn = MutableStateFlow<Boolean?>(null)
    val tvScreenOn: StateFlow<Boolean?> = _tvScreenOn

    /** 最近一次成功连接的设备，作为「开机」指令的目标（息屏/关机后连接已断，据此重连） */
    private var lastConnectDevice: TvDevice? = null
    private var powerOnJob: Job? = null
    private var powerOnInstance: PowerOn? = null
    private var screenStateJob: Job? = null
    /** 上次成功发射红外电源码的时刻（切换键冷却去抖） */
    private var lastIrFireAt = 0L
    /** 连续拿不到 isScreenOn 的次数；≥2 判定本机固件不支持该 RPC，熔断轮询（进程级事实） */
    private var screenStatusFailures = 0

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

    val accentColor: Flow<SettingsStore.AccentColor> = settings.accentColor

    fun setAccentColor(color: SettingsStore.AccentColor) {
        scope.launch { settings.setAccentColor(color) }
    }

    fun forgetDevice(id: String) {
        scope.launch { settings.forgetDevice(id) }
    }

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
        wifiMac = d.wifiMac,
        btMac = d.btMac,
        wol = d.wol,
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

    /** 正在真实建连（防重入标志）。不能用 UI 的 phase==CONNECTING 判断：
     *  自动重连调度时也会把 phase 设为 CONNECTING，会把重连自己挡死。 */
    @Volatile
    private var building = false

    suspend fun connect(device: TvDevice, force: Boolean = true): Boolean =
        connectMutex.withLock {
            withContext(Dispatchers.IO) {
                val st = _connection.value
                if (!force) {
                    // 非强制（自动重连/自动连接场景）：已连接或正在真实建连都直接跳过，
                    // 避免后到者把刚建好的连接 kill 掉（日志里「连接成功 1s 后 Socket closed」的元凶）
                    if (st.isConnected || building) return@withContext true
                }
                building = true
                try {
                    val ok = connectInternal(device)
                    if (ok) reconnectAttempts = 0
                    ok
                } finally {
                    building = false
                }
            }
        }

    private suspend fun connectInternal(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        userDisconnected = false
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
        observeRc(rcClient, device)

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
        // 遥控通道就绪即视为电视亮屏、可遥控
        lastConnectDevice = device
        _tvPowerState.value = TvPowerState.REACHABLE
        // 启动屏幕状态轮询：区分亮屏/息屏，驱动电源键分流（注入 vs 唤醒）
        startScreenStatePolling()

        // ---- 2) UDT 通道（首连强制重建）
        buildUdt(device, encrypted, existingPairing, rebuild = true)

        settings.setLastDevice(device)
        notice("已连接 ${device.displayName}")
        ConnectionService.start(appContext)
        // 连接成功自动刷新应用列表（静默，失败不打扰）
        if (udt != null) {
            runCatching { refreshInstalledApps(quiet = true) }
        }

        // ---- 3) 固件要求加密但还没配对：自动发起授权配对（电视会弹 6 位授权码）
        if (!encrypted && version.needsCrypto && existingPairing == null) {
            notice("该电视固件要求加密通道，正在发起授权配对…")
            startPairingInternal(device.host)
        }
        true
    }

    /** 观察 RC 事件：心跳保活、断线自动重连、播放控制响应（音量）解析。供首连与重连复用。 */
    private fun observeRc(rcClient: RcClient, device: TvDevice) {
        eventJob = scope.launch {
            rcClient.events.collect { ev ->
                when (ev) {
                    is RcEvent.ServerHeartbeat ->
                        _connection.value = _connection.value.copy(lastHeartbeatAt = System.currentTimeMillis())
                    is RcEvent.Closed -> {
                        if (userDisconnected) return@collect
                        // 旧客户端的 Closed 事件（已被新连接取代）不处理，防止重连风暴
                        if (rcClient !== rc) return@collect
                        scheduleReconnect(device, ev.cause)
                    }
                    is RcEvent.Other -> {
                        if (ev.type == RcCodec.TYPE_PLAY) {
                            val parsed = RcPlayControlCodec.parseResponse(ev.payload)
                            parsed?.let { (sub, data) ->
                                // 原版 l.p：子类型 8 = query result（7 是查询请求）
                                if (sub == RcPlayControlCodec.SUB_QUERY_RESULT) {
                                    RcPlayControlCodec.extractVolume(data)?.let { _tvVolume.value = it }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 建立 UDT（6093）+ Social（9093）通道。
     * rebuild=true：无条件重建（首连/用户主动连）；
     * rebuild=false（RC 重连场景）：既有 UDT/Social 仍存活则直接复用，不重建、不重查音量，
     * 避免 RC 抖动时连带重建健康通道造成刷屏；仅当既有通道已死才重建。
     */
    private suspend fun buildUdt(
        device: TvDevice,
        encrypted: Boolean,
        existingPairing: TvPairing?,
        rebuild: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!rebuild && udt?.isConnected == true && socialUdt?.isConnected == true) {
            Log.i(TAG, "UDT/Social 仍存活，RC 重连复用既有通道，不重建")
            return@withContext true
        }
        val keyPair = pairingStore.ensureKeyPair()
        val udtClient = try {
            connectUdtWithFallback(device.host, device.udtPort)
        } catch (t: Throwable) {
            Log.w(TAG, "UDT 连接失败：${t.message}")
            _connection.value = _connection.value.copy(udtConnected = false)
            return@withContext false
        }
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
        scope.launch { udtClient.pushes.collect { handlePush(it) } }
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.READY,
            udtConnected = true,
            message = if (encrypted) "已连接（加密通道）" else "已连接",
        )

        // Social 9093：音量专用（原版 v9.c: appId=1026）
        val social = try {
            UdtClient(device.host, SOCIAL_PORT, appId = SOCIAL_APP_ID).also { it.connect() }
        } catch (t: Throwable) {
            Log.w(TAG, "Social 9093 连接失败：${t.message}")
            null
        }
        if (social != null) {
            if (encrypted && existingPairing != null) {
                social.setEncryption(
                    UdtEncryption(
                        tvAuthPublicKey = existingPairing.tvAuthPublicKey,
                        ourDeviceId = keyPair.publicKeyMd5,
                        cbcKey = existingPairing.cbcKey,
                        cbcIv = existingPairing.cbcIv,
                    )
                )
            }
            socialUdt = social
            socialApi = TvApi(social)
            Log.i(TAG, "Social 9093 已就绪")
            queryVolume() // 仅 UDT 实际重建时查一次音量（不再查 getMute）
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
    /**
     * RC 断线调度：指数退避（2s/5s/10s 封顶），永不放弃。
     * 仅重建 RC 通道（轻量），复用或按需重建 UDT；重连成功不弹 Toast，避免频繁重连刷屏。
     */
    private fun scheduleReconnect(device: TvDevice, cause: Throwable? = null) {
        val attempt = ++reconnectAttempts
        val backoff = reconnectBackoffMs[minOf(attempt - 1, reconnectBackoffMs.size - 1)]
        Log.i(TAG, "RC 断线 cause=${cause?.javaClass?.simpleName}: ${cause?.message}，${backoff / 1000}s 后自动重连（第 $attempt 次）")
        // 断线即视为网络可能不可达：状态翻 UNREACHABLE（唤醒等待期除外，避免覆盖 WAKING）
        // 修复：此前只在主动 disconnect 时翻 OFF，电视自己关机走断线回调，电源键残留 ON 误发关机键
        if (_tvPowerState.value != TvPowerState.WAKING) {
            _tvPowerState.value = TvPowerState.UNREACHABLE
        }
        // 屏幕状态未知（断网后无法再查询）
        stopScreenStatePolling()
        // 短重连低调提示：仅标记 RC 断开，不弹 Toast
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.CONNECTING,
            rcConnected = false,
            message = "连接中断，正在重连…",
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            if (userDisconnected) return@launch
            reconnectJob = null
            val ok = runCatching { reconnectRc(device) }.getOrDefault(false)
            if (!ok && !userDisconnected) scheduleReconnect(device)
        }
    }

    /** RC 断线后的轻量重连：仅重建 RC 通道，复用（或按需重建）UDT/Social，不弹 Toast 打扰。 */
    private suspend fun reconnectRc(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        building = true
        try {
            val existingPairing = pairingStore.pairingFor(device.host)
            val keyPair = pairingStore.ensureKeyPair()
            // 旧 RC 已断，取消其观察协程防止重复调度重连
            eventJob?.cancel()
            eventJob = null
            val rcClient = try {
                connectRcWithFallback(device.host, device.rcPort, keyPair.publicKeyMd5)
            } catch (t: Throwable) {
                Log.w(TAG, "RC 重连失败：${t.message}")
                _connection.value = _connection.value.copy(rcConnected = false)
                return@withContext false
            }
            rc = rcClient
            observeRc(rcClient, device)

            val version = rcClient.negotiated ?: RcVersionCodec.RcVersionResponse(
                RcVersionCodec.CODE_RESPONSE,
                RcVersionCodec.DEFAULT_VERSION,
                null,
                null,
            )
            var encrypted = false
            version.secretKey?.let { sk -> encrypted = rcClient.applySecretKey(sk, keyPair.privateKey) }
            if (!encrypted && existingPairing != null && version.needsCrypto) {
                val resp = rcClient.resendVersionRequestAndWait()
                resp?.secretKey?.let { sk -> encrypted = rcClient.applySecretKey(sk, keyPair.privateKey) }
            }

            _connection.value = _connection.value.copy(
                phase = if (udt?.isConnected == true) ConnectionPhase.READY else ConnectionPhase.RC_READY,
                rcConnected = true,
                encrypted = encrypted,
                message = if (encrypted) "遥控通道已恢复（加密）" else "遥控通道已恢复",
                lastHeartbeatAt = System.currentTimeMillis(),
            )
            lastConnectDevice = device
            _tvPowerState.value = TvPowerState.REACHABLE

            // 重连成功同样恢复屏幕状态轮询
            startScreenStatePolling()

            // UDT：存活复用，已死才重建（重建才查音量，不查 getMute）
            buildUdt(device, encrypted, existingPairing, rebuild = false)

            reconnectAttempts = 0
            Log.i(TAG, "RC 重连成功（UDT ${if (udt?.isConnected == true) "复用" else "已重建"}）")
            true
        } finally {
            building = false
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
        powerOnJob?.cancel()
        powerOnJob = null
        disconnectInternal()
        _connection.value = ConnectionState()
        _tvPowerState.value = TvPowerState.UNREACHABLE
        ConnectionService.stop(appContext)
    }

    private fun disconnectInternal() {
        eventJob?.cancel()
        eventJob = null
        runCatching { rc?.shutdown() }
        runCatching { udt?.shutdown() }
        runCatching { socialUdt?.shutdown() }
        rc = null
        udt = null
        api = null
        socialUdt = null
        socialApi = null
        _tvVolume.value = null
        _tvMuted.value = null
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

    /**
     * 电源键「关机/息屏」：对已连接的电视发送 POWER 键（DOWN+UP 经 RC 通道）。
     * 电视收到可能熄屏或关机；若彻底关机，RC 连接会断开并进入自动重连（状态翻 OFF）。
     */
    suspend fun powerOff(): Boolean {
        val ok = sendKey(TvKey.POWER)
        if (ok) {
            // 亮屏时发 POWER → 电视熄屏（屏幕熄灭但网络仍通），乐观更新为息屏态
            // （已进入重连/关机则 stopScreenStatePolling 会把它归位为 null）
            _tvScreenOn.value = false
            notice("已发送电源键，电视可能熄屏/关机")
        }
        return ok
    }

    /**
     * 电源键「开机」：电视处于息屏/关机、RC 连接已断时调用。
     * 依次尝试 Wake-on-LAN（需电视支持）、BLE 广播开机、经典蓝牙 SPP 开机（均依赖 mDNS 广播的 MAC/标志），
     * 之后进入「待开机」状态并轮询重连，电视一旦回连即翻转为 ON。
     * 依赖缺失的路径会被跳过并在提示里说明，不会抛异常。
     */
    /**
     * 从 mDNS 实时补全唤醒元数据：蓝牙 MAC / WOL / Wi-Fi MAC。
     * 覆盖「手动 IP 连接」或设备记录过旧导致这些字段缺失的情况
     * （此前因此三路唤醒全跳过、点「打开」无反应）。
     */
    // ------------------------------------------------------------ 屏幕状态轮询
    // 协议层无法靠网络可达性区分「亮屏」与「息屏待机」（二者网络都通），但息屏时注入键被固件
    // 静默丢弃，理想情况靠 getDeviceStatus 的 isScreenOn 决定电源键走「注入切换」还是「唤醒」。
    // 但本机 L65MA-XT 固件恒定返回 status=1（反编译树里只有 SocialRequest 接口声明，固件未实现），
    // 每 10s 发一个 639 字节加密包纯属无效流量；连续 2 次拿不到 isScreenOn 即熔断轮询。
    // 此后 _tvScreenOn 维持 unknown，UI 双按钮兜底，唤醒入口始终可用。

    private fun startScreenStatePolling() {
        screenStateJob?.cancel()
        if (screenStatusFailures >= 2) return
        screenStateJob = scope.launch {
            while (isActive && _connection.value.isConnected && screenStatusFailures < 2) {
                queryScreenState()
                if (screenStatusFailures >= 2) break
                delay(10_000L)
            }
        }
    }

    private fun stopScreenStatePolling() {
        screenStateJob?.cancel()
        screenStateJob = null
        _tvScreenOn.value = null
        // 不重置 screenStatusFailures：固件不支持是进程级事实，重连后不必再试
    }

    private suspend fun queryScreenState() {
        val client = udt ?: return
        val status = runCatching { TvApi(client).getDeviceStatus() }
            .onFailure { Log.w(TAG, "查询屏幕状态失败：${it.message}") }
            .getOrNull()
        val screenOn = status?.screenOn
        if (screenOn != null) {
            screenStatusFailures = 0
            _tvScreenOn.value = screenOn
        } else if (++screenStatusFailures >= 2) {
            Log.i(TAG, "本机固件不提供 getDeviceStatus/isScreenOn，停止屏幕状态轮询（UI 双按钮兜底）")
        }
    }

    private suspend fun enrichWakeMeta(base: TvDevice): TvDevice {
        val found = runCatching { MdnsDiscovery(appContext).scan(4000) }.getOrDefault(emptyList())
        val hit = found.firstOrNull { it.id == base.host || it.host == base.host }
        // 兜底：曾经连接过并持久化的设备记录里可能已存过蓝牙 MAC / WOL / Wi-Fi MAC。
        // 电视关机后 mDNS 不可达（此前 22:27 日志即「mDNS 未广播」导致三路全跳过），
        // 此时用持久化记录仍能发起唤醒。
        val persisted = runCatching { settings.lastDevice.first() }.getOrNull()
            ?.takeIf { it.host == base.host }
        val device = base.copy(
            wifiMac = base.wifiMac ?: hit?.wifiMac ?: persisted?.wifiMac,
            btMac = base.btMac ?: hit?.btMac ?: persisted?.btMac,
            wol = base.wol || hit?.wol == true || persisted?.wol == true,
        )
        if (device.btMac != null || device.wol) {
            Log.i(TAG, "mDNS 补全唤醒元数据：btMac=${device.btMac} wol=${device.wol} wifiMac=${device.wifiMac}")
        } else {
            Log.i(TAG, "mDNS 未找到匹配设备(host=${base.host})，持久化兜底也无 MAC，无法补全唤醒元数据")
        }
        // 持久化补全后的唤醒元数据，电视下次关机离线时仍可尝试唤醒
        runCatching { settings.setLastDevice(device) }
        return device
    }

    suspend fun powerOn(): Boolean = withContext(Dispatchers.IO) {
        val base = lastConnectDevice ?: run {
            notice("没有可开机的电视记录，请先在首页连接一次", true)
            return@withContext false
        }
        // 红外电源码是「切换键」：10s 内重复点击必须忽略。
        // v1.8.26 日志实证曾在 3.7s 内连发两帧红外，第二帧会把刚点亮的电视又关掉。
        val now = System.currentTimeMillis()
        if (now - lastIrFireAt < IR_COOLDOWN_MS) {
            notice("已发射红外开机信号，电源为切换键，请稍候（10s 内重复点击已忽略）")
            return@withContext true
        }
        // 新一轮唤醒先停掉上一轮的 BLE 广播/SPP 重试，避免多份广播并存
        powerOnInstance?.stopWake()
        powerOnInstance = null
        powerOnJob?.cancel()

        val po = PowerOn(appContext)
        // 【关键优化】红外先行：不需要 mDNS 元数据、网络或权限，不等 4s 扫描。
        // v1.8.26 日志实证红外是唯一生效路径：21:42:57.840 发射 → 57.874 电视 mDNS 服务丢失，
        // 而 BLE(+93ms) / SPP(+915ms) 都在响应之后。点击到发射从 ~4.2s 降到 ~200ms。
        val ir = po.fireIr()
        if (ir.sent) {
            lastIrFireAt = now
            _tvPowerState.value = TvPowerState.WAKING
            // 切换键结果不可知：屏幕态归位 unknown，UI 保持双按钮兜底
            _tvScreenOn.value = null
            Log.i(TAG, "红外开机信号已发射（原版主路径），跳过 BLE/SPP 避免双电源切换键")
            notice("已发射红外开机信号（原版主路径）。电源为切换键，请勿重复点击；手机需对准电视。")
            // 红外是切换键，不做「亮屏」断言；短展示窗口后回到网络可达性语义。
            powerOnJob = scope.launch {
                delay(IR_WAKING_MS)
                _tvPowerState.value =
                    if (_connection.value.isConnected) TvPowerState.REACHABLE else TvPowerState.UNREACHABLE
            }
            return@withContext true
        }

        // 红外不可用（无硬件或发射失败）→ 才走原版其余三路，此时才需要 mDNS 补全唤醒元数据
        val device = enrichWakeMeta(base)
        Log.i(TAG, "powerOn 设备元数据：host=${device.host} btMac=${device.btMac} wol=${device.wol} wifiMac=${device.wifiMac}")
        powerOnInstance = po
        val report = runCatching { po.beginWake(device, ir) }
            .onFailure { Log.w(TAG, "开机唤醒启动失败：${it.message}") }
            .getOrElse { PowerOn.Report(false, false, false, false, false, false, listOf(it.message ?: "未知错误")) }
        Log.i(TAG, "红外不可用，启用兜底唤醒：${report.summary}")
        if (!report.attempted) {
            po.stopWake()
            powerOnInstance = null
            notice(
                "无法发起唤醒：${report.reasons.joinToString("；")}。" +
                    "请用电视遥控器或实体电源键开机。",
                true,
            )
            return@withContext false
        }
        // 正在尝试唤醒的是「非亮屏」电视（息屏待机或已关机），乐观标记屏幕熄灭
        _tvScreenOn.value = false
        _tvPowerState.value = TvPowerState.WAKING
        notice("红外不可用，已启用兜底唤醒（${report.summary}），持续尝试约 75s…")
        // 集中由本任务驱动重连轮询，避免与既有重连循环打架
        reconnectJob?.cancel()
        reconnectJob = null
        userDisconnected = false
        powerOnJob?.cancel()
        powerOnJob = scope.launch {
            try {
                val deadline = System.currentTimeMillis() + 75_000L
                // 关键修复：绝不能因「已连接」提前结束唤醒。
                // 息屏/待机态网络本就全通，_connection.isConnected 恒为 true；旧逻辑第一次循环
                // 就 return → finally 立刻 stopWake()，把刚 launch 的 BLE 续播/SPP 重试协程
                // 掐死在启动前（日志里零条 PowerOn 记录 = 「打开无效」的确凿根因）。
                // 现在让 BLE/SPP 跑满整个窗口；只有真正拿到 screenOn=true 才算唤醒成功。
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (_tvScreenOn.value == true) {
                        _tvPowerState.value = TvPowerState.REACHABLE
                        notice("电视屏幕已点亮，唤醒完成。")
                        return@launch
                    }
                    if (!_connection.value.isConnected &&
                        _connection.value.phase != ConnectionPhase.CONNECTING
                    ) {
                        connect(device, force = false)
                    }
                    delay(3_000L)
                }
                // 本机固件不返回 getDeviceStatus（status=1），screenOn 恒为 unknown，
                // 所以通常走到超时：不把「网络仍通」误报成开机成功，只如实提示。
                if (_connection.value.isConnected) {
                    _tvPowerState.value = TvPowerState.REACHABLE
                    notice(
                        "兜底唤醒已持续发送 75s。若屏幕仍未亮起，请按电视实体电源键或遥控器开机；" +
                            "本机固件不支持屏幕状态查询，无法自动确认亮屏。",
                    )
                } else {
                    _tvPowerState.value = TvPowerState.UNREACHABLE
                    notice(
                        "75s 内未能恢复与电视的网络连接，且本机红外不可用。" +
                            "请按实体电源键开机；若电视实际已亮屏，请在首页手动连接。",
                        true,
                    )
                }
            } finally {
                // 窗口真正结束（超时/屏幕点亮/被取消）才停，此时 BLE/SPP 已充分运行
                runCatching { po.stopWake() }
                if (powerOnInstance === po) powerOnInstance = null
            }
        }
        true
    }

    /** 音量请求串行化：Social 9093 单连接，同时只允许一个在途音量请求 */
    private val volumeMutex = Mutex()

    /**
     * 切换静音：Social setMute 电视实测不支持（无效果），走 RC MUTE 键（实测有效）。
     * 电视不回报告静音状态，App 侧本地跟踪：发送成功后翻转本地状态。
     */
    suspend fun toggleMuteKey(): Boolean {
        val ok = sendKey(TvKey.MUTE)
        if (ok) {
            _tvMuted.value = !(_tvMuted.value ?: false)
            Log.i(TAG, "静音切换 → ${_tvMuted.value}")
        }
        return ok
    }

    /**
     * 设置电视音量（绝对值）。原版 SocialRequest.setVolume 走 UDT 通道
     * （逆向确认：遥控场景音量不走 RC 按键/播放控制帧，HTTP 旧接口仅限旧机型）。
     * 返回 false 表示发送/解析失败，调用方应停止连发。
     *
     * 关键：响应回填放在 NonCancellable —— 连发协程被取消（松手）时，
     * 已发出的请求其结论必须写回缓存，否则缓存停滞导致后续点击重复发同一值。
     */
    suspend fun setVolume(volume: Int): Boolean {
        val a = socialApi ?: api ?: return false
        return try {
            volumeMutex.withLock {
                val resp = withContext(NonCancellable) { a.setVolume(volume) }
                if (resp != null) {
                    _tvVolume.value = resp.current
                    _tvVolumeMax.value = resp.max
                } else {
                    // 无响应体但请求成功时乐观回填
                    _tvVolume.value = volume.coerceIn(0, _tvVolumeMax.value)
                }
                // 电视行为：调节音量会自动解除静音，同步本地状态
                _tvMuted.value = false
                true
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "音量设置失败：${t.message}")
            }
            false
        }
    }

    /** 查询电视当前音量（Social 9093 通道 getVolume）。
     *  关键：getVolume 响应常一并携带静音标志（isMute/mute/muted），原版小米电视助手即此路径——
     *  连接时一次性拿到音量 + 静音真实状态，不再依赖（多固件不存在的）getMute 方法。 */
    fun queryVolume() {
        val a = socialApi ?: api ?: return
        scope.launch {
            try {
                val resp = volumeMutex.withLock {
                    withContext(NonCancellable) { a.getVolume() }
                }
                if (resp != null) {
                    _tvVolume.value = resp.current
                    _tvVolumeMax.value = resp.max
                    if (resp.muted != null) {
                        _tvMuted.value = resp.muted
                        Log.i(TAG, "电视音量：${resp.current}/${resp.max} 静音：${resp.muted}（来自 getVolume 响应）")
                    } else {
                        // getVolume 未携带静音字段：本机实测 getMute 方法 status=21104 不存在，
                        // 不再发必败查询，静音态保持「未知」（由 App 内静音键本地翻转）
                        Log.i(TAG, "电视音量：${resp.current}/${resp.max}（getVolume 未携带静音字段，静音态保持未知）")
                    }
                } else {
                    Log.w(TAG, "音量查询无结果（方法可能不受支持）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "音量查询失败：${t.message}")
            }
        }
    }

    /** 静音状态兜底查询（Social 9093 通道 getMute，部分固件支持，本机实测为 status=21104 不存在）。
     *  主路径是 getVolume 响应解析；仅当 getVolume 未携带静音字段时才调用。 */
    fun queryMute() {
        val a = socialApi ?: api ?: return
        scope.launch {
            try {
                val m = withContext(NonCancellable) { a.getMute() }
                if (m != null) {
                    _tvMuted.value = m
                    Log.i(TAG, "电视静音状态：$m（来自 getMute 兜底）")
                } else {
                    Log.w(TAG, "静音查询无结果（getMute 方法可能不受支持），保持本地/未知状态")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "静音查询失败：${t.message}")
            }
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
