// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import kotlinx.serialization.builtins.ListSerializer
import androidx.media3.session.SessionResult
import app.lumen.Graph
import app.lumen.MainActivity
import app.lumen.R
import app.lumen.data.SavedQueue
import app.lumen.data.Track
import app.lumen.data.source.Http
import app.lumen.engine.Guardian
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Plays in the background with a media notification, lock screen and headset controls. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var radioJob: Job? = null
    private var retriedId: String? = null
    /** The service's own scope: everything it starts stops with it, so nothing touches a released player. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + Guardian.handler)
    /** The last song counted as a play, so restoring a queue or pausing/resuming doesn't count twice. */
    private var countedId: String? = null

    private val likeCommand = SessionCommand(COMMAND_LIKE, Bundle.EMPTY)
    private val enqueueCommand = SessionCommand(COMMAND_ENQUEUE, Bundle.EMPTY)
    private val radioCommand = SessionCommand(COMMAND_RADIO, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val store = Graph.store

        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT))
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.scheme != LUMEN_SCHEME) return@Factory spec
            val id = spec.uri.lastPathSegment ?: return@Factory spec
            val track = TrackRegistry[id]
                ?: Graph.store.queue.tracks.firstOrNull { it.id == id }?.also(TrackRegistry::register)
                ?: throw java.io.IOException("Unknown track $id")
            spec.withUri(Graph.repo.resolve(track).url.toUri())
        }
        val cached = CacheDataSource.Factory()
            .setCache(Graph.cache)
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Start playing after 0.5 s of audio instead of the default 2.5 s; keep a deep buffer behind it.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 120_000, 500, 1_500)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(LumenMediaSourceFactory(cached, resolving))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.skipSilenceEnabled = store.settings.skipSilence
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(callback)
            .build()

        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_lumen) })
        runCatching { restoreQueue() }.onFailure {
            // A saved queue that can't be restored must never stop the player from starting.
            Guardian.record("restore queue", it)
            store.saveQueue(SavedQueue())
        }

        scope.launch {
            store.state.distinctUntilChangedBy { it.settings.skipSilence }.collect { player.skipSilenceEnabled = it.settings.skipSilence }
        }
        scope.launch {
            store.state.distinctUntilChangedBy { it.liked }.collect { updateLikeButton() }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                if (player.isPlaying) {
                    persistQueue()
                    rememberVideoPosition()
                }
            }
        }
    }

    private fun restoreQueue() {
        val saved = Graph.store.queue
        if (saved.tracks.isEmpty() || player.mediaItemCount > 0) return
        player.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index.coerceIn(0, saved.tracks.lastIndex), saved.positionMs.coerceAtLeast(0))
        player.prepare()
    }

    /**
     * Saves up to 1,000 songs around the current one. A long playlist used to be cut at its first 500
     * songs, so reopening the app on song 700 restored the wrong song.
     */
    private fun persistQueue() {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        val from = if (count <= 1000) 0 else (current - 100).coerceIn(0, count - 1000)
        val to = minOf(count, from + 1000)
        val window = (from until to).map { player.getMediaItemAt(it) }
        val tracks = window.mapNotNull { it.track }
        // Index within what's saved; items without a readable track are skipped, so recount before current.
        val index = window.take((current - from).coerceAtLeast(0)).count { it.track != null }
        Graph.store.saveQueue(SavedQueue(tracks, index, player.currentPosition))
    }

    /** Long videos remember where you stopped, like a podcast app. */
    private fun rememberVideoPosition(track: Track? = player.currentMediaItem?.track, positionMs: Long = player.currentPosition) {
        if (track == null || !track.isLongForm()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && track.id == player.currentMediaItem?.mediaId } ?: track.durationMs
        if (positionMs < 30_000 || (duration > 0 && positionMs > duration - 30_000)) Graph.store.clearResume(track.id)
        else Graph.store.saveResume(track.id, positionMs)
    }

    private fun Track.isLongForm() = video && durationMs >= 10 * 60_000

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val track = mediaItem?.track
            retriedId = null
            trimPlayed()
            updateLikeButton()
            persistQueue()
            maybeExtendWithRadio()
            prefetchUpcoming()
            // Pick a long video up where you left it.
            if (track != null && track.isLongForm() && player.currentPosition < 5_000) {
                Graph.store.resumePosition(track.id)?.let { player.seekTo(it) }
            }
            if (player.isPlaying) countPlay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) countPlay()
            else {
                persistQueue()
                rememberVideoPosition()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
            val old = oldPosition.mediaItem?.track ?: return
            when (reason) {
                // Played to the end on its own.
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    Graph.taste.onComplete(old)
                    Graph.store.clearResume(old.id)
                }
                // You moved on: a skip in the first 30 seconds says "not this one".
                Player.DISCONTINUITY_REASON_SEEK -> {
                    if (oldPosition.positionMs in 1 until 30_000 && newPosition.mediaItemIndex > oldPosition.mediaItemIndex) Graph.taste.onSkip(old)
                    rememberVideoPosition(old, oldPosition.positionMs)
                }
                else -> rememberVideoPosition(old, oldPosition.positionMs)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) maybeExtendWithRadio()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Stream links expire; resolve once more before moving on.
            val item = player.currentMediaItem ?: return
            if (retriedId != item.mediaId) {
                retriedId = item.mediaId
                Graph.repo.invalidate(item.mediaId)
                val position = player.currentPosition
                player.prepare()
                player.seekTo(position)
                player.play()
            } else if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * Autoplay keeps adding songs, so a day-long session could grow the queue into the thousands, and
     * every queue update (here and in the UI) walks the whole list. Songs played long ago are dropped,
     * keeping the last [KEEP_PLAYED] for "previous", so heavy use stays as fast as the first song.
     */
    private fun trimPlayed() {
        val index = player.currentMediaItemIndex
        if (player.shuffleModeEnabled || player.repeatMode == Player.REPEAT_MODE_ALL) return
        if (index <= KEEP_PLAYED + 50) return
        runCatching { player.removeMediaItems(0, index - KEEP_PLAYED) }
    }

    /** History and taste learn from songs actually heard, not from a queue that was merely restored. */
    private fun countPlay() {
        val track = player.currentMediaItem?.track ?: return
        if (countedId == track.id) return
        countedId = track.id
        Graph.store.addHistory(track)
        Graph.taste.onPlay(track)
    }

    /** Looks up the next two songs' streams now, so skipping or moving on starts without a wait. */
    private fun prefetchUpcoming() {
        var index = player.currentMediaItemIndex
        repeat(2) {
            index = player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return
            player.getMediaItemAt(index).track?.let(Graph.repo::prefetch)
        }
    }

    /** Keeps music going after the queue ends, like a radio seeded by the last song (or video). */
    private fun maybeExtendWithRadio() {
        if (!Graph.store.settings.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount == 0 || player.currentMediaItemIndex < player.mediaItemCount - 2) return
        if (radioJob?.isActive == true) return
        val seed = player.getMediaItemAt(player.mediaItemCount - 1).track ?: return
        extendWithRadio(seed, count = if (seed.video) 8 else 15)
    }

    private fun extendWithRadio(seed: Track, count: Int) {
        radioJob?.cancel()
        radioJob = scope.launch {
            val more = runCatching { Graph.repo.radio(seed) }.getOrDefault(emptyList())
            // The queue may have been replaced while we waited (another song or playlist was played):
            // songs related to a seed that's no longer queued don't belong in it.
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            if (seed.id !in ids) return@launch
            val existing = ids.toHashSet()
            val recent = Graph.store.state.value.history.take(30).map { it.id }.toSet()
            val fresh = more.filter { it.id !in existing && (it.id !in recent || more.size < 10) }.take(count)
            if (fresh.isEmpty()) return@launch
            val items = fresh.map { it.toMediaItem() }
            if (player.shuffleModeEnabled) appendKeepingShuffle(items) else player.addMediaItems(items)
        }
    }

    /** "Start radio": plays [seed] now, then fills the queue with similar music (even with autoplay off). */
    private fun startRadio(seed: Track) {
        player.shuffleModeEnabled = false
        player.setMediaItems(listOf(seed.toMediaItem()), 0, 0)
        player.prepare()
        player.play()
        extendWithRadio(seed, count = 25)
    }

    /**
     * The order songs will actually play in. With shuffle on that's the shuffle order, not the list order.
     */
    private fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        if (!player.shuffleModeEnabled) return (0 until timeline.windowCount).toList()
        val order = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    /** Adds songs at the end of the list and, with shuffle on, also at the end of the play order. */
    private fun appendKeepingShuffle(items: List<MediaItem>) {
        val order = playOrder()
        val start = player.mediaItemCount
        player.addMediaItems(items)
        if (player.shuffleModeEnabled) {
            val seq = order + (start until start + items.size)
            runCatching { player.setShuffleOrder(DefaultShuffleOrder(seq.toIntArray(), System.nanoTime())) }
        }
    }

    /**
     * "Play next" puts songs right after the current one. "Add to queue" puts them after the current
     * song and any songs queued before them, ahead of the rest of the playlist and of autoplay, the way
     * people expect a queue to work. With shuffle on, the shuffle order is adjusted to match, so a queued
     * song never lands at some random later point.
     */
    private fun enqueue(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toMediaItem(queued = !next) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            player.play()
            return
        }
        val ended = player.playbackState == Player.STATE_ENDED
        val current = player.currentMediaItemIndex
        val firstNew: Int
        if (!player.shuffleModeEnabled) {
            var pos = (current + 1).coerceAtMost(player.mediaItemCount)
            if (!next) while (pos < player.mediaItemCount && player.getMediaItemAt(pos).isQueued) pos++
            player.addMediaItems(pos, items)
            firstNew = pos
        } else {
            val order = playOrder()
            var at = order.indexOf(current) + 1
            if (!next) while (at < order.size && player.getMediaItemAt(order[at]).isQueued) at++
            val start = player.mediaItemCount
            player.addMediaItems(items)
            val seq = order.toMutableList().apply { addAll(at.coerceIn(0, size), (start until start + items.size).toList()) }
            runCatching { player.setShuffleOrder(DefaultShuffleOrder(seq.toIntArray(), System.nanoTime())) }
            firstNew = start
        }
        // The queue had run out: start the newly added songs instead of sitting silent.
        if (ended) {
            player.seekToDefaultPosition(firstNew)
            player.prepare()
            player.play()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    private fun updateLikeButton() {
        val current = player.currentMediaItem?.track
        val liked = current != null && Graph.store.isLiked(current.id)
        val button = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Remove from Liked" else "Like")
            .setSessionCommand(likeCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session?.setMediaButtonPreferences(listOf(button))
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(likeCommand).apply {
                // Queue and radio commands only from Lumen itself, not from other apps' controllers.
                if (controller.packageName == packageName) { add(enqueueCommand); add(radioCommand) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_LIKE -> player.currentMediaItem?.track?.let { Graph.store.toggleLike(it) }
                COMMAND_ENQUEUE, COMMAND_RADIO -> {
                    if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    val tracks = runCatching {
                        Http.json.decodeFromString(ListSerializer(Track.serializer()), args.getString(ARG_TRACKS).orEmpty())
                    }.getOrDefault(emptyList())
                    if (customCommand.customAction == COMMAND_RADIO) tracks.firstOrNull()?.let(::startRadio)
                    else enqueue(tracks, next = args.getBoolean(ARG_NEXT, false))
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.track?.toMediaItem(it.isQueued) ?: it }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = Graph.store.queue
            val index = if (saved.tracks.isEmpty()) 0 else saved.index.coerceIn(0, saved.tracks.lastIndex)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.tracks.map { it.toMediaItem() }, index, saved.positionMs.coerceAtLeast(0)),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistQueue()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        persistQueue()
        rememberVideoPosition()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val COMMAND_LIKE = "lumen.like"
        const val COMMAND_ENQUEUE = "lumen.enqueue"
        const val COMMAND_RADIO = "lumen.radio"
        const val ARG_TRACKS = "tracks"
        const val ARG_NEXT = "next"
        private const val KEEP_PLAYED = 100
    }
}

/** Progressive audio goes through the disk cache; HLS (SoundCloud) and local files skip it. */
private class LumenMediaSourceFactory(cached: DataSource.Factory, direct: DataSource.Factory) : MediaSource.Factory {
    private val progressive = DefaultMediaSourceFactory(cached)
    private val local = DefaultMediaSourceFactory(direct)
    private val hls = HlsMediaSource.Factory(direct)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        progressive.setDrmSessionManagerProvider(provider); local.setDrmSessionManagerProvider(provider); hls.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        progressive.setLoadErrorHandlingPolicy(policy); local.setLoadErrorHandlingPolicy(policy); hls.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        return when {
            config?.mimeType == MimeTypes.APPLICATION_M3U8 -> hls.createMediaSource(mediaItem)
            config?.uri?.scheme == "content" || config?.uri?.scheme == "file" -> local.createMediaSource(mediaItem)
            else -> progressive.createMediaSource(mediaItem)
        }
    }
}
