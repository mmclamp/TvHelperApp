package com.tvhelper.app.protocol.rc

import com.tvhelper.app.protocol.Bytes

/**
 * RC 通道（AirKan RemoteControl，TCP 30612）消息帧。
 *
 * 逆向依据：g2.d(RCHeader)、h2.h(RCClientThread)、m2.c(OldMessages)。
 *
 * 帧结构（3 字节头，全部大端）：
 * ```
 * [type 1B][payloadLen 2B BE][payload ...]
 * ```
 * payload 上限 1500 字节（原实现硬校验）。
 *
 * type 取值：
 * - 2  == 心跳（payload 为空）
 * - 3  == 播放控制
 * - 4  == 按键 SendKey
 * - 5  == 版本 / 密钥协商
 * - 6  == 触摸 SendTouch
 * - 8  == 输入法上屏
 * - 11 == 红外
 * - 61 == 连接就绪握手（0x3D，payload 为空）
 */
object RcCodec {

    const val PORT = 30612
    const val MAX_PAYLOAD = 1500

    const val TYPE_HEARTBEAT = 2
    const val TYPE_PLAY = 3
    const val TYPE_KEY = 4
    const val TYPE_VERSION = 5
    const val TYPE_TOUCH = 6
    const val TYPE_IME = 8
    const val TYPE_IR = 11
    const val TYPE_READY = 61

    /** 心跳帧 {2,0,0}，客户端每 10s 发一次 */
    fun heartbeat(): ByteArray = byteArrayOf(TYPE_HEARTBEAT.toByte(), 0, 0)

    /** 连接就绪帧 {61,0,0}（0x3D），TCP 建连后立即发送 */
    fun ready(): ByteArray = byteArrayOf(TYPE_READY.toByte(), 0, 0)

    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "RC payload 超长: ${payload.size} > $MAX_PAYLOAD"
        }
        return Bytes.concat(byteArrayOf(type.toByte()), Bytes.i16be(payload.size), payload)
    }

    /** 仅解析 3 字节头，返回 (type, payloadLen) */
    fun parseHeader(header: ByteArray): Pair<Int, Int> =
        Pair(Bytes.u8(header, 0), Bytes.readI16(header, 1))

    fun typeOf(frame: ByteArray): Int = Bytes.u8(frame, 0)
}
