// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import app.lumen.engine.SourceHealth
import app.lumen.ui.findLink

import app.lumen.ui.components.PlayButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import app.lumen.data.Track
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.LButton
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lumen.Graph
import app.lumen.data.Collection
import app.lumen.data.SearchResults
import app.lumen.data.Source
import app.lumen.ui.LocalApp
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.Chip
import app.lumen.ui.components.CollectionCard
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.SectionHeader
import app.lumen.ui.components.ShelfArrangement
import app.lumen.ui.components.Skeleton
import app.lumen.ui.components.TrackRow
import app.lumen.ui.components.VideoCard
import app.lumen.ui.components.VideoRow
import app.lumen.ui.components.VideoRowSkeleton
import app.lumen.ui.components.pressable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchTab(val label: String) { TOP("Top"), SONGS("Songs"), VIDEOS("Videos"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists") }

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var submitted by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var bySource by mutableStateOf<Map<Source, SearchResults>>(emptyMap())
        private set
    /** True until every source has answered; results already on screen stay usable meanwhile. */
    var loading by mutableStateOf(false)
        private set
    var tab by mutableStateOf(SearchTab.TOP)
    var source by mutableStateOf<Source?>(null)
        private set
    var results by mutableStateOf(SearchResults())
        private set
    /** Extra pages of songs, appended below the ranked results as you scroll. */
    var more by mutableStateOf<List<Track>>(emptyList())
        private set
    var loadingMore by mutableStateOf(false)
        private set
    private var moreRound = 0
    private var noMore = false
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun loadMore() {
        val q = submitted ?: return
        if (loading || loadingMore || noMore || source != null) return
        loadingMore = true
        viewModelScope.launch {
            moreRound++
            val next = runCatching { Graph.repo.moreTracks(q, moreRound) }.getOrDefault(emptyList())
            val known = (results.tracks + more).map { it.id }.toHashSet()
            val fresh = next.filter { known.add(it.id) }
            if (fresh.isEmpty()) noMore = true
            more = more + fresh
            loadingMore = false
        }
    }

    /** Extra pages of videos for the Videos tab. */
    var moreVideos by mutableStateOf<List<Track>>(emptyList())
        private set
    var loadingMoreVideos by mutableStateOf(false)
        private set
    private var noMoreVideos = false

    fun loadMoreVideos() {
        val q = submitted ?: return
        if (loading || loadingMoreVideos || noMoreVideos) return
        loadingMoreVideos = true
        viewModelScope.launch {
            val next = runCatching { Graph.repo.moreVideos(q) }.getOrDefault(emptyList())
            val known = (results.videos + moreVideos).map { it.id }.toHashSet()
            val fresh = next.filter { known.add(it.id) }
            if (fresh.isEmpty()) noMoreVideos = true
            moreVideos = moreVideos + fresh
            loadingMoreVideos = false
        }
    }

    private fun resetMore() {
        more = emptyList()
        moreRound = 0
        noMore = false
        loadingMore = false
        moreVideos = emptyList()
        noMoreVideos = false
        loadingMoreVideos = false
    }
    private val cache = object : LinkedHashMap<String, Map<Source, SearchResults>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Source, SearchResults>>?) = size > 12
    }

    fun selectSource(value: Source?) {
        source = value
        recompute()
    }

    private fun recompute() {
        results = source?.let { bySource[it] } ?: Graph.repo.merge(submitted.orEmpty(), bySource)
    }

    fun onQuery(value: String) {
        query = value
        if (submitted != null && value != submitted) submitted = null
        suggestJob?.cancel()
        // A pasted link gets an "Open" row instead of search suggestions.
        if (findLink(value) != null || app.lumen.data.PlaylistShare.find(value) != null) { suggestions = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(220)
            suggestions = runCatching { Graph.repo.suggestions(value) }.getOrDefault(emptyList())
        }
    }

    fun submit(value: String = query, startTab: SearchTab = SearchTab.TOP) {
        val q = value.trim()
        if (q.isEmpty()) return
        query = q
        submitted = q
        suggestions = emptyList()
        Graph.store.addSearch(q)
        suggestJob?.cancel()
        searchJob?.cancel()
        resetMore()
        source = null
        tab = startTab
        cache[q.lowercase()]?.let { cached ->
            bySource = cached
            loading = false
            recompute()
            return
        }
        loading = true
        bySource = emptyMap()
        recompute()
        searchJob = viewModelScope.launch {
            runCatching {
                var warmed = false
                Graph.repo.search(q).collect { partial ->
                    bySource = partial
                    recompute()
                    // Most taps land on the first few results: have their streams ready before the tap.
                    if (!warmed && Source.YOUTUBE in partial && results.tracks.size >= 3) {
                        warmed = true
                        results.tracks.take(3).forEach(Graph.repo::prefetch)
                    }
                }
            }
            loading = false
            if (bySource.values.any { it.tracks.isNotEmpty() || it.videos.isNotEmpty() }) cache[q.lowercase()] = bySource
        }
    }

    fun clear() {
        searchJob?.cancel()
        resetMore()
        query = ""
        submitted = null
        suggestions = emptyList()
        bySource = emptyMap()
        loading = false
        recompute()
    }
}

