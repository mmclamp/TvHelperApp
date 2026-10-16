package com.tvhelper.app.protocol

import com.tvhelper.app.net.IpProbe
import com.tvhelper.app.protocol.rc.RcCodec
import com.tvhelper.app.protocol.rc.RcKeyCodec
import com.tvhelper.app.protocol.rc.RcTouchCodec
import com.tvhelper.app.protocol.rc.RcVersionCodec
import com.tvhelper.app.protocol.rc.TvKey
import com.tvhelper.app.protocol.udt.KnownTvPackages
import com.tvhelper.app.protocol.udt.UdtCodec
import com.tvhelper.app.protocol.udt.UdtMethods
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议黄金测试：用逆向还原的字节布局校验编解码器。
 * 纯 JVM 运行，不依赖 Android 运行时。
 */
class ProtocolTest {

    // ============================================================ RC 基础帧

    @Test
    fun rcFrameHeaderIsTypePlusBigEndianLength() {
        val f = RcCodec.frame(RcCodec.TYPE_KEY, ByteArray(300) { 0x7F })
        assertEquals(RcCodec.TYPE_KEY, Bytes.u8(f, 0))
        assertEquals(300, Bytes.readI16(f, 1))
        assertEquals(303, f.size)
    }

    @Test
    fun heartbeatAndReadyAreThreeBytesWithDistinctType() {
        assertArrayEquals(byteArrayOf(2, 0, 0), RcCodec.heartbeat())
        assertArrayEquals(byteArrayOf(61, 0, 0), RcCodec.ready())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rcPayloadOver1500IsRejected() {
        RcCodec.frame(RcCodec.TYPE_KEY, ByteArray(1501))
    }

    // ============================================================ RC 版本包

    @Test
    fun versionRequestMatchesReversedLayout() {
        val text = RcVersionCodec.DEFAULT_TEXT
        val frame = RcVersionCodec.frameRequest(RcVersionCodec.RcVersion())
        val payload = frame.copyOfRange(3, frame.size)

        assertEquals(RcCodec.TYPE_VERSION, Bytes.u8(frame, 0))
        assertEquals(payload.size, Bytes.readI16(frame, 1))
        assertEquals(RcVersionCodec.CODE_REQUEST, Bytes.u8(payload, 0))
        // subcode 01 + version int32
        assertEquals(1, Bytes.u8(payload, 1))
        assertEquals(0x01000000, Bytes.readI32(payload, 2))
        // subcode 02 + u16 长度 + 版本文字
        assertEquals(2, Bytes.u8(payload, 6))
        assertEquals(text.toByteArray(Charsets.UTF_8).size, Bytes.readI16(payload, 7))
        val len = Bytes.readI16(payload, 7)
        assertEquals(text, String(payload, 9, len, Charsets.UTF_8))
    }

    @Test
    fun versionResponseParsesSecretKeyAndCryptoFlag() {
        val extra = """{"secretKey":"QUJDREVGR0hJSktMTU5PUFFS","k1":"v"}"""
        val extraBytes = Bytes.utf8(extra)
        val data = Bytes.concat(
            byteArrayOf(RcVersionCodec.CODE_RESPONSE.toByte()),
            byteArrayOf(1), Bytes.i32be(0x02000000),
            byteArrayOf(3, 1),
            byteArrayOf(4), Bytes.i16be(extraBytes.size), extraBytes,
        )
        val frame = RcCodec.frame(RcCodec.TYPE_VERSION, data)

        val r = RcVersionCodec.parse(frame)
        assertEquals(RcVersionCodec.CODE_RESPONSE, r.code)
        assertEquals(0x02000000, r.version)
        assertTrue(r.needsCrypto)
        assertEquals("QUJDREVGR0hJSktMTU5PUFFS", r.secretKey)
    }

    @Test
    fun versionResponseWithoutSecretKeyMeansPlaintextOk() {
        val data = Bytes.concat(
            byteArrayOf(RcVersionCodec.CODE_RESPONSE.toByte()),
            byteArrayOf(1), Bytes.i32be(0x01000000),
            byteArrayOf(3, 3),
            byteArrayOf(4), Bytes.i16be(0), ByteArray(0),
        )
        val r = RcVersionCodec.parse(RcCodec.frame(RcCodec.TYPE_VERSION, data))
        assertEquals(0x01000000, r.version)
        assertTrue(!r.needsCrypto)
        assertNull(r.secretKey)
    }

    // ============================================================ RC 按键

    @Test
    fun sendKeyFrameIsByteExactForDpadUpDown() {
        RcKeyCodec.resetMsgIdForTest(0) // 下一次自增得到 1
        val frame = RcKeyCodec.frame(
            RcKeyCodec.KeyEvent(
                keyCode = TvKey.DPAD_UP,
                action = RcKeyCodec.ACTION_DOWN,
                downTime = 1000L,
                eventTime = 1000L,
            )
        )

        // [04][0041] 三字节 RC 头；payload 65 = 7 字节 SendKeyHeader + 58 字节 TLV
        assertEquals(4, Bytes.u8(frame, 0))
        assertEquals(65, Bytes.readI16(frame, 1))
        assertEquals(68, frame.size)

        // 04 | 0041 | 01 | 00000001 | 003A
        assertEquals("0400410100000001003A", Bytes.toHex(frame.copyOfRange(0, 10)).uppercase())

        val data = frame.copyOfRange(10, frame.size)
        assertEquals(58, data.size)

        val tlv = parseTlv(data)
        assertEquals(0, tlv.i(1))                 // metaState
        assertEquals(0, tlv.i(2))                 // action = DOWN
        assertEquals(TvKey.DPAD_UP, tlv.i(3))     // keyCode 19
        assertEquals(0, tlv.i(4))                 // scanCode
        assertEquals(0, tlv.i(5))                 // repeatCount
        assertEquals(0, tlv.i(6))                 // flags
        assertEquals(1000L, tlv.l(7))             // downTime
        assertEquals(1000L, tlv.l(8))             // eventTime
        assertEquals(-1, tlv.i(10))               // deviceId = VIRTUAL_KEYBOARD
        assertEquals(0x0101, tlv.i(11))           // source = SOURCE_KEYBOARD
    }

    @Test
    fun sendKeyTlvOrderPutsDeviceIdAndSourceBeforeCharacters() {
        val data = RcKeyCodec.buildKeyData(
            RcKeyCodec.KeyEvent(
                keyCode = TvKey.ENTER,
                action = RcKeyCodec.ACTION_UP,
                downTime = 1L,
                eventTime = 2L,
                characters = "a",
            )
        )
        val order = ArrayList<Int>()
        var i = 0
        while (i < data.size) {
            val code = Bytes.u8(data, i)
            order += code
            i += when (code) {
                7, 8 -> 9
                9, 12 -> 3 + Bytes.readI16(data, i + 1)
                else -> 5
            }
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 9), order)
    }

