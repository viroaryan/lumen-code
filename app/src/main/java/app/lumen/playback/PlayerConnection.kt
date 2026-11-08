// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.data.LibraryStore
import app.lumen.data.MusicRepository
import app.lumen.data.Track
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val error: String? = null,
)

data class Progress(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAt: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/** The UI's handle on the playback service, exposed as state flows. */
class PlayerConnection(
    private val context: Context,
    private val store: LibraryStore,
    private val repo: MusicRepository,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val _sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleep: StateFlow<SleepTimer> = _sleep
    private var sleepJob: Job? = null

    private var connecting = false
    private var ticker: Job? = null

    fun connect() {
        // Every command issued before the controller is ready used to start another connection and
        // another progress loop; now there's exactly one of each.
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                // The service couldn't be reached; the next command will try again.
                return@addListener
            }
            controller = c
            c.addListener(listener)
            sync()
            val queued = pending.toList()
            pending.clear()
            queued.forEach { runCatching { it(c) } }
        }, ContextCompat.getMainExecutor(context))
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    _progress.value = Progress(c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it != C.TIME_UNSET } ?: (_state.value.current?.durationMs ?: 0), c.bufferedPosition)
                }
                // Fast while playing (smooth seek bar), lazy while paused.
                delay(if (_state.value.isPlaying) 250 else 1000)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run { pending += block; connect() }
    }

    private val listener = object : Player.Listener {
        // Play/pause, buffering and seeks fire constantly; only a changed queue needs the queue list rebuilt.
        override fun onEvents(player: Player, events: Player.Events) =
            sync(rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleep.value == SleepTimer.EndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _sleep.value = SleepTimer.Off
            }
        }
    }

    private var queueCache: List<Track> = emptyList()

    private fun sync(rebuildQueue: Boolean = true) {
        val c = controller ?: return
        if (rebuildQueue || queueCache.size != c.mediaItemCount) {
            queueCache = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).track }
        }
        val queue = queueCache
        _state.value = PlayerState(
            current = c.currentMediaItem?.track,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            speed = c.playbackParameters.speed,
            queue = queue,
            index = c.currentMediaItemIndex,
            error = c.playerError?.let { "Couldn't play this song" },
        )
    }

    // ---- Commands ---------------------------------------------------------------------------

    /** Bumped by every "play this now": a slower, older request never overwrites a newer one. */
    private var generation = 0

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val start = (if (shuffle && startIndex == 0) tracks.indices.random() else startIndex).coerceIn(tracks.indices)
        // Begin the stream lookup right away, in parallel with handing the queue to the service, and
        // ahead of any background guesses still waiting their turn.
        repo.prefetch(tracks[start], urgent = true)
        val mine = ++generation
        scope.launch {
            // Building media items for a 2,000-song playlist takes a moment: done off the main thread.
            val items = withContext(Dispatchers.Default) { tracks.map { it.toMediaItem() } }
            if (mine != generation) return@launch
            withController { c ->
                if (mine != generation) return@withController
                c.shuffleModeEnabled = shuffle
                c.setMediaItems(items, start, 0)
                c.prepare()
                c.play()
            }
        }
    }

    private fun sendToService(action: String, tracks: List<Track>, next: Boolean = false) {
        val args = android.os.Bundle().apply {
            putString(PlaybackService.ARG_TRACKS, app.lumen.data.source.Http.json.encodeToString(ListSerializer(Track.serializer()), tracks))
            putBoolean(PlaybackService.ARG_NEXT, next)
        }
        withController { c -> c.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args) }
    }

    /** Right after the current song. */
    fun playNext(track: Track) = playNext(listOf(track))
    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        tracks.firstOrNull()?.let(repo::prefetch)
        // Large lists go in slices so each message to the service stays small.
        tracks.chunked(300).reversed().forEach { sendToService(PlaybackService.COMMAND_ENQUEUE, it, next = true) }
    }

    /** After the current song and anything queued before, ahead of the rest of the list and autoplay. */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        tracks.chunked(300).forEach { sendToService(PlaybackService.COMMAND_ENQUEUE, it, next = false) }
    }

    /** Plays the song now and fills the queue with similar music. */
    fun startRadio(track: Track) {
        ++generation
        repo.prefetch(track, urgent = true)
        sendToService(PlaybackService.COMMAND_RADIO, listOf(track))
    }

    fun toggle() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        if (c.isPlaying) c.pause() else c.play()
    }
    fun pause() = withController { it.pause() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); _progress.value = _progress.value.copy(positionMs = ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed) }
    fun moveQueueItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeQueueItem(index: Int) = withController { it.removeMediaItem(index) }
    fun clearUpcoming() = withController { c -> if (c.currentMediaItemIndex + 1 < c.mediaItemCount) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount) }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        when (minutes) {
            null -> _sleep.value = SleepTimer.Off
            0 -> _sleep.value = SleepTimer.EndOfTrack
            else -> {
                val ends = System.currentTimeMillis() + minutes * 60_000L
                _sleep.value = SleepTimer.At(ends)
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    controller?.pause()
                    _sleep.value = SleepTimer.Off
                }
            }
        }
    }

    fun toggleLike(track: Track? = _state.value.current) = track?.let { store.toggleLike(it) }
}
