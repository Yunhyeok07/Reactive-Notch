package com.reactivenotch.bt

import com.reactivenotch.bt.SonyCommands.sanitized
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesKnownFrame() {
        // 참조 구현 알고리즘으로 손계산: 0C 00 00000002 00 00 -> 체크섬 0E
        val frame = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, bytes(0x00, 0x00))
        assertArrayEquals(bytes(0x3E, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x0E, 0x3C), frame)
    }

    @Test
    fun escapesSpecialBytesInPayload() {
        val frame = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, bytes(0x3C, 0x3D, 0x3E))
        // 본문 중간에 START/END 가 그대로 나타나면 안 된다.
        val inner = frame.copyOfRange(1, frame.size - 1)
        assertTrue(inner.none { (it.toInt() and 0xFF) == 0x3E || (it.toInt() and 0xFF) == 0x3C })
        assertEquals(0x3E, frame.first().toInt() and 0xFF)
        assertEquals(0x3C, frame.last().toInt() and 0xFF)
    }

    @Test
    fun roundTripsPayloadsWithSpecialBytes() {
        val payload = bytes(0x3C, 0x3D, 0x3E, 0x00, 0xFF, 0x22)
        val frames = SonyFrame.Decoder().feed(SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, payload))
        assertEquals(1, frames.size)
        assertEquals(SonyFrame.TYPE_DATA_MDR, frames[0].type)
        assertEquals(1, frames[0].seq)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun reassemblesFramesSplitAcrossReads() {
        val a = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, bytes(0x23, 0x09, 80, 0, 75, 1))
        val b = SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0))
        val stream = a + b
        val decoder = SonyFrame.Decoder()
        val out = ArrayList<SonyFrame.Frame>()
        for (byte in stream) out += decoder.feed(byteArrayOf(byte)) // 1바이트씩
        assertEquals(2, out.size)
        assertEquals(SonyFrame.TYPE_ACK, out[1].type)
    }

    @Test
    fun dropsFramesWithBadChecksumAndRecovers() {
        val good = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, bytes(1, 2, 3))
        val bad = good.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        val decoder = SonyFrame.Decoder()
        val frames = decoder.feed(bad + good)
        assertEquals(1, frames.size)
        assertEquals(1, decoder.droppedFrames)
    }

    @Test
    fun parsesDualBatteryAndCase() {
        val dual = SonyCommands.parse(bytes(0x23, 0x09, 80, 0, 75, 1), BatteryInfo())
        val info = (dual as SonyEvent.Battery).info
        assertEquals(80, info.left)
        assertEquals(75, info.right)
        assertTrue(info.rightCharging)

        val case = SonyCommands.parse(bytes(0x25, 0x0A, 60, 0), info) as SonyEvent.Battery
        assertEquals(60, case.info.case)
        assertEquals(80, case.info.left) // 이전 값 유지
    }

    @Test
    fun unknownPayloadIsReportedNotGuessed() {
        assertTrue(SonyCommands.parse(bytes(0x99, 0x01), BatteryInfo()) is SonyEvent.Unknown)
        assertTrue(SonyCommands.parse(bytes(0x23), BatteryInfo()) is SonyEvent.Unknown)
    }

    @Test
    fun sanitizeDropsOutOfRangeBattery() {
        val s = BatteryInfo(left = 250, right = 40).sanitized()
        assertNull(s.left)
        assertEquals(40, s.right)
    }

    @Test
    fun setNoiseModeRoundTripsThroughParser() {
        val set = SonyCommands.setNoiseMode(NoiseMode.AMBIENT)
        val notify = set.copyOf().also { it[0] = 0x69 }
        val ev = SonyCommands.parse(notify, BatteryInfo()) as SonyEvent.Noise
        assertEquals(NoiseMode.AMBIENT, ev.mode)
    }
}