    @Test
    fun msgIdIncrementsPerFrame() {
        RcKeyCodec.resetMsgIdForTest(0)
        val a = RcKeyCodec.frame(RcKeyCodec.KeyEvent(TvKey.BACK, 0, 1L, 1L))
        val b = RcKeyCodec.frame(RcKeyCodec.KeyEvent(TvKey.BACK, 1, 1L, 2L))
        assertEquals(1, Bytes.readI32(a, 4))
        assertEquals(2, Bytes.readI32(b, 4))
    }

    @Test
    fun downUpProducesTwoFramesWithDownAndUpActions() {
        RcKeyCodec.resetMsgIdForTest(0)
        val frames = RcKeyCodec.framesDownUp(TvKey.BACK, 10L)
        assertEquals(2, frames.size)

        val down = parseTlv(frames[0].copyOfRange(10, frames[0].size))
        val up = parseTlv(frames[1].copyOfRange(10, frames[1].size))
        assertEquals(RcKeyCodec.ACTION_DOWN, down.i(2))
        assertEquals(RcKeyCodec.ACTION_UP, up.i(2))
        assertEquals(TvKey.BACK, down.i(3))
        assertEquals(TvKey.BACK, up.i(3))
        // UP 帧的 eventTime 必须晚于 downTime（否则电视端输入系统可能丢弃）
        assertEquals(10L + 120, up.l(8))
        assertNotEquals(0, Bytes.readI32(frames[0], 4))
    }

