// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.lumen.data.Collection
import app.lumen.data.CoverKeys
import app.lumen.data.Kind
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.ui.LocalApp
import app.lumen.ui.TrackMenuRequest
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen

/** One song in a list: artwork, title, artist and source, with a menu. */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playlistId: String? = null,
    showArt: Boolean = true,
    /** False in lists you can reorder: there a long press picks the song up instead (the ⋮ menu stays). */
    longPressMenu: Boolean = true,
    /**
     * The drag gesture of a reorderable list. It goes inside the tap handling so it sees the touch
     * first and owns it once the hold turns into a drag.
     */
    dragHandle: Modifier = Modifier,
) {
    val c = Lumen.colors
    val app = LocalApp.current
    // Each row listens only to facts about its own song, so a list doesn't redraw on every player event.
    val playing by remember(track.id) {
        Graph.player.state.map { if (it.current?.id == track.id) it.isPlaying else null }.distinctUntilChanged()
    }.collectAsState(null)
    val isCurrent = playing != null
    val downloaded by remember(track.id) { Graph.store.state.map { track.id in it.downloads }.distinctUntilChanged() }.collectAsState(false)
    val progress by remember(track.id) { Graph.downloads.progress.map { it[track.id] }.distinctUntilChanged() }.collectAsState(null)
    Row(
        modifier
            .fillMaxWidth()
            .pressable(
                { Graph.repo.prefetch(track, urgent = true); onClick() },
                // In a reorderable list the hold belongs to the drag (which handles it); a no-op here
                // still stops a long hold from also counting as a tap and starting the song.
                onLongClick = if (longPressMenu) ({ app.trackMenu = TrackMenuRequest(track, playlistId) }) else ({}),
            )
            .then(dragHandle)
            .padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null && !showArt) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) PlayingBars(playing = playing == true)
                else Text("${index + 1}", style = Lumen.type.small, color = c.textDim)
            }
        }
        if (showArt) {
            Box {
                Artwork(track.artwork, Modifier.size(50.dp), corner = 6.dp)
                if (isCurrent) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(c.bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        PlayingBars(playing = playing == true)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, style = if (isCurrent) Lumen.type.bodyStrong else Lumen.type.body, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(c.textDim).padding(horizontal = 4.dp)) {
                        Text("E", style = Lumen.type.micro, color = c.bg)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                val p = progress
                when {
                    p != null -> {
                        CircularProgressIndicator(progress = { p }, Modifier.size(12.dp), strokeWidth = 1.6.dp, color = c.text, trackColor = c.line)
                        Spacer(Modifier.width(6.dp))
                    }
                    downloaded -> {
                        Icon(LIcons.Downloaded, null, Modifier.size(13.dp), tint = c.text)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(
                    listOfNotNull(track.artist.ifBlank { null }, if (track.video) "Video" else sourceTag(track.source)).joinToString(" · "),
                    style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LIconButton(LIcons.More, { app.trackMenu = TrackMenuRequest(track, playlistId) }, tint = c.textDim, iconSize = 20.dp)
    }
}

fun sourceTag(source: Source): String? = when (source) {
    Source.YOUTUBE -> null
    Source.SAAVN -> "JioSaavn"
    Source.SOUNDCLOUD -> "SoundCloud"
    Source.LOCAL -> "Device"
}

/** Square card used in horizontal shelves. */
@Composable
fun TrackCard(track: Track, onClick: () -> Unit, width: Dp = 150.dp) {
    val app = LocalApp.current
    Column(Modifier.width(width).pressable(onClick, onLongClick = { app.trackMenu = TrackMenuRequest(track) })) {
        Artwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = Lumen.type.bodyStrong, color = Lumen.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = Lumen.type.small, color = Lumen.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CollectionCard(collection: Collection, onClick: () -> Unit, modifier: Modifier = Modifier.width(150.dp)) {
    val artist = collection.kind == Kind.ARTIST
    Column(modifier.pressable(onClick), horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
        Artwork(
            collection.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp, circle = artist,
            placeholder = if (artist) LIcons.Artist else LIcons.Album,
        )
        Spacer(Modifier.height(8.dp))
        Text(collection.title, style = Lumen.type.bodyStrong, color = Lumen.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            collection.subtitle.ifBlank { null }.takeUnless { artist },
            if (artist) "Artist" else sourceTag(collection.source),
        ).joinToString(" · ")
        Text(sub, style = Lumen.type.small, color = Lumen.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Row used for library entries: playlists, saved albums, artists. */
@Composable
fun LibraryRow(
    title: String,
    subtitle: String,
    artwork: String?,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    circle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Lumen.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = c.text)
            }
        } else {
            Artwork(artwork, Modifier.size(58.dp), corner = 8.dp, circle = circle, placeholder = if (circle) LIcons.Artist else LIcons.Playlist)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Lumen.colors.text)
        Spacer(Modifier.width(18.dp))
        Text(text, style = Lumen.type.body, color = Lumen.colors.text)
    }
}

/** Everything you can do with a song, as a bottom sheet. */
@Composable
fun TrackMenuSheet() {
    val app = LocalApp.current
    val request = app.trackMenu ?: return
    val track = request.track
    val context = LocalContext.current
    val library by Graph.store.state.collectAsState()
    val downloads by Graph.downloads.progress.collectAsState()
    val liked = library.liked.any { it.id == track.id }
    val downloaded = track.id in library.downloads
    val close = { app.trackMenu = null }
    val c = Lumen.colors

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artwork, Modifier.size(52.dp), corner = 6.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(track.artist, if (track.video) "YouTube video" else track.source.label).joinToString(" · "), style = Lumen.type.small, color = c.textDim, maxLines = 1)
                }
            }
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(c.line))
            MenuItem(if (liked) LIcons.HeartFilled else LIcons.Heart, if (liked) "Remove from Liked Songs" else "Add to Liked Songs") {
                Graph.store.toggleLike(track); close()
            }
            MenuItem(LIcons.PlusCircle, "Add to playlist") { app.addToPlaylist = listOf(track); close() }
            request.playlistId?.let { pid ->
                MenuItem(LIcons.Trash, "Remove from this playlist") { Graph.store.removeFromPlaylist(pid, track.id); close() }
            }
            MenuItem(LIcons.PlayNext, "Play next") { Graph.player.playNext(track); app.toast("Playing next"); close() }
            MenuItem(LIcons.AddToQueue, "Add to queue") { Graph.player.addToQueue(listOf(track)); app.toast("Added to queue"); close() }
            if (track.source != Source.LOCAL) {
                MenuItem(LIcons.Radio, if (track.video) "Play with related videos" else "Start radio") { Graph.player.startRadio(track); close() }
            }
            if (Graph.downloads.canDownload(track)) {
                when {
                    downloaded -> MenuItem(LIcons.Downloaded, "Remove download") { Graph.downloads.remove(track); close() }
                    track.id in downloads -> MenuItem(LIcons.Close, "Cancel download") { Graph.downloads.cancel(track.id); close() }
                    else -> MenuItem(LIcons.Download, "Download") { Graph.downloads.download(track); app.toast("Downloading"); close() }
                }
            }
            if (track.artist.isNotBlank()) {
                MenuItem(LIcons.Artist, "Go to artist") { close(); app.openArtist(track.artist, track.source) }
            }
            if (track.source != Source.LOCAL) {
                MenuItem(LIcons.Share, "Share") {
                    val link = when (track.source) {
                        Source.SAAVN -> "${track.title} · ${track.artist}"
                        else -> track.ref
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                    close()
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistSheet() {
    val app = LocalApp.current
    val tracks = app.addToPlaylist ?: return
    val library by Graph.store.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val close = { app.addToPlaylist = null }
    val c = Lumen.colors

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            Graph.store.createPlaylist(name, tracks)
            app.toast("Added to $name")
            creating = false
            close()
        }
    }
    ModalBottomSheet(onDismissRequest = close, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Add to playlist", style = Lumen.type.title, color = c.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                LButton("New playlist", { creating = true }, icon = LIcons.Plus)
            }
            LazyColumn(Modifier.padding(bottom = 16.dp)) {
                items(library.playlists, key = { it.id }) { p ->
                    LibraryRow(p.name, "${p.tracks.size} songs", library.covers[CoverKeys.playlist(p.id)] ?: p.tracks.firstOrNull()?.artwork, onClick = {
                        // Say plainly when nothing changed, instead of "Added" for songs already there.
                        val n = Graph.store.countNew(p.id, tracks)
                        Graph.store.addToPlaylist(p.id, tracks)
                        app.toast(
                            when {
                                n == 0 -> if (tracks.size == 1) "Already in ${p.name}" else "All already in ${p.name}"
                                tracks.size == 1 -> "Added to ${p.name}"
                                else -> "Added $n songs to ${p.name}"
                            },
                        )
                        close()
                    })
                }
            }
        }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val c = Lumen.colors
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = Lumen.type.title, color = c.text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = RoundedCornerShape(12.dp),
                textStyle = Lumen.type.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text, unfocusedBorderColor = c.line, cursorColor = c.text),
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(confirm, style = Lumen.type.label, color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Lumen.type.label, color = c.textDim) } },
    )
}

/** A yes/no question before anything that loses the user's work. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Lumen.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = Lumen.type.title, color = c.text) },
        text = { Text(body, style = Lumen.type.body, color = c.textDim) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = Lumen.type.label, color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Lumen.type.label, color = c.textDim) } },
    )
}

val ShelfArrangement = Arrangement.spacedBy(12.dp)
