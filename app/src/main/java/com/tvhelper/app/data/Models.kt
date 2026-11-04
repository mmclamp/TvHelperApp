package com.tvhelper.app.data

import com.tvhelper.app.protocol.udt.RemoteApp

/** 一台电视 */
data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val rcPort: Int = 30612,
    val udtPort: Int = 6093,
    val model: String? = null,
    /** 局域网 Wi-Fi MAC（WOL 魔术包目标地址来源） */
    val wifiMac: String? = null,
    /** 蓝牙 MAC（BLE 广播开机 / 经典蓝牙 SPP 开机目标）。mDNS TXT 可能不含，缺失则相应开机路径不可用 */
    val btMac: String? = null,
    /** 是否支持 WOL 唤醒（mDNS TXT `wol` 标志）。false=电视不支持网络唤醒 */
    val wol: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { host }
}

/**
 * 电视网络可达性状态（仅用于连接指示，不再驱动电源按钮 UI）。
 *
 * 重要语义（2026-11-01 日志实证）：电视息屏/待机时 RC/UDT 网络仍然全通、getVolume 正常响应、
 * 注入回 success 但屏幕不亮——协议层无法区分"亮屏"与"息屏"。因此本枚举只描述
 * **网络可达性**，绝不断言电视屏幕开关：
 * - [REACHABLE]：网络可达（已连接）。
 * - [UNREACHABLE]：网络不可达（关机/深待机，或 RC 断线重连窗口期）。
 *
 * 2026-11-04：删除原 WAKING「开机中」中间态。原版 `MiboxRCActivity.java:621` 只有**一个**
 * 电源按钮、按下即完成（`reconnectDevice(30)` 在后台跑，UI 不等待）；且红外是瞬时切换键，
 * 发射完动作就结束了，不存在"开机中"阶段。中间态只会让 UI 卡住并禁用按钮。
 * 电源键行为改由 [com.tvhelper.app.data.TvRepository.power] 在按下瞬间分流。
 */
enum class TvPowerState {
    REACHABLE,
    UNREACHABLE,
}

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    /** RC 通道已握手（可遥控） */
    RC_READY,
    /** RC + UDT 均就绪（可管理应用） */
    READY,
    FAILED,
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val device: TvDevice? = null,
    val tvProtocolVersion: Int? = null,
    /** RC 会话密钥已启用（加密模式） */
    val encrypted: Boolean = false,
    val rcConnected: Boolean = false,
    val udtConnected: Boolean = false,
    val message: String? = null,
    val lastHeartbeatAt: Long = 0L,
) {
    val isConnected: Boolean
        get() = phase == ConnectionPhase.RC_READY || phase == ConnectionPhase.READY
}

/** AirKan 授权配对状态 */
enum class PairingPhase {
    /** 未在配对流程中 */
    IDLE,
    /** 已发起 requestAuth，电视正在显示 6 位授权码 */
    AWAITING_CODE,
    /** 正在提交授权码完成配对 */
    COMPLETING,
    /** 配对成功 */
    PAIRED,
    FAILED,
}

data class PairingState(
    val phase: PairingPhase = PairingPhase.IDLE,
    val host: String? = null,
    val error: String? = null,
    /** 电视上是否已配对过（决定连接后是否自动发起配对） */
    val knownPaired: Boolean = false,
)

/** APK 安装流程状态 */
sealed interface InstallState {
    data object Idle : InstallState
    data class Preparing(val fileName: String) : InstallState
    data class Pushing(val fileName: String, val url: String, val bytesServed: Long, val totalBytes: Long) : InstallState {
        val progress: Float
            get() = if (totalBytes <= 0) 0f else (bytesServed.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class AwaitingConfirm(val fileName: String, val bytesServed: Long, val totalBytes: Long) : InstallState
    data class Done(val fileName: String, val message: String) : InstallState
    data class Failed(val fileName: String?, val message: String) : InstallState
}

/** 一条 Toast / 日志式提示 */
data class Notice(val text: String, val isError: Boolean = false, val at: Long = System.currentTimeMillis())

/** 首页快捷区条目 */
data class PinnedApp(val packageName: String, val label: String? = null, val className: String? = null) {
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: com.tvhelper.app.protocol.udt.KnownTvPackages.friendly(packageName)
}

fun RemoteApp.toPinned() = PinnedApp(packageName, label, className)
