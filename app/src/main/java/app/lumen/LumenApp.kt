// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.lumen.data.Covers
import app.lumen.data.HomeFeed
import app.lumen.data.LibraryStore
import app.lumen.data.MusicRepository
import app.lumen.data.source.Http
import app.lumen.data.source.LocalSource
import app.lumen.data.source.NewPipeDownloader
import app.lumen.engine.Guardian
import app.lumen.engine.LumenJobService
import app.lumen.engine.Notifier
import app.lumen.engine.StorageGuard
import app.lumen.engine.TasteEngine
import app.lumen.playback.DownloadService
import app.lumen.playback.Downloads
import app.lumen.playback.PlayerConnection
import app.lumen.studio.StudioStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.util.Locale

/** App-wide singletons. Everything runs in one process, so the UI and the playback service share them. */
object Graph {
    lateinit var app: Application
    lateinit var scope: CoroutineScope
    lateinit var store: LibraryStore
    lateinit var taste: TasteEngine
    lateinit var repo: MusicRepository
    lateinit var home: HomeFeed
    lateinit var studio: StudioStore
    lateinit var downloads: Downloads
    lateinit var player: PlayerConnection

    /**
     * The song cache, opened on first use (not at startup) and sized from the phone's free space,
     * so it can never eat storage the phone needs.
     */
    val cache: SimpleCache by lazy {
        SimpleCache(
            File(app.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(StorageGuard.audioCacheLimit(app, store.settings.cacheLimitMb)),
            StandaloneDatabaseProvider(app),
        )
    }
}

class LumenApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // First: crash protection, and self-healing if the last launches crashed.
        Guardian.install(this)

        val country = Locale.getDefault().country.ifEmpty { "IN" }
        NewPipe.init(NewPipeDownloader, Localization("en", country), ContentCountry(country))

        Graph.app = this
        Graph.scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + Guardian.handler)
        Graph.store = LibraryStore(this, Graph.scope)
        Graph.taste = TasteEngine(this, Graph.scope)
        Graph.repo = MusicRepository(this, LocalSource(this), Graph.store, Graph.taste)
        Graph.downloads = Downloads(this, Graph.repo, Graph.store, Graph.scope)
        Graph.player = PlayerConnection(this, Graph.store, Graph.repo, Graph.scope)
        Graph.home = HomeFeed(Graph.repo, Graph.scope)
        Graph.studio = StudioStore(this)
        Graph.home.startAutoRefresh(Graph.store)

        learnFromLibrary()
        Notifier.createChannel(this)
        DownloadService.createChannel(this)
        LumenJobService.schedule(this)

        // The feed refreshes when you open the app, not when the music service or a job wakes the process.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var resumedDownloads = false

            override fun onStart(owner: LifecycleOwner) {
                Graph.home.refreshIfStale()
                // Downloads left unfinished last time carry on, once per launch, while the app is on screen.
                if (!resumedDownloads) {
                    resumedDownloads = true
                    Graph.downloads.resumePending()
                }
                // Warm up: look up the last song now, so pressing play on it (or resuming) starts instantly.
                Graph.store.queue.let { q -> q.tracks.getOrNull(q.index) }?.let(Graph.repo::prefetch)
            }

            override fun onStop(owner: LifecycleOwner) {
                // Leaving the app is often the last moment before Android may end the process: save now.
                Graph.scope.launch(Dispatchers.IO) {
                    Graph.store.flushNow()
                    Graph.repo.saveStreamCache()
                }
            }
        })

        // Tidy storage a little after startup, off the main thread, when it can't slow the first screen.
        Graph.scope.launch(Dispatchers.IO) {
            delay(8_000)
            runCatching { StorageGuard.maintain(this@LumenApp) }.onFailure { Guardian.record("startup storage", it) }
            Covers.prune(this@LumenApp, Graph.store)
        }
    }

    /** Likes and searches teach the taste engine too. */
    private fun learnFromLibrary() {
        val store = Graph.store
        Graph.scope.launch {
            // Only songs that are new to Liked count: dragging the list into a new order is not a like.
            var known: Set<String>? = null
            store.state.map { it.liked }.distinctUntilChanged().collect { liked ->
                val ids = liked.mapTo(HashSet(liked.size * 2)) { it.id }
                val before = known
                known = ids
                if (before == null) return@collect
                val added = liked.filter { it.id !in before }
                // Many at once means a restored backup, not a tap on the heart: don't download them all.
                if (added.size > 5) return@collect
                added.forEach {
                    Graph.taste.onLike(it)
                    if (store.settings.autoDownloadLiked) Graph.downloads.download(it)
                }
            }
        }
        Graph.scope.launch {
            store.state.map { it.searches.firstOrNull() }.distinctUntilChanged().drop(1).filterNotNull()
                .collect { Graph.taste.onSearch(it) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(StorageGuard.imageCacheLimit(this)).build() }
        .crossfade(true)
        .build()
}

val Context.app get() = applicationContext as LumenApp
