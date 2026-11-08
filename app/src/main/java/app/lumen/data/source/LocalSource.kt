// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.lumen.data.Source
import app.lumen.data.Track

/** Songs stored on the phone, read from MediaStore. */
class LocalSource(private val context: Context) {

    fun tracks(): List<Track> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        val result = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist"
                    result += Track(
                        id = "local:$id",
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = artist,
                        album = c.getString(albumCol),
                        artwork = ContentUris.withAppendedId(ALBUM_ART, c.getLong(albumIdCol)).toString(),
                        durationMs = c.getLong(durationCol),
                        source = Source.LOCAL,
                        ref = ContentUris.withAppendedId(uri, id).toString(),
                    )
                }
            }
        }
        return result
    }

    companion object {
        private val ALBUM_ART = android.net.Uri.parse("content://media/external/audio/albumart")
    }
}
