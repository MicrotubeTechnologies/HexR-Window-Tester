package com.microtube.hexr

import android.os.SystemClock

/**
 * The model: what gloves exist, what the tester is set to send.
 *
 * A port of `hexr/state.py`. Plain objects polled by the UI on a timer, with no
 * observer graph — the BLE callbacks mutate these fields and the Compose tick
 * reads them. Every field written from a BLE thread is `@Volatile` and set by a
 * single assignment of an immutable value, which is what makes that safe.
 *
 * Polling rather than pushing is deliberate. Telemetry arrives at 50 Hz; driving
 * recomposition from it would rebuild the whole screen fifty times a second to
 * move a few numbers. The desktop tester made the same call.
 *
 * Gloves are keyed by hand, not by address, because a HEXR kit has exactly one
 * left and one right and the two are not interchangeable. Two gloves of the
 * same hand cannot be driven at once, and keying by hand makes that structural
 * rather than something the UI has to police.
 */

const val LEFT = "Left"
const val RIGHT = "Right"
val HANDS = listOf(LEFT, RIGHT)

/**
 * A glove that has not sent a notification in this long is treated as stale.
 * Telemetry arrives at 50 Hz, so this is three orders of magnitude of slack.
 */
const val STALE_AFTER_MS = 3_000L

class Glove(
    val hand: String,
    val address: String,
    val name: String = "",
    val rssi: Int? = null,
) {
    @Volatile var connected: Boolean = false
    @Volatile var connecting: Boolean = false
    @Volatile var status: String = "Not connected"

    /** Negotiated ATT MTU. Null until the glove answers the request. */
    @Volatile var mtu: Int? = null

    val telemetry = Protocol.Telemetry()

    @Volatile var lastRx: Long = 0L

    /** Settled by observation on first connect — see [Protocol.rawToKpa]. */
    @Volatile var absolutePa: Boolean? = null

    /** 0.0–1.0, or null if the glove has not reported yet. */
    val battery: Double? get() = telemetry.battery

    fun batteryLabel(): String {
        val b = telemetry.battery ?: return "—"
        return "${Math.round(b * 100)}%"
    }

    /** Gauge kPa for one channel. Channel 6 is the source/tank. */
    fun kpa(channel: Int): Double =
        if (channel in telemetry.pressureRaw.indices) {
            Protocol.rawToKpa(telemetry.pressureRaw[channel])
        } else {
            0.0
        }

    fun channelKpa(): List<Double> = (0 until 6).map { kpa(it) }

    fun sourceKpa(): Double = kpa(6)

    /**
     * Connected *and* sending telemetry.
     *
     * Not a health check: a glove that has never sent a byte still actuates
     * normally. This gates the things that genuinely need data — the pressure
     * readout and the QA sweep — and nothing else.
     */
    fun isLive(now: Long = SystemClock.elapsedRealtime()): Boolean =
        connected && (now - lastRx) < STALE_AFTER_MS
}

// What the Test screen will send lives in HexrViewModel as Compose state
// rather than here. It is written only by the UI and read only by the UI, so a
// second plain-object copy would be one more thing to keep in step for nothing.

/** One row in the scan results. */
data class Found(
    val address: String,
    val name: String,
    val hand: String,
    val rssi: Int?,
)

class AppState {
    val gloves = java.util.concurrent.ConcurrentHashMap<String, Glove>()

    fun addGlove(hand: String, address: String, name: String = "", rssi: Int? = null): Glove {
        val g = Glove(hand, address, name, rssi)
        gloves[hand] = g
        return g
    }

    fun get(hand: String): Glove? = gloves[hand]

    fun connectedGloves(): List<Glove> = gloves.values.filter { it.connected }

    fun anyConnected(): Boolean = gloves.values.any { it.connected }
}
