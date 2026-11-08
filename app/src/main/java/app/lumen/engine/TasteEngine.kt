// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.engine

import android.content.Context
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.data.source.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.pow

/** A home-feed shelf that rotates in and out with your taste and the hour. */
class Genre(val title: String, val query: String, val indian: Boolean = false, val hints: List<String> = emptyList(), val hours: IntRange? = null)

/** A kind of spoken or long-form video worth listening to. */
class VideoTopic(val name: String, val query: String, val hints: List<String>, val hours: IntRange? = null)

/** Everything the app has learned about what you like. Scores fade over a few weeks, so taste can change. */
@Serializable
data class TasteState(
    val artists: Map<String, Float> = emptyMap(),
    val keywords: Map<String, Float> = emptyMap(),
    val topics: Map<String, Float> = emptyMap(),
    val channels: Map<String, Float> = emptyMap(),
    val skips: Map<String, Int> = emptyMap(),
    /** Share of Indian music in what you play, 0..1. */
    val indian: Float = 0.5f,
    /** Share of videos (podcasts, lectures…) in what you play, 0..1. */
    val videoShare: Float = 0f,
    val updatedAt: Long = 0,
    /** Things already announced in a notification, so nothing is announced twice. */
    val notified: List<String> = emptyList(),
    val lastNotifiedAt: Long = 0,
    /** Recent notifications as "kind@time", so each one is a different kind from the last few. */
    val notifiedKinds: List<String> = emptyList(),
)

/**
 * The on-device recommendation engine. It learns from plays, full listens, skips, likes and searches,
 * and everything stays on the phone: there is no server. The home feed, video picks and notifications
 * all read from it.
 */
@OptIn(FlowPreview::class)
class TasteEngine(context: Context, scope: CoroutineScope) {

