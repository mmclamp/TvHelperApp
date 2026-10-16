package com.tvhelper.app.net

import android.util.Log
import com.tvhelper.app.protocol.Bytes
import com.tvhelper.app.protocol.airkan.AirkanCrypto
import com.tvhelper.app.protocol.rc.RcCodec
import com.tvhelper.app.protocol.rc.RcKeyCodec
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.RcVersionCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** RC 通道事件 */
sealed interface RcEvent {
    data object ServerHeartbeat : RcEvent
    data class Version(val response: RcVersionCodec.RcVersionResponse) : RcEvent
    data class Other(val type: Int, val payload: ByteArray) : RcEvent
    data class Closed(val cause: Throwable?) : RcEvent
}

class RcException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * RC 通道客户端（TCP 30612）。
 *
 * 建连序列（对齐 h2.h.RCClientThread）：
 * 1. TCP 连接 30612
 * 2. 发送就绪帧 `{61,0,0}`
 * 3. 发送 type5 版本请求（声明客户端协议版本）
 * 4. 起读线程：解析 3 字节头 + payload；type2 为电视心跳，type5 为版本/密钥协商
 * 5. 每 10s 发一次 `{2,0,0}` 心跳
 *
 * 若电视版本 > [RcVersionCodec.CRYPTO_THRESHOLD]，版本响应里会带 secretKey
 * （RSA 加密的 Base64），用本地私钥解出后得 AES/ECB/PKCS5 会话密钥；
 * 此后除心跳（type2）与版本包（type5）外，收发帧 payload 均加密（q1.a SecurityDelegate）。
 * 拿不到 secretKey 时说明电视侧没有本机配对记录，需要走 requestAuth 授权配对。
 */
