// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import app.lumen.data.Collection
import app.lumen.data.Kind
import app.lumen.data.Source
import app.lumen.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val COLLECTION = "collection/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val SPECIAL = "special/{kind}"
    const val STUDIO = "studio"
    const val STUDIO_SESSION = "studio/session/{id}"
}

/** Lists the library screen can open that aren't playlists. */
object Special {
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val HISTORY = "history"
    const val LOCAL = "local"
}

/**
 * Collections being opened, keyed by id, so routes only carry an id. The last few are also kept on disk:
 * when Android kills the app in the background and later restores the screen you were on, the
 * album or playlist is still there instead of the screen closing itself.
 */
object CollectionCache {
    private const val KEEP = 60
    private val map = ConcurrentHashMap<String, Collection>()
    private val serializer = kotlinx.serialization.builtins.ListSerializer(Collection.serializer())
    private val file get() = java.io.File(app.lumen.Graph.app.cacheDir, "open_collections.json")
    @Volatile private var loaded = false

    fun put(c: Collection) {
        map[c.id] = c
        val recent = (listOf(c) + (map.values - c)).distinctBy { it.id }.take(KEEP)
        if (map.size > KEEP * 2) map.keys.retainAll(recent.map { it.id }.toSet())
        app.lumen.Graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { file.writeText(app.lumen.data.source.Http.json.encodeToString(serializer, recent)) }
        }
    }

    operator fun get(id: String): Collection? = map[id] ?: run {
        if (!loaded) {
            loaded = true
            runCatching { app.lumen.data.source.Http.json.decodeFromString(serializer, file.readText()) }.getOrNull()
                ?.forEach { map.putIfAbsent(it.id, it) }
        }
        map[id]
    }
}

data class TrackMenuRequest(val track: Track, val playlistId: String? = null)

class AppState(val nav: NavHostController, private val scope: CoroutineScope) {
    var playerOpen by mutableStateOf(false)
    var currentTab by mutableStateOf(Routes.HOME)
    var trackMenu by mutableStateOf<TrackMenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    val snackbar = SnackbarHostState()

    fun openCollection(c: Collection) {
        CollectionCache.put(c)
        playerOpen = false
        nav.navigate("collection/${Uri.encode(c.id)}")
    }

    fun openArtist(name: String, source: Source) {
        val artist = name.substringBefore(",").substringBefore(" & ").trim()
        val ref = if (source == Source.SAAVN) "" else artist
        val src = if (source == Source.SAAVN || source == Source.LOCAL) source else Source.YOUTUBE
        openCollection(Collection("artist:${src.name}:$artist", Kind.ARTIST, artist, "Artist", null, src, ref))
    }

    fun openPlaylist(id: String) { playerOpen = false; nav.navigate("playlist/$id") }
    fun openSpecial(kind: String) { playerOpen = false; nav.navigate("special/$kind") }
    fun openSettings() = nav.navigate(Routes.SETTINGS)

    /** Plays a song link or opens a playlist/album link from YouTube, JioSaavn or SoundCloud. */
    fun openLink(url: String) {
        scope.launch {
            toast("Opening link…")
            when (val link = app.lumen.Graph.repo.openLink(url)) {
                is app.lumen.data.MusicRepository.Link.Song -> {
                    // A podcast or lecture plays on its own (autoplay brings related videos); a song gets a radio.
                    if (link.track.video) app.lumen.Graph.player.play(listOf(link.track))
                    else app.lumen.Graph.player.startRadio(link.track)
                    playerOpen = true
                }
                is app.lumen.data.MusicRepository.Link.List -> openCollection(link.collection)
                null -> toast("Couldn't open that link")
            }
        }
    }

    /** Rebuilds a playlist from a share code and opens it. */
    fun importPlaylist(code: String) {
        scope.launch {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { app.lumen.data.PlaylistShare.decode(code) }
            if (decoded == null) {
                toast("This playlist code is incomplete. Ask for the whole message again.")
                return@launch
            }
            val id = app.lumen.Graph.store.importPlaylist(decoded.name, decoded.tracks)
            playerOpen = false
            nav.navigate("playlist/$id")
            toast("Added \"${decoded.name}\" · ${decoded.tracks.size} songs")
        }
    }

    /** Opens whatever was pasted or shared in: a playlist code first, else a music link. Returns false if neither. */
    fun openShared(text: String): Boolean {
        app.lumen.data.PlaylistShare.find(text)?.let { importPlaylist(it); return true }
        findLink(text)?.let { openLink(it); return true }
        return false
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    /** A message with one button (like "Undo") that stays a little longer than a plain toast. */
    fun toast(message: String, action: String, onAction: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = action, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppState> { error("AppState not provided") }

private val fullLink = Regex("https?://\\S+")
private val bareLink = Regex("(?:(?:www|m|music)\\.)?(?:youtube\\.com|youtu\\.be|jiosaavn\\.com|soundcloud\\.com)/\\S+", RegexOption.IGNORE_CASE)

/**
 * Opens the system share sheet with the playlist as a code anyone can paste into Lumen. Very long
 * playlists go as a small text file instead, since chat apps cap message length. Returns a short
 * message for the user.
 */
fun sharePlaylist(context: android.content.Context, name: String, tracks: List<Track>): String {
    val encoded = runCatching { app.lumen.data.PlaylistShare.encode(name, tracks) }.getOrNull()
        ?: return "Couldn't prepare this playlist for sharing"
    if (encoded.shared == 0) return "Songs stored only on this phone can't be shared"
    val message = app.lumen.data.PlaylistShare.message(name, encoded)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "$name · Lumen playlist")
    if (message.length <= 60_000) {
        send.putExtra(android.content.Intent.EXTRA_TEXT, message)
    } else {
        val file = runCatching {
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            java.io.File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Playlist" }.take(40) + ".lumen.txt")
                .apply { writeText(message) }
        }.getOrNull() ?: return "Couldn't prepare this playlist for sharing"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_TEXT, "🎧 \"$name\" · ${encoded.shared} songs. Open this file with Lumen to add it.")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(android.content.Intent.createChooser(send, "Share \"$name\"").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        if (encoded.skipped > 0) "${encoded.skipped} songs stored only on this phone were left out" else "Sharing ${encoded.shared} songs"
    }.getOrDefault("No app to share with")
}

/** Finds a playable link in text, including ones pasted without "https://" (like "youtu.be/abc"). */
fun findLink(text: String): String? {
    fullLink.find(text)?.value?.let { return it.trimEnd('.', ',', ')', '"', '\'') }
    return bareLink.find(text)?.value?.let { "https://" + it.trimEnd('.', ',', ')', '"', '\'') }
}
