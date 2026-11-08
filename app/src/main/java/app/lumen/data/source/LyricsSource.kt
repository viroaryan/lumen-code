// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import app.lumen.data.LyricLine
import app.lumen.data.Lyrics
import app.lumen.data.Track
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** Synced lyrics from LRCLIB, a free and open lyrics database. */
object LyricsSource {

    @Serializable
    private data class Entry(val syncedLyrics: String? = null, val plainLyrics: String? = null, val duration: Double = 0.0) {
        fun usable() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
    }

    private val brackets = Regex("\\s*[(\\[{][^)\\]}]*[)\\]}]")
    private val featuring = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)

    /** "Kesariya (From "Brahmastra") [Official Video] | T-Series" becomes "Kesariya". */
    fun cleanTitle(title: String): String =
        title.replace(brackets, "").substringBefore(" | ").replace(featuring, "").trim().ifEmpty { title.trim() }

    fun fetch(track: Track): Lyrics? {
        val base = cleanTitle(track.title)
        // YouTube titles are often "Artist - Song", so try both halves as well.
        val titles = listOf(base, base.substringBefore(" - "), base.substringAfter(" - ", ""))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val artist = track.artist.substringBefore(",").substringBefore(" & ").trim()
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val duration = if (track.durationMs > 0) "&duration=${track.durationMs / 1000}" else ""

        val exact = titles.firstNotNullOfOrNull { title ->
            runCatching {
                Http.json.decodeFromString<Entry>(Http.get("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$duration"))
            }.getOrNull()?.takeIf { it.usable() }
        }
        val entry = exact ?: titles.firstNotNullOfOrNull { title ->
            runCatching {
                Http.json.decodeFromString<List<Entry>>(Http.get("https://lrclib.net/api/search?q=${enc("$title $artist")}"))
                    .filter { it.usable() }
                    .filter { track.durationMs <= 0 || kotlin.math.abs(it.duration * 1000 - track.durationMs) < 15_000 }
                    .sortedByDescending { !it.syncedLyrics.isNullOrBlank() }
                    .minByOrNull { if (track.durationMs > 0) kotlin.math.abs(it.duration * 1000 - track.durationMs) else 0.0 }
            }.getOrNull()
        } ?: return null

        entry.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return Lyrics(parseLrc(it), synced = true) }
        entry.plainLyrics?.takeIf { it.isNotBlank() }?.let { plain ->
            return Lyrics(plain.lines().map { LyricLine(0, it) }, synced = false)
        }
        return null
    }

    private val stamp = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")

    fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().flatMap { line ->
        val text = line.replace(stamp, "").trim()
        stamp.findAll(line).map { m ->
            val (min, sec, frac) = m.destructured
            val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, text)
        }.toList()
    }.sortedBy { it.timeMs }
}
