// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.engine

import android.content.Context
import android.util.Log
import app.lumen.Graph
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps Lumen's footprint small and never lets it fill the phone.
 *
 *  - The song cache and image cache are sized from the free space on the phone (a few percent of it,
 *    with a hard ceiling), instead of a fixed 1 GB + 300 MB.
 *  - Studio's decoded backing tracks (~10 MB a minute) are dropped when no recording uses them, and
 *    kept under a total budget otherwise; they're re-downloaded on demand.
 *  - Leftovers from interrupted downloads, exports and crashes are removed.
 *  - When the phone runs low, caches shrink on their own; downloads are refused before the disk fills.
 */
object StorageGuard {
    private const val TAG = "LumenStorage"
    private const val MB = 1024L * 1024

    /** Below this, new downloads are refused and caches are trimmed hard. */
    const val CRITICAL_FREE = 300 * MB
    /** Below this, caches are trimmed. */
    const val LOW_FREE = 1024 * MB

    fun freeBytes(context: Context): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /** Song cache size: the user's choice, or 4% of free space between 64 MB and 400 MB. */
    fun audioCacheLimit(context: Context, settingMb: Int): Long =
        if (settingMb > 0) settingMb * MB else (freeBytes(context) / 25).coerceIn(64 * MB, 400 * MB)

    /** Artwork cache: 1% of free space between 30 MB and 120 MB. */
    fun imageCacheLimit(context: Context): Long = (freeBytes(context) / 100).coerceIn(30 * MB, 120 * MB)

    fun hasRoomForDownload(context: Context) = freeBytes(context) > CRITICAL_FREE

    data class Usage(
        val downloads: Long,
        val songCache: Long,
        val images: Long,
        val studio: Long,
        val free: Long,
    )

    suspend fun usage(context: Context): Usage = withContext(Dispatchers.IO) {
        Usage(
            downloads = sizeOf(File(context.filesDir, "downloads")),
            songCache = runCatching { Graph.cache.cacheSpace }.getOrDefault(0L),
            images = sizeOf(File(context.cacheDir, "images")),
            studio = sizeOf(File(context.filesDir, "studio")),
            free = freeBytes(context),
        )
    }

    fun sizeOf(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    /**
     * Routine upkeep: run at startup and from the background job. Returns the bytes freed.
     * With [aggressive] (low space, or the user pressed "Free up space") caches are emptied too.
     */
    suspend fun maintain(context: Context, aggressive: Boolean = false): Long = withContext(Dispatchers.IO) {
        val before = freeBytes(context)
        val low = before < LOW_FREE
        runCatching { cleanTemp(context) }.onFailure { Guardian.record("cleanTemp", it) }
        runCatching { cleanStudio(context, tight = low || aggressive) }.onFailure { Guardian.record("cleanStudio", it) }
        runCatching { cleanDownloads(context) }.onFailure { Guardian.record("cleanDownloads", it) }
        val limit = audioCacheLimit(context, Graph.store.settings.cacheLimitMb)
        when {
            aggressive || before < CRITICAL_FREE -> {
                trimAudioCache(0)
                runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
            }
            // Low on space: keep the song cache to a quarter of its normal size.
            low -> trimAudioCache(limit / 4)
            else -> trimAudioCache(limit)
        }
        val freed = (freeBytes(context) - before).coerceAtLeast(0)
        if (freed > 0) Log.i(TAG, "Freed ${freed / MB} MB")
        freed
    }

    /** Evicts the least recently played songs until the cache fits in [targetBytes]. */
    fun trimAudioCache(targetBytes: Long) {
        val cache = runCatching { Graph.cache }.getOrNull() ?: return
        runCatching {
            if (cache.cacheSpace <= targetBytes) return
            if (targetBytes <= 0) {
                cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                return
            }
            val byAge = cache.keys.toList().map { key ->
                val spans = cache.getCachedSpans(key)
                key to (spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L)
            }.sortedBy { it.second }
            for ((key, _) in byAge) {
                if (cache.cacheSpace <= targetBytes) break
                runCatching { cache.removeResource(key) }
            }
        }.onFailure { Guardian.record("trimAudioCache", it) }
    }

    private fun cleanTemp(context: Context) {
        val hour = 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        File(context.cacheDir, "export.m4a").takeIf { it.exists() && now - it.lastModified() > hour }?.delete()
        File(context.filesDir, "studio/backing").listFiles()?.forEach { f ->
            // A ".part" or raw ".audio" file only exists mid-download/decode; an old one was interrupted.
            if ((f.name.endsWith(".part") || f.name.endsWith(".audio")) && now - f.lastModified() > hour) f.delete()
        }
        File(context.filesDir, "library.json.tmp").takeIf { it.exists() && now - it.lastModified() > hour }?.delete()
    }

    /** Backing tracks: unused ones go after 2 days; the rest stay within a budget, oldest out first. */
    private fun cleanStudio(context: Context, tight: Boolean) {
        val studio = Graph.studio
        val takes = studio.takes.value
        val usedBacking = takes.map { studio.backingPcm(it.track).name }.toSet()
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "studio/backing")
        val pcms = dir.listFiles { f -> f.name.endsWith(".pcm") }.orEmpty().toMutableList()

        val maxUnusedAge = if (tight) 0L else 2 * 24 * 60 * 60 * 1000L
        pcms.filter { it.name !in usedBacking && now - it.lastModified() > maxUnusedAge }.forEach { pcm ->
            deleteBacking(pcm)
            pcms.remove(pcm)
        }

        val budget = if (tight) 150 * MB else 600 * MB
        var total = pcms.sumOf { it.length() }
        for (pcm in pcms.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            total -= pcm.length()
            deleteBacking(pcm)
        }

        // Voice files whose recording was deleted, or never saved (a crash mid-take).
        val takeIds = takes.map { it.id }.toSet()
        File(context.filesDir, "studio/takes").listFiles()?.forEach { f ->
            if (f.name.endsWith(".pcm") && f.nameWithoutExtension !in takeIds && now - f.lastModified() > 60 * 60 * 1000L) f.delete()
        }
    }

    private fun deleteBacking(pcm: File) {
        pcm.delete()
        File(pcm.parentFile, pcm.nameWithoutExtension + ".rate").delete()
    }

    /** Files with no library entry (interrupted downloads), and entries whose file is gone. */
    private fun cleanDownloads(context: Context) {
        Graph.store.pruneDownloads()
        val active = Graph.downloads.activeIds().map { it.replace(Regex("[^A-Za-z0-9_-]"), "_") }.toSet()
        val known = Graph.store.state.value.downloads.values.flatMap { t ->
            listOfNotNull(File(t.ref).name, t.artwork?.takeIf { it.startsWith("file:") }?.substringAfterLast('/'))
        }.toSet()
        File(context.filesDir, "downloads").listFiles()?.forEach { f ->
            if (f.name !in known && f.nameWithoutExtension !in active) f.delete()
        }
    }

    suspend fun clearImages(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val loader = SingletonImageLoader.get(context)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        }
    }
}
