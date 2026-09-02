package com.microtube.hexr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.microtube.hexr.ui.ConnectScreen
import com.microtube.hexr.ui.Dot
import com.microtube.hexr.ui.HexrTheme
import com.microtube.hexr.ui.QuickTestScreen
import com.microtube.hexr.ui.Size
import com.microtube.hexr.ui.T
import com.microtube.hexr.ui.TestScreen
import kotlin.math.cos
import kotlin.math.sin

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
 * Connection state on the left, All off on the right.
 *
 * The strip turns accent while anything is being driven, so the app's live state
 * is legible from the header no matter which tab is showing — including from the
 * quick-test screen, where the two-second drive is otherwise invisible if you
 * are looking at the glove rather than the phone.
 */
@Composable
private fun TopBar(vm: HexrViewModel, anyConnected: Boolean) {
    val live = vm.driving || vm.qtPhase == "drive"
    val haptics = LocalHapticFeedback.current
    val strip by animateColorAsState(if (live) T.ACCENT else T.RAISED, label = "strip")

    Column(Modifier.fillMaxWidth().background(T.RAISED)) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(0.dp),
        )
        Box(Modifier.fillMaxWidth().height(3.dp).background(strip))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "HEXR Tester",
                    color = T.TEXT,
                    fontSize = Size.SUBTITLE.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val connected = HANDS.filter { vm.gloves[it]?.connected == true }
                    Dot(if (connected.isEmpty()) T.DOT_OFF else T.SUCCESS, size = 7)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when {
                            live -> "driving"
                            connected.isEmpty() -> "no glove connected"
                            else -> connected.joinToString(" + ") { it.lowercase() } + " connected"
                        },
                        color = if (live) T.ACCENT else T.TEXT_MUTED,
                        fontSize = Size.MICRO.sp,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(T.R_CTRL))
                    .background(if (anyConnected) T.INSET else Color.Transparent)
                    .clickable(enabled = anyConnected) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.allOff()
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(
                    "All off",
                    color = if (anyConnected) T.DANGER else T.TEXT_OFF,
                    fontSize = Size.CAPTION.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onPick: (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().background(T.RAIL)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.BORDER_SOFT))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 6.dp),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                val tint by animateColorAsState(
                    if (selected) T.ACCENT else T.TEXT_MUTED,
                    label = "navTint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(T.R_CELL))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(t)
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NavGlyph(t, tint)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        t.label,
                        color = tint,
                        fontSize = Size.MICRO.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Tab glyphs, drawn rather than imported.
 *
 * Three shapes taken from the tool's own subject: the brand hexagon for finding
 * a glove, a waveform for driving one, and a bar chart for measuring one. A
 * generic icon set has nothing this specific in it, and pulling in
 * material-icons-extended to get three near-misses is a poor trade.
 */
@Composable
private fun NavGlyph(tab: Tab, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            Tab.Connect -> {
                // The HEXR mark: a flat-topped hexagon with its contact point.
                val r = w * 0.42f
                val cx = w / 2f
                val cy = h / 2f
                val path = Path()
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i)
                    val x = cx + r * cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, tint, style = stroke)
                drawCircle(tint, radius = w * 0.13f, center = Offset(cx, cy))
            }

            Tab.Test -> {
                // A pressure wave: rise, hold, fall.
                val path = Path()
                path.moveTo(w * 0.08f, h * 0.72f)
                path.cubicTo(w * 0.30f, h * 0.72f, w * 0.30f, h * 0.26f, w * 0.50f, h * 0.26f)
                path.cubicTo(w * 0.70f, h * 0.26f, w * 0.70f, h * 0.72f, w * 0.92f, h * 0.72f)
                drawPath(path, tint, style = stroke)
            }

            Tab.Quick -> {
                // Three channels at three different peaks — the thing the sweep
                // is looking for.
                val bw = w * 0.16f
                listOf(0.42f, 0.78f, 0.58f).forEachIndexed { i, frac ->
                    val x = w * (0.20f + i * 0.30f)
                    drawLine(
                        tint,
                        Offset(x, h * 0.86f),
                        Offset(x, h * (0.86f - 0.72f * frac)),
                        strokeWidth = bw,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
