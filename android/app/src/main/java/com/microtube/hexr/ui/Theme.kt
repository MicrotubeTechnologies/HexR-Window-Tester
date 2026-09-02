package com.microtube.hexr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens, transcribed from the desktop tester's `hexr/theme.py`.
 *
 * The palette is deliberately identical so the two apps read as one tool. It is
 * dark-only and does not follow the system light theme: this is used on a bench
 * and in customers' offices, and a glove's pressure meters need consistent
 * contrast rather than a palette that changes with the phone's settings.
 */
object T {
    val CANVAS = Color(0xFF0A0B0C)        // behind everything; text on orange fills
    val SURFACE = Color(0xFF111214)       // body
    val RAISED = Color(0xFF17191C)        // panels, cards, table rows
    val INSET = Color(0xFF1C1F23)         // selected rows, chips, readouts
    val RAIL = Color(0xFF131417)          // meter and trace backgrounds, bars

    val BORDER = Color(0xFF2A2E34)        // panel and control borders
    val BORDER_SOFT = Color(0xFF23262B)   // section dividers, panel outlines
    val BORDER_ROW = Color(0xFF1E2126)    // table row separators

    val TEXT = Color(0xFFE9EAEC)          // primary
    val TEXT_2 = Color(0xFFB7BCC3)        // values, secondary labels
    val TEXT_MUTED = Color(0xFF868C94)    // descriptions, idle nav
    val TEXT_FAINT = Color(0xFF6E747C)    // section labels, captions
    val TEXT_OFF = Color(0xFF5C626A)      // disabled labels
    val DOT_OFF = Color(0xFF4A5058)       // disabled dots

    val ACCENT = Color(0xFFF26B21)        // brand, active state, firing signal
    val ACCENT_HOVER = Color(0xFFFF8442)
    val ACCENT_FILL = Color(0xFF26221F)   // assigned/selected fill
    val ACCENT_EDGE = Color(0xFF5A3A20)   // assigned/selected border

    val SUCCESS = Color(0xFF3ECF8E)       // connected, healthy
    val DANGER = Color(0xFFE5484D)        // stop, destructive

    val METER_IDLE = Color(0xFF3A4048)    // below-threshold signal fill

    // Geometry. The desktop's radii, scaled up a little: a 3 px radius that
    // reads as a crisp corner on a monitor reads as a rendering artefact at
    // phone density.
    val R_METER = 4.dp
    val R_CHIP = 6.dp
    val R_CELL = 10.dp
    val R_CTRL = 12.dp
    val R_PANEL = 14.dp

    val PAGE_PAD = 16.dp

    /**
     * Minimum touch target. Android asks for 48; controls that carry pressure
     * to somebody's hand get a little more, because a mis-tap here is not a
     * mis-tap in a menu.
     */
    val TAP_MIN = 52.dp
}

/**
 * The type scale.
 *
 * The desktop app sits at 11–15 px throughout, which is right for a monitor an
 * arm's length away with a mouse. On a phone held over a bench it flattens
 * everything into one texture. The reading is what someone is actually here to
 * see, so the reading is the biggest thing on the screen and the label next to
 * it is the smallest.
 */
object Size {
    const val DISPLAY = 30      // the one number a screen exists to show
    const val READING = 19      // live kPa, peak values
    const val TITLE = 23        // screen heading
    const val SUBTITLE = 16     // panel heading
    const val BODY = 14.5f
    const val CAPTION = 13
    const val MICRO = 11.5f     // gauge scales, units
    const val LABEL = 11        // tracked uppercase section labels
}

/**
 * Monospace for anything the glove reported and anything compared column to
 * column — pressures, addresses, timings. Proportional digits make a table of
 * kPa readings jitter as the values change.
 */
val Mono = FontFamily.Monospace

private val HexrTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = T.TEXT),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = T.TEXT),
    bodyLarge = TextStyle(fontSize = 15.sp, color = T.TEXT),
    bodyMedium = TextStyle(fontSize = 13.5.sp, color = T.TEXT_2),
    bodySmall = TextStyle(fontSize = 12.5.sp, color = T.TEXT_MUTED),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        color = T.TEXT_FAINT,
    ),
)

private val HexrColors = darkColorScheme(
    primary = T.ACCENT,
    onPrimary = T.CANVAS,
    background = T.SURFACE,
    onBackground = T.TEXT,
    surface = T.RAISED,
    onSurface = T.TEXT,
    surfaceVariant = T.INSET,
    onSurfaceVariant = T.TEXT_2,
    error = T.DANGER,
    outline = T.BORDER,
)

/** Dark only, by design — the system light theme is deliberately not consulted. */
@Composable
fun HexrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HexrColors, typography = HexrTypography, content = content)
}
