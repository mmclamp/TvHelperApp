package com.tvhelper.app.protocol.udt

import com.tvhelper.app.protocol.Bytes
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * UDT 通道（milink UDT，TCP 6093）消息编解码。
 *
 * 逆向依据：ga.a(UDTMessage)、ga.b(UDTMessageCtrlRegion)、ga.c(DataRegion)、
 * ga.d(EndingRegion)、ha.c/ha.e(UDTMethodController)、ia.f(TypeUtil)。
 *
 * 帧结构（三段长度 + 三段内容，全部大端）：
 * ```
 * [ctrlLen u32BE][dataLen u32BE][tailLen u32BE][ctrl JSON][data bytes][tail JSON]
 * ```
 *
 * ctrl JSON（注意外层键名是 `action`，不是 `type`；`control.type == 0` 表示方法调用）：
 * ```json
 * {
 *   "action": 0,
 *   "request": true,
 *   "requestId": "<appVersionCode>:<uuid>",
 *   "control": {
 *     "type": 0,
 *     "method": {
 *       "methodName": "openApp",
 *       "methodCallID": 1699999999999,
 *       "invokerID": "",
 *       "verifyCode": "",
 *       "methodCall": { "args": [1699999999999, "com.x.y"], "useDataChannelReturn": true }
 *     }
 *   },
 *   "id": 1
 * }
 * ```
 *
 * 响应里 `method.methodReturn = { "status": 0, "result": {...}, "error_msg": "" }`；
 * 大响应（如 getInstalledPackages）走 `method.dataChannel` 分页 + data 段承载，
 * `page_no` 从 2 开始递增，`total` 为总页数。
 */
object UdtCodec {

    const val PORT = 6093

    /** 客户端本地绑定端口（原实现固定 1025） */
    const val LOCAL_PORT = 1025

    /** data 段为空时的占位 */
    private val EMPTY = ByteArray(0)

    // ---------------------------------------------------------------- 模型

    data class MethodCall(
        val methodName: String,
        val methodCallId: Long,
        val invokerId: String = "",
        val verifyCode: String = "",
        val args: List<Any?> = emptyList(),
        val useDataChannelReturn: Boolean = true,
    )

    data class PageInfo(
        val pageNo: Int,
        val total: Int,
        val pageSize: Long,
        val totalSize: Long,
        val md5: String?,
    )

    data class Request(
        val requestId: String,
        val call: MethodCall,
        val action: Int = 0,
        val controlType: Int = 0,
        val id: Int,
        val request: Boolean = true,
        val tailDeviceId: String? = null,
        val tailEncrypt: Boolean = false,
    )

    data class Message(
        val ctrl: JSONObject,
        val data: ByteArray,
        val tail: JSONObject,
    ) {
        val requestId: String? get() = ctrl.optString("requestId", "").takeIf { it.isNotEmpty() }
        val isRequest: Boolean get() = ctrl.optBoolean("request", false)
        val action: Int get() = ctrl.optInt("action", -1)
        val messageId: Int get() = ctrl.optInt("id", -1)
        val methodName: String? get() = method?.optString("methodName", "")?.takeIf { it.isNotEmpty() }
        val methodCallId: Long get() = method?.optLong("methodCallID", 0L) ?: 0L
        private val method: JSONObject?
            get() = ctrl.optJSONObject("control")?.optJSONObject("method")
        private val methodReturn: JSONObject?
            get() = method?.optJSONObject("methodReturn")
        val status: Int? get() = methodReturn?.takeIf { it.has("status") }?.optInt("status")
        val result: JSONObject? get() = methodReturn?.optJSONObject("result")
        val errorMsg: String? get() = methodReturn?.optString("error_msg", "")?.takeIf { it.isNotEmpty() }
        val dataChannel: PageInfo?
            get() = method?.optJSONObject("dataChannel")?.let { d ->
                PageInfo(
                    pageNo = d.optInt("page_no", 0),
                    total = d.optInt("total", 0),
                    pageSize = d.optLong("page_size", 0L),
                    totalSize = d.optLong("total_size", 0L),
                    md5 = d.optString("md5", "").takeIf { it.isNotEmpty() },
                )
            }

        val hasMethodCall: Boolean get() = method?.has("methodCall") == true
    }

