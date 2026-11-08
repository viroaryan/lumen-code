// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ModalBottomSheet
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.LButton
import app.lumen.ui.components.pressable
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lumen.ui.theme.Lumen
import kotlin.math.cos
import kotlin.math.sin

/** Everything a control needs: its range, resolution, default and how to print its value. */
class ControlSpec(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val default: Float,
    val step: Float,
    val format: (Float) -> String,
    val onChange: (Float) -> Unit,
    val bipolar: Boolean = false,
    val help: String = "",
) {
    val span get() = range.endInclusive - range.start
    val fraction get() = ((value - range.start) / span).coerceIn(0f, 1f)

    /** Snaps to the control's resolution, so values land on clean numbers like -3.5 dB or 120 Hz. */
    fun snap(v: Float): Float {
        val steps = ((v - range.start) / step).roundToInt()
        return (range.start + steps * step).coerceIn(range.start, range.endInclusive)
    }

    fun set(v: Float) {
        val s = snap(v)
        if (s != value) onChange(s)
    }
}

/**
 * A studio rotary knob. Drag up/right to turn it up (a full turn takes 240 dp of travel, so it can be
 * set precisely), double-tap to reset, tap to open the fine-tune panel with exact steps.
 */
@Composable
fun Knob(spec: ControlSpec, onFineTune: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lumen.colors
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(spec)
    // Fractional progress between steps is kept here so slow drags still move the knob.
    var travel by remember { mutableFloatStateOf(0f) }
    Column(modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(60.dp)
                .pointerInput(Unit) {
                    val fullTurn = 240.dp.toPx()
                    detectDragGestures(
                        onDragStart = { travel = current.value },
                        onDragEnd = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    ) { change, drag ->
                        change.consume()
                        travel = (travel + (drag.x - drag.y) / fullTurn * current.span).coerceIn(current.range.start, current.range.endInclusive)
                        current.set(travel)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onFineTune() },
                        onDoubleTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            current.onChange(current.default)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(60.dp)) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val start = 135f
                val sweep = 270f
                val fraction = spec.fraction
                drawArc(c.line, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (spec.bipolar) {
                    val mid = start + sweep / 2
                    val to = start + sweep * fraction
                    drawArc(c.text, minOf(mid, to), kotlin.math.abs(to - mid), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                } else {
                    drawArc(c.text, start, sweep * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val r = size.minDimension / 2 - inset - stroke * 1.8f
                drawCircle(c.surfaceHigh, r)
                val angle = Math.toRadians((start + sweep * fraction).toDouble())
                val centre = Offset(size.width / 2, size.height / 2)
                drawLine(
                    c.text, centre + Offset((cos(angle) * r * 0.25f).toFloat(), (sin(angle) * r * 0.25f).toFloat()),
                    centre + Offset((cos(angle) * r * 0.85f).toFloat(), (sin(angle) * r * 0.85f).toFloat()),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(spec.format(spec.value), style = Lumen.type.label, color = c.text, maxLines = 1)
        Text(spec.label, style = Lumen.type.micro, color = c.textDim, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A horizontal fader for levels: drag or tap anywhere on it, fine steps with − and +. */
@Composable
fun Fader(spec: ControlSpec, onFineTune: () -> Unit) {
    val c = Lumen.colors
    val current by rememberUpdatedState(spec)
    var travel by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, style = Lumen.type.label, color = c.text, modifier = Modifier.weight(1f))
            Text(
                spec.format(spec.value), style = Lumen.type.label, color = c.text,
                modifier = Modifier.pressable(onFineTune).clip(RoundedCornerShape(6.dp)).background(c.surfaceHigh).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { spec.set(spec.value - spec.step) }
            Box(
                Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { current.onChange(current.default) },
                            onTap = { o -> current.set(current.range.start + (o.x / size.width).coerceIn(0f, 1f) * current.span) },
                        )
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragStart = { o -> travel = current.range.start + (o.x / size.width).coerceIn(0f, 1f) * current.span }) { change, _ ->
                            change.consume()
                            travel = current.range.start + (change.position.x / size.width).coerceIn(0f, 1f) * current.span
                            current.set(travel)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                    val y = size.height / 2
                    val x = size.width * spec.fraction
                    drawLine(c.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                    if (spec.bipolar) {
                        val mid = size.width * ((0f - spec.range.start) / spec.span).coerceIn(0f, 1f)
                        drawLine(c.text, Offset(mid, y), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(c.textDim, Offset(mid, y - 9.dp.toPx()), Offset(mid, y + 9.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    } else {
                        drawLine(c.text, Offset(0f, y), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                    }
                    drawCircle(c.text, 10.dp.toPx(), Offset(x, y))
                    drawCircle(c.bg, 3.5.dp.toPx(), Offset(x, y))
                }
            }
            StepButton("+") { spec.set(spec.value + spec.step) }
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    val c = Lumen.colors
    Box(
        Modifier.pressable(onClick).size(34.dp).clip(CircleShape).background(c.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Lumen.type.heading, color = c.text) }
}

/** Fine-tune panel: the exact value, a full-width fader, and single / coarse steps either way. */
@Composable
fun FineTuneSheet(spec: ControlSpec, onDismiss: () -> Unit) {
    val c = Lumen.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text(spec.label, style = Lumen.type.title, color = c.text)
            if (spec.help.isNotBlank()) Text(spec.help, style = Lumen.type.small, color = c.textDim)
            Spacer(Modifier.height(16.dp))
            Text(spec.format(spec.value), style = Lumen.type.display, color = c.text, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            Fader(spec, onFineTune = {})
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                LButton("−${trimStep(spec.step * 10)}", { spec.set(spec.value - spec.step * 10) }, style = ButtonStyle.Ghost)
                LButton("−${trimStep(spec.step)}", { spec.set(spec.value - spec.step) }, style = ButtonStyle.Ghost)
                LButton("Reset", { spec.onChange(spec.default) }, style = ButtonStyle.Secondary)
                LButton("+${trimStep(spec.step)}", { spec.set(spec.value + spec.step) }, style = ButtonStyle.Ghost)
                LButton("+${trimStep(spec.step * 10)}", { spec.set(spec.value + spec.step * 10) }, style = ButtonStyle.Ghost)
            }
        }
    }
}

private fun trimStep(v: Float): String = if (v >= 1f || v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.1f".format(v).trimEnd('0').trimEnd('.')

/** Live microphone level, with a hot zone near clipping. */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val c = Lumen.colors
    Row(modifier.height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val bars = 24
        repeat(bars) { i ->
            val on = level * bars > i
            Box(
                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (on) (if (i > bars * 0.85) c.text else c.textDim) else c.line),
            )
        }
    }
}

/** The recorded voice as a waveform, with the playhead. Tap or drag to seek. */
@Composable
fun Waveform(peaks: FloatArray, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Lumen.colors
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        if (peaks.isEmpty()) return@Canvas
        val w = size.width / peaks.size
        val mid = size.height / 2
        peaks.forEachIndexed { i, p ->
            val h = (p * size.height * 0.9f).coerceAtLeast(2.dp.toPx())
            val x = i * w + w / 2
            drawLine(if (i.toFloat() / peaks.size <= progress) c.text else c.textFaint, Offset(x, mid - h / 2), Offset(x, mid + h / 2), strokeWidth = (w * 0.6f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        }
        drawLine(c.text, Offset(size.width * progress, 0f), Offset(size.width * progress, size.height), strokeWidth = 2.dp.toPx())
    }
}

/** A titled group of controls, like a plugin panel on a mixing desk. */
@Composable
fun Panel(title: String, subtitle: String, content: @Composable () -> Unit) {
    val c = Lumen.colors
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp),
    ) {
        Text(title, style = Lumen.type.bodyStrong, color = c.text)
        Text(subtitle, style = Lumen.type.small, color = c.textDim)
        Spacer(Modifier.height(12.dp))
        content()
    }
}
