package com.tvhelper.app.net

import android.util.Log
import com.tvhelper.app.protocol.udt.RemoteApp
import com.tvhelper.app.protocol.udt.UdtCodec
import com.tvhelper.app.protocol.udt.UdtMethods
import org.json.JSONObject

/**
 * UDT 业务 API：把 [UdtMethods] 的请求构造 + [UdtClient] 的传输 + 电视端返回解析串起来。
 *
 * 注意：openApp / installUrlApp / uninstallApp / getAppStatus 走 ctrl 连接；
 * getInstalledPackages 走 data 连接（返回体在 data 段，可能分页）。
 */
class TvApi(private val udt: UdtClient) {

    companion object {
        private const val TAG = "TvApi"
    }

    private fun nextId(): Int = udt.nextId()

    /** 打开电视上的应用；返回电视端 status（0 表示成功） */
    suspend fun openApp(pkg: String): Int {
        val msg = udt.call(UdtMethods.openApp(pkg, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.status ?: -1
    }

    /** 枚举已安装应用 */
    suspend fun getInstalledPackages(): List<RemoteApp> {
        val msg = udt.call(UdtMethods.getInstalledPackages(nextId()), UdtMethods.TIMEOUT_LIST)
        // 优先用 data 段（useDataChannelReturn=false）
        val fromData = UdtMethods.parseInstalledPackages(msg.data)
        if (fromData.isNotEmpty()) return fromData
        // 兜底：极少数实现把结果放在 result 里
        val result = msg.result ?: return emptyList()
        return UdtMethods.parseInstalledPackages(result.toString().toByteArray())
    }

    /** 获取单个应用的图标 PNG 字节（走 ctrl 连接，图标在 data 段）；失败返回 null */
    suspend fun getPackageIcon(pkg: String): ByteArray? {
        val msg = udt.call(UdtMethods.getPackageInfoIcon(pkg, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        val ok = msg.status ?: -1
        Log.d(TAG, "getPackageIcon $pkg status=$ok dataLen=${msg.data.size}")
        if (ok != 0) return null
        return msg.data.takeIf { it.isNotEmpty() }
    }

    /** 让电视从 URL 拉取 APK 并安装 */
    suspend fun installUrlApp(url: String, timeoutMs: Long = 180_000L): UdtCodec.Message =
        udt.call(UdtMethods.installUrlApp(url, nextId()), timeoutMs)

    suspend fun uninstallApp(pkg: String): Int {
        val msg = udt.call(UdtMethods.uninstallApp(pkg, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.status ?: -1
    }

    suspend fun getAppStatus(key: String): JSONObject? {
        val msg = udt.call(UdtMethods.getAppStatus(key, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.result
    }

    /** 探测链路是否可用（getIdentity 不需要签名） */
    suspend fun getIdentity(): JSONObject? {
        val msg = udt.call(UdtMethods.probe(nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.result
    }

    private suspend fun fireAndForget(method: String): Int {
        val msg = udt.call(UdtMethods.fireAndForget(method, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.status ?: -1
    }

    suspend fun longPressHome(): Int = fireAndForget(UdtMethods.LONG_PRESS_HOME)
    suspend fun longPressPower(): Int = fireAndForget(UdtMethods.LONG_PRESS_POWER)
    suspend fun reboot(): Int = fireAndForget(UdtMethods.REBOOT)
}
