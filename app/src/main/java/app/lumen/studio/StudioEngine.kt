// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.studio

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.lumen.Graph
import app.lumen.data.Track
import app.lumen.data.source.Quality
import app.lumen.playback.fetchRanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

sealed interface StudioPhase {
    data class Preparing(val label: String, val progress: Float) : StudioPhase
    data object Ready : StudioPhase
    data object Recording : StudioPhase
    data object Playing : StudioPhase
    data class Exporting(val progress: Float) : StudioPhase
    data class Failed(val message: String) : StudioPhase
}

/**
 * The studio's audio engine. It plays the backing track and captures the microphone with its own
 * AudioTrack/AudioRecord pair (so timing is exact), then mixes voice and music live through the
 * vocal chain for preview, and renders the same mix to an AAC file for export.
 */
class StudioEngine(private val context: Context, private val store: StudioStore) {

    private val _phase = MutableStateFlow<StudioPhase>(StudioPhase.Preparing("Getting ready", 0f))
    val phase: StateFlow<StudioPhase> = _phase
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel
    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform

    /** Live mix settings; the preview picks up changes on the next 20 ms block. */
    @Volatile var params = MixParams()
    /** How loud the music is in your headphones while recording. */
    @Volatile var monitorVolume = 0.85f

    var sampleRate = 44100
        private set
    private var backing: ShortBuffer? = null
    private var backingFrames = 0L
    private var voice: ShortBuffer? = null
    private var voiceFrames = 0L
    private var alignFrames = 0
    var take: Take? = null
        private set
    private lateinit var track: Track

    val backingDurationMs get() = backingFrames * 1000 / sampleRate
    val mixDurationMs get() = mixFrames() * 1000 / sampleRate

    // ---- Preparation --------------------------------------------------------------------------

    /** Downloads and decodes the backing track once; later sessions reuse the decoded copy. */
    suspend fun prepare(track: Track) = withContext(Dispatchers.IO) {
        this@StudioEngine.track = track
        runCatching {
            val pcm = store.backingPcm(track)
            val rateFile = store.backingRate(track)
            if (!pcm.exists() || !rateFile.exists()) {
                val audio = store.backingAudio(track)
                if (!audio.exists() || audio.length() == 0L) {
                    _phase.value = StudioPhase.Preparing("Downloading music", 0f)
                    val stream = Graph.repo.resolve(track, Quality.HIGH)
                    val tmp = File(audio.path + ".part")
                    fetchRanged(stream.url, tmp) { _phase.value = StudioPhase.Preparing("Downloading music", it) }
                    tmp.renameTo(audio)
                }
                _phase.value = StudioPhase.Preparing("Preparing audio", 0f)
                val rate = decodeToPcm(audio, pcm) { _phase.value = StudioPhase.Preparing("Preparing audio", it) }
                rateFile.writeText(rate.toString())
                audio.delete()
            }
            sampleRate = rateFile.readText().trim().toInt()
            // Marks the decoded track as recently used, so storage cleanup removes older ones first.
            pcm.setLastModified(System.currentTimeMillis())
            backing = map(pcm)
            backingFrames = pcm.length() / 4
            _phase.value = StudioPhase.Ready
        }.onFailure {
            // Never leave half-written audio behind: it would be wasted space and a corrupt cache next time.
            runCatching {
                File(store.backingAudio(track).path + ".part").delete()
                if (!store.backingRate(track).exists()) store.backingPcm(track).delete()
            }
            _phase.value = if (!app.lumen.engine.StorageGuard.hasRoomForDownload(context))
                StudioPhase.Failed("Your phone is almost full. Free up some space, then try again.")
            else StudioPhase.Failed("Couldn't load this track. Check your connection and try again.")
        }
    }

