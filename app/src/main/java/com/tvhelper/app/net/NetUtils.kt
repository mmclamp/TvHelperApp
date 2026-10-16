package com.tvhelper.app.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** 局域网工具：取本机 IP、IP ↔ int 互转（UDT 的 IP 用 int 表示） */
object NetUtils {

    /** 本机第一个站点本地 IPv4（如 192.168.x.x） */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** 所有本地 IPv4，用于手动选择 */
    fun allLocalIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** 大端序 4 字节 → int（与 ia.f.b 一致） */
    fun ipv4ToInt(ip: String): Int = runCatching {
        val addr = InetAddress.getByName(ip).address
        if (addr.size != 4) 0
        else ((addr[0].toInt() and 0xFF) shl 24) or
            ((addr[1].toInt() and 0xFF) shl 16) or
            ((addr[2].toInt() and 0xFF) shl 8) or
            (addr[3].toInt() and 0xFF)
    }.getOrDefault(0)

    fun intToIpv4(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isValidIpv4(ip: String): Boolean {
        val m = IPV4.matchEntire(ip.trim()) ?: return false
        return m.groupValues.drop(1).all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }
    }

    /** 同网段判断（按 /24 粗判），用于提示手机与电视是否在同一局域网 */
    fun sameSubnet(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val pa = a.split('.')
        val pb = b.split('.')
        if (pa.size != 4 || pb.size != 4) return false
        return pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }
}
