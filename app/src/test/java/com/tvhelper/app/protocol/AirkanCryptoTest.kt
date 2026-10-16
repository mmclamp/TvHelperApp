package com.tvhelper.app.protocol

import com.tvhelper.app.protocol.airkan.AirkanCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirKan 加密工具单测：
 * - 自定义 Base64（$ 填充）与标准 Base64 互换
 * - HMAC 迭代派生：与独立 Python 实现对齐的黄金值
 * - AES/CBC、AES/ECB、RSA 往返
 */
class AirkanCryptoTest {

    @Test
    fun customBase64MatchesStandardWithDollarPadding() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(1, 2),
            byteArrayOf(1, 2, 3),
            "airkandevicePublicKey=abc&serial_num=1".toByteArray(),
            ByteArray(100) { it.toByte() },
            ByteArray(128) { it.toByte() },
        )
        for (b in samples) {
            val custom = AirkanCrypto.b64Encode(b)
            val std = java.util.Base64.getEncoder().encodeToString(b)
            assertEquals(std.replace('=', '$'), custom)
            assertArrayEquals(b, AirkanCrypto.b64Decode(custom))
        }
    }

    /** 黄金值由独立 Python 实现（hmac + sha256，迭代 10000 次）生成 */
    @Test
    fun deriveCbcKeyIvMatchesGoldenValues() {
        // seed = "123456abc"
        val (k1, iv1) = AirkanCrypto.deriveCbcKeyIv("123456abc")
        assertEquals("fyEY7FI+gxfNXQk0", k1)
        assertEquals("RDPqIzAMN8sBvPYh", iv1)

        // seed = "654321"
        val (k2, iv2) = AirkanCrypto.deriveCbcKeyIv("654321")
        assertEquals("KjoJzMYJnvuQs8k/", k2)
        assertEquals("vBhiHzgy4jSxoP9K", iv2)
    }

    @Test
    fun cbcRoundTrip() {
        val (key, iv) = AirkanCrypto.deriveCbcKeyIv("123456abc")
        val plain = "airkan" + "0123456789abcdef" + "MIIBIDANBgkqhkix00b64PADDING=="
        val enc = AirkanCrypto.cbcEncrypt(plain, key, iv)
        assertEquals(plain, AirkanCrypto.cbcDecrypt(enc, key, iv))
    }

    @Test
    fun ecbRoundTripWithBase64KeyText() {
        // 16 字节 AES 密钥，keyText 为标准 base64
        val raw = ByteArray(16) { (it * 7 + 3).toByte() }
        val keyText = java.util.Base64.getEncoder().encodeToString(raw)
        val key = AirkanCrypto.ecbCipherFromKeyText(keyText)
        val data = "RC session payload 加密测试 1234567890".toByteArray()
        val enc = AirkanCrypto.ecbEncrypt(data, key)
        assertArrayEquals(data, AirkanCrypto.ecbDecrypt(enc, key))
    }

    @Test
    fun rsaRoundTripAndChunking() {
        val kp = AirkanCrypto.generateKeyPair()
        assertEquals(32, kp.publicKeyMd5.length)
        assertTrue(kp.publicKey.endsWith("$") || !kp.publicKey.contains("="))

        // 短明文（单块）
        val short = "airkandevicePublicKey=XXX&serial_num=1"
        val encShort = AirkanCrypto.rsaEncryptChunked(kp.publicKey, short.toByteArray())
        assertEquals(short, AirkanCrypto.rsaDecrypt(kp.privateKey, AirkanCrypto.b64Encode(encShort)))

        // 多块明文（>113 字节，触发分块）
        val long = ByteArray(300) { ((it * 31) and 0x7F).toByte() }
        val encLong = AirkanCrypto.rsaEncryptChunked(kp.publicKey, long)
        assertEquals(128, encLong.size / 3) // ceil(300/113)=3 块 × 128 字节
        val decLong = AirkanCrypto.rsaDecrypt(kp.privateKey, AirkanCrypto.b64Encode(encLong))
        assertArrayEquals(long, decLong.toByteArray())
    }

    @Test
    fun rsaRawRoundTrip() {
        // 电视授权密钥为 RSA-2048（模长 256B），此处同规格生成以便容纳 251B 明文
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp2048 = gen.generateKeyPair()
        val pubB64 = AirkanCrypto.b64Encode(kp2048.public.encoded)
        val privB64 = AirkanCrypto.b64Encode(kp2048.private.encoded)
        // NoPadding 单块：明文不超过模长（2048 位 = 256 字节）
        val payload = "airkandevicePublicKey=${AirkanCrypto.generateKeyPair().publicKey}&serial_num=1".toByteArray()
        val enc = AirkanCrypto.rsaEncryptRaw(pubB64, payload)
        assertEquals(256, enc.size)
        val dec = AirkanCrypto.rsaDecryptRaw(privB64, AirkanCrypto.b64Encode(enc))
        // NoPadding 解密带前导零，剥离后应与原文一致
        var start = 0
        while (start < dec.size && dec[start] == 0.toByte()) start++
        assertArrayEquals(payload, dec.copyOfRange(start, dec.size))
        // 容错解码：前导零不应影响 MIME Base64 解码出的密钥文本
        val keyText = "abcdefgh12345678"
        val encKey = AirkanCrypto.rsaEncryptRaw(pubB64, keyText.toByteArray())
        val decKey = String(AirkanCrypto.rsaDecryptRaw(privB64, AirkanCrypto.b64Encode(encKey)), Charsets.ISO_8859_1)
        assertEquals(keyText, decKey.trimStart('\u0000'))
    }

    @Test
    fun keyPairSerializationRoundTrip() {
        val kp = AirkanCrypto.generateKeyPair()
        val restored = AirkanCrypto.KeyPairB64.fromJson(kp.toJson())!!
        assertEquals(kp, restored)
    }

    @Test
    fun magicNumberIsSixDigits() {
        repeat(20) {
            val m = AirkanCrypto.magicNumber()
            assertEquals(6, m.length)
            assertTrue(m.all { it.isDigit() })
        }
    }
}
