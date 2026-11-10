// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.studio

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lumen.Graph
import app.lumen.data.Track
import app.lumen.playback.TrackRegistry
import app.lumen.studio.StudioCatalog
import app.lumen.studio.Take
import app.lumen.ui.LocalApp
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.Chip
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.SectionHeader
import app.lumen.ui.components.Skeleton
import app.lumen.ui.components.formatDuration
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class StudioViewModel : ViewModel() {
    var query by mutableStateOf("")
    var category by mutableStateOf(StudioCatalog.categories.first().first)
    var results by mutableStateOf<List<Track>?>(null)
        private set
    private var job: Job? = null
    private val cache = mutableMapOf<String, List<Track>>()

    init { load(StudioCatalog.categories.first().second) }

    fun pickCategory(name: String, q: String) {
        category = name
        query = ""
        load(q)
    }

    fun search() {
        if (query.isBlank()) return
        category = ""
        load(query.trim())
    }

    private fun load(q: String) {
        job?.cancel()
        cache[q]?.let { results = it; return }
        results = null
        job = viewModelScope.launch {
            val found = runCatching { StudioCatalog.search(q) }.getOrDefault(emptyList())
            cache[q] = found
            results = found
        }
    }
}

@Composable
fun StudioScreen(contentPadding: PaddingValues, vm: StudioViewModel = viewModel()) {
    val app = LocalApp.current
    val c = Lumen.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val takes by Graph.studio.takes.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text("Studio", style = Lumen.type.display, color = c.text)
                Text("Sing over any song. Record, mix like a pro, export.", style = Lumen.type.small, color = c.textDim)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LIcons.Search, null, Modifier.size(22.dp), tint = c.textDim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (vm.query.isEmpty()) Text("Find a song to sing", style = Lumen.type.body, color = c.textFaint)
                        BasicTextField(
                            value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                            textStyle = Lumen.type.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search(); keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (vm.query.isNotEmpty()) LIconButton(LIcons.Close, { vm.query = "" }, tint = c.textDim, iconSize = 20.dp)
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StudioCatalog.categories) { (name, q) -> Chip(name, vm.category == name, { vm.pickCategory(name, q) }) }
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LIcons.Headphones, null, Modifier.size(22.dp), tint = c.text)
                Spacer(Modifier.width(12.dp))
                Text("Wear earphones while recording so only your voice reaches the mic.", style = Lumen.type.small, color = c.textDim)
            }
        }
        if (takes.isNotEmpty()) {
            item { Spacer(Modifier.height(20.dp)); SectionHeader("Your recordings") }
            items(takes, key = { "take" + it.id }) { take -> TakeRow(take) }
        }
        item { Spacer(Modifier.height(16.dp)); SectionHeader(if (vm.category.isNotEmpty()) "${vm.category} tracks" else "Music-only versions") }
        val list = vm.results
        when {
            list == null -> items(6) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Skeleton(Modifier.size(64.dp, 48.dp), corner = 6.dp)
                    Spacer(Modifier.width(14.dp))
                    Column { Skeleton(Modifier.size(200.dp, 14.dp)); Spacer(Modifier.height(6.dp)); Skeleton(Modifier.size(120.dp, 12.dp)) }
                }
            }
            list.isEmpty() -> item { EmptyState(LIcons.Mic, "Nothing found", "Try the song name with the singer, like \"Tum Hi Ho Arijit\".") }
            else -> items(list, key = { "res" + it.id }) { track ->
                Row(
                    Modifier.fillMaxWidth().pressable({ StudioTracks.put(track); app.nav.navigate("studio/session/${Uri.encode("new:" + track.id)}") })
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.artwork, Modifier.size(72.dp, 48.dp), corner = 6.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(StudioCatalog.songTitle(track.title), style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(if (StudioCatalog.isMusicOnly(track.title)) "Music only" else "May include vocals", formatDuration(track.durationMs), track.artist)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(LIcons.Mic, null, Modifier.size(20.dp), tint = c.textDim)
                }
            }
        }
    }
}

/** Tracks being opened in a session, keyed by id, so the route only carries an id. */
object StudioTracks {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Track>()
    fun put(t: Track) { map[t.id] = t; TrackRegistry.register(t) }
    operator fun get(id: String) = map[id]
}

@Composable
private fun TakeRow(take: Take) {
    val app = LocalApp.current
    val context = LocalContext.current
    val c = Lumen.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().pressable({ app.nav.navigate("studio/session/${Uri.encode("take:" + take.id)}") })
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(c.text), contentAlignment = Alignment.Center) {
            Icon(LIcons.Waveform, null, Modifier.size(24.dp), tint = c.bg)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(StudioCatalog.songTitle(take.track.title), style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(take.durationMs)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(take.createdAt))}" +
                    if (take.exportedUri != null) " · Saved" else "",
                style = Lumen.type.small, color = c.textDim,
            )
        }
        Box {
            LIconButton(LIcons.More, { menu = true }, tint = c.textDim, iconSize = 20.dp)
            DropdownMenu(menu, { menu = false }, containerColor = c.surface) {
                take.exportedUri?.let { uri ->
                    DropdownMenuItem({ Text("Share song", style = Lumen.type.body) }, {
                        menu = false
                        val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }, leadingIcon = { Icon(LIcons.Share, null) })
                }
                DropdownMenuItem({ Text("Delete recording", style = Lumen.type.body) }, {
                    menu = false
                    Graph.studio.delete(take.id)
                    app.toast("Recording deleted")
                }, leadingIcon = { Icon(LIcons.Trash, null) })
            }
        }
    }
}
