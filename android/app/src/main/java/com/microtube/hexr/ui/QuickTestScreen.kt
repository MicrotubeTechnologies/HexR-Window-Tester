package com.microtube.hexr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.microtube.hexr.HANDS
import com.microtube.hexr.HexrViewModel
import com.microtube.hexr.QuickTest

/**
 * Screen 03 — the canned QA sweep.
 *
 * Drives every channel to full pressure at once, watches what each one reaches
 * and how fast, then judges it. This is the pass/fail check you run on a glove
 * coming off the bench or back from a customer; screen 02 is for exploring,
 * this is for deciding.
 *
 * The verdict thresholds live in [QuickTest] and match the desktop tester's, so
 * a result recorded on a phone means the same thing as one recorded on a bench
 * laptop.
 */

private fun verdictColour(verdict: String?): Color = when (verdict) {
    "Pass", "Good" -> T.SUCCESS
    "Weak" -> T.ACCENT
    "Indenter failed", "Pump failed" -> T.DANGER
    else -> T.TEXT_2
}

@Composable
fun QuickTestScreen(vm: HexrViewModel) {
    val running = vm.qtPhase == "settle" || vm.qtPhase == "drive"

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(T.PAGE_PAD),
    ) {
        Heading("Quick test")
        Caption(
            "Drives every channel to full for two seconds and reports what each one " +
                "reached. Take the glove off first.",
            Modifier.padding(top = 4.dp),
            T.TEXT_MUTED,
        )
        VSpace(18)

        SectionLabel("Glove to test")
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HANDS.forEach { hand ->
                val g = vm.gloves[hand]
                val live = g?.connected == true
                Cell(
                    label = hand,
                    sub = if (live) "connected" else "not connected",
                    selected = live && vm.qtHand == hand,
                    enabled = live && !running,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.qtHand = hand },
                )
            }
        }
        VSpace(18)

        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                if (running) "Stop" else "Run quick test",
                onClick = vm::toggleQuickTest,
            )
            HSpace(12)
            Caption(vm.qtStatus, Modifier.weight(1f), vm.qtStatusTone.color())
        }
        VSpace(14)

        ProgressBar(
            fraction = vm.qtProgress,
            color = when (vm.qtPhase) {
                "settle" -> T.TEXT_FAINT
                "drive" -> T.ACCENT
                "done" -> T.SUCCESS
                else -> T.RAIL
            },
        )
        VSpace(18)

        Panel(Modifier.fillMaxWidth()) {
            Column {
                TableRow {
                    SectionLabel("Channel", Modifier.width(72.dp))
                    SectionLabel("Peak", Modifier.width(76.dp))
                    SectionLabel("Time", Modifier.width(56.dp))
                    SectionLabel("Verdict", Modifier.weight(1f))
                }
                Divider()
                vm.qtRows.forEach { row ->
                    TableRow {
                        Label(row.label, Modifier.width(72.dp))
                        MonoText(
                            row.peak?.let { "%.1f".format(it) } ?: "—",
                            Modifier.width(76.dp),
                            T.TEXT,
                        )
                        MonoText(
                            row.timeToPeak?.let { "%.2fs".format(it) } ?: "—",
                            Modifier.width(56.dp),
                            T.TEXT_2,
                            11,
                        )
                        Label(
                            row.verdict ?: "—",
                            Modifier.weight(1f),
                            verdictColour(row.verdict),
                            12,
                        )
                    }
                    Divider()
                }
            }
        }

        if (vm.qtSummary.isNotEmpty()) {
            VSpace(14)
            Caption(vm.qtSummary, color = vm.qtSummaryTone.color())
        }

        VSpace(22)
        SectionLabel("What the verdicts mean")
        VSpace(8)
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                listOf(
                    "Pass" to "above ${QuickTest.PASS_KPA.toInt()} kPa — full strength",
                    "Good" to "${QuickTest.GOOD_KPA.toInt()}–${QuickTest.PASS_KPA.toInt()} kPa — within tolerance",
                    "Weak" to "${QuickTest.WEAK_KPA.toInt()}–${QuickTest.GOOD_KPA.toInt()} kPa — never got to full",
                    "Indenter failed" to "under ${QuickTest.WEAK_KPA.toInt()} kPa — barely moved",
                    "Pump failed" to "nothing at all — that channel is getting no air",
                ).forEach { (verdict, meaning) ->
                    TableRow {
                        Label(verdict, Modifier.width(112.dp), verdictColour(verdict), 12)
                        Caption(meaning, Modifier.weight(1f))
                    }
                }
            }
        }
        VSpace(10)
        Caption(
            "Only Pass and Good count as a pass — a Weak channel is reported as a failure " +
                "even though it did move. If the source itself never develops pressure, every " +
                "channel is marked Pump failed at once, because that is one fault and not six.",
        )
        VSpace(24)
    }
}
