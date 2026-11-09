// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.identify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.data.IdentifiedSong
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.data.source.Http
import app.lumen.engine.Guardian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * "What's this song?": listens through the microphone for up to 15 seconds and asks the song
 * recognition service while it's still listening, so most songs are named within 4 to 8 seconds.
 *
 * Privacy: the recording stays in memory on the phone and is thrown away afterwards. Only an audio
 * fingerprint (peaks of the spectrum, not sound) is sent, with no account, location or device ID.
 */
object SongFinder {

    sealed interface Outcome {
        data class Found(val song: IdentifiedSong) : Outcome
        /** Heard music but no match. */
        data object NotFound : Outcome
        /** Almost silence: nothing loud enough to recognise. */
        data object TooQuiet : Outcome
        data object NoPermission : Outcome
        /** Another app is using the microphone (a call, a voice note). */
        data object MicBusy : Outcome
        data object Offline : Outcome
    }

    private const val RATE = 16_000
    private const val MAX_SECONDS = 15
    /** The fingerprint covers at most this much of the latest audio. */
    private const val WINDOW_SECONDS = 12
    /** When to ask, in seconds of audio heard so far. */
    private val CHECKPOINTS = intArrayOf(4, 8, 12, 15)

    private val busy = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()

    fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Listens and identifies. [onProgress] gets 0..1 as listening goes on. Cancelling the calling
     * coroutine stops the microphone at once.
     */
    suspend fun identify(context: Context, onProgress: (Float) -> Unit = {}): Outcome {
        if (!hasMic(context)) return Outcome.NoPermission
        if (!busy.compareAndSet(false, true)) return Outcome.MicBusy
        try {
            return withContext(Dispatchers.IO) { listenAndAsk(onProgress) }
        } finally {
            busy.set(false)
        }
    }

    @SuppressLint("MissingPermission") // checked in identify()
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(RATE, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, rate))
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            rec.release()
        }
        return null
    }

    private suspend fun listenAndAsk(onProgress: (Float) -> Unit): Outcome = coroutineScope {
        val (rec, rate) = openRecorder() ?: return@coroutineScope Outcome.MicBusy
        val pcm = ShortArray(RATE * MAX_SECONDS)
        val written = AtomicInteger(0)
        val done = AtomicBoolean(false)
        val resampler = Resampler(rate)
        try {
            runCatching { rec.startRecording() }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@coroutineScope Outcome.MicBusy

            val reader = async(Dispatchers.IO) {
                val buf = ShortArray(rate / 10)
                while (isActive && !done.get() && written.get() < pcm.size) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val at = written.get()
                    val added = resampler.process(buf, n, pcm, at)
                    written.set(at + added)
                    onProgress(written.get().toFloat() / pcm.size)
                }
                done.set(true)
            }

            var heardMusic = false
            var outcome: Outcome = Outcome.NotFound
            for (cp in CHECKPOINTS) {
                // Wait for this much audio (or for the recording to end early).
                while (written.get() < cp * RATE && !done.get()) delay(60)
                ensureActive()
                val end = written.get()
                if (end < RATE * 2) break
                val start = maxOf(0, end - WINDOW_SECONDS * RATE)
                if (loudness(pcm, start, end) < 120) { outcome = Outcome.TooQuiet; if (done.get()) break else continue }
                heardMusic = true
                val (uri, ms) = withContext(Dispatchers.Default) { SignatureGenerator.of(pcm, start, end) }
                val match = try {
                    Shazam.recognize(uri, ms)
                } catch (e: IOException) {
                    outcome = Outcome.Offline
                    break
                }
                if (match != null) { outcome = Outcome.Found(match); break }
                outcome = Outcome.NotFound
                if (done.get()) break
            }
            if (outcome == Outcome.TooQuiet && heardMusic) outcome = Outcome.NotFound
            done.set(true)
            reader.cancel()
            outcome
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Guardian.record("song finder", e)
            Outcome.NotFound
        } finally {
            done.set(true)
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Average absolute level, sampled sparsely (cheap). */
    private fun loudness(pcm: ShortArray, start: Int, end: Int): Int {
        var sum = 0L; var count = 0
        var i = start
        while (i < end) { sum += abs(pcm[i].toInt()); count++; i += 16 }
        return if (count == 0) 0 else (sum / count).toInt()
    }

    /** Converts the microphone's rate to 16 kHz, smoothing first so high notes don't fold down. */
    private class Resampler(private val inRate: Int) {
        private val step = inRate.toDouble() / RATE
        private var pos = 0.0
        private var prev = 0f

        fun process(input: ShortArray, n: Int, out: ShortArray, at: Int): Int {
            if (inRate == RATE) {
                val count = minOf(n, out.size - at)
                System.arraycopy(input, 0, out, at, count)
                return count
            }
            var o = at
            // A light low-pass (moving average over about one output sample) then linear interpolation.
            val window = step.toInt().coerceAtLeast(1)
            val smooth = FloatArray(n)
            var acc = 0f
            for (i in 0 until n) {
                acc += input[i]
                if (i >= window) acc -= input[i - window]
                smooth[i] = acc / minOf(i + 1, window)
            }
            while (o < out.size) {
                val fi = kotlin.math.floor(pos)
                val i = fi.toInt()
                if (i + 1 >= n) break
                val f = (pos - fi).toFloat()
                val a = if (i < 0) prev else smooth[i]
                out[o++] = (a + (smooth[i + 1] - a) * f).toInt().coerceIn(-32768, 32767).toShort()
                pos += step
            }
            pos -= n
            prev = smooth[n - 1]
            return o - at
        }
    }

    // ---- Finding it in Lumen ---------------------------------------------------------------------

    /** The best playable copy of a recognised song, from the user's sources. Null if none found. */
    suspend fun findTrack(song: IdentifiedSong): Track? {
        val query = "${song.title} ${song.artist}".trim()
        var best: Track? = null
        val wanted = listOf(Source.YOUTUBE, Source.SAAVN).filter { it in Graph.store.settings.sources }
        withTimeoutOrNull(15_000) {
            runCatching {
                Graph.repo.search(query).first { results ->
                    val merged = Graph.repo.merge(query, results)
                    best = merged.tracks.firstOrNull { !it.video } ?: merged.tracks.firstOrNull()
                    // Once the main catalogues have answered, the best copy is known: don't wait for the slowest.
                    best != null && results.keys.containsAll(wanted)
                }
            }
        }
        return best
    }
}