    @Test
    fun keyFramesCarryRealScanCodes() {
        // 音量键必须带真实 scancode（电视端部分处理依赖它），不能是 0
        assertEquals(115, RcKeyCodec.scanCodeFor(TvKey.VOLUME_UP))
        assertEquals(114, RcKeyCodec.scanCodeFor(TvKey.VOLUME_DOWN))
        assertEquals(158, RcKeyCodec.scanCodeFor(TvKey.BACK))
        RcKeyCodec.resetMsgIdForTest(0)
        val f = RcKeyCodec.frameDown(TvKey.VOLUME_UP, 100L)
        val down = parseTlv(f.copyOfRange(10, f.size))
        assertEquals(115, down.i(4))
    }

    @Test
    fun repeatDownCarriesRepeatCount() {
        RcKeyCodec.resetMsgIdForTest(0)
        val f1 = RcKeyCodec.frameDown(TvKey.VOLUME_UP, 100L, eventTime = 100L, repeatCount = 0)
        val f3 = RcKeyCodec.frameDown(TvKey.VOLUME_UP, 100L, eventTime = 460L, repeatCount = 2)
        val first = parseTlv(f1.copyOfRange(10, f1.size))
        val third = parseTlv(f3.copyOfRange(10, f3.size))
        assertEquals(0, first.i(5))
        assertEquals(2, third.i(5))
        // repeat 帧保持同一次按键的 downTime，eventTime 随当前时刻推进
        assertEquals(100L, first.l(7))
        assertEquals(100L, third.l(7))
        assertEquals(460L, third.l(8))
    }

    // ============================================================ RC 触摸

    @Test
    fun touchFrameCarriesPointerGeometry() {
        RcTouchCodec.resetMsgIdForTest(0)
        val frame = RcTouchCodec.frame(
            RcTouchCodec.TouchEvent(
                action = RcTouchCodec.ACTION_DOWN,
                downTime = 5L,
                eventTime = 6L,
                pointers = listOf(RcTouchCodec.TouchPointer(x = 960f, y = 540f)),
            )
        )
        assertEquals(6, Bytes.u8(frame, 0))
        assertEquals(frame.size - 3, Bytes.readI16(frame, 1))

        val payload = frame.copyOfRange(3, frame.size)
        assertEquals(1, Bytes.u8(payload, 0))          // subType
        assertEquals(1, Bytes.readI32(payload, 1))     // msgId
        assertEquals(payload.size - 7, Bytes.readI16(payload, 5))

        val tlv = parseTlv(payload.copyOfRange(7, payload.size), ::touchTlvKind)
        assertEquals(1, tlv.i(0x0C))                   // pointerCount
        assertEquals(0, tlv.i(0x0D))                   // pointer index
        assertEquals(960f, tlv.f(0x1B), 0.001f)        // x
        assertEquals(540f, tlv.f(0x17), 0.001f)        // y
        assertEquals(RcTouchCodec.SOURCE_TOUCHSCREEN, tlv.i(7))
    }

    // ============================================================ UDT 帧

    @Test
    fun udtLengthPrefixMatchesThreeRegions() {
        val req = UdtMethods.installUrlApp("http://192.168.1.5:8765/demo.apk", 7, "1:fixed")
        val bytes = UdtCodec.encode(req)
        val lengths = UdtCodec.readLengths(bytes)

        assertEquals(bytes.size, 12 + lengths.first + lengths.second + lengths.third)
        assertEquals(bytes.size, UdtCodec.totalLength(bytes))
        assertEquals(0, lengths.second) // ctrl 连接请求 data 段为空

        val msg = UdtCodec.decode(bytes)
        assertEquals("1:fixed", msg.requestId)
        assertTrue(msg.isRequest)
        assertEquals(7, msg.messageId)
        assertEquals(UdtMethods.INSTALL_URL_APP, msg.methodName)
    }

    @Test
    fun udtCtrlJsonUsesActionKeyNotType() {
        val req = UdtMethods.openApp("com.xiaomi.mitv.appstore", 3)
        val bytes = UdtCodec.encode(req)
        val ctrlLen = UdtCodec.readLengths(bytes).first
        val ctrlJson = JSONObject(String(bytes, 12, ctrlLen, Charsets.UTF_8))

        assertEquals(0, ctrlJson.getInt("action"))
        assertTrue(ctrlJson.getBoolean("request"))
        assertTrue(!ctrlJson.has("type"))

        val control = ctrlJson.getJSONObject("control")
        assertEquals(0, control.getInt("type"))

        val method = control.getJSONObject("method")
        assertEquals("openApp", method.getString("methodName"))
        assertTrue(method.has("methodCallID"))
        assertEquals("", method.getString("invokerID"))
        assertEquals("", method.getString("verifyCode"))

        val call = method.getJSONObject("methodCall")
        val args: JSONArray = call.getJSONArray("args")
        assertEquals(2, args.length())
        assertEquals("com.xiaomi.mitv.appstore", args.getString(1))
        assertEquals(req.call.methodCallId, args.getLong(0))
        assertTrue(call.getBoolean("useDataChannelReturn"))
    }

