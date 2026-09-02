package com.microtube.hexr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
 *
 * Each result is one tap target across its full width. A phone-sized Connect
 * button beside a phone-sized row is a smaller thing to hit than the row it
 * sits in, for no gain.
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
            Modifier.padding(top = 5.dp),
            T.TEXT_MUTED,
        )
        VSpace(18)

        if (!vm.permissionsGranted) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Heading("Bluetooth permission needed", size = Size.SUBTITLE)
                    Caption(
                        "Android will not let the app see a glove until you allow it to find " +
                            "and connect to nearby devices.",
                        Modifier.padding(top = 6.dp),
                        T.TEXT_MUTED,
                    )
                    VSpace(14)
                    PrimaryButton(
                        "Allow Bluetooth",
                        Modifier.fillMaxWidth(),
                        onClick = onRequestPermissions,
                    )
                }
            }
            VSpace(16)
        }

        PrimaryButton(
            if (vm.scanning) "Stop scanning" else "Scan for gloves",
            Modifier.fillMaxWidth(),
            enabled = vm.permissionsGranted,
            onClick = vm::startScan,
        )
        VSpace(10)
        Caption(vm.scanStatus, color = vm.scanTone.color())
        VSpace(16)

        Panel(Modifier.fillMaxWidth()) {
            Column {
                if (vm.found.isEmpty()) {
                    EmptyNote("No gloves yet. Press scan with the arm module switched on.")
                } else {
                    vm.found.forEachIndexed { i, f ->
                        val g = vm.gloves[f.hand]
                        val busy = g != null && (g.connected || g.connecting)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { vm.connect(f) }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(if (busy) T.DOT_OFF else T.SUCCESS)
                            HSpace(11)
                            Column(Modifier.weight(1f)) {
                                Label(f.name, color = T.TEXT, weight = FontWeight.Medium)
                                MonoText(
                                    f.address + (f.rssi?.let { "  ·  $it dBm" } ?: ""),
                                    Modifier.padding(top = 3.dp),
                                    T.TEXT_FAINT,
                                    Size.MICRO,
                                )
                            }
                            HSpace(10)
                            Chip(f.hand, color = T.TEXT_2)
                            HSpace(8)
                            Label(
                                if (busy) "" else "Connect",
                                color = T.ACCENT,
                                size = Size.CAPTION.toFloat(),
                                weight = FontWeight.SemiBold,
                            )
                        }
                        if (i < vm.found.lastIndex) Divider()
                    }
                }
            }
        }

        val cards = HANDS.mapNotNull { vm.gloves[it] }.filter { it.connected || it.connecting }
        if (cards.isNotEmpty()) {
            VSpace(22)
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
                                size = 10,
                            )
                            HSpace(10)
                            Heading("${g.hand} glove", size = Size.SUBTITLE)
                            HSpace(10)
                            val (label, tone) = when {
                                g.connecting -> "Connecting…" to T.TEXT_MUTED
                                g.live -> "streaming" to T.SUCCESS
                                // Connected and usable — it simply is not
                                // reporting pressure. Flagged quietly, not as a
                                // fault: haptics work either way.
                                g.connected -> "connected" to T.SUCCESS
                                else -> g.status to T.TEXT_MUTED
                            }
                            Chip(label, color = tone)
                        }
                        VSpace(12)
                        Row {
                            Readout("Battery", g.battery, Modifier.weight(1f))
                            HSpace(8)
                            Readout(
                                "Source",
                                if (g.live) "%.1f".format(g.source) else "—",
                                Modifier.weight(1f),
                            )
                        }
                        VSpace(10)
                        MonoText(
                            g.address + (g.mtu?.let { "   MTU $it" } ?: ""),
                            color = T.TEXT_FAINT,
                            size = Size.MICRO,
                        )
                        if (g.mtu != null && g.mtu < SMALL_MTU) {
                            Caption(
                                "This glove kept a small MTU, so six-channel commands go out " +
                                    "as several writes instead of one.",
                                Modifier.padding(top = 8.dp),
                            )
                        }
                        VSpace(14)
                        GhostButton(
                            "Disconnect",
                            Modifier.fillMaxWidth(),
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