/**
 * Talks to the song recognition service SongRec uses. Unofficial: it may change or stop working,
 * and then the finder simply reports "no match".
 */
internal object Shazam {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val userAgent: String by lazy {
        "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID})"
    }

    /** Null when the service had no match; throws IOException when offline. */
    fun recognize(uri: String, sampleMs: Long): IdentifiedSong? {
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            // A fixed, neutral location: Lumen never sends where you are.
            putJsonObject("geolocation") { put("altitude", 300); put("latitude", 45); put("longitude", 2) }
            putJsonObject("signature") { put("samplems", sampleMs); put("timestamp", now); put("uri", uri) }
            put("timestamp", now)
            put("timezone", TimeZone.getDefault().id)
        }.toString()
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase(Locale.ROOT)}/${UUID.randomUUID().toString().uppercase(Locale.ROOT)}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Content-Language", "en_US")
            .post(body.toRequestBody(JSON))
            .build()
        val text = Http.client.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code >= 500) throw IOException("song service busy (${response.code})")
            if (!response.isSuccessful) return null
            // A real answer is a few KB; refuse anything absurd.
            val bytes = response.body.byteStream().readNBytesSafe(2 * 1024 * 1024) ?: return null
            bytes.decodeToString()
        }
        return parse(text)
    }

    private fun java.io.InputStream.readNBytesSafe(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    fun parse(text: String): IdentifiedSong? = runCatching {
        val root = Http.json.parseToJsonElement(text).jsonObject
        if (root["matches"]?.jsonArray?.isEmpty() != false) return null
        val track = root["track"]?.jsonObject ?: return null
        val title = track["title"]?.jsonPrimitive?.contentOrNull?.let { clean(it) }.orEmpty()
        val artist = track["subtitle"]?.jsonPrimitive?.contentOrNull?.let { clean(it) }.orEmpty()
        if (title.isBlank()) return null
        val images = track["images"] as? JsonObject
        val art = (images?.get("coverarthq") ?: images?.get("coverart"))?.jsonPrimitive?.contentOrNull
        IdentifiedSong(title, artist, app.lumen.data.PlaylistShare.safeImage(art))
    }.getOrNull()

    private fun clean(s: String) = app.lumen.data.PlaylistShare.clean(s, 200)
}
