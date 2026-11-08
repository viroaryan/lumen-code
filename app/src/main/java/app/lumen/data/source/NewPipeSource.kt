// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import android.net.Uri
import app.lumen.data.Collection
import app.lumen.data.CollectionDetail
import app.lumen.data.HomeSection
import app.lumen.data.Kind
import app.lumen.data.SearchResults
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.engine.SourceHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as YtFilters

enum class Quality { HIGH, NORMAL, LOW }

data class ResolvedStream(val url: String, val mimeType: String? = null)

/** YouTube Music and SoundCloud, both through NewPipeExtractor. */
class NewPipeSource(private val source: Source) {

    private val service: StreamingService =
        if (source == Source.YOUTUBE) ServiceList.YouTube else ServiceList.SoundCloud

    private val prefix = if (source == Source.YOUTUBE) "yt" else "sc"

    /** Where the next page of each search starts, keyed by filter and query. Bounded so it can't grow forever. */
    private val nextPages = ConcurrentHashMap<String, Page>()

    private fun remember(key: String, page: Page?) {
        if (page == null) { nextPages.remove(key); return }
        if (nextPages.size > 60) nextPages.clear()
        nextPages[key] = page
    }

    private fun search(query: String, filter: String): List<InfoItem> = SourceHealth.track(source) {
        service.getSearchExtractor(query, listOf(filter), "").let {
            it.fetchPage()
            val page = it.initialPage
            remember("$filter|$query", page.nextPage)
            page.items
        }
    }

    private fun more(query: String, filter: String): List<InfoItem> = SourceHealth.track(source) {
        val key = "$filter|$query"
        val page = nextPages[key] ?: return@track emptyList()
        val next = service.getSearchExtractor(query, listOf(filter), "").getPage(page)
        remember(key, next.nextPage)
        next.items
    }

    private val songFilter get() = if (source == Source.YOUTUBE) YtFilters.MUSIC_SONGS else SoundcloudSearchQueryHandlerFactory.TRACKS

    /** Live streams have no fixed audio file to play, so they're left out everywhere. */
    private fun StreamInfoItem.playable() = streamType != StreamType.LIVE_STREAM && streamType != StreamType.AUDIO_LIVE_STREAM

    fun searchTracks(query: String): List<Track> = runCatching {
        search(query, songFilter).filterIsInstance<StreamInfoItem>().filter { it.playable() }.map { it.toTrack() }
    }.getOrDefault(emptyList())

    /** Uploads that aren't in the music catalogue (live versions, regional songs, rare tracks). */
    private fun searchMusicVideos(query: String): List<Track> = runCatching {
        search(query, YtFilters.MUSIC_VIDEOS).filterIsInstance<StreamInfoItem>().filter { it.playable() && it.duration in 60..1200 }.map { it.toTrack() }
    }.getOrDefault(emptyList())

    /** Plain YouTube video search, where karaoke and instrumental uploads live. */
    fun searchAllVideos(query: String): List<Track> = runCatching {
        search(query, YtFilters.VIDEOS).filterIsInstance<StreamInfoItem>().filter { it.playable() && it.duration in 60..900 }.map { it.toTrack() }
    }.getOrDefault(emptyList())

    /**
     * Any YouTube video you could listen to: podcasts, lectures, talks, stories. Shorts are skipped by
     * [minSeconds]; long videos are welcome.
     */
    fun searchWebVideos(query: String, minSeconds: Long = 60): List<Track> = runCatching {
        search(query, YtFilters.VIDEOS).filterIsInstance<StreamInfoItem>()
            .filter { it.playable() && it.duration >= minSeconds }
            .map { it.toTrack(video = true) }
    }.getOrDefault(emptyList())

    /** The next page of [searchWebVideos]. */
    fun moreWebVideos(query: String): List<Track> = runCatching {
        more(query, YtFilters.VIDEOS).filterIsInstance<StreamInfoItem>().filter { it.playable() && it.duration >= 60 }.map { it.toTrack(video = true) }
    }.getOrDefault(emptyList())

    /** The next page of songs for a search already made; empty when there are no more. */
    fun moreTracks(query: String): List<Track> = runCatching {
        more(query, songFilter).filterIsInstance<StreamInfoItem>().filter { it.playable() }.map { it.toTrack() }
    }.getOrDefault(emptyList())

    private fun searchAlbums(query: String) = runCatching {
        search(query, YtFilters.MUSIC_ALBUMS).filterIsInstance<PlaylistInfoItem>().map { it.toCollection(Kind.ALBUM) }
    }.getOrDefault(emptyList())

