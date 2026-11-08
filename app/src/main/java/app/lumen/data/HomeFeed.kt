// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import app.lumen.engine.Guardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The home feed, owned by the app rather than the screen: it starts loading when the app opens,
 * survives navigation, and shows the last saved feed instantly while a fresh one loads behind it.
 */
class HomeFeed(private val repo: MusicRepository, private val scope: CoroutineScope) {

    private val _sections = MutableStateFlow(repo.cachedHome())
    val sections: StateFlow<List<HomeSection>> = _sections

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    private var job: Job? = null
    private var playsAtRefresh = -1
    /** Set when the feed should change but the app is in the background; applied on next open. */
    private var stale = false

    /**
     * Keeps the feed alive: a fresh feed every 20 minutes, and sooner once you've played enough new
     * songs for the personal shelves to change. Only while the app is on screen: in the background
     * (music playing with the screen off) it costs no data or battery.
     */
    fun startAutoRefresh(store: LibraryStore) {
        scope.launch {
            while (true) {
                delay(20 * 60 * 1000L)
                if (Guardian.appInForeground()) refresh() else stale = true
            }
        }
        scope.launch {
            store.state.map { it.plays.values.sum() }.distinctUntilChanged().collect { total ->
                if (playsAtRefresh < 0) playsAtRefresh = total
                else if (total - playsAtRefresh >= 8) {
                    playsAtRefresh = total
                    if (Guardian.appInForeground()) refresh() else stale = true
                }
            }
        }
    }

    /** Called whenever the app comes to the foreground. */
    fun refreshIfStale() {
        if (stale || _sections.value.isEmpty() || !repo.homeIsFresh) refresh()
    }

    fun refresh() {
        if (job?.isActive == true) return
        stale = false
        job = scope.launch {
            _loading.value = true
            _failed.value = false
            val hadFeed = _sections.value.isNotEmpty()
            var latest = emptyList<HomeSection>()
            runCatching {
                repo.home().collect { partial ->
                    latest = partial
                    // With nothing on screen, show shelves as they arrive; otherwise swap once, when complete.
                    if (!hadFeed) _sections.value = partial
                }
            }.onFailure { Guardian.record("home feed", it) }
            if (latest.isNotEmpty()) {
                _sections.value = latest
                repo.saveHome(latest)
            } else if (!hadFeed) {
                _failed.value = true
            }
            _loading.value = false
        }
    }

    /** Builds a feed without touching the screen, for the background job to keep the cache warm. */
    suspend fun prefetchQuietly() {
        var latest = emptyList<HomeSection>()
        runCatching { repo.home().collect { latest = it } }
        if (latest.isNotEmpty()) {
            _sections.value = latest
            repo.saveHome(latest)
        }
    }
}
