package com.microtube.hexr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
 */
@Composable
fun ConnectScreen(vm: HexrViewModel, onRequestPermissions: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(T.PAGE_PAD),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Title("Connect a HEXR glove")
            Caption(
                "Power on the arm module and press scan. Left and right are separate " +
                    "devices — connect either, or both.",
                Modifier.padding(top = 5.dp),
                T.TEXT_2,
            )
        }

        if (!vm.permissionsGranted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Label("Bluetooth permission needed", weight = FontWeight.SemiBold)
                    Caption(
                        "Android will not let the app see a glove until you allow it to find " +
                            "and connect to nearby devices.",
                        Modifier.padding(top = 6.dp),
                        T.TEXT_2,
                    )
                    VSpace(14)
                    PrimaryButton(
                        "Allow Bluetooth",
                        Modifier.fillMaxWidth(),
                        onClick = onRequestPermissions,
                    )
                }
            }
        }

        Column {
            PrimaryButton(
                if (vm.scanning) "Scanning…" else "Scan for gloves",
                Modifier.fillMaxWidth(),
                enabled = vm.permissionsGranted && !vm.scanning,
                onClick = vm::startScan,
            )
            Caption(vm.scanStatus, Modifier.padding(top = 10.dp), vm.scanTone.color())
        }

        if (vm.found.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.found.forEach { f ->
                    val g = vm.gloves[f.hand]
                    val busy = g != null && (g.connected || g.connecting)
                    Card(
                        Modifier.fillMaxWidth(),
                        fill = T.CARD,
                        border = T.BORDER_SOFT,
                        radius = T.R_CARD,
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { vm.connect(f) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Dot(if (busy) T.TEXT_FAINT else T.GREEN, size = 7)
                            Column(Modifier.weight(1f)) {
                                Label(f.name, size = Size.BODY, weight = FontWeight.SemiBold)
                                MonoText(
                                    f.address + (f.rssi?.let { " · $it dBm" } ?: ""),
                                    Modifier.padding(top = 2.dp),
                                    T.TEXT_5,
                                    Size.LABEL,
                                )
                            }
                            Pill(f.hand)
                            if (!busy) {
                                Label(
                                    "Connect",
                                    color = T.ACCENT,
                                    size = Size.BODY_2,
                                    weight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        val cards = HANDS.mapNotNull { vm.gloves[it] }.filter { it.connected || it.connecting }
        if (cards.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Connected")
                cards.forEach { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Label(
                                    "${g.hand} glove",
                                    Modifier.weight(1f),
                                    size = Size.BODY,
                                    weight = FontWeight.SemiBold,
                                )
                                when {
                                    g.connecting -> Pill("connecting", color = T.TEXT_2)
                                    g.live -> Pill(
                                        "streaming",
                                        color = T.GREEN,
                                        fill = T.GREEN.copy(alpha = 0.12f),
                                        border = T.GREEN.copy(alpha = 0.35f),
                                    )
                                    // Connected and usable — it simply is not
                                    // reporting pressure. Flagged quietly, not
                                    // as a fault: haptics work either way.
                                    g.connected -> Pill("connected", color = T.GREEN)
                                    else -> Pill(g.status, color = T.TEXT_2)
                                }
                            }

                            VSpace(12)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile("Battery", g.battery, Modifier.weight(1f))
                                StatTile(
                                    "Source kPa",
                                    if (g.live) "%.1f".format(g.source) else "—",
                                    Modifier.weight(1f),
                                )
                            }

                            VSpace(10)
                            MonoText(
                                g.address + (g.mtu?.let { "   MTU $it" } ?: ""),
                                color = T.TEXT_5,
                                size = Size.LABEL,
                            )
                            if (g.mtu != null && g.mtu < SMALL_MTU) {
                                Caption(
                                    "This glove kept a small MTU, so six-channel commands go " +
                                        "out as several writes instead of one.",
                                    Modifier.padding(top = 8.dp),
                                )
                            }

                            VSpace(14)
                            GhostButton(
                                "Disconnect",
                                Modifier.fillMaxWidth(),
                                tint = T.RED,
                                onClick = { vm.disconnect(g.hand) },
                            )
                        }
                    }
                }
            }
        }

        if (vm.found.isEmpty() && cards.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyNote("No gloves yet. Press scan with the arm module switched on.")
            }
        }

        VSpace(8)
    }
}

/**
 * Below this, a 108-byte six-channel batch does not fit in one write and the
 * engine splits it. Worth surfacing: it changes how simultaneous the six
 * channels really are, which is exactly what the quick test measures.
 */
private const val SMALL_MTU = 111
