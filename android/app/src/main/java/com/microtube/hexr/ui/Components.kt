package com.microtube.hexr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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

// -- text ---------------------------------------------------------------------

/** A screen or panel heading. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: Int = Size.TITLE) {
    Text(
        text,
        modifier = modifier,
        color = T.TEXT,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )
}

/** Plain body text in the app's secondary colour. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = T.TEXT_2,
    size: Float = Size.BODY,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(text, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight)
}

/**
 * A value the glove reported.
 *
 * Monospace throughout, so a column of readings does not jitter sideways as the
 * digits change — which they do fifty times a second.
 */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = T.TEXT_2,
    size: Float = 13f,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = Mono,
        fontSize = size.sp,
        fontWeight = weight,
    )
}

/** The tracked uppercase micro-label above every group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = Size.LABEL.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
        color = T.TEXT_FAINT,
    )
}

/** Explanatory copy under a control group. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = T.TEXT_FAINT) {
    if (text.isEmpty()) return
    Text(text, modifier = modifier, color = color, fontSize = Size.CAPTION.sp, lineHeight = 18.sp)
}

/** Shown where a screen has nothing to display yet. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(22.dp),
        color = T.TEXT_MUTED,
        fontSize = Size.CAPTION.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
}

// -- containers -----------------------------------------------------------------

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

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(T.BORDER_ROW))
}

/** A one-line row in a results table. */
@Composable
fun TableRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A small status word — hand, state. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = T.TEXT_2) {
    Box(
        modifier
            .clip(RoundedCornerShape(T.R_CHIP))
            .background(T.INSET)
            .border(1.dp, T.BORDER, RoundedCornerShape(T.R_CHIP))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = Size.MICRO.sp, fontWeight = FontWeight.Medium)
    }
}

/** Connection state, as a coloured dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Int = 9) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

// -- controls -------------------------------------------------------------------

/**
 * A selectable tile — hand, channel and mode pickers are all made of these.
 *
 * Selection is carried by fill, border *and* text colour together rather than by
 * colour alone. These get used under bench lighting and read at arm's length.
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
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(
        if (!enabled) T.SURFACE else if (selected) T.ACCENT_FILL else T.INSET,
        label = "cellFill",
    )
    val edge by animateColorAsState(
        if (!enabled) T.BORDER_SOFT else if (selected) T.ACCENT else T.BORDER,
        label = "cellEdge",
    )
    val fg = when {
        !enabled -> T.TEXT_OFF
        selected -> T.ACCENT
        else -> T.TEXT
    }
    Column(
        modifier
            .defaultMinSize(minHeight = T.TAP_MIN)
            .clip(RoundedCornerShape(T.R_CELL))
            .background(fill)
            .border(if (selected) 1.5.dp else 1.dp, edge, RoundedCornerShape(T.R_CELL))
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            color = fg,
            fontSize = Size.BODY.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (sub != null) {
            Text(
                sub,
                color = if (enabled) T.TEXT_MUTED else T.TEXT_OFF,
                fontSize = Size.MICRO.sp,
            )
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
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .defaultMinSize(minHeight = T.TAP_MIN)
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(if (enabled) T.ACCENT else T.INSET)
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) T.CANVAS else T.TEXT_OFF,
            fontSize = Size.BODY.sp,
            fontWeight = FontWeight.Bold,
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
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .then(if (compact) Modifier else Modifier.defaultMinSize(minHeight = T.TAP_MIN))
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(T.INSET)
            .border(1.dp, if (enabled) T.BORDER else T.BORDER_SOFT, RoundedCornerShape(T.R_CTRL))
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 8.dp else 14.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tint else T.TEXT_OFF,
            fontSize = if (compact) Size.CAPTION.sp else Size.BODY.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A two-option switch. Used for hold-versus-latch on the drive pad. */
@Composable
fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(T.RAIL)
            .border(1.dp, T.BORDER_SOFT, RoundedCornerShape(T.R_CTRL))
            .padding(3.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(T.R_CHIP))
                    .background(if (on) T.INSET else Color.Transparent)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPick(key)
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) T.TEXT else T.TEXT_MUTED,
                    fontSize = Size.CAPTION.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
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
    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionLabel(label, Modifier.weight(1f).padding(bottom = 2.dp))
            MonoText(valueText, color = T.TEXT, size = 14f, weight = FontWeight.Medium)
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

// -- instrumentation --------------------------------------------------------------

/**
 * The six-channel manifold gauge.
 *
 * One vertical bar per channel, laid out the way the hardware is: six lines off
 * one source. It is the reason the Test screen is built around a pinned panel —
 * on the desktop the readout sits beside the controls, and losing that to a
 * scroll would mean driving a channel with no way to see what it did.
 *
 * Bars are scaled to a fixed ceiling rather than to the highest value seen, so a
 * weak channel looks weak instead of being normalised into looking fine.
 */
