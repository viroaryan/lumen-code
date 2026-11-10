// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import app.lumen.ui.components.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.lumen.Graph
import app.lumen.data.IdentifiedSong
import app.lumen.identify.BubbleService
import app.lumen.identify.SongFinder
import app.lumen.ui.LocalApp
import app.lumen.ui.components.Artwork
import app.lumen.ui.components.Chip
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.LButton
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.LumenLogo
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface Hearing {
    data object Idle : Hearing
    data object Listening : Hearing
    data class Result(val outcome: SongFinder.Outcome) : Hearing
}

/**
 * The song finder: "What's this song?" right here, the floating bubble for every other app, and the
 * songs found so far.
 */
@Composable
fun IdentifySheet() {
    val app = LocalApp.current
    if (!app.identifyOpen) return
    val context = LocalContext.current
    val c = Lumen.colors
    val scope = rememberCoroutineScope()
    val library by Graph.store.state.collectAsState()
    val bubbleOn by BubbleService.running.collectAsState()

    var hearing by remember { mutableStateOf<Hearing>(Hearing.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasMic by remember { mutableStateOf(SongFinder.hasMic(context)) }
    /** What to do once the microphone is allowed. */
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** The user went to the system screen to allow the bubble; turn it on when they come back. */
    var wantBubble by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMic = granted
        if (granted) afterMic?.invoke() else app.toast("The song finder needs the microphone to hear the music")
        afterMic = null
    }

    fun listenHere() {
        if (!SongFinder.hasMic(context)) { afterMic = { listenHere() }; micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        if (job?.isActive == true) { job?.cancel(); hearing = Hearing.Idle; return }
        hearing = Hearing.Listening
        progress = 0f
        job = scope.launch {
            val outcome = SongFinder.identify(context, projection = BubbleService.projection.takeUnless { Graph.store.settings.finderMic }) { p -> progress = p }
            if (outcome is SongFinder.Outcome.Found) Graph.store.addIdentified(outcome.song)
            hearing = Hearing.Result(outcome)
        }
    }

    var setupOpen by remember { mutableStateOf(false) }
    val useMic by remember { Graph.store.state.map { it.settings.finderMic }.distinctUntilChanged() }.collectAsState(Graph.store.settings.finderMic)

    fun bubbleStarted(ok: Boolean, phoneSound: Boolean) {
        app.toast(
            when {
                !ok -> "Couldn't start the bubble"
                phoneSound -> "Bubble is on. Open any app and tap it when a song plays"
                else -> "Bubble is on, listening with the mic"
            },
        )
    }

    // Android's own "Start recording?" question. Lumen takes only the sound from it, never the picture.
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) bubbleStarted(BubbleService.start(context, result.resultCode, data), true)
        else bubbleStarted(BubbleService.start(context), false)
    }

    fun startBubble() {
        // Anything still missing: the setup screen walks through it, one step at a time.
        if (!SongFinder.hasMic(context) || !Settings.canDrawOverlays(context)) { setupOpen = true; return }
        if (!Graph.store.settings.finderMic && android.os.Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching {
                val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                capture.launch(manager.createScreenCaptureIntent())
            }.isSuccess
            if (ok) return
        }
        bubbleStarted(BubbleService.start(context), false)
    }

    if (setupOpen) FinderSetup(onClose = { setupOpen = false }, onReady = { setupOpen = false; startBubble() })

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(context)
        hasMic = SongFinder.hasMic(context)
        if (wantBubble) {
            wantBubble = false
            if (canOverlay) startBubble()
        }
    }

    // Leaving the sheet stops listening (the bubble keeps working on its own).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { job?.cancel() } }

    ModalBottomSheet(
        onDismissRequest = { app.identifyOpen = false },
        containerColor = c.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(Modifier.navigationBarsPadding().fillMaxWidth().heightIn(max = 760.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("What's this song?", style = Lumen.type.title, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Heard it on a reel, a video, a café? Lumen names it and plays it.",
                        style = Lumen.type.small, color = c.textDim, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(22.dp))
                    ListenButton(listening = hearing == Hearing.Listening, progress = progress) { listenHere() }
                    Spacer(Modifier.height(14.dp))
                    val status = when (val h = hearing) {
                        Hearing.Idle -> "Tap to listen"
                        Hearing.Listening -> "Listening… tap to stop"
                        is Hearing.Result -> when (h.outcome) {
                            is SongFinder.Outcome.Found -> "Found it"
                            SongFinder.Outcome.NotFound -> "Couldn't name this one. Try closer to the music."
                            SongFinder.Outcome.TooQuiet -> "Too quiet. Play it out loud (the mic can't hear headphones)."
                            SongFinder.Outcome.Offline -> "No internet. Naming a song needs a connection."
                            SongFinder.Outcome.MicBusy -> "The mic is busy in another app. Try again after."
                            SongFinder.Outcome.NoPermission -> "Allow the microphone to use the song finder."
                        }
                    }
                    Text(status, style = Lumen.type.body, color = c.textDim, textAlign = TextAlign.Center)
                    (hearing as? Hearing.Result)?.outcome?.let { o ->
                        if (o is SongFinder.Outcome.Found) {
                            Spacer(Modifier.height(14.dp))
                            FoundCard(o.song) { app.playIdentified(o.song) }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                Text("Listen to", style = Lumen.type.micro, color = c.textDim, modifier = Modifier.padding(horizontal = 24.dp))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    fun pick(mic: Boolean) {
                        if (mic == useMic) return
                        Graph.store.updateSettings { it.copy(finderMic = mic) }
                        // Phone sound needs Android's permission for this bubble session: restart it to ask.
                        if (!mic && bubbleOn && BubbleService.projection == null) { BubbleService.stop(context); startBubble() }
                    }
                    Chip("This phone's sound", !useMic, { pick(false) })
                    Chip("Mic (music outside)", useMic, { pick(true) })
                }
                Text(
                    if (useMic) "Hears music playing near the phone: a laptop, a TV, a shop. Earphones can't be heard this way."
                    else "Hears what this phone is playing in any app, with earphones too. If an app keeps its sound private, Lumen switches to the mic by itself.",
                    style = Lumen.type.small, color = c.textDim, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh)
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF5466EC)), contentAlignment = Alignment.Center) {
                        LumenLogo(Modifier.size(24.dp), color = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Floating bubble", style = Lumen.type.bodyStrong, color = c.text)
                        Text(
                            if (bubbleOn) "On. Open Instagram, YouTube or any app and tap the bubble when a song plays."
                            else "Puts the Lumen bubble over every app. Drag it anywhere; drop it on ✕ to hide.",
                            style = Lumen.type.small, color = c.textDim,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = bubbleOn,
                        onCheckedChange = { on -> if (on) startBubble() else BubbleService.stop(context) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF5466EC), checkedThumbColor = Color.White),
                    )
                }
                if (!canOverlay || !hasMic) {
                    Text(
                        "The bubble needs two permissions: the microphone (to hear the song) and \"display over other apps\" (to show on top). Lumen only listens after you tap it, and only the song's fingerprint is sent to name it, never the recording.",
                        style = Lumen.type.small, color = c.textFaint,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
            }
            if (library.identified.isNotEmpty()) {
                item {
                    Text("Found before", style = Lumen.type.heading, color = c.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp))
                }
                items(library.identified, key = { "${it.at}:${it.title}" }) { song ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ app.playIdentified(song) }).padding(start = 24.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(song.artwork, Modifier.size(48.dp), corner = 6.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = Lumen.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        LIconButton(LIcons.Play, { app.playIdentified(song) }, tint = c.text, iconSize = 18.dp, contentDescription = "Play")
                        LIconButton(LIcons.Close, { Graph.store.removeIdentified(song) }, tint = c.textDim, iconSize = 18.dp, contentDescription = "Remove")
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * The setup screen for the floating bubble: every permission as its own step, with why it's needed,
 * a tick when it's done, and what to do when the phone says "Restricted setting".
 */
@Composable
private fun FinderSetup(onClose: () -> Unit, onReady: () -> Unit) {
    val context = LocalContext.current
    val c = Lumen.colors
    var mic by remember { mutableStateOf(SongFinder.hasMic(context)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    fun notifOn() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notif by remember { mutableStateOf(notifOn()) }
    /** The overlay settings were opened but the switch didn't get turned on: likely "Restricted setting". */
    var overlayTried by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = notifOn() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mic = SongFinder.hasMic(context); overlay = Settings.canDrawOverlays(context); notif = notifOn() }

    fun appInfo() = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(24.dp)).background(c.surface).padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            item {
                Text("Set up the song finder", style = Lumen.type.title, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("Three quick steps, once. Lumen listens only after you tap the bubble, and only a song fingerprint leaves the phone, never the recording.", style = Lumen.type.small, color = c.textDim)
                Spacer(Modifier.height(14.dp))
            }
            item {
                SetupStep(1, "Microphone", "To hear the song. Used only while the bubble is listening.", mic, "Allow") {
                    runCatching { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                }
                if (!mic) Text("Nothing happens when you tap Allow? Open App info → Permissions → Microphone → Allow.", style = Lumen.type.small, color = c.textFaint, modifier = Modifier.pressable({ appInfo() }).padding(start = 44.dp, bottom = 8.dp))
            }
            item {
                SetupStep(2, "Show over other apps", "So the bubble can sit on top of Instagram, YouTube and the rest.", overlay, "Open settings") {
                    overlayTried = true
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
                }
                if (!overlay) {
                    Column(Modifier.padding(start = 44.dp, bottom = 8.dp)) {
                        Text(
                            if (overlayTried) "Is the switch grey, or does the phone say \"Restricted setting\"? That's Android being careful with apps installed from outside the Play Store. Fix it once:"
                            else "If the phone says \"Restricted setting\":",
                            style = Lumen.type.small, color = c.textDim,
                        )
                        Text("1. Tap \"Open App info\" below\n2. Tap ⋮ at the top right\n3. Tap \"Allow restricted settings\"\n4. Come back and tap \"Open settings\" again", style = Lumen.type.small, color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                        LButton("Open App info", { appInfo() }, style = ButtonStyle.Secondary)
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) item {
                SetupStep(3, "Notification (optional)", "Shows \"Song finder is on\" with a Turn off button, so you always know.", notif, "Allow") {
                    runCatching { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            item {
                Text(
                    "Next, Android asks \"Start recording or casting?\". That is how Lumen hears this phone's sound; it takes the sound only, never your screen. Say no and Lumen uses the mic instead.\n\nYour phone may also show a safety warning because Lumen isn't from the Play Store. Lumen is open source: anyone can read its code.",
                    style = Lumen.type.small, color = c.textFaint, modifier = Modifier.padding(vertical = 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LButton("Not now", onClose, style = ButtonStyle.Ghost)
                    Spacer(Modifier.weight(1f))
                    LButton("Turn on the bubble", onReady, enabled = mic && overlay)
                }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, why: String, done: Boolean, action: String, onClick: () -> Unit) {
    val c = Lumen.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (done) Color(0xFF2E9E5B) else c.surfaceHigh), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", style = Lumen.type.label, color = if (done) Color.White else c.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.bodyStrong, color = c.text)
            Text(why, style = Lumen.type.small, color = c.textDim)
        }
        Spacer(Modifier.width(8.dp))
        if (done) Text("Done", style = Lumen.type.label, color = Color(0xFF2E9E5B)) else LButton(action, onClick)
    }
}

@Composable
private fun FoundCard(song: IdentifiedSong, onPlay: () -> Unit) {
    val c = Lumen.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artwork, Modifier.size(64.dp), corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = Lumen.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        LButton("Play", onPlay, icon = LIcons.Play, style = ButtonStyle.Primary)
    }
}

/** The big round listen button: the Lumen mark with ripples while it listens. */
@Composable
private fun ListenButton(listening: Boolean, progress: Float, onClick: () -> Unit) {
    val c = Lumen.colors
    val t = rememberInfiniteTransition(label = "listen")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val breathe by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    val brand = Color(0xFF5466EC)
    val peach = Color(0xFFFFB29E)
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp)) {
            val r = size.minDimension / 2f * 0.62f
            if (listening) {
                for (i in 0 until 3) {
                    val p = (wave + i / 3f) % 1f
                    drawCircle(peach.copy(alpha = (1f - p) * 0.7f), radius = r + p * size.minDimension * 0.17f, style = Stroke(width = 5f))
                }
                drawArc(
                    peach, -90f, 360f * progress, false,
                    topLeft = Offset(center.x - r - 14f, center.y - r - 14f),
                    size = androidx.compose.ui.geometry.Size((r + 14f) * 2, (r + 14f) * 2),
                    style = Stroke(width = 9f, cap = StrokeCap.Round),
                )
            } else {
                drawCircle(brand.copy(alpha = 0.18f), radius = r * 1.18f * breathe)
            }
        }
        Box(
            Modifier.size(118.dp).scale(if (listening) breathe else 1f).clip(CircleShape).background(brand).pressable(onClick),
            contentAlignment = Alignment.Center,
        ) { LumenLogo(Modifier.size(62.dp), color = Color.White) }
    }
}