    @Test
    fun udtTailRepeatsRequestIdAndInstalledPackagesUsesDataChannel() {
        val req = UdtMethods.getInstalledPackages(1)
        val bytes = UdtCodec.encode(req)
        val lengths = UdtCodec.readLengths(bytes)

        val ctrl = JSONObject(String(bytes, 12, lengths.first, Charsets.UTF_8))
        val tail = JSONObject(
            String(bytes, 12 + lengths.first + lengths.second, lengths.third, Charsets.UTF_8)
        )

        assertEquals(ctrl.getString("requestId"), tail.getString("requestId"))
        assertTrue(!tail.getBoolean("encrypt"))

        val call = ctrl.getJSONObject("control").getJSONObject("method").getJSONObject("methodCall")
        assertTrue(!call.getBoolean("useDataChannelReturn"))
        assertEquals(1, call.getJSONArray("args").length())

        assertTrue(UdtMethods.useDataConnection(UdtMethods.GET_INSTALLED_PACKAGES))
        assertTrue(!UdtMethods.useDataConnection(UdtMethods.OPEN_APP))
    }

    @Test
    fun udtResponseExposesStatusResultAndPaging() {
        val ctrl = JSONObject(
            """
            {"action":0,"request":false,"requestId":"1:abc","id":9,
             "control":{"type":0,"method":{"methodName":"getInstalledPackages","methodCallID":42,
             "methodReturn":{"status":0,"result":{"ok":true},"error_msg":""},
             "dataChannel":{"page_no":2,"total":3,"page_size":1048576,"total_size":2097152,"md5":"aa"}}}}
            """.trimIndent()
        )
        val data = Bytes.utf8("""{"PkgInfos":[]}""")
        val msg = UdtCodec.decode(
            UdtCodec.encode(UdtCodec.Message(ctrl, data, JSONObject("{}")))
        )

        assertEquals("1:abc", msg.requestId)
        assertEquals(0, (msg.status ?: -1).toLong().toInt())
        assertEquals(true, msg.result?.getBoolean("ok"))
        assertEquals("getInstalledPackages", msg.methodName)
        assertEquals(2, (msg.dataChannel?.pageNo ?: -1).toLong().toInt())
        assertEquals(3, (msg.dataChannel?.total ?: -1).toLong().toInt())
    }

    @Test
    fun installedPackagesParsingToleratesMissingLabel() {
        // 用程序化构造避免字符串转义歧义
        val withLabel = JSONObject().apply {
            put("pkgName", "com.bilibili.tv")
            put("versionCode", 12)
            put("versionName", "1.2.3")
            put("lastUpdateTime", 200L)
            put("appInfo", JSONObject().put("label", "哔哩哔哩").put("flags", 0).toString())
        }
        val appInfoObj = JSONObject().apply {
            put("name", "com.unknown.app")
            put("pkgName", "com.unknown.app")
            put("clsName", "com.unknown.app.Main")
            put("flags", 1)
        }
        val noLabel = JSONObject().apply {
            put("pkgName", "com.unknown.app")
            put("versionCode", 1)
            put("lastUpdateTime", 300L)
            put("appInfo", appInfoObj.toString())
        }
        val root = JSONObject().apply {
            put("PkgInfos", JSONArray().put(withLabel).put(noLabel))
            put("extradata", JSONObject())
        }

        val apps = UdtMethods.parseInstalledPackages(Bytes.utf8(root.toString()))
        assertEquals(2, apps.size)
        assertEquals("com.unknown.app", apps[0].packageName)   // lastUpdateTime 倒序
        assertEquals("com.unknown.app", apps[0].displayName)   // 无 label 时回退包名
        assertTrue(apps[0].isSystem)                           // flags bit0
        assertEquals("哔哩哔哩", apps[1].displayName)
        assertEquals("哔哩哔哩TV", KnownTvPackages.friendly("tv.danmaku.bilianime.tv"))
    }

