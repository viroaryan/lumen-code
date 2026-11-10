// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import app.lumen.data.LyricLine
import app.lumen.data.Lyrics
import app.lumen.data.Track
import app.lumen.ui.components.Chip
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lyrics that follow the song word by word. When the source has real word timing it's used as is;
 * otherwise each word's moment is estimated from the line's length. If the song exists in several
 * scripts (हिन्दी and Latin, say) the listener picks one, and the choice is remembered.
 */
@Composable
internal fun LyricsPanel(track: Track) {
    val c = Lumen.colors
    var versions by remember(track.id) { mutableStateOf<List<Lyrics>?>(null) }
    LaunchedEffect(track.id) { versions = runCatching { Graph.repo.lyricsAll(track) }.getOrDefault(emptyList()) }
    val preferred by remember { Graph.store.state.map { it.settings.lyricsScript }.distinctUntilChanged() }.collectAsState(Graph.store.settings.lyricsScript)

    val all = versions
    if (all == null) { Text("Finding lyrics…", style = Lumen.type.body, color = c.textDim); return }
    if (all.isEmpty()) { Text("No lyrics for this song", style = Lumen.type.title, color = c.textDim, textAlign = TextAlign.Center); return }
    val l = all.firstOrNull { it.label == preferred } ?: all.first()

    // The player reports its position a few times a second; between reports the clock runs on frames,
    // so words light up smoothly.
    val progress by Graph.player.progress.collectAsState()
    val playing by remember { Graph.player.state.map { it.isPlaying to it.speed }.distinctUntilChanged() }.collectAsState(false to 1f)
    var now by remember { mutableLongStateOf(progress.positionMs) }
    LaunchedEffect(progress.positionMs, playing) {
        val base = progress.positionMs
        now = base
        if (playing.first && l.synced) {
            val t0 = withFrameNanos { it }
            while (true) { val t = withFrameNanos { it }; now = base + ((t - t0) / 1_000_000 * playing.second).toLong() }
        }
    }
    val active by remember(l) { derivedStateOf { if (l.synced) l.lines.indexOfLast { it.timeMs <= now + 200 }.coerceAtLeast(0) else -1 } }
    val list = rememberLazyListState()
    // Follows the song, but never yanks the list away while you're reading ahead with your finger.
    LaunchedEffect(active, l) { if (active >= 0 && !list.isScrollInProgress) list.animateScrollToItem((active - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize()) {
        if (all.size > 1) {
            LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(all) { v -> Chip(v.label, v === l, { Graph.store.updateSettings { it.copy(lyricsScript = v.label) } }) }
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            itemsIndexed(l.lines) { i, line ->
                val mod = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .then(if (l.synced) Modifier.pressable({ Graph.player.seekTo(line.timeMs) }) else Modifier)
                when {
                    !l.synced -> Text(line.text.ifBlank { " " }, style = Lumen.type.title, color = c.text, modifier = mod)
                    i == active && line.text.isNotBlank() -> ActiveLine(line, l.lines.getOrNull(i + 1)?.timeMs, { now }, c.text, c.textFaint, mod)
                    else -> Text(line.text.ifBlank { "♪" }, style = Lumen.type.title, color = if (i < active) c.textDim else c.textFaint, modifier = mod)
                }
            }
        }
    }
}

/** The line being sung: words already sung are bright, the rest wait in grey. */
@Composable
private fun ActiveLine(line: LyricLine, nextMs: Long?, now: () -> Long, sung: Color, waiting: Color, modifier: Modifier) {
    val words = remember(line, nextMs) { timedWords(line, nextMs) }
    val done by remember(words) { derivedStateOf { val t = now(); words.count { it.first <= t } } }
    Text(
        buildAnnotatedString {
            words.forEachIndexed { i, w -> withStyle(SpanStyle(color = if (i < done) sung else waiting)) { append(w.second) } }
        },
        style = Lumen.type.title, modifier = modifier,
    )
}

/** Each word with the moment it starts: from the source when it has them, else spread by length. */
private fun timedWords(line: LyricLine, nextMs: Long?): List<Pair<Long, String>> {
    if (line.words.isNotEmpty()) return line.words.map { it.timeMs to it.text }
    val parts = line.text.split(' ').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    val weights = parts.map { 180L + 70L * it.length }
    val natural = weights.sum()
    // A long gap before the next line is a pause or music, not slow singing.
    val gap = nextMs?.minus(line.timeMs)?.takeIf { it > 0 } ?: natural
    val span = (if (gap > natural * 1.6) natural * 1.2 else gap * 0.92).toLong().coerceAtLeast(300)
    var at = 0L
    return parts.mapIndexed { i, p ->
        val start = line.timeMs + at * span / natural
        at += weights[i]
        start to (if (i < parts.lastIndex) "$p " else p)
    }
}