    private val file = File(context.filesDir, "taste.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TasteState> = _state

    init {
        _state.drop(1).debounce(1500).onEach { runCatching { save(it) } }
            .launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    private fun load(): TasteState = runCatching { Http.json.decodeFromString(TasteState.serializer(), file.readText()) }.getOrDefault(TasteState())

    private fun save(s: TasteState) = app.lumen.data.SafeFile.write(file, Http.json.encodeToString(TasteState.serializer(), s))

    // ---- Learning -----------------------------------------------------------------------------

    private fun edit(block: (TasteState) -> TasteState) = _state.update { block(decayed(it, System.currentTimeMillis())) }

    /** Scores halve every three weeks. */
    private fun decayed(s: TasteState, now: Long): TasteState {
        if (s.updatedAt == 0L) return s.copy(updatedAt = now)
        val days = (now - s.updatedAt) / 86_400_000.0
        if (days < 0.25) return s
        val f = 0.5.pow(days / 21.0).toFloat()
        return s.copy(
            artists = s.artists.scaled(f), keywords = s.keywords.scaled(f), topics = s.topics.scaled(f),
            channels = s.channels.scaled(f), updatedAt = now,
        )
    }

    private fun Map<String, Float>.scaled(f: Float) = mapValues { it.value * f }.filterValues { kotlin.math.abs(it) > 0.05f }

    private fun Map<String, Float>.bump(key: String, by: Float): Map<String, Float> {
        val k = key.lowercase().trim()
        if (k.isEmpty()) return this
        val next = this + (k to ((this[k] ?: 0f) + by).coerceIn(-20f, 300f))
        return if (next.size > 90) next.entries.sortedByDescending { it.value }.take(70).associate { it.key to it.value } else next
    }

    private fun words(text: String): Set<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotEmpty() }.toSet()

    private fun hit(text: String, words: Set<String>, hint: String) = if (' ' in hint) text.contains(hint) else hint in words

    private fun keywordsIn(text: String): List<String> {
        val lower = text.lowercase()
        val w = words(lower)
        return vocabulary.filter { hit(lower, w, it) }
    }

    private fun topicsIn(text: String): List<VideoTopic> {
        val lower = text.lowercase()
        val w = words(lower)
        return videoTopics.filter { t -> t.hints.any { hit(lower, w, it) } }
    }

    /** A song or video started playing. */
    fun onPlay(t: Track) = edit { s ->
        val text = "${t.title} ${t.artist}"
        if (t.video) {
            var topics = s.topics
            topicsIn(text).forEach { topics = topics.bump(it.name, 1f) }
            val channels = if (t.artist.isNotBlank()) s.channels.bump(t.artist, 1f) else s.channels
            s.copy(topics = topics, channels = channels, videoShare = s.videoShare * 0.9f + 0.1f)
        } else {
            var keywords = s.keywords
            keywordsIn(text).forEach { keywords = keywords.bump(it, 0.6f) }
            val artist = primaryArtist(t.artist)
            val indianSignal = if (t.source == Source.SAAVN || keywordsIn(text).any { it in indianWords }) 1f else 0f
            s.copy(
                artists = if (artist.isNotBlank() && !artist.contains("unknown", true)) s.artists.bump(artist, 1f) else s.artists,
                keywords = keywords,
                indian = s.indian * 0.95f + indianSignal * 0.05f,
                videoShare = s.videoShare * 0.97f,
            )
        }
    }

    /** Listened to the end: a stronger signal than a start. */
    fun onComplete(t: Track) = edit { s ->
        if (t.video) s.copy(channels = if (t.artist.isNotBlank()) s.channels.bump(t.artist, 0.6f) else s.channels)
        else s.copy(artists = s.artists.bump(primaryArtist(t.artist), 0.4f))
    }

    /** Skipped in the first seconds: probably not your thing. */
    fun onSkip(t: Track) = edit { s ->
        val skips = (s.skips + (t.id to (s.skips[t.id] ?: 0) + 1)).let { m -> if (m.size > 400) m.entries.toList().takeLast(300).associate { it.key to it.value } else m }
        if (t.video) s.copy(skips = skips, channels = s.channels.bump(t.artist, -0.5f))
        else s.copy(skips = skips, artists = s.artists.bump(primaryArtist(t.artist), -0.7f))
    }

    fun onLike(t: Track) = edit { s ->
        var keywords = s.keywords
        keywordsIn("${t.title} ${t.artist}").forEach { keywords = keywords.bump(it, 1f) }
        s.copy(artists = s.artists.bump(primaryArtist(t.artist), 3f), keywords = keywords)
    }

    fun onSearch(query: String) = edit { s ->
        var keywords = s.keywords
        var topics = s.topics
        keywordsIn(query).forEach { keywords = keywords.bump(it, 1f) }
        topicsIn(query).forEach { topics = topics.bump(it.name, 1.5f) }
        s.copy(keywords = keywords, topics = topics)
    }

    fun markNotified(key: String, kind: String = key.substringBefore(':')) = _state.update { s ->
        val now = System.currentTimeMillis()
        s.copy(
            notified = (s.notified + key).takeLast(300),
            lastNotifiedAt = now,
            notifiedKinds = (s.notifiedKinds + "$kind@$now").takeLast(20),
        )
    }

    // ---- Reading ------------------------------------------------------------------------------

    fun skipped(id: String) = (_state.value.skips[id] ?: 0) >= 2

    fun artistScore(name: String) = _state.value.artists[primaryArtist(name).lowercase()] ?: 0f

    /** Your artists, strongest first (lowercase names). */
    fun topArtists(n: Int = 10): List<String> = _state.value.artists.entries.filter { it.value > 0.8f }
        .sortedByDescending { it.value }.take(n).map { it.key }

    fun topChannels(n: Int = 5): List<String> = _state.value.channels.entries.filter { it.value > 1.5f }
        .sortedByDescending { it.value }.take(n).map { it.key }

    fun genreScore(g: Genre): Double {
        val s = _state.value
        val kw = g.hints.sumOf { (s.keywords[it] ?: 0f).toDouble() }
        val language = if (g.indian) (s.indian - 0.5) * 2 else 0.0
        return kw + language
    }

    /** Video topics ranked for you, with a nudge for the hour and some variety. */
    fun rankedTopics(hour: Int, random: java.util.Random): List<VideoTopic> {
        val s = _state.value
        return videoTopics.sortedByDescending { t ->
            (s.topics[t.name.lowercase()] ?: 0f) * 2.0 + (if (t.hours != null && hour in t.hours) 1.5 else 0.0) + random.nextDouble() * 2.0
        }
    }

    fun topicScore(t: VideoTopic) = _state.value.topics[t.name.lowercase()] ?: 0f

    /** The genre that best describes you right now, if the app knows yet. */
    fun favouriteGenre(): Genre? = genres.maxByOrNull { genreScore(it) }?.takeIf { genreScore(it) > 1.5 }

    companion object {
        fun primaryArtist(artist: String) =
            artist.substringBefore(",").substringBefore(" & ").substringBefore(" x ").substringBefore(" feat").trim()

        val genres = listOf(
            Genre("Punjabi bangers", "latest punjabi songs", indian = true, hints = listOf("punjabi", "diljit", "sidhu", "aujla", "dhillon", "shubh", "gurnam")),
            Genre("Lo-fi & chill", "hindi lofi chill songs", hints = listOf("lofi", "lo-fi", "slowed", "reverb", "chill"), hours = 21..23),
            Genre("Romantic", "romantic hindi songs", indian = true, hints = listOf("love", "ishq", "pyaar", "dil", "romantic")),
            Genre("Party starters", "party songs hits", hints = listOf("party", "dance", "dj")),
            Genre("Hip-hop & rap", "hip hop rap hits", hints = listOf("rap", "divine", "badshah", "eminem", "drake", "krsna", "seedhe maut")),
            Genre("Heartbreak", "sad songs hindi", indian = true, hints = listOf("sad", "dard", "bewafa", "tanha", "alone"), hours = 22..23),
            Genre("90s Bollywood", "90s bollywood hits", indian = true, hints = listOf("kumar sanu", "udit", "alka", "lata", "90s")),
            Genre("Devotional", "bhakti songs", indian = true, hints = listOf("bhajan", "krishna", "shiv", "hanuman", "aarti"), hours = 5..9),
            Genre("Workout", "gym workout motivation songs", hints = listOf("gym", "workout"), hours = 6..10),
            Genre("K-pop", "kpop hits", hints = listOf("bts", "blackpink", "jungkook", "twice", "kpop")),
            Genre("EDM", "edm hits", hints = listOf("edm", "remix", "martin garrix", "alan walker")),
            Genre("Indie India", "indian indie songs", hints = listOf("prateek kuhad", "anuv jain", "ritviz", "indie")),
            Genre("Tamil hits", "latest tamil songs", indian = true, hints = listOf("tamil", "anirudh", "sid sriram")),
            Genre("Telugu hits", "latest telugu songs", indian = true, hints = listOf("telugu", "dsp", "thaman")),
            Genre("Sufi & ghazal", "sufi songs", indian = true, hints = listOf("sufi", "ghazal", "nusrat", "qawwali")),
            Genre("Acoustic", "acoustic songs", hints = listOf("acoustic", "unplugged")),
            Genre("Phonk", "phonk", hints = listOf("phonk", "drift")),
        )

        val videoTopics = listOf(
            VideoTopic("Podcasts", "hindi podcast full episode", listOf("podcast", "episode", "raj shamani", "beerbiceps", "ranveer", "figuring out")),
            VideoTopic("Study & lectures", "full lecture explained one shot", listOf("lecture", "class", "physics", "chemistry", "maths", "math", "biology", "upsc", "jee", "neet", "one shot", "explained", "tutorial", "course"), hours = 8..18),
            VideoTopic("Motivation", "motivational speech", listOf("motivation", "motivational", "speech", "sandeep maheshwari", "discipline", "success"), hours = 5..9),
            VideoTopic("Stand-up comedy", "stand up comedy", listOf("comedy", "stand up", "standup", "zakir khan", "samay raina", "funny"), hours = 19..23),
            VideoTopic("Talks & ideas", "ted talk", listOf("ted", "tedx", "talk", "ideas")),
            VideoTopic("Stories & audiobooks", "audiobook hindi story", listOf("audiobook", "story", "kahani", "book summary"), hours = 21..23),
            VideoTopic("Tech & science", "science explained documentary", listOf("tech", "science", "space", "ai", "documentary", "dhruv rathee")),
            VideoTopic("Spiritual", "spiritual discourse", listOf("gita", "spiritual", "meditation", "premanand", "satsang"), hours = 4..8),
            VideoTopic("Focus music", "deep focus music for studying", listOf("focus", "study music", "concentration", "study with me")),
            VideoTopic("News & analysis", "news analysis podcast", listOf("news", "analysis", "current affairs")),
        )

        private val indianWords = setOf(
            "punjabi", "ishq", "pyaar", "dil", "dard", "bewafa", "bhajan", "krishna", "shiv", "hanuman", "aarti",
            "tamil", "telugu", "sufi", "ghazal", "qawwali", "bollywood", "arijit", "kumar sanu", "udit",
        )

        private val vocabulary: List<String> = (genres.flatMap { it.hints } + indianWords + listOf("bollywood", "english", "pop", "rock", "jazz", "classical", "arijit")).distinct()
    }
}
