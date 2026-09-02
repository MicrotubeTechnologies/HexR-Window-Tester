package com.microtube.hexr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * The desktop puts control on the left and truth on the right. A phone has one
 * column, so the order becomes control then truth: what you asked for, above
 * what the hardware actually did. Testing a haptic device without the pressure
 * readout next to the controls means guessing whether a channel is weak or your
 * finger is just bad at telling 30 kPa from 40.
 */

/**
 * The meters are scaled to the firmware's own ceiling, not to the highest value
 * seen, so a weak channel looks weak instead of being normalised into looking
 * fine.
 */
private const val METER_FULL_KPA = 60.0

@Composable
fun TestScreen(vm: HexrViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(T.PAGE_PAD),
    ) {
        Heading("Test the haptics")
        Caption(
            "Pick channels, set the strength, and trigger. Release vents them again — " +
                "nothing switches itself off.",
            Modifier.padding(top = 4.dp),
            T.TEXT_MUTED,
        )
        VSpace(18)

        // -- hand ------------------------------------------------------------
        SectionLabel("Hand")
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HANDS.forEach { hand ->
                val g = vm.gloves[hand]
                val live = g?.connected == true
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

        // -- channels ----------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Channels", Modifier.weight(1f))
            GhostButton("All", onClick = {
                vm.channels = Protocol.ALL_FINGERS.map { it.channel }.toSet()
            })
            HSpace(8)
            GhostButton("None", tint = T.TEXT_2, onClick = { vm.channels = emptySet() })
        }
        VSpace(8)
        Protocol.ALL_FINGERS.chunked(3).forEach { rowFingers ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowFingers.forEach { f ->
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
                // Keep the last row's tiles the same width as the rows above.
                repeat(3 - rowFingers.size) { Spacer3() }
            }
        }
        VSpace(10)

        // -- mode --------------------------------------------------------------
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

        // -- parameters ---------------------------------------------------------
        val vibrating = vm.mode == "vibration"
        LabelledSlider(
            "Intensity",
            vm.intensity,
            "%.2f  ·  %.0f kPa".format(vm.intensity, Protocol.intensityToKpa(vm.intensity)),
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
            // The firmware switches technique at 5 Hz, and the two feel nothing
            // alike. Saying which one is running turns a confusing result into
            // an expected one.
            Caption(
                if (vm.frequency < Protocol.PWM_VIBRATION_HZ) {
                    "Under ${Protocol.PWM_VIBRATION_HZ.toInt()} Hz the glove vibrates " +
                        "pneumatically — the pressure target is pulsed."
                } else {
                    "At ${Protocol.PWM_VIBRATION_HZ.toInt()} Hz and above the pressure loop " +
                        "is off and the channel's motor is driven directly."
                },
            )
        } else {
            LabelledSlider(
                "Ramp speed", vm.speed, "%.2f".format(vm.speed),
                0.1f..1.0f, { vm.speed = it },
            )
        }
        VSpace(14)

        // -- actions -------------------------------------------------------------
        val ready = vm.channels.isNotEmpty() && vm.hands.any { vm.gloves[it]?.connected == true }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Trigger", Modifier.weight(1f), enabled = ready, onClick = vm::trigger)
            GhostButton("Release", Modifier.weight(1f), enabled = ready, onClick = vm::release)
        }
        if (!ready) {
            VSpace(8)
            Caption(
                if (vm.channels.isEmpty()) {
                    "Pick at least one channel."
                } else {
                    "No connected glove selected — connect one, or select a hand above."
                },
                color = T.TEXT_MUTED,
            )
        }
        VSpace(24)

        // -- live monitor ----------------------------------------------------------
        SectionLabel("Live from the glove")
        VSpace(6)

        // One monitor, so show a hand that is actually selected where possible.
        val connected = HANDS.mapNotNull { vm.gloves[it] }.filter { it.connected }
        val g = connected.firstOrNull { it.hand in vm.hands } ?: connected.firstOrNull()

        Caption(
            when {
                g == null -> "No glove connected"
                g.live -> "${g.hand} glove"
                else -> "${g.hand} glove  ·  no telemetry"
            },
            color = T.TEXT_MUTED,
        )
        VSpace(10)

        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Protocol.ALL_FINGERS.forEach { f ->
                    val kpa = g?.kpa?.getOrNull(f.channel)
                    TableRow {
                        Label(f.label, Modifier.width(64.dp))
                        Meter(
                            fraction = ((kpa ?: 0.0) / METER_FULL_KPA).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            active = g != null,
                        )
                        MonoText(
                            if (kpa == null) "—" else "%.1f kPa".format(kpa),
                            color = T.TEXT,
                            size = 12,
                        )
                    }
                }
            }
        }
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readout("Battery", g?.battery ?: "—", Modifier.weight(1f))
            Readout(
                "Source",
                if (g == null) "—" else "%.1f kPa".format(g.source),
                Modifier.weight(1f),
            )
        }
        if (g?.absolutePa == true) {
            VSpace(10)
            Caption("This glove reports absolute pressure; readings are converted to gauge kPa.")
        }
        VSpace(24)
    }
}

/** Placeholder that keeps a short final row aligned with the full rows above. */
@Composable
private fun RowScope.Spacer3() = Spacer(Modifier.weight(1f))
