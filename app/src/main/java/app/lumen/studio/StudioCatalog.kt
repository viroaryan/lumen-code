// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.studio

import app.lumen.Graph
import app.lumen.data.Lyrics
import app.lumen.data.Track
import app.lumen.data.source.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Finds music-only versions of songs (karaoke and instrumental uploads) and their lyrics. */
object StudioCatalog {

    val categories = listOf(
        "Trending" to "latest hindi songs karaoke with lyrics",
        "Bollywood" to "bollywood karaoke with lyrics",
        "Arijit Singh" to "arijit singh karaoke with lyrics",
        "Romantic" to "romantic hindi karaoke",
        "English" to "english songs karaoke with lyrics",
        "Punjabi" to "punjabi songs karaoke",
        "90s" to "90s bollywood karaoke",
        "Ghazal" to "ghazal karaoke",
        "Devotional" to "bhajan karaoke",
        "Instrumental" to "hindi songs instrumental",
    )

    private val musicOnly = listOf("karaoke", "instrumental", "backing track", "without vocal", "minus one", "music only", "track only", "no vocals", "unplugged karaoke")
    private val noise = Regex(
        "(?i)\\b(karaoke|instrumental|with lyrics|lyrics|lyrical|backing track|without vocals?|minus one|music only|track only|no vocals|" +
            "sing along|hd|4k|full song|original|clean|high quality|scrolling|video|version|cover|piano|guitar|unplugged|mp3)\\b",
    )

    fun isMusicOnly(title: String) = musicOnly.any { title.contains(it, ignoreCase = true) }

    /** Karaoke versions of whatever was typed, music-only uploads first. */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        coroutineScope {
            val q = if (isMusicOnly(query)) query else "$query karaoke"
            val a = async { Graph.repo.youtube.searchAllVideos(q) }
            val b = async { Graph.repo.youtube.searchAllVideos("$query instrumental") }
            val all = (a.await() + b.await()).distinctBy { it.id }
            val (karaoke, rest) = all.partition { isMusicOnly(it.title) }
            karaoke + rest.take(5)
        }
    }

    /** The song's real title, without the "karaoke | lyrics | HD" decoration karaoke uploads carry. */
    fun songTitle(uploadTitle: String): String {
        val base = LyricsSource.cleanTitle(uploadTitle).replace(noise, " ")
        val parts = base.split(" - ", " | ", "|", " – ", ",").map { it.trim() }.filter { it.length > 1 }
        return (parts.firstOrNull() ?: base)
            .replace(Regex("(?i)\\s+(with|and|by|for|in|on|&)\\s*$"), "") // "Gehra Hua With" -> "Gehra Hua"
            .replace(Regex("\\s+"), " ").trim()
    }

    suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val title = songTitle(track.title)
        LyricsSource.fetch(track.copy(title = title, artist = "", durationMs = 0))
            ?: LyricsSource.fetch(track.copy(title = title, artist = "", durationMs = track.durationMs))
    }
}
