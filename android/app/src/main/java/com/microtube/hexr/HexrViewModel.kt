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
 * The frequency slider's ceiling.
 *
 * The protocol accepts up to 40 Hz, but the design caps the control at 20:
 * above that the channel is a buzz rather than a distinguishable pulse, and the
 * extra travel costs precision across the range people actually use.
 */
const val MAX_FREQUENCY_HZ = 20

private fun other(hand: String) = if (hand == LEFT) RIGHT else LEFT

/**
 * The canned QA sweep, ported from `hexr/screens/quicktest.py`.
 *
 * The thresholds here must match the desktop tester's, or the same glove passes
 * on one and fails on the other. They were last retuned in desktop v0.2.0.
 */
object QuickTest {
    const val SETTLE_MS = 600L      // vent and let the channels fall back
    const val DRIVE_MS = 2_000L     // how long each channel is held at full

    /**
     * Three words, because three is what a decision needs: the channel is doing
     * its job, it is doing some of it, or it is not doing it.
     *
     * Sensors on a dead channel float on noise rather than reading a clean 0.0,
     * which is why the failure line sits at 2 kPa and not at zero.
     */
    const val GOOD_KPA = 40.0       // above this the channel delivers full pressure
    const val FAIL_KPA = 2.0        // below this it is not working at all

    /**
     * If the source/tank never develops pressure, every channel reads Fail for
     * one reason — a dead pump — and reporting six dead channels would send
     * someone replacing the wrong parts. This does not change any channel's
     * verdict; it changes what the summary tells you to go and look at.
     */
    const val SOURCE_MIN_KPA = 10.0

    val PASSING = setOf("Good")

