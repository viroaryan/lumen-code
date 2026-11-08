// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.R
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import coil3.compose.SubcomposeAsyncImage

/** The Lumen mark: a listening ear inside a headphone arc. */
@Composable
fun LumenLogo(modifier: Modifier = Modifier, color: Color = Lumen.colors.text) {
    Icon(painterResource(R.drawable.lumen_mark), "Lumen", modifier, tint = color)
}

/** Shrinks slightly while pressed, the tactile feel used by every Lumen button. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, onLongClick: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    return this
        .scale(scale)
        .then(
            if (onLongClick != null) Modifier.combinedClickable(interactionSource = source, indication = null, enabled = enabled, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }, onClick = onClick)
            else Modifier.clickable(source, null, enabled = enabled, onClick = onClick),
        )
}

@Composable
fun LIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Lumen.colors.text,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier.pressable(onClick, enabled).size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

/** Big round play button: ink-filled with a page-coloured glyph. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val c = Lumen.colors
    val ink = c.text
    // The circle is drawn directly (no clip layer) inside the pressed-scale layer, so it can never be
    // dropped by the GPU while the icon above it still draws.
    Box(
        modifier.size(size).pressable(onClick).drawBehind { drawCircle(ink) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) LIcons.Pause else LIcons.Play, if (playing) "Pause" else "Play", Modifier.size(size * 0.46f), tint = c.bg)
    }
}

enum class ButtonStyle { Primary, Secondary, Ghost }

@Composable
fun LButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val c = Lumen.colors
    val shape = RoundedCornerShape(50)
    val bg = when (style) { ButtonStyle.Primary -> c.text; ButtonStyle.Secondary -> Color.Transparent; ButtonStyle.Ghost -> c.surface }
    val fg = if (style == ButtonStyle.Primary) c.bg else c.text
    Row(
        modifier
            .pressable(onClick, enabled)
            .height(42.dp)
            .clip(shape)
            .background(bg)
            .then(if (style == ButtonStyle.Secondary) Modifier.border(1.dp, c.line, shape) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Lumen.type.label, color = fg, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lumen.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .pressable(onClick)
            .height(34.dp)
            .clip(shape)
            .background(if (selected) c.text else c.surface)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Lumen.type.label, color = if (selected) c.bg else c.text, maxLines = 1)
    }
}

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    circle: Boolean = false,
    placeholder: ImageVector = LIcons.Note,
) {
    val c = Lumen.colors
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { ArtPlaceholder(placeholder) },
            )
        } else ArtPlaceholder(placeholder)
    }
}

@Composable
private fun ArtPlaceholder(icon: ImageVector) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = Lumen.colors.textFaint)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Lumen.type.heading, color = Lumen.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(action, style = Lumen.type.label, color = Lumen.colors.textDim, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAction).padding(8.dp))
        }
    }
}

/** Three bars that dance while the row's song is playing. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean, color: Color = Lumen.colors.text) {
    val t = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 360).map { delay ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = delay), RepeatMode.Reverse), label = "bar$delay")
    }
    Canvas(modifier.size(14.dp)) {
        val w = size.width / 5
        phases.forEachIndexed { i, p ->
            val h = size.height * (if (playing) p.value else 0.3f + i * 0.15f)
            drawLine(color, Offset(w * (i * 2 + 0.5f), size.height), Offset(w * (i * 2 + 0.5f), size.height - h), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(modifier.clip(RoundedCornerShape(corner)).background(Lumen.colors.surfaceHigh.copy(alpha = a)))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Lumen.colors.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = Lumen.colors.textDim)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = Lumen.type.title, color = Lumen.colors.text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Lumen.type.small, color = Lumen.colors.textDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

fun Modifier.fadingEdge(color: Color, top: Boolean = true): Modifier = this.background(
    Brush.verticalGradient(if (top) listOf(color, Color.Transparent) else listOf(Color.Transparent, color)),
)

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTotal(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}
