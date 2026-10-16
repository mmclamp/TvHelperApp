package com.tvhelper.app.data

import android.content.Context
import android.content.SharedPreferences
import com.tvhelper.app.protocol.airkan.AirkanCrypto

/** 一台电视的 AirKan 配对凭据 */
data class TvPairing(
    val deviceId: String,      // 本机 publicKeyMd5（全局一致）
    val tvId: String,
    val tvAuthPublicKey: String, // 电视授权 RSA 公钥（自定义 Base64）
    val cbcKey: String,        // 授权码派生会话密钥（电视下行 ctrl 解密）
    val cbcIv: String,
)

/**
 * AirKan 配对凭据持久化（SharedPreferences，对齐原实现的 airkan_sdk_pref）：
 * - 本地 RSA-1024 密钥对（全局一份，删除即重置 device_id）
 * - 每台电视 host 的配对结果（tvId / 电视公钥 / CBC 会话密钥）
 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("airkan_pairing", Context.MODE_PRIVATE)

    @Volatile
    private var cachedKeyPair: AirkanCrypto.KeyPairB64? = null

    /** 取本地密钥对，没有则生成并持久化（device_id = publicKeyMd5） */
    @Synchronized
    fun ensureKeyPair(): AirkanCrypto.KeyPairB64 {
        cachedKeyPair?.let { return it }
        val saved = prefs.getString(KEY_KEYPAIR, null)
            ?.let { AirkanCrypto.KeyPairB64.fromJson(it) }
        val kp = saved ?: AirkanCrypto.generateKeyPair().also {
            prefs.edit().putString(KEY_KEYPAIR, it.toJson()).apply()
        }
        cachedKeyPair = kp
        return kp
    }

    fun pairingFor(host: String): TvPairing? {
        val raw = prefs.getString("$PREFIX_HOST$host", null) ?: return null
        return runCatching {
            val o = org.json.JSONObject(raw)
            TvPairing(
                deviceId = o.getString("deviceId"),
                tvId = o.getString("tvId"),
                tvAuthPublicKey = o.getString("tvAuthPub"),
                cbcKey = o.getString("cbcKey"),
                cbcIv = o.getString("cbcIv"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun savePairing(host: String, pairing: TvPairing) {
        val o = org.json.JSONObject().apply {
            put("deviceId", pairing.deviceId)
            put("tvId", pairing.tvId)
            put("tvAuthPub", pairing.tvAuthPublicKey)
            put("cbcKey", pairing.cbcKey)
            put("cbcIv", pairing.cbcIv)
        }
        prefs.edit().putString("$PREFIX_HOST$host", o.toString()).apply()
    }

    @Synchronized
    fun clearPairing(host: String) {
        prefs.edit().remove("$PREFIX_HOST$host").apply()
    }

    companion object {
        private const val KEY_KEYPAIR = "local_keypair"
        private const val PREFIX_HOST = "pairing_"
    }
}
