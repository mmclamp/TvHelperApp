package com.tvhelper.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

/** 发现的电视设备 */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val host: String,
    val rcPort: Int = 30612,
    val udtPort: Int = 6093,
    val attributes: Map<String, String> = emptyMap(),
) {
    val model: String? get() = attributes["model"] ?: attributes["md"]
    val mac: String? get() = attributes["mac"]
}

/**
 * 局域网电视发现。
 *
 * 小米电视助手注册两类 mDNS 服务（逆向确认自 k2.b / n6.a / RCClientService）：
 * - `_rc._tcp.local.`     遥控服务（TCP 30612）
 * - `_milink._tcp.local.` milink UDT 服务（TXT: `tcp=6093`、`udp=6094`、`mac=...`）
 *
 * 这里用系统 NsdManager 浏览两者，按 IP 归并成一条设备记录。
 * 只依赖 IP 也能工作 —— 端口有固定默认值（30612 / 6093）。
 */
class MdnsDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_RC = "_rc._tcp"
        const val SERVICE_MILINK = "_milink._tcp"
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("tvhelper-mdns").apply {
                setReferenceCounted(true)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock 获取失败：${it.message}") }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    /**
     * 持续发现设备。冷流：收集时开始浏览，取消时停止。
     * 同 IP 的多次解析结果会合并（保留已有端口/属性）。
     */
    fun discover(): Flow<DiscoveredDevice> = callbackFlow {
        acquireLock()
        val nsd = context.applicationContext
            .getSystemService(Context.NSD_SERVICE) as NsdManager
        val merged = LinkedHashMap<String, DiscoveredDevice>()

        val listeners = ArrayList<NsdManager.DiscoveryListener>()

        fun emitMerged(d: DiscoveredDevice) {
            val prev = merged[d.id]
            val next = if (prev == null) d else prev.copy(
                name = d.name.ifBlank { prev.name },
                rcPort = if (d.rcPort != 30612) d.rcPort else prev.rcPort,
                udtPort = if (d.udtPort != 6093) d.udtPort else prev.udtPort,
                attributes = prev.attributes + d.attributes,
            )
            merged[d.id] = next
            trySend(next)
        }

        fun startBrowse(serviceType: String, defaultRc: Int, defaultUdt: Int) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "发现启动失败 $type code=$code")
                }

                override fun onStopDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "发现停止失败 $type code=$code")
                }

                override fun onDiscoveryStarted(type: String) {
                    Log.i(TAG, "开始发现 $type")
                }

                override fun onDiscoveryStopped(type: String) {
                    Log.i(TAG, "停止发现 $type")
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    runCatching { nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "解析失败 ${si.serviceName} code=$errorCode")
                        }

                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val ip = ipv4Of(si) ?: return
                            val attrs = si.attributes.orEmpty()
                                .mapNotNull { (k, v) ->
                                    if (v == null) null else k to String(v, Charsets.UTF_8)
                                }
                                .toMap()
                            val port = si.port.takeIf { it > 0 } ?: 0
                            val isRc = si.serviceType?.contains("_rc.") == true
                            // milink 服务会把真实 TCP 端口写在 TXT 里（tcp=6093），比 SRV 更权威
                            val txtTcp = attrs["tcp"]?.trim()?.toIntOrNull()
                            emitMerged(
                                DiscoveredDevice(
                                    id = ip,
                                    name = si.serviceName.orEmpty().ifBlank { ip },
                                    host = ip,
                                    // RC 端口：以 SRV 广播为准（h2.h.M 即取该值），缺失才用默认
                                    rcPort = if (isRc && port > 0) port else defaultRc,
                                    udtPort = when {
                                        isRc -> defaultUdt
                                        txtTcp != null && txtTcp > 0 -> txtTcp
                                        port > 0 -> port
                                        else -> defaultUdt
                                    },
                                    attributes = attrs,
                                )
                            )
                        }
                    }) }.onFailure { Log.w(TAG, "resolve 异常：${it.message}") }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    Log.i(TAG, "服务丢失 ${info.serviceName}")
                }
            }
            listeners += listener
            runCatching {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(TAG, "discoverServices($serviceType) 异常：${it.message}") }
        }

        startBrowse(SERVICE_RC, defaultRc = 30612, defaultUdt = 6093)
        startBrowse(SERVICE_MILINK, defaultRc = 30612, defaultUdt = 6093)

        awaitClose {
            listeners.forEach { l -> runCatching { nsd.stopServiceDiscovery(l) } }
            listeners.clear()
            releaseLock()
        }
    }

    private fun ipv4Of(info: NsdServiceInfo): String? {
        val direct = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
        } else {
            null
        }
        if (direct != null) return direct
        @Suppress("DEPRECATION")
        val legacy = info.host?.hostAddress
        return legacy?.takeIf { it.count { c -> c == '.' } == 3 }
    }
}
