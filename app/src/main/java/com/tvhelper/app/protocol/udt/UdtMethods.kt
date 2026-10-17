package com.tvhelper.app.protocol.udt

import org.json.JSONObject

/**
 * UDT 方法名与请求构造。
 *
 * 逆向依据：UDTClientManager.MethodInvoker 的 postCall 表。
 * 注意每个方法在电视端挂在不同连接上：
 * - ctrl 连接：openApp / installUrlApp / uninstallApp / sendIntent / getAppStatus / getSecretData ...
 * - data 连接：getInstalledPackages / getScreenshotBitmap（大返回）
 * `useDataChannelReturn` 决定返回值从 ctrl 段还是 data 段取。
 */
object UdtMethods {

    const val OPEN_APP = "openApp"
    const val GET_INSTALLED_PACKAGES = "getInstalledPackages"
    const val INSTALL_URL_APP = "installUrlApp"
    const val INSTALL_APPSTORE_APP = "installAppstoreApp"
    const val UNINSTALL_APP = "uninstallApp"
    const val GET_APP_STATUS = "getAppStatus"
    const val GET_PACKAGE_INFO = "getPackageInfo"
    const val SEND_INTENT = "sendIntent"
    const val GET_SECRET_DATA = "getSecretData"
    const val GET_IDENTITY = "getIdentity"
    const val GET_IDENTITY_INFO = "getIdentityInfo"
    const val GET_AUTH_INFO = "getAuthInfo"
    const val GET_DISK_INFO = "getDiskInfo"
    const val GET_FREE_MEMORY = "getFreeMemory"
    const val GET_TOP_EXECUTING_PACKAGE = "getTopExecutingPackageName"
    const val SCREENSHOT = "getScreenshotBitmap"
    const val REBOOT = "reboot"
    const val LONG_PRESS_HOME = "longPressHome"
    const val LONG_PRESS_POWER = "longPressPower"
    const val REMOVE_APP_INSTALL_PROGRESS = "removeAppInstallProgress"

    /** 音量：原版 com.xiaomi.mitv.phone.tvassistant.udt.SocialRequest 接口同款方法名 */
    const val GET_VOLUME = "getVolume"
    const val SET_VOLUME = "setVolume"

    /** 静音：SocialRequest 同族方法，参数/响应格式与音量同路数 */
    const val GET_MUTE = "getMute"
    const val SET_MUTE = "setMute"

    /** 电视端定义的应用安装状态 */
    const val APP_STATUS_UNINSTALL = 0
    const val APP_STATUS_INSTALLED = 1
    const val APP_STATUS_UPDATE = 2

    /** 遥控类方法默认 15s，列表类 120s（与原实现一致） */
    const val TIMEOUT_DEFAULT = 15_000L
    const val TIMEOUT_LIST = 120_000L

    /** 哪些方法必须走 data 连接 */
    private val DATA_CONNECTION_METHODS = setOf(GET_INSTALLED_PACKAGES, SCREENSHOT)

    fun useDataConnection(method: String): Boolean = method in DATA_CONNECTION_METHODS

    /** 原实现里 postCall 会把 System.currentTimeMillis() 作为第一个参数塞进 args */
    fun prefixTimestampArgs(args: List<Any?>?, ts: Long): List<Any?> =
        if (args.isNullOrEmpty()) listOf(ts) else listOf(ts) + args

    fun call(
        method: String,
        args: List<Any?>? = null,
        methodCallId: Long = System.currentTimeMillis(),
        invokerId: String = "",
        verifyCode: String = "",
        useDataChannelReturn: Boolean = true,
        prefixTs: Boolean = true,
    ): UdtCodec.MethodCall {
        val finalArgs = if (prefixTs) {
            prefixTimestampArgs(args, methodCallId)
        } else {
            args ?: emptyList()
        }
        return UdtCodec.MethodCall(
            methodName = method,
            methodCallId = methodCallId,
            invokerId = invokerId,
            verifyCode = verifyCode,
            args = finalArgs,
            useDataChannelReturn = useDataChannelReturn,
        )
    }

    // ------------------------------------------------------------ 便捷构造