    @Test
    fun installedPackagesParsingHandlesGarbage() {
        assertTrue(UdtMethods.parseInstalledPackages(ByteArray(0)).isEmpty())
        assertTrue(UdtMethods.parseInstalledPackages(Bytes.utf8("not json")).isEmpty())
        assertTrue(UdtMethods.parseInstalledPackages(Bytes.utf8("""{"foo":1}""")).isEmpty())
    }

    // ============================================================ 端口候选

    @Test
    fun rcPortCandidatesPreferAdvertisedThenKnownDefaults() {
        // mDNS 广播了端口：排在最前，其余兜底
        assertEquals(listOf(6091, 30612), IpProbe.rcCandidates(6091))
        assertEquals(listOf(30612, 6091), IpProbe.rcCandidates(30612))
        // 无广播端口（手输 IP）：用默认值
        assertEquals(listOf(30612, 6091), IpProbe.rcCandidates(0))
        // 去重
        assertEquals(listOf(6091, 30612), IpProbe.rcCandidates(6091))
    }

    @Test
    fun udtPortCandidatesFallBackTo6093() {
        assertEquals(listOf(6093), IpProbe.udtCandidates(0))
        assertEquals(listOf(6097, 6093), IpProbe.udtCandidates(6097))
    }

    // ============================================================ 工具

    private enum class TlvKind { INT, LONG, FLOAT, STRING }

    private class Tlv(private val map: Map<Int, Any>) {
        fun i(code: Int): Int = map[code] as? Int
            ?: error("TLV $code 不是 Int：${map[code]}")

        fun l(code: Int): Long = when (val v = map[code]) {
            is Long -> v
            is Int -> v.toLong()
            else -> error("TLV $code 不是 Long：$v")
        }

        fun f(code: Int): Float = map[code] as? Float
            ?: error("TLV $code 不是 Float：${map[code]}")
    }

    /** 按键 TLV：7/8 为 i64，9/12 为字符串，其余 i32 */
    private fun keyTlvKind(code: Int): TlvKind = when (code) {
        7, 8 -> TlvKind.LONG
        9, 12 -> TlvKind.STRING
        else -> TlvKind.INT
    }

    /**
     * 触摸 TLV：只有 04 downTime / 05 eventTime 是 i64；
     * 0x10~0x16、0x1B、0x17、09、0A 是 f32；其余 i32。
     * 注意 09 与 0C 在触摸里分别是 xPrecision / pointerCount，与按键语义不同。
     */
    private fun touchTlvKind(code: Int): TlvKind = when (code) {
        4, 5 -> TlvKind.LONG
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x1B, 0x17, 9, 10 -> TlvKind.FLOAT
        else -> TlvKind.INT
    }

    private fun parseTlv(data: ByteArray, kind: (Int) -> TlvKind = ::keyTlvKind): Tlv {
        val map = HashMap<Int, Any>()
        var i = 0
        while (i < data.size) {
            val code = Bytes.u8(data, i)
            when (kind(code)) {
                TlvKind.LONG -> {
                    require(i + 9 <= data.size) { "TLV $code 越界：$i/${data.size}" }
                    map[code] = Bytes.readI64(data, i + 1)
                    i += 9
                }
                TlvKind.STRING -> {
                    require(i + 3 <= data.size) { "TLV $code 越界：$i/${data.size}" }
                    val len = Bytes.readI16(data, i + 1)
                    require(i + 3 + len <= data.size) { "TLV $code 字符串越界：$i/${data.size}" }
                    map[code] = String(data, i + 3, len, Charsets.UTF_8)
                    i += 3 + len
                }
                TlvKind.FLOAT -> {
                    require(i + 5 <= data.size) { "TLV $code 越界：$i/${data.size}" }
                    map[code] = Bytes.readF32(data, i + 1)
                    i += 5
                }
                TlvKind.INT -> {
                    require(i + 5 <= data.size) { "TLV $code 越界：$i/${data.size}" }
                    map[code] = Bytes.readI32(data, i + 1)
                    i += 5
                }
            }
        }
        return Tlv(map)
    }
}