    /**
     * Good / Poor / Fail, from the peak this channel reached.
     *
     * The source reading does not enter into it. A channel is judged on what it
     * delivered; whether a healthy pump or a dead one is the reason sits in the
     * summary, where it can be said once instead of six times.
     */
    fun verdict(peak: Double): String = when {
        peak > GOOD_KPA -> "Good"
        peak >= FAIL_KPA -> "Poor"
        else -> "Fail"
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

    /** Whether the phone's Bluetooth radio is on. Polled; the user can flip it. */
    var bluetoothOn by mutableStateOf(engine.bluetoothEnabled())
        private set

    /**
     * True once Android will no longer show the permission dialog — the user
     * has denied it twice, or picked "Don't allow" on a phone that treats one
     * refusal as final. The app cannot prompt again from here, so the Connect
     * screen has to send them to Settings instead of offering a button that
     * silently does nothing.
     */
    var mustUseSettings by mutableStateOf(false)

    /** Everything that has to be true before a scan can find anything. */
    fun scanBlocker(): String? = when {
        !permissionsGranted -> "permission"
        !bluetoothOn -> "bluetooth"
        else -> null
    }

    // -- test screen settings ------------------------------------------------

    var channels by mutableStateOf(setOf(Protocol.Finger.Index.channel))

    /**
     * 0-100. Maps onto the protocol's 0.1-1.0 window; exactly 0 means off.
     *
     * Written through a custom setter so a change lands on a channel that is
     * already driving. Pushing the new value only on the next Trigger would let
     * the slider and the glove disagree about what is happening right now.
     */
    private var intensityState by mutableStateOf(60)
    var intensity: Int
        get() = intensityState
        set(value) {
            intensityState = value.coerceIn(0, 100)
            if (driving) engine.send(hand, frames(true))
        }

    /**
     * 0-20 Hz, where **0 means steady pressure, not silence**.
     *
     * This replaced a separate Pressure/Vibration mode picker, and it is a
     * better fit for the hardware than the picker was: the firmware's own
     * distinction is a frequency of zero versus a frequency above it, so the
     * one control now says the same thing the wire does.
     */
    private var frequencyState by mutableStateOf(0)
    var frequency: Int
        get() = frequencyState
        set(value) {
            frequencyState = value.coerceIn(0, MAX_FREQUENCY_HZ)
            if (frequencyState > 0) lastFrequency = frequencyState
            if (driving) engine.send(hand, frames(true))
        }

    /** What the frequency toggle restores when switched back on. */
    private var lastFrequency = 6

    /** Which glove the Test tab drives. One at a time, as the diagram shows one. */
    var hand by mutableStateOf(LEFT)

    var driving by mutableStateOf(false)
        private set

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

        // Re-read the grant until it lands. A permission dialog pauses the
        // activity without stopping it, so onStart does not fire on the way
        // back and the launcher callback was the only thing updating this —
        // leaving the Connect screen asking for a permission the user had
        // already given. Checked only while ungranted, so it costs nothing
        // once it is; a later revocation restarts the process anyway.
        if (!permissionsGranted && engine.hasPermissions()) permissionsGranted = true
        bluetoothOn = engine.bluetoothEnabled()

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

        // A glove that goes away mid-drive must clear the flag, or the button
        // keeps claiming to drive something that is no longer there.
        if (driving && !handConnected()) driving = false
        if (!handConnected() && handConnected(other(hand))) hand = other(hand)

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

    /**
     * Frequency decides the opcode, not a mode flag: zero is a steady pressure
     * target, anything above it is the vibration path.
     */
    private fun frames(on: Boolean): ByteArray {
        val level = intensity / 100.0
        return Protocol.batch(
            activeFingers().map { f ->
                if (frequency > 0) {
                    Protocol.vibration(f, on, frequency.toDouble(), level)
                } else {
                    Protocol.pressure(f, on, level, 1.0)
                }
            },
        )
    }

    fun toggleChannel(channel: Int) {
        channels = if (channel in channels) channels - channel else channels + channel
        // Deselecting the last channel leaves nothing to drive, so the drive
        // must end with it rather than silently continuing on stale channels.
        if (channels.isEmpty() && driving) release()
        if (driving) engine.send(hand, frames(true))
    }

    fun selectAll() {
        channels = Protocol.ALL_FINGERS.map { it.channel }.toSet()
        if (driving) engine.send(hand, frames(true))
    }

    fun selectNone() {
        channels = emptySet()
        if (driving) release()
    }

    fun pickHand(which: String) {
        if (which == hand) return
        // Never leave the hand you are walking away from inflated.
        if (driving) release()
        hand = which
    }

    fun handConnected(which: String = hand): Boolean = gloves[which]?.connected == true

    fun canDrive(): Boolean = channels.isNotEmpty() && handConnected()

    fun toggleFrequency() {
        frequency = if (frequency == 0) lastFrequency else 0
    }

    /** Start output on the selected channels of the selected glove. */
    fun trigger() {
        if (!canDrive()) return
        driving = true
        engine.send(hand, frames(true))
    }

    /**
     * Stop output, whatever is selected.
     *
     * Deliberately unconditional and deliberately every channel: a channel
     * deselected mid-drive is still inflated, and a targeted release would
     * leave it that way.
     */
    fun release() {
        driving = false
        engine.sendAllOff()
    }

    /** Vent everything, everywhere. */
    fun allOff() {
        if (qtPhase == "settle" || qtPhase == "drive") abortQuickTest("Stopped")
        driving = false
        engine.sendAllOff()
    }

    /**
     * Vent on the way to the background.
     *
     * A phone gets put in a pocket mid-test in a way a desktop window does not,
     * and a channel left inflated stays pressed against someone's hand until
     * the battery dies. Costing someone a re-trigger is the cheaper mistake.
     */
    fun onBackground() {
        if (qtPhase == "settle" || qtPhase == "drive") abortQuickTest("Stopped — app backgrounded")
        driving = false
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
        var good = 0
        var poor = 0
        var failed = 0

        qtRows = Protocol.ALL_FINGERS.map { f ->
            val peak = qtPeak[f.channel]
            val verdict = QuickTest.verdict(peak)
            when (verdict) {
                "Good" -> good++
                "Poor" -> poor++
                else -> failed++
            }
            QuickTestRow(
                channel = f.channel,
                label = f.label,
                peak = peak,
                timeToPeak = if (peak > 0) qtTimeToPeak[f.channel] else null,
                verdict = verdict,
            )
        }

        val breakdown = "$good good, $poor poor, $failed failed"
        val source = "Source reached %.1f kPa".format(qtSourcePeak)

        when {
            pumpDead -> {
                qtStatus = "Pump failed"
                qtStatusTone = Tone.Danger
                qtSummary = "The source never rose above %.1f kPa, so no channel could have "
                    .format(qtSourcePeak) +
                    "reached pressure. This is one fault in the pump or its supply, not six " +
                    "failed channels."
                qtSummaryTone = Tone.Danger
            }

            failed > 0 -> {
                qtStatus = "$failed of 6 channels failed"
                qtStatusTone = Tone.Danger
                qtSummary = "$source, so supply is fine — a channel reading nothing with a " +
                    "healthy source is a blocked line, a valve that never opened, or a dead " +
                    "indenter. $breakdown."
                qtSummaryTone = Tone.Danger
            }

            poor > 0 -> {
                qtStatus = "$poor of 6 channels poor"
                qtStatusTone = Tone.Accent
                qtSummary = "$source. Every channel moved, but not all of them reached full " +
                    "pressure — check the tubing on the weak ones. $breakdown."
                qtSummaryTone = Tone.Muted
            }

            else -> {
                qtStatus = "All 6 channels good"
                qtStatusTone = Tone.Success
                qtSummary = "$source."
                qtSummaryTone = Tone.Muted
            }
        }
    }

}