    fun openApp(pkg: String, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(OPEN_APP, listOf(pkg), useDataChannelReturn = true),
        id = id,
    )

    fun getInstalledPackages(id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(
            GET_INSTALLED_PACKAGES,
            args = null,
            useDataChannelReturn = false,
        ),
        id = id,
    )

    /**
     * 单个应用的包信息 + 图标（原版 getPackageInfo(pkg, filter, seconds)）。
     * firstLevel=3（GET_PACKAGE|GET_APPLICATION），seconds=[31,63]：
     * pkg 二级=31（pkgName/verCode/verName/时间），app 二级=31|32（基本字段 + GET_APP_ICON）。
     * 响应：ctrl JSON 里 appInfo.iconBytes 只是长度，图标 PNG 字节在 data 段
     * （UDTClientManagerImpl.onRecvCtrlByTCP: onSuccess(result, dataRegion)）。
     */
    fun getPackageInfoIcon(pkg: String, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(
            GET_PACKAGE_INFO,
            args = listOf(pkg, 3, listOf(31, 63)),
            useDataChannelReturn = true,
        ),
        id = id,
    )

    fun installUrlApp(url: String, id: Int, requestId: String = UdtCodec.newRequestId()): UdtCodec.Request =        UdtCodec.Request(
            requestId = requestId,
            call = call(INSTALL_URL_APP, listOf(url), useDataChannelReturn = true),
            id = id,
        )

    fun uninstallApp(pkg: String, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(UNINSTALL_APP, listOf(pkg), useDataChannelReturn = true),
        id = id,
    )

    fun getAppStatus(key: String, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(GET_APP_STATUS, listOf(key), useDataChannelReturn = true),
        id = id,
    )

    /** 无参数方法（longPressHome / longPressPower / reboot 等） */
    fun fireAndForget(method: String, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(method, null, useDataChannelReturn = true),
        id = id,
    )

    /** 查询电视音量；响应 result 形如 {"volum":x,"maxVolum":y}。
     *  TV 端反射调用无参 getVolume()，**不能带 ts 前缀**（实测带参报 NoSuchMethod） */
    fun getVolume(id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(GET_VOLUME, null, useDataChannelReturn = true, prefixTs = false),
        id = id,
    )

    /** 设置电视音量（绝对值）；原版 SocialRequest.setVolume(int) 同款，单参无 ts 前缀 */
    fun setVolume(volume: Int, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(SET_VOLUME, listOf(volume), useDataChannelReturn = true, prefixTs = false),
        id = id,
    )

    /** 查询静音状态；TV 端反射调用无参 getMute()，不带 ts 前缀 */
    fun getMute(id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(GET_MUTE, null, useDataChannelReturn = true, prefixTs = false),
        id = id,
    )

    /** 设置静音；原版 SocialRequest.setMute(boolean) 同款，单布尔参无 ts 前缀 */
    fun setMute(mute: Boolean, id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(SET_MUTE, listOf(mute), useDataChannelReturn = true, prefixTs = false),
        id = id,
    )

    /** 空协议探测：用于验证 UDT 是否可达（电视收到会返回错误，但至少证明链路通） */
    fun probe(id: Int): UdtCodec.Request = UdtCodec.Request(
        requestId = UdtCodec.newRequestId(),
        call = call(GET_IDENTITY, null, useDataChannelReturn = true),
        id = id,
    )

    // ------------------------------------------------------------ 响应解析

    /**
     * 解析 getInstalledPackages 的 data 段。
     * 结构：`{"PkgInfos":[{"pkgName","versionCode","versionName","appInfo":"<json string>"}],"extradata":{}}`
     */
    fun parseInstalledPackages(data: ByteArray): List<RemoteApp> {
        if (data.isEmpty()) return emptyList()
        val text = String(data, Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("PkgInfos") ?: return emptyList()
        val out = ArrayList<RemoteApp>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("pkgName", "")
            if (pkg.isEmpty()) continue
            var label: String? = null
            var clsName: String? = null
            var flags = 0
            o.optString("appInfo", "").takeIf { it.isNotEmpty() }?.let { s ->
                runCatching { JSONObject(s) }.getOrNull()?.let { app ->
                    label = app.optString("label", "").takeIf { it.isNotEmpty() }
                    clsName = app.optString("clsName", "").takeIf { it.isNotEmpty() }
                    flags = app.optInt("flags", 0)
                }
            }
            out += RemoteApp(
                packageName = pkg,
                label = label,
                className = clsName,
                versionName = o.optString("versionName", "").takeIf { it.isNotEmpty() },
                versionCode = o.optInt("versionCode", 0),
                lastUpdateTime = o.optLong("lastUpdateTime", 0L),
                isSystem = (flags and 0x1) != 0,
            )
        }
        return out.sortedWith(
            compareByDescending<RemoteApp> { it.lastUpdateTime }.thenBy { it.displayName }
        )
    }

