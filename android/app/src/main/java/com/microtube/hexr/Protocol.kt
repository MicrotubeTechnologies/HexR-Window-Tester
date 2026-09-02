package com.microtube.hexr

/**
 * HEXR glove wire protocol — frame building and decoding. No I/O.
 *
 * A direct port of `hexr/protocol.py` from the desktop tester. Everything here
 * is pure: builders return `ByteArray`, the decoder consumes `ByteArray`. The
 * BLE transport lives in [BleEngine].
 *
 * This file and its Python original are two copies of one wire format, so they
 * can drift. `ProtocolTest.kt` is a port of the desktop's `test_protocol.py`
 * carrying the same reference frames, which is what stops that drift being
 * silent — if the layouts diverge, one of the two suites goes red.
 *
 * Wire format
 * -----------
 * Outbound  `[len][opcode][TLV...]`            — no checksum, no terminator
 * Inbound   `[len][opcode][TLV...][xor]`       — trailing XOR of bytes 0..len-2
 *
 * `len` is the *whole* frame including itself, which is also the stride the
 * firmware uses to walk several frames concatenated into one write.
 *
 * A TLV is a one-byte type tag followed by a little-endian value.
 *
 * Two facts that are not guessable from the wire and cost real time if missed:
 *
 * 1. **State 1 ("stay") is a no-op.** The firmware acts on 0 (enter) and 2
 *    (exit) only. An earlier Python sample sent 1 to mean "on" and did nothing.
 *
 * 2. **Telemetry is not required for actuation.** Reading the firmware suggests
 *    otherwise — the 50 Hz notify timer also feeds the pressure loop — but
 *    gloves in hand actuate perfectly well having sent nothing back. Subscribe
 *    anyway, because the pressure readout and the QA sweep both need it, but do
 *    not treat silence as a fault.
 */
object Protocol {

    // -- identity -----------------------------------------------------------

    const val SERVICE_UUID = "000000ff-0000-1000-8000-00805f9b34fb"

    /** Write + notify on one handle. */
    const val CHAR_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"

    /** Standard Client Characteristic Configuration descriptor. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val NAME_PREFIX = "HaptGloveAR"

    /**
     * "Left" | "Right" | null. Hand is carried only by the advertised name —
     * the command bytes are identical for both gloves.
     */
    fun handFromName(name: String?): String? {
        val n = name?.trim() ?: return null
        if (!n.startsWith(NAME_PREFIX)) return null
        val tail = n.substring(NAME_PREFIX.length).trim().lowercase()
        return when {
            tail.startsWith("left") -> "Left"
            tail.startsWith("right") -> "Right"
            else -> null
        }
    }

    // -- opcodes ------------------------------------------------------------

    object Op {
        const val AIR_PRESSURE = 0x01
        const val STABLE_PRESSURE_CTRL = 0x02   // dead on current firmware — do not send
        const val SET_PRESSURE_DEPRECATED = 0x03
        const val SET_PRESSURE = 0x04
        const val SET_PID = 0x05
        const val SET_BATTERY_LED = 0x06
        const val SET_VIBRATION = 0x07
        const val SET_PULSE = 0x08
        const val SET_VIB_SPEED = 0x09
        const val SET_PULSE_SPEED = 0x0A
    }

    /** Opcodes the glove sends back. */
    object InOp {
        const val PRESSURE = 0x01
        const val MICROTUBE = 0x04
        const val CLUTCH_ACTIVATED = 0x05
        const val BATTERY = 0x06
    }

    private val INBOUND_OPS = setOf(
        InOp.PRESSURE, InOp.MICROTUBE, InOp.CLUTCH_ACTIVATED, InOp.BATTERY,
    )

    enum class Finger(val channel: Int, val label: String) {
        Thumb(0, "Thumb"),
        Index(1, "Index"),
        Middle(2, "Middle"),
        Ring(3, "Ring"),
        Pinky(4, "Pinky"),
        Palm(5, "Palm");

        companion object {
            fun of(channel: Int): Finger = entries.first { it.channel == channel }
        }
    }

    val ALL_FINGERS: List<Finger> = Finger.entries.toList()

    const val STATE_ENTER = 0   // apply / inflate
    const val STATE_EXIT = 2    // release / vent

    /**
     * Physical limits the firmware itself enforces: below [MIN_KPA] a channel
     * is simply switched off, and the target is capped at [MAX_KPA].
     */
    const val MIN_KPA = 15.0
    const val MAX_KPA = 50.0

    /**
     * Vibration below this frequency is produced pneumatically by toggling the
     * PID target; at or above it the PID is disabled and the channel's PWM is
     * driven hard on/off. The two feel entirely different, so the UI names the
     * boundary.
     */
    const val PWM_VIBRATION_HZ = 5.0

    // -- encoding -----------------------------------------------------------

    private const val TAG_U8: Byte = 0x01
    private const val TAG_U16: Byte = 0x02
    private const val TAG_F32: Byte = 0x09