class RcClient(
    private val host: String,
    private val port: Int = RcCodec.PORT,
    private val deviceId: String? = null,
) {

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val VERSION_WAIT_MS = 4_000L
        const val CONNECT_TIMEOUT_MS = 6_000
        private const val TAG = "RcClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = ReentrantLock()

    @Volatile
    private var socket: Socket? = null
    private var input: InputStream? = null

    @Volatile
    private var output: java.io.OutputStream? = null

    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    private val versionDeferred = CompletableDeferred<RcVersionCodec.RcVersionResponse>()
    private val closed = AtomicBoolean(false)

    /** 后续版本响应等待器（重协商用，每次 awaitNextVersion 换新） */
    @Volatile
    private var nextVersion: CompletableDeferred<RcVersionCodec.RcVersionResponse>? = null

    /** RC 会话密钥（secretKey 解出后设置）；null = 明文模式 */
    @Volatile
    private var ecbCipher: javax.crypto.spec.SecretKeySpec? = null

    val hasCipher: Boolean get() = ecbCipher != null

    private val _events = MutableSharedFlow<RcEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RcEvent> = _events

    @Volatile
    var lastServerBeatAt: Long = 0L
        private set

    @Volatile
    var negotiated: RcVersionCodec.RcVersionResponse? = null
        private set

    val isConnected: Boolean get() = socket?.isConnected == true && !socket!!.isClosed && !closed.get()

    /**
     * 建连并完成版本协商，返回电视端版本信息。
     *
     * 注意：整个建连过程（DNS 解析 + Socket.connect + getInputStream/getOutputStream）
     * 都是阻塞 I/O，必须切到 [Dispatchers.IO]，否则从 `viewModelScope`（主线程）调用时
     * 会抛 NetworkOnMainThreadException。
     */
    suspend fun connect(): RcVersionCodec.RcVersionResponse = withContext(Dispatchers.IO) {
        close()
        closed.set(false)

        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = 0
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw RcException("无法连接电视 ${host}:$port（${t.javaClass.simpleName}）", t)
        }

        socket = s
        input = s.getInputStream().buffered(8 * 1024)
        output = s.getOutputStream()

        // 2) 就绪握手
        writeDirect(RcCodec.ready())
        // 3) 声明客户端协议版本（带 deviceId extra，电视据此查找配对公钥下发 secretKey）
        writeDirect(buildVersionFrame())
        lastServerBeatAt = System.currentTimeMillis()

        // 4) 读循环
        readerJob = scope.launch { readLoop() }
        // 5) 心跳
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    writeRaw(RcCodec.heartbeat())
                } catch (t: Throwable) {
                    Log.w(TAG, "心跳发送失败，停止心跳：${t.message}")
                    break
                }
            }
        }

        // 等电视回版本包；不回也不阻塞（按明文继续，很多固件不主动回）
        val resp = withTimeoutOrNull(VERSION_WAIT_MS) { versionDeferred.await() }
            ?: RcVersionCodec.RcVersionResponse(
                RcVersionCodec.CODE_RESPONSE,
                RcVersionCodec.DEFAULT_VERSION,
                null,
                null,
            )
        negotiated = resp
        resp
    }

    /** 版本请求帧：本地有 AirKan 密钥对时带 {"deviceId":"<publicKeyMd5>"} extra（h2.h.L） */
    private fun buildVersionFrame(): ByteArray {
        val extra = deviceId?.let { "{\"deviceId\":\"$it\"}" }
        return RcVersionCodec.frameRequest(RcVersionCodec.RcVersion(extra = extra))
    }

    /** 在既有连接上重发版本请求并等待新响应（授权配对成功后调用） */
    suspend fun resendVersionRequestAndWait(timeoutMs: Long = 6_000L): RcVersionCodec.RcVersionResponse? {
        val d = CompletableDeferred<RcVersionCodec.RcVersionResponse>()
        nextVersion = d
        writeRaw(buildVersionFrame())
        return withTimeoutOrNull(timeoutMs) { d.await() }
    }

    /**
     * 解出 RC 会话密钥（secretKey = "RSA" 裸变换加密的自定义 Base64，私钥为本机 AirKan 私钥）。
     * NoPadding 解密得到「前导零 + 密钥文本」，由 MIME Base64 容错解码剥离。
     * 成功后所有非心跳/版本帧自动加密收发。
     */
    fun applySecretKey(secretKeyEncrypted: String, privateKeyCustomB64: String): Boolean = runCatching {
        val raw = AirkanCrypto.rsaDecryptRaw(privateKeyCustomB64, secretKeyEncrypted)
        val keyText = String(raw, Charsets.ISO_8859_1).trimStart('\u0000')
        ecbCipher = AirkanCrypto.ecbCipherFromKeyText(keyText)
        Log.i(TAG, "RC 会话密钥已启用（加密模式），密钥原文长度=${keyText.trim().length}")
        true
    }.getOrElse {
        Log.w(TAG, "secretKey 解密失败：${it.message}")
        false
    }

    /** 按键：一次点击 = DOWN + UP */
    // ------------------------------------------------------------ 发送

    suspend fun sendKey(keyCode: Int) {
        Log.i(TAG, "→ key keyCode=$keyCode")
        // 时间戳必须用 uptimeMillis（Android 输入系统惯例），wall clock 会导致电视端时间判断异常
        val now = android.os.SystemClock.uptimeMillis()
        for (frame in RcKeyCodec.framesDownUp(keyCode, now)) writeRaw(frame)
    }

    suspend fun sendKeyRawFrames(frames: List<ByteArray>) {
        for (f in frames) writeRaw(f)
    }

    suspend fun sendTouch(event: RcTouchCodec.TouchEvent) {
        writeRaw(RcTouchCodec.frame(event))
    }

    suspend fun writeRaw(bytes: ByteArray) {
        if (closed.get()) throw RcException("RC 通道已关闭")
        withContext(Dispatchers.IO) { writeDirect(bytes) }
    }

    /** 阻塞写。调用方必须已在 IO 线程上（要么是 [writeRaw]，要么已在 Dispatchers.IO 内）。 */
    private fun writeDirect(bytes: ByteArray) {
        val out = output ?: throw RcException("RC 通道未连接")
        val frame = encryptIfNeeded(bytes)
        writeLock.withLock {
            try {
                out.write(frame)
                out.flush()
            } catch (t: Throwable) {
                throw RcException("RC 写入失败：${t.message}", t)
            }
        }
    }

    /**
     * 加密出站帧（q1.a.b SecurityDelegate）：保留 type 字节，
     * AES/ECB 加密 payload（跳过 3 字节头），头部长度改为密文长度。
     * 心跳（type2）与版本包（type5）始终明文（h2.h.F）。
     */
    private fun encryptIfNeeded(frame: ByteArray): ByteArray {
        val cipher = ecbCipher ?: return frame
        val type = Bytes.u8(frame, 0)
        if (type == RcCodec.TYPE_HEARTBEAT || type == RcCodec.TYPE_VERSION) return frame
        if (frame.size <= 3) return frame
        val enc = AirkanCrypto.ecbEncrypt(frame.copyOfRange(3, frame.size), cipher)
        return Bytes.concat(byteArrayOf(frame[0]), Bytes.i16be(enc.size), enc)
    }

    /** 入站帧解密（q1.a.a）：type2/type5 与无密钥时原样返回 */
    private fun decryptIfNeeded(frame: ByteArray): ByteArray {
        val cipher = ecbCipher ?: return frame
        val type = Bytes.u8(frame, 0)
        if (type == RcCodec.TYPE_HEARTBEAT || type == RcCodec.TYPE_VERSION) return frame
        if (frame.size <= 3) return frame
        return runCatching {
            val dec = AirkanCrypto.ecbDecrypt(frame.copyOfRange(3, frame.size), cipher)
            Bytes.concat(byteArrayOf(frame[0]), Bytes.i16be(dec.size), dec)
        }.getOrElse {
            Log.w(TAG, "RC 帧解密失败 type=$type len=${frame.size}: ${it.message}")
            frame
        }
    }

    // ------------------------------------------------------------ 读循环

    private suspend fun readLoop() {
        val ins = input ?: return
        try {
            while (currentCoroutineContext().isActive) {
                val header = readExactly(ins, 3) ?: break
                val type = Bytes.u8(header, 0)
                val len = Bytes.readI16(header, 1)
                if (len > RcCodec.MAX_PAYLOAD) throw RcException("RC 非法长度 $len")
                val payload = if (len > 0) readExactly(ins, len) ?: break else ByteArray(0)
                val frame = decryptIfNeeded(Bytes.concat(header, payload))

                when (type) {
                    RcCodec.TYPE_HEARTBEAT -> {
                        lastServerBeatAt = System.currentTimeMillis()
                        _events.tryEmit(RcEvent.ServerHeartbeat)
                    }
                    RcCodec.TYPE_VERSION -> {
                        val resp = runCatching { RcVersionCodec.parse(frame) }.getOrNull()
                        if (resp != null) {
                            versionDeferred.complete(resp)
                            nextVersion?.complete(resp)
                            negotiated = resp
                            _events.tryEmit(RcEvent.Version(resp))
                        }
                    }
                    else -> _events.tryEmit(RcEvent.Other(type, payload))
                }
            }
            _events.tryEmit(RcEvent.Closed(null).also { Log.i(TAG, "读循环 EOF，连接被对端关闭") })
        } catch (t: Throwable) {
            Log.w(TAG, "读循环异常退出：${t.javaClass.simpleName}: ${t.message}")
            _events.tryEmit(RcEvent.Closed(t))
        } finally {
            closed.set(true)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    fun close() {
        closed.set(true)
        readerJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    fun shutdown() {
        close()
        scope.cancel()
    }
}
