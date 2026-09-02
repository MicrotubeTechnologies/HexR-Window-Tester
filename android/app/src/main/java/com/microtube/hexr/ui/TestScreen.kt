package com.microtube.hexr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.microtube.hexr.HANDS
import com.microtube.hexr.HexrViewModel
import com.microtube.hexr.Protocol

/**
 * Screen 02 — drive channels by hand and watch what the glove reports back.
 *
 * The desktop puts control on the left and truth on the right, side by side, so
 * you can see what a channel did while you are driving it. A phone has one
 * column, and stacking them would push the readout below the fold at exactly
 * the moment it matters.
 *
 * So the screen is split instead of stacked: settings scroll in the upper half,
 * and the instrument panel — the six-channel manifold and the drive pad — is
 * pinned to the bottom, in the thumb's reach and always visible. You never
 * drive a channel you cannot see.
 */

/**
 * The manifold is scaled to the firmware's own ceiling, not to the highest value
 * seen, so a weak channel looks weak instead of being normalised into looking
 * fine.
 */
private const val METER_FULL_KPA = 60.0

/** Initials, because six full finger names will not fit across a phone. */
private val SHORT_LABELS = listOf("Th", "In", "Mi", "Ri", "Pi", "Pa")

@Composable
fun TestScreen(vm: HexrViewModel) {
    // One monitor, so show a hand that is actually selected where possible.
    val connected = HANDS.mapNotNull { vm.gloves[it] }.filter { it.connected }
    val g = connected.firstOrNull { it.hand in vm.hands } ?: connected.firstOrNull()
    val ready = vm.canDrive()

    Column(Modifier.fillMaxSize()) {

        // -- scrolling settings ------------------------------------------------
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = T.PAGE_PAD)
                .padding(top = T.PAGE_PAD),
        ) {
            SectionLabel("Hand")
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HANDS.forEach { hand ->
                    val glove = vm.gloves[hand]
                    val live = glove?.connected == true
                    Cell(
                        label = hand,
                        sub = if (live) "connected" else "not connected",
                        selected = live && hand in vm.hands,
                        enabled = live,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.hands = if (hand in vm.hands) vm.hands - hand else vm.hands + hand
                        },
                    )
                }
            }
            VSpace(18)

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Channels", Modifier.weight(1f))
                GhostButton("All", compact = true, onClick = {
                    vm.channels = Protocol.ALL_FINGERS.map { it.channel }.toSet()
                })
                HSpace(8)
                GhostButton("None", tint = T.TEXT_2, compact = true, onClick = {
                    vm.channels = emptySet()
                })
            }
            VSpace(8)
            Protocol.ALL_FINGERS.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { f ->
                        Cell(
                            label = f.label,
                            selected = f.channel in vm.channels,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                vm.channels = if (f.channel in vm.channels) {
                                    vm.channels - f.channel
                                } else {
                                    vm.channels + f.channel
                                }
                            },
                        )
                    }
                }
            }
            VSpace(10)

            SectionLabel("Mode")
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "pressure" to ("Pressure" to "steady squeeze"),
                    "vibration" to ("Vibration" to "oscillating"),
                ).forEach { (key, text) ->
                    Cell(
                        label = text.first,
                        sub = text.second,
                        selected = vm.mode == key,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.mode = key },
                    )
                }
            }
            VSpace(18)

            val vibrating = vm.mode == "vibration"
            LabelledSlider(
                "Intensity",
                vm.intensity,
                "%.2f · %.0f kPa".format(vm.intensity, Protocol.intensityToKpa(vm.intensity)),
                0.1f..1.0f,
                { vm.intensity = it },
            )
            if (vibrating) {
                LabelledSlider(
                    "Frequency", vm.frequency, "%.1f Hz".format(vm.frequency),
                    0.1f..40.0f, { vm.frequency = it },
                )
                LabelledSlider(
                    "Peak ratio", vm.peakRatio, "%.2f".format(vm.peakRatio),
                    0.2f..0.8f, { vm.peakRatio = it },
                )
                // The firmware switches technique at 5 Hz, and the two feel
                // nothing alike. Saying which one is running turns a confusing
                // result into an expected one.
                Caption(
                    if (vm.frequency < Protocol.PWM_VIBRATION_HZ) {
                        "Under ${Protocol.PWM_VIBRATION_HZ.toInt()} Hz the glove vibrates " +
                            "pneumatically — the pressure target is pulsed."
                    } else {
                        "At ${Protocol.PWM_VIBRATION_HZ.toInt()} Hz and above the pressure " +
                            "loop is off and the channel's motor is driven directly."
                    },
                )
            } else {
                LabelledSlider(
                    "Ramp speed", vm.speed, "%.2f".format(vm.speed),
                    0.1f..1.0f, { vm.speed = it },
                )
            }

            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Readout("Battery", g?.battery ?: "—", Modifier.weight(1f))
                Readout(
                    "Source",
                    if (g == null) "—" else "%.1f".format(g.source),
                    Modifier.weight(1f),
                )
            }
            if (g?.absolutePa == true) {
                VSpace(10)
                Caption(
                    "This glove reports absolute pressure; readings are converted to gauge kPa.",
                )
            }
            VSpace(18)
        }

        // -- pinned instrument panel ---------------------------------------------
        Column(
            Modifier
                .fillMaxWidth()
                .background(T.RAISED)
                .padding(horizontal = T.PAGE_PAD, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    when {
                        g == null -> "No glove connected"
                        g.live -> "${g.hand} glove · live"
                        else -> "${g.hand} glove · no telemetry"
                    },
                    Modifier.weight(1f),
                )
                MonoText("kPa", color = T.TEXT_FAINT, size = Size.MICRO)
            }
            VSpace(10)

            Manifold(
                values = Protocol.ALL_FINGERS.map { f -> g?.kpa?.getOrNull(f.channel) },
                labels = SHORT_LABELS,
                selected = vm.channels,
                fullScale = METER_FULL_KPA,
            )
            VSpace(14)

            Segmented(
                options = listOf("hold" to "Hold to drive", "latch" to "Latch on"),
                selected = vm.driveMode,
                onPick = {
                    // Switching mode mid-drive would strand a latched channel
                    // with no pad state left to release it.
                    if (vm.driving) vm.stopDrive()
                    vm.driveMode = it
                },
            )
            VSpace(10)

            DrivePad(
                driving = vm.driving,
                enabled = ready,
                latched = vm.driveMode == "latch",
                summary = driveSummary(vm, ready),
                onStart = vm::startDrive,
                onStop = vm::stopDrive,
            )
        }
    }
}

/** Says exactly what the pad will do, so nobody has to scroll up to check. */
private fun driveSummary(vm: HexrViewModel, ready: Boolean): String {
    if (vm.channels.isEmpty()) return "Pick a channel above"
    if (!ready) return "Connect a glove, or select a hand above"
    val n = vm.channels.size
    val hands = vm.hands.filter { vm.gloves[it]?.connected == true }
    val where = if (hands.size == 2) "both hands" else hands.firstOrNull()?.lowercase() ?: ""
    val what = if (vm.mode == "vibration") {
        "%.0f Hz · %.0f kPa".format(vm.frequency, Protocol.intensityToKpa(vm.intensity))
    } else {
        "%.0f kPa".format(Protocol.intensityToKpa(vm.intensity))
    }
    return "$n channel${if (n == 1) "" else "s"} · $where · $what"
}