    private fun u8(v: Int): ByteArray = byteArrayOf(TAG_U8, (v and 0xFF).toByte())

    private fun f32(v: Double): ByteArray {
        // Little-endian IEEE-754 single, matching Python's struct.pack("<f").
        val bits = java.lang.Float.floatToRawIntBits(v.toFloat())
        return byteArrayOf(
            TAG_F32,
            (bits and 0xFF).toByte(),
            ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(),
            ((bits ushr 24) and 0xFF).toByte(),
        )
    }

    /** Prepend the length and opcode. Length counts its own two bytes. */
    private fun frame(op: Int, payload: ByteArray): ByteArray =
        byteArrayOf((payload.size + 2).toByte(), op.toByte()) + payload

    fun clamp(lo: Double, hi: Double, v: Double): Double =
        if (v < lo) lo else if (v > hi) hi else v

    private fun map(inLo: Double, inHi: Double, v: Double, outLo: Double, outHi: Double): Double {
        if (inHi == inLo) return 0.0
        return (outHi - outLo) * (v - inLo) / (inHi - inLo) + outLo
    }

    /** 0.1–1.0 → 15–50 kPa. Exactly 0 means off, not "lightest touch". */
    fun intensityToKpa(intensity: Double): Double {
        if (intensity == 0.0) return 0.0
        return map(0.1, 1.0, clamp(0.1, 1.0, intensity), MIN_KPA, MAX_KPA)
    }

    /** 0.1–1.0 → 10–100. This is a ramp *rate*, roughly 60 ms per 10 kPa at 1.0. */
    fun speedToByte(speed: Double): Int = (clamp(0.1, 1.0, speed) * 100).toInt()

    /** 0.2–0.8 → 20–80, the duty cycle of the vibration waveform in percent. */
    fun peakRatioToByte(peakRatio: Double): Int = (clamp(0.2, 0.8, peakRatio) * 100).toInt()

    // -- outbound commands --------------------------------------------------

    /** Steady closed-loop pressure on one channel. 18 bytes. */
    fun pressure(finger: Finger, on: Boolean, intensity: Double, speed: Double): ByteArray {
        val payload =
            f32(0.0) +                                      // frequency 0 = no vibration
                u8(finger.channel) +
                u8(if (on) STATE_ENTER else STATE_EXIT) +
                f32(intensityToKpa(if (on) intensity else 0.0)) +
                u8(speedToByte(speed))
        return frame(Op.SET_PRESSURE, payload)
    }

    /**
     * Vibration on one channel. 18 bytes.
     *
     * Frequency is clamped to 0.1–40 Hz. Below [PWM_VIBRATION_HZ] this is
     * pneumatic; at or above it the channel's motor is driven directly.
     */
    fun vibration(
        finger: Finger,
        on: Boolean,
        frequency: Double,
        intensity: Double,
        peakRatio: Double = 0.5,
    ): ByteArray {
        val payload =
            f32(clamp(0.1, 40.0, frequency)) +
                u8(finger.channel) +
                u8(if (on) STATE_ENTER else STATE_EXIT) +
                f32(intensityToKpa(if (on) intensity else 0.0)) +
                u8(peakRatioToByte(peakRatio))
        return frame(Op.SET_VIBRATION, payload)
    }

    /**
     * Ramped vibration (FI_SET_VIB_SPEED). 25 bytes.
     *
     * Note the tighter frequency clamp: this mode tops out at 2 Hz, not 40.
     */
    fun vibrationRamped(
        finger: Finger,
        on: Boolean,
        frequency: Double,
        intensity: Double,
        peakRatio: Double,
        speed: Double,
        endIntensity: Double,
    ): ByteArray {
        val endKpa = if (endIntensity <= 0.1) 0.0 else intensityToKpa(endIntensity)
        val payload =
            f32(clamp(0.1, 2.0, frequency)) +
                u8(finger.channel) +
                u8(if (on) STATE_ENTER else STATE_EXIT) +
                f32(intensityToKpa(if (on) intensity else 0.0)) +
                u8(peakRatioToByte(peakRatio)) +
                u8(speedToByte(speed)) +
                f32(endKpa)
        return frame(Op.SET_VIB_SPEED, payload)
    }

