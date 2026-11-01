package com.tvhelper.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.tvhelper.app.data.TvDevice
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID

/**
 * 从「已关机/息屏」状态唤醒电视。
 *
 * 原版小米电视助手在电源键点击且设备未连接时走 `openOrCloseDevice` 四路并发
 * （逆向自 MilinkActivity）：红外 / BLE 广播 / 经典蓝牙 SPP / WOL。
 * 其中红外需手机红外发射器（本项目手机无），其余三路在此复刻：
 *
 * 1. **WOL**：mDNS TXT `wol` 标志为真时，向 255.255.255.255:40000 广播魔术包
 *    （6×0xFF + 16 次 Wi-Fi MAC），电视网卡收到即上电。
 * 2. **BLE 广播开机**：机型支持（蓝牙 MAC 已知 + 手机支持多播广播）时，手机以
 *    电视蓝牙 MAC 派生出的 serviceUuid 发起 BLE 广播，电视深待机期扫描命中即开机。
 *    serviceUuid 规则（原版 POWER_ON_SERVICE_FORMAT）：
 *    `00AABBCC-DDEE-FF01-00ff-030501020100`（AA~FF 为蓝牙 MAC 六段）。
 * 3. **经典蓝牙 SPP**：RFCOMM 连电视蓝牙 MAC（UUID 8ce255c0-200a-11e0-ac64-0905200c1a6a），
 *    发 8 字节头 + JSON `{"cmd":"security","keycode":26}` 触发开机。
 *
 * 各路径均做能力探测：缺依赖（蓝牙 MAC / 权限 / 硬件支持）即跳过并记入 [PowerOnReport.reasons]，
 * 不抛异常。唤醒后由调用方（仓库重连循环）等待电视回连。
 */
class PowerOn(private val context: Context) {

    companion object {
        private const val TAG = "PowerOn"
        /** WOL 广播端口（原版 40000） */
        private const val WOL_PORT = 40000
        /** 经典蓝牙 SPP 通道 UUID（原版 8ce255c0-200a-11e0-ac64-0905200c1a6a） */
        private val SPP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0905200c1a6a")
        /** BLE 广播开机 serviceUuid 模板（%s 依次为蓝牙 MAC 六段） */
        private const val POWER_ON_SERVICE_FORMAT = "00%s%s%s-%s%s-%s01-00ff-030501020100"
        /** BLE 广播时长（ms），到了主动停止，避免一直霸占广播资源 */
        private const val BLE_ADVERTISE_MS = 30_000L
        private val MAC_RE = Regex("([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}")
    }

    /** 本次唤醒尝试的结果与未命中原因 */
    data class Report(
        val wolSupported: Boolean,
        val wolSent: Boolean,
        val bleSupported: Boolean,
        val bleSent: Boolean,
        val btSupported: Boolean,
        val btSent: Boolean,
        /** 各路径被跳过/失败的原因，便于 UI 提示用户为何没开起来 */
        val reasons: List<String>,
    ) {
        val attempted: Boolean get() = wolSent || bleSent || btSent
        val summary: String
            get() = buildString {
                append("WOL${if (wolSent) "✓" else "·"} ")
                append("BLE${if (bleSent) "✓" else "·"} ")
                append("BT${if (btSent) "✓" else "·"}")
                if (reasons.isNotEmpty()) append("｜${reasons.joinToString("；")}")
            }
    }

    @SuppressLint("MissingPermission")
    suspend fun wake(device: TvDevice): Report {
        val reasons = mutableListOf<String>()

        // ---- 1) WOL ----
        val wolSupported = device.wol && parseMac(device.wifiMac) != null
        var wolSent = false
        if (device.wol) {
            val macBytes = parseMac(device.wifiMac)
            if (macBytes != null) {
                wolSent = runCatching { sendWol(macBytes) }.onFailure {
                    reasons += "WOL发送失败：${it.message}"
                }.isSuccess
            } else {
                reasons += "电视支持WOL但无Wi-Fi MAC，无法组包"
            }
        }

        // ---- 2) BLE 广播 ----
        val bleSupported = hasBleAdvertiseCapability(device)
        var bleSent = false
        if (bleSupported) {
            bleSent = runCatching { sendBleWake(device.btMac!!) }.onFailure {
                reasons += "BLE开机失败：${it.message}"
            }.isSuccess
        } else if (device.btMac != null) {
            reasons += "手机不支持BLE广播或缺少BLUETOOTH_ADVERTISE权限"
        }

        // ---- 3) 经典蓝牙 SPP ----
        val btSupported = hasBluetoothConnect() && parseMac(device.btMac) != null
        var btSent = false
        if (btSupported) {
            btSent = runCatching { sendBtWake(device.btMac!!) }.onFailure {
                reasons += "蓝牙SPP开机失败：${it.message}"
            }.isSuccess
        } else if (device.btMac != null) {
            reasons += "缺少BLUETOOTH_CONNECT权限，无法经典蓝牙开机"
        }

        if (device.btMac == null) reasons += "未获取到电视蓝牙MAC（mDNS未广播），BLE/蓝牙开机不可用"
        if (!device.wol) reasons += "电视未广播WOL支持，网络唤醒不可用"

        return Report(
            wolSupported = wolSupported,
            wolSent = wolSent,
            bleSupported = bleSupported,
            bleSent = bleSent,
            btSupported = btSupported,
            btSent = btSent,
            reasons = reasons.distinct(),
        )
    }

