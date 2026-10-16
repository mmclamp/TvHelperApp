package com.tvhelper.app.protocol

/**
 * 字节工具：小米电视助手（多看 AirKan / milink UDT）协议全部使用 **大端序**。
 * 参考逆向：v1.p (TypeConvertor)、ia.f (TypeUtil)。
 */
object Bytes {

    fun i16be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    fun i32be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    fun i64be(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }

    fun f32be(v: Float): ByteArray = i32be(java.lang.Float.floatToIntBits(v))

    fun readI16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun readI32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun readI64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    fun readF32(b: ByteArray, off: Int): Float = java.lang.Float.intBitsToFloat(readI32(b, off))

    fun concat(vararg parts: ByteArray): ByteArray {
        var size = 0
        for (p in parts) size += p.size
        val out = ByteArray(size)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun toHex(b: ByteArray): String = buildString {
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (v < 16) append('0')
            append(Integer.toHexString(v))
        }
    }

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
}
