// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import android.content.Context
import app.lumen.data.source.Http
import app.lumen.data.source.Quality
import app.lumen.engine.Guardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val quality: Quality = Quality.HIGH,
    val downloadQuality: Quality = Quality.HIGH,
    val skipSilence: Boolean = false,
    val autoplay: Boolean = true,
    val autoPip: Boolean = true,
    val sources: Set<Source> = setOf(Source.YOUTUBE, Source.SAAVN, Source.SOUNDCLOUD),
    val saveHistory: Boolean = true,
    /** Funny little picks and new-release alerts, at most about once a day. */
    val notifications: Boolean = true,
    /** Song cache ceiling in MB; 0 lets the app size it from free space. */
    val cacheLimitMb: Int = 0,
    /** Every song you like is also saved for offline listening. */
    val autoDownloadLiked: Boolean = false,
)

@Serializable
data class SavedQueue(val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0)

/**
 * What the player needs to pick up where you left off. It changes every few seconds while music plays,
 * so it lives in its own small file instead of rewriting the whole library each time.
 */
@Serializable
data class PlaybackMemory(
    val queue: SavedQueue = SavedQueue(),
    /** Where you stopped in long videos (podcasts, lectures), by track id. */
    val resume: Map<String, Long> = emptyMap(),
)

@Serializable
data class LibraryState(
    val liked: List<Track> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val savedCollections: List<Collection> = emptyList(),
    val history: List<Track> = emptyList(),
    /** How many times each song has been played, for "On repeat". */
    val plays: Map<String, Int> = emptyMap(),
    val searches: List<String> = emptyList(),
    val downloads: Map<String, Track> = emptyMap(),
    val settings: Settings = Settings(),
    /** Old home of the saved queue; read once to migrate into [PlaybackMemory]. */
    val queue: SavedQueue = SavedQueue(),
    /** The order the user dragged "On this device" into, by track id (the files themselves aren't ours). */
    val localOrder: List<String> = emptyList(),
    /** Custom cover pictures, by list key ([CoverKeys]) → image file inside the app's own storage. */
    val covers: Map<String, String> = emptyMap(),
    /** Songs the song finder recognised, newest first. */
    val identified: List<IdentifiedSong> = emptyList(),
)

/** Keys for lists that can have a custom cover. */
object CoverKeys {
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val LOCAL = "local"
    const val HISTORY = "history"
    fun playlist(id: String) = "pl:$id"
}

/**
 * Everything the user owns, persisted as one JSON file. Writes are debounced off the main thread, go
 * through a temp file, and keep an hourly backup. A file that fails to read is set aside, never
 * overwritten, and the backup is used instead, so a bad write can't wipe someone's library.
 */
@OptIn(FlowPreview::class)
class LibraryStore(context: Context, scope: CoroutineScope) {