    /** Concatenate frames for a single write. The firmware walks them by length. */
    fun batch(frames: List<ByteArray>): ByteArray {
        val out = ByteArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) {
            f.copyInto(out, at)
            at += f.size
        }
        return out
    }

    /** Release every channel. Sent on disconnect, on close, and on demand. */
    fun allOff(fingers: List<Finger> = ALL_FINGERS): ByteArray =
        batch(fingers.map { pressure(it, false, 0.0, 1.0) })

    /**
     * Walk a batch back into its individual frames, using byte 0 as the stride
     * exactly as the firmware does.
     *
     * Only the Android port needs this. A BLE connection carries `MTU - 3`
     * bytes per write and the default MTU is 23, so a 108-byte [allOff] does
     * not fit until the MTU has been raised. When a glove refuses the larger
     * MTU, [BleEngine] falls back to sending these one at a time.
     */
    fun splitFrames(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < data.size) {
            val length = data[at].toInt() and 0xFF
            // A stride that does not advance, or runs off the end, would spin
            // forever or throw. Ship the remainder as one chunk instead: it is
            // malformed either way, and a stall here is a channel left inflated.
            if (length < 3 || at + length > data.size) {
                out.add(data.copyOfRange(at, data.size))
                break
            }
            out.add(data.copyOfRange(at, at + length))
            at += length
        }
        return out
    }

    // -- inbound decoding ---------------------------------------------------

    /** Latest known state of one glove, updated in place by [FrameDecoder]. */
    class Telemetry {
        /** 7 channels: Thumb..Palm then the source/tank reading. */
        val pressureRaw = DoubleArray(7)
        val fingerPosition = IntArray(5)

        /** 0.0–1.0 fraction, not a percentage. Null until the glove reports. */
        @Volatile
        var battery: Double? = null

        @Volatile
        var seen: Boolean = false
    }

    /**
     * Resynchronising stream parser for the notify characteristic.
     *
     * Notifications are not frame-aligned in general, so this buffers,
     * validates the length, verifies the XOR, and only then consumes. A frame
     * that fails validation costs one byte, not the whole buffer — dropping the
     * buffer on a bad byte would resynchronise far more slowly.
     */
    class FrameDecoder(val telemetry: Telemetry = Telemetry()) {

        private val buf = ArrayDeque<Byte>()
        var badFrames: Int = 0
            private set

        fun feed(data: ByteArray): Telemetry {
            for (b in data) buf.addLast(b)
            while (buf.size > MAX_BUFFER) buf.removeFirst()

            while (buf.size >= 3) {
                val length = buf[0].toInt() and 0xFF
                val op = buf[1].toInt() and 0xFF
                if (length < 3 || op !in INBOUND_OPS) {
                    buf.removeFirst()
                    continue
                }
                if (buf.size < length) break

                val frame = ByteArray(length) { buf[it] }
                var checksum = 0
                for (i in 0 until length - 1) checksum = checksum xor (frame[i].toInt() and 0xFF)
                if (checksum != (frame[length - 1].toInt() and 0xFF)) {
                    badFrames++
                    buf.removeFirst()
                    continue
                }
                repeat(length) { buf.removeFirst() }
                apply(frame)
            }
            return telemetry
        }

        private fun apply(frame: ByteArray) {
            val op = frame[1].toInt() and 0xFF
            val t = telemetry
            try {
                when (op) {
                    InOp.PRESSURE -> {
                        // 7 × f32, stride 5 (one tag byte + four data bytes).
                        for (i in 0 until 7) t.pressureRaw[i] = readF32(frame, 3 + i * 5)
                        t.seen = true
                    }

                    InOp.MICROTUBE, InOp.CLUTCH_ACTIVATED -> {
                        for (i in 0 until 5) t.fingerPosition[i] = readI32(frame, 3 + i * 5)
                        t.seen = true
                    }

                    InOp.BATTERY -> {
                        t.battery = readF32(frame, 3)
                        t.seen = true
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                // A frame that passed its checksum but is shorter than its
                // opcode implies. Count it rather than killing the BLE thread.
                badFrames++
            }
        }

        private fun readI32(b: ByteArray, off: Int): Int {
            if (off + 4 > b.size) throw IndexOutOfBoundsException()
            return (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
        }

        private fun readF32(b: ByteArray, off: Int): Double =
            java.lang.Float.intBitsToFloat(readI32(b, off)).toDouble()

        companion object {
            const val MAX_BUFFER = 74 * 16
        }
    }

    // -- pressure units -----------------------------------------------------
    //
    // Unresolved, and the two existing Microtube apps disagree: the firmware
    // comments its value as kPa gauge, the Unity editor tooling converts it
    // with (raw - 100000) / 1000 as though it were absolute pascals, and the
    // Unity test app prints the raw number as "kPa" with no conversion at all.
    //
    // Rather than pick one and be silently wrong, detect it: a glove at rest
    // reads near 0 if the value is gauge kPa, and near 100000 if it is absolute
    // pascals. The threshold sits far from both.

    private const val ABSOLUTE_PA_THRESHOLD = 10000.0

    /** Gauge kPa, whichever way the firmware reports it. */
    fun rawToKpa(raw: Double): Double =
        if (raw > ABSOLUTE_PA_THRESHOLD) (raw - 100000.0) / 1000.0 else raw

    /**
     * True if this glove reports absolute pascals. Settled on first connect so
     * the ambiguity is resolved by observation rather than by argument.
     */
    fun looksLikeAbsolutePa(rawValues: DoubleArray): Boolean =
        rawValues.any { it > ABSOLUTE_PA_THRESHOLD }
}
