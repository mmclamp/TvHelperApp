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
) {
    val displayName: String get() = name.ifBlank { host }
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
