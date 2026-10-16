package com.tvhelper.app.net

import com.tvhelper.app.protocol.Bytes
import com.tvhelper.app.protocol.airkan.AirkanCrypto
import com.tvhelper.app.protocol.udt.UdtCodec
import com.tvhelper.app.protocol.udt.UdtMethods
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class UdtException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 电视端主动发来的请求 / 推送 */
data class UdtPush(val methodName: String?, val requestId: String?, val payload: JSONObject)

/** UDT 加密参数（授权配对成功后启用） */
data class UdtEncryption(
    val tvAuthPublicKey: String,
    val ourDeviceId: String,
    val cbcKey: String,
    val cbcIv: String,
)

/**
 * UDT 通道客户端（TCP 6093，ctrl + data 双连接）。
 *
 * 逆向依据：UDTClientManagerImpl.createConnections(int,boolean) 使用同一个
 * `UDTClient(ip, 6093, appId=1025)` 描述符建立两条连接：
 * - ctrl 连接：openApp / installUrlApp / uninstallApp / getAppStatus / getSecretData ...
 * - data 连接：getInstalledPackages / getScreenshotBitmap 等大返回方法
 *
 * 请求-响应以 `requestId` 关联（格式 `<appVersionCode>:<uuid>`）；
 * 大响应按 `dataChannel.{page_no,total}` 分页，data 段承载分片内容。
 *
 * 线级格式（关键！此前缺失导致电视直接 RST）：
 * 1. 连接建立后必须立即发送握手帧（o6.d/o6.g 构造函数行为）：
 *    ctrl 连接发 {0,0,0,0,5,0,appId,appId}，data 连接第 6 字节为 1；
 * 2. 之后所有消息都要套 16 字节 UDTPacket 头（o6.c），见 [UdtCodec.udtPacketHeader]。
 */
