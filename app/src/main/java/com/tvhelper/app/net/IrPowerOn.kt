package com.tvhelper.app.net

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log

/**
 * 红外开机：复刻原版小米电视助手的第一路唤醒（jadx 逆向自 `r6/` 包）。
 *
 * 原版证据链：
 * - `r6/g.java:111` `d(platformId)` 仅对 {611,610,608,607,606,210,207} 禁用红外，其余机型全部放行；
 * - `r6/g.java:164` `h()` → `ConsumerIrManager.transmit(carrierFrequency, pattern)`；
 * - `r6/g.java:140` `f()` 取 `IRData.a()` 作载波、`DKPlainIRCoder.a()` 作时序，且 `z10=true` 时
 *   **直接使用原始微秒数组**（不再做 `value*1000000/carrier` 换算）；
 * - `r6/h.java:120` `e()` 分支（非 MIUI adjusted，本机 OnePlus 即走此分支）`c()` 为电源码，
 *   24 个微秒时序经 `b(arr, 7)` 重复 7 次，载波 37920Hz；
 * - `MilinkActivity.java:430` `lambda$sendIRPowerOn$1` → `sendIR(getMiTVIRData().c())` 只发这一帧
 *   （主电源键路径 `powerOn(pdd,false)` 的 z10=false，不发第二帧 `a()`）。
 *
 * 为什么必须补这一路：红外不依赖网络、蓝牙、也不受「息屏态固件静默丢弃注入键」影响，
 * 息屏/待机/关机均可直接唤醒屏幕。本机 `wol=0`、BLE/SPP 实测无效果，而手机有红外发射器
 * （OnePlus PLC110，`pm list features` 含 android.hardware.consumerir，服务 consumer_ir 存在），
 * 这正是原版在息屏/关机态能开机的主力路径。
 *
 * 注意：红外电源键是**切换键**，绝对不能连发多个电源码（会开了又关）。
 * 这里严格对齐原版主路径，只发一帧 `c()`；`transmit` 抛异常表示未发出，才尝试备用时序。
 */
class IrPowerOn(private val context: Context) {

    companion object {
        private const val TAG = "IrPowerOn"

        /** 原版 `r6/h.java` f(100, coder, 37920) 的第三参：载波频率 Hz */
        private const val CARRIER_HZ = 37920

        /** 原版 `r6/h.java:120` e() 分支 c() 单帧时序（微秒），末尾 10763 为帧间隔 */
        private val POWER_PATTERN = intArrayOf(
            1009, 588, 588, 588, 588, 1470, 588, 1470, 588, 588, 588, 1470,
            588, 588, 588, 1470, 588, 588, 588, 1470, 588, 1470, 588, 10763,
        )

        /** 原版 `r6/h.java:103` d() 分支（MIUI adjusted，998/560 系列）备用时序 */
        private val POWER_PATTERN_ADJUSTED = intArrayOf(
            998, 560, 560, 560, 560, 1400, 560, 1400, 560, 560, 560, 1400,
            560, 560, 560, 1400, 560, 560, 560, 1400, 560, 1400, 560, 10763,
        )

        /** 原版 `f21046v = 7`：单帧时序重复 7 次连发，保证落在电视待机扫描窗口内 */
        private const val REPEAT = 7
    }

    data class Result(val supported: Boolean, val sent: Boolean, val reason: String? = null)

    /** 是否具备红外发射能力（对齐原版 `r6/g.b()`：`mCIR != null && hasIrEmitter()`） */
    fun hasIrEmitter(): Boolean = runCatching {
        irManager()?.hasIrEmitter() == true
    }.getOrDefault(false)

    /**
     * 发射电源红外码。同步阻塞约 200~250ms（7 帧 × ~32ms），调用方须放在 IO 线程。
     * 返回 [Result]：supported=硬件可用，sent=是否成功调用 transmit。
     */
    fun transmitPower(): Result {
        val manager = irManager()
            ?: return Result(false, false, "无 ConsumerIrManager（设备不支持红外）")
        if (!runCatching { manager.hasIrEmitter() }.getOrDefault(false)) {
            return Result(false, false, "手机无红外发射器")
        }
        val primary = repeatPattern(POWER_PATTERN)
        try {
            manager.transmit(CARRIER_HZ, primary)
            Log.i(TAG, "红外电源码已发射（载波 ${CARRIER_HZ}Hz，${REPEAT} 帧，${primary.size} 段时序）")
            return Result(true, true)
        } catch (t: Throwable) {
            Log.w(TAG, "红外标准时序发射失败：${t.message}，尝试 MIUI 调整版时序")
        }
        // 仅当标准时序**发射抛异常**（未真正发出）时才尝试备用，避免双发导致开机又被关掉
        return try {
            val adjusted = repeatPattern(POWER_PATTERN_ADJUSTED)
            manager.transmit(CARRIER_HZ, adjusted)
            Log.i(TAG, "红外电源码(调整版)已发射（载波 ${CARRIER_HZ}Hz，${REPEAT} 帧）")
            Result(true, true)
        } catch (t: Throwable) {
            Log.w(TAG, "红外发射失败：${t.message}")
            Result(true, false, "transmit 失败：${t.message}")
        }
    }

    private fun irManager(): ConsumerIrManager? =
        runCatching {
            context.applicationContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        }.getOrNull()

    /** 对齐 `r6/h.b(int[], int)`：把单帧时序原样重复 [REPEAT] 次 */
    private fun repeatPattern(frame: IntArray): IntArray {
        val out = IntArray(frame.size * REPEAT)
        repeat(REPEAT) { n -> System.arraycopy(frame, 0, out, n * frame.size, frame.size) }
        return out
    }
}
