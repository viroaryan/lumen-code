// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import app.lumen.engine.SourceHealth

import app.lumen.ui.Special
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import app.lumen.data.HomeSection
import app.lumen.data.Track
import app.lumen.ui.LocalApp
import app.lumen.ui.TrackMenuRequest
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.CollectionCard
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.LumenLogo
import app.lumen.ui.components.SectionHeader
import app.lumen.ui.components.ShelfArrangement
import app.lumen.ui.components.Skeleton
import app.lumen.ui.components.TrackCard
import app.lumen.ui.components.VideoCard
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.LButton
import java.util.Calendar

@Composable
fun HomeScreen(contentPadding: PaddingValues) {
    val app = LocalApp.current
    val history by remember { Graph.store.state.map { it.history.take(6) }.distinctUntilChanged() }.collectAsState(Graph.store.current.history.take(6))
    val onRepeat by remember {
        Graph.store.state.map { s ->
            s.history.filter { (s.plays[it.id] ?: 0) >= 2 }.sortedByDescending { s.plays[it.id] ?: 0 }.take(20)
        }.distinctUntilChanged()
    }.collectAsState(emptyList())
    // The "jump back in" grid is the likeliest next tap: look those streams up in the background.
    LaunchedEffect(history) { history.take(4).forEach(Graph.repo::prefetch) }
    val sections by Graph.home.sections.collectAsState()
    val loading by Graph.home.loading.collectAsState()
    val failed by Graph.home.failed.collectAsState()
    val down by SourceHealth.down.collectAsState()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { if (!loading) pulled = false }

    PullToRefreshBox(
        isRefreshing = pulled && loading,
        onRefresh = { pulled = true; Graph.home.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LumenLogo(Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(greeting(), style = Lumen.type.title, color = Lumen.colors.text, modifier = Modifier.weight(1f))
                    LIconButton(LIcons.History, { app.openSpecial(Special.HISTORY) }, contentDescription = "History")
                    LIconButton(LIcons.Settings, { app.openSettings() }, contentDescription = "Settings")
                }
            }

            if (down.isNotEmpty()) {
                item(key = "health") {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Lumen.colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(LIcons.Wifi, null, Modifier.size(18.dp), tint = Lumen.colors.textDim)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${down.joinToString(" & ") { it.label }} is acting up right now. Music keeps playing from the other sources.",
                            style = Lumen.type.small, color = Lumen.colors.textDim,
                        )
                    }
                }
            }
            if (history.isNotEmpty()) {
                item(key = "quick") { QuickPicks(history) }
            }
            if (onRepeat.size >= 3) {
                item(key = "repeat", contentType = "shelf") { Shelf(HomeSection("On repeat", tracks = onRepeat)) }
            }

            when {
                sections.isNotEmpty() -> sections.forEachIndexed { i, section ->
                    item(key = "shelf:$i:" + section.title, contentType = "shelf") { Shelf(section) }
                }
                failed -> item {
                    EmptyState(LIcons.Wifi, "You're offline", "Connect to the internet for new music. Your downloads still play.") {
                        LButton("Try again", { Graph.home.refresh() }, style = ButtonStyle.Secondary)
                    }
                }
                else -> items(3) { ShelfSkeleton() }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

@Composable
private fun QuickPicks(tracks: List<Track>) {
    val c = Lumen.colors
    val app = LocalApp.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tracks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { track ->
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp)).background(c.surface)
                            // Long-press opens the same menu as everywhere else: like, add to playlist, download…
                            .pressable(
                                { Graph.player.play(tracks, tracks.indexOf(track)) },
                                onLongClick = { app.trackMenu = TrackMenuRequest(track) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(track.artwork, Modifier.size(56.dp), corner = 0.dp)
                        Text(
                            track.title, style = Lumen.type.label, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Shelf(section: HomeSection) {
    val app = LocalApp.current
    val tracks = remember(section) { section.tracks.distinctBy { it.id } }
    val collections = remember(section) { section.collections.distinctBy { it.id } }
    Column(Modifier.padding(top = 22.dp)) {
        if (section.subtitle.isNotBlank()) {
            Text(
                section.subtitle.uppercase(), style = Lumen.type.micro, color = Lumen.colors.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        SectionHeader(section.title)
        Spacer(Modifier.height(10.dp))
        if (section.style == HomeSection.STYLE_VIDEO) {
            // Videos play on their own; autoplay follows with related ones.
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
                items(tracks, key = { it.id }, contentType = { "video" }) { video ->
                    VideoCard(video, onClick = { Graph.player.play(listOf(video)) })
                }
            }
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { i, track ->
                TrackCard(track, onClick = { Graph.player.play(tracks, i) })
            }
            items(collections, key = { it.id }, contentType = { "collection" }) { col ->
                CollectionCard(col, onClick = { app.openCollection(col) })
            }
        }
    }
}

@Composable
private fun ShelfSkeleton() {
    Column(Modifier.padding(top = 24.dp)) {
        Skeleton(Modifier.padding(horizontal = 20.dp).size(180.dp, 20.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
            repeat(3) {
                Column {
                    Skeleton(Modifier.size(150.dp), corner = 10.dp)
                    Spacer(Modifier.height(8.dp))
                    Skeleton(Modifier.size(110.dp, 14.dp))
                }
            }
        }
    }
}
