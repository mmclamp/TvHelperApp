package com.tvhelper.app.protocol.rc

import com.tvhelper.app.protocol.Bytes
import java.util.concurrent.atomic.AtomicInteger

/**
 * RC type 3 —— 播放控制（PlayControl）。
 *
 * 逆向依据：v1/l(PlayControlPacket)、v1/j(PlayControlHeader)、v1/i(PlayControlData)、
 * com/duokan/airkan/phone/a.s0（原版 setVolume 实现）。
 *
 * 完整帧：
 * ```
 * [03][payloadLen 2B BE]
 *   [subType 1B][id 4B BE][dataLen 2B BE]     <- PlayControlHeader，7 字节（与 SendKey 头同构）
 *   [data ...]                                 <- PlayControlData
 * ```
 *
 * 子类型（原版 l.java 的方法与字节值）：
 * `1/16` play · `2` stop · `3` pause · `4` resume · `5` seek · `7` query（状态查询）
 * `12` 分辨率 · `13` source · **`14` setVolume**
 *
 * **音量不走按键**：原版遥控界面用滑条直接设绝对音量——
 * setVolume = subType 14 + 单字节数值；查询 = subType 7 + 数据为要查的项码列表，
 * 音量项码 = 12，响应数据为 `[项码, 值]` 序列（音量值 1 字节）。
 */
object RcPlayControlCodec {

    const val SUB_PLAY = 16
    const val SUB_STOP = 2
    const val SUB_PAUSE = 3
    const val SUB_RESUME = 4
    const val SUB_SEEK = 5
    const val SUB_QUERY = 7
    const val SUB_RESOLUTION = 12
    const val SUB_SOURCE = 13
    const val SUB_SET_VOLUME = 14

    /** 查询项码：音量 */
    const val QUERY_ITEM_VOLUME = 12

    private val idSeq = AtomicInteger(0)

    /**
     * 设置音量帧。原版 a.s0：`data.u(volume)` → 单字节绝对音量值。
     * 电视端会自行钳位到实际范围（部分机型 max=50/100）。
     */
    fun frameSetVolume(volume: Int): ByteArray =
        frame(SUB_SET_VOLUME, byteArrayOf(volume.coerceIn(0, 100).toByte()))

    /** 查询音量帧：数据为要查询的项码列表，这里只查音量（12） */
    fun frameQueryVolume(): ByteArray = frame(SUB_QUERY, byteArrayOf(QUERY_ITEM_VOLUME.toByte()))

    /** 播放控制帧：[03][len][subType][id u32BE][dataLen u16BE][data] */
    fun frame(subType: Int, data: ByteArray): ByteArray {
        val id = idSeq.incrementAndGet()
        val header = Bytes.concat(
            byteArrayOf(subType.toByte()),
            Bytes.i32be(id),
            Bytes.i16be(data.size),
        )
        return RcCodec.frame(RcCodec.TYPE_PLAY, Bytes.concat(header, data))
    }

    /**
     * 从查询响应数据里提取音量。
     * 响应数据是 `[项码 1B][值 1B]` 序列（v1/i.l 构造），项码 12 为音量。
     * 解析容错：长度不齐或码不匹配时跳过。
     */
    fun extractVolume(data: ByteArray): Int? {
        var i = 0
        while (i + 1 < data.size) {
            val code = data[i].toInt() and 0xFF
            val value = data[i + 1].toInt() and 0xFF
            if (code == QUERY_ITEM_VOLUME) return value
            i += 2
        }
        return null
    }
}
