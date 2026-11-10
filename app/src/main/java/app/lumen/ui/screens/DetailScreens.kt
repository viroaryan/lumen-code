// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import app.lumen.ui.Routes
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.data.CollectionDetail
import app.lumen.data.Kind
import app.lumen.data.Track
import app.lumen.ui.CollectionCache
import app.lumen.ui.LocalApp
import app.lumen.ui.Special
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.CoverEditor
import app.lumen.ui.components.pressable
import app.lumen.ui.components.reorderable
import app.lumen.ui.components.rememberReorderState
import app.lumen.data.CoverKeys
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.CollectionCard
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LButton
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.PlayButton
import app.lumen.ui.components.SectionHeader
import app.lumen.ui.components.ShelfArrangement
import app.lumen.ui.components.Skeleton
import app.lumen.ui.components.TextInputDialog
import app.lumen.ui.components.ConfirmDialog
import app.lumen.ui.components.Chip
import app.lumen.ui.sharePlaylist
import app.lumen.ui.components.TrackRow
import app.lumen.ui.components.formatTotal
import app.lumen.ui.components.sourceTag
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import androidx.compose.ui.graphics.vector.ImageVector

private enum class ListSort(val label: String) { DEFAULT("Default order"), TITLE("Title"), ARTIST("Artist"), LONGEST("Longest") }