    // ---------------------------------------------------------------- 编码

    fun newRequestId(appVersionCode: Int = 1): String =
        "$appVersionCode:${UUID.randomUUID()}"

    fun buildCtrlJson(r: Request): JSONObject {
        val method = JSONObject().apply {
            put("methodName", r.call.methodName)
            put("methodCallID", r.call.methodCallId)
            put("invokerID", r.call.invokerId)
            put("verifyCode", r.call.verifyCode)
            put(
                "methodCall",
                JSONObject().apply {
                    put("args", toJsonArray(r.call.args))
                    put("useDataChannelReturn", r.call.useDataChannelReturn)
                },
            )
        }
        val control = JSONObject().apply {
            put("type", r.controlType)
            put("method", method)
        }
        return JSONObject().apply {
            put("action", r.action)
            put("request", r.request)
            put("requestId", r.requestId)
            put("control", control)
            put("id", r.id)
        }
    }

    fun buildTailJson(r: Request): JSONObject = JSONObject().apply {
        put("requestId", r.requestId)
        // 键名必须是 deviceId（ga.d / IdentityInfo.JSON_KEY_DEVICE_ID），不是 device_id
        put("deviceId", r.tailDeviceId ?: "")
        put("encrypt", r.tailEncrypt)
    }

    private fun toJsonArray(list: List<Any?>): JSONArray {
        val arr = JSONArray()
        for (v in list) {
            when (v) {
                null -> arr.put(JSONObject.NULL)
                is List<*> -> {
                    val inner = JSONArray()
                    for (x in v) inner.put(x)
                    arr.put(inner)
                }
                is Array<*> -> {
                    val inner = JSONArray()
                    for (x in v) inner.put(x)
                    arr.put(inner)
                }
                else -> arr.put(v)
            }
        }
        return arr
    }

    fun encode(msg: Message): ByteArray {
        val ctrl = Bytes.utf8(msg.ctrl.toString())
        val tail = Bytes.utf8(msg.tail.toString())
        return encodeRaw(ctrl, msg.data, tail)
    }

    /** ctrl 段已加密时用这个编码：直接传密文字节 */
    fun encodeRaw(ctrlBytes: ByteArray, data: ByteArray, tailBytes: ByteArray): ByteArray =
        Bytes.concat(
            Bytes.i32be(ctrlBytes.size),
            Bytes.i32be(data.size),
            Bytes.i32be(tailBytes.size),
            ctrlBytes,
            data,
            tailBytes,
        )

    fun encode(r: Request): ByteArray = encode(
        Message(buildCtrlJson(r), EMPTY, buildTailJson(r))
    )

    // ------------------------------------------------- UDTPacket 分帧层（o6.c/o6.b）

    /**
     * TCP 6093 上真正的线级格式是「UDTPacket 分帧」再套上面的三段式消息：
     * ```
     * [0]=0x55 magic
     * [1]=0x10（高4位=version 1）| 0x08（还有后续分片时置位）
     * [7]=0x10（头长度 16，低5位）
     * [8..11]=本片 payload 长度（u32BE）
     * [12..13]=srcAppId（u16BE，本地 1025）
     * [14..15]=dstAppId（u16BE，电视侧 1025）
     * [payload]：消息层字节的一个分片（ctrl 连接单片上限 102400，data 连接 1440）
     * ```
     * 逆向依据：o6.c.l()/m()（UDTPacket 编码）、o6.d.b()（ctrl 发送）、
     * o6.g.j()/a.run()（data 发送，1440 分片循环）。
     */
    const val UDT_APP_ID = 1025
    const val PACKET_HEADER_LEN = 16
    const val CTRL_CHUNK_MAX = 102_400
    const val DATA_CHUNK_MAX = 1440

