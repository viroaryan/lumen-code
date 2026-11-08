// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data.source

import android.util.Base64
import androidx.core.text.HtmlCompat
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JioSaavn's public web API (no account needed). */
class SaavnSource {

    private fun call(name: String, params: Map<String, String>, ctx: String = "web6dot0"): JsonElement {
        val query = (params + mapOf(
            "__call" to name, "_format" to "json", "_marker" to "0", "api_version" to "4", "ctx" to ctx,
        )).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return SourceHealth.track(Source.SAAVN) { Http.json.parseToJsonElement(Http.get("https://www.jiosaavn.com/api.php?$query")) }
    }

    private fun JsonElement.results(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> (this["results"] ?: this["list"])?.let { el -> (el as? JsonArray)?.mapNotNull { it as? JsonObject } }.orEmpty()
        else -> emptyList()
    }

    fun searchTracks(query: String, limit: Int = 30, page: Int = 1): List<Track> = runCatching {
        call("search.getResults", mapOf("q" to query, "n" to "$limit", "p" to "$page")).results().mapNotNull { it.toTrack() }
    }.getOrDefault(emptyList())

    /** A song from a jiosaavn.com/song/... link. */
    fun songFromUrl(url: String): Track? = runCatching {
        val token = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val root = call("webapi.get", mapOf("token" to token, "type" to "song")).jsonObject
        (root["songs"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.toTrack() }
    }.getOrNull()

    private fun searchCollections(call: String, query: String, kind: String, n: Int) = runCatching {
        call(call, mapOf("q" to query, "n" to "$n", "p" to "1")).results().map { it.toCollection(kind) }
    }.getOrDefault(emptyList())

    /** All four searches at once; the slowest one sets the pace instead of their sum. */
    suspend fun search(query: String): SearchResults = coroutineScope {
        val tracks = async(Dispatchers.IO) { searchTracks(query) }
        val albums = async(Dispatchers.IO) { searchCollections("search.getAlbumResults", query, Kind.ALBUM, 12) }
        val artists = async(Dispatchers.IO) { searchCollections("search.getArtistResults", query, Kind.ARTIST, 8) }
        val playlists = async(Dispatchers.IO) { searchCollections("search.getPlaylistResults", query, Kind.PLAYLIST, 12) }
        SearchResults(tracks.await(), albums.await(), artists.await(), playlists.await())
    }

    fun collection(collection: Collection): CollectionDetail {
        val token = collection.ref.trimEnd('/').substringAfterLast('/')
        val type = if (collection.kind == Kind.ARTIST) "artist" else if (collection.kind == Kind.ALBUM) "album" else "playlist"
        val root = call("webapi.get", mapOf("token" to token, "type" to type, "n" to "100", "p" to "1", "n_song" to "50", "n_album" to "20")).jsonObject
        val songs = when (type) {
            "artist" -> (root["topSongs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            else -> root.results()
        }
        val albums = if (type == "artist") (root["topAlbums"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toCollection(Kind.ALBUM) }.orEmpty() else emptyList()
        val artwork = root.str("image")?.hiRes() ?: collection.artwork
        val title = (root.str("title") ?: root.str("name"))?.unescape() ?: collection.title
        return CollectionDetail(collection.copy(artwork = artwork, title = title), songs.mapNotNull { it.toTrack() }, albums)
    }

    /** JioSaavn's own "song radio": songs picked to follow the given one. */
    fun radio(track: Track, count: Int = 20): List<Track> = runCatching {
        val id = track.id.removePrefix("jsv:")
        val station = call(
            "webradio.createEntityStation", mapOf("entity_id" to "[\"$id\"]", "entity_type" to "queue"), ctx = "android",
        ).jsonObject.str("stationid") ?: return@runCatching emptyList()
        val songs = call("webradio.getSong", mapOf("stationid" to station, "k" to "$count", "next" to "1"), ctx = "android").jsonObject
        songs.values.mapNotNull { ((it as? JsonObject)?.get("song") as? JsonObject)?.toTrack() }.filter { it.id != track.id }
    }.getOrDefault(emptyList())

    /** The most followed artists right now, with their photos. */
    fun topArtists(): List<Collection> = runCatching {
        val list = (call("social.getTopArtists", emptyMap()).jsonObject["top_artists"] as? JsonArray).orEmpty()
        list.mapNotNull { it as? JsonObject }.map { a ->
            Collection(
                id = "jsv:artist:${a.str("artistid")}", kind = Kind.ARTIST, title = a.str("name").unescape(), subtitle = "Artist",
                artwork = a.str("image")?.hiRes(), source = Source.SAAVN, ref = a.str("perma_url").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** Looks an artist up by name, for their photo and page. */
    fun findArtist(name: String): Collection? =
        searchCollections("search.getArtistResults", name, Kind.ARTIST, 3).firstOrNull { it.title.equals(name, ignoreCase = true) }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** The JioSaavn home page: trending, new releases, charts and editorial playlists. */
    fun launch(): List<HomeSection> = runCatching {
        val root = call("webapi.getLaunchData", emptyMap()).jsonObject
        val modules = (root["modules"] as? JsonObject).orEmpty()
        val wanted = modules.entries
            .mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            .filter { (key, m) ->
                val title = m.str("title").orEmpty()
                key !in setOf("radio", "artist_recos", "city_mod") && !title.contains("Podcast", true) && !title.contains("Radio", true)
            }
            .sortedBy { (_, m) -> m.str("position")?.toIntOrNull() ?: 99 }
        wanted.mapNotNull { (key, m) ->
            val items = (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val tracks = items.filter { it.str("type") == "song" }.mapNotNull { it.toTrack() }
            val collections = items.filter { it.str("type") == "album" || it.str("type") == "playlist" }
                .map { it.toCollection(if (it.str("type") == "album") Kind.ALBUM else Kind.PLAYLIST) }
            HomeSection(m.str("title").unescape(), tracks, collections).takeIf { tracks.isNotEmpty() || collections.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    fun resolve(track: Track, quality: Quality): ResolvedStream {
        val url = decrypt(track.ref)
        val bitrate = when (quality) { Quality.HIGH -> "_320"; Quality.NORMAL -> "_160"; Quality.LOW -> "_96" }
        // Android blocks plain-http media; the CDN serves the same file over https.
        val stream = url.replace(Regex("_(96|160|320)\\."), "$bitrate.").replaceFirst("http://", "https://")
        // Song refs can arrive from shared playlist codes and backups, which anyone could edit: only ever
        // stream from JioSaavn's own CDN.
        val host = runCatching { java.net.URI(stream).host.orEmpty().lowercase() }.getOrDefault("")
        if (!stream.startsWith("https://") || !(host == "saavncdn.com" || host.endsWith(".saavncdn.com"))) {
            throw java.io.IOException("Unexpected JioSaavn stream host")
        }
        return ResolvedStream(stream)
    }

    /**
     * JioSaavn hands out its stream links encrypted with this fixed public key; decrypting them is how
     * every client reads them. It protects nothing of ours, so the weak cipher is not a security issue.
     */
    @android.annotation.SuppressLint("GetInstance")
    private fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))).trim()
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.info(): JsonObject = (this["more_info"] as? JsonObject) ?: JsonObject(emptyMap())

    private fun JsonObject.toTrack(): Track? {
        val info = info()
        val encrypted = info.str("encrypted_media_url") ?: return null
        val artists = (info["artistMap"] as? JsonObject)?.get("primary_artists")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.str("name") }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return Track(
            id = "jsv:${str("id")}",
            title = str("title").unescape(),
            artist = (artists ?: info.str("music") ?: str("subtitle")).unescape(),
            album = info.str("album")?.unescape(),
            artwork = str("image")?.hiRes(),
            durationMs = (info.str("duration")?.toLongOrNull() ?: 0L) * 1000,
            source = Source.SAAVN,
            ref = encrypted,
            explicit = str("explicit_content") == "1",
        )
    }

    private fun JsonObject.toCollection(kind: String): Collection {
        val subtitle = str("subtitle") ?: str("description") ?: info().str("music")
        return Collection(
            id = "jsv:$kind:${str("id")}",
            kind = kind,
            title = (str("title") ?: str("name")).unescape(),
            subtitle = subtitle.unescape(),
            artwork = str("image")?.hiRes(),
            source = Source.SAAVN,
            ref = str("perma_url").orEmpty(),
        )
    }

    private fun String.hiRes() = replace("150x150", "500x500").replace("50x50", "500x500").replace("http://", "https://")

    private fun String?.unescape(): String =
        HtmlCompat.fromHtml(orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
}