    /** Reopens a saved take for mixing. */
    suspend fun open(saved: Take) {
        prepare(saved.track)
        if (_phase.value is StudioPhase.Failed) return
        // A take whose voice file is gone (cleaned by another app, or a broken write) must not crash the studio.
        val ok = withContext(Dispatchers.IO) { runCatching { attachVoice(saved) }.isSuccess }
        if (!ok) {
            take = null; voice = null; voiceFrames = 0
            _phase.value = StudioPhase.Failed("This recording's voice file is missing. Record a new take.")
            return
        }
        params = saved.params
    }

    private fun map(file: File): ShortBuffer = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    }

    private fun attachVoice(t: Take) {
        val file = store.voicePcm(t.id)
        take = t
        voice = map(file)
        voiceFrames = file.length() / 2
        alignFrames = t.alignFrames
        _waveform.value = peaks(voice!!, voiceFrames, 180)
    }

    private fun peaks(buf: ShortBuffer, frames: Long, buckets: Int): FloatArray {
        if (frames == 0L) return FloatArray(0)
        val per = (frames / buckets).coerceAtLeast(1)
        return FloatArray(buckets) { b ->
            var peak = 0
            var i = b * per
            val end = minOf(frames, i + per)
            while (i < end) { peak = maxOf(peak, abs(buf.get(i.toInt()).toInt())); i += 4 }
            (peak / 32768f).coerceIn(0f, 1f)
        }
    }

    // ---- Recording ----------------------------------------------------------------------------

    @Volatile private var recording = false
    private var recordThreads: List<Thread> = emptyList()
    private var recordedFrames = 0L
    @Volatile private var measuredAlign = -1
    private var pendingTakeId = ""
    private var recordStartMs = 0L

    /** Starts the music and the microphone together. Needs the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun startRecording(fromMs: Long = 0) {
        if (recording || backing == null) return
        stopPreview()
        val sr = sampleRate
        val takeId = UUID.randomUUID().toString().take(12)
        pendingTakeId = takeId
        recordStartMs = fromMs

        val recMin = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_PERFORMANCE is Android's capture path built for karaoke: low latency, no call-style processing.
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.MIC)
        }
        val recorder = sources.firstNotNullOfOrNull { source ->
            runCatching { AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMin * 4) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } ?: run {
            _phase.value = StudioPhase.Failed("The microphone isn't available. Close other apps using it and try again.")
            return
        }
        val trackMin = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(trackMin * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val startFrame = (fromMs * sr / 1000).coerceIn(0, backingFrames)
        recordedFrames = 0
        measuredAlign = -1
        recording = true
        _phase.value = StudioPhase.Recording
        recorder.startRecording()
        player.play()

        val music = thread(name = "studio-music", priority = Thread.MAX_PRIORITY) {
            val block = ShortArray(512 * 2)
            var frame = startFrame
            val src = backing!!
            while (recording && frame < backingFrames) {
                val n = minOf(512L, backingFrames - frame).toInt()
                val vol = monitorVolume
                for (i in 0 until n * 2) block[i] = (src.get((frame * 2 + i).toInt()) * vol).toInt().toShort()
                player.write(block, 0, n * 2)
                frame += n
                _positionMs.value = (startFrame + player.playbackHeadPosition) * 1000L / sr
            }
            // The song ended: stop on our own.
            if (recording) recording = false
        }

        val mic = thread(name = "studio-mic", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(1024)
            val bytes = ByteArray(2048)
            val rts = AudioTimestamp()
            val tts = AudioTimestamp()
            BufferedOutputStream(FileOutputStream(store.voicePcm(takeId)), 1 shl 16).use { out ->
                while (recording) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte()
                        sum += s * s
                    }
                    out.write(bytes, 0, n * 2)
                    recordedFrames += n
                    _inputLevel.value = (sqrt(sum / n) / 8000.0).toFloat().coerceIn(0f, 1f)

                    // Once both streams are flowing, measure how far the microphone runs behind the speaker.
                    if (measuredAlign < 0 && recordedFrames > sr / 2) {
                        if (recorder.getTimestamp(rts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && player.getTimestamp(tts)) {
                            val now = System.nanoTime()
                            val captured = rts.framePosition + (now - rts.nanoTime) * sr / 1_000_000_000.0
                            val heard = tts.framePosition + (now - tts.nanoTime) * sr / 1_000_000_000.0
                            measuredAlign = (captured - heard).toInt().coerceIn(0, sr)
                        } else if (recordedFrames > sr * 2) {
                            measuredAlign = (sr * 0.12).toInt() // typical round-trip when the hardware won't say
                        }
                    }
                }
            }
            _inputLevel.value = 0f
            runCatching { recorder.stop() }; recorder.release()
            runCatching { player.stop() }; player.release()
        }
        recordThreads = listOf(music, mic)
    }

    val isRecording get() = recording

    /** Stops recording and saves the take. Returns null if nothing was captured. */
    fun stopRecording(): Take? {
        if (!recording && recordThreads.isEmpty()) return take
        recording = false
        recordThreads.forEach { it.join(1500) }
        recordThreads = emptyList()
        val sr = sampleRate
        if (recordedFrames < sr) {
            store.voicePcm(pendingTakeId).delete()
            _phase.value = StudioPhase.Ready
            return null
        }
        val align = measuredAlign.takeIf { it >= 0 } ?: (sr * 0.12).toInt()
        // A take that starts mid-song stores its offset in the alignment, so mixing stays in sync.
        val startFrames = (recordStartMs * sr / 1000).toInt()
        val newTake = Take(
            id = pendingTakeId, track = track, sampleRate = sr, alignFrames = align - startFrames,
            durationMs = recordedFrames * 1000 / sr, params = params,
        )
        store.put(newTake)
        attachVoice(newTake)
        _positionMs.value = 0
        _phase.value = StudioPhase.Ready
        return newTake
    }

    // ---- Mixing ------------------------------------------------------------------------------

    private fun mixFrames(): Long {
        if (voice == null) return backingFrames
        return (voiceFrames - alignFrames).coerceIn(0, backingFrames)
    }

    private val renderL = FloatArray(1024)
    private val renderR = FloatArray(1024)
    private val voiceIn = FloatArray(1024)
    private val frameBuf = FloatArray(2)

    /** Mixes [n] frames starting at backing frame [from] into interleaved 16-bit stereo. */
    private fun render(from: Long, n: Int, out: ShortArray, chain: VocalChain, bus: MasterBus, p: MixParams, musicOnly: Boolean = false) {
        val src = backing!!
        val v = if (musicOnly) null else voice
        val sync = (p.syncMs * sampleRate / 1000).toInt()
        for (i in 0 until n) {
            renderL[i] = 0f; renderR[i] = 0f
            val vi = from + i + alignFrames + sync
            voiceIn[i] = if (v != null && vi in 0 until voiceFrames) v.get(vi.toInt()) / 32768f else 0f
        }
        if (v != null) chain.process(voiceIn, n, p, renderL, renderR)
        val music = dbToGain(p.musicDb)
        for (i in 0 until n) {
            val b = from + i
            val duck = bus.duckGain(voiceIn[i], p)
            val bl = if (b < backingFrames) src.get((b * 2).toInt()) / 32768f else 0f
            val br = if (b < backingFrames) src.get((b * 2 + 1).toInt()) / 32768f else 0f
            frameBuf[0] = bl * music * duck + renderL[i]
            frameBuf[1] = br * music * duck + renderR[i]
            bus.limit(frameBuf)
            out[i * 2] = (frameBuf[0] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[i * 2 + 1] = (frameBuf[1] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Volatile private var previewing = false
    @Volatile private var seekTo = -1L
    private var previewThread: Thread? = null

    /** Plays the mix; with [musicOnly] it plays just the backing track, for rehearsing with the lyrics. */
    fun play(fromMs: Long = _positionMs.value, musicOnly: Boolean = false) {
        if (previewing || backing == null) return
        val sr = sampleRate
        val total = if (musicOnly) backingFrames else mixFrames()
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        previewing = true
        _phase.value = StudioPhase.Playing
        var frame = (fromMs * sr / 1000).coerceIn(0, maxOf(0, total - 1))
        if (frame >= total - sr / 10) frame = 0
        player.play()
        previewThread = thread(name = "studio-preview", priority = Thread.MAX_PRIORITY) {
            var chain = VocalChain(sr)
            var bus = MasterBus(sr)
            val out = ShortArray(1024 * 2)
            while (previewing && frame < total) {
                val jump = seekTo
                if (jump >= 0) {
                    seekTo = -1
                    frame = (jump * sr / 1000).coerceIn(0, total)
                    chain = VocalChain(sr); bus = MasterBus(sr) // drop reverb/echo tails from the old spot
                    player.pause(); player.flush(); player.play()
                }
                val n = minOf(1024L, total - frame).toInt()
                if (n <= 0) break
                val p = params
                render(frame, n, out, chain, bus, if (musicOnly) p.copy(musicDb = gainToDb(monitorVolume).coerceAtLeast(-60f)) else p, musicOnly)
                player.write(out, 0, n * 2)
                frame += n
                _positionMs.value = frame * 1000 / sr
            }
            runCatching { player.stop() }; player.release()
            if (frame >= total) _positionMs.value = 0
            previewing = false
            if (_phase.value == StudioPhase.Playing) _phase.value = StudioPhase.Ready
        }
    }

    fun pause() = stopPreview()

    fun seek(ms: Long) {
        if (previewing) seekTo = ms else _positionMs.value = ms
    }

    private fun stopPreview() {
        previewing = false
        previewThread?.join(500)
        previewThread = null
    }

    // ---- Export -------------------------------------------------------------------------------

    /** Renders the full mix to AAC and saves it to Music/Lumen Studio. Returns a shareable URI. */
    suspend fun export(): Uri? = withContext(Dispatchers.IO) {
        val t = take ?: return@withContext null
        stopPreview()
        _phase.value = StudioPhase.Exporting(0f)
        val result = runCatching {
            val sr = sampleRate
            val total = mixFrames()
            val chain = VocalChain(sr)
            val bus = MasterBus(sr)
            val p = params
            var frame = 0L
            val name = "Lumen - ${StudioCatalog.songTitle(t.track.title).take(60).replace(Regex("[\\\\/:*?\"<>|]"), "")} (${t.id.take(4)}).m4a"
            val tmp = File(context.cacheDir, "export.m4a")
            encodeAac(tmp, sr, total, next = { block ->
                val n = minOf((block.size / 2).toLong(), total - frame).toInt()
                if (n > 0) { render(frame, n, block, chain, bus, p); frame += n }
                n
            }) { _phase.value = StudioPhase.Exporting(it) }
            try {
                val uri = saveToMusic(tmp, name)
                store.update(t.id) { it.copy(params = p, exportedUri = uri.toString()) }
                take = store.get(t.id)
                uri
            } finally {
                tmp.delete()
            }
        }
        _phase.value = if (result.isSuccess) StudioPhase.Ready else StudioPhase.Failed("Couldn't save the song. Free up some space and try again.")
        result.getOrNull()
    }

    private fun saveToMusic(file: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lumen Studio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Lumen Studio").apply { mkdirs() }
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
    }

    fun saveParams() {
        take?.let { t -> store.update(t.id) { it.copy(params = params) } }
    }

    fun saveLayout(layout: LyricsLayout) {
        take?.let { t -> store.update(t.id) { it.copy(layout = layout) } }
    }

    fun release() {
        recording = false
        recordThreads.forEach { it.join(1000) }
        stopPreview()
    }
}
