// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import kotlinx.serialization.Serializable

@Serializable
enum class Source(val label: String) {
    YOUTUBE("YouTube Music"),
    SAAVN("JioSaavn"),
    SOUNDCLOUD("SoundCloud"),
    LOCAL("On device"),
}

/**
 * A playable song from any source. [id] is globally unique ("yt:…", "jsv:…", "sc:…", "local:…")
 * and [ref] is whatever the source needs to resolve a stream (video url, encrypted media url, content uri).
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artwork: String? = null,
    val durationMs: Long = 0,
    val source: Source,
    val ref: String,
    val explicit: Boolean = false,
    /** A regular YouTube video (podcast, lecture, talk…) rather than a song: no music radio, resumable. */
    val video: Boolean = false,
)

object Kind {
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val ARTIST = "artist"
}

/** An album, playlist or artist that opens into a list of tracks. */
@Serializable
data class Collection(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val source: Source,
    val ref: String,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** A song the song finder recognised (from the floating bubble or the Identify sheet). */
@Serializable
data class IdentifiedSong(
    val title: String,
    val artist: String,
    val artwork: String? = null,
    val at: Long = System.currentTimeMillis(),
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Collection> = emptyList(),
    val artists: List<Collection> = emptyList(),
    val playlists: List<Collection> = emptyList(),
    /** Plain YouTube videos: podcasts, lectures, talks, anything you can listen to. */
    val videos: List<Track> = emptyList(),
)

data class CollectionDetail(
    val collection: Collection,
    val tracks: List<Track>,
    val albums: List<Collection> = emptyList(),
)

@Serializable
data class HomeSection(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val collections: List<Collection> = emptyList(),
    /** A short line above the title, like "Based on your listening". */
    val subtitle: String = "",
    /** How the shelf is drawn: "" for square song cards, [STYLE_VIDEO] for wide video cards. */
    val style: String = "",
) {
    companion object {
        const val STYLE_VIDEO = "video"
    }
}

/** One word of a line and the moment it's sung. */
data class LyricWord(val timeMs: Long, val text: String)

/**
 * A line of lyrics. [words] is filled when the source has real word-by-word timing; otherwise the
 * player estimates each word's moment from the line's length.
 */
data class LyricLine(val timeMs: Long, val text: String, val endMs: Long = 0, val words: List<LyricWord> = emptyList())

/**
 * One version of a song's lyrics. A song can have several: the original script (हिन्दी, 한국어…) and
 * a romanised one, for example. [label] names the script for the picker.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val label: String = "",
    /** Real word-by-word timing from the source (not estimated). */
    val wordSync: Boolean = false,
)