    /** Every category is fetched in parallel, so a search costs one round trip instead of five. */
    suspend fun search(query: String): SearchResults = coroutineScope {
        val tracks = async(Dispatchers.IO) { searchTracks(query) }
        if (source == Source.SOUNDCLOUD) {
            val playlists = async(Dispatchers.IO) {
                runCatching {
                    search(query, SoundcloudSearchQueryHandlerFactory.PLAYLISTS).filterIsInstance<PlaylistInfoItem>().map { it.toCollection(Kind.PLAYLIST) }
                }.getOrDefault(emptyList())
            }
            return@coroutineScope SearchResults(tracks = tracks.await(), playlists = playlists.await())
        }
        val musicVideos = async(Dispatchers.IO) { searchMusicVideos(query) }
        val videos = async(Dispatchers.IO) { searchWebVideos(query) }
        val albums = async(Dispatchers.IO) { searchAlbums(query) }
        val artists = async(Dispatchers.IO) {
            runCatching { search(query, YtFilters.MUSIC_ARTISTS).filterIsInstance<ChannelInfoItem>().map { it.toArtist() } }.getOrDefault(emptyList())
        }
        val playlists = async(Dispatchers.IO) {
            runCatching {
                search(query, YtFilters.MUSIC_PLAYLISTS).filterIsInstance<PlaylistInfoItem>().map { it.toCollection(Kind.PLAYLIST) }
            }.getOrDefault(emptyList())
        }
        val songs = tracks.await()
        val extra = musicVideos.await().filter { v -> songs.none { it.id == v.id } }
        SearchResults(songs + extra, albums.await(), artists.await(), playlists.await(), videos.await())
    }

