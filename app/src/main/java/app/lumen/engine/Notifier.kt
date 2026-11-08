// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.MainActivity
import app.lumen.R
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.data.source.Http
import app.lumen.data.source.LyricsSource
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import java.util.Calendar
import kotlin.random.Random

/**
 * Picks one thing worth telling you about, from your own taste, and says it with a bit of fun.
 * It runs entirely on the phone (the background job wakes it a few times a day) and stays polite:
 * at most one notification in ~20 hours, never at night, never while you're listening or using the app.
 */
object Notifier {
    const val CHANNEL = "lumen_picks"
    private const val NOTIFICATION_ID = 4242

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Picks for you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New songs from your artists, mood picks and talks chosen from what you listen to"
            },
        )
    }

    fun permitted(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private class Pick(val key: String, val title: String, val body: String, val track: Track?)

    /** Two moments a day: one in the day, one in the evening. Never at night. */
    private enum class Slot(val hours: IntRange) { DAY(10..14), EVENING(18..22) }

    private fun slotOf(hour: Int) = Slot.entries.firstOrNull { hour in it.hours }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Recent notifications as (kind, time). */
    private fun recentKinds(): List<Pair<String, Long>> = Graph.taste.state.value.notifiedKinds.mapNotNull {
        val kind = it.substringBefore('@')
        it.substringAfter('@').toLongOrNull()?.let { at -> kind to at }
    }

    /**
     * Called by the background job (every ~90 minutes). Posts at most one notification per slot, so
     * two a day: one in the day window and one in the evening. Returns true if something was posted.
     */
    suspend fun maybeNotify(context: Context): Boolean {
        if (!Graph.store.settings.notifications || !permitted(context)) return false
        val taste = Graph.taste
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val slot = slotOf(hour) ?: return false
        // Already sent one in this window today, or the last one was too recent.
        val slotStart = startOfToday() + slot.hours.first * 60 * 60 * 1000L
        if (taste.state.value.lastNotifiedAt >= slotStart) return false
        if (now - taste.state.value.lastNotifiedAt < 4 * 60 * 60 * 1000L) return false
        // Not while you're using the app or listening: the next run in this window will try again.
        if (Guardian.appInForeground() || Graph.player.state.value.isPlaying) return false
        // Only for people who actually use the app: no history, nothing to say.
        if (Graph.store.state.value.history.size < 3) return false

        val pick = runCatching { choose(hour) }.onFailure { Guardian.record("notifier choose", it) }.getOrNull() ?: return false
        post(context, pick)
        taste.markNotified(pick.key)
        return true
    }

    /**
     * Picks a different kind of message from the last few (new release, what's trending, a fresh find
     * for your taste, a deep cut from your top artist, a mood pick, a talk, a weekly fun fact), and
     * only songs you haven't played and haven't been told about, so it's always something new.
     */
    private suspend fun choose(hour: Int): Pick? {
        val taste = Graph.taste
        val seen = taste.state.value.notified.toSet()
        val repo = Graph.repo
        val library = Graph.store.state.value
        val played = library.history.mapTo(HashSet()) { it.id } + library.liked.map { it.id }
        val now = System.currentTimeMillis()
        val recent = recentKinds()
        val lastKinds = recent.takeLast(3).map { it.first }.toSet()
        val loved = taste.topArtists(8)
        fun fresh(key: String, t: Track) = key !in seen && t.id !in played && !taste.skipped(t.id)

        val candidates = mutableListOf<Pair<String, suspend () -> Pick?>>()

        // A new release from an artist you love, from JioSaavn's new and trending lists.
        candidates += "new" to {
            if (loved.isEmpty() || !SourceHealth.available(Source.SAAVN)) null
            else repo.saavn.launch().filter { s -> listOf("new", "trend", "fresh", "latest").any { s.title.contains(it, true) } }
                .flatMap { it.tracks }
                .firstOrNull { t -> fresh("new:${t.id}", t) && loved.any { a -> t.artist.lowercase().contains(a) } }
                ?.let { t ->
                    val artist = TasteEngine.primaryArtist(t.artist)
                    Pick("new:${t.id}", pickLine(newTitles).fill(artist, t), pickLine(newBodies).fill(artist, t), t)
                }
        }
        // What the country is playing right now, that you haven't heard yet.
        candidates += "trend" to {
            val chart = if (SourceHealth.available(Source.SAAVN)) repo.saavn.launch().firstOrNull()?.tracks.orEmpty() else emptyList()
            val list = chart.ifEmpty { repo.youtube.chart("Top songs in India", "PL4fGSI1pDJn4pTWyM3t61lOyZ6_4jcNOw")?.tracks.orEmpty() }
            list.take(25).withIndex().firstOrNull { (_, t) -> fresh("trend:${t.id}", t) }?.let { (i, t) ->
                Pick("trend:${t.id}", pickLine(trendTitles).fill(TasteEngine.primaryArtist(t.artist), t).replace("{rank}", "${i + 1}"), pickLine(trendBodies).fill(t.artist, t), t)
            }
        }
        // Something new that sounds like what you love: radio from your current favourite.
        candidates += "discover" to {
            val seed = library.history.filter { !it.video && it.source != Source.LOCAL }.take(20).maxByOrNull { library.plays[it.id] ?: 0 }
            seed?.let { s ->
                repo.radio(s).firstOrNull { t -> fresh("disc:${t.id}", t) }?.let { t ->
                    Pick("disc:${t.id}", pickLine(discoverTitles).fill(TasteEngine.primaryArtist(t.artist), t),
                        pickLine(discoverBodies).fill(t.artist, t).replace("{seed}", LyricsSource.cleanTitle(s.title)), t)
                }
            }
        }
        // A song by your top artist you somehow haven't played.
        candidates += "artist" to {
            val artist = loved.shuffled().firstOrNull()
            if (artist == null || !SourceHealth.available(Source.YOUTUBE)) null
            else repo.youtube.searchTracks(artist).filter { it.artist.contains(artist, true) }
                .firstOrNull { t -> fresh("art:${t.id}", t) }
                ?.let { t ->
                    val name = TasteEngine.primaryArtist(t.artist)
                    Pick("art:${t.id}", pickLine(artistTitles).fill(name, t), pickLine(artistBodies).fill(name, t), t)
                }
        }
        // A mood pick for the time of day, from your favourite kind of music.
        candidates += "mood" to {
            val genre = taste.favouriteGenre() ?: TasteEngine.genres.first { it.title == "Lo-fi & chill" }
            val songs = if (genre.indian && SourceHealth.available(Source.SAAVN)) repo.saavn.searchTracks(genre.query, 15)
            else if (SourceHealth.available(Source.YOUTUBE)) repo.youtube.searchTracks(genre.query) else emptyList()
            songs.shuffled().firstOrNull { fresh("mood:${it.id}", it) }?.let { t ->
                Pick("mood:${t.id}", moodTitle(hour, genre.title), "Aaj ka pick: \"${LyricsSource.cleanTitle(t.title)}\" · ${t.artist}", t)
            }
        }
        // Something to listen to and learn from, if you listen to videos.
        candidates += "vid" to {
            val topic = taste.rankedTopics(hour, java.util.Random()).firstOrNull()
            if (topic == null || taste.state.value.videoShare < 0.08f || !SourceHealth.available(Source.YOUTUBE)) null
            else repo.youtube.searchWebVideos(topic.query, minSeconds = 240)
                .firstOrNull { fresh("vid:${it.id}", it) }
                ?.let { v -> Pick("vid:${v.id}", pickLine(videoTitles(topic.name)), "\"${v.title.take(70)}\" · ${v.artist}", v) }
        }
        // Sunday evening: a fun fact about your week, at most once a week.
        candidates += "stats" to {
            val week = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
            val sunday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val top = loved.firstOrNull()
            if (!sunday || hour < 17 || top == null || "stats:$week" in seen) null
            else {
                val song = library.history.filter { TasteEngine.primaryArtist(it.artist).equals(top, true) }.maxByOrNull { library.plays[it.id] ?: 0 }
                val name = top.replaceFirstChar { it.uppercase() }
                Pick("stats:$week", pickLine(statsTitles).replace("{artist}", name), pickLine(statsBodies).replace("{artist}", name), song)
            }
        }
        // An old favourite: rarely, and never twice in a week.
        val comebackThisWeek = recent.any { it.first == "back" && now - it.second < 7 * 24 * 60 * 60 * 1000L }
        if (!comebackThisWeek) candidates += "back" to {
            val recentIds = library.history.take(15).map { it.id }.toSet()
            library.history.filter { !it.video && (library.plays[it.id] ?: 0) >= 3 && it.id !in recentIds && "back:${it.id}" !in seen }
                .maxByOrNull { library.plays[it.id] ?: 0 }
                ?.let { t -> Pick("back:${t.id}", pickLine(comebackTitles).fill("", t), pickLine(comebackBodies).fill("", t), t) }
        }

        // Kinds not used in the last three notifications go first, in a random order; repeats only as
        // a last resort. New releases jump the line when one exists, since they're time-sensitive.
        val (freshKinds, usedKinds) = candidates.partition { it.first !in lastKinds }
        val ordered = freshKinds.sortedByDescending { if (it.first == "new") 1 else 0 }.let { l ->
            l.filter { it.first == "new" } + l.filter { it.first != "new" }.shuffled()
        } + usedKinds.shuffled()
        for ((_, c) in ordered) runCatching { c() }.getOrNull()?.let { return it }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun post(context: Context, pick: Pick) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { pick.track?.let { putExtra(MainActivity.EXTRA_PLAY_TRACK, Http.json.encodeToString(Track.serializer(), it)) } }
        // Each notification gets its own tap target, so the day's and the evening's each open their own song.
        val pending = PendingIntent.getActivity(context, pick.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val art = pick.track?.artwork?.let { loadBitmap(context, it) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lumen)
            .setColor(0xFF111111.toInt())
            .setContentTitle(pick.title)
            .setContentText(pick.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pick.body))
            .setLargeIcon(art)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Day and evening use separate ids: the evening one doesn't wipe out a day one you haven't read.
        val id = NOTIFICATION_ID + if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 16) 0 else 1
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build()
        (SingletonImageLoader.get(context).execute(request).image as? BitmapImage)?.bitmap
    }.getOrNull()

    // ---- The fun part ---------------------------------------------------------------------------

    private fun pickLine(lines: List<String>) = lines[Random.nextInt(lines.size)]

    private fun String.fill(artist: String, t: Track) =
        replace("{artist}", artist).replace("{title}", LyricsSource.cleanTitle(t.title))

    private val newTitles = listOf(
        "🚨 {artist} ne naya gaana gira diya!",
        "{artist} is back, aur kya back hai 🔥",
        "Breaking news: {artist} ka naya drop 📰",
        "Earphones dhoondo, charger baad mein ⚡",
    )
    private val newBodies = listOf(
        "\"{title}\" aa gaya hai. Sunna compulsory hai, attendance lagegi 😤",
        "\"{title}\" — pehle sunne walon mein naam likhwa lo 🏃",
        "\"{title}\" tumhara wait kar raha hai. Itna bhi attitude theek nahi 😏",
        "\"{title}\" bilkul fresh hai. Doston se pehle sun lo, phir gyaan dena 😎",
    )
    private val trendTitles = listOf(
        "🔥 India mein #{rank} pe yahi baj raha hai",
        "Sab yahi sun rahe hain, tum kab? 👀",
        "Trending alert 📈 \"{title}\"",
        "Reels se pehle yahan sun lo 😎",
    )
    private val trendBodies = listOf(
        "\"{title}\" · {artist}. Doston ke group mein sabse pehle tum share karna 😏",
        "\"{title}\" abhi sabki playlist mein hai. FOMO se bacho, ek baar sun lo 🎧",
        "\"{title}\" · {artist}. Charts pe chadh raha hai, tum bhi chadh jao 🚀",
    )
    private val discoverTitles = listOf(
        "Tumhare taste ka naya gaana mila hai 🎯",
        "Algorithm ne aaj kamaal kar diya ✨",
        "Ye tumne abhi tak suna nahi? 😲",
        "Naya favourite loading… 🔄",
    )
    private val discoverBodies = listOf(
        "\"{seed}\" pasand hai na? Toh \"{title}\" · {artist} bhi pakka pasand aayega 💯",
        "\"{title}\" · {artist}. Tumhare vibe se ekdum match karta hai 🫶",
        "\"{title}\" sun ke batana, kaisa laga. Hum wait kar rahe hain 👂",
    )
    private val artistTitles = listOf(
        "{artist} ka ye gaana miss ho gaya tha 🙈",
        "Sacche {artist} fan ho? Ye sun ke dikhao 😤",
        "{artist} ki taraf se ek aur gift 🎁",
    )
    private val artistBodies = listOf(
        "\"{title}\" tumhari list mein hi nahi hai. Ye toh galat baat hai 😅",
        "\"{title}\" · fan card tabhi valid hoga jab ye bhi sunoge 🪪",
        "\"{title}\". {artist} ke deep cuts ka maza hi alag hai 🎶",
    )
    private val statsTitles = listOf(
        "Is hafte ke tumhare #1: {artist} 🏆",
        "Weekly report card aa gaya 📊 Topper: {artist}",
        "Hafte ka sabse bada crush: {artist} 💘",
    )
    private val statsBodies = listOf(
        "Itna sunoge toh {artist} tumhe concert ka free pass de dega. Shayad 😂",
        "Ek aur round ho jaye? Tap karo aur weekend set karo 🎉",
        "Consistency ho toh aisi. Ab naya artist bhi try karo, hum suggest karenge 😉",
    )
    private val comebackTitles = listOf(
        "\"{title}\" tumhe miss kar raha hai 🥺",
        "Kuch bhool rahe ho kya? 👀",
        "Ek purana pyaar wapas aaya hai 💌",
    )
    private val comebackBodies = listOf(
        "Itna bhi ghost mat karo yaar. Ek baar aur baja do, emotional support ke liye 🎧",
        "Tumhara on-repeat gaana akela baitha hai. Kuch toh sharam karo 😭",
        "\"{title}\" bol raha hai: 'yaad hai na hum kitna saath the?' 🥲",
    )

    private fun videoTitles(topic: String): List<String> = when (topic) {
        "Stand-up comedy" -> listOf("Hasna mana nahi hai 😂", "Aaj thoda has lo, therapy sasti padegi 🤣")
        "Study & lectures" -> listOf("📚 Gyaan ki dukaan khuli hai", "Padhai ka time, bahane ka nahi 😤", "Topper wali feeling chahiye? 🧠")
        "Motivation" -> listOf("Uth jao champion 💪", "Aaj ka dose of josh ⚡")
        "Stories & audiobooks" -> listOf("Kahani sunoge? 🌙", "Bedtime story time, bachpan wapas 📖")
        else -> listOf("Scroll karna band, sunna shuru 🎙️", "Free mein smart bano 🧠", "Earphones lagao, dimaag chalao 🎧")
    }

    private fun moodTitle(hour: Int, genre: String): String = when (hour) {
        in 9..11 -> pickLine(listOf("Subah ho gayi mamu ☀️ $genre ke saath start karo", "Chai + $genre = perfect morning ☕"))
        in 12..16 -> pickLine(listOf("Afternoon slump? $genre se jagao khud ko 😴➡️😎", "Lunch ke baad wala $genre break 🍱"))
        in 17..20 -> pickLine(listOf("Shaam ka scene set hai 🌆 $genre lagao", "Main character wali shaam: $genre on 🎬"))
        else -> pickLine(listOf("Neend nahi aa rahi? 🌙 $genre sun lo, bhed ginne se better hai 🐑", "Raat lambi hai, $genre ke saath kaato ✨"))
    }
}
