package com.tvhelper.app.protocol.rc

import com.tvhelper.app.protocol.Bytes

/**
 * RC type 5 —— 版本 / 密钥协商包。
 *
 * 逆向依据：g2.r(VersionPacket)、v1.q(VersionData)、h2.h.L() 与 h2.h.B()。
 *
 * 外层帧：`[05][len 2B BE][code 1B][VersionData ...]`
 * - code == 1 客户端请求
 * - code == 2 电视响应
 *
 * VersionData 为 TLV（大端）：
 * - 请求：`01`+version(4B) `02`+u16 长度+版本文字 `04`+u16 长度+extra JSON
 * - 响应：`01`+version(4B) `03`+1B 结果位 `04`+u16 长度+extra JSON
 *
 * extra JSON 里的 `secretKey` 即会话密钥（RSA 加密的 Base64），
 * 电视版本 > [CRYPTO_THRESHOLD] 时会下发，用于 AES/ECB/PKCS5 加密后续帧。
 */
object RcVersionCodec {

    const val CODE_REQUEST = 1
    const val CODE_RESPONSE = 2

    /** 原实现默认版本号 16777216 = 0x01000000 */
    const val DEFAULT_VERSION = 0x01000000
    const val DEFAULT_TEXT = "Airkan Protocol Version 1.0"

    /** 电视回包版本 > 该值才会下发 secretKey（原实现 16777984 = 0x01000200） */
    const val CRYPTO_THRESHOLD = 0x01000200

    private const val SUB_VERSION = 1
    private const val SUB_TEXT = 2
    private const val SUB_RESULT = 3
    private const val SUB_EXTRA = 4

    data class RcVersion(
        val version: Int = DEFAULT_VERSION,
        val text: String = DEFAULT_TEXT,
        val extra: String? = null
    )

    data class RcVersionResponse(
        val code: Int,
        val version: Int,
        val text: String?,
        val extra: String?
    ) {
        val needsCrypto: Boolean get() = version > CRYPTO_THRESHOLD

        /** 从 extra JSON 中取 secretKey（可能为空） */
        val secretKey: String?
            get() = extra?.let { e ->
                runCatching { org.json.JSONObject(e).optString("secretKey", "") }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            }
    }

    private fun tlvString(code: Int, s: String): ByteArray {
        val body = Bytes.utf8(s)
        return Bytes.concat(byteArrayOf(code.toByte()), Bytes.i16be(body.size), body)
    }

    fun buildVersionData(v: RcVersion): ByteArray {
        var out = Bytes.concat(byteArrayOf(SUB_VERSION.toByte()), Bytes.i32be(v.version))
        out = Bytes.concat(out, tlvString(SUB_TEXT, v.text))
        v.extra?.let { out = Bytes.concat(out, tlvString(SUB_EXTRA, it)) }
        return out
    }

    /** 客户端请求帧（完整 RC 帧） */
    fun frameRequest(v: RcVersion = RcVersion()): ByteArray {
        val payload = Bytes.concat(byteArrayOf(CODE_REQUEST.toByte()), buildVersionData(v))
        return RcCodec.frame(RcCodec.TYPE_VERSION, payload)
    }

    /** 解析电视回包（完整 RC 帧） */
    fun parse(frame: ByteArray): RcVersionResponse {
        require(frame.size >= 4) { "版本包过短" }
        require(Bytes.u8(frame, 0) == RcCodec.TYPE_VERSION) { "非 type5 帧" }
        val code = Bytes.u8(frame, 3)
        val data = frame.copyOfRange(4, frame.size)

        var version = 0
        var text: String? = null
        var extra: String? = null

        var i = 0
        while (i < data.size) {
            val sc = Bytes.u8(data, i)
            when (sc) {
                SUB_VERSION -> {
                    if (i + 5 > data.size) break
                    version = Bytes.readI32(data, i + 1)
                    i += 5
                }
                SUB_TEXT -> {
                    if (i + 3 > data.size) break
                    val len = Bytes.readI16(data, i + 1)
                    if (i + 3 + len > data.size) break
                    text = String(data, i + 3, len, Charsets.UTF_8)
                    i += 3 + len
                }
                SUB_RESULT -> {
                    i += 2
                }
                SUB_EXTRA -> {
                    if (i + 3 > data.size) break
                    val len = Bytes.readI16(data, i + 1)
                    if (i + 3 + len > data.size) break
                    extra = String(data, i + 3, len, Charsets.UTF_8)
                    i += 3 + len
                }
                else -> break
            }
        }
        return RcVersionResponse(code, version, text, extra)
    }
}
