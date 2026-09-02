package com.microtube.hexr

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the UI samples the model. Telemetry arrives far faster; see [Glove]. */
private const val TICK_MS = 100L

/**
 * The canned QA sweep, ported from `hexr/screens/quicktest.py`.
 *
 * The thresholds here must match the desktop tester's, or the same glove passes
 * on one and fails on the other. They were last retuned in desktop v0.2.0.
 */
object QuickTest {
    const val SETTLE_MS = 600L      // vent and let the channels fall back
    const val DRIVE_MS = 2_000L     // how long each channel is held at full

    const val PASS_KPA = 45.0       // full strength
    const val GOOD_KPA = 40.0       // within tolerance
    const val WEAK_KPA = 30.0       // reached pressure, but never got to full

    /**
     * A channel that never moves at all is not a weak indenter — it is getting
     * no air. Sensors on a dead channel float on noise rather than reading a
     * clean 0.0, so anything under this counts as nothing.
     */
    const val ZERO_KPA = 1.0

    /**
     * If the source/tank never develops pressure, every channel fails for one
     * reason — a dead pump — and reporting six failed indenters would send
     * someone replacing the wrong part.
     */
    const val SOURCE_MIN_KPA = 10.0

    val PASSING = setOf("Pass", "Good")

    fun verdict(peak: Double, pumpDead: Boolean): String {
        // A channel reading nothing is a supply fault whatever the source gauge
        // says — the gauge can look healthy while the air never arrives.
        if (pumpDead || peak < ZERO_KPA) return "Pump failed"
        if (peak > PASS_KPA) return "Pass"
        if (peak > GOOD_KPA) return "Good"
        if (peak > WEAK_KPA) return "Weak"
        return "Indenter failed"
    }
}

/** Severity of a status line, mapped to a colour by the UI. */
enum class Tone { Muted, Accent, Success, Danger }

data class QuickTestRow(
    val channel: Int,
    val label: String,
    val peak: Double? = null,
    val timeToPeak: Double? = null,
    val verdict: String? = null,
)

/** An immutable read of one glove, refreshed on the UI tick. */
data class GloveSnapshot(
    val hand: String,
    val address: String,
    val connected: Boolean,
    val connecting: Boolean,
    val status: String,
    val live: Boolean,
    val battery: String,
    val kpa: List<Double>,
    val source: Double,
    val absolutePa: Boolean,
    val mtu: Int?,
)

class HexrViewModel(app: Application) : AndroidViewModel(app) {

    val state = AppState()
    val engine = BleEngine(app, state) { /* snapshots refresh on the tick */ }

    // -- observable UI state -------------------------------------------------

    var gloves by mutableStateOf<Map<String, GloveSnapshot>>(emptyMap())
        private set

    val found = mutableStateListOf<Found>()

    var scanning by mutableStateOf(false)
        private set
    var scanStatus by mutableStateOf("Ready to scan")
        private set
    var scanTone by mutableStateOf(Tone.Muted)
        private set

    var permissionsGranted by mutableStateOf(engine.hasPermissions())

    // -- test screen settings ------------------------------------------------

    var channels by mutableStateOf(setOf(Protocol.Finger.Index.channel))
    var mode by mutableStateOf("pressure")          // "pressure" | "vibration"
    var intensity by mutableStateOf(0.6)
    var speed by mutableStateOf(1.0)
    var frequency by mutableStateOf(10.0)
    var peakRatio by mutableStateOf(0.5)
    var hands by mutableStateOf(setOf(LEFT, RIGHT))

    // -- quick test ----------------------------------------------------------

    var qtHand by mutableStateOf<String?>(null)
    var qtPhase by mutableStateOf("idle")           // idle | settle | drive | done
    var qtProgress by mutableStateOf(0f)
    var qtStatus by mutableStateOf("")
    var qtStatusTone by mutableStateOf(Tone.Muted)
    var qtSummary by mutableStateOf("")
    var qtSummaryTone by mutableStateOf(Tone.Muted)
    var qtRows by mutableStateOf(Protocol.ALL_FINGERS.map { QuickTestRow(it.channel, it.label) })
        private set

    private var qtT0 = 0L
    private val qtPeak = DoubleArray(6)
    private val qtTimeToPeak = DoubleArray(6)
    private var qtSourcePeak = 0.0

