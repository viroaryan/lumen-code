// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.lumen.Graph
import app.lumen.MainActivity
import app.lumen.data.Lyrics
import app.lumen.data.Track
import app.lumen.playback.SleepTimer
import app.lumen.ui.LocalApp
import app.lumen.ui.TrackMenuRequest
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.PlayButton
import app.lumen.ui.components.PlayingBars
import app.lumen.ui.components.TextInputDialog
import app.lumen.ui.components.formatDuration
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by Graph.player.state.collectAsState()
    val track = state.current ?: return
    val c = Lumen.colors
    // Only "is this song liked" — not the whole library, which changes with every play.
    val liked by remember(track.id) { Graph.store.state.map { s -> s.liked.any { it.id == track.id } }.distinctUntilChanged() }
        .collectAsState(Graph.store.isLiked(track.id))
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(62.dp)
            .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceHigh)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -120) Graph.player.next() else if (drag > 120) Graph.player.previous()
                        drag = 0f
                    },
                    // A cancelled swipe must snap back, or the bar would drift off screen bit by bit.
                    onDragCancel = { drag = 0f },
                    onHorizontalDrag = { _, d -> drag += d },
                )
            }
            .pressable(onOpen),
    ) {
        Row(
            Modifier.fillMaxSize().offset { IntOffset((drag * 0.4f).roundToInt(), 0) }.padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artwork, Modifier.size(46.dp), corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LIconButton(if (liked) LIcons.HeartFilled else LIcons.Heart, { Graph.player.toggleLike(track) }, iconSize = 22.dp, contentDescription = "Like")
            LIconButton(if (state.isPlaying) LIcons.Pause else LIcons.Play, { Graph.player.toggle() }, iconSize = 26.dp, contentDescription = "Play")
        }
        MiniProgress(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp).fillMaxWidth().height(2.dp))
    }
}

/** Reads playback position on its own, so only this thin bar redraws as the song plays. */
@Composable
private fun MiniProgress(modifier: Modifier) {
    val c = Lumen.colors
    val progress by Graph.player.progress.collectAsState()
    Canvas(modifier.clip(RoundedCornerShape(1.dp))) {
        val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
        drawRect(c.line)
        drawRect(c.text, size = size.copy(width = size.width * fraction))
    }
}

private enum class PlayerSheet { NONE, QUEUE, TIMER, SPEED }

