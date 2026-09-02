package com.microtube.hexr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.microtube.hexr.Tone

fun Tone.color(): Color = when (this) {
    Tone.Muted -> T.TEXT_MUTED
    Tone.Accent -> T.ACCENT
    Tone.Success -> T.SUCCESS
    Tone.Danger -> T.DANGER
}

/** A screen or panel heading. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: Int = 20) {
    Text(
        text,
        modifier = modifier,
        color = T.TEXT,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Plain body text in the app's secondary colour. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = T.TEXT_2,
    size: Int = 13,
) {
    Text(text, modifier = modifier, color = color, fontSize = size.sp)
}

/** A value the glove reported, or anything compared column to column. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = T.TEXT_2,
    size: Int = 12,
) {
    Text(text, modifier = modifier, color = color, fontFamily = Mono, fontSize = size.sp)
}

/** The tracked uppercase micro-label used above every group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        color = T.TEXT_FAINT,
    )
}

/** A raised container: the app's panels, cards and tables all sit in one. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(T.R_PANEL))
            .background(T.RAISED)
            .border(1.dp, T.BORDER_SOFT, RoundedCornerShape(T.R_PANEL)),
    ) { content() }
}

/**
 * A selectable tile — hand, channel and mode pickers are all made of these.
 *
 * Selection is carried by fill, border *and* text colour together rather than
 * by colour alone. These get used under bench lighting and read at arm's length.
 */
@Composable
fun Cell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fill by animateColorAsState(
        if (!enabled) T.SURFACE else if (selected) T.ACCENT_FILL else T.INSET,
        label = "cellFill",
    )
    val edge by animateColorAsState(
        if (!enabled) T.BORDER_SOFT else if (selected) T.ACCENT_EDGE else T.BORDER,
        label = "cellEdge",
    )
    val fg = when {
        !enabled -> T.TEXT_OFF
        selected -> T.ACCENT
        else -> T.TEXT
    }
    Column(
        modifier
            .clip(RoundedCornerShape(T.R_CELL))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(T.R_CELL))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (sub != null) {
            Text(sub, color = if (enabled) T.TEXT_MUTED else T.TEXT_OFF, fontSize = 11.5.sp)
        }
    }
}

/** The solid accent action. One per screen at most. */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(if (enabled) T.ACCENT else T.INSET)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) T.CANVAS else T.TEXT_OFF,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** An outlined action. [tint] carries destructive intent where it applies. */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = T.TEXT,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(T.INSET)
            .border(1.dp, if (enabled) T.BORDER else T.BORDER_SOFT, RoundedCornerShape(T.R_CTRL))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tint else T.TEXT_OFF,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Connection state, as a coloured dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(9.dp).clip(CircleShape).background(color))
}

/**
 * A horizontal bar for one channel's pressure.
 *
 * Scaled to a fixed ceiling rather than to the highest value seen, so a weak
 * channel looks weak instead of being normalised into looking fine.
 */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, active: Boolean = true) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "meter")
    Box(
        modifier
            .height(12.dp)
            .clip(RoundedCornerShape(T.R_METER))
            .background(T.RAIL),
    ) {
        Box(
            Modifier
                .fillMaxSize(fraction = f)
                .clip(RoundedCornerShape(T.R_METER))
                .background(if (active) T.ACCENT else T.METER_IDLE),
        )
    }
}

/** Determinate progress, used by the quick test's settle and drive phases. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(T.R_METER))
            .background(T.RAIL),
    ) {
        Box(
            Modifier
                .fillMaxSize(fraction = fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(T.R_METER))
                .background(color),
        )
    }
}

/** A labelled scalar — battery, source pressure. */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(T.R_CELL))
            .background(T.INSET)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, color = T.TEXT, fontFamily = Mono, fontSize = 15.sp)
    }
}

/** A slider with its name on the left and its current value on the right. */
@Composable
fun LabelledSlider(
    label: String,
    value: Double,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            Text(valueText, color = T.TEXT, fontFamily = Mono, fontSize = 12.5.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = T.ACCENT,
                activeTrackColor = T.ACCENT,
                inactiveTrackColor = T.RAIL,
            ),
        )
    }
}

/** A one-line row in a results table. */
@Composable
fun TableRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(T.BORDER_ROW))
}

/** Explanatory copy under a control group. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = T.TEXT_FAINT) {
    if (text.isEmpty()) return
    Text(text, modifier = modifier, color = color, fontSize = 12.5.sp, lineHeight = 17.sp)
}

/** Shown where a screen has nothing to display yet. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(18.dp),
        color = T.TEXT_MUTED,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.width(width.dp))

@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