    /** 解析 getScreenshotBitmap 等返回 base64 图片的方法（原始数据即图片字节） */
    fun parseBinaryResult(data: ByteArray): ByteArray? = data.takeIf { it.isNotEmpty() }
}

/** 电视端已安装应用 */
data class RemoteApp(
    val packageName: String,
    val label: String? = null,
    val className: String? = null,
    val versionName: String? = null,
    val versionCode: Int = 0,
    val lastUpdateTime: Long = 0L,
    val isSystem: Boolean = false,
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: KnownTvPackages.friendly(packageName)

    /** 首页宫格/列表上显示的首字母（无图标时的占位） */
    val initial: String
        get() = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
}

/** 常见电视应用包名 → 中文名兜底映射（TV 未返回 label 时使用） */
object KnownTvPackages {
    private val map = mapOf(
        "com.mitv.tvhome" to "小米电视主页",
        "com.xiaomi.mitv.tvhome" to "小米电视主页",
        "com.xiaomi.mitv.settings" to "电视设置",
        "com.mitv.settings" to "电视设置",
        "com.xiaomi.mitv.appstore" to "小米应用商店",
        "com.xiaomi.mitv.tvplayer" to "小米电视播放器",
        "com.mitv.videoplayer" to "媒体播放器",
        "com.xiaomi.mitv.videoplayer" to "媒体播放器",
        "com.xiaomi.tv.livetv" to "电视直播",
        "com.xiaomi.mitv.browser" to "电视浏览器",
        "com.xiaomi.mitv.calendar" to "日历",
        "com.xiaomi.mitv.clock" to "时钟",
        "com.xiaomi.mitv.gallery" to "相册",
        "com.xiaomi.mitv.music" to "音乐",
        "com.xiaomi.mitv.video" to "小米视频",
        "com.xiaomi.mitv.gamecenter" to "游戏中心",
        "com.xiaomi.mitv.remotecontroller" to "电视助手服务",
        "com.duokan.videoplayer" to "多看播放器",
        "com.miui.tv.analytics" to "系统统计",
        "com.miui.tv.cloud" to "云服务",
        "tv.danmaku.bilianime.tv" to "哔哩哔哩TV",
        "com.bilibili.tv" to "哔哩哔哩TV",
        "com.tencent.qqlive.tv" to "腾讯视频TV",
        "com.tencent.qqlive" to "腾讯视频",
        "com.qiyi.tv" to "爱奇艺TV",
        "com.gitvdemo.video" to "爱奇艺TV",
        "com.youku.tv" to "优酷TV",
        "com.tudou.tv" to "土豆TV",
        "com.cctv.ott" to "央视影音",
        "com.hunantv.imgo.tv" to "芒果TV",
        "com.letv.android.tv" to "乐视视频",
        "com.sohu.tv" to "搜狐视频",
        "com.ss.android.ugc.aweme.tv" to "抖音TV",
        "com.kuaishou.tv" to "快手TV",
        "com.netease.cloudmusic.tv" to "网易云音乐",
        "com.happyfish.tv" to "电视管家",
        "com.dangbeimarket" to "当贝市场",
        "com.hj.qianqian" to "QQ音乐TV",
        "com.spotify.tv.android" to "Spotify TV",
        "com.google.android.youtube.tv" to "YouTube TV",
        "com.netflix.ninja" to "Netflix",
    )

    fun friendly(pkg: String): String = map[pkg] ?: pkg
}