    init {
        viewModelScope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    // -- tick ----------------------------------------------------------------

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        gloves = HANDS.mapNotNull { hand ->
            val g = state.get(hand) ?: return@mapNotNull null
            hand to GloveSnapshot(
                hand = g.hand,
                address = g.address,
                connected = g.connected,
                connecting = g.connecting,
                status = g.status,
                live = g.isLive(now),
                battery = g.batteryLabel(),
                kpa = g.channelKpa(),
                source = g.sourceKpa(),
                absolutePa = g.absolutePa == true,
                mtu = g.mtu,
            )
        }.toMap()

        // Keep the quick test's hand pointed at something real.
        val chosen = qtHand
        if (chosen != null && gloves[chosen]?.connected != true) qtHand = null
        if (qtHand == null) qtHand = gloves.values.firstOrNull { it.connected }?.hand

        tickQuickTest(now)
    }

    // -- connect screen --------------------------------------------------------

    fun startScan() {
        if (scanning) {
            engine.stopScan()
            scanning = false
            scanStatus = "Stopped"
            scanTone = Tone.Muted
            return
        }
        found.clear()
        scanning = true
        scanStatus = "Listening for gloves…"
        scanTone = Tone.Muted
        engine.scan(
            onResult = { f ->
                viewModelScope.launch {
                    if (found.none { it.address == f.address }) found.add(f)
                    scanStatus = "Found ${found.size}"
                    scanTone = Tone.Success
                }
            },
            onDone = { error ->
                viewModelScope.launch {
                    scanning = false
                    when {
                        error != null -> {
                            scanStatus = "Bluetooth unavailable — $error"
                            scanTone = Tone.Danger
                        }

                        found.isEmpty() -> {
                            scanStatus = "Nothing found. Is the arm module switched on?"
                            scanTone = Tone.Muted
                        }

                        else -> {
                            scanStatus = "Found ${found.size}"
                            scanTone = Tone.Success
                        }
                    }
                }
            },
        )
    }

    fun connect(f: Found) = engine.connect(f.hand, f.address, f.name, f.rssi)

    fun disconnect(hand: String) = engine.disconnect(hand)

    // -- test screen -----------------------------------------------------------

    fun activeFingers(): List<Protocol.Finger> = channels.sorted().map { Protocol.Finger.of(it) }

    private fun frames(on: Boolean): ByteArray {
        val fingers = activeFingers()
        return if (mode == "vibration") {
            Protocol.batch(fingers.map { Protocol.vibration(it, on, frequency, intensity, peakRatio) })
        } else {
            Protocol.batch(fingers.map { Protocol.pressure(it, on, intensity, speed) })
        }
    }

    fun trigger() = engine.sendHands(connectedAmong(hands), frames(true))

    fun release() = engine.sendHands(connectedAmong(hands), frames(false))

    /** Vent everything, everywhere. Wired to the always-visible top-bar button. */
    fun allOff() {
        if (qtPhase == "settle" || qtPhase == "drive") abortQuickTest("Stopped")
        engine.sendAllOff()
    }

    private fun connectedAmong(wanted: Set<String>): List<String> =
        wanted.filter { gloves[it]?.connected == true }

    /**
     * Vent on the way to the background.
     *
     * A phone gets put in a pocket mid-test in a way a desktop window does not,
     * and a channel left inflated stays pressed against someone's hand until
     * the battery dies. Costing someone a re-trigger is the cheaper mistake.
     */
    fun onBackground() {
        if (qtPhase == "settle" || qtPhase == "drive") abortQuickTest("Stopped — app backgrounded")
        engine.sendAllOff()
        if (scanning) {
            engine.stopScan()
            scanning = false
        }
    }

    override fun onCleared() {
        engine.shutdown()
        super.onCleared()
    }

    // -- quick test ------------------------------------------------------------

    fun toggleQuickTest() {
        if (qtPhase == "settle" || qtPhase == "drive") abortQuickTest("Stopped") else startQuickTest()
    }

    private fun startQuickTest() {
        val hand = qtHand
        val snap = hand?.let { gloves[it] }
        if (snap == null || !snap.connected) {
            qtStatus = "Connect a glove first"
            qtStatusTone = Tone.Danger
            return
        }
        if (!snap.live) {
            qtStatus = "That glove is not sending telemetry — nothing to measure"
            qtStatusTone = Tone.Danger
            return
        }
        qtPeak.fill(0.0)
        qtTimeToPeak.fill(0.0)
        qtSourcePeak = 0.0
        qtRows = Protocol.ALL_FINGERS.map { QuickTestRow(it.channel, it.label) }
        qtSummary = ""
        engine.send(hand, Protocol.allOff())
        qtPhase = "settle"
        qtT0 = SystemClock.elapsedRealtime()
        qtProgress = 0f
        qtStatus = "Venting…"
        qtStatusTone = Tone.Muted
    }