private val genres = listOf(
    "Bollywood", "Punjabi", "Pop", "Hip-hop", "Lo-fi", "Romance", "Chill", "Workout",
    "Focus", "Party", "Indie", "Rock", "Sad songs", "Devotional", "K-pop", "Jazz",
    "Classical", "EDM", "Tamil", "Telugu", "90s Hindi", "Ghazal", "Sufi", "Acoustic",
)

@Composable
fun SearchScreen(contentPadding: PaddingValues, vm: SearchViewModel = viewModel()) {
    val c = Lumen.colors
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val app = LocalApp.current
    val requester = androidx.compose.runtime.remember { FocusRequester() }
    var focused by androidx.compose.runtime.remember { mutableStateOf(false) }

    BackHandler(enabled = vm.query.isNotEmpty()) { vm.clear(); focus.clearFocus() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp)) {
            if (!focused && vm.query.isEmpty()) {
                Text("Search", style = Lumen.type.display, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            }
            Row(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Search, null, Modifier.size(22.dp), tint = c.textDim)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (vm.query.isEmpty()) Text("Songs, podcasts, videos or paste a link", style = Lumen.type.body, color = c.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = vm.query,
                        onValueChange = vm::onQuery,
                        singleLine = true,
                        textStyle = Lumen.type.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            // A pasted playlist code or YouTube / JioSaavn / SoundCloud link opens directly instead of searching.
                            if (app.openShared(vm.query)) vm.clear() else vm.submit()
                            keyboard?.hide(); focus.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused },
                    )
                }
                if (vm.query.isNotEmpty()) LIconButton(LIcons.Close, { vm.clear(); requester.requestFocus() }, tint = c.textDim, iconSize = 20.dp)
            }
        }

        when {
            vm.submitted != null -> Results(vm, contentPadding)
            vm.query.isNotEmpty() -> Suggestions(
                vm, contentPadding,
                onPick = { vm.submit(it); keyboard?.hide(); focus.clearFocus() },
                onLink = { app.openShared(it); vm.clear(); keyboard?.hide(); focus.clearFocus() },
            )
            else -> Browse(
                contentPadding,
                onRecent = { vm.submit(it) },
                onGenre = { vm.submit("$it songs") },
                onListen = { q -> vm.submit(q, SearchTab.VIDEOS) },
                onLink = { app.openShared(it) },
            )
        }
    }
}

@Composable
private fun Suggestions(vm: SearchViewModel, contentPadding: PaddingValues, onPick: (String) -> Unit, onLink: (String) -> Unit) {
    val c = Lumen.colors
    val code = app.lumen.data.PlaylistShare.find(vm.query)
    val link = if (code == null) findLink(vm.query) else null
    LazyColumn(contentPadding = contentPadding) {
        if (code != null) item(key = "code") {
            Row(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .pressable({ onLink(code) }).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Playlist, null, Modifier.size(22.dp), tint = c.text)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add this shared playlist", style = Lumen.type.bodyStrong, color = c.text)
                    Text("It will appear in your Library", style = Lumen.type.small, color = c.textDim)
                }
                Icon(LIcons.Plus, null, Modifier.size(20.dp), tint = c.text)
            }
        }
        if (link != null) item(key = "link") {
            Row(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .pressable({ onLink(link) }).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Link, null, Modifier.size(22.dp), tint = c.text)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Open and play this link", style = Lumen.type.bodyStrong, color = c.text)
                    Text(link, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(LIcons.Play, null, Modifier.size(20.dp), tint = c.text)
            }
        }
        items(vm.suggestions) { s ->
            Row(Modifier.fillMaxWidth().pressable({ onPick(s) }).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LIcons.Search, null, Modifier.size(18.dp), tint = c.textDim)
                Spacer(Modifier.width(16.dp))
                Text(s, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(LIcons.Up, null, Modifier.size(16.dp).padding(0.dp), tint = c.textFaint)
            }
        }
    }
}

