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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID

/**
 * 从「已关机/息屏」状态唤醒电视。
 *
 * 原版小米电视助手在电源键点击且设备未连接时走 `openOrCloseDevice` 四路并发
 * （逆向自 MilinkActivity）：红外 / BLE 广播 / 经典蓝牙 SPP / WOL。四路在此全部复刻：
 *
 * 0. **红外（第一主力，且先行）**：`r6/g.java` 用 `ConsumerIrManager.transmit(37920, pattern)` 发电源码，
 *    `r6/h.java` 的 24 段微秒时序重复 7 次。不依赖网络/蓝牙，息屏/待机/关机均可亮屏。
 *    此前误判「本项目手机无红外」而整个缺失，实际 OnePlus PLC110 有 consumer_ir——这是打不开的根因。
 *    v1.8.26 真机日志实证：`21:42:57.840` 红外发射 → `57.874` 电视 mDNS 服务丢失（电视响应），
 *    而 BLE 广播 `57.933`(+93ms)、SPP `58.755`(+915ms) 都在响应之后，**生效路径就是红外**。
 *    故 1.8.27 起红外由 [fireIr] 先行发射（不等 4s mDNS 扫描），且发射成功后不再并发 BLE/SPP：
 *    原版 `openOrCloseDevice` 的 SPP 分支本就带 `!isIRSupport(...)`，因为红外与 SPP 的 keycode 26
 *    都是电源「切换」键，并发等于按两次电源，会把刚点亮的电视又关掉。
 * 1. **WOL**：mDNS TXT `wol` 标志为真时，向 255.255.255.255:40000 广播魔术包
 *    （6×0xFF + 16 次 Wi-Fi MAC），电视网卡收到即上电。
 * 2. **BLE 广播开机**：机型支持（蓝牙 MAC 已知 + 手机支持多播广播）时，手机以
 *    电视蓝牙 MAC 派生出的 serviceUuid 发起 BLE 广播，电视深待机期扫描命中即开机。
 *    serviceUuid 规则（原版 POWER_ON_SERVICE_FORMAT）：
 *    `00AABBCC-DDEE-FF01-00ff-030501020100`（AA~FF 为蓝牙 MAC 六段）。
 * 3. **经典蓝牙 SPP**：RFCOMM 连电视蓝牙 MAC（UUID 8ce255c0-200a-11e0-ac64-0905200c1a6a），
 *    发 8 字节头 + JSON `{"cmd":"security","keycode":26}` 触发开机。
 *
 * 各路径均做能力探测：缺依赖（蓝牙 MAC / 权限 / 硬件支持）即跳过并记入 [Report.reasons]，
 * 不抛异常。唤醒后由调用方（仓库重连循环）等待电视回连。
 *
 * 设计要点（修复点）：
 * - **MAC 统一大写**：Android `BluetoothAdapter.checkBluetoothAddress()` 只接受大写十六进制，
 *   mDNS 广播的小写 MAC（如 a8:96:09:9f:c8:76）会被判为非法地址，导致 `getRemoteDevice()` 抛错，
 *   此前 SPP 一路必败。这里在入口处统一 `.uppercase()`。
 * - **BLE 持续广播**：改为后台协程在整个唤醒窗口内循环续播（每段 25s 自动续），
 *   覆盖电视从待机到扫描命中的周期；不再阻塞调用方。
 * - **SPP 后台重试**：电视蓝牙从关机到上线有延迟，SPP 连接放后台重试，提高命中率。
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
        /** BLE 单段广播时长（ms），到了由 runBleWake 自动续播 */
        private const val BLE_ADVERTISE_MS = 25_000L
        /** SPP 后台重试次数与间隔（电视蓝牙上线有延迟） */
        private const val SPP_RETRIES = 3
        private const val SPP_RETRY_DELAY_MS = 2_500L
        private val MAC_RE = Regex("([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}")
    }

    /** 自有后台作用域：承载 BLE 续播与 SPP 重试，生命周期由 stopWake 收尾 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleCallback: AdvertiseCallback? = null
    private val wakeJobs = mutableListOf<Job>()

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
        /** 红外（原版第一路）：带默认值以兼容旧构造点 */
        val irSupported: Boolean = false,
        val irSent: Boolean = false,
    ) {
        val attempted: Boolean get() = irSent || wolSent || bleSent || btSent
        val summary: String
            get() = buildString {
                append("IR${if (irSent) "✓" else "·"} ")
                append("WOL${if (wolSent) "✓" else "·"} ")
                append("BLE${if (bleSent) "✓" else "·"} ")
                append("BT${if (btSent) "✓" else "·"}")
                if (reasons.isNotEmpty()) append("｜${reasons.joinToString("；")}")
            }
    }

    /**
     * 立即发射红外电源码（原版第一路，也是本机唯一被实证生效的路径）。
     *
     * 不需要设备元数据、网络、蓝牙或运行时权限，因此由调用方在 mDNS 扫描**之前**调用：
     * 点击「打开」到真正发射的延迟从 ~4.2s（等 4s mDNS）降到 ~200ms（发射本身耗时）。
     * 红外电源是切换键，调用方必须做冷却去抖，禁止连点连发。
     */
    fun fireIr(): IrPowerOn.Result =
        runCatching { IrPowerOn(context).transmitPower() }
            .onFailure { Log.w(TAG, "红外开机异常：${it.message}") }
            .getOrElse { IrPowerOn.Result(false, false, it.message ?: "未知错误") }

    /**
     * 红外不可用时的兜底唤醒：WOL 同步发，BLE/SPP 后台持续尝试（不阻塞），立即返回 [Report]。
     * [ir] 是已先行发射的红外结果；红外发射成功时跳过 BLE/SPP，避免双电源切换键互相抵消
     * （依据原版 `MilinkActivity.java:1003` SPP 分支的 `!isIRSupport(...)` 条件）。
     * 调用方在唤醒成功/超时时务必调用 [stopWake]。
     */
    @SuppressLint("MissingPermission")
    fun beginWake(device: TvDevice, ir: IrPowerOn.Result): Report {
        val reasons = mutableListOf<String>()
        // 关键修复：MAC 统一大写 + 去空格，否则 Android 校验小写 MAC 为非法地址使 SPP 必败
        val dev = device.copy(
            btMac = device.btMac?.trim()?.uppercase(),
            wifiMac = device.wifiMac?.trim()?.uppercase(),
        )
        if (!ir.sent) reasons += "红外不可用：${ir.reason ?: "发射失败"}"

        // ---- 1) WOL ----
        val wolSupported = dev.wol && parseMac(dev.wifiMac) != null
        var wolSent = false
        if (dev.wol) {
            val macBytes = parseMac(dev.wifiMac)
            if (macBytes != null) {
                wolSent = runCatching { sendWol(macBytes) }.onFailure {
                    reasons += "WOL发送失败：${it.message}"
                }.isSuccess
            } else {
                reasons += "电视支持WOL但无Wi-Fi MAC，无法组包"
            }
        }

        // ---- 2) BLE 广播（后台持续续播，直到 stopWake）----
        // 红外已生效时不再续播：本机实测 BLE 从未触发开机，却会占用蓝牙 75s、耗电且可能与红外抢触发。
        val bleSupported = !ir.sent && hasBleAdvertiseCapability(dev)
        var bleSent = false
        if (bleSupported) {
            bleSent = true
            wakeJobs += scope.launch { runBleWake(dev.btMac!!) }
        } else if (dev.btMac != null && !ir.sent) {
            reasons += "手机不支持BLE广播或缺少BLUETOOTH_ADVERTISE权限（请开启蓝牙）"
        }

        // ---- 3) 经典蓝牙 SPP（后台重试）----
        // 原版 SPP 分支带 !isIRSupport：红外与 SPP 的 keycode 26 都是 POWER 切换键，
        // 并发等于按两次电源，会把刚点亮的电视又关掉。
        val btSupported = !ir.sent && hasBluetoothConnect() && parseMac(dev.btMac) != null
        var btSent = false
        if (btSupported) {
            btSent = true
            wakeJobs += scope.launch { runBtWake(dev.btMac!!) }
        } else if (dev.btMac != null && !ir.sent) {
            reasons += "缺少BLUETOOTH_CONNECT权限，无法经典蓝牙开机（请授权并开启蓝牙）"
        }

        if (dev.btMac == null && !ir.sent) reasons += "未获取到电视蓝牙MAC，BLE/蓝牙开机不可用"
        if (!dev.wol && !ir.sent) reasons += "电视未广播WOL支持，网络唤醒不可用"

        return Report(
            wolSupported = wolSupported,
            wolSent = wolSent,
            bleSupported = bleSupported,
            bleSent = bleSent,
            btSupported = btSupported,
            btSent = btSent,
            reasons = reasons.distinct(),
            irSupported = ir.supported,
            irSent = ir.sent,
        )
    }

    /** 停止全部唤醒任务与 BLE 广播（唤醒成功或超时时由调用方调用） */
    fun stopWake() {
        bleCallback?.let { cb ->
            runCatching { bleAdvertiser?.stopAdvertising(cb) }
        }
        bleCallback = null
        bleAdvertiser = null
        wakeJobs.forEach { runCatching { it.cancel() } }
        wakeJobs.clear()
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

    /** BLE 广播开机：循环续播直到 [stopWake]，覆盖电视待机扫描周期 */
    @SuppressLint("MissingPermission")
    private suspend fun runBleWake(btMac: String) {
        val adapter = bluetoothAdapter() ?: run {
            Log.w(TAG, "BLE 开机失败：无蓝牙适配器"); return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE 开机失败：手机不支持BLE广播"); return
        }
        val uuid = ParcelUuid.fromString(buildPowerOnUuid(btMac))
        val data = AdvertiseData.Builder().addServiceUuid(uuid).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE 开机广播已启动（$BLE_ADVERTISE_MS ms/段）：$uuid")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE 开机广播启动失败 errorCode=$errorCode")
            }
        }
        bleAdvertiser = advertiser
        bleCallback = cb
        val settings = AdvertiseSettings.Builder()
            // 复刻原版：connectable=true（电视靠「连接」该广播触发开机，false 则电视不响应）、
            // LOW_POWER 模式、HIGH 发射功率。超时取单段时长，到时由本循环自动续播。
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(BLE_ADVERTISE_MS.toInt())
            .build()
        while (scope.isActive && bleCallback != null) {
            try {
                advertiser.startAdvertising(settings, data, cb)
            } catch (t: Throwable) {
                Log.w(TAG, "BLE startAdvertising 异常：${t.message}")
                break
            }
            delay(BLE_ADVERTISE_MS)
        }
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

    /** 后台重试 SPP 连接发 keycode 26，覆盖电视从待机到蓝牙上线的延迟 */
    @SuppressLint("MissingPermission")
    private suspend fun runBtWake(btMac: String) {
        repeat(SPP_RETRIES) { i ->
            if (!scope.isActive) return
            try {
                sendBtWakeOnce(btMac)
                Log.i(TAG, "蓝牙SPP开机指令已发送（$btMac）")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "蓝牙SPP开机第 ${i + 1}/$SPP_RETRIES 次失败：${t.message}")
                if (i < SPP_RETRIES - 1) delay(SPP_RETRY_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBtWakeOnce(btMac: String) {
        val adapter = bluetoothAdapter() ?: error("无蓝牙适配器")
        if (!adapter.isEnabled) error("蓝牙未开启")
        // btMac 已统一大写，checkBluetoothAddress 才能通过
        val device = adapter.getRemoteDevice(btMac)
        val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        try {
            socket.connect()
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
