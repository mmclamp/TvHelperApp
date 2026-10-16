package com.tvhelper.app.protocol.rc

import com.tvhelper.app.protocol.Bytes

/**
 * RC type 4 —— 按键 SendKey。
 *
 * 逆向依据：g2.m(SendKeyPacket)、g2.k(SendKeyHeader)、g2.j(SendKeyData)。
 *
 * 完整帧：
 * ```
 * [04][payloadLen 2B BE]
 *   [subType=01][msgId 4B BE][dataLen 2B BE]      <- SendKeyHeader，7 字节
 *   [TLV ...]                                      <- SendKeyData
 * ```
 * payloadLen = 7 + dataLen。
 *
 * SendKeyData 的 TLV 项，按原实现的拼装顺序：
 * `01` metaState(i32) `02` action(i32) `03` keyCode(i32) `04` scanCode(i32)
 * `05` repeatCount(i32) `06` flags(i32) `07` downTime(i64) `08` eventTime(i64)
 * `0A` deviceId(i32) `0B` source(i32) `09` characters(u16+utf8) `0C` extra(u16+utf8)
 *
 * 电视端会用这些字段重建 `KeyEvent` 并注入输入系统，因此字段值需是合法的 Android 值。
 */
object RcKeyCodec {

    const val SUB_TYPE_REQUEST = 1

    // TLV 子码
    const val SUB_META_STATE = 1
    const val SUB_ACTION = 2
    const val SUB_KEY_CODE = 3
    const val SUB_SCAN_CODE = 4
    const val SUB_REPEAT_COUNT = 5
    const val SUB_FLAGS = 6
    const val SUB_DOWN_TIME = 7
    const val SUB_EVENT_TIME = 8
    const val SUB_CHARACTERS = 9
    const val SUB_DEVICE_ID = 10
    const val SUB_SOURCE = 11
    const val SUB_EXTRA = 12

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    /** KeyCharacterMap.VIRTUAL_KEYBOARD */
    const val VIRTUAL_KEYBOARD_ID = -1

    /** InputDevice.SOURCE_KEYBOARD */
    const val SOURCE_KEYBOARD = 0x00000101

    /** 原实现的 msgId 是包级自增计数（g2.k.f17006f） */
    @Volatile
    private var msgIdSeq = 0

    private fun nextMsgId(): Int {
        msgIdSeq = (msgIdSeq + 1) and 0x7FFFFFFF
        if (msgIdSeq == 0) msgIdSeq = 1
        return msgIdSeq
    }

    /** 仅供单测固定 msgId，使帧字节可复现 */
    @JvmStatic
    fun resetMsgIdForTest(value: Int) {
        msgIdSeq = value
    }

    data class KeyEvent(
        val keyCode: Int,
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val repeatCount: Int = 0,
        val metaState: Int = 0,
        val deviceId: Int = VIRTUAL_KEYBOARD_ID,
        val scanCode: Int = 0,
        val flags: Int = 0,
        val source: Int = SOURCE_KEYBOARD,
        val characters: String? = null,
    )

    private fun tlvInt(code: Int, v: Int): ByteArray =
        Bytes.concat(byteArrayOf(code.toByte()), Bytes.i32be(v))

    private fun tlvLong(code: Int, v: Long): ByteArray =
        Bytes.concat(byteArrayOf(code.toByte()), Bytes.i64be(v))

    private fun tlvString(code: Int, s: String): ByteArray {
        val b = Bytes.utf8(s)
        return Bytes.concat(byteArrayOf(code.toByte()), Bytes.i16be(b.size), b)
    }

