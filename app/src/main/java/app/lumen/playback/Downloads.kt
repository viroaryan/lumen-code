// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.content.Context
import android.net.Uri
import app.lumen.data.LibraryStore
import app.lumen.data.MusicRepository
import app.lumen.data.Source
import app.lumen.data.Track
import app.lumen.data.source.Http
import app.lumen.engine.StorageGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Saves songs for offline listening. Progress is 0f..1f per track id; a missing id means idle. */
class Downloads(
    private val context: Context,
    private val repo: MusicRepository,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Three at a time: a 100-song playlist no longer fires 100 requests at YouTube at once. */
    private val slots = Semaphore(3)

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Messages worth showing the user (storage full, a failed download). */
    val events: SharedFlow<String> = _events

    private val _completed = MutableStateFlow(0)
    /** Songs finished since the app started, for the progress notification. */
    val completedThisRun: StateFlow<Int> = _completed

    /**
     * Songs waiting or in progress, saved on disk. If the app is closed or Android ends it in the middle
     * of a 500-song download, the rest continue the next time Lumen opens instead of silently vanishing.
     */
    private val pendingFile = File(context.filesDir, "downloads_pending.json")
    private val pendingSerializer = kotlinx.serialization.builtins.ListSerializer(Track.serializer())
    private val pending = java.util.Collections.synchronizedMap(LinkedHashMap<String, Track>())
    private val pendingLock = Any()

    init {
        runCatching { Http.json.decodeFromString(pendingSerializer, pendingFile.readText()) }.getOrNull()
            ?.forEach { pending[it.id] = it }
    }

    private fun savePending() {
        val snapshot = synchronized(pending) { pending.values.toList() }
        scope.launch(Dispatchers.IO) {
            synchronized(pendingLock) {
                runCatching { app.lumen.data.SafeFile.write(pendingFile, Http.json.encodeToString(pendingSerializer, snapshot)) }
            }
        }
    }

    fun pendingCount(): Int = pending.size

    /** Picks up downloads left unfinished last time. Called once the app is up. */
    fun resumePending() {
        val left = synchronized(pending) { pending.values.toList() }
        if (left.isEmpty()) return
        val started = left.count { start(it) }
        if (started > 0) DownloadService.start(context)
    }

    fun canDownload(track: Track) = track.source == Source.YOUTUBE || track.source == Source.SAAVN

    fun activeIds(): Set<String> = jobs.keys.toSet()

    /** Queues every downloadable song, however many; returns how many were added. */
    fun download(tracks: List<Track>): Int {
        if (!StorageGuard.hasRoomForDownload(context)) {
            _events.tryEmit("Your phone is almost full. Free up some space to download more.")
            return 0
        }
        val fresh = tracks.filter { canDownload(it) && !jobs.containsKey(it.id) && store.downloadPath(it.id) == null }.distinctBy { it.id }
        fresh.forEach { pending[it.id] = it }
        savePending()
        val n = fresh.count { start(it) }
        if (n > 0) DownloadService.start(context)
        return n
    }

    fun download(track: Track): Boolean = download(listOf(track)) > 0

    private fun start(track: Track): Boolean {
        if (jobs.containsKey(track.id)) return false
        if (store.downloadPath(track.id) != null) { finish(track.id); return false }
        _progress.update { it + (track.id to 0f) }
        // Registered before it starts, so a download that fails instantly can't leave a stale entry behind.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val name = track.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val audio = File(dir, "$name.audio")
            val part = File(dir, "$name.part")
            var keepPending = false
            try {
                slots.withPermit {
                    if (!StorageGuard.hasRoomForDownload(context)) {
                        keepPending = true
                        _events.tryEmit("Your phone is almost full. Downloads will continue when there's space.")
                        return@withPermit
                    }
                    // Networks drop and stream links expire: three tries, with a fresh link each time.
                    var lastError: Throwable? = null
                    for (attempt in 1..3) {
                        val result = runCatching {
                            val stream = repo.resolve(track, store.settings.downloadQuality)
                            fetchRanged(stream.url, part) { p -> _progress.update { it + (track.id to p) } }
                            if (part.length() == 0L) throw IOException("Empty download")
                            audio.delete()
                            if (!part.renameTo(audio)) throw IOException("Couldn't save the file")
                        }
                        if (result.isSuccess) { lastError = null; break }
                        lastError = result.exceptionOrNull()
                        if (lastError is kotlinx.coroutines.CancellationException) throw lastError
                        repo.invalidate(track.id)
                        kotlinx.coroutines.delay(2_000L * attempt)
                    }
                    if (lastError != null) {
                        part.delete()
                        _events.tryEmit("Couldn't download \"${track.title}\"")
                        return@withPermit
                    }
                    val art = track.artwork?.let { url ->
                        runCatching {
                            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                if (!r.isSuccessful) null else File(dir, "$name.jpg").also { f -> f.writeBytes(r.body.bytes()) }
                            }
                        }.getOrNull()
                    }
                    val saved = track.copy(artwork = art?.let { Uri.fromFile(it).toString() } ?: track.artwork)
                    store.addDownload(saved, audio.absolutePath)
                    _completed.update { it + 1 }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                // Cancelled by the user: forget it. Cancelled because the app is closing: keep it for next time.
                keepPending = jobs[track.id] != null
                throw e
            } finally {
                _progress.update { it - track.id }
                jobs.remove(track.id)
                if (!keepPending) finish(track.id)
            }
        }
        jobs[track.id] = job
        job.start()
        return true
    }

    private fun finish(id: String) {
        if (pending.remove(id) != null) savePending()
    }

    fun cancel(trackId: String) {
        val job = jobs.remove(trackId)
        job?.cancel()
        finish(trackId)
        _progress.update { it - trackId }
    }

    /** Stops every download in progress or waiting. */
    fun cancelAll() {
        jobs.keys.toList().forEach(::cancel)
        synchronized(pending) { pending.clear() }
        savePending()
    }

    fun remove(track: Track) {
        cancel(track.id)
        store.state.value.downloads[track.id]?.let { saved ->
            File(saved.ref).delete()
            saved.artwork?.takeIf { it.startsWith("file:") }?.let { Uri.parse(it).path?.let(::File)?.delete() }
        }
        store.removeDownload(track.id)
    }
}

/** Range requests in 2 MB chunks: CDNs like YouTube's throttle one long request but serve chunks at full speed. */
suspend fun fetchRanged(url: String, out: File, onProgress: (Float) -> Unit) {
    val chunk = 2L * 1024 * 1024
    var offset = 0L
    var total = -1L
    RandomAccessFile(out, "rw").use { file ->
        file.setLength(0)
        while (total < 0 || offset < total) {
            coroutineContext.ensureActive()
            val request = Request.Builder().url(url)
                .header("User-Agent", Http.USER_AGENT)
                .header("Range", "bytes=$offset-${offset + chunk - 1}")
                .build()
            val done = Http.retrying {
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (total < 0) {
                        total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: response.body.contentLength().takeIf { response.code == 200 } ?: -1L
                    }
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) return@use true
                    file.seek(offset)
                    file.write(bytes)
                    offset += bytes.size
                    if (response.code == 200) total = offset
                    false
                }
            }
            if (done) return
            if (total > 0) onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
        }
    }
}