    private fun abortQuickTest(why: String) {
        qtPhase = "idle"
        qtHand?.let { engine.send(it, Protocol.allOff()) }
        qtProgress = 0f
        qtStatus = why
        qtStatusTone = Tone.Muted
    }

    private fun tickQuickTest(now: Long) {
        if (qtPhase == "idle" || qtPhase == "done") return
        val hand = qtHand
        val snap = hand?.let { gloves[it] }
        if (hand == null || snap == null || !snap.connected) {
            abortQuickTest("Glove disconnected")
            return
        }
        val elapsed = (now - qtT0) / 1000.0

        if (qtPhase == "settle") {
            qtProgress = (elapsed / (QuickTest.SETTLE_MS / 1000.0)).coerceAtMost(1.0).toFloat()
            if (elapsed >= QuickTest.SETTLE_MS / 1000.0) {
                // Everything at once, exactly as the desktop test does — it also
                // exercises whether the pump can keep up with six channels.
                engine.send(
                    hand,
                    Protocol.batch(Protocol.ALL_FINGERS.map { Protocol.pressure(it, true, 1.0, 1.0) }),
                )
                qtPhase = "drive"
                qtT0 = now
                qtStatus = "Driving all channels…"
                qtStatusTone = Tone.Accent
            }
            return
        }

        // drive
        qtProgress = (elapsed / (QuickTest.DRIVE_MS / 1000.0)).coerceAtMost(1.0).toFloat()
        qtSourcePeak = maxOf(qtSourcePeak, snap.source)
        for (f in Protocol.ALL_FINGERS) {
            val kpa = snap.kpa.getOrElse(f.channel) { 0.0 }
            if (kpa > qtPeak[f.channel]) {
                qtPeak[f.channel] = kpa
                qtTimeToPeak[f.channel] = elapsed
            }
        }
        if (elapsed >= QuickTest.DRIVE_MS / 1000.0) finishQuickTest(hand)
    }

    private fun finishQuickTest(hand: String) {
        engine.send(hand, Protocol.allOff())
        qtPhase = "done"
        qtProgress = 1f

        val pumpDead = qtSourcePeak < QuickTest.SOURCE_MIN_KPA
        var failures = 0
        var starved = 0        // channels that read nothing at all

        qtRows = Protocol.ALL_FINGERS.map { f ->
            val peak = qtPeak[f.channel]
            val verdict = QuickTest.verdict(peak, pumpDead)
            if (verdict !in QuickTest.PASSING) failures++
            if (verdict == "Pump failed") starved++
            QuickTestRow(
                channel = f.channel,
                label = f.label,
                peak = peak,
                timeToPeak = if (peak > 0) qtTimeToPeak[f.channel] else null,
                verdict = verdict,
            )
        }

        val source = "%.1f".format(qtSourcePeak)
        when {
            pumpDead -> {
                qtStatus = "Pump failed"
                qtStatusTone = Tone.Danger
                qtSummary = "The source never rose above $source kPa, so no channel could have " +
                    "reached pressure. This is one fault in the pump or its supply, not six " +
                    "failed indenters."
                qtSummaryTone = Tone.Danger
            }

            starved > 0 -> {
                // Source gauge looks healthy but the air is not arriving, so the
                // fault is upstream of the indenters even though only some
                // channels show it — a blocked line or a valve that never opened.
                qtStatus = "$failures of 6 channels failed"
                qtStatusTone = Tone.Danger
                qtSummary = "Source reached $source kPa, but $starved of 6 channels read nothing " +
                    "at all. Those are getting no air — check the supply line and valves before " +
                    "the indenters."
                qtSummaryTone = Tone.Danger
            }

            failures > 0 -> {
                qtStatus = "$failures of 6 channels failed"
                qtStatusTone = Tone.Danger
                qtSummary = "Source reached $source kPa, so supply is fine — the failing channels " +
                    "are indenter or valve faults."
                qtSummaryTone = Tone.Muted
            }

            else -> {
                qtStatus = "All 6 channels passed"
                qtStatusTone = Tone.Success
                qtSummary = "Source reached $source kPa."
                qtSummaryTone = Tone.Muted
            }
        }
    }
}
