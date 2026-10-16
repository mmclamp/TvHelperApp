package com.tvhelper.app.protocol.airkan

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirKan 授权配对与加密的工具集。
 *
 * 逆向依据（原 App 反编译）：
 * - a3.e Base64Utils：自定义 Base64，用 `$` 代替 `=` 填充
 * - a3.f EncodeUtils：MD5 hex
 * - b3.a AirkanKeyPair：RSA-1024，PKCS8/X509 DER 的自定义 Base64 序列化，
 *   device_id = publicKeyMd5
 * - a3.b AirkanEncryptHelper：HMAC-SHA256（固定 key）迭代 1024 次派生 CBC 会话密钥
 * - a3.c AirkanEncryptUtils：AES/ECB、AES/CBC/PKCS5、RSA/ECB/PKCS1（分块）
 * - z2.d AirkanUtils：派生串前 16 字符为 key、中间偏移 16 字符为 iv；magic 生成
 * - a3.a AESUtil：RC 通道 AES/ECB/PKCS5（密钥为标准 Base64 文本）
 */
object AirkanCrypto {

    /** HMAC 派生的固定盐（原样取自 a3.b） */
    private const val HMAC_KEY = "3e4f2550-0818-4665-9bfb-edbe9b15f586"

    // ------------------------------------------------------------ 自定义 Base64（$ 填充）

    fun b64Encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('=', '$')

    fun b64Decode(s: String): ByteArray =
        Base64.getMimeDecoder().decode(s.replace('$', '='))