    fun suggestions(query: String): List<String> =
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())

    fun playlist(collection: Collection): CollectionDetail = SourceHealth.track(source) {
        val info = PlaylistInfo.getInfo(service, collection.ref)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && items.size < 300) {
            val more = PlaylistInfo.getMoreItems(service, collection.ref, page)
            items += more.items
            page = more.nextPage
        }
        val artwork = collection.artwork ?: info.thumbnails.best()
        val tracks = items.filter { it.playable() }.map { it.toTrack() }.map {
            if (collection.kind == Kind.ALBUM) it.copy(album = collection.title, artwork = artwork ?: it.artwork) else it
        }
        // Collections opened from a link only know their URL until now.
        val named = collection.copy(
            artwork = artwork,
            title = collection.title.takeUnless { it == "Playlist" } ?: info.name,
            subtitle = collection.subtitle.ifBlank { info.uploaderName.cleanArtist() },
        )
        CollectionDetail(named, tracks)
    }

    suspend fun artist(collection: Collection): CollectionDetail = coroutineScope {
        val name = collection.title
        val songs = async(Dispatchers.IO) {
            searchTracks(name).filter { it.artist.contains(name, ignoreCase = true) }.ifEmpty { searchTracks(name) }
        }
        val albums = async(Dispatchers.IO) {
            if (source == Source.YOUTUBE) searchAlbums(name).filter { it.subtitle.contains(name, ignoreCase = true) } else emptyList()
        }
        CollectionDetail(collection.copy(artwork = collection.artwork ?: songs.await().firstOrNull()?.artwork), songs.await(), albums.await())
    }

    fun related(track: Track): List<Track> = runCatching {
        SourceHealth.track(source) { StreamInfo.getInfo(service, track.ref) }.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.playable() && it.duration in 60..900 }
            .map { it.toTrack() }
    }.getOrDefault(emptyList())

    /** What to play after a podcast or lecture: more long-form videos like it, not music. */
    fun relatedVideos(track: Track): List<Track> = runCatching {
        SourceHealth.track(source) { StreamInfo.getInfo(service, track.ref) }.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.playable() && it.duration >= 180 }
            .map { it.toTrack(video = true) }
    }.getOrDefault(emptyList())

    /**
     * YouTube Music's own radio for a song (the "RDAMVM" mix): the same songs-only queue the official app
     * builds after you play something. Falls back to plain related videos.
     */
    fun radio(track: Track): List<Track> {
        if (source != Source.YOUTUBE) return related(track)
        if (track.video) return relatedVideos(track)
        val id = track.id.removePrefix("yt:")
        val mix = runCatching {
            SourceHealth.track(source) { PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=$id&list=RDAMVM$id") }.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.playable() }
                .map { it.toTrack() }
                .filter { it.id != track.id }
        }.getOrDefault(emptyList())
        return mix.ifEmpty { related(track) }
    }

    /** A YouTube Music chart playlist, as a home shelf. */
    fun chart(title: String, playlistId: String, limit: Int = 30): HomeSection? = runCatching {
        val items = SourceHealth.track(source) { PlaylistInfo.getInfo(service, "https://www.youtube.com/playlist?list=$playlistId") }.relatedItems
        HomeSection(title, tracks = items.filterIsInstance<StreamInfoItem>().filter { it.playable() }.take(limit).map { it.toTrack() })
    }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }

    /**
     * Turns a shared link (youtube.com, music.youtube.com, youtu.be, shorts, soundcloud.com) into a track.
     * Anything that isn't music on YouTube (a podcast, lecture, talk) comes back marked as a video.
     */
    fun trackFromUrl(url: String): Track {
        val info = SourceHealth.track(source) { StreamInfo.getInfo(service, url) }
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw IllegalStateException("Live streams can't be played")
        }
        val id = if (source == Source.YOUTUBE) info.id else info.url.substringAfter("soundcloud.com/")
        val category = runCatching { info.category }.getOrNull().orEmpty()
        val music = source != Source.YOUTUBE || "music.youtube" in url || category.equals("Music", ignoreCase = true)
        return Track(
            id = "$prefix:$id",
            title = info.name,
            artist = info.uploaderName.cleanArtist(),
            artwork = info.thumbnails.best(),
            durationMs = info.duration * 1000,
            source = source,
            ref = info.url,
            video = !music,
        )
    }

    /**
     * Only the audio stream list is needed to play, so the extractor is asked for just that, skipping
     * the related videos, comments-info, subtitles and other fields StreamInfo builds. The full
     * StreamInfo path stays as a fallback.
     */
    private fun audioStreams(url: String): List<AudioStream> = runCatching {
        service.getStreamExtractor(url).run {
            fetchPage()
            audioStreams
        }.takeIf { it.isNotEmpty() } ?: error("No audio streams")
    }.getOrElse { e ->
        // A network failure would fail the same way again; only parsing trouble is worth the full path.
        if (e is java.io.IOException) throw e
        StreamInfo.getInfo(service, url).audioStreams
    }

    fun resolve(track: Track, quality: Quality): ResolvedStream = SourceHealth.track(source) {
        val streams = audioStreams(track.ref).filter { it.isUrl }
        if (source == Source.SOUNDCLOUD) {
            val hls = streams.filter { it.deliveryMethod == DeliveryMethod.HLS }
            val pick = hls.maxByOrNull { it.averageBitrate } ?: error("No playable SoundCloud stream")
            return@track ResolvedStream(pick.content, "application/x-mpegURL")
        }
        val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        val pick = when (quality) {
            Quality.HIGH -> progressive.maxByOrNull { it.bitrateKbps() }
            // Spoken word sounds the same at a lower bitrate; long videos use less data and cache.
            Quality.NORMAL -> progressive.minByOrNull { kotlin.math.abs(it.bitrateKbps() - (if (track.video) 64 else 128)) }
            Quality.LOW -> progressive.minByOrNull { it.bitrateKbps() }
        } ?: error("No playable audio stream")
        ResolvedStream(pick.content)
    }

    private fun AudioStream.bitrateKbps(): Int = (if (averageBitrate > 0) averageBitrate else bitrate / 1000)

    private fun InfoItem.playable() = (this as? StreamInfoItem)?.playable() ?: true

    private fun InfoItem.toTrack(video: Boolean = false): Track {
        val item = this as? StreamInfoItem
        val id = if (source == Source.YOUTUBE) videoId(url) else url.substringAfter("soundcloud.com/")
        return Track(
            id = "$prefix:$id",
            title = name.orEmpty(),
            artist = item?.uploaderName.cleanArtist(),
            artwork = thumbnails.best(),
            durationMs = (item?.duration ?: 0L).coerceAtLeast(0) * 1000,
            source = source,
            ref = url,
            video = video,
        )
    }

    private fun PlaylistInfoItem.toCollection(kind: String) = Collection(
        id = "$prefix:$kind:${url.hashCode()}",
        kind = kind,
        title = name.orEmpty(),
        subtitle = uploaderName.cleanArtist(),
        artwork = thumbnails.best(),
        source = source,
        ref = url,
    )

    private fun ChannelInfoItem.toArtist() = Collection(
        id = "$prefix:artist:${url.hashCode()}",
        kind = Kind.ARTIST,
        title = name.orEmpty(),
        subtitle = "Artist",
        artwork = thumbnails.best(),
        source = source,
        ref = url,
    )

    companion object {
        fun videoId(url: String): String =
            runCatching { Uri.parse(url).getQueryParameter("v") }.getOrNull() ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/')

        private val sizeParam = Regex("=w\\d+-h\\d+[^/]*$")
        private val sizePath = Regex("/w\\d+-h\\d+[^/]*/")

        /** Picks the largest image and asks Google's image CDN for a crisp square. */
        fun List<Image>.best(): String? {
            val url = maxByOrNull { it.width * it.height }?.url ?: return null
            return when {
                url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
                    url.replace(sizeParam, "=w600-h600-l90-rj").replace(sizePath, "/w600-h600-l90-rj/")
                url.contains("sndcdn.com") -> url.replace(Regex("-(large|t\\d+x\\d+)\\."), "-t500x500.")
                else -> url
            }
        }

        fun String?.cleanArtist(): String = orEmpty().removeSuffix(" - Topic").trim()
    }
}