@Composable
fun Manifold(
    values: List<Double?>,
    labels: List<String>,
    selected: Set<Int>,
    modifier: Modifier = Modifier,
    fullScale: Double = 60.0,
    barHeight: Int = 76,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val kpa = values.getOrNull(i)
            val on = i in selected
            val fraction by animateFloatAsState(
                ((kpa ?: 0.0) / fullScale).coerceIn(0.0, 1.0).toFloat(),
                spring(stiffness = 900f),
                label = "bar",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoText(
                    kpa?.let { "%.0f".format(it) } ?: "–",
                    color = when {
                        kpa == null -> T.TEXT_OFF
                        on -> T.TEXT
                        else -> T.TEXT_MUTED
                    },
                    size = Size.MICRO,
                    weight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(T.R_METER))
                        .background(T.RAIL)
                        .border(
                            1.dp,
                            if (on) T.ACCENT_EDGE else T.BORDER_SOFT,
                            RoundedCornerShape(T.R_METER),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(T.R_METER))
                            .background(if (on) T.ACCENT else T.METER_IDLE),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    label,
                    color = if (on) T.ACCENT else T.TEXT_FAINT,
                    fontSize = Size.MICRO.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Hold to drive, release to vent.
 *
 * This replaces the desktop's Trigger and Release pair, and it is the one real
 * departure in the port. Three reasons it is the right control on a phone: the
 * gesture is what the hardware does, so there is no state to lose track of; it
 * sits under the thumb of the hand that is not holding the glove; and letting go
 * is both the natural end of a test and the safe one — a dropped phone vents.
 *
 * Latch mode exists for the one case hold cannot serve: putting the phone down
 * to feel a channel with both hands.
 */
@Composable
fun DrivePad(
    driving: Boolean,
    enabled: Boolean,
    latched: Boolean,
    summary: String,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(
        when {
            !enabled -> T.SURFACE
            driving -> T.ACCENT
            else -> T.ACCENT_FILL
        },
        label = "padFill",
    )
    val edge by animateColorAsState(
        when {
            !enabled -> T.BORDER_SOFT
            driving -> T.ACCENT
            else -> T.ACCENT_EDGE
        },
        label = "padEdge",
    )
    val fg = when {
        !enabled -> T.TEXT_OFF
        driving -> T.CANVAS
        else -> T.ACCENT
    }

    val gesture = when {
        !enabled -> Modifier
        latched -> Modifier.pointerInput(driving) {
            detectTapGestures(onTap = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (driving) onStop() else onStart()
            })
        }

        else -> Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStart()
                // Returns on release *or* cancel, so a finger dragged off the
                // pad vents too. Anything that ends the touch vents.
                tryAwaitRelease()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onStop()
            })
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(T.R_CTRL))
            .background(fill)
            .border(2.dp, edge, RoundedCornerShape(T.R_CTRL))
            .then(gesture)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                !enabled -> "Nothing to drive"
                driving -> "Driving"
                latched -> "Tap to drive"
                else -> "Hold to drive"
            },
            color = fg,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            summary,
            color = if (driving) T.CANVAS.copy(alpha = 0.75f) else T.TEXT_MUTED,
            fontSize = Size.MICRO.sp,
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
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        SectionLabel(label)
        Spacer(Modifier.height(5.dp))
        MonoText(value, color = T.TEXT, size = 18f, weight = FontWeight.Medium)
    }
}

/**
 * The answer, stated once and large.
 *
 * Someone runs the quick test to learn one thing. Making them read it out of a
 * six-row table is making them do the summarising themselves.
 */
@Composable
fun VerdictBanner(headline: String, detail: String, tone: Tone, modifier: Modifier = Modifier) {
    val accent = tone.color()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(T.R_PANEL))
            .background(T.RAISED)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(T.R_PANEL))
            .padding(18.dp),
    ) {
        Text(
            headline,
            color = accent,
            fontSize = Size.DISPLAY.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 34.sp,
        )
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(detail, color = T.TEXT_2, fontSize = Size.CAPTION.sp, lineHeight = 18.sp)
        }
    }
}

/** A collapsed section. Reference material should not cost a screen of scroll. */
@Composable
fun Disclosure(
    title: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(T.R_CELL))
                .clickable(onClick = onToggle)
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "−" else "+",
                color = T.TEXT_FAINT,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp),
            )
            SectionLabel(title, Modifier.weight(1f))
        }
        if (expanded) content()
    }
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.width(width.dp))

@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
