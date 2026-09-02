package com.microtube.hexr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the HEXR wire protocol, ported from the desktop
 * tester's `tests/test_protocol.py` with the same reference frames.
 *
 * These carry a second job here that they do not have on the desktop. The
 * Android app reimplements the protocol in Kotlin, so there are now two copies
 * of one wire format. Keeping both suites on identical reference bytes is what
 * makes a divergence show up as a red test rather than as a glove that quietly
 * ignores what the phone sends.
 */
class ProtocolTest {

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

    private fun f32At(b: ByteArray, off: Int): Float {
        val bits = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun u8At(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    // -- outbound -----------------------------------------------------------

    /**
     * Index finger, on, full intensity, full speed.
     *
     * 12 04 | 09 00000000 | 01 01 | 01 00 | 09 00004842 | 01 64
     * len op   f32 freq=0   finger  state    f32 50.0kPa   speed=100
     */
    @Test
    fun pressureReferenceFrame() {
        val frame = Protocol.pressure(Protocol.Finger.Index, true, 1.0, 1.0)
        assertEquals("12 04 09 00 00 00 00 01 01 01 00 09 00 00 48 42 01 64", hex(frame))
        assertEquals(18, frame.size)
        assertEquals(frame.size, u8At(frame, 0))
    }

    @Test
    fun pressureReleaseFrame() {
        val frame = Protocol.pressure(Protocol.Finger.Index, false, 0.0, 1.0)
        assertEquals(18, u8At(frame, 0))
        assertEquals(Protocol.Op.SET_PRESSURE, u8At(frame, 1))
        assertEquals(Protocol.Finger.Index.channel, u8At(frame, 8))
        assertEquals(Protocol.STATE_EXIT, u8At(frame, 10))
        assertEquals(0.0f, f32At(frame, 12), 0.0f)
    }

    @Test
    fun everyFingerEncodesItsOwnChannel() {
        for (f in Protocol.ALL_FINGERS) {
            val frame = Protocol.pressure(f, true, 1.0, 1.0)
            assertEquals("${f.name} encoded the wrong channel", f.channel, u8At(frame, 8))
        }
    }

    /** 1 means "stay" and the firmware ignores it. Neither path may emit it. */
    @Test
    fun stateIsNeverTheNoOpValue() {
        for (on in listOf(true, false)) {
            val frames = listOf(
                Protocol.pressure(Protocol.Finger.Thumb, on, 0.5, 0.5),
                Protocol.vibration(Protocol.Finger.Thumb, on, 10.0, 0.5),
            )
            for (frame in frames) {
                assertTrue(u8At(frame, 10) in listOf(Protocol.STATE_ENTER, Protocol.STATE_EXIT))
            }
        }
    }

    @Test
    fun vibrationReferenceFrame() {
        val frame = Protocol.vibration(Protocol.Finger.Palm, true, 20.0, 1.0, 0.5)
        assertEquals(18, frame.size)
        assertEquals(18, u8At(frame, 0))
        assertEquals(Protocol.Op.SET_VIBRATION, u8At(frame, 1))
        assertEquals(20.0f, f32At(frame, 3), 0.0f)
        assertEquals(Protocol.Finger.Palm.channel, u8At(frame, 8))
        assertEquals(Protocol.STATE_ENTER, u8At(frame, 10))
        assertEquals(50.0f, f32At(frame, 12), 0.0f)
        assertEquals(50, u8At(frame, 17))          // peak ratio 0.5 -> 50%
    }

    @Test
    fun vibrationRampedFrameLength() {
        val frame = Protocol.vibrationRamped(Protocol.Finger.Ring, true, 1.5, 0.8, 0.4, 0.9, 0.3)
        assertEquals(25, frame.size)
        assertEquals(25, u8At(frame, 0))
        assertEquals(Protocol.Op.SET_VIB_SPEED, u8At(frame, 1))
    }

    /** This mode tops out at 2 Hz even though plain vibration allows 40. */
    @Test
    fun rampedVibrationClampsFrequencyToTwoHz() {
        val frame = Protocol.vibrationRamped(Protocol.Finger.Index, true, 40.0, 1.0, 0.5, 1.0, 0.5)
        assertEquals(2.0f, f32At(frame, 3), 0.0f)
    }

    // -- value mapping ------------------------------------------------------

    @Test
    fun intensityMapsToTheFirmwarePressureWindow() {
        assertEquals(0.0, Protocol.intensityToKpa(0.0), 0.0)          // exactly zero means off
        assertEquals(Protocol.MIN_KPA, Protocol.intensityToKpa(0.1), 1e-9)
        assertEquals(Protocol.MAX_KPA, Protocol.intensityToKpa(1.0), 1e-9)
        assertEquals(Protocol.MAX_KPA, Protocol.intensityToKpa(5.0), 1e-9)  // clamped
    }

    @Test
    fun speedAndPeakRatioByteRanges() {
        assertEquals(10, Protocol.speedToByte(0.1))
        assertEquals(100, Protocol.speedToByte(1.0))
        assertEquals(100, Protocol.speedToByte(9.0))
        assertEquals(20, Protocol.peakRatioToByte(0.2))
        assertEquals(80, Protocol.peakRatioToByte(0.8))
        assertEquals(20, Protocol.peakRatioToByte(0.01))
    }

    // -- batching -----------------------------------------------------------

    /**
     * A pressure exit does not stop a vibrating channel.
     *
     * Above PWM_VIBRATION_HZ the firmware disables the pressure loop and drives
     * the motor directly, so a frame that only zeroes a pressure target never
     * reaches what is running. allOff has to exit both paths or "release all"
     * leaves a channel buzzing.
     */
    @Test
    fun allOffStopsBothOpcodesOnEveryChannel() {
        val data = Protocol.allOff()
        assertEquals(18 * 12, data.size)

        // The firmware walks concatenated frames using byte 0 as the stride.
        var offset = 0
        val seen = ArrayList<Triple<Int, Int, Int>>()
        while (offset < data.size) {
            val length = u8At(data, offset)
            assertEquals(18, length)
            seen.add(Triple(u8At(data, offset + 1), u8At(data, offset + 8), u8At(data, offset + 10)))
            offset += length
        }

        val channels = Protocol.ALL_FINGERS.map { it.channel }
        val expected =
            channels.map { Triple(Protocol.Op.SET_VIBRATION, it, Protocol.STATE_EXIT) } +
                channels.map { Triple(Protocol.Op.SET_PRESSURE, it, Protocol.STATE_EXIT) }
        assertEquals(expected, seen)
    }

    /** Order matters: the last word on a channel is a zero pressure target. */
    @Test
    fun allOffEndsOnThePressureExit() {
        val data = Protocol.allOff()
        assertEquals(Protocol.Op.SET_PRESSURE, u8At(data, data.size - 18 + 1))
        assertEquals(0.0f, f32At(data, data.size - 18 + 12), 0.0f)
    }

    /**
     * The MTU fallback path must reproduce the batch byte for byte. If it does
     * not, a glove with a small MTU is being sent something different from a
     * glove with a large one — the worst kind of bug to chase in hardware.
     */
    @Test
    fun splitFramesRoundTripsABatch() {
        val data = Protocol.allOff()
        val parts = Protocol.splitFrames(data)
        assertEquals(12, parts.size)
        assertTrue(parts.all { it.size == 18 })
        assertEquals(hex(data), hex(Protocol.batch(parts)))
    }

    /** A malformed stride must not spin forever — that would strand a channel inflated. */
    @Test
    fun splitFramesTerminatesOnAMalformedStride() {
        assertEquals(1, Protocol.splitFrames(byteArrayOf(0, 0, 0)).size)
        assertEquals(1, Protocol.splitFrames(byteArrayOf(99, 4, 4)).size)
        assertTrue(Protocol.splitFrames(ByteArray(0)).isEmpty())
    }

    // -- names --------------------------------------------------------------

    @Test
    fun handIsReadFromTheAdvertisedName() {
        assertEquals("Left", Protocol.handFromName("HaptGloveAR Left"))
        assertEquals("Right", Protocol.handFromName("HaptGloveAR Right"))
        assertNull(Protocol.handFromName("HaptGloveAR"))
        assertNull(Protocol.handFromName("Something Else"))
        assertNull(Protocol.handFromName(null))
    }

    // -- inbound ------------------------------------------------------------

    /** Build a device->host frame, including its trailing XOR checksum. */
    private fun inbound(op: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf((payload.size + 3).toByte(), op.toByte()) + payload
        var checksum = 0
        for (b in body) checksum = checksum xor (b.toInt() and 0xFF)
        return body + byteArrayOf(checksum.toByte())
    }

    private fun f32Bytes(v: Double): ByteArray {
        val bits = java.lang.Float.floatToRawIntBits(v.toFloat())
        return byteArrayOf(
            0x09,
            (bits and 0xFF).toByte(),
            ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(),
            ((bits ushr 24) and 0xFF).toByte(),
        )
    }

    private fun pressureFrame(values: List<Double>): ByteArray =
        inbound(Protocol.InOp.PRESSURE, values.fold(ByteArray(0)) { acc, v -> acc + f32Bytes(v) })

    private fun batteryFrame(level: Double): ByteArray =
        inbound(Protocol.InOp.BATTERY, f32Bytes(level))

    @Test
    fun decodesPressureAndBattery() {
        val d = Protocol.FrameDecoder()
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        val t = d.feed(pressureFrame(values) + batteryFrame(0.75))
        assertEquals(values, t.pressureRaw.map { it })
        assertEquals(0.75, t.battery!!, 1e-3)
        assertEquals(0, d.badFrames)
    }

    /** Notifications are not frame-aligned; the parser must hold state. */
    @Test
    fun framesSplitAcrossNotificationsStillDecode() {
        val data = pressureFrame(List(7) { 9.0 })
        val d = Protocol.FrameDecoder()
        d.feed(data.copyOfRange(0, 5))
        assertEquals(0.0, d.telemetry.pressureRaw[0], 0.0)   // nothing consumed yet
        val t = d.feed(data.copyOfRange(5, data.size))
        assertEquals(9.0, t.pressureRaw[0], 1e-6)
    }

    @Test
    fun aCorruptFrameDoesNotSwallowTheNextGoodOne() {
        val bad = batteryFrame(0.5)
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()   // break the checksum
        val d = Protocol.FrameDecoder()
        val t = d.feed(bad + batteryFrame(0.25))
        assertTrue(d.badFrames >= 1)
        assertEquals(0.25, t.battery!!, 1e-3)
    }

    @Test
    fun leadingGarbageIsResynchronised() {
        val d = Protocol.FrameDecoder()
        val t = d.feed(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x07) + batteryFrame(0.5))
        assertEquals(0.5, t.battery!!, 1e-3)
    }

    // -- units --------------------------------------------------------------

    @Test
    fun rawToKpaHandlesBothFirmwareConventions() {
        assertEquals(42.0, Protocol.rawToKpa(42.0), 1e-9)             // already gauge kPa
        assertEquals(42.0, Protocol.rawToKpa(142000.0), 1e-9)         // absolute pascals
        assertTrue(Protocol.looksLikeAbsolutePa(doubleArrayOf(100500.0)))
        assertFalse(Protocol.looksLikeAbsolutePa(doubleArrayOf(0.5, 1.0)))
    }
}
