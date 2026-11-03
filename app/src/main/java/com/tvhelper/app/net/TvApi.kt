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

    /** 电视音量查询结果：当前值 / 最大值 / 静音标志（null=响应未携带该字段，协议不支持或方法缺失） */
    data class VolumeResult(val current: Int, val max: Int, val muted: Boolean?)

    /**
     * 查询电视音量（UDT SocialRequest.getVolume）。
     * 原版小米电视助手即此路径：getVolume 响应除 volum/maxVolum 外常一并携带静音标志
     * （isMute/mute/muted 任一字段），一次查询同时拿到音量与静音状态——并不单独调 getMute。
     * 返回 null 表示解析失败（方法不受支持）。
     */
    suspend fun getVolume(): VolumeResult? = volumeFrom(
        udt.call(UdtMethods.getVolume(nextId()), UdtMethods.TIMEOUT_DEFAULT),
    )

    /** 设置电视音量（绝对值）。返回生效值/最大值，失败 null；响应可能同样携带静音标志 */
    suspend fun setVolume(volume: Int): VolumeResult? = volumeFrom(
        udt.call(UdtMethods.setVolume(volume, nextId()), UdtMethods.TIMEOUT_DEFAULT),
    )

    /**
     * 查询电视静音状态（UDT SocialRequest.getMute）——仅作兜底。
     * 实测多款电视 getMute 反射不存在（status=21104），本 App 主路径改为从 getVolume 响应解析静音。
     * 返回 true=已静音 / false=未静音；解析失败（部分固件无此方法）返回 null。
     * 响应容错：isMute/mute/muted 任一字段为真即视为静音。
     */
    suspend fun getMute(): Boolean? {
        val msg = udt.call(UdtMethods.getMute(nextId()), UdtMethods.TIMEOUT_DEFAULT)
        val r = msg.result
        Log.i(TAG, "mute resp status=${msg.status} result=$r")
        if (r == null || msg.status != 0) return null
        val m = when {
            r.has("isMute") -> r.optBoolean("isMute", false)
            r.has("mute") -> r.optBoolean("mute", false)
            r.has("muted") -> r.optBoolean("muted", false)
            else -> return null
        }
        return m
    }

    private fun volumeFrom(msg: UdtCodec.Message): VolumeResult? {
        val r = msg.result
        // INFO 级打印完整 result，便于确认响应是否携带静音字段（取证用）
        Log.i(TAG, "volume resp status=${msg.status} result=$r dataLen=${msg.data.size}")
        if (r == null) return null
        // 容错：result 可能是 {"volum":x,"maxVolum":y}，也可能是裸数值或文本
        val v: Int = when {
            r.has("volum") -> r.optInt("volum", -1)
            r.has("volume") -> r.optInt("volume", -1)
            else -> r.optInt("value", -1)
        }
        if (v < 0) return null
        val max = r.optInt("maxVolum", r.optInt("maxVolume", 100))
        // 静音标志：响应可能携带 isMute/mute/muted 任一字段（原版即此来源）
        val muted = when {
            r.has("isMute") -> r.optBoolean("isMute", false)
            r.has("mute") -> r.optBoolean("mute", false)
            r.has("muted") -> r.optBoolean("muted", false)
            else -> null
        }
        return VolumeResult(v, max, muted)
    }

    private suspend fun fireAndForget(method: String): Int {
        val msg = udt.call(UdtMethods.fireAndForget(method, nextId()), UdtMethods.TIMEOUT_DEFAULT)
        return msg.status ?: -1
    }

    suspend fun longPressHome(): Int = fireAndForget(UdtMethods.LONG_PRESS_HOME)
    suspend fun longPressPower(): Int = fireAndForget(UdtMethods.LONG_PRESS_POWER)
    suspend fun reboot(): Int = fireAndForget(UdtMethods.REBOOT)

    /** 电视运行状态：screenOn=null 表示查询失败/电视不支持该方法 */
    data class DeviceStatus(val screenOn: Boolean?, val deviceStatus: Int?)

    /**
     * 查询电视运行状态（含 isScreenOn / deviceStatus），原版 RemoteDeviceStatus 的来源。
     * 这是区分「亮屏」与「息屏待机」的权威信号：二者网络都通，但息屏时注入键被固件静默丢弃，
     * 必须靠该字段决定电源键走「注入切换」还是「BLE/蓝牙唤醒」。
     * 返回 null 表示链路不可用或该方法不支持（status≠0）。
     */
    suspend fun getDeviceStatus(): DeviceStatus? {
        val msg = udt.call(UdtMethods.getDeviceStatus(nextId()), UdtMethods.TIMEOUT_DEFAULT)
        if (msg.status != 0) {
            Log.i(TAG, "getDeviceStatus status=${msg.status}（电视不支持或失败）")
            return null
        }
        val r = msg.result ?: return null
        // 兼容两种返回结构：isScreenOn 直接在 result，或被包在 result.result 字符串里
        val nested = if (r.has("result")) runCatching { r.getJSONObject("result") }.getOrNull() else null
        val screenOn = when {
            r.has("isScreenOn") -> r.optBoolean("isScreenOn")
            nested?.has("isScreenOn") == true -> nested.optBoolean("isScreenOn")
            else -> null
        }
        val deviceStatus = when {
            r.has("deviceStatus") -> r.optInt("deviceStatus", -1).takeIf { it >= 0 }
            nested?.has("deviceStatus") == true -> nested.optInt("deviceStatus", -1).takeIf { it >= 0 }
            else -> null
        }
        Log.i(TAG, "getDeviceStatus screenOn=$screenOn deviceStatus=$deviceStatus")
        return DeviceStatus(screenOn, deviceStatus)
    }
}
