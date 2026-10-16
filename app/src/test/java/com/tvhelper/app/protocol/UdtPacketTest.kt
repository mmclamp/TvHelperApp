package com.tvhelper.app.protocol

import com.tvhelper.app.protocol.udt.UdtCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UDTPacket 分帧层（o6.c/o6.b/o6.d）逆向验证：
 * - 16 字节头：[0]=0x55、[1]=0x10|more<<3、[7]=0x10、[8..11]=片长 BE、[12..13]=src、[14..15]=dst
 * - 握手帧：连接后立即发送，ctrl 第6字节=0 / data=1，外层头 src/dst=0
 */
class UdtPacketTest {

    @Test
    fun `single fragment header matches o6c layout`() {
        val hdr = UdtCodec.udtPacketHeader(1025, 1025, 300, more = false)
        assertEquals(16, hdr.size)
        assertEquals(0x55, hdr[0].toInt() and 0xFF)
        assertEquals(0x10, hdr[1].toInt() and 0xFF) // version=1，无 more 位
        assertEquals(0x10, hdr[7].toInt() and 0xFF) // headerLen=16
        assertEquals(300, Bytes.readI32(hdr, 8))
        assertEquals(1025, ((hdr[12].toInt() and 0xFF) shl 8) or (hdr[13].toInt() and 0xFF))
        assertEquals(1025, ((hdr[14].toInt() and 0xFF) shl 8) or (hdr[15].toInt() and 0xFF))
    }

    @Test
    fun `more flag sets bit3`() {
        val hdr = UdtCodec.udtPacketHeader(1025, 1025, 1440, more = true)
        assertEquals(0x18, hdr[1].toInt() and 0xFF)
        assertEquals(1440, Bytes.readI32(hdr, 8))
    }

    @Test
    fun `handshake packet layout for ctrl and data connections`() {
        val ctrl = UdtCodec.handshakePacket(isCtrl = true)
        val data = UdtCodec.handshakePacket(isCtrl = false)
        // 外层头：src/dst=0（原版 b(0,0,handshake)），payload 10 字节
        assertEquals(10, Bytes.readI32(ctrl, 8))
        assertEquals(0, ((ctrl[12].toInt() and 0xFF) shl 8) or (ctrl[13].toInt() and 0xFF))
        assertEquals(0, ((ctrl[14].toInt() and 0xFF) shl 8) or (ctrl[15].toInt() and 0xFF))
        assertEquals(26, ctrl.size)
        val p = ctrl.copyOfRange(16, ctrl.size)
        assertTrue(UdtCodec.isControlPayload(p))
        // payload: {0,0,0,0,5,flag, 0x04,0x01, 0x04,0x01}（appId=1025）
        assertEquals(0x05, p[4].toInt() and 0xFF)
        assertEquals(0, p[5].toInt() and 0xFF)
        assertEquals(1025, ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF))
        assertEquals(1025, ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF))
        // data 连接第 6 字节 = 1
        assertEquals(1, data[16 + 5].toInt() and 0xFF)
    }

    @Test
    fun `message payload is not control`() {
        val msg = UdtCodec.encode(
            UdtCodec.Request(
                requestId = "1:abc",
                call = UdtCodec.MethodCall("openApp", 123L, args = listOf(1L, "com.a.b")),
                id = 1,
            ),
        )
        assertFalse(UdtCodec.isControlPayload(msg))
    }

    @Test
    fun `packet roundtrip via three segment decode`() {
        // 模拟：消息层编码 → 套头 → 接收侧剥头 → 三段解码
        val msg = UdtCodec.encode(
            UdtCodec.Request(
                requestId = "1:test",
                call = UdtCodec.MethodCall("getInstalledPackages", 42L, args = listOf(1L), useDataChannelReturn = true),
                id = 7,
            ),
        )
        val packet = Bytes.concat(
            UdtCodec.udtPacketHeader(UdtCodec.UDT_APP_ID, UdtCodec.UDT_APP_ID, msg.size, more = false),
            msg,
        )
        val hdr = packet.copyOfRange(0, 16)
        assertEquals(0x55, hdr[0].toInt() and 0xFF)
        val chunkLen = Bytes.readI32(hdr, 8)
        assertEquals(msg.size, chunkLen)
        val payload = packet.copyOfRange(16, 16 + chunkLen)
        val decoded = UdtCodec.decode(payload)
        assertEquals("1:test", decoded.requestId)
        assertEquals("getInstalledPackages", decoded.methodName)
    }
}