/** Shared layout for every list of songs: big artwork, title, actions, then the songs. */
@Composable
private fun TrackListLayout(
    title: String,
    subtitle: String,
    artwork: String?,
    tracks: List<Track>,
    contentPadding: PaddingValues,
    loading: Boolean = false,
    circleArt: Boolean = false,
    headerIcon: ImageVector? = null,
    playlistId: String? = null,
    numbered: Boolean = false,
    /** Library list key ([app.lumen.data.CoverKeys]): turns on drag-to-reorder and a custom cover. */
    listKey: String? = null,
    actions: @Composable () -> Unit = {},
    footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    empty: @Composable () -> Unit = {},
) {
    val app = LocalApp.current
    val c = Lumen.colors
    val player by Graph.player.state.collectAsState()
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 600 } }
    // Find-in-list and sorting, for long playlists. Playing follows what's on screen.
    var finding by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ListSort.DEFAULT) }
    val allTracks = tracks
    @Suppress("NAME_SHADOWING")
    val tracks = remember(allTracks, filter, sort) {
        val q = filter.trim()
        val found = if (q.isEmpty()) allTracks else allTracks.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album?.contains(q, true) == true
        }
        when (sort) {
            ListSort.DEFAULT -> found
            ListSort.TITLE -> found.sortedBy { it.title.lowercase() }
            ListSort.ARTIST -> found.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            ListSort.LONGEST -> found.sortedByDescending { it.durationMs }
        }
    }
    // Long-press-and-drag reordering: only on the user's own lists, shown in their own order, unfiltered.
    val distinctIds = remember(allTracks) { allTracks.distinctBy { it.id }.size == allTracks.size }
    val canReorder = listKey != null && !loading && filter.isBlank() && sort == ListSort.DEFAULT && allTracks.size > 1 && distinctIds
    var working by remember(allTracks) { mutableStateOf<List<Track>?>(null) }
    val shown = if (canReorder) working ?: tracks else tracks
    val reorder = rememberReorderState(list)
    reorder.canMoveTo = { k -> k is String && k.startsWith("t:") }
    reorder.onMove = { from, to ->
        val l = (working ?: tracks).toMutableList()
        val fi = l.indexOfFirst { "t:${it.id}" == from }
        val ti = l.indexOfFirst { "t:${it.id}" == to }
        if (fi < 0 || ti < 0) false else { l.add(ti, l.removeAt(fi)); working = l; true }
    }
    reorder.onEnd = {
        val w = working
        if (w != null && listKey != null && w.map { it.id } != allTracks.map { it.id }) Graph.store.reorder(listKey, w.map { it.id })
    }

    val covers by remember { Graph.store.state.map { it.covers }.distinctUntilChanged() }.collectAsState(Graph.store.current.covers)
    val coverPath = listKey?.let { covers[it] }
    val cover = remember(coverPath) { coverPath?.takeIf { java.io.File(it).exists() } }
    var coverMenu by remember { mutableStateOf(false) }
    if (listKey != null) CoverEditor(listKey, cover != null, coverMenu) { coverMenu = false }

    val playingThis = player.isPlaying && tracks.any { it.id == player.current?.id }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(c.surfaceHigh, c.bg)))
                        .statusBarsPadding()
                        .padding(top = 56.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Read the scroll position in the draw phase only: scrolling no longer recomposes the header.
                    val shrink: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
                        val s = if (list.firstVisibleItemIndex == 0) (1f - list.firstVisibleItemScrollOffset / 1200f).coerceIn(0.6f, 1f) else 0.6f
                        scaleX = s; scaleY = s
                    }
                    Box(
                        Modifier.size(220.dp).graphicsLayer(shrink)
                            .then(if (listKey != null) Modifier.pressable({ coverMenu = true }) else Modifier),
                    ) {
                        when {
                            cover != null -> Artwork(cover, Modifier.fillMaxSize(), corner = 12.dp)
                            headerIcon != null -> Box(
                                Modifier.fillMaxSize().background(c.text, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(headerIcon, null, Modifier.size(72.dp), tint = c.bg) }
                            else -> Artwork(
                                artwork, Modifier.fillMaxSize(),
                                corner = 12.dp, circle = circleArt, placeholder = if (circleArt) LIcons.Artist else LIcons.Album,
                            )
                        }
                        if (listKey != null) {
                            // A small pencil says the picture can be changed.
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp)
                                    .background(c.bg.copy(alpha = 0.88f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(LIcons.Edit, "Change cover", Modifier.size(18.dp), tint = c.text) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        title, style = Lumen.type.display, color = c.text, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(subtitle, style = Lumen.type.small, color = c.textDim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions()
                    if (allTracks.size >= 8) {
                        LIconButton(LIcons.Search, { finding = !finding; if (!finding) { filter = ""; sort = ListSort.DEFAULT } }, tint = if (finding) c.text else c.textDim, contentDescription = "Find in list")
                    }
                    Spacer(Modifier.weight(1f))
                    LIconButton(LIcons.Shuffle, { Graph.player.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty(), contentDescription = "Shuffle")
                    Spacer(Modifier.width(8.dp))
                    PlayButton(playingThis, {
                        if (tracks.any { it.id == player.current?.id }) Graph.player.toggle() else Graph.player.play(shown)
                    })
                }
            }
            if (canReorder) {
                item(key = "reorder-hint") {
                    Text(
                        "Hold a song, then drag it to move it", style = Lumen.type.small, color = c.textFaint,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    )
                }
            }
            if (finding) {
                item(key = "find") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = filter, onValueChange = { filter = it }, singleLine = true,
                            placeholder = { Text("Find in ${allTracks.size} songs", style = Lumen.type.body, color = c.textFaint) },
                            leadingIcon = { Icon(LIcons.Search, null, Modifier.size(20.dp), tint = c.textDim) },
                            trailingIcon = { if (filter.isNotEmpty()) LIconButton(LIcons.Close, { filter = "" }, tint = c.textDim, iconSize = 18.dp) },
                            textStyle = Lumen.type.body.copy(color = c.text),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = c.text, unfocusedBorderColor = c.line, cursorColor = c.text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyRow(contentPadding = PaddingValues(vertical = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            items(ListSort.entries) { s -> Chip(s.label, sort == s, { sort = s }) }
                        }
                        if (filter.isNotBlank()) Text("${tracks.size} of ${allTracks.size} songs", style = Lumen.type.small, color = c.textDim, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            if (loading) {
                items(8) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Skeleton(Modifier.size(50.dp), corner = 6.dp)
                        Spacer(Modifier.width(14.dp))
                        Column { Skeleton(Modifier.size(180.dp, 14.dp)); Spacer(Modifier.height(6.dp)); Skeleton(Modifier.size(110.dp, 12.dp)) }
                    }
                }
            } else if (allTracks.isEmpty()) {
                item { empty() }
            } else if (tracks.isEmpty()) {
                item { Text("No songs match \"$filter\"", style = Lumen.type.body, color = c.textDim, modifier = Modifier.padding(24.dp)) }
            } else {
                itemsIndexed(shown, key = { i, t -> if (canReorder) "t:${t.id}" else "$i${t.id}" }) { i, t ->
                    val key = "t:${t.id}"
                    val dragging = canReorder && reorder.draggingKey == key
                    TrackRow(
                        t, onClick = { Graph.player.play(shown, i) }, index = i, showArt = !numbered, playlistId = playlistId,
                        longPressMenu = !canReorder,
                        dragHandle = Modifier.reorderable(reorder, key, canReorder),
                        modifier = when {
                            !canReorder -> Modifier
                            dragging -> Modifier.zIndex(1f)
                                .graphicsLayer {
                                    translationY = reorder.offset; scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 24f
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                                }
                                .background(c.surfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            else -> Modifier.animateItem()
                        },
                    )
                }
            }
            footer()
            item { Spacer(Modifier.height(24.dp)) }
        }

        // Floating top bar that fills in as the header scrolls away.
        Row(
            Modifier.fillMaxWidth()
                .background(if (collapsed) c.bg else androidx.compose.ui.graphics.Color.Transparent)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LIconButton(LIcons.Back, { app.nav.popBackStack() }, contentDescription = "Back")
            if (collapsed) Text(title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

private fun summary(tracks: List<Track>) = "${tracks.size} songs" +
    tracks.sumOf { it.durationMs }.takeIf { it > 0 }?.let { " · ${formatTotal(it)}" }.orEmpty()

@Composable
fun CollectionScreen(id: String, contentPadding: PaddingValues) {
    val app = LocalApp.current
    val collection = remember(id) { CollectionCache[id] }
    if (collection == null) {
        LaunchedEffect(Unit) { app.nav.popBackStack() }
        return
    }
    var detail by remember(id) { mutableStateOf<CollectionDetail?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val library by Graph.store.state.collectAsState()
    LaunchedEffect(id, attempt) {
        failed = false
        runCatching { Graph.repo.collection(collection) }.onSuccess { detail = it }.onFailure { failed = true }
    }
    val shown = detail?.collection ?: collection
    val tracks = detail?.tracks.orEmpty()
    val saved = library.savedCollections.any { it.id == collection.id }
    val isArtist = collection.kind == Kind.ARTIST
    val subtitle = listOfNotNull(
        shown.subtitle.ifBlank { null }.takeUnless { isArtist },
        sourceTag(shown.source),
        if (detail != null && !isArtist) summary(tracks) else null,
    ).joinToString(" · ")

    TrackListLayout(
        title = shown.title,
        subtitle = if (isArtist) "Artist" + (sourceTag(shown.source)?.let { " · $it" } ?: "") else subtitle,
        artwork = shown.artwork,
        tracks = tracks,
        contentPadding = contentPadding,
        loading = detail == null && !failed,
        circleArt = isArtist,
        numbered = collection.kind == Kind.ALBUM,
        actions = {
            LIconButton(
                if (saved) LIcons.HeartFilled else LIcons.Heart,
                { Graph.store.toggleSaved(shown); app.toast(if (saved) "Removed from Library" else "Saved to Library") },
                contentDescription = "Save",
            )
            if (tracks.any { Graph.downloads.canDownload(it) }) {
                val allDownloaded = tracks.all { it.id in library.downloads }
                LIconButton(
                    if (allDownloaded) LIcons.Downloaded else LIcons.Download,
                    { if (!allDownloaded) { val n = Graph.downloads.download(tracks); if (n > 0) app.toast("Downloading $n songs") } },
                    contentDescription = "Download",
                )
            }
            LIconButton(LIcons.PlusCircle, { if (tracks.isNotEmpty()) app.addToPlaylist = tracks }, contentDescription = "Add to playlist")
            if (tracks.isNotEmpty()) {
                LIconButton(LIcons.AddToQueue, { Graph.player.addToQueue(tracks); app.toast("Added ${tracks.size} songs to queue") }, contentDescription = "Add to queue")
            }
        },
        footer = {
            val albums = detail?.albums.orEmpty()
            if (albums.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 24.dp)) {
                        SectionHeader("Albums")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
                            items(albums, key = { it.id }) { a -> CollectionCard(a, onClick = { app.openCollection(a) }) }
                        }
                    }
                }
            }
        },
        empty = {
            if (failed) EmptyState(LIcons.Wifi, "Couldn't load this", "Check your connection and try again.") {
                LButton("Try again", { attempt++ }, style = ButtonStyle.Secondary)
            } else EmptyState(LIcons.Note, "Nothing in here yet", "This ${collection.kind} has no playable songs right now.")
        },
    )
}

@Composable
fun UserPlaylistScreen(id: String, contentPadding: PaddingValues) {
    val app = LocalApp.current
    val library by Graph.store.state.collectAsState()
    val playlist = library.playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (playlist == null) {
        LaunchedEffect(Unit) { app.nav.popBackStack() }
        return
    }
    if (renaming) {
        TextInputDialog("Rename playlist", playlist.name, "Save", onDismiss = { renaming = false }) {
            Graph.store.renamePlaylist(id, it); renaming = false
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete \"${playlist.name}\"?",
            "The playlist and its ${playlist.tracks.size} songs will be removed from your library. Tip: share it first to keep a copy.",
            "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            val removed = Graph.store.deletePlaylist(id)
            app.nav.popBackStack()
            if (removed != null) app.toast("Playlist deleted", "Undo") { Graph.store.restorePlaylist(removed.first, removed.second) }
        }
    }
    TrackListLayout(
        title = playlist.name,
        subtitle = "Your playlist · " + summary(playlist.tracks),
        artwork = playlist.tracks.firstOrNull()?.artwork,
        tracks = playlist.tracks,
        contentPadding = contentPadding,
        playlistId = id,
        listKey = CoverKeys.playlist(id),
        headerIcon = if (playlist.tracks.isEmpty()) LIcons.Playlist else null,
        actions = {
            LIconButton(LIcons.Download, { val n = Graph.downloads.download(playlist.tracks); app.toast(if (n > 0) "Downloading $n songs" else "Nothing new to download") }, contentDescription = "Download")
            LIconButton(LIcons.Share, {
                if (playlist.tracks.isEmpty()) app.toast("Add some songs before sharing")
                else app.toast(sharePlaylist(context, playlist.name, playlist.tracks))
            }, contentDescription = "Share playlist")
            Box {
                LIconButton(LIcons.More, { menu = true }, contentDescription = "More")
                DropdownMenu(menu, { menu = false }, containerColor = Lumen.colors.surface) {
                    DropdownMenuItem({ Text("Rename", style = Lumen.type.body) }, { menu = false; renaming = true }, leadingIcon = { Icon(LIcons.Edit, null) })
                    DropdownMenuItem({ Text("Play next", style = Lumen.type.body) }, { menu = false; Graph.player.playNext(playlist.tracks); app.toast("${playlist.tracks.size} songs play next") }, leadingIcon = { Icon(LIcons.PlayNext, null) })
                    DropdownMenuItem({ Text("Add to queue", style = Lumen.type.body) }, { menu = false; Graph.player.addToQueue(playlist.tracks); app.toast("Added ${playlist.tracks.size} songs to queue") }, leadingIcon = { Icon(LIcons.AddToQueue, null) })
                    DropdownMenuItem({ Text("Delete playlist", style = Lumen.type.body) }, {
                        menu = false; confirmDelete = true
                    }, leadingIcon = { Icon(LIcons.Trash, null) })
                }
            }
        },
        empty = {
            EmptyState(LIcons.Search, "Let's find something for your playlist", "Use the menu on any song to add it here.") {
                LButton("Find songs", { app.nav.navigate(Routes.SEARCH) }, style = ButtonStyle.Secondary)
            }
        },
    )
}

@Composable
fun SpecialScreen(kind: String, contentPadding: PaddingValues) {
    val app = LocalApp.current
    val context = LocalContext.current
    val library by Graph.store.state.collectAsState()
    when (kind) {
        Special.LIKED -> TrackListLayout(
            "Liked Songs", summary(library.liked), null, library.liked, contentPadding, headerIcon = LIcons.HeartFilled, listKey = CoverKeys.LIKED,
            actions = {
                LIconButton(LIcons.Download, { val n = Graph.downloads.download(library.liked); app.toast(if (n > 0) "Downloading $n songs" else "Nothing new to download") }, contentDescription = "Download")
                if (library.liked.isNotEmpty()) {
                    LIconButton(LIcons.Share, { app.toast(sharePlaylist(context, "Liked Songs", library.liked)) }, contentDescription = "Share")
                }
            },
            empty = { EmptyState(LIcons.Heart, "Songs you like appear here", "Save songs by tapping the heart.") },
        )
        Special.DOWNLOADS -> {
            val tracks = library.downloads.values.toList()
            val active by Graph.downloads.progress.collectAsState()
            val waiting = maxOf(active.size, Graph.downloads.pendingCount())
            TrackListLayout(
                "Downloads", summary(tracks) + " · offline" + (if (waiting > 0) " · $waiting downloading" else ""), null, tracks, contentPadding, headerIcon = LIcons.Downloaded,
                listKey = CoverKeys.DOWNLOADS,
                actions = {
                    if (waiting > 0) {
                        LButton("Stop all", { Graph.downloads.cancelAll(); app.toast("Downloads stopped") }, style = ButtonStyle.Secondary)
                    }
                },
                empty = { EmptyState(LIcons.Download, "No downloads yet", "Download songs and playlists to listen without internet.") },
            )
        }
        Special.HISTORY -> TrackListLayout(
            "Recently played", "${library.history.size} songs", null, library.history, contentPadding, headerIcon = LIcons.History,
            listKey = CoverKeys.HISTORY,
            actions = {
                if (library.history.isNotEmpty()) LIconButton(LIcons.Trash, { Graph.store.clearHistory() }, contentDescription = "Clear history")
            },
            empty = { EmptyState(LIcons.History, "Nothing played yet", "Songs you play show up here.") },
        )
        Special.LOCAL -> LocalScreen(contentPadding)
    }
}

@Composable
private fun LocalScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Graph.repo.localTracks(refresh = true) }
    }
    // The user's dragged order is applied on top of what the phone reports.
    val localOrder by remember { Graph.store.state.map { it.localOrder }.distinctUntilChanged() }.collectAsState(Graph.store.current.localOrder)
    val ordered = remember(tracks, localOrder) { tracks?.let { Graph.store.orderLocal(it) } }
    TrackListLayout(
        "On this device", ordered?.let { summary(it) } ?: "", null, ordered.orEmpty(), contentPadding,
        loading = granted && tracks == null, headerIcon = LIcons.Phone, listKey = CoverKeys.LOCAL,
        empty = {
            if (!granted) EmptyState(LIcons.Phone, "Play music from your phone", "Allow Lumen to read your music files.") {
                LButton("Allow access", { launcher.launch(permission) })
            } else EmptyState(LIcons.Note, "No music files found", "Songs saved on your phone will show up here.")
        },
    )
}
