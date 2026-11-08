// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.lumen.data.PlaylistShare
import app.lumen.data.Track
import app.lumen.data.source.Http
import app.lumen.playback.PipActionReceiver
import app.lumen.ui.AppState
import app.lumen.ui.LumenRoot
import app.lumen.ui.findLink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.lumen.ui.player.PipContent
import app.lumen.ui.theme.LocalColors
import app.lumen.ui.theme.LumenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var appState: AppState? = null
    private var inPip by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Graph.player.connect()


        setContent {
            // Only the theme matters up here; reading the whole library would recompose the entire app
            // every time a song was added to history or the queue was saved.
            val theme by remember { Graph.store.state.map { it.settings.theme }.distinctUntilChanged() }
                .collectAsState(Graph.store.settings.theme)
            LumenTheme(theme) {
                val dark = LocalColors.current.isDark
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                // The app stays composed under the PiP view so navigation survives the round trip.
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val state = remember(nav) { AppState(nav, scope) }
                appState = state
                LaunchedEffect(state) { handleIntent(intent) }
                LumenRoot(state)
                if (inPip) PipContent()
            }
        }

        lifecycleScope.launch {
            Graph.player.state.distinctUntilChangedBy { it.isPlaying to it.current?.id }.collect { state ->
                updatePipParams()
                // Ask for notifications when they first matter: the moment music starts.
                if (state.isPlaying) askForNotifications()
            }
        }
    }

    private var askedNotifications = false

    private fun askForNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            appState?.playerOpen = true
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
        intent.getStringExtra(EXTRA_PLAY_TRACK)?.let { json ->
            intent.removeExtra(EXTRA_PLAY_TRACK)
            // Tapped a notification: play what it was about.
            runCatching { Http.json.decodeFromString(Track.serializer(), json) }.getOrNull()?.let { track ->
                if (track.video) Graph.player.play(listOf(track)) else Graph.player.startRadio(track)
                appState?.playerOpen = true
            }
        }
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            val action = intent.action
            intent.action = null
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            // A shared playlist code in the message itself, or (for very long playlists) in a text file.
            if (text != null && (PlaylistShare.find(text) != null || findLink(text) != null)) {
                appState?.openShared(text)
                return
            }
            val uri = if (action == Intent.ACTION_VIEW) intent.data
            else androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            if (uri != null) {
                lifecycleScope.launch {
                    val content = withContext(Dispatchers.IO) { readSharedText(uri) }
                    if (content == null || appState?.openShared(content) != true) appState?.toast("Nothing Lumen can open in that file")
                }
            }
        }
    }

    /** Reads a small shared text file (at most 1 MB: a playlist code is far smaller). */
    private fun readSharedText(uri: android.net.Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(1024 * 1024 + 1)
            if (bytes.size > 1024 * 1024) null else bytes.decodeToString()
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---- Picture-in-picture ------------------------------------------------------------------

    private fun action(icon: Int, title: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(this, code, Intent(this, PipActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    )

    private fun pipParams(): PictureInPictureParams {
        val state = Graph.player.state.value
        val actions = listOf(
            action(R.drawable.ic_pip_previous, "Previous", PipActionReceiver.ACTION_PREVIOUS, 1),
            if (state.isPlaying) action(R.drawable.ic_pip_pause, "Pause", PipActionReceiver.ACTION_TOGGLE, 2)
            else action(R.drawable.ic_pip_play, "Play", PipActionReceiver.ACTION_TOGGLE, 2),
            action(R.drawable.ic_pip_next, "Next", PipActionReceiver.ACTION_NEXT, 3),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .setActions(actions)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(Graph.store.settings.autoPip && state.isPlaying)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    fun enterPip() {
        if (Graph.player.state.value.current == null) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Graph.store.settings.autoPip && Graph.player.state.value.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_PLAY_TRACK = "play_track"
    }
}
