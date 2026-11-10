// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import app.lumen.data.CoverKeys
import app.lumen.data.Kind
import app.lumen.ui.LocalApp
import app.lumen.ui.Special
import app.lumen.ui.components.Chip
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LButton
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.LibraryRow
import app.lumen.ui.components.LumenLogo
import app.lumen.ui.components.TextInputDialog
import app.lumen.ui.components.sourceTag
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen

private enum class LibraryFilter(val label: String) { ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }

@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val app = LocalApp.current
    val c = Lumen.colors
    val library by Graph.store.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // A custom cover replaces the plain icon; one whose file has gone falls back to the icon.
    fun cover(key: String) = library.covers[key]?.takeIf { java.io.File(it).exists() }

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            val id = Graph.store.createPlaylist(name)
            creating = false
            app.openPlaylist(id)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LumenLogo(Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text("Your Library", style = Lumen.type.title, color = c.text, modifier = Modifier.weight(1f))
                LIconButton(LIcons.Plus, { creating = true }, contentDescription = "New playlist")
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries) { f -> Chip(f.label, filter == f, { filter = f }) }
            }
        }
        if (filter == LibraryFilter.ALL || filter == LibraryFilter.PLAYLISTS) {
            item {
                cover(CoverKeys.LIKED).let { art -> LibraryRow("Liked Songs", "${library.liked.size} songs", art, { app.openSpecial(Special.LIKED) }, icon = LIcons.HeartFilled.takeIf { art == null }) }
            }
            item {
                cover(CoverKeys.DOWNLOADS).let { art -> LibraryRow("Downloads", "${library.downloads.size} songs · plays offline", art, { app.openSpecial(Special.DOWNLOADS) }, icon = LIcons.Downloaded.takeIf { art == null }) }
            }
            item {
                cover(CoverKeys.LOCAL).let { art -> LibraryRow("On this device", "Music files on your phone", art, { app.openSpecial(Special.LOCAL) }, icon = LIcons.Phone.takeIf { art == null }) }
            }
            item {
                cover(CoverKeys.HISTORY).let { art -> LibraryRow("Recently played", "${library.history.size} songs", art, { app.openSpecial(Special.HISTORY) }, icon = LIcons.History.takeIf { art == null }) }
            }
            items(library.playlists, key = { it.id }) { p ->
                LibraryRow(p.name, "Playlist · ${p.tracks.size} songs", cover(CoverKeys.playlist(p.id)) ?: p.tracks.firstOrNull()?.artwork, { app.openPlaylist(p.id) })
            }
        }
        val saved = library.savedCollections.filter {
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.PLAYLISTS -> it.kind == Kind.PLAYLIST
                LibraryFilter.ALBUMS -> it.kind == Kind.ALBUM
                LibraryFilter.ARTISTS -> it.kind == Kind.ARTIST
            }
        }
        items(saved, key = { it.id }) { col ->
            val kind = col.kind.replaceFirstChar { it.uppercase() }
            LibraryRow(
                col.title, listOfNotNull(kind, col.subtitle.ifBlank { null }.takeIf { col.kind != Kind.ARTIST }, sourceTag(col.source)).joinToString(" · "),
                col.artwork, { app.openCollection(col) }, circle = col.kind == Kind.ARTIST,
            )
        }
        if (filter != LibraryFilter.ALL && filter != LibraryFilter.PLAYLISTS && saved.isEmpty()) {
            item {
                EmptyState(
                    if (filter == LibraryFilter.ARTISTS) LIcons.Artist else LIcons.Album,
                    "Nothing saved yet",
                    "Tap the heart on any ${filter.label.lowercase().removeSuffix("s")} to keep it here.",
                )
            }
        }
        if (filter == LibraryFilter.PLAYLISTS && library.playlists.isEmpty()) {
            item {
                EmptyState(LIcons.Playlist, "Make your first playlist", "It's easy, we'll help you.") {
                    LButton("Create playlist", { creating = true }, icon = LIcons.Plus)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
