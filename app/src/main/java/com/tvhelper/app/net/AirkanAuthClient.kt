package com.tvhelper.app.net

import android.util.Log
import com.tvhelper.app.protocol.airkan.AirkanCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** requestAuth 响应 */
data class RequestAuthResult(
    val code: Int,
    val msg: String,
    val tvId: String?,
    /** 电视回传的加密 blob（需用授权码派生密钥 CBC 解密） */
    val publicKeyBlob: String?,
    /** 派生盐 */
    val verifyCodeAdditional: String?,
    val versionCode: String?,
)

class AirkanAuthException(message: String, val code: Int = -1) : Exception(message)

/**
 * AirKan 授权配对 HTTP 客户端（电视 6095 端口）。
 *
 * 逆向依据：a3.d AirkanHttpClient / a3.g HttpTask / a3.c AirkanEncryptUtils。
 *
 * 配对流程：
 * 1. `GET /requestAuth?device_id=<publicKeyMd5>` —— 电视弹出 6 位授权码，
 *    返回 `resp_data.{tv_id, public_key(密文), verify_code_additional}`
 * 2. 用户输入授权码 → 派生 AES/CBC key/iv（HMAC 迭代 1024 次）
 * 3. 用派生密钥 CBC 解密 public_key → `airkan` + 16 字节 + 电视授权 RSA 公钥
 * 4. 用电视公钥 RSA 加密 `airkandevicePublicKey=<本地公钥>&serial_num=1`
 * 5. `GET /completeAuth?device_id=..&magic=..&tail=0&encrypt=<RSA密文base64>`
 *    → 电视侧持久化配对，返回 `resp_data.tv_id`
 *
 * 此后：RC 版本握手（带 deviceId extra）会拿到 RSA 加密的 secretKey；
 * UDT ctrl 段用电视公钥 RSA 分块加密（上行）/ 会话派生密钥 CBC（下行）。
 */
object AirkanAuthClient {

    private const val TAG = "AirkanAuth"
    private const val PORT = 6095
    private const val TIMEOUT_MS = 15_000

    private fun url(host: String, path: String, query: String) =
        "http://$host:$PORT/$path?$query"

    private suspend fun get(host: String, path: String, query: String): String =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url(host, path, query)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code != 200) {
                    Log.w(TAG, "$path HTTP $code 响应体：$body")
                    throw AirkanAuthException("HTTP $code（$path）$body", code)
                }
                body
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    private fun parseEnvelope(raw: String): Triple<Int, String, JSONObject?> {
        val o = JSONObject(raw)
        val code = o.optInt("code", -1)
        val msg = o.optString("msg", "")
        return Triple(code, msg, o.optJSONObject("resp_data"))
    }

    /**
     * 发起配对：电视屏幕会显示 6 位授权码。
     * 注意必须先于输入授权码调用 —— 电视收到请求才会生成并展示授权码。
     */
    suspend fun requestAuth(host: String, deviceId: String): RequestAuthResult {
        val raw = get(host, "requestAuth", "device_id=$deviceId")
        Log.i(TAG, "requestAuth ← $raw")
        val (code, msg, data) = parseEnvelope(raw)
        if (code != 60000 || data == null) {
            throw AirkanAuthException("requestAuth 失败：code=$code $msg", code)
        }
        return RequestAuthResult(
            code = code,
            msg = msg,
            tvId = data.optString("tv_id").takeIf { it.isNotEmpty() },
            publicKeyBlob = data.optString("public_key").takeIf { it.isNotEmpty() },
            verifyCodeAdditional = data.optString("verify_code_additional").takeIf { it.isNotEmpty() },
            versionCode = data.optString("versionCode").takeIf { it.isNotEmpty() },
        )
    }

    /** 取消挂起的授权会话（冲突 60006 时使用） */
    suspend fun cancelAuth(host: String, deviceId: String) {
        runCatching {
            val raw = get(host, "cancelAuth", "device_id=$deviceId")
            Log.i(TAG, "cancelAuth ← $raw")
        }
    }

    /**
     * 完成配对。输入为 requestAuth 的响应 + 用户输入的 6 位授权码。
     * 返回 (tvId, cbcKey, cbcIv, tvAuthPublicKey)。
     */
    suspend fun completeAuth(
        host: String,
        deviceId: String,
        ourPublicKeyCustomB64: String,
        blobEncrypted: String,
        verifyCodeAdditional: String,
        code: String,
    ): CompleteAuthResult {
        require(code.length == 6) { "授权码必须是 6 位数字" }

        // 1) 授权码派生会话密钥
        val (cbcKey, cbcIv) = AirkanCrypto.deriveCbcKeyIv(code + verifyCodeAdditional)

        // 2) 解密电视授权公钥（明文形如 "airkan" + 16字节 + <X509 自定义Base64公钥>）
        val plain = AirkanCrypto.cbcDecrypt(blobEncrypted, cbcKey, cbcIv)
        Log.i(
            TAG,
            "completeAuth 调试：code=$code blob明文长度=${plain.length} 前40字符=${plain.take(40)}",
        )
        if (!plain.startsWith("airkan")) {
            throw AirkanAuthException("授权码不正确（解密校验失败）", 90007)
        }
        val tvAuthPub = plain.substring(6 + 16)

        // 3) 用电视公钥加密本机公钥（对齐 a3.d.h() → a3.c.e 的 "RSA" 裸变换 = NoPadding 单块）
        val payload = "airkandevicePublicKey=$ourPublicKeyCustomB64&serial_num=1"
        val encBytes = AirkanCrypto.rsaEncryptRaw(tvAuthPub, payload.toByteArray(Charsets.UTF_8))
        val enc = AirkanCrypto.b64Encode(encBytes)
        Log.i(
            TAG,
            "completeAuth 调试：blob明文长度=${plain.length} tvAuthPub长度=${tvAuthPub.length} " +
                "payload长度=${payload.length} 密文=${encBytes.size}B enc长度=${enc.length}",
        )

        // 4) completeAuth 查询串（z2.d.b：encrypt 为自定义 Base64，'$' 填充不含 '='，tail=0）
        val query = "device_id=$deviceId&magic=${AirkanCrypto.magicNumber()}&tail=0&encrypt=$enc"
        Log.i(TAG, "completeAuth query=${query.take(120)}…（总长 ${query.length}）")
        val raw = get(host, "completeAuth", query)
        Log.i(TAG, "completeAuth ← $raw")
        val (respCode, msg, data) = parseEnvelope(raw)
        if (respCode != 60000 || data == null) {
            throw AirkanAuthException("completeAuth 失败：code=$respCode $msg", respCode)
        }
        val tvId = data.optString("tv_id").takeIf { it.isNotEmpty() }
            ?: throw AirkanAuthException("completeAuth 未返回 tv_id", 90009)
        return CompleteAuthResult(tvId, cbcKey, cbcIv, tvAuthPub)
    }

    data class CompleteAuthResult(
        val tvId: String,
        val cbcKey: String,
        val cbcIv: String,
        val tvAuthPublicKey: String,
    )
}
