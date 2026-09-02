package com.microtube.hexr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.microtube.hexr.ui.ConnectScreen
import com.microtube.hexr.ui.Dot
import com.microtube.hexr.ui.HexrTheme
import com.microtube.hexr.ui.QuickTestScreen
import com.microtube.hexr.ui.T
import com.microtube.hexr.ui.TestScreen

class MainActivity : ComponentActivity() {

    private val vm: HexrViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.permissionsGranted = vm.engine.hasPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HexrTheme {
                AppRoot(vm) { permissionLauncher.launch(vm.engine.requiredPermissions) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.permissionsGranted = vm.engine.hasPermissions()
    }

    /**
     * Vent everything the moment the app stops being visible.
     *
     * A phone gets pocketed mid-test in a way a desktop window does not, and a
     * channel left inflated presses on someone's hand until the battery dies.
     * Costing a technician one re-trigger is much the cheaper mistake.
     */
    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}

private enum class Tab(val label: String) {
    Connect("Connect"),
    Test("Test"),
    Quick("Quick test"),
}

@Composable
private fun AppRoot(vm: HexrViewModel, onRequestPermissions: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Connect) }
    val anyConnected = vm.gloves.values.any { it.connected }

    Scaffold(
        containerColor = T.SURFACE,
        topBar = { TopBar(vm, anyConnected) },
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Connect -> ConnectScreen(vm, onRequestPermissions)
                Tab.Test -> TestScreen(vm)
                Tab.Quick -> QuickTestScreen(vm)
            }
        }
    }
}

/**
 * The header carries connection state and an always-reachable All off.
 *
 * On the desktop that button lives on the test screen. Here it is pinned to the
 * top bar on every screen: the one action you might need in a hurry should
 * never be behind a tab change.
 */
@Composable
private fun TopBar(vm: HexrViewModel, anyConnected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(T.RAISED)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "HEXR Tester",
                color = T.TEXT,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val connected = HANDS.filter { vm.gloves[it]?.connected == true }
                Dot(if (connected.isEmpty()) T.DOT_OFF else T.SUCCESS)
                Text(
                    "  " + if (connected.isEmpty()) {
                        "no glove connected"
                    } else {
                        connected.joinToString(" + ") { it.lowercase() } + " connected"
                    },
                    color = T.TEXT_MUTED,
                    fontSize = 12.sp,
                )
            }
        }
        Box(
            Modifier
                .background(T.INSET, androidx.compose.foundation.shape.RoundedCornerShape(T.R_CTRL))
                .clickable(enabled = anyConnected) { vm.allOff() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                "All off",
                color = if (anyConnected) T.DANGER else T.TEXT_OFF,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onPick: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(T.RAIL)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Tab.entries.forEach { t ->
            val selected = t == current
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onPick(t) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = if (selected) T.ACCENT else T.TEXT_MUTED,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