    private val file = File(context.filesDir, "library.json")
    private val backup = File(context.filesDir, "library.json.bak")
    /** A second, slower-moving copy (refreshed once a day), so even a bad hour can be rolled back. */
    private val daily = File(context.filesDir, "library.daily.json")
    private val memoryFile = File(context.filesDir, "queue.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryState> = _state

    private val _memory = MutableStateFlow(loadMemory())
    val memory: StateFlow<PlaybackMemory> = _memory

    init {
        val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
        _state.drop(1).debounce(400).onEach { s ->
            runCatching { save(s) }.onFailure { Guardian.record("library save", it) }
        }.launchIn(io)
        _memory.drop(1).debounce(1500).onEach { m -> writeMemory(m) }.launchIn(io)
    }

    private fun decode(f: File): LibraryState = Http.json.decodeFromString(LibraryState.serializer(), f.readText())

    private fun fromBackups(): LibraryState =
        runCatching { decode(backup) }.recoverCatching { decode(daily) }.getOrDefault(LibraryState())

    private fun load(): LibraryState {
        if (!file.exists()) return fromBackups()
        return runCatching { decode(file) }.getOrElse { error ->
            Guardian.record("library load", error)
            // Keep the unreadable file for inspection, and recover from the backups.
            runCatching { file.copyTo(File(file.parentFile, "library.json.corrupt"), overwrite = true) }
            fromBackups()
        }
    }

    private fun loadMemory(): PlaybackMemory {
        val saved = runCatching { Http.json.decodeFromString(PlaybackMemory.serializer(), memoryFile.readText()) }.getOrNull()
        if (saved != null) return saved
        // First run after the update: move the queue out of the library file.
        val legacy = _state.value.queue
        if (legacy.tracks.isNotEmpty()) _state.update { it.copy(queue = SavedQueue()) }
        return PlaybackMemory(queue = legacy)
    }

    private fun write(target: File, text: String) = SafeFile.write(target, text)

    private val saveLock = Any()

    private fun save(state: LibraryState) = synchronized(saveLock) {
        val now = System.currentTimeMillis()
        // Only a file that still reads back cleanly is promoted to a backup.
        if (file.exists() && (now - backup.lastModified() > 60 * 60 * 1000L || now - daily.lastModified() > 24 * 60 * 60 * 1000L)) {
            if (runCatching { decode(file) }.isSuccess) {
                if (now - backup.lastModified() > 60 * 60 * 1000L) runCatching { file.copyTo(backup, overwrite = true) }
                if (now - daily.lastModified() > 24 * 60 * 60 * 1000L) runCatching { file.copyTo(daily, overwrite = true) }
            }
        }
        write(file, Http.json.encodeToString(LibraryState.serializer(), state))
    }

    /**
     * Saves right now instead of after the usual short pause. Called when the app goes to the background,
     * which is often the last chance before Android ends the process.
     */
    fun flushNow() {
        val s = _state.value
        runCatching { save(s) }.onFailure { Guardian.record("library flush", it) }
        writeMemory(_memory.value)
    }

    private val memoryLock = Any()
    private fun writeMemory(m: PlaybackMemory) = synchronized(memoryLock) {
        runCatching { write(memoryFile, Http.json.encodeToString(PlaybackMemory.serializer(), m)) }
    }

    private inline fun edit(block: (LibraryState) -> LibraryState) = _state.update(block)

    /** The library right now, as a plain snapshot (for initial values; observe [state] for changes). */
    val current: LibraryState get() = _state.value

    val settings get() = _state.value.settings
    fun updateSettings(block: (Settings) -> Settings) = edit { it.copy(settings = block(it.settings)) }

    fun isLiked(id: String) = _state.value.liked.any { it.id == id }
    fun toggleLike(track: Track) = edit { s ->
        if (s.liked.any { it.id == track.id }) s.copy(liked = s.liked.filterNot { it.id == track.id })
        else s.copy(liked = listOf(track) + s.liked)
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        edit { it.copy(playlists = listOf(UserPlaylist(id, name.trim().ifEmpty { "My playlist" }, tracks.distinctBy { t -> t.id })) + it.playlists) }
        return id
    }

    /**
     * Adds a playlist that came from a share code or a backup. Pasting the same code twice opens the
     * playlist already there instead of making a copy. Returns the playlist's id.
     */
    fun importPlaylist(name: String, tracks: List<Track>): String {
        val ids = tracks.map { it.id }
        _state.value.playlists.firstOrNull { p -> p.name == name && p.tracks.map { it.id } == ids }?.let { return it.id }
        return createPlaylist(name, tracks)
    }

    /** Merges a restored backup into the library. Returns what was added. */
    fun restore(backup: LibraryState): LibraryBackup.Summary {
        var summary = LibraryBackup.Summary(0, 0, 0)
        edit { s -> LibraryBackup.merge(s, backup).also { summary = it.second }.first }
        return summary
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    /** Removes the playlist and returns it with its old position, so the deletion can be undone. */
    fun deletePlaylist(id: String): Pair<UserPlaylist, Int>? {
        val s = _state.value
        val index = s.playlists.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = s.playlists[index]
        edit { st -> st.copy(playlists = st.playlists.filterNot { it.id == id }) }
        return removed to index
    }

    fun restorePlaylist(playlist: UserPlaylist, index: Int) = edit { s ->
        if (s.playlists.any { it.id == playlist.id }) s
        else s.copy(playlists = s.playlists.toMutableList().apply { add(index.coerceIn(0, size), playlist) })
    }
    /** Adds songs not already in the playlist. Fast for any size (a set lookup per song, not a scan). */
    fun addToPlaylist(id: String, tracks: List<Track>) = editPlaylist(id) { p ->
        val have = p.tracks.mapTo(HashSet(p.tracks.size * 2)) { it.id }
        p.copy(tracks = p.tracks + tracks.filter { have.add(it.id) })
    }

    /** How many of [tracks] would be new to the playlist. */
    fun countNew(id: String, tracks: List<Track>): Int {
        val p = _state.value.playlists.firstOrNull { it.id == id } ?: return 0
        val have = p.tracks.mapTo(HashSet()) { it.id }
        return tracks.distinctBy { it.id }.count { it.id !in have }
    }
    fun removeFromPlaylist(id: String, trackId: String) = editPlaylist(id) { p -> p.copy(tracks = p.tracks.filterNot { it.id == trackId }) }
    fun movePlaylistTrack(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices) p
        else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }

    // ---- Custom order (long-press and drag) -----------------------------------------------------

    /**
     * Puts a list into the order the user dragged it into. [ids] is the whole list as shown; anything
     * that arrived while dragging (a new like, a finished download) is kept, never dropped.
     */
    fun reorder(list: String, ids: List<String>) = edit { s ->
        when {
            list == CoverKeys.LIKED -> s.copy(liked = s.liked.inOrder(ids, newFirst = true))
            list == CoverKeys.HISTORY -> s.copy(history = s.history.inOrder(ids, newFirst = true))
            list == CoverKeys.DOWNLOADS -> s.copy(
                downloads = s.downloads.values.toList().inOrder(ids, newFirst = false).associateByTo(LinkedHashMap()) { it.id },
            )
            list == CoverKeys.LOCAL -> s.copy(localOrder = ids.distinct().take(50_000))
            list.startsWith("pl:") -> {
                val id = list.removePrefix("pl:")
                s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(tracks = it.tracks.inOrder(ids, newFirst = false)) else it })
            }
            else -> s
        }
    }

    private fun List<Track>.inOrder(ids: List<String>, newFirst: Boolean): List<Track> {
        val byId = HashMap<String, Track>(size * 2).also { m -> forEach { m.putIfAbsent(it.id, it) } }
        val seen = HashSet<String>(size * 2)
        val ordered = ids.mapNotNull { id -> byId[id]?.takeIf { seen.add(id) } }
        val rest = filter { seen.add(it.id) }
        return if (newFirst) rest + ordered else ordered + rest
    }

    /** Phone files in the user's own order; files not yet placed keep their usual spot at the end. */
    fun orderLocal(tracks: List<Track>): List<Track> {
        val order = _state.value.localOrder
        if (order.isEmpty()) return tracks
        val rank = HashMap<String, Int>(order.size * 2).also { m -> order.forEachIndexed { i, id -> m.putIfAbsent(id, i) } }
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    // ---- Covers ---------------------------------------------------------------------------------

    /** Sets a list's cover and returns the picture it replaced, so the caller can delete that file. */
    fun setCover(key: String, path: String): String? {
        var old: String? = null
        edit { s -> old = s.covers[key]; s.copy(covers = s.covers + (key to path)) }
        return old?.takeIf { it != path }
    }

    fun removeCover(key: String): String? {
        var old: String? = null
        edit { s -> old = s.covers[key]; s.copy(covers = s.covers - key) }
        return old
    }

    // ---- Song finder ----------------------------------------------------------------------------

    fun addIdentified(song: IdentifiedSong) = edit { s ->
        val rest = s.identified.filterNot { it.title.equals(song.title, true) && it.artist.equals(song.artist, true) }
        s.copy(identified = (listOf(song) + rest).take(60))
    }

    fun removeIdentified(song: IdentifiedSong) = edit { s -> s.copy(identified = s.identified - song) }

    private fun editPlaylist(id: String, block: (UserPlaylist) -> UserPlaylist) = edit { s ->
        s.copy(playlists = s.playlists.map { if (it.id == id) block(it) else it })
    }

    fun isSaved(id: String) = _state.value.savedCollections.any { it.id == id }
    fun toggleSaved(collection: Collection) = edit { s ->
        if (s.savedCollections.any { it.id == collection.id }) s.copy(savedCollections = s.savedCollections.filterNot { it.id == collection.id })
        else s.copy(savedCollections = listOf(collection) + s.savedCollections)
    }

    fun addHistory(track: Track) {
        if (!settings.saveHistory) return
        edit { s ->
            val history = (listOf(track) + s.history.filterNot { it.id == track.id }).take(300)
            val kept = history.map { it.id }.toHashSet()
            val plays = (s.plays + (track.id to (s.plays[track.id] ?: 0) + 1)).filterKeys { it in kept }
            s.copy(history = history, plays = plays)
        }
    }
    fun clearHistory() = edit { it.copy(history = emptyList(), plays = emptyMap()) }

    fun addSearch(query: String) = edit { s ->
        val q = query.trim()
        if (q.isEmpty()) s else s.copy(searches = (listOf(q) + s.searches.filterNot { it.equals(q, true) }).take(20))
    }
    fun removeSearch(query: String) = edit { s -> s.copy(searches = s.searches - query) }
    fun clearSearches() = edit { it.copy(searches = emptyList()) }

    fun addDownload(track: Track, path: String) = edit { s -> s.copy(downloads = s.downloads + (track.id to track.copy(ref = path))) }
    fun removeDownload(id: String) = edit { s -> s.copy(downloads = s.downloads - id) }
    fun downloadPath(id: String): String? = _state.value.downloads[id]?.ref?.takeIf { File(it).exists() }

    /** Drops download entries whose file has gone (deleted by a cleaner app, or a broken write). */
    fun pruneDownloads(): Int {
        val missing = _state.value.downloads.filterValues { !File(it.ref).exists() }.keys
        if (missing.isNotEmpty()) edit { s -> s.copy(downloads = s.downloads - missing) }
        return missing.size
    }

    // ---- Playback memory -------------------------------------------------------------------------

    val queue: SavedQueue get() = _memory.value.queue
    fun saveQueue(queue: SavedQueue) = _memory.update { it.copy(queue = queue) }

    fun resumePosition(id: String): Long? = _memory.value.resume[id]
    fun saveResume(id: String, positionMs: Long) = _memory.update { m ->
        val next = (m.resume - id) + (id to positionMs)
        m.copy(resume = if (next.size > 40) next.entries.toList().takeLast(40).associate { it.key to it.value } else next)
    }
    fun clearResume(id: String) = _memory.update { m -> if (id in m.resume) m.copy(resume = m.resume - id) else m }
}
