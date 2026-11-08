// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import app.lumen.data.source.Http
import java.util.Base64
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Playlists shared as plain text, with no server and no account.
 *
 * The whole playlist is packed into one short code (`lumen:pl1:…`): song ids, titles and artists,
 * compressed and written in URL-safe letters. Anyone can send it over WhatsApp, Telegram, email or a
 * note to themselves; pasting it into Lumen's search (or sharing the message to Lumen) rebuilds the
 * playlist. Nothing about the songs is stored anywhere except inside the code itself.
 *
 * Codes come from outside the app, so decoding treats them as hostile: sizes are capped, every field
 * is checked, and stream links are rebuilt from ids rather than trusted.
 */
object PlaylistShare {
    const val PREFIX = "lumen:pl1:"
    private val codePattern = Regex("lumen:pl1:([A-Za-z0-9_-]{8,})")

    private const val MAX_CODE_CHARS = 400_000
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_TRACKS = 5_000

    /** One song in the code: a source letter, its id, and just enough to show it before it plays. */
    @Serializable
    private data class Packed(
        val n: String,
        val t: List<List<String>>,
    )

    data class Encoded(val code: String, val shared: Int, val skipped: Int)
    data class Decoded(val name: String, val tracks: List<Track>)

    /** Packs [tracks] into a code. Songs stored only on this phone can't travel and are left out. */
    fun encode(name: String, tracks: List<Track>): Encoded {
        val rows = tracks.distinctBy { it.id }.mapNotNull { pack(it) }
        val packed = Packed(name.take(120), rows)
        val json = Http.json.encodeToString(Packed.serializer(), packed).toByteArray()
        val code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json))
        return Encoded(code, rows.size, tracks.size - rows.size)
    }

    /** The message people actually send: a human line on top, the code at the bottom. */
    fun message(name: String, encoded: Encoded): String = buildString {
        append("🎧 \"").append(name).append("\" · ").append(encoded.shared).append(" songs\n\n")
        append("To open it: copy this whole message, open Lumen, and paste it in Search.\n\n")
        append(encoded.code)
    }

    /** Finds a playlist code anywhere in pasted or shared text. */
    fun find(text: String): String? {
        if (text.length > MAX_CODE_CHARS + 10_000) return null
        return codePattern.find(text)?.value
    }

    fun decode(code: String): Decoded? = runCatching {
        val body = code.removePrefix(PREFIX).trim()
        if (body.length > MAX_CODE_CHARS) return null
        val bytes = Base64.getUrlDecoder().decode(body)
        val json = inflate(bytes) ?: return null
        val packed = Http.json.decodeFromString(Packed.serializer(), json.decodeToString())
        val tracks = packed.t.asSequence().take(MAX_TRACKS).mapNotNull { unpack(it) }.distinctBy { it.id }.toList()
        if (tracks.isEmpty()) null
        else Decoded(clean(packed.n, 120).ifBlank { "Shared playlist" }, tracks)
    }.getOrNull()

    // ---- Packing ------------------------------------------------------------------------------

    private const val FLAG_VIDEO = 1
    private const val FLAG_EXPLICIT = 2

    private fun pack(t: Track): List<String>? {
        val flags = (if (t.video) FLAG_VIDEO else 0) or (if (t.explicit) FLAG_EXPLICIT else 0)
        val seconds = (t.durationMs / 1000).toString()
        return when (t.source) {
            // YouTube artwork is rebuilt from the video id, so it costs nothing in the code.
            Source.YOUTUBE -> listOf("y", t.id.removePrefix("yt:"), t.title, t.artist, seconds, flags.toString())
            Source.SAAVN -> listOf("j", t.id.removePrefix("jsv:"), t.title, t.artist, seconds, flags.toString(), t.ref, t.artwork.orEmpty())
            Source.SOUNDCLOUD -> listOf("s", t.id.removePrefix("sc:"), t.title, t.artist, seconds, flags.toString(), "", t.artwork.orEmpty())
            Source.LOCAL -> null
        }
    }

    private val ytId = Regex("[A-Za-z0-9_-]{6,20}")
    private val saavnId = Regex("[A-Za-z0-9_-]{1,40}")
    private val scPath = Regex("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+){1,3}")
    private val saavnRef = Regex("[A-Za-z0-9+/=]{8,600}")

    private fun unpack(row: List<String>): Track? {
        if (row.size < 6) return null
        val key = row[1]
        val title = clean(row[2], 300).ifBlank { return null }
        val artist = clean(row[3], 300)
        val durationMs = (row[4].toLongOrNull() ?: 0L).coerceIn(0, 24 * 3600) * 1000
        val flags = row[5].toIntOrNull() ?: 0
        val video = flags and FLAG_VIDEO != 0
        val explicit = flags and FLAG_EXPLICIT != 0
        val art = row.getOrNull(7)?.let(::safeImage)
        return when (row[0]) {
            "y" -> if (!ytId.matches(key)) null else Track(
                id = "yt:$key", title = title, artist = artist, artwork = "https://i.ytimg.com/vi/$key/hqdefault.jpg",
                durationMs = durationMs, source = Source.YOUTUBE, ref = "https://www.youtube.com/watch?v=$key",
                explicit = explicit, video = video,
            )
            "j" -> {
                val ref = row.getOrNull(6).orEmpty()
                if (!saavnId.matches(key) || !saavnRef.matches(ref)) null else Track(
                    id = "jsv:$key", title = title, artist = artist, artwork = art,
                    durationMs = durationMs, source = Source.SAAVN, ref = ref, explicit = explicit,
                )
            }
            "s" -> if (!scPath.matches(key)) null else Track(
                id = "sc:$key", title = title, artist = artist, artwork = art,
                durationMs = durationMs, source = Source.SOUNDCLOUD, ref = "https://soundcloud.com/$key", explicit = explicit,
            )
            else -> null
        }
    }

    private val ytRef = Regex("https://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/\\S{1,200}")
    private val scRef = Regex("https://(www\\.|m\\.)?soundcloud\\.com/\\S{1,250}")

    /**
     * Checks a full song record from outside the app (a backup file). Returns a cleaned copy, or null if
     * anything about it looks wrong. Stream links must point at the source's own site.
     */
    fun safeTrack(t: Track): Track? {
        val title = clean(t.title, 300).ifBlank { return null }
        val okRef = when (t.source) {
            Source.YOUTUBE -> ytRef.matches(t.ref) && t.id.startsWith("yt:")
            Source.SOUNDCLOUD -> scRef.matches(t.ref) && t.id.startsWith("sc:")
            Source.SAAVN -> saavnRef.matches(t.ref) && t.id.startsWith("jsv:")
            // Phone files only make sense on the phone they came from; MediaStore links are harmless elsewhere.
            Source.LOCAL -> t.ref.startsWith("content://media/") && t.id.startsWith("local:")
        }
        if (!okRef || t.id.length > 260) return null
        val art = if (t.source == Source.LOCAL) t.artwork?.takeIf { it.startsWith("content://media/") } else safeImage(t.artwork)
        return t.copy(
            title = title, artist = clean(t.artist, 300), album = t.album?.let { clean(it, 300) },
            artwork = art, durationMs = t.durationMs.coerceIn(0, 24 * 3600 * 1000L),
        )
    }

    /** Text from outside: no control characters, bounded length. */
    fun clean(s: String?, max: Int): String = s.orEmpty().filter { !it.isISOControl() }.trim().take(max)

    /** Artwork must be a normal https image link; anything else is dropped. */
    fun safeImage(url: String?): String? =
        url?.trim()?.takeIf { it.length in 12..600 && it.startsWith("https://") && it.none { c -> c.isWhitespace() } }

    // ---- Compression --------------------------------------------------------------------------

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates with a hard ceiling, so a crafted code can't expand into gigabytes. */
    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON_BYTES) return null
            }
            if (!inflater.finished()) null else out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
