package com.microtube.hexr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.microtube.hexr.Protocol
import kotlin.math.cos
import kotlin.math.sin

/**
 * The glove, drawn as six tappable shapes.
 *
 * This replaced a list of channel names with a bar-gauge grid. The list made
 * you translate "Ring" into a finger before you could act on it; the diagram is
 * the thing itself, and each shape carries its own live pressure so the reading
 * is where the channel is rather than in a table somewhere else.
 *
 * Geometry is in the handoff's 216x240 coordinate space and scaled to whatever
 * box it is given, so the numbers below match the design source exactly and can
 * be diffed against it.
 *
 * A selected channel fills from its own colour at an alpha that tracks live
 * pressure (0-50 kPa mapped to 0.10-0.82), which makes a weak channel visibly
 * pale next to a healthy one at a glance — the same reason the desktop meters
 * are scaled to a fixed ceiling rather than to the highest value seen.
 */

private const val VB_W = 216f
private const val VB_H = 240f

/** Full-scale pressure for the fill ramp. The firmware caps a channel here. */
private const val FULL_KPA = 50.0

/** A rounded rectangle in viewBox units, optionally rotated about a pivot. */
private data class Shape(
    val finger: Protocol.Finger,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val r: Float,
    val rotation: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    /** Baseline position of the channel's numeric readout. */
    val textX: Float,
    val textY: Float,
)

/**
 * Draw order, back to front, exactly as the design source lists them. Hit
 * testing walks this in reverse so the topmost shape wins a tap, which is what
 * makes the thumb's rotated corner behave where it grazes the palm.
 */
private val SHAPES = listOf(
    Shape(Protocol.Finger.Pinky, 18f, 56f, 26f, 76f, 13f, textX = 31f, textY = 98f),
    Shape(Protocol.Finger.Ring, 52f, 30f, 28f, 102f, 14f, textX = 66f, textY = 84f),
    Shape(Protocol.Finger.Middle, 88f, 20f, 28f, 112f, 14f, textX = 102f, textY = 79f),
    Shape(Protocol.Finger.Index, 124f, 32f, 28f, 100f, 14f, textX = 138f, textY = 85f),
    Shape(
        Protocol.Finger.Thumb, 150f, 122f, 30f, 70f, 15f,
        rotation = -38f, pivotX = 165f, pivotY = 132f, textX = 173f, textY = 163f,
    ),
    Shape(Protocol.Finger.Palm, 30f, 140f, 122f, 86f, 26f, textX = 91f, textY = 187f),
)

@Composable
fun GloveDiagram(
    selected: Set<Int>,
    live: (Int) -> Double?,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
    widthDp: Int = 252,
    heightDp: Int = 300,
    onToggle: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Box(modifier.width(widthDp.dp).height(heightDp.dp)) {
        Canvas(
            Modifier
                .width(widthDp.dp)
                .height(heightDp.dp)
                .pointerInput(mirrored) {
                    detectTapGestures { tap ->
                        val sx = size.width / VB_W
                        val sy = size.height / VB_H
                        var vx = tap.x / sx
                        val vy = tap.y / sy
                        // Undo the right-hand mirror before testing, so the
                        // shapes are always hit in their canonical positions.
                        if (mirrored) vx = VB_W - vx
                        val hit = SHAPES.lastOrNull { it.contains(vx, vy) }
                        if (hit != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle(hit.finger.channel)
                        }
                    }
                },
        ) {
            val sx = size.width / VB_W
            val sy = size.height / VB_H

            // The right glove is the left one mirrored about the diagram's
            // centre line. Channel numbering does not change with the hand —
            // only where the finger sits.
            val flip = if (mirrored) -1f else 1f
            scale(scaleX = flip, scaleY = 1f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                for (shape in SHAPES) {
                    val on = shape.finger.channel in selected
                    val kpa = live(shape.finger.channel)
                    val v = ((kpa ?: 0.0) / FULL_KPA).coerceIn(0.0, 1.0).toFloat()

                    val fill = if (on) {
                        T.ACCENT.copy(alpha = 0.10f + 0.72f * v)
                    } else {
                        Color.White.copy(alpha = 0.03f)
                    }
                    val stroke = if (on) T.ACCENT else T.TEXT_FAINT

                    withShape(shape, sx, sy) {
                        drawRoundRect(
                            color = fill,
                            topLeft = Offset(shape.x * sx, shape.y * sy),
                            size = GSize(shape.w * sx, shape.h * sy),
                            cornerRadius = CornerRadius(shape.r * sx, shape.r * sy),
                        )
                        drawRoundRect(
                            color = stroke,
                            topLeft = Offset(shape.x * sx, shape.y * sy),
                            size = GSize(shape.w * sx, shape.h * sy),
                            cornerRadius = CornerRadius(shape.r * sx, shape.r * sy),
                            style = Stroke(width = 2f * sx),
                        )
                    }
                }
            }

            // Readouts are drawn outside the mirror so the digits stay upright
            // on a right glove while their positions still mirror with it.
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 11f * sx
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.BOLD,
                )
            }
            for (shape in SHAPES) {
                val on = shape.finger.channel in selected
                val kpa = live(shape.finger.channel)
                val text = when {
                    kpa == null -> "–"
                    kpa < 0.5 -> "0"
                    else -> kpa.toInt().toString()
                }
                paint.color = (if (on) T.TEXT else T.TEXT_4).toArgb()
                val tx = if (mirrored) (VB_W - shape.textX) * sx else shape.textX * sx
                drawContext.canvas.nativeCanvas.drawText(text, tx, shape.textY * sy, paint)
            }
        }
    }
}

/** Apply a shape's rotation about its pivot, in scaled canvas space. */
private inline fun DrawScope.withShape(
    shape: Shape,
    sx: Float,
    sy: Float,
    body: DrawScope.() -> Unit,
) {
    if (shape.rotation == 0f) {
        body()
    } else {
        rotate(shape.rotation, Offset(shape.pivotX * sx, shape.pivotY * sy)) { body() }
    }
}

/** Point-in-shape in viewBox units, undoing the shape's own rotation first. */
private fun Shape.contains(px: Float, py: Float): Boolean {
    var x = px
    var y = py
    if (rotation != 0f) {
        val rad = Math.toRadians(-rotation.toDouble())
        val dx = px - pivotX
        val dy = py - pivotY
        x = (dx * cos(rad) - dy * sin(rad)).toFloat() + pivotX
        y = (dx * sin(rad) + dy * cos(rad)).toFloat() + pivotY
    }
    return x >= this.x && x <= this.x + w && y >= this.y && y <= this.y + h
}