@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val c = Lumen.colors
    val state by Graph.player.state.collectAsState()
    val likedIds by remember { Graph.store.state.map { s -> s.liked.map { it.id }.toHashSet() }.distinctUntilChanged() }
        .collectAsState(emptySet<String>())
    val sleep by Graph.player.sleep.collectAsState()
    val track = state.current
    val scope = rememberCoroutineScope()
    // Plain state updated synchronously; animating back runs once, after the finger lifts.
    var dragY by remember { mutableFloatStateOf(0f) }
    fun settle() {
        // Closing: let the exit animation carry the sheet down from where the finger left it. Springing
        // back up at the same time made it jump before disappearing.
        if (dragY > 260f) {
            onClose()
            // Reset once the sheet is off screen, in case it's reopened before it was fully removed.
            scope.launch { kotlinx.coroutines.delay(400); dragY = 0f }
            return
        }
        val from = dragY
        scope.launch { animate(from, 0f, animationSpec = tween(220)) { v, _ -> dragY = v } }
    }
    var showLyrics by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }

    BackHandler { if (showLyrics) showLyrics = false else onClose() }

    if (track == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val liked = track.id in likedIds

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.roundToInt()) }
            .background(c.bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                    onVerticalDrag = { change, d ->
                        change.consume()
                        dragY = (dragY + d).coerceAtLeast(0f)
                    },
                )
            },
    ) {
        // A soft wash of the artwork's colours behind the monochrome UI: a tiny copy (exact size, so the
        // cached full-size image isn't reused) stretched to full screen, softened further where blur exists.
        if (track.artwork != null) {
            val wash = remember(track.artwork) {
                ImageRequest.Builder(context).data(track.artwork).size(24).precision(Precision.EXACT)
                    .memoryCacheKey("wash:" + track.artwork).build()
            }
            AsyncImage(
                model = wash, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (c.isDark) 0.5f else 0.4f)
                    .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.35f), c.bg.copy(alpha = 0.85f), c.bg))))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LIconButton(LIcons.ChevronDown, onClose, modifier = Modifier.offset(x = (-10).dp), contentDescription = "Close")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = Lumen.type.micro, color = c.textDim)
                    Text(if (track.video) "YouTube video" else track.source.label, style = Lumen.type.label, color = c.text)
                }
                LIconButton(LIcons.More, { app.trackMenu = TrackMenuRequest(track) }, modifier = Modifier.offset(x = 10.dp), contentDescription = "More")
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsPanel(track)
                } else {
                    SwipeArtwork(track)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(track.title, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "title") { title ->
                        Text(title, style = Lumen.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        track.artist, style = Lumen.type.body, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.pressable({ onClose(); app.openArtist(track.artist, track.source) }),
                    )
                }
                LIconButton(if (liked) LIcons.HeartFilled else LIcons.Heart, { Graph.player.toggleLike(track) }, iconSize = 26.dp, contentDescription = "Like")
            }

            Spacer(Modifier.height(10.dp))
            SeekBar()

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(LIcons.Shuffle, state.shuffle) { Graph.player.toggleShuffle() }
                LIconButton(LIcons.Previous, { Graph.player.previous() }, size = 56.dp, iconSize = 30.dp, contentDescription = "Previous")
                PlayButton(state.isPlaying, { Graph.player.toggle() }, size = 72.dp)
                LIconButton(LIcons.Next, { Graph.player.next() }, size = 56.dp, iconSize = 30.dp, enabled = state.hasNext || state.repeat != Player.REPEAT_MODE_OFF, contentDescription = "Next")
                ToggleIcon(if (state.repeat == Player.REPEAT_MODE_ONE) LIcons.RepeatOne else LIcons.Repeat, state.repeat != Player.REPEAT_MODE_OFF) { Graph.player.cycleRepeat() }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(LIcons.Lyrics, showLyrics) { showLyrics = !showLyrics }
                ToggleIcon(LIcons.Moon, sleep != SleepTimer.Off) { sheet = PlayerSheet.TIMER }
                LIconButton(LIcons.Speed, { sheet = PlayerSheet.SPEED }, tint = if (state.speed != 1f) c.text else c.textDim, contentDescription = "Speed")
                LIconButton(LIcons.Pip, { (context as? MainActivity)?.enterPip() }, tint = c.textDim, contentDescription = "Picture in picture")
                ToggleIcon(LIcons.Queue, false) { sheet = PlayerSheet.QUEUE }
            }
        }
    }

    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet { sheet = PlayerSheet.NONE }
        PlayerSheet.TIMER -> TimerSheet { sheet = PlayerSheet.NONE }
        PlayerSheet.SPEED -> SpeedSheet(state.speed, onEqualizer = {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            runCatching { context.startActivity(intent) }.onFailure { app.toast("No equalizer on this phone") }
        }) { sheet = PlayerSheet.NONE }
        PlayerSheet.NONE -> Unit
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    val c = Lumen.colors
    Box(contentAlignment = Alignment.BottomCenter) {
        LIconButton(icon, onClick, tint = if (active) c.text else c.textDim)
        if (active) Box(Modifier.padding(bottom = 2.dp).size(4.dp).clip(CircleShape).background(c.text))
    }
}

