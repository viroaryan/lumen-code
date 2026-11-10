// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import app.lumen.data.LyricLine
import app.lumen.data.LyricWord
import app.lumen.data.Lyrics
import app.lumen.data.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics for a song, in every script they exist in.
 *
 * 1. LRCLIB (free, open): the exact song first, then a search. One song often has several entries:
 *    the original script (हिन्दी, 한국어…) and a romanised one. Each script becomes a version the
 *    listener can pick. Entries with real word-by-word timing are preferred.
 * 2. NetEase Cloud Music, as a second catalogue when LRCLIB has nothing.
 */
object LyricsSource {

    @Serializable
    private data class Entry(
        val id: Long = 0,
        val trackName: String = "",
        val artistName: String = "",
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val duration: Double = 0.0,
        val hasWordSync: Boolean = false,
        val lyricsfile: String? = null,
    ) {
        fun usable() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
        fun sample() = (syncedLyrics ?: plainLyrics).orEmpty()
    }

    private val brackets = Regex("\\s*[(\\[{][^)\\]}]*[)\\]}]")
    private val featuring = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)

    /** "Kesariya (From "Brahmastra") [Official Video] | T-Series" becomes "Kesariya". */
    fun cleanTitle(title: String): String =
        title.replace(brackets, "").substringBefore(" | ").replace(featuring, "").trim().ifEmpty { title.trim() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The best single version (used where there's no picker, like Studio). */
    fun fetch(track: Track): Lyrics? = fetchAll(track).firstOrNull()

    /** Every version found, best first: word-synced, then line-synced, then plain text. */
    fun fetchAll(track: Track): List<Lyrics> {
        val base = cleanTitle(track.title)
        // YouTube titles are often "Artist - Song", so try both halves as well.
        val titles = listOf(base, base.substringBefore(" - "), base.substringAfter(" - ", ""))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val artist = track.artist.substringBefore(",").substringBefore(" & ").trim()
        val duration = if (track.durationMs > 0) "&duration=${track.durationMs / 1000}" else ""

        val found = ArrayList<Entry>()
        titles.firstNotNullOfOrNull { title ->
            runCatching {
                Http.json.decodeFromString<Entry>(Http.get("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$duration"))
            }.getOrNull()?.takeIf { it.usable() }
        }?.let { found += it }

        // The search brings the other scripts and the word-synced copies of the same song.
        for (title in titles) {
            val more = runCatching {
                Http.json.decodeFromString<List<Entry>>(Http.get("https://lrclib.net/api/search?q=${enc("$title $artist")}"))
            }.getOrNull().orEmpty().filter { it.usable() && sameSong(it, title, artist, track.durationMs) }
            found += more
            if (more.isNotEmpty()) break
        }

        // One version per script; within a script the richest timing wins, then the closest length.
        val versions = found.distinctBy { it.id }
            .groupBy { script(it.sample()) }
            .map { (label, entries) ->
                val best = entries.sortedWith(
                    compareByDescending<Entry> { it.hasWordSync }
                        .thenByDescending { !it.syncedLyrics.isNullOrBlank() }
                        .thenBy { if (track.durationMs > 0) abs(it.duration * 1000 - track.durationMs) else 0.0 },
                ).first()
                toLyrics(best, label)
            }
            .filter { it.lines.isNotEmpty() }
            .sortedWith(compareByDescending<Lyrics> { it.wordSync }.thenByDescending { it.synced })

        if (versions.isNotEmpty()) return versions
        return listOfNotNull(runCatching { netEase(base, artist, track.durationMs) }.getOrNull())
    }

    private fun sameSong(e: Entry, title: String, artist: String, durationMs: Long): Boolean {
        if (durationMs > 0 && abs(e.duration * 1000 - durationMs) > 15_000) return false
        val t = norm(cleanTitle(e.trackName))
        val want = norm(title)
        if (t.isEmpty() || want.isEmpty()) return false
        if (!(t == want || t.startsWith(want) || want.startsWith(t))) return false
        // With no duration to go by, the artist has to agree too.
        if (durationMs > 0 || artist.isBlank()) return true
        val a = norm(artist)
        return norm(e.artistName).let { it.contains(a) || a.contains(it) }
    }

    private fun toLyrics(e: Entry, label: String): Lyrics {
        if (e.hasWordSync) {
            val file = e.lyricsfile ?: runCatching {
                Http.json.decodeFromString<Entry>(Http.get("https://lrclib.net/api/get/${e.id}")).lyricsfile
            }.getOrNull()
            val lines = file?.let { runCatching { parseLyricsFile(it) }.getOrNull() }.orEmpty()
            if (lines.any { it.words.isNotEmpty() }) return Lyrics(lines, synced = true, label = label, wordSync = true)
        }
        e.syncedLyrics?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = parseLrc(lrc)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true, label = label, wordSync = lines.any { it.words.isNotEmpty() })
        }
        val plain = e.plainLyrics.orEmpty()
        return Lyrics(plain.lines().map { LyricLine(0, it) }.takeIf { plain.isNotBlank() }.orEmpty(), synced = false, label = label)
    }

    // ---- Formats -------------------------------------------------------------------------------

    private val stamp = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
    private val wordStamp = Regex("<(\\d+):(\\d+)(?:[.:](\\d+))?>")

    private fun ms(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    /** LRC, including the "enhanced" kind that carries a time before each word: `<00:12.34>word`. */
    fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().flatMap { line ->
        val body = line.replace(stamp, "").trim()
        val words = if (wordStamp.containsMatchIn(body)) {
            val marks = wordStamp.findAll(body).toList()
            marks.mapIndexedNotNull { i, m ->
                val (a, b, c) = m.destructured
                val end = marks.getOrNull(i + 1)?.range?.first ?: body.length
                val text = body.substring(m.range.last + 1, end)
                if (text.isBlank()) null else LyricWord(ms(a, b, c), text)
            }
        } else emptyList()
        val text = body.replace(wordStamp, "").replace(Regex("\\s+"), " ").trim()
        stamp.findAll(line).map { m ->
            val (min, sec, frac) = m.destructured
            LyricLine(ms(min, sec, frac), text, words = words)
        }.toList()
    }.sortedBy { it.timeMs }

    /**
     * LRCLIB's word-synced "lyricsfile": a small YAML document of lines, each with its words and
     * the millisecond each one starts. Read line by line; no YAML library needed for this shape.
     */
    fun parseLyricsFile(yaml: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        var text: String? = null
        var start = 0L
        var end = 0L
        var words = ArrayList<LyricWord>()
        var wordText: String? = null
        var inLines = false

        fun flush() {
            val t = text ?: return
            lines += LyricLine(start, t.trim(), end, words.toList())
            text = null; words = ArrayList(); wordText = null; start = 0; end = 0
        }
        for (raw in yaml.lines()) {
            if (!inLines) { if (raw.trimEnd() == "lines:") inLines = true; continue }
            val indent = raw.length - raw.trimStart().length
            val s = raw.trim()
            when {
                indent == 0 && s.startsWith("- text:") -> { flush(); text = scalar(s.removePrefix("- text:")) }
                indent == 2 && s.startsWith("start_ms:") -> start = s.substringAfter(":").trim().toLongOrNull() ?: 0
                indent == 2 && s.startsWith("end_ms:") -> end = s.substringAfter(":").trim().toLongOrNull() ?: 0
                indent == 2 && s.startsWith("- text:") -> wordText = scalar(s.removePrefix("- text:"))
                indent == 4 && s.startsWith("start_ms:") -> {
                    val w = wordText
                    val at = s.substringAfter(":").trim().toLongOrNull()
                    if (w != null && at != null && w.isNotEmpty()) words += LyricWord(at, w)
                    wordText = null
                }
                indent == 0 && s.isNotEmpty() && !s.startsWith("-") -> { flush(); inLines = false }
            }
        }
        flush()
        return lines.filter { it.text.isNotEmpty() || it.words.isNotEmpty() }.sortedBy { it.timeMs }
    }

    /** A YAML scalar: plain, 'single quoted' ('' is one quote) or "double quoted" (JSON escapes). */
    private fun scalar(raw: String): String {
        val s = raw.trim()
        return when {
            s.length >= 2 && s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1).replace("''", "'")
            s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") ->
                runCatching { Http.json.parseToJsonElement(s).jsonPrimitive.content }.getOrDefault(s.substring(1, s.length - 1))
            else -> s
        }
    }

    // ---- Scripts -------------------------------------------------------------------------------

    /** Names the writing system most of the text is in, in its own language where there is one. */
    fun script(text: String): String {
        val counts = HashMap<String, Int>()
        var seen = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            val name = when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.LATIN -> "Latin (A–Z)"
                Character.UnicodeScript.DEVANAGARI -> "हिन्दी"
                Character.UnicodeScript.GURMUKHI -> "ਪੰਜਾਬੀ"
                Character.UnicodeScript.BENGALI -> "বাংলা"
                Character.UnicodeScript.GUJARATI -> "ગુજરાતી"
                Character.UnicodeScript.TAMIL -> "தமிழ்"
                Character.UnicodeScript.TELUGU -> "తెలుగు"
                Character.UnicodeScript.KANNADA -> "ಕನ್ನಡ"
                Character.UnicodeScript.MALAYALAM -> "മലയാളം"
                Character.UnicodeScript.ORIYA -> "ଓଡ଼ିଆ"
                Character.UnicodeScript.ARABIC -> "اردو / عربي"
                Character.UnicodeScript.HANGUL -> "한국어"
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "日本語"
                Character.UnicodeScript.HAN -> "中文"
                Character.UnicodeScript.CYRILLIC -> "Кириллица"
                Character.UnicodeScript.THAI -> "ไทย"
                Character.UnicodeScript.GREEK -> "Ελληνικά"
                Character.UnicodeScript.HEBREW -> "עברית"
                else -> "Other"
            }
            counts[name] = (counts[name] ?: 0) + 1
            if (++seen >= 600) break
        }
        // Japanese mixes kana with Han; any real amount of kana means Japanese.
        if ((counts["日本語"] ?: 0) > seen / 10) return "日本語"
        return counts.maxByOrNull { it.value }?.key ?: "Latin (A–Z)"
    }

    // ---- NetEase -------------------------------------------------------------------------------

    private fun netEase(title: String, artist: String, durationMs: Long): Lyrics? {
        val headers = mapOf("Referer" to "https://music.163.com/")
        val search = Http.get("https://music.163.com/api/search/get?s=${enc("$title $artist")}&type=1&limit=8", headers)
        val songs = Http.json.parseToJsonElement(search).jsonObject["result"]?.jsonObject?.get("songs")?.jsonArray ?: return null
        val want = norm(title)
        val id = songs.map { it.jsonObject }.firstOrNull { song ->
            val name = norm(cleanTitle(song["name"]?.jsonPrimitive?.content.orEmpty()))
            val length = song["duration"]?.jsonPrimitive?.longOrNull ?: 0
            val artists = song["artists"]?.jsonArray?.joinToString(" ") { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
            val nameOk = name.isNotEmpty() && (name == want || name.startsWith(want) || want.startsWith(name))
            val lengthOk = durationMs <= 0 || length <= 0 || abs(length - durationMs) < 15_000
            val artistOk = artist.isBlank() || norm(artists).let { a -> a.contains(norm(artist)) || norm(artist).contains(a) }
            nameOk && lengthOk && (artistOk || (durationMs > 0 && length > 0 && abs(length - durationMs) < 4_000))
        }?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val body = Http.get("https://music.163.com/api/song/lyric?id=$id&lv=1&tv=1&rv=1", headers)
        val lrc = Http.json.parseToJsonElement(body).jsonObject["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content.orEmpty()
        // Credit lines ("作词 : …") come first with a timestamp; they aren't lyrics.
        val lines = parseLrc(lrc).filterNot { it.text.contains(" : ") && it.timeMs < 20_000 && (it.text.startsWith("作") || it.text.startsWith("编") || it.text.startsWith("制")) }
        if (lines.count { it.text.isNotBlank() } < 4) return null
        return Lyrics(lines, synced = true, label = script(lines.joinToString(" ") { it.text }))
    }
}
