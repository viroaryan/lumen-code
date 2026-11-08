// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import app.lumen.data.source.Http
import kotlinx.serialization.Serializable

/**
 * The whole library as one file the user keeps wherever they like (Google Drive, Downloads, sent to
 * themselves on WhatsApp). There's no server: this file *is* the backup. Restoring merges into what's
 * already on the phone and never deletes anything.
 */
object LibraryBackup {
    private const val MAX_BYTES = 20 * 1024 * 1024

    @Serializable
    private data class File(
        val lumenBackup: Int = 1,
        val createdAt: Long = 0,
        val liked: List<Track> = emptyList(),
        val playlists: List<UserPlaylist> = emptyList(),
        val savedCollections: List<Collection> = emptyList(),
        val history: List<Track> = emptyList(),
        val plays: Map<String, Int> = emptyMap(),
        val searches: List<String> = emptyList(),
        val settings: Settings? = null,
    )

    data class Summary(val playlists: Int, val liked: Int, val saved: Int)

    fun export(state: LibraryState): String = Http.json.encodeToString(
        File.serializer(),
        File(
            createdAt = System.currentTimeMillis(),
            liked = state.liked,
            // Downloaded songs point at files on this phone; the backup keeps the song, not the file.
            playlists = state.playlists,
            savedCollections = state.savedCollections,
            history = state.history,
            plays = state.plays,
            searches = state.searches,
            settings = state.settings,
        ),
    )

    /** Reads at most the size a real backup could be, so a huge wrong file can't exhaust memory. */
    fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /** Parses and checks a backup. Null when it isn't a Lumen backup or is damaged. */
    fun read(bytes: ByteArray): LibraryState? {
        if (bytes.size > MAX_BYTES) return null
        val file = runCatching { Http.json.decodeFromString(File.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        if (file.lumenBackup < 1) return null
        fun tracks(list: List<Track>) = list.asSequence().take(20_000).mapNotNull(PlaylistShare::safeTrack).distinctBy { it.id }.toList()
        return LibraryState(
            liked = tracks(file.liked),
            playlists = file.playlists.take(2_000).map { p ->
                UserPlaylist(
                    id = p.id.take(64).ifBlank { java.util.UUID.randomUUID().toString() },
                    name = PlaylistShare.clean(p.name, 120).ifBlank { "Restored playlist" },
                    tracks = tracks(p.tracks),
                    createdAt = p.createdAt,
                )
            },
            savedCollections = file.savedCollections.take(5_000).mapNotNull { c ->
                c.takeIf { it.id.length <= 260 && it.ref.length <= 600 && (it.ref.isEmpty() || it.ref.startsWith("https://") || it.source == Source.LOCAL) }
                    ?.copy(title = PlaylistShare.clean(c.title, 300), subtitle = PlaylistShare.clean(c.subtitle, 300), artwork = PlaylistShare.safeImage(c.artwork))
            },
            history = tracks(file.history).take(300),
            plays = file.plays.entries.take(1_000).associate { it.key.take(260) to it.value.coerceIn(0, 100_000) },
            searches = file.searches.take(20).map { PlaylistShare.clean(it, 200) }.filter { it.isNotBlank() },
            settings = file.settings ?: Settings(),
        )
    }

    /** Adds the backup to the current library: playlists, likes and saved items are combined, nothing removed. */
    fun merge(current: LibraryState, backup: LibraryState): Pair<LibraryState, Summary> {
        val existingIds = current.playlists.map { it.id }.toHashSet()
        val newPlaylists = backup.playlists.filter { p ->
            p.id !in existingIds && current.playlists.none { it.name == p.name && it.tracks.map { t -> t.id } == p.tracks.map { t -> t.id } }
        }
        val likedIds = current.liked.map { it.id }.toHashSet()
        val newLiked = backup.liked.filter { it.id !in likedIds }
        val savedIds = current.savedCollections.map { it.id }.toHashSet()
        val newSaved = backup.savedCollections.filter { it.id !in savedIds }
        val historyIds = current.history.map { it.id }.toHashSet()
        val merged = current.copy(
            playlists = current.playlists + newPlaylists,
            liked = current.liked + newLiked,
            savedCollections = current.savedCollections + newSaved,
            history = (current.history + backup.history.filter { it.id !in historyIds }).take(300),
            plays = backup.plays + current.plays,
            searches = (current.searches + backup.searches).distinct().take(20),
        )
        return merged to Summary(newPlaylists.size, newLiked.size, newSaved.size)
    }
}