@Composable
private fun SwipeArtwork(track: Track) {
    var drag by remember { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, label = "swipe")
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight - 24.dp)
        AnimatedContent(
            track,
            transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
            contentKey = { it.id },
            label = "art",
        ) { t ->
            Artwork(
                t.artwork,
                Modifier
                    .size(side)
                    .offset { IntOffset((shift * 0.5f).roundToInt(), 0) }
                    .shadow(24.dp, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (drag < -140) Graph.player.next() else if (drag > 140) Graph.player.previous()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                            onHorizontalDrag = { _, d -> drag += d },
                        )
                    },
                corner = 14.dp,
            )
        }
    }
}

@Composable
private fun SeekBar() {
    val c = Lumen.colors
    val progress by Graph.player.progress.collectAsState()
    val positionMs = progress.positionMs
    val durationMs = progress.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val value = dragging ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val thumb by animateFloatAsState(if (dragging != null) 9f else 6f, label = "thumb")
    Column {
        // Drawn by hand so the dot always sits exactly on the end of the filled line, centred, and is
        // never clipped at either edge.
        Canvas(
            Modifier.fillMaxWidth().height(32.dp)
                .pointerInput(duration) {
                    val inset = 9.dp.toPx()
                    fun at(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    detectTapGestures { o -> Graph.player.seekTo((at(o.x) * duration).toLong()) }
                }
                .pointerInput(duration) {
                    val inset = 9.dp.toPx()
                    fun at(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = at(o.x) },
                        onDragEnd = { dragging?.let { Graph.player.seekTo((it * duration).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ -> change.consume(); dragging = at(change.position.x) },
                    )
                },
        ) {
            val inset = 9.dp.toPx()
            val y = size.height / 2
            val left = inset
            val right = size.width - inset
            val x = left + (right - left) * value
            val line = 4.dp.toPx()
            drawLine(c.line, Offset(left, y), Offset(right, y), strokeWidth = line, cap = StrokeCap.Round)
            drawLine(c.text, Offset(left, y), Offset(x, y), strokeWidth = line, cap = StrokeCap.Round)
            drawCircle(c.text, thumb.dp.toPx(), Offset(x, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(dragging?.let { (it * duration).toLong() } ?: positionMs), style = Lumen.type.small, color = c.textDim)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = Lumen.type.small, color = c.textDim)
        }
    }
}

@Composable
private fun LyricsPanel(track: Track) {
    val c = Lumen.colors
    val positionMs = Graph.player.progress.collectAsState().value.positionMs
    var lyrics by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }
    LaunchedEffect(track.id) {
        lyrics = runCatching { Graph.repo.lyrics(track) }.getOrNull()
        loading = false
    }
    val list = rememberLazyListState()
    val l = lyrics
    when {
        loading -> Text("Finding lyrics…", style = Lumen.type.body, color = c.textDim)
        l == null || l.lines.isEmpty() -> Text("No lyrics for this song", style = Lumen.type.title, color = c.textDim, textAlign = TextAlign.Center)
        else -> {
            val active = if (l.synced) l.lines.indexOfLast { it.timeMs <= positionMs + 300 }.coerceAtLeast(0) else -1
            // Follows the song, but never yanks the list away while you're reading ahead with your finger.
            LaunchedEffect(active) { if (active >= 0 && !list.isScrollInProgress) list.animateScrollToItem((active - 2).coerceAtLeast(0)) }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                itemsIndexed(l.lines) { i, line ->
                    val past = l.synced && i < active
                    Text(
                        line.text.ifBlank { "♪" },
                        style = Lumen.type.title,
                        color = when {
                            !l.synced -> c.text
                            i == active -> c.text
                            past -> c.textDim
                            else -> c.textFaint
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .then(if (l.synced) Modifier.pressable({ Graph.player.seekTo(line.timeMs) }) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) =
    Text(text, style = Lumen.type.title, color = Lumen.colors.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val c = Lumen.colors
    val app = LocalApp.current
    val state by Graph.player.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextInputDialog("Save queue as playlist", "My queue", "Save", onDismiss = { naming = false }) { name ->
            val id = Graph.store.createPlaylist(name, state.queue)
            naming = false
            onDismiss()
            app.toast("Saved ${state.queue.distinctBy { it.id }.size} songs to \"$name\"", "Open") { app.openPlaylist(id) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            Box(Modifier.weight(1f)) { SheetTitle("Queue · ${state.queue.size}") }
            if (state.queue.size > 1) {
                Text("Save", style = Lumen.type.label, color = c.textDim, modifier = Modifier.pressable({ naming = true }).padding(8.dp))
            }
            if (state.index < state.queue.lastIndex) {
                Text("Clear", style = Lumen.type.label, color = c.textDim, modifier = Modifier.pressable({ Graph.player.clearUpcoming() }).padding(8.dp))
            }
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            itemsIndexed(state.queue, key = { i, t -> "$i${t.id}" }) { i, t ->
                val current = i == state.index
                if (i == state.index + 1) {
                    Text("Next up", style = Lumen.type.micro, color = c.textDim, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
                }
                Row(
                    Modifier.fillMaxWidth().pressable({ Graph.player.skipTo(i) }).padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                        .alpha(if (i < state.index) 0.45f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Artwork(t.artwork, Modifier.size(44.dp), corner = 6.dp)
                        if (current) Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(c.bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            PlayingBars(playing = state.isPlaying)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = if (current) Lumen.type.bodyStrong else Lumen.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i > state.index + 1) LIconButton(LIcons.Up, { Graph.player.moveQueueItem(i, i - 1) }, tint = c.textDim, iconSize = 18.dp)
                    if (!current) LIconButton(LIcons.Close, { Graph.player.removeQueueItem(i) }, tint = c.textDim, iconSize = 18.dp)
                }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colors
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f))
        if (selected) Icon(LIcons.Check, null, Modifier.size(20.dp), tint = c.text)
    }
}

@Composable
private fun TimerSheet(onDismiss: () -> Unit) {
    val sleep by Graph.player.sleep.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lumen.colors.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetTitle("Sleep timer")
            (sleep as? SleepTimer.At)?.let {
                val left = ((it.endsAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                Text("Stops in about $left min", style = Lumen.type.small, color = Lumen.colors.textDim, modifier = Modifier.padding(horizontal = 24.dp))
            }
            listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                OptionRow("$m minutes", false) { Graph.player.setSleepTimer(m); onDismiss() }
            }
            OptionRow("End of song", sleep == SleepTimer.EndOfTrack) { Graph.player.setSleepTimer(0); onDismiss() }
            if (sleep != SleepTimer.Off) OptionRow("Turn off timer", false) { Graph.player.setSleepTimer(null); onDismiss() }
        }
    }
}

@Composable
private fun SpeedSheet(current: Float, onEqualizer: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lumen.colors.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetTitle("Playback speed")
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                OptionRow(if (s == 1f) "Normal" else "${s}×", abs(current - s) < 0.01f) { Graph.player.setSpeed(s); onDismiss() }
            }
            Row(
                Modifier.fillMaxWidth().pressable({ onEqualizer(); onDismiss() }).padding(horizontal = 24.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Equalizer, null, Modifier.size(20.dp), tint = Lumen.colors.text)
                Spacer(Modifier.width(14.dp))
                Text("Equalizer", style = Lumen.type.body, color = Lumen.colors.text)
            }
        }
    }
}

/** What the picture-in-picture window shows: the artwork, with the song underneath. */
@Composable
fun PipContent() {
    val state by Graph.player.state.collectAsState()
    val track = state.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (track?.artwork != null) {
            AsyncImage(track.artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            app.lumen.ui.components.LumenLogo(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(8.dp),
        ) {
            Column {
                Text(track?.title.orEmpty(), style = Lumen.type.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artist.orEmpty(), style = Lumen.type.micro, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
    }
}