    /** SendKeyData 的 TLV 体（不含 7 字节头） */
    fun buildKeyData(e: KeyEvent): ByteArray {
        var out = Bytes.concat(
            tlvInt(SUB_META_STATE, e.metaState),
            tlvInt(SUB_ACTION, e.action),
            tlvInt(SUB_KEY_CODE, e.keyCode),
            tlvInt(SUB_SCAN_CODE, e.scanCode),
        )
        out = Bytes.concat(
            out,
            tlvInt(SUB_REPEAT_COUNT, e.repeatCount),
            tlvInt(SUB_FLAGS, e.flags),
        )
        out = Bytes.concat(
            out,
            tlvLong(SUB_DOWN_TIME, e.downTime),
            tlvLong(SUB_EVENT_TIME, e.eventTime),
        )
        out = Bytes.concat(
            out,
            tlvInt(SUB_DEVICE_ID, e.deviceId),
            tlvInt(SUB_SOURCE, e.source),
        )
        val chars = e.characters
        if (!chars.isNullOrEmpty()) out = Bytes.concat(out, tlvString(SUB_CHARACTERS, chars))
        return out
    }

    /** Linux input.h 标准 scancode（物理遥控器同源）；电视端部分处理（如音量）依赖它 */
    fun scanCodeFor(keyCode: Int): Int = when (keyCode) {
        TvKey.DPAD_UP -> 103
        TvKey.DPAD_DOWN -> 108
        TvKey.DPAD_LEFT -> 105
        TvKey.DPAD_RIGHT -> 106
        TvKey.DPAD_CENTER -> 352   // KEY_OK
        TvKey.VOLUME_UP -> 115
        TvKey.VOLUME_DOWN -> 114
        TvKey.POWER -> 116
        TvKey.BACK -> 158
        TvKey.HOME -> 102
        TvKey.MENU -> 139
        TvKey.MUTE -> 113
        TvKey.MEDIA_PLAY_PAUSE, 85 -> 164
        TvKey.MEDIA_NEXT -> 163
        TvKey.MEDIA_PREVIOUS -> 165
        TvKey.MEDIA_REWIND -> 168
        TvKey.MEDIA_FAST_FORWARD -> 208
        TvKey.ENTER -> 352
        else -> 0
    }

    /** 完整 RC 帧（type 4） */
    fun frame(e: KeyEvent): ByteArray {
        val data = buildKeyData(e)
        val header = Bytes.concat(
            byteArrayOf(SUB_TYPE_REQUEST.toByte()),
            Bytes.i32be(nextMsgId()),
            Bytes.i16be(data.size),
        )
        return RcCodec.frame(RcCodec.TYPE_KEY, Bytes.concat(header, data))
    }

    /**
     * 按下帧（长按 repeat 也用它：repeatCount 递增、eventTime 用当前时刻）。
     * 时间戳必须用 SystemClock.uptimeMillis()（Android 输入系统惯例），
     * 绝不能用 currentTimeMillis()——wall clock 会让电视端输入队列时间判断异常。
     */
    fun frameDown(
        keyCode: Int,
        downTime: Long,
        eventTime: Long = downTime,
        repeatCount: Int = 0,
    ): ByteArray = frame(
        KeyEvent(
            keyCode,
            ACTION_DOWN,
            downTime = downTime,
            eventTime = eventTime,
            repeatCount = repeatCount,
            scanCode = scanCodeFor(keyCode),
        )
    )

    /** 抬起帧：eventTime 必须晚于 downTime（否则电视端输入系统可能丢弃） */
    fun frameUp(keyCode: Int, downTime: Long, eventTime: Long = downTime + 120): ByteArray = frame(
        KeyEvent(
            keyCode,
            ACTION_UP,
            downTime = downTime,
            eventTime = eventTime,
            scanCode = scanCodeFor(keyCode),
        )
    )

    /**
     * 一次点击 = DOWN + UP 两帧（downTime 由调用方传，必须是 uptimeMillis）。
     */
    fun framesDownUp(keyCode: Int, downTime: Long): List<ByteArray> = listOf(
        frameDown(keyCode, downTime, eventTime = downTime),
        frameUp(keyCode, downTime, eventTime = downTime + 120),
    )
}

/** 常用遥控按键码（标准 Android KeyEvent 值） */
object TvKey {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val ENTER = 66

    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26

    const val BACK = 4
    const val HOME = 3
    const val MENU = 82

    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90

    const val DEL = 67
    const val MUTE = 164
    const val ESCAPE = 111
    const val TAB = 61
    const val SEARCH = 84
    const val SETTINGS = 176
}
