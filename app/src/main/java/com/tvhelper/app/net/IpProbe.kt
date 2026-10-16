package com.tvhelper.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手输 IP 的端口探测。
 *
 * 小米电视常开端口（逆向确认）：
 * - 30612 RC 遥控通道（TCP）—— `h2.h` 中的默认值
 * - 6093  milink UDT 通道（TCP）—— `d7.u` 中硬编码
 * - 6088 / 6091 AirKan 投屏相关（TCP）
 *
 * 注意 RC 端口并非固定：`h2.h.M()` 里连接端口优先取设备数据中
 * （即 mDNS `_rc._tcp` SRV 记录）的端口，**仅当为 0 才回落 30612**。
 * 实测确实有电视把 `_rc._tcp` 广播到 6091，所以这里把常见值都作为候选。
 */
object IpProbe {

    const val PORT_RC = 30612
    const val PORT_UDT = 6093
    const val PORT_AIRKAN = 6088
    const val PORT_AIRKAN_ALT = 6091

    /** RC 通道候选端口（按尝试顺序） */
    val RC_PORT_FALLBACKS = listOf(PORT_RC, PORT_AIRKAN_ALT)

    /** UDT 通道候选端口（原实现硬编码 6093，少量机型不同） */
    val UDT_PORT_FALLBACKS = listOf(PORT_UDT)

    /** 组装候选端口：优先用发现到的端口，再补默认值兜底 */
    fun rcCandidates(advertised: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        if (advertised > 0) out += advertised
        out += RC_PORT_FALLBACKS
        return out.toList()
    }

    fun udtCandidates(advertised: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        if (advertised > 0) out += advertised
        out += UDT_PORT_FALLBACKS
        return out.toList()
    }

    /** 并行探测多个端口，返回开放的端口列表（保持入参顺序） */
    suspend fun openPorts(host: String, ports: List<Int>, timeoutMs: Int = 800): List<Int> =
        coroutineScope {
            ports.distinct()
                .map { p -> async(Dispatchers.IO) { p to isOpen(host, p, timeoutMs) } }
                .let { deferred -> deferred.map { it.await() } }
                .filter { it.second }
                .map { it.first }
        }

    /**
     * TCP 建连测试。阻塞调用 —— 只应在 IO 线程上使用
     * （[openPorts] 内部已用 [Dispatchers.IO]）。
     */
    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)
}

