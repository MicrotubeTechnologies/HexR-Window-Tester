package com.microtube.hexr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/**
 * Screen 01 — find gloves and connect them.
 *
 * A kit is a left glove and a right glove, and they are independent BLE
 * peripherals. Both can be connected at once and the rest of the app treats
 * them separately throughout, so this is a list you connect from rather than a
 * chooser you pick one out of.
 */
@Composable
fun ConnectScreen(vm: HexrViewModel, onRequestPermissions: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(T.PAGE_PAD),
    ) {
        Heading("Connect a HEXR glove")
        Caption(
            "Power on the arm module and press scan. Left and right are separate " +
                "devices — connect either, or both.",
            Modifier.padding(top = 4.dp),
            T.TEXT_MUTED,
        )
        VSpace(16)

        if (!vm.permissionsGranted) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Heading("Bluetooth permission needed", size = 15)
                    Caption(
                        "Android will not let the app see a glove until you allow it to find " +
                            "and connect to nearby devices.",
                        Modifier.padding(top = 6.dp),
                        T.TEXT_MUTED,
                    )
                    VSpace(12)
                    PrimaryButton("Allow Bluetooth", onClick = onRequestPermissions)
                }
            }
            VSpace(16)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                if (vm.scanning) "Stop" else "Scan for gloves",
                enabled = vm.permissionsGranted,
                onClick = vm::startScan,
            )
            HSpace(12)
            Caption(vm.scanStatus, Modifier.weight(1f), vm.scanTone.color())
        }
        VSpace(16)

        Panel(Modifier.fillMaxWidth()) {
            Column {
                TableRow {
                    SectionLabel("Device", Modifier.weight(1f))
                    SectionLabel("Hand")
                }
                Divider()
                if (vm.found.isEmpty()) {
                    EmptyNote("No gloves yet. Press scan with the arm module switched on.")
                } else {
                    vm.found.forEach { f ->
                        val g = vm.gloves[f.hand]
                        val already = g != null && (g.connected || g.connecting)
                        TableRow {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(T.SUCCESS)
                                    HSpace(8)
                                    Heading(f.name, size = 14)
                                }
                                MonoText(
                                    f.address + (f.rssi?.let { "   $it dBm" } ?: ""),
                                    Modifier.padding(top = 3.dp),
                                    T.TEXT_FAINT,
                                    11,
                                )
                            }
                            HSpace(10)
                            GhostButton(
                                if (already) "Connected" else "Connect",
                                enabled = !already,
                                onClick = { vm.connect(f) },
                            )
                        }
                        Divider()
                    }
                }
            }
        }

        val cards = HANDS.mapNotNull { vm.gloves[it] }.filter { it.connected || it.connecting }
        if (cards.isNotEmpty()) {
            VSpace(20)
            SectionLabel("Connected")
            VSpace(8)
            cards.forEach { g ->
                Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(
                                when {
                                    g.connecting -> T.ACCENT
                                    g.connected -> T.SUCCESS
                                    else -> T.DANGER
                                },
                            )
                            HSpace(9)
                            Heading("${g.hand} glove", size = 15)
                        }
                        VSpace(6)
                        val (label, tone) = when {
                            g.connecting -> "Connecting…" to T.TEXT_MUTED
                            g.live -> "Connected · streaming" to T.SUCCESS
                            // Connected and usable — it simply is not reporting
                            // pressure. Flagged quietly, not as a fault: haptics
                            // work either way.
                            g.connected -> "Connected" to T.SUCCESS
                            else -> g.status to T.TEXT_MUTED
                        }
                        Caption(label, color = tone)
                        VSpace(6)
                        MonoText(
                            "battery ${g.battery}   ${g.address}" +
                                (g.mtu?.let { "   MTU $it" } ?: ""),
                            color = T.TEXT_2,
                            size = 11,
                        )
                        if (g.mtu != null && g.mtu < SMALL_MTU) {
                            Caption(
                                "This glove kept a small MTU, so six-channel commands go out " +
                                    "as several writes instead of one.",
                                Modifier.padding(top = 6.dp),
                            )
                        }
                        VSpace(12)
                        GhostButton(
                            "Disconnect",
                            tint = T.DANGER,
                            onClick = { vm.disconnect(g.hand) },
                        )
                    }
                }
            }
        }
        VSpace(24)
    }
}

/**
 * Below this, a 108-byte six-channel batch does not fit in one write and the
 * engine splits it. Worth surfacing: it changes how simultaneous the six
 * channels really are, which is exactly what the quick test measures.
 */
private const val SMALL_MTU = 111
