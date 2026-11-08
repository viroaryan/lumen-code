// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.lumen.Graph
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.data.source.Http

/** Tracks currently known to the player, so the data source can turn a media id back into a stream. */
object TrackRegistry {
    // Bounded (least recently used go first) so hours of autoplay can't grow it without limit. Far
    // larger than any queue, and anything evicted is rebuilt from the media item's own metadata.
    private val tracks = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Track>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 5000
    })
    fun register(track: Track) { tracks[track.id] = track }
    operator fun get(id: String): Track? = tracks[id]
}

const val LUMEN_SCHEME = "lumen"
private const val EXTRA_TRACK = "lumen.track"
/** Marks songs the user put in the queue themselves ("Add to queue"), so later ones line up behind them. */
private const val EXTRA_QUEUED = "lumen.queued"

/** True for a song added with "Add to queue". */
val MediaItem.isQueued: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_QUEUED, false) == true

fun Track.toMediaItem(queued: Boolean = false): MediaItem {
    val path = Graph.store.downloadPath(id)
    val downloaded = path != null
    // Downloaded songs carry a local copy of their artwork, so it shows offline too.
    val saved = if (downloaded) Graph.store.state.value.downloads[id]?.artwork else null
    if (saved != null && saved != artwork) return copy(artwork = saved).toMediaItem(queued)
    TrackRegistry.register(this)
    val uri = when {
        source == Source.LOCAL -> ref.toUri()
        // Straight from the file: no stream lookup and no second copy in the song cache.
        path != null -> Uri.fromFile(java.io.File(path))
        else -> Uri.Builder().scheme(LUMEN_SCHEME).authority("t").appendPath(id).build()
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setCustomCacheKey(id)
        .setMimeType(if (source == Source.SOUNDCLOUD && !downloaded) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork?.toUri())
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle().apply {
                    putString(EXTRA_TRACK, Http.json.encodeToString(Track.serializer(), this@toMediaItem))
                    if (queued) putBoolean(EXTRA_QUEUED, true)
                })
                .build(),
        )
        .build()
}

val MediaItem.track: Track?
    get() = TrackRegistry[mediaId] ?: mediaMetadata.extras?.getString(EXTRA_TRACK)?.let {
        runCatching { Http.json.decodeFromString(Track.serializer(), it) }.getOrNull()?.also(TrackRegistry::register)
    }
