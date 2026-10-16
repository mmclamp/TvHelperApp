package com.tvhelper.app.protocol.rc

import com.tvhelper.app.protocol.Bytes

/**
 * RC type 6 —— 触摸 SendTouch。
 *
 * 逆向依据：g2.p(SendTouchPacket)、g2.o(SendTouchHeader)、g2.n(SendTouchData)。
 *
 * 帧结构：
 * ```
 * [06][payloadLen 2B BE]
 *   [subType=01][msgId 4B BE][dataLen 2B BE]      <- SendTouchHeader
 *   [TLV ...]                                      <- SendTouchData
 * ```
 *
 * SendTouchData 为 MotionEvent 全量序列化，TLV 子码（对应 Netty opcode 常量）：
 * ```
 * 04 downTime(i64)  05 eventTime(i64)  02 action(i32)  0C pointerCount(i32)
 * 每指: 0D index(i32) 0E id(i32) 0F toolType(i32)
 *       10 orientation(f32) 11 pressure(f32) 12 size(f32)
 *       13 toolMajor(f32) 14 toolMinor(f32) 15 touchMajor(f32) 16 touchMinor(f32)
 *       1B x(f32) 17 y(f32)
 * 01 metaState(i32)  08 buttonState(i32)  09 xPrecision(f32)  0A yPrecision(f32)
 * 06 deviceId(i32)  0B edgeFlags(i32)  07 source(i32)  03 flags(i32)
 * ```
 */
object RcTouchCodec {

    const val SUB_TYPE_REQUEST = 1

    // MotionEvent action
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3

    const val TOOL_TYPE_FINGER = 1

    /** InputDevice.SOURCE_TOUCHSCREEN */
    const val SOURCE_TOUCHSCREEN = 0x00001002

    /** InputDevice.SOURCE_MOUSE —— 用鼠标源可得到「指针式」拖动，部分电视更适配 */
    const val SOURCE_MOUSE = 0x00002002

    @Volatile
    private var msgIdSeq = 0

    private fun nextMsgId(): Int {
        msgIdSeq = (msgIdSeq + 1) and 0x7FFFFFFF
        if (msgIdSeq == 0) msgIdSeq = 1
        return msgIdSeq
    }

    /** 仅供单测固定 msgId */
    @JvmStatic
    fun resetMsgIdForTest(value: Int) {
        msgIdSeq = value
    }

    data class TouchPointer(
        val id: Int = 0,
        val toolType: Int = TOOL_TYPE_FINGER,
        val x: Float,
        val y: Float,
        val pressure: Float = 1f,
        val size: Float = 1f,
        val orientation: Float = 0f,
        val toolMajor: Float = 1f,
        val toolMinor: Float = 1f,
        val touchMajor: Float = 1f,
        val touchMinor: Float = 1f,
    )

    data class TouchEvent(
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val pointers: List<TouchPointer>,
        val metaState: Int = 0,
        val buttonState: Int = 0,
        val xPrecision: Float = 1f,
        val yPrecision: Float = 1f,
        val deviceId: Int = RcKeyCodec.VIRTUAL_KEYBOARD_ID,
        val edgeFlags: Int = 0,
        val source: Int = SOURCE_TOUCHSCREEN,
        val flags: Int = 0,
    )

    private fun i(code: Int, v: Int) = Bytes.concat(byteArrayOf(code.toByte()), Bytes.i32be(v))
    private fun l(code: Int, v: Long) = Bytes.concat(byteArrayOf(code.toByte()), Bytes.i64be(v))
    private fun f(code: Int, v: Float) = Bytes.concat(byteArrayOf(code.toByte()), Bytes.f32be(v))

    fun buildTouchData(e: TouchEvent): ByteArray {
        var out = Bytes.concat(
            l(4, e.downTime),
            l(5, e.eventTime),
            i(2, e.action),
            i(0x0C, e.pointers.size),
        )
        e.pointers.forEachIndexed { index, p ->
            out = Bytes.concat(
                out,
                i(0x0D, index),
                i(0x0E, p.id),
                i(0x0F, p.toolType),
                f(0x10, p.orientation),
                f(0x11, p.pressure),
                f(0x12, p.size),
                f(0x13, p.toolMajor),
                f(0x14, p.toolMinor),
                f(0x15, p.touchMajor),
                f(0x16, p.touchMinor),
                f(0x1B, p.x),
                f(0x17, p.y),
            )
        }
        out = Bytes.concat(
            out,
            i(1, e.metaState),
            i(8, e.buttonState),
            f(9, e.xPrecision),
            f(10, e.yPrecision),
        )
        out = Bytes.concat(
            out,
            i(6, e.deviceId),
            i(11, e.edgeFlags),
            i(7, e.source),
            i(3, e.flags),
        )
        return out
    }

    fun frame(e: TouchEvent): ByteArray {
        val data = buildTouchData(e)
        val header = Bytes.concat(
            byteArrayOf(SUB_TYPE_REQUEST.toByte()),
            Bytes.i32be(nextMsgId()),
            Bytes.i16be(data.size),
        )
        return RcCodec.frame(RcCodec.TYPE_TOUCH, Bytes.concat(header, data))
    }
}
