// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import android.content.Context
import android.net.Uri
import app.lumen.data.source.Http
import app.lumen.data.source.LocalSource
import app.lumen.data.source.LyricsSource
import app.lumen.data.source.NewPipeSource
import app.lumen.data.source.Quality
import app.lumen.data.source.ResolvedStream
import app.lumen.data.source.SaavnSource
import app.lumen.engine.Genre
import app.lumen.engine.SourceHealth
import app.lumen.engine.TasteEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class MusicRepository(
    context: Context,
    private val local: LocalSource,
    private val store: LibraryStore,
    private val taste: TasteEngine,
) {

    val youtube = NewPipeSource(Source.YOUTUBE)
    val soundcloud = NewPipeSource(Source.SOUNDCLOUD)
    val saavn = SaavnSource()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + app.lumen.engine.Guardian.handler)

    /** Sources that are switched on and not currently failing. */
    private fun on(source: Source) = source in store.settings.sources && SourceHealth.available(source)

    // ---- Search -------------------------------------------------------------------------------

    /** Emits the results gathered so far each time a source answers, so the fastest one shows at once. */
    fun search(query: String): Flow<Map<Source, SearchResults>> = channelFlow {
        val results = ConcurrentHashMap<Source, SearchResults>()
        val lock = Mutex()
        suspend fun publish(source: Source, r: SearchResults) = lock.withLock {
            results[source] = r
            send(results.toMap())
        }
        launch { publish(Source.LOCAL, SearchResults(tracks = localTracks().filter { it.matches(query) })) }
        if (on(Source.YOUTUBE)) launch { publish(Source.YOUTUBE, runCatching { youtube.search(query) }.getOrDefault(SearchResults())) }
        if (on(Source.SAAVN)) launch { publish(Source.SAAVN, runCatching { saavn.search(query) }.getOrDefault(SearchResults())) }
        if (on(Source.SOUNDCLOUD)) launch { publish(Source.SOUNDCLOUD, runCatching { soundcloud.search(query) }.getOrDefault(SearchResults())) }
    }.flowOn(Dispatchers.IO)

    private val coverWords = listOf("cover", "karaoke", "instrumental", "slowed", "reverb", "lofi", "lo-fi", "8d", "remix", "mashup", "sped up", "nightcore", "ringtone")

    /**
     * Ranks songs from every catalogue together: how well the title and artist match the query, a
     * preference for official catalogues over user uploads, a penalty for covers and edits the query
     * didn't ask for, and a nudge towards artists you love. Near-duplicates collapse into the best copy.
     */
    fun merge(query: String, results: Map<Source, SearchResults>): SearchResults {
        val q = query.lowercase().trim()
        val words = q.split(Regex("\\s+")).filter { it.length > 1 }
        val sourceWeight = mapOf(Source.LOCAL to 1.15, Source.YOUTUBE to 1.0, Source.SAAVN to 0.97, Source.SOUNDCLOUD to 0.55)

        fun score(t: Track, rank: Int): Double {
            val title = t.title.lowercase()
            val artist = t.artist.lowercase()
            val hay = "$title $artist"
            val coverage = if (words.isEmpty()) 0.0 else words.count { hay.contains(it) }.toDouble() / words.size
            val titleHit = if (q.contains(LyricsSource.cleanTitle(title)) || title.startsWith(q)) 0.3 else 0.0
            val edits = coverWords.count { title.contains(it) && !q.contains(it) } * 0.45
            val position = 1.0 / (1 + rank * 0.18)
            val loved = (taste.artistScore(t.artist) / 40.0).coerceIn(-0.2, 0.15)
            return (coverage + titleHit + position - edits + loved) * (sourceWeight[t.source] ?: 1.0)
        }

        val scored = results.flatMap { (_, r) -> r.tracks.mapIndexed { i, t -> t to score(t, i) } }.sortedByDescending { it.second }
        val kept = mutableListOf<Track>()
        for ((track, _) in scored) {
            if (kept.none { it.sameSongAs(track) }) kept += track
        }

        val order = listOf(Source.LOCAL, Source.YOUTUBE, Source.SAAVN, Source.SOUNDCLOUD)
        fun <T> interleave(pick: (SearchResults) -> List<T>): List<T> {
            val lists = order.mapNotNull { results[it] }.map(pick)
            return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        }
        val artists = interleave { it.artists }.distinctBy { it.title.lowercase() }
            .sortedByDescending { it.title.equals(query.trim(), ignoreCase = true) }
        val videos = results[Source.YOUTUBE]?.videos.orEmpty().distinctBy { it.id }
        return SearchResults(kept, interleave { it.albums }, artists, interleave { it.playlists }, videos)
    }

    private fun Track.sameSongAs(other: Track): Boolean {
        val a = LyricsSource.cleanTitle(title).lowercase().filter { it.isLetterOrDigit() }
        val b = LyricsSource.cleanTitle(other.title).lowercase().filter { it.isLetterOrDigit() }
        if (a.isEmpty() || a != b) return false
        val artistsA = artist.lowercase().split(',', '&').map { it.trim() }.filter { it.isNotEmpty() }
        val artistsB = other.artist.lowercase().split(',', '&').map { it.trim() }.filter { it.isNotEmpty() }
        return artistsA.any { x -> artistsB.any { y -> x.contains(y) || y.contains(x) } }
    }

    /** The next page of songs from every catalogue for a search already shown. [round] starts at 1. */
    suspend fun moreTracks(query: String, round: Int): List<Track> = withContext(Dispatchers.IO) {
        val yt = async { if (on(Source.YOUTUBE)) youtube.moreTracks(query) else emptyList() }
        val jsv = async { if (on(Source.SAAVN)) saavn.searchTracks(query, page = round + 1) else emptyList() }
        val sc = async { if (on(Source.SOUNDCLOUD)) soundcloud.moreTracks(query) else emptyList() }
        val lists = listOf(yt.await(), jsv.await(), sc.await())
        (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }

    /** The next page of YouTube videos for a search already shown. */
    suspend fun moreVideos(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (Source.YOUTUBE in store.settings.sources) youtube.moreWebVideos(query) else emptyList()
    }

    sealed interface Link {
        data class Song(val track: Track) : Link
        data class List(val collection: Collection) : Link
    }

    /** Opens a pasted or shared link from YouTube, YouTube Music, JioSaavn or SoundCloud. */
    suspend fun openLink(url: String): Link? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()
            val list = runCatching { uri.getQueryParameter("list") }.getOrNull()
            val hasVideo = runCatching { uri.getQueryParameter("v") }.getOrNull() != null || "youtu.be" in host || "/shorts/" in url || "/live/" in url
            when {
                // A playlist link (but not a watch link that happens to sit inside a mix).
                ("youtube" in host || "youtu.be" in host) && list != null && !hasVideo && !list.startsWith("RD") ->
                    Link.List(Collection("yt:playlist:$list", Kind.PLAYLIST, "Playlist", "", null, Source.YOUTUBE, "https://www.youtube.com/playlist?list=$list"))
                "youtube" in host || "youtu.be" in host -> Link.Song(youtube.trackFromUrl(url))
                "soundcloud" in host && "/sets/" in url ->
                    Link.List(Collection("sc:playlist:${url.hashCode()}", Kind.PLAYLIST, "Playlist", "", null, Source.SOUNDCLOUD, url))
                "soundcloud" in host -> Link.Song(soundcloud.trackFromUrl(url))
                "jiosaavn" in host && "/song/" in url -> saavn.songFromUrl(url)?.let { Link.Song(it) }
                "jiosaavn" in host && "/album/" in url ->
                    Link.List(Collection("jsv:album:${url.hashCode()}", Kind.ALBUM, "Album", "", null, Source.SAAVN, url))
                "jiosaavn" in host -> Link.List(Collection("jsv:playlist:${url.hashCode()}", Kind.PLAYLIST, "Playlist", "", null, Source.SAAVN, url))
                else -> null
            }
        }.getOrNull()
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !SourceHealth.available(Source.YOUTUBE)) emptyList() else youtube.suggestions(query).take(8)
    }

    // ---- Browse -------------------------------------------------------------------------------

    suspend fun collection(collection: Collection): CollectionDetail = withContext(Dispatchers.IO) {
        when (collection.source) {
            Source.SAAVN -> {
                // Artists opened by name carry no link yet: look the artist up first.
                val target = if (collection.ref.isBlank()) {
                    saavn.search(collection.title).artists.firstOrNull()?.let { collection.copy(ref = it.ref, artwork = it.artwork) }
                        ?: return@withContext youtube.artist(collection.copy(source = Source.YOUTUBE))
                } else collection
                saavn.collection(target)
            }
            Source.YOUTUBE, Source.SOUNDCLOUD -> {
                val src = if (collection.source == Source.YOUTUBE) youtube else soundcloud
                if (collection.kind == Kind.ARTIST) src.artist(collection) else src.playlist(collection)
            }
            Source.LOCAL -> {
                val tracks = localTracks().filter {
                    if (collection.kind == Kind.ARTIST) it.artist == collection.title else it.album == collection.title
                }
                CollectionDetail(collection.copy(artwork = tracks.firstOrNull()?.artwork), tracks)
            }
        }
    }

    private val homeFile = File(context.cacheDir, "home.json")
    private val homeSerializer = ListSerializer(HomeSection.serializer())

    /** The last home feed, saved on disk, so the app opens with music on screen even before the network answers. */
    fun cachedHome(): List<HomeSection> = runCatching { Http.json.decodeFromString(homeSerializer, homeFile.readText()) }.getOrDefault(emptyList())
    val homeIsFresh get() = System.currentTimeMillis() - homeFile.lastModified() < 20 * 60 * 1000

    private val artistCache = ConcurrentHashMap<String, Collection>()

    /** An artist's photo and page, looked up once and remembered. */
    private fun artistProfile(name: String, fallbackArt: String?, source: Source): Collection {
        artistCache[name.lowercase()]?.let { return it }
        val found = if (on(Source.SAAVN)) runCatching { saavn.findArtist(name) }.getOrNull() else null
        val profile = found ?: Collection(
            "artist:${source.name}:$name", Kind.ARTIST, name, "Artist", fallbackArt,
            if (source == Source.LOCAL) Source.LOCAL else Source.YOUTUBE, "",
        )
        if (artistCache.size > 200) artistCache.clear()
        artistCache[name.lowercase()] = profile
        return profile
    }

    private fun primaryArtist(t: Track) = TasteEngine.primaryArtist(t.artist)

    private fun genreShelf(g: Genre, seed: Long): HomeSection? {
        val songs = if (g.indian && on(Source.SAAVN)) saavn.searchTracks(g.query, 30) else if (on(Source.YOUTUBE)) youtube.searchTracks(g.query) else emptyList()
        val fresh = songs.filterNot { taste.skipped(it.id) }
        if (fresh.size < 4) return null
        // A light shuffle of the top results keeps the shelf fresh between visits.
        return HomeSection(g.title, tracks = fresh.take(30).shuffled(java.util.Random(seed)).take(20))
    }

    /**
     * Long-form listening picked for you: podcasts, lectures, talks, comedy. Topics follow what you
     * search and play, plus the time of day (lectures by day, stories at night).
     */
    private suspend fun videoShelves(hour: Int, seed: Long): List<HomeSection> = coroutineScope {
        if (!on(Source.YOUTUBE)) return@coroutineScope emptyList()
        val topics = taste.rankedTopics(hour, java.util.Random(seed)).take(3)
        val picks = topics.map { t ->
            async { t to youtube.searchWebVideos(t.query, minSeconds = 240).filterNot { taste.skipped(it.id) }.take(12) }
        }.awaitAll()
        val channels = taste.topChannels(2)
        val fromChannels = channels.map { ch ->
            async { youtube.searchWebVideos(ch, minSeconds = 240).filter { it.artist.equals(ch, ignoreCase = true) }.take(10) }
        }.awaitAll().flatten()

        val lists = picks.map { it.second }
        val mixed = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.id }.take(18)
        buildList {
            if (mixed.size >= 4) add(HomeSection("Podcasts, talks & lectures", tracks = mixed, subtitle = "Listen like music", style = HomeSection.STYLE_VIDEO))
            if (fromChannels.size >= 3) add(HomeSection("From channels you listen to", tracks = fromChannels.distinctBy { it.id }, subtitle = "Made for you", style = HomeSection.STYLE_VIDEO))
            val (top, videos) = picks.firstOrNull() ?: return@buildList
            if (taste.topicScore(top) >= 3f && videos.size >= 4) {
                add(HomeSection("More ${top.name.lowercase()}", tracks = videos, subtitle = "Because you keep coming back", style = HomeSection.STYLE_VIDEO))
            }
        }
    }

    /**
     * Builds the home feed, emitting it shelf by shelf (in a fixed order) as each source answers.
     * Personal shelves come from what you play; genre and video shelves rotate with your taste and the hour.
     */
    fun home(): Flow<List<HomeSection>> = channelFlow {
        val state = store.state.value
        val history = state.history
        val seedTime = System.currentTimeMillis() / (20 * 60 * 1000)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val yt = on(Source.YOUTUBE)
        val jsv = on(Source.SAAVN)
        val songs = history.filter { !it.video }

        // Your artists: plays and likes, faded by time, minus the ones you keep skipping.
        val topArtists = songs.filter { it.artist.isNotBlank() }
            .groupBy { primaryArtist(it) }
            .entries.map { (name, ts) -> Triple(name, ts.first(), ts.sumOf { state.plays[it.id] ?: 1 } + taste.artistScore(name) * 2) }
            .filter { it.first.isNotBlank() && !it.first.contains("unknown", true) && taste.artistScore(it.first) > -1f }
            .sortedByDescending { it.third }
            .map { it.first to it.second }
            .take(8)
        val byPlays = songs.sortedByDescending { state.plays[it.id] ?: 0 }
        val seed = songs.firstOrNull { it.source != Source.LOCAL && !taste.skipped(it.id) }
        val second = byPlays.firstOrNull { it.id != seed?.id && it.source != Source.LOCAL && !taste.skipped(it.id) }

        // Genres: what you already like first, then ones that suit the hour, then a rotating pick.
        val listened = songs.take(120).joinToString(" ") { "${it.title} ${it.artist}" }.lowercase()
        val rnd = java.util.Random(seedTime)
        val rotating = TasteEngine.genres.sortedByDescending { g ->
            taste.genreScore(g) * 1.5 + g.hints.count { listened.contains(it) } * 2.0 +
                (if (g.hours != null && hour in g.hours) 2.0 else 0.0) + rnd.nextDouble() * 2.5
        }.take(3)

        val slots = arrayOfNulls<List<HomeSection>>(20)
        val lock = Mutex()
        suspend fun fill(i: Int, sections: List<HomeSection>) = lock.withLock {
            slots[i] = sections
            send(slots.filterNotNull().flatten())
        }
        fun slot(i: Int, block: suspend () -> List<HomeSection>) = launch { fill(i, runCatching { block() }.getOrDefault(emptyList())) }

        val saavnHome = if (jsv) async { runCatching { saavn.launch() }.getOrDefault(emptyList()) } else null

        slot(0) {
            val radio = seed?.let { radio(it) }.orEmpty().filterNot { taste.skipped(it.id) }.take(25)
            if (seed == null || radio.isEmpty()) emptyList()
            else listOf(HomeSection("Because you listened to ${LyricsSource.cleanTitle(seed.title)}", tracks = radio, subtitle = "Made for you"))
        }
        slot(1) { saavnHome?.await()?.take(1).orEmpty() }
        slot(2) {
            if (topArtists.size < 3) emptyList()
            else coroutineScope {
                val profiles = topArtists.map { (name, t) -> async { artistProfile(name, t.artwork, t.source) } }.map { it.await() }
                listOf(HomeSection("Your top artists", collections = profiles, subtitle = "Based on your listening"))
            }
        }
        // New releases from the artists you play, picked out of JioSaavn's new and trending lists.
        slot(3) {
            val loved = (topArtists.map { it.first } + taste.topArtists(6)).map { it.lowercase() }.distinct()
            if (loved.isEmpty()) return@slot emptyList()
            val fresh = saavnHome?.await().orEmpty().flatMap { it.tracks }
                .filter { t -> loved.any { a -> t.artist.lowercase().contains(a) } }
                .distinctBy { it.id }
            if (fresh.size < 2) emptyList() else listOf(HomeSection("New from artists you love", tracks = fresh.take(20), subtitle = "Fresh drops"))
        }
        slot(4) { if (yt) listOfNotNull(youtube.chart("Top songs in India", "PL4fGSI1pDJn4pTWyM3t61lOyZ6_4jcNOw")) else emptyList() }
        slot(5) { listOfNotNull(genreShelf(Genre("Bollywood hits", "latest bollywood hits", indian = true), seedTime)) }
        slot(6) { videoShelves(hour, seedTime) }
        slot(7) {
            if (!jsv) emptyList()
            else listOf(HomeSection("Popular artists", collections = saavn.topArtists().take(16))).filter { it.collections.isNotEmpty() }
        }
        slot(8) { if (yt) listOfNotNull(youtube.chart("Hollywood hits", "PL4fGSI1pDJn6O1LS0XSdF3RyO0Rq_LDeI")) else emptyList() }
        slot(9) { saavnHome?.await()?.drop(1)?.take(3).orEmpty() }
        slot(10) {
            val top = topArtists.firstOrNull()?.first
            val found = if (top != null && yt) youtube.searchTracks(top).filter { it.artist.contains(top, ignoreCase = true) } else emptyList()
            if (top == null || found.size < 4) emptyList()
            else listOf(HomeSection("More from $top", tracks = found.take(20), subtitle = "Because you play them a lot"))
        }
        rotating.forEachIndexed { i, g -> slot(11 + i) { listOfNotNull(genreShelf(g, seedTime + i)) } }
        slot(14) { if (yt) listOfNotNull(youtube.chart("Top songs worldwide", "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i")) else emptyList() }
        slot(15) {
            val mix = second?.let { radio(it) }.orEmpty().filterNot { taste.skipped(it.id) }.take(25)
            if (second == null || mix.isEmpty()) emptyList()
            else listOf(HomeSection("Inspired by ${LyricsSource.cleanTitle(second.title)}", tracks = mix, subtitle = "Made for you"))
        }
        slot(16) {
            val next = topArtists.getOrNull(1)?.first
            val found = if (next != null && yt) youtube.searchTracks(next).filter { it.artist.contains(next, ignoreCase = true) } else emptyList()
            if (next == null || found.size < 4) emptyList()
            else listOf(HomeSection("$next mix", tracks = found.take(20), subtitle = "Made for you"))
        }
        slot(17) { listOfNotNull(genreShelf(Genre("Phonk", "phonk"), seedTime)) }
        slot(18) { saavnHome?.await()?.drop(4).orEmpty() }
    }.flowOn(Dispatchers.IO)

    fun saveHome(sections: List<HomeSection>) {
        if (sections.isEmpty()) return
        io.launch { runCatching { homeFile.writeText(Http.json.encodeToString(homeSerializer, sections)) } }
    }

    /** Songs to play after [track], from the catalogue that knows it best. Videos lead to more videos. */
    suspend fun radio(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val songs = when {
            track.video -> youtube.relatedVideos(track)
            track.source == Source.YOUTUBE -> youtube.radio(track).ifEmpty { if (on(Source.SAAVN)) saavnRadioFor(track) else emptyList() }
            track.source == Source.SAAVN -> (if (on(Source.SAAVN)) saavn.radio(track) else emptyList()).ifEmpty { youtubeRadioFor(track) }
            track.source == Source.SOUNDCLOUD -> soundcloud.related(track)
            else -> youtubeRadioFor(track)
        }
        songs.filterNot { taste.skipped(it.id) }.distinctBy { LyricsSource.cleanTitle(it.title).lowercase() }
    }

    private fun youtubeRadioFor(track: Track): List<Track> {
        if (!on(Source.YOUTUBE)) return saavnRadioFor(track)
        val match = youtube.searchTracks("${LyricsSource.cleanTitle(track.title)} ${track.artist.substringBefore(",")}").firstOrNull()
        return match?.let { listOf(it) + youtube.radio(it) }.orEmpty()
    }

    /** When YouTube is down, JioSaavn's radio for the same song keeps music flowing. */
    private fun saavnRadioFor(track: Track): List<Track> {
        val match = saavn.searchTracks("${LyricsSource.cleanTitle(track.title)} ${track.artist.substringBefore(",")}", 5).firstOrNull()
        return match?.let { listOf(it) + saavn.radio(it) }.orEmpty()
    }

    suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) { LyricsSource.fetch(track) }

    @Volatile private var localCache: List<Track>? = null
    fun localTracks(refresh: Boolean = false): List<Track> {
        if (refresh || localCache == null) localCache = local.tracks()
        return localCache.orEmpty()
    }

    // ---- Playback -----------------------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class Cached(val url: String, val mimeType: String? = null, val at: Long, val expires: Long) {
        val stream get() = ResolvedStream(url, mimeType)
        fun fresh(now: Long) = now < expires
    }
    private val resolved = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ResolvedStream>>()

    /**
     * Stream links looked up earlier are kept on disk until shortly before they expire, so the first tap
     * after reopening the app plays at once instead of waiting 1–3 s for YouTube to answer again.
     */
    private val streamFile = File(context.cacheDir, "streams.json")
    private val streamSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Cached.serializer())

    init {
        io.launch {
            val now = System.currentTimeMillis()
            runCatching { Http.json.decodeFromString(streamSerializer, streamFile.readText()) }.getOrNull()
                ?.filterValues { it.fresh(now) }
                ?.forEach { (k, v) -> resolved.putIfAbsent(k, v) }
        }
    }

    fun saveStreamCache() {
        val now = System.currentTimeMillis()
        val keep = resolved.filterValues { it.fresh(now) }.entries.sortedByDescending { it.value.at }.take(300).associate { it.key to it.value }
        runCatching { SafeFile.write(streamFile, Http.json.encodeToString(streamSerializer, keep)) }
    }

    /** When a stream link stops working: YouTube says so in the link itself; others get a safe default. */
    private fun expiryOf(track: Track, url: String, now: Long): Long {
        val stated = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLongOrNull() }.getOrNull()
        return when {
            // Leave 30 minutes of margin so a song started just before expiry can finish.
            stated != null -> stated * 1000 - 30 * 60_000
            track.source == Source.SAAVN -> now + 12 * 60 * 60_000L
            // SoundCloud's HLS links are short-lived.
            track.source == Source.SOUNDCLOUD -> now + 10 * 60_000L
            else -> now + 3 * 60 * 60_000L
        }
    }

    /** Streams are cached per song *and* quality, so a download never reuses a lower-quality stream link. */
    private fun key(track: Track, quality: Quality) = "${track.id}|$quality"

    /**
     * Blocking; called from the player's loader thread. Concurrent calls for the same song share one
     * lookup, so a prefetch that is already running is simply awaited. Falls back to another
     * catalogue when a source fails.
     */
    fun resolve(track: Track, quality: Quality = store.settings.quality): ResolvedStream {
        store.downloadPath(track.id)?.let { return ResolvedStream(Uri.fromFile(File(it)).toString()) }
        if (track.source == Source.LOCAL) return ResolvedStream(track.ref)
        val key = key(track, quality)
        resolved[key]?.takeIf { it.fresh(System.currentTimeMillis()) }?.let { return it.stream }

        val mine = CompletableFuture<ResolvedStream>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return runCatching { existing.get() }.getOrElse { throw (it.cause ?: it) }
        try {
            val started = System.currentTimeMillis()
            val stream = runCatching { resolveFrom(track, quality) }.getOrElse { error ->
                // Videos have no copy elsewhere; songs can be found on another catalogue.
                (if (track.video) null else alternateFor(track))?.let { resolveFrom(it, quality) } ?: throw error
            }
            val now = System.currentTimeMillis()
            if (resolved.size > 400) resolved.entries.removeIf { !it.value.fresh(now) || now - it.value.at > 60 * 60 * 1000 }
            resolved[key] = Cached(stream.url, stream.mimeType, now, expiryOf(track, stream.url, now))
            android.util.Log.i("LumenPerf", "resolved ${track.id} in ${System.currentTimeMillis() - started} ms")
            mine.complete(stream)
            return stream
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    private val prefetchSlots = Semaphore(2)

    /**
     * Starts looking up a stream in the background so playback can begin the moment it's needed.
     * [urgent] is for the song just tapped: it starts immediately instead of queueing behind guesses.
     */
    fun prefetch(track: Track, urgent: Boolean = false) {
        if (track.source == Source.LOCAL || store.downloadPath(track.id) != null) return
        val key = key(track, store.settings.quality)
        val now = System.currentTimeMillis()
        if (resolved[key]?.fresh(now) == true || inFlight.containsKey(key)) return
        io.launch {
            if (urgent) runCatching { resolve(track) }
            // At most two background lookups at a time, so they never crowd out the song you just tapped.
            else prefetchSlots.withPermit {
                if (resolved[key]?.fresh(System.currentTimeMillis()) != true) runCatching { resolve(track) }
            }
        }
    }

    fun invalidate(trackId: String) {
        resolved.keys.removeIf { it.startsWith("$trackId|") }
    }

    private fun resolveFrom(track: Track, quality: Quality): ResolvedStream = when (track.source) {
        Source.YOUTUBE -> youtube.resolve(track, quality)
        Source.SOUNDCLOUD -> soundcloud.resolve(track, quality)
        Source.SAAVN -> saavn.resolve(track, quality)
        Source.LOCAL -> ResolvedStream(track.ref)
    }

    private fun alternateFor(track: Track): Track? {
        val query = "${LyricsSource.cleanTitle(track.title)} ${track.artist.substringBefore(",")}"
        val candidates = when (track.source) {
            Source.SAAVN -> if (SourceHealth.available(Source.YOUTUBE)) youtube.searchTracks(query) else emptyList()
            else -> saavn.searchTracks(query, 5) + (if (track.source != Source.YOUTUBE && SourceHealth.available(Source.YOUTUBE)) youtube.searchTracks(query) else emptyList())
        }
        return candidates.firstOrNull { it.id != track.id && it.sameSongAs(track) }
            ?: candidates.firstOrNull { it.id != track.id && LyricsSource.cleanTitle(it.title).equals(LyricsSource.cleanTitle(track.title), true) }
    }

    private fun Track.matches(q: String) = title.contains(q, true) || artist.contains(q, true) || album?.contains(q, true) == true
}