    // ---------------------------------------------------------------- WOL

    private fun sendWol(macBytes: ByteArray) {
        require(macBytes.size == 6) { "MAC 必须为 6 字节" }
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 6 until 102) packet[i] = macBytes[(i - 6) % 6]
        DatagramSocket().use { sock ->
            sock.broadcast = true
            val addr = InetSocketAddress("255.255.255.255", WOL_PORT)
            repeat(5) {
                sock.send(DatagramPacket(packet, packet.size, addr))
            }
        }
        Log.i(TAG, "WOL 魔术包已广播（$WOL_PORT，5 次）")
    }

    // ---------------------------------------------------------------- BLE 广播

    @SuppressLint("MissingPermission")
    private fun hasBleAdvertiseCapability(device: TvDevice): Boolean {
        val btMac = device.btMac
        if (btMac == null || parseMac(btMac) == null) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = bluetoothAdapter() ?: return false
        if (!adapter.isEnabled) return false
        return adapter.isMultipleAdvertisementSupported
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendBleWake(btMac: String) {
        val adapter = bluetoothAdapter() ?: error("无蓝牙适配器")
        val advertiser: BluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
            ?: error("手机不支持BLE广播")
        val uuid = ParcelUuid.fromString(buildPowerOnUuid(btMac))
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(BLE_ADVERTISE_MS.toInt())
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(uuid)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE 开机广播已启动（$BLE_ADVERTISE_MS ms）：$uuid")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE 开机广播启动失败 errorCode=$errorCode")
            }
        }
        advertiser.startAdvertising(settings, data, cb)
        // 播够时长后自动停止，释放资源
        delay(BLE_ADVERTISE_MS)
        runCatching { advertiser.stopAdvertising(cb) }
    }

    /** 由蓝牙 MAC 拼出原版 serviceUuid：00AABBCC-DDEE-FF01-00ff-030501020100 */
    private fun buildPowerOnUuid(btMac: String): String {
        val parts = btMac.split(":", "-").map { it.lowercase() }
        require(parts.size == 6) { "蓝牙 MAC 段数异常：$btMac" }
        return String.format(
            POWER_ON_SERVICE_FORMAT,
            parts[0], parts[1], parts[2], parts[3], parts[4], parts[5],
        )
    }

    // ---------------------------------------------------------------- 经典蓝牙 SPP

    @SuppressLint("MissingPermission")
    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun sendBtWake(btMac: String) {
        val adapter = bluetoothAdapter() ?: error("无蓝牙适配器")
        if (!adapter.isEnabled) error("蓝牙未开启")
        val socket = adapter.getRemoteDevice(btMac).createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        socket.connect()
        try {
            val out = socket.outputStream
            val json = """{"cmd":"security","keycode":26}""".toByteArray(Charsets.UTF_8)
            val header = byteArrayOf(
                0, 1, 0, 0,
                (json.size shr 24).toByte(), (json.size shr 16).toByte(),
                (json.size shr 8).toByte(), json.size.toByte(),
            )
            out.write(header)
            out.write(json)
            out.flush()
            Log.i(TAG, "蓝牙SPP开机指令已发送（$btMac）")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter

    /** 将 "AA:BB:CC:DD:EE:FF" 解析为 6 字节；格式不符返回 null */
    private fun parseMac(mac: String?): ByteArray? {
        if (mac == null) return null
        val cleaned = mac.trim().uppercase()
        if (!MAC_RE.matches(cleaned)) return null
        val baos = ByteArrayOutputStream()
        cleaned.split(":", "-").forEach { baos.write(it.toInt(16)) }
        val bytes = baos.toByteArray()
        return if (bytes.size == 6) bytes else null
    }
}