class UdtClient(
    private val host: String,
    private val port: Int = UdtCodec.PORT,
    private val appVersionCode: Int = 1,
) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        private const val TAG = "UdtClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)

    private var ctrlSocket: Socket? = null
    private var dataSocket: Socket? = null
    private var ctrlOut: java.io.OutputStream? = null
    private var dataOut: java.io.OutputStream? = null

    private var ctrlReader: Job? = null
    private var dataReader: Job? = null

    private val idSeq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<UdtCodec.Message>>()
    private val lastMessage = ConcurrentHashMap<String, UdtCodec.Message>()
    private val pageStore = ConcurrentHashMap<String, ConcurrentSkipListMap<Int, ByteArray>>()

    private val _pushes = MutableSharedFlow<UdtPush>(extraBufferCapacity = 16)
    val pushes: SharedFlow<UdtPush> = _pushes

    /** 加密配置；null = 明文模式 */
    @Volatile
    private var encryption: UdtEncryption? = null

    fun setEncryption(e: UdtEncryption?) {
        encryption = e
        Log.i(TAG, "UDT 加密${if (e != null) "已启用（上行RSA/下行AES-CBC）" else "关闭"}")
    }

    val isConnected: Boolean
        get() = !closed.get() &&
            (ctrlSocket?.isConnected == true && ctrlSocket?.isClosed == false)

    // ------------------------------------------------------------ 建连

    /**
     * 建两条 TCP 连接。所有 Socket 操作都在 [Dispatchers.IO] 上执行 ——
     * 否则从主线程（viewModelScope）调用会抛 NetworkOnMainThreadException。
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        close()
        closed.set(false)
        try {
            val c = openSocket()
            ctrlSocket = c
            ctrlOut = c.getOutputStream()
            // 会话初始化：原版在发送器构造时立即发握手帧，电视据此登记会话
            ctrlOut!!.write(UdtCodec.handshakePacket(isCtrl = true))
            ctrlOut!!.flush()
            ctrlReader = scope.launch { readLoop(c.getInputStream(), "ctrl") }
            Log.i(TAG, "ctrl 连接成功 ${host}:$port（握手已发送）")
        } catch (t: Throwable) {
            close()
            throw UdtException("UDT ctrl 连接失败 ${host}:$port（${t.javaClass.simpleName}）", t)
        }
        // data 连接失败不阻断 ctrl（遥控/打开应用仍可用）
        runCatching {
            val d = openSocket()
            dataSocket = d
            dataOut = d.getOutputStream()
            dataOut!!.write(UdtCodec.handshakePacket(isCtrl = false))
            dataOut!!.flush()
            dataReader = scope.launch { readLoop(d.getInputStream(), "data") }
            Log.i(TAG, "data 连接成功 ${host}:$port（握手已发送）")
        }.onFailure { Log.w(TAG, "data 连接失败：${it.message}") }
        Unit
    }

    private fun openSocket(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = 0
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return s
    }

    // ------------------------------------------------------------ 请求

    fun nextId(): Int = idSeq.incrementAndGet()

    suspend fun call(req: UdtCodec.Request, timeoutMs: Long = UdtMethods.TIMEOUT_DEFAULT): UdtCodec.Message {
        if (closed.get()) throw UdtException("UDT 通道已关闭")
        val viaData = UdtMethods.useDataConnection(req.call.methodName)
        val out = (if (viaData) dataOut else ctrlOut)
            ?: throw UdtException(if (viaData) "UDT data 连接不可用" else "UDT ctrl 连接不可用")

        val deferred = CompletableDeferred<UdtCodec.Message>()
        pending[req.requestId] = deferred
        try {
            val payload = encodeRequest(req)
            Log.i(TAG, "→ ${req.call.methodName} rid=${req.requestId} via=${if (viaData) "data" else "ctrl"} bytes=${payload.size} id=${req.id} enc=${encryption != null}")
            // 写 socket 是阻塞 I/O，必须离开调用线程（通常是主线程）
            withContext(Dispatchers.IO) { writePacketed(out, payload, viaData) }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            // 超时：若已收到部分分页，尽量拼装返回
            val last = lastMessage[req.requestId]
            if (last != null) {
                val pages = pageStore[req.requestId]
                val payload = if (!pages.isNullOrEmpty()) assemble(pages) else last.data
                return UdtCodec.Message(last.ctrl, payload, last.tail)
            }
            throw UdtException("UDT 调用超时：${req.call.methodName}")
        } catch (t: UdtException) {
            throw t
        } catch (t: Throwable) {
            throw UdtException("UDT 调用失败：${req.call.methodName}（${t.message}）", t)
        } finally {
            pending.remove(req.requestId)
            lastMessage.remove(req.requestId)
            pageStore.remove(req.requestId)
        }
    }

    /**
     * 请求编码：已配置加密时，ctrl JSON 用电视授权公钥 RSA/ECB/PKCS1 分块加密，
     * ctrl 段为【密文原始字节】（ga.a 构造：a3.c.f 返回值直接作为 ctrl 段，不做 Base64），
     * tail 填 deviceId + encrypt=true。
     */
    private fun encodeRequest(req: UdtCodec.Request): ByteArray {
        val enc = encryption ?: return UdtCodec.encode(req)
        val req2 = req.copy(tailDeviceId = enc.ourDeviceId, tailEncrypt = true)
        val ctrlPlain = Bytes.utf8(UdtCodec.buildCtrlJson(req2).toString())
        val ctrlEnc = AirkanCrypto.rsaEncryptChunked(enc.tvAuthPublicKey, ctrlPlain)
        val tail = Bytes.utf8(UdtCodec.buildTailJson(req2).toString())
        return UdtCodec.encodeRaw(ctrlEnc, ByteArray(0), tail)
    }

    /** 发送：套 UDTPacket 16 字节头，data 连接按 1440 字节分片（复刻 o6.c/o6.d/o6.g） */
    private fun writePacketed(out: java.io.OutputStream, msg: ByteArray, viaData: Boolean) {
        val chunkMax = if (viaData) UdtCodec.DATA_CHUNK_MAX else UdtCodec.CTRL_CHUNK_MAX
        writeLock.withLock {
            var off = 0
            do {
                val len = minOf(chunkMax, msg.size - off)
                val more = off + len < msg.size
                out.write(UdtCodec.udtPacketHeader(UdtCodec.UDT_APP_ID, UdtCodec.UDT_APP_ID, len, more))
                out.write(msg, off, len)
                off += len
            } while (off < msg.size)
            out.flush()
        }
    }

    // ------------------------------------------------------------ 读循环

    private suspend fun readLoop(rawIn: InputStream, tag: String) {
        val ins = rawIn.buffered(16 * 1024)
        val acc = java.io.ByteArrayOutputStream()
        try {
            while (currentCoroutineContext().isActive) {
                // UDTPacket 分帧层：16 字节头 + payload（可能多片）
                val hdr = readExactly(ins, UdtCodec.PACKET_HEADER_LEN) ?: break
                if ((hdr[0].toInt() and 0xFF) != 0x55) {
                    Log.w(TAG, "[$tag] UDTPacket magic 错误 head=${Bytes.toHex(hdr)}，流已错位")
                    break
                }
                val more = (hdr[1].toInt() and 0x08) != 0
                val chunkLen = Bytes.readI32(hdr, 8)
                val chunk = readExactly(ins, chunkLen) ?: break
                acc.write(chunk)
                if (more) continue
                val buf = acc.toByteArray()
                acc.reset()
                // type=0 控制帧（握手/保活）不进消息层（o6.e.d 行为）
                if (UdtCodec.isControlPayload(buf)) {
                    Log.i(TAG, "[$tag] ← 控制/握手帧（${buf.size}B），忽略")
                    continue
                }
                val lengths = UdtCodec.readLengths(buf.copyOfRange(0, 12))
                val msg = runCatching { decodeMessage(buf) }.getOrElse {
                    Log.w(TAG, "[$tag] 解码失败 len=ctrl:${lengths.first}/data:${lengths.second}/tail:${lengths.third} head=${Bytes.toHex(buf.copyOfRange(0, minOf(48, buf.size)))}")
                    return@getOrElse null
                } ?: continue
                Log.i(TAG, "← [$tag] rid=${msg.requestId} action=${msg.ctrl.optJSONObject("control")?.optString("type")} method=${msg.methodName} status=${msg.status} dataLen=${msg.data.size} dataCh=${msg.dataChannel} dataHead=${if (msg.data.isEmpty()) "-" else Bytes.toHex(msg.data.copyOfRange(0, minOf(32, msg.data.size)))}")
                dispatch(msg)
            }
        } catch (_: Throwable) {
            // 读线程结束即代表该连接断开
        } finally {
            if (tag == "ctrl") closed.set(true)
        }
    }

    /**
     * 帧解码 + 下行 ctrl 解密（ga.a 解析构造）：tail.encrypt=true 时，
     * ctrl 段为「自定义Base64(AES-CBC(自定义Base64(明文)))」，用授权码派生密钥解出。
     */
    private fun decodeMessage(buf: ByteArray): UdtCodec.Message {
        val (ctrlBytes, dataBytes, tailBytes) = UdtCodec.decodeRaw(buf)
        val tail = JSONObject(if (tailBytes.isEmpty()) "{}" else String(tailBytes, Charsets.UTF_8))
        var ctrlText = if (ctrlBytes.isEmpty()) "{}" else String(ctrlBytes, Charsets.UTF_8)
        if (tail.optBoolean("encrypt")) {
            val enc = encryption ?: throw UdtException("收到加密 ctrl 但未配置 UDT 加密参数")
            ctrlText = AirkanCrypto.cbcDecrypt(ctrlText, enc.cbcKey, enc.cbcIv)
        }
        return UdtCodec.Message(JSONObject(ctrlText), dataBytes, tail)
    }

    private suspend fun dispatch(msg: UdtCodec.Message) {
        val rid = msg.requestId
        // 电视 → 手机：请求（反向 RPC）或推送
        if (msg.isRequest) {
            if (msg.hasMethodCall) {
                _pushes.tryEmit(UdtPush(msg.methodName, rid, msg.ctrl))
                respondOk(msg)
            } else {
                _pushes.tryEmit(UdtPush(msg.methodName, rid, msg.ctrl))
            }
            return
        }
        if (rid == null) { Log.w(TAG, "响应无 requestId：${msg.ctrl}") ; return }
        val deferred = pending[rid]
        if (deferred == null) { Log.w(TAG, "无匹配 pending 的响应 rid=$rid method=${msg.methodName}") ; return }
        lastMessage[rid] = msg

        val page = msg.dataChannel
        if (page == null || page.total <= 1) {
            deferred.complete(msg)
            return
        }
        val store = pageStore.getOrPut(rid) { ConcurrentSkipListMap() }
        store[page.pageNo] = msg.data
        // 原实现分页从 2 开始编号，页数 = total - 1；两种约定都兼容
        val expected = (page.total - 1).coerceAtLeast(1)
        if (store.size >= expected || page.pageNo >= page.total) {
            val all = assemble(store)
            deferred.complete(UdtCodec.Message(msg.ctrl, all, msg.tail))
        }
    }

    private fun assemble(store: ConcurrentSkipListMap<Int, ByteArray>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        for ((_, part) in store) buf.write(part)
        return buf.toByteArray()
    }

    /** 对电视端请求回一个空成功响应，避免对方等超时；若来帧是加密的，回应也加密 */
    private suspend fun respondOk(msg: UdtCodec.Message) {
        runCatching {
            val ctrl = JSONObject(msg.ctrl.toString())
            ctrl.put("request", false)
            val control = ctrl.optJSONObject("control") ?: return@runCatching
            val method = control.optJSONObject("method") ?: return@runCatching
            method.remove("methodCall")
            method.put(
                "methodReturn",
                JSONObject().apply {
                    put("status", 0)
                    put("result", JSONObject())
                    put("error_msg", "")
                },
            )
            val enc = encryption
            val encryptOut = msg.tail.optBoolean("encrypt") && enc != null
            val tail = JSONObject().apply {
                put("requestId", msg.tail.optString("requestId"))
                put("deviceId", if (encryptOut) enc!!.ourDeviceId else msg.tail.optString("deviceId", ""))
                put("encrypt", encryptOut)
            }
            val payload = if (encryptOut) {
                val ctrlEnc = AirkanCrypto.rsaEncryptChunked(
                    enc.tvAuthPublicKey,
                    Bytes.utf8(ctrl.toString()),
                )
                UdtCodec.encodeRaw(ctrlEnc, ByteArray(0), Bytes.utf8(tail.toString()))
            } else {
                UdtCodec.encode(UdtCodec.Message(ctrl, ByteArray(0), tail))
            }
            val out = ctrlOut ?: return@runCatching
            withContext(Dispatchers.IO) { writePacketed(out, payload, false) }
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
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
        ctrlReader?.cancel()
        dataReader?.cancel()
        runCatching { ctrlSocket?.close() }
        runCatching { dataSocket?.close() }
        ctrlSocket = null
        dataSocket = null
        ctrlOut = null
        dataOut = null
        pending.values.forEach { runCatching { it.cancel() } }
        pending.clear()
        lastMessage.clear()
        pageStore.clear()
    }

    fun shutdown() {
        close()
        scope.cancel()
    }
}