    // ------------------------------------------------------------ MD5

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }

    // ------------------------------------------------------------ 本地 RSA 密钥对

    /** 序列化格式与 b3.a AirkanKeyPair 一致，便于理解与调试 */
    data class KeyPairB64(
        val privateKey: String,   // PKCS8 DER 的自定义 Base64
        val privateKeyMd5: String,
        val publicKey: String,    // X509 DER 的自定义 Base64
        val publicKeyMd5: String, // 即 AirKan 的 device_id
    ) {
        fun toJson(): String = JSONObject().apply {
            put("privateKey", privateKey)
            put("privateKeyMd5", privateKeyMd5)
            put("publicKey", publicKey)
            put("publicKeyMd5", publicKeyMd5)
        }.toString()

        companion object {
            fun fromJson(s: String): KeyPairB64? = runCatching {
                val o = JSONObject(s)
                KeyPairB64(
                    privateKey = o.getString("privateKey"),
                    privateKeyMd5 = o.getString("privateKeyMd5"),
                    publicKey = o.getString("publicKey"),
                    publicKeyMd5 = o.getString("publicKeyMd5"),
                )
            }.getOrNull()
        }
    }

    fun generateKeyPair(): KeyPairB64 {
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(1024)
        val kp = gen.generateKeyPair()
        val priv = kp.private.encoded
        val pub = kp.public.encoded
        return KeyPairB64(
            privateKey = b64Encode(priv),
            privateKeyMd5 = md5Hex(priv),
            publicKey = b64Encode(pub),
            publicKeyMd5 = md5Hex(pub),
        )
    }

    // ------------------------------------------------------------ 授权码派生 CBC 会话密钥

    /**
     * 原逻辑（z2.d.f + a3.b.b + z2.d.a）：
     * 1. 种子 = (6位授权码 + verify_code_additional) 的字节
     * 2. 扩展成 1024 字节：前 1000 字节按种子循环填充，其余为 0
     * 3. HMAC-SHA256 连续迭代 10000 次（每次 update 上一次结果再 doFinal）
     *    —— 迭代次数取自原代码里的 netty Http2CodecUtil.DEFAULT_MAX_QUEUED_CONTROL_FRAMES（=10000）
     * 4. 结果自定义 Base64（44 字符），key = 前 16 字符，iv = [22,38) 16 字符
     */
    fun deriveCbcKeyIv(codePlusAdditional: String): Pair<String, String> {
        val seed = codePlusAdditional.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1024)
        for (i in 0 until 1000) buf[i] = seed[i % seed.size]

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var h = buf
        repeat(10_000) {
            mac.update(h)
            h = mac.doFinal()
        }

        val s = b64Encode(h)
        require(s.length >= 32) { "派生串过短：$s" }
        val half = s.length / 2
        return s.substring(0, 16) to s.substring(half, half + 16)
    }

    // ------------------------------------------------------------ AES/CBC（授权 HTTP 数据 + 电视下行 ctrl）

    /** 返回自定义 Base64 的密文（对齐 a3.c.a encrypt 分支：先加密再 base64） */
    fun cbcEncrypt(plain: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        return b64Encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 输入为自定义 Base64 密文（对齐 a3.c.a decrypt 分支：先 base64 解码再解密） */
    fun cbcDecrypt(cipherB64: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        return String(cipher.doFinal(b64Decode(cipherB64)), Charsets.UTF_8)
    }

    // ------------------------------------------------------------ AES/ECB（RC 通道会话密钥）

    /** secretKey 明文是标准 Base64 的 AES 密钥文本（a3.a.d 用 Base64.decode） */
    fun ecbCipherFromKeyText(keyText: String): SecretKeySpec {
        val raw = Base64.getMimeDecoder().decode(keyText)
        return SecretKeySpec(raw, "AES")
    }

    fun ecbEncrypt(data: ByteArray, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    fun ecbDecrypt(data: ByteArray, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    // ------------------------------------------------------------ RSA

    private fun publicKeyOf(pubCustomB64: String): RSAPublicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(b64Decode(pubCustomB64))) as RSAPublicKey

    private fun privateKeyOf(privCustomB64: String): RSAPrivateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(b64Decode(privCustomB64))) as RSAPrivateKey

    /**
     * RSA/ECB/PKCS1 加密，忠实复刻 a3.c.f：
     * 分块大小 = cipher.getOutputSize(100) - 15（2048 位密钥即 241，1024 位即 113），
     * 明文不超过分块大小时单块加密。用于 UDT ctrl 段与 completeAuth。
     */
    fun rsaEncryptChunked(pubCustomB64: String, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKeyOf(pubCustomB64))
        val outputSize = cipher.getOutputSize(100)
        val chunk = if (outputSize == 0 || outputSize > 256) 240 else outputSize - 15
        if (data.size <= chunk) return cipher.doFinal(data)
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val len = minOf(chunk, data.size - off)
            out.write(cipher.doFinal(data, off, len))
            off += len
        }
        return out.toByteArray()
    }

    /**
     * RSA/ECB/PKCS1 解密（a3.c.c）。支持分块密文（按 128 字节逐块解）。
     * 用于：RC type5 响应里的 secretKey（单块）。
     */
    fun rsaDecrypt(privCustomB64: String, cipherCustomB64: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKeyOf(privCustomB64))
        val data = b64Decode(cipherCustomB64)
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val len = minOf(128, data.size - off)
            out.write(cipher.doFinal(data, off, len))
            off += len
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ------ "RSA" 裸变换（a3.c.e / a3.c.c 用 Cipher.getInstance("RSA")）------
    //
    // completeAuth 明文 251 字节 > RSA-2048 PKCS1 上限（245）：PKCS1 单块必抛异常、
    // 分块则电视端解不出正确前缀 —— 只有 NoPadding 单块在数学上可行。
    // 解 secretKey 时同样按 NoPadding 处理：得到「前导零 + 密钥文本」，
    // 由调用方用容忍非字母表字符的 Base64 解码剥离前导零。

    fun rsaEncryptRaw(pubCustomB64: String, data: ByteArray): ByteArray {
        val pub = publicKeyOf(pubCustomB64)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        require(data.size <= pub.modulus.bitLength() / 8) { "明文 ${data.size}B 超过模长" }
        return cipher.doFinal(data)
    }

    fun rsaDecryptRaw(privCustomB64: String, cipherCustomB64: String): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKeyOf(privCustomB64))
        return cipher.doFinal(b64Decode(cipherCustomB64))
    }

    // ------------------------------------------------------------ 杂项

    /** 对齐 z2.d.c 的 6 位 magic 生成（值本身不影响电视侧校验，按原样复刻） */
    fun magicNumber(): String {
        val r = Random()
        val a = r.nextInt(10)
        val b = r.nextInt(10)
        val c = r.nextInt(10)
        val d = r.nextInt(10)
        var e = r.nextInt(10) + 1
        if (e == 10) e--
        val first = when ((b % 2) + (c % 3) + (d % 4) + (e % 5)) {
            2, 4, 6, 8, 10 -> 2
            3, 5, 7 -> (b % 2) + (c % 3) + (d % 4) + (e % 5)
            9 -> 3
            else -> 1
        }
        return "$first$e$d$c$b$a"
    }
}
