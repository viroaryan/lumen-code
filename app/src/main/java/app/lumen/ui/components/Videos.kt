// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import app.lumen.data.Track
import app.lumen.ui.LocalApp
import app.lumen.ui.TrackMenuRequest
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Length printed on the thumbnail, like on YouTube. */
@Composable
private fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(formatDuration(ms), style = Lumen.type.micro.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = Color.White)
    }
}

/** How far you got in a long video, as a thin bar along the thumbnail. */
@Composable
private fun ResumeBar(track: Track, modifier: Modifier = Modifier) {
    val position by remember(track.id) { Graph.store.memory.map { it.resume[track.id] }.distinctUntilChanged() }.collectAsState(null)
    val p = position ?: return
    if (track.durationMs <= 0) return
    val fraction = (p.toFloat() / track.durationMs).coerceIn(0.02f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color(0xFFE53935)))
    }
}

@Composable
private fun Thumbnail(track: Track, modifier: Modifier, corner: Dp) {
    Box(modifier.clip(RoundedCornerShape(corner))) {
        Artwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 0.dp, placeholder = LIcons.Video)
        if (track.durationMs > 0) DurationBadge(track.durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        ResumeBar(track, Modifier.align(Alignment.BottomStart))
    }
}

/** A wide card for home and search shelves: thumbnail, title, channel. */
@Composable
fun VideoCard(track: Track, onClick: () -> Unit, width: Dp = 248.dp) {
    val app = LocalApp.current
    Column(Modifier.width(width).pressable({ Graph.repo.prefetch(track, urgent = true); onClick() }, onLongClick = { app.trackMenu = TrackMenuRequest(track) })) {
        Thumbnail(track, Modifier.fillMaxWidth(), 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = Lumen.type.bodyStrong, color = Lumen.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = Lumen.type.small, color = Lumen.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A video in a list: thumbnail on the left, two lines of title, channel and length. */
@Composable
fun VideoRow(track: Track, onClick: () -> Unit) {
    val c = Lumen.colors
    val app = LocalApp.current
    val playing by remember(track.id) {
        Graph.player.state.map { if (it.current?.id == track.id) it.isPlaying else null }.distinctUntilChanged()
    }.collectAsState(null)
    Row(
        Modifier.fillMaxWidth()
            .pressable({ Graph.repo.prefetch(track, urgent = true); onClick() }, onLongClick = { app.trackMenu = TrackMenuRequest(track) })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumbnail(track, Modifier.width(132.dp), 8.dp)
            if (playing != null) {
                Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    PlayingBars(playing = playing == true)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(track.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LIconButton(LIcons.More, { app.trackMenu = TrackMenuRequest(track) }, tint = c.textDim, iconSize = 20.dp)
    }
}

/** Placeholder rows while videos load. */
@Composable
fun VideoRowSkeleton() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(132.dp, 74.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column { Skeleton(Modifier.size(170.dp, 14.dp)); Spacer(Modifier.height(6.dp)); Skeleton(Modifier.size(100.dp, 12.dp)) }
    }
}