    fun udtPacketHeader(srcApp: Int, dstApp: Int, chunkLen: Int, more: Boolean): ByteArray =
        ByteArray(PACKET_HEADER_LEN).apply {
            this[0] = 0x55
            this[1] = (0x10 or (if (more) 0x08 else 0x00)).toByte()
            this[7] = 0x10
            this[8] = (chunkLen ushr 24).toByte()
            this[9] = (chunkLen ushr 16).toByte()
            this[10] = (chunkLen ushr 8).toByte()
            this[11] = chunkLen.toByte()
            this[12] = ((srcApp shr 8) and 0xFF).toByte()
            this[13] = (srcApp and 0xFF).toByte()
            this[14] = ((dstApp shr 8) and 0xFF).toByte()
            this[15] = (dstApp and 0xFF).toByte()
        }

    /**
     * 会话初始化（握手）帧：连接建立后必须立即发送，电视据此登记会话。
     * o6.d/o6.g 构造函数：payload = {0,0,0,0,5, isCtrl?0:1, srcApp(2B BE), dstApp(2B BE)}，
     * 且外层 UDTPacket 头的 src/dst 传 0（b(0,0,handshake)）。
     */
    fun handshakePacket(isCtrl: Boolean, srcApp: Int = UDT_APP_ID, dstApp: Int = UDT_APP_ID): ByteArray {
        val payload = byteArrayOf(
            0, 0, 0, 0, 5,
            (if (isCtrl) 0 else 1).toByte(),
            ((srcApp shr 8) and 0xFF).toByte(),
            (srcApp and 0xFF).toByte(),
            ((dstApp shr 8) and 0xFF).toByte(),
            (dstApp and 0xFF).toByte(),
        )
        return Bytes.concat(udtPacketHeader(0, 0, payload.size, more = false), payload)
    }

    /** payload 前 5 字节为 {0,0,0,0,5} → type=0 控制帧（握手），消息层不处理 */
    fun isControlPayload(payload: ByteArray): Boolean =
        payload.size >= 5 && payload[0] == 0.toByte() && payload[1] == 0.toByte() &&
            payload[2] == 0.toByte() && payload[3] == 0.toByte() && payload[4] == 5.toByte()

    // ---------------------------------------------------------------- 解码

    /** 读取帧头，返回三段的长度 (ctrlLen, dataLen, tailLen) */
    fun readLengths(header: ByteArray): Triple<Int, Int, Int> = Triple(
        Bytes.readI32(header, 0),
        Bytes.readI32(header, 4),
        Bytes.readI32(header, 8),
    )

    /** 原始三段解码（不做 ctrl 解密，供调用方按 tail.encrypt 决定后续处理） */
    fun decodeRaw(buf: ByteArray, offset: Int = 0): Triple<ByteArray, ByteArray, ByteArray> {
        val (ctrlLen, dataLen, tailLen) = readLengths(buf.copyOfRange(offset, offset + 12))
        var p = offset + 12
        val ctrl = buf.copyOfRange(p, p + ctrlLen)
        p += ctrlLen
        val data = buf.copyOfRange(p, p + dataLen)
        p += dataLen
        val tail = buf.copyOfRange(p, p + tailLen)
        return Triple(ctrl, data, tail)
    }

    fun decode(buf: ByteArray, offset: Int = 0): Message {
        val (ctrlLen, dataLen, tailLen) = readLengths(buf.copyOfRange(offset, offset + 12))
        var p = offset + 12
        val ctrl = if (ctrlLen > 0) String(buf, p, ctrlLen, Charsets.UTF_8) else "{}"
        p += ctrlLen
        val data = buf.copyOfRange(p, p + dataLen)
        p += dataLen
        val tail = if (tailLen > 0) String(buf, p, tailLen, Charsets.UTF_8) else "{}"
        return Message(JSONObject(ctrl), data, JSONObject(tail))
    }

    /** 整个帧的字节长度（用于流式读取） */
    fun totalLength(header: ByteArray): Int {
        val (c, d, t) = readLengths(header)
        return 12 + c + d + t
    }
}
