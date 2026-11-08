// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.engine

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.lumen.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the app standing up on its own:
 *  - background coroutine failures are logged instead of killing the process,
 *  - crashes are recorded, and two crashes right after start put the next launch in "safe mode",
 *    which throws away the state most likely to be causing them (saved queue, cached feed, temp files),
 *  - the music library file is never overwritten after a failed read (see LibraryStore).
 */
object Guardian {
    private const val TAG = "LumenGuardian"
    private var dir: File? = null

    /** True when this launch followed a crash loop; the app healed itself before starting. */
    @Volatile var healedThisLaunch = false
        private set

    /** Catches failures in fire-and-forget coroutines so they can't take the app down. */
    val handler = CoroutineExceptionHandler { _, e -> if (e !is CancellationException) record("coroutine", e) }

    fun install(context: Context) {
        val d = File(context.filesDir, "guardian").apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                record("crash on ${thread.name}", error)
                File(d, "crashes.txt").appendText("${System.currentTimeMillis()}\n")
            }
            previous?.uncaughtException(thread, error)
        }
        checkCrashLoop(context, d)
    }

    private fun checkCrashLoop(context: Context, d: File) {
        val file = File(d, "crashes.txt")
        val now = System.currentTimeMillis()
        val recent = runCatching { file.readLines() }.getOrDefault(emptyList())
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { now - it < 10 * 60 * 1000 }
        if (recent.size >= 2) {
            Log.w(TAG, "Crash loop detected (${recent.size} crashes in 10 min): healing")
            heal(context)
            healedThisLaunch = true
            file.delete()
        } else if (recent.isEmpty()) {
            file.delete()
        }
    }

    /** Drops everything that can be rebuilt and might be poisoning startup. Never touches the library. */
    fun heal(context: Context) {
        listOf(
            File(context.cacheDir, "home.json"),
            File(context.filesDir, "queue.json"),
            File(context.cacheDir, "export.m4a"),
        ).forEach { runCatching { it.delete() } }
    }

    fun record(where: String, e: Throwable) {
        Log.e(TAG, "Recovered from failure in $where", e)
        val d = dir ?: return
        runCatching {
            val log = File(d, "errors.log")
            // Keep the log small: it only needs the latest problems.
            if (log.length() > 64 * 1024) log.writeText(log.readText().takeLast(16 * 1024))
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().take(2000)
            log.appendText("--- ${java.util.Date()} [$where]\n$trace\n")
        }
    }

    fun appInForeground(): Boolean =
        runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
}

/**
 * A circuit breaker per music source. YouTube and JioSaavn change their sites without warning; when a
 * source starts failing, the app stops waiting on it for a while (so search and the home feed stay fast),
 * falls back to the other catalogues, and quietly tries again later. For YouTube it also clears the
 * extractor's cached player code, which is the usual cause of "everything suddenly fails".
 */
object SourceHealth {
    private class State(var failures: Int = 0, var openUntil: Long = 0)

    private val states = ConcurrentHashMap<Source, State>()
    private val _down = MutableStateFlow<Set<Source>>(emptySet())
    /** Sources currently being skipped. */
    val down: StateFlow<Set<Source>> = _down
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    fun available(source: Source): Boolean = (states[source]?.openUntil ?: 0L) <= System.currentTimeMillis()

    /** Runs [block] against [source], recording the outcome. Fails fast while the source is down. */
    fun <T> track(source: Source, block: () -> T): T {
        if (!available(source)) throw SourceDownException(source)
        return try {
            block().also { success(source) }
        } catch (e: Throwable) {
            failure(source, e)
            throw e
        }
    }

    private fun success(source: Source) {
        val s = states[source] ?: return
        var wasDown = false
        synchronized(s) {
            wasDown = s.failures >= THRESHOLD
            s.failures = 0
            s.openUntil = 0
        }
        if (wasDown) _down.update { it - source }
    }

    private fun failure(source: Source, e: Throwable) {
        // One video being private or blocked says nothing about the service as a whole.
        if (e is ContentNotAvailableException || e is SourceDownException || e is CancellationException) return
        val s = states.getOrPut(source) { State() }
        var opened = false
        synchronized(s) {
            s.failures++
            if (s.failures >= THRESHOLD) {
                // 30 s, 1 min, 2 min … capped at 10 min.
                val backoff = (30_000L shl (s.failures - THRESHOLD).coerceAtMost(5)).coerceAtMost(10 * 60_000L)
                s.openUntil = System.currentTimeMillis() + backoff
                opened = s.failures == THRESHOLD
            }
        }
        if (opened) {
            Guardian.record("source ${source.name} is failing", e)
            _down.update { it + source }
            if (source == Source.YOUTUBE) resetYoutubeExtractor()
            _notices.tryEmit("${source.label} is having a moment. Using the other sources meanwhile.")
        }
    }

    /** Clears NewPipe's cached YouTube player code and client version so they're fetched fresh. */
    fun resetYoutubeExtractor() {
        listOf(
            "org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager" to "clearAllCaches",
            "org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper" to "resetClientVersion",
            "org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper" to "resetClientVersionAndKey",
        ).forEach { (cls, method) ->
            // Reflection, so a renamed helper in a future extractor version can never break the build or crash.
            runCatching { Class.forName(cls).getMethod(method).invoke(null) }
        }
    }

    private const val THRESHOLD = 3
}

class SourceDownException(source: Source) : java.io.IOException("${source.label} is temporarily unavailable")