/** Spoken and long-form listening, searched as YouTube videos. */
private val listenTiles = listOf(
    "Podcasts" to "podcast full episode",
    "Lectures" to "full lecture explained",
    "Audiobooks" to "audiobook",
    "Stand-up" to "stand up comedy",
    "Motivation" to "motivational speech",
    "TED talks" to "ted talk",
)

@Composable
private fun Browse(
    contentPadding: PaddingValues,
    onRecent: (String) -> Unit,
    onGenre: (String) -> Unit,
    onListen: (String) -> Unit,
    onLink: (String) -> Unit,
) {
    val c = Lumen.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = LocalApp.current
    val searches by androidx.compose.runtime.remember {
        Graph.store.state.map { it.searches }.distinctUntilChanged()
    }.collectAsState(Graph.store.current.searches)
    LazyColumn(contentPadding = contentPadding) {
        // Tells people links work, and makes pasting one a single tap.
        item(key = "linkTip") {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(c.surface).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Link, null, Modifier.size(22.dp), tint = c.text)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Paste a link or playlist code", style = Lumen.type.bodyStrong, color = c.text)
                    Text(
                        "YouTube, YouTube Music, JioSaavn and SoundCloud links play at once. A Lumen playlist someone shared with you is added to your Library.",
                        style = Lumen.type.small, color = c.textDim,
                    )
                }
                Spacer(Modifier.width(8.dp))
                LButton("Paste", {
                    val clip = runCatching {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    }.getOrNull()
                    if (clip == null || !app.openShared(clip)) app.toast("Copy a playlist code or a YouTube, JioSaavn or SoundCloud link first")
                }, style = ButtonStyle.Ghost)
            }
        }
        if (searches.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Recent searches", action = "Clear", onAction = { Graph.store.clearSearches() })
            }
            items(searches.take(8), key = { "recent:$it" }) { q ->
                Row(Modifier.fillMaxWidth().pressable({ onRecent(q) }).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LIcons.History, null, Modifier.size(20.dp), tint = c.textDim)
                    Spacer(Modifier.width(16.dp))
                    Text(q, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LIconButton(LIcons.Close, { Graph.store.removeSearch(q) }, tint = c.textFaint, iconSize = 18.dp)
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Listen & learn")
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(listenTiles) { (label, q) ->
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh)
                            .pressable({ onListen(q) }).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(LIcons.Video, null, Modifier.size(18.dp), tint = c.text)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = Lumen.type.label, color = c.text)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Browse all")
            Spacer(Modifier.height(8.dp))
        }
        items(genres.chunked(2)) { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, g ->
                    val inverted = (genres.indexOf(g) / 2 + i) % 2 == 0
                    Box(
                        Modifier.weight(1f).aspectRatio(1.9f).clip(RoundedCornerShape(12.dp))
                            .background(if (inverted) c.text else c.surfaceHigh)
                            .pressable({ onGenre(g) })
                            .padding(14.dp),
                    ) {
                        Text(g, style = Lumen.type.title, color = if (inverted) c.bg else c.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Results(vm: SearchViewModel, contentPadding: PaddingValues) {
    val app = LocalApp.current
    val c = Lumen.colors
    val results = vm.results
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchTab.entries) { t -> Chip(t.label, vm.tab == t, { vm.tab = t }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SourceChip("All sources", vm.source == null) { vm.selectSource(null) } }
            items(Source.entries.filter { vm.bySource[it]?.tracks?.isNotEmpty() == true }) { s -> SourceChip(s.label, vm.source == s) { vm.selectSource(s) } }
        }
        val empty = results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty() && results.videos.isEmpty()
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (vm.loading && !empty) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.text, trackColor = c.bg)
        }
        if (vm.loading && empty) {
            Column(Modifier.padding(top = 16.dp)) {
                repeat(8) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Skeleton(Modifier.size(50.dp), corner = 6.dp)
                        Spacer(Modifier.width(14.dp))
                        Column { Skeleton(Modifier.size(180.dp, 14.dp)); Spacer(Modifier.height(6.dp)); Skeleton(Modifier.size(110.dp, 12.dp)) }
                    }
                }
            }
            return@Column
        }
        if (empty) {
            EmptyState(LIcons.Search, "No results", "Try different words, or check your connection.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            when (vm.tab) {
                SearchTab.TOP -> {
                    // An exact artist match leads, the way you'd expect when typing a name.
                    results.artists.firstOrNull()?.takeIf { it.title.equals(vm.submitted?.trim(), ignoreCase = true) }?.let { artist ->
                        item(key = "topArtist") {
                            Row(
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                                    .pressable({ app.openCollection(artist) }).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(artist.artwork, Modifier.size(72.dp), circle = true, placeholder = LIcons.Artist)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(artist.title, style = Lumen.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Artist", style = Lumen.type.small, color = c.textDim)
                                }
                                Icon(LIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.textDim)
                            }
                        }
                    }
                    results.tracks.firstOrNull()?.let { top ->
                        item {
                            Row(
                                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                                    .pressable({ Graph.player.play(results.tracks, 0) }).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(top.artwork, Modifier.size(88.dp), corner = 8.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Top result", style = Lumen.type.micro, color = c.textDim)
                                    Spacer(Modifier.height(4.dp))
                                    Text(top.title, style = Lumen.type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(top.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1)
                                }
                                val player by Graph.player.state.collectAsState()
                                val isTop = player.current?.id == top.id
                                PlayButton(isTop && player.isPlaying, { if (isTop) Graph.player.toggle() else Graph.player.play(results.tracks, 0) }, size = 44.dp)
                            }
                        }
                    }
                    itemsIndexed(results.tracks.drop(1).take(6), key = { i, t -> "top$i" + t.id }) { i, t ->
                        TrackRow(t, onClick = { Graph.player.play(results.tracks, i + 1) })
                    }
                    if (results.tracks.size > 7) item(key = "allSongs") {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            LButton("See all ${results.tracks.size}+ songs", { vm.tab = SearchTab.SONGS }, style = ButtonStyle.Secondary)
                        }
                    }
                    val songIds = results.tracks.map { it.id }.toHashSet()
                    val videos = results.videos.filter { it.id !in songIds }
                    if (videos.isNotEmpty()) item(key = "videosShelf") {
                        Column(Modifier.padding(top = 20.dp)) {
                            SectionHeader("Videos", action = "See all", onAction = { vm.tab = SearchTab.VIDEOS })
                            Spacer(Modifier.height(10.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
                                items(videos.take(10), key = { "v:" + it.id }) { v -> VideoCard(v, onClick = { Graph.player.play(listOf(v)) }) }
                            }
                        }
                    }
                    shelf("Artists", results.artists) { app.openCollection(it) }
                    shelf("Albums", results.albums) { app.openCollection(it) }
                    shelf("Playlists", results.playlists) { app.openCollection(it) }
                }
                SearchTab.SONGS -> {
                    val songs = results.tracks + vm.more
                    itemsIndexed(songs, key = { i, t -> "$i:" + t.id }) { i, t ->
                        TrackRow(t, onClick = { Graph.player.play(songs, i) })
                        // Reaching the last few rows fetches the next page from every catalogue.
                        if (i >= songs.size - 4) LaunchedEffect(songs.size) { vm.loadMore() }
                    }
                    if (vm.loadingMore) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.text)
                        }
                    }
                }
                SearchTab.VIDEOS -> {
                    val videos = results.videos + vm.moreVideos
                    if (videos.isEmpty()) {
                        item(key = "noVideos") {
                            if (vm.loading) Column { repeat(6) { VideoRowSkeleton() } }
                            else EmptyState(LIcons.Video, "No videos here", "Try other words, or paste a YouTube link in the search bar.")
                        }
                    }
                    itemsIndexed(videos, key = { i, t -> "vid$i:" + t.id }) { i, v ->
                        VideoRow(v, onClick = { Graph.player.play(listOf(v)) })
                        if (i >= videos.size - 4) LaunchedEffect(videos.size) { vm.loadMoreVideos() }
                    }
                    if (vm.loadingMoreVideos) item(key = "moreVideos") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.text)
                        }
                    }
                }
                SearchTab.ALBUMS -> grid(results.albums) { app.openCollection(it) }
                SearchTab.ARTISTS -> grid(results.artists) { app.openCollection(it) }
                SearchTab.PLAYLISTS -> grid(results.playlists) { app.openCollection(it) }
            }
        }
    }
}

@Composable
private fun SourceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colors
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(50))
            .background(if (selected) c.surfaceHigh else c.bg)
            .pressable(onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Lumen.type.small, color = if (selected) c.text else c.textDim)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(title: String, items: List<Collection>, onOpen: (Collection) -> Unit) {
    if (items.isEmpty()) return
    item(key = "shelf$title") {
        Column(Modifier.padding(top = 20.dp)) {
            SectionHeader(title)
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = ShelfArrangement) {
                itemsIndexed(items, key = { i, c -> "$i:" + c.id }) { _, it -> CollectionCard(it, onClick = { onOpen(it) }, modifier = Modifier.width(140.dp)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.grid(items: List<Collection>, onOpen: (Collection) -> Unit) {
    items(items.chunked(2)) { row ->
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { col -> Box(Modifier.weight(1f)) { CollectionCard(col, onClick = { onOpen(col) }, modifier = Modifier.fillMaxWidth()) } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
