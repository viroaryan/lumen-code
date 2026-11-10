// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import app.lumen.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.data.Lyrics
import app.lumen.data.Track
import app.lumen.studio.LyricsLayout
import app.lumen.studio.MixParams
import app.lumen.studio.Presets
import app.lumen.studio.StudioCatalog
import app.lumen.studio.StudioEngine
import app.lumen.studio.StudioPhase
import app.lumen.ui.LocalApp
import app.lumen.ui.components.ButtonStyle
import app.lumen.ui.components.Chip
import app.lumen.ui.components.EmptyState
import app.lumen.ui.components.LButton
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.PlayButton
import app.lumen.ui.components.formatDuration
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Mode { RECORD, MIX }

@Composable
fun StudioSessionScreen(id: String) {
    val app = LocalApp.current
    val context = LocalContext.current
    val c = Lumen.colors
    val scope = rememberCoroutineScope()
    val engine = remember(id) { StudioEngine(context.applicationContext, Graph.studio) }
    val phase by engine.phase.collectAsState()
    val take = remember(id, phase) { engine.take }
    val savedTake = remember(id) { if (id.startsWith("take:")) Graph.studio.get(id.removePrefix("take:")) else null }
    val track: Track? = remember(id) { savedTake?.track ?: StudioTracks[id.removePrefix("new:")] }

    var mode by remember { mutableStateOf(if (savedTake != null) Mode.MIX else Mode.RECORD) }
    var params by remember { mutableStateOf(savedTake?.params ?: MixParams()) }
    var layout by remember { mutableStateOf(savedTake?.layout ?: LyricsLayout()) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember { mutableStateOf(true) }
    var countIn by remember { mutableIntStateOf(0) }

    if (track == null) {
        LaunchedEffect(Unit) { app.nav.popBackStack() }
        return
    }

    // Pause the music player, keep the screen on, and load everything.
    LaunchedEffect(id) {
        Graph.player.pause()
        if (savedTake != null) engine.open(savedTake) else engine.prepare(track)
        engine.params = params
    }
    LaunchedEffect(id) {
        lyrics = runCatching { StudioCatalog.lyrics(track) }.getOrNull()
        lyricsLoading = false
    }
    DisposableEffect(id) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            engine.saveParams()
            engine.saveLayout(layout)
            engine.release()
        }
    }
    LaunchedEffect(params) { engine.params = params }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) app.toast("Lumen needs the microphone to record you")
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        scope.launch {
            for (n in 3 downTo 1) { countIn = n; delay(800) }
            countIn = 0
            engine.startRecording(0)
        }
    }

    fun stopRecording() {
        val saved = engine.stopRecording()
        if (saved != null) {
            engine.saveLayout(layout)
            mode = Mode.MIX
            app.toast("Take saved. Shape your sound below.")
        } else app.toast("That was too short to keep")
    }

    BackHandler {
        when {
            engine.isRecording -> stopRecording()
            else -> app.nav.popBackStack()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // Top bar: back, song, and the Record / Mix switch.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LIconButton(LIcons.Back, { if (engine.isRecording) stopRecording() else app.nav.popBackStack() }, contentDescription = "Back")
            Column(Modifier.weight(1f)) {
                Text(StudioCatalog.songTitle(track.title), style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (mode == Mode.RECORD) "Recording booth" else "Mixing desk", style = Lumen.type.small, color = c.textDim)
            }
            ModeSwitch(mode, mixEnabled = take != null && phase !is StudioPhase.Recording) { m ->
                engine.pause()
                mode = m
            }
        }

        when (val p = phase) {
            is StudioPhase.Preparing -> Preparing(p)
            is StudioPhase.Failed -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(LIcons.Wifi, "Something went wrong", p.message) {
                    LButton("Try again", { scope.launch { if (savedTake != null) engine.open(savedTake) else engine.prepare(track) } }, style = ButtonStyle.Secondary)
                }
            }
            else -> if (mode == Mode.RECORD) {
                RecordBooth(engine, phase, lyrics, lyricsLoading, layout, onLayout = { layout = it }, countIn = countIn, onRecord = ::startRecording, onStop = ::stopRecording, params = params, onParams = { params = it })
            } else {
                MixDesk(engine, phase, params, onParams = { params = it }, onReRecord = { mode = Mode.RECORD })
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: Mode, mixEnabled: Boolean, onMode: (Mode) -> Unit) {
    val c = Lumen.colors
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface).padding(3.dp)) {
        listOf(Mode.RECORD to "Record", Mode.MIX to "Mix").forEach { (m, label) ->
            val on = m == mode
            val enabled = m == Mode.RECORD || mixEnabled
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) c.text else Color.Transparent)
                    .pressable({ if (enabled) onMode(m) }, enabled = enabled).padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(label, style = Lumen.type.label, color = if (on) c.bg else if (enabled) c.text else c.textFaint)
            }
        }
    }
}

@Composable
private fun Preparing(p: StudioPhase.Preparing) {
    val c = Lumen.colors
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = c.text, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(20.dp))
        Text(p.label, style = Lumen.type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator({ p.progress }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.text, trackColor = c.line)
        Spacer(Modifier.height(10.dp))
        Text("The music is saved for next time, so this happens once per song.", style = Lumen.type.small, color = c.textDim, textAlign = TextAlign.Center)
    }
}

// ---- Recording booth ---------------------------------------------------------------------------

@Composable
private fun RecordBooth(
    engine: StudioEngine,
    phase: StudioPhase,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    layout: LyricsLayout,
    onLayout: (LyricsLayout) -> Unit,
    countIn: Int,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    params: MixParams,
    onParams: (MixParams) -> Unit,
) {
    val c = Lumen.colors
    val position by engine.positionMs.collectAsState()
    val level by engine.inputLevel.collectAsState()
    val recording = phase is StudioPhase.Recording
    val rehearsing = phase is StudioPhase.Playing
    var showLayout by remember { mutableStateOf(false) }
    var monitor by remember { androidx.compose.runtime.mutableFloatStateOf(engine.monitorVolume) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            KaraokeLyrics(lyrics, lyricsLoading, if (recording || rehearsing) position else 0L, layout, Modifier.fillMaxSize())
            // Count-in overlay.
            AnimatedContent(countIn, transitionSpec = { (scaleIn(initialScale = 1.6f) + fadeIn()) togetherWith fadeOut() }, label = "count", modifier = Modifier.align(Alignment.Center)) { n ->
                if (n > 0) {
                    Box(Modifier.size(140.dp).clip(CircleShape).background(c.bg.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                        Text("$n", style = Lumen.type.display.copy(fontSize = 72.sp), color = c.text)
                    }
                }
            }
        }

        if (showLayout) {
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp)) {
                Text("Lyrics placement", style = Lumen.type.bodyStrong, color = c.text)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Timing", style = Lumen.type.small, color = c.textDim, modifier = Modifier.width(64.dp))
                    LButton("−0.5s", { onLayout(layout.copy(offsetMs = layout.offsetMs - 500)) }, style = ButtonStyle.Ghost)
                    Spacer(Modifier.width(6.dp))
                    Text("${if (layout.offsetMs >= 0) "+" else ""}${"%.1f".format(layout.offsetMs / 1000f)}s", style = Lumen.type.label, color = c.text, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.width(6.dp))
                    LButton("+0.5s", { onLayout(layout.copy(offsetMs = layout.offsetMs + 500)) }, style = ButtonStyle.Ghost)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = Lumen.type.small, color = c.textDim, modifier = Modifier.width(64.dp))
                    LButton("A−", { onLayout(layout.copy(scale = (layout.scale - 0.15f).coerceAtLeast(0.7f))) }, style = ButtonStyle.Ghost)
                    Spacer(Modifier.width(6.dp))
                    LButton("A+", { onLayout(layout.copy(scale = (layout.scale + 0.15f).coerceAtMost(1.9f))) }, style = ButtonStyle.Ghost)
                    Spacer(Modifier.width(12.dp))
                    Chip("Left", !layout.centered, { onLayout(layout.copy(centered = false)) })
                    Spacer(Modifier.width(6.dp))
                    Chip("Centre", layout.centered, { onLayout(layout.copy(centered = true)) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LIcons.Mic, null, Modifier.size(16.dp), tint = c.textDim)
                Spacer(Modifier.width(8.dp))
                LevelMeter(if (recording) level else 0f, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("${formatDuration(position)} / ${formatDuration(engine.backingDurationMs)}", style = Lumen.type.label, color = c.textDim)
            }
            // Two different things: what you hear while singing, and how loud the music ends up in your song.
            Fader(
                ControlSpec("Music in your earphones", monitor, 0f..1f, 0.85f, 0.05f, pctFmt, {
                    monitor = it
                    engine.monitorVolume = it
                }),
                onFineTune = {},
            )
            Fader(mixSpecs(params, onParams).getValue("Music").let {
                ControlSpec("Music in your song", it.value, it.range, it.default, it.step, it.format, it.onChange, help = it.help)
            }, onFineTune = {})
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LIconButton(LIcons.TextSize, { showLayout = !showLayout }, tint = if (showLayout) c.text else c.textDim, contentDescription = "Lyrics placement")
            Spacer(Modifier.weight(1f))
            RecordButton(recording, onClick = { if (recording) onStop() else if (countIn == 0) onRecord() })
            Spacer(Modifier.weight(1f))
            // Rehearse: hear the music with scrolling lyrics, without recording.
            LIconButton(
                if (rehearsing) LIcons.Pause else LIcons.Headphones,
                { if (rehearsing) engine.pause() else if (!recording) engine.play(0, musicOnly = true) },
                enabled = !recording, tint = if (rehearsing) c.text else c.textDim, contentDescription = "Rehearse",
            )
        }
        Text(
            when {
                recording -> "Recording · tap to stop"
                rehearsing -> "Rehearsing: practise with the music, nothing is recorded"
                else -> "Tap to record (3-second count-in). Tap the headphones to rehearse first."
            },
            style = Lumen.type.small, color = c.textDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val c = Lumen.colors
    val pulse by animateFloatAsState(if (recording) 1.08f else 1f, tween(400), label = "pulse")
    Box(
        Modifier.size(84.dp).scale(pulse).clip(CircleShape).border(3.dp, c.text, CircleShape).pressable(onClick).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().scale(if (recording) 0.55f else 1f).clip(if (recording) RoundedCornerShape(10.dp) else CircleShape).background(if (recording) c.text else Color(0xFFD9534F)))
    }
}

/** Big, readable karaoke lyrics: the current line bright, the next ones waiting below. */
@Composable
private fun KaraokeLyrics(lyrics: Lyrics?, loading: Boolean, positionMs: Long, layout: LyricsLayout, modifier: Modifier) {
    val c = Lumen.colors
    val align = if (layout.centered) TextAlign.Center else TextAlign.Start
    when {
        loading -> Box(modifier, contentAlignment = Alignment.Center) { Text("Finding lyrics…", style = Lumen.type.body, color = c.textDim) }
        lyrics == null || lyrics.lines.isEmpty() -> Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No lyrics found for this song.\nSing from memory, you've got this.", style = Lumen.type.title, color = c.textDim, textAlign = TextAlign.Center)
        }
        else -> {
            val list = rememberLazyListState()
            val t = positionMs + layout.offsetMs
            val active = if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= t }.coerceAtLeast(0) else -1
            LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
            LazyColumn(modifier, state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)) {
                itemsIndexed(lyrics.lines) { i, line ->
                    val isActive = i == active
                    Text(
                        line.text.ifBlank { "♪" },
                        style = Lumen.type.display.copy(fontSize = (26 * layout.scale).sp, lineHeight = (34 * layout.scale).sp),
                        color = when {
                            !lyrics.synced -> c.text
                            isActive -> c.text
                            i < active -> c.textFaint
                            else -> c.textDim
                        },
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// ---- Mixing desk -------------------------------------------------------------------------------

private val dbFmt = { v: Float -> "${if (v > 0) "+" else ""}${"%.1f".format(v)} dB" }
private val pctFmt = { v: Float -> "${(v * 100).roundToInt()}%" }

/** Every mix control, described once: the desk, the fine-tune panel and the booth all use these. */
private fun mixSpecs(p: MixParams, set: (MixParams) -> Unit): Map<String, ControlSpec> = listOf(
    ControlSpec("Voice", p.voiceDb, -12f..12f, 0f, 0.5f, dbFmt, { set(p.copy(voiceDb = it)) }, bipolar = true,
        help = "How loud your voice sits in the song."),
    ControlSpec("Music", p.musicDb, -30f..6f, -4f, 0.5f, dbFmt, { set(p.copy(musicDb = it)) },
        help = "How loud the background music plays in your finished song."),
    ControlSpec("Pan", p.pan, -1f..1f, 0f, 0.05f,
        { v -> if (kotlin.math.abs(v) < 0.025f) "Centre" else if (v < 0) "L ${(-v * 100).roundToInt()}" else "R ${(v * 100).roundToInt()}" },
        { set(p.copy(pan = it)) }, bipolar = true, help = "Moves your voice left or right."),
    ControlSpec("Sync", p.syncMs, -300f..300f, 0f, 5f, { "${if (it > 0) "+" else ""}${it.roundToInt()} ms" }, { set(p.copy(syncMs = it)) },
        bipolar = true, help = "Nudges your voice earlier (+) or later (−) if it doesn't line up with the beat."),
    ControlSpec("Duck music", p.duck, 0f..1f, 0.2f, 0.05f, pctFmt, { set(p.copy(duck = it)) },
        help = "Lowers the music slightly while you sing, so every word stays clear."),
    ControlSpec("Gate", p.gateDb, -80f..-30f, -55f, 1f, { if (it <= -79f) "Off" else "${it.roundToInt()} dB" }, { set(p.copy(gateDb = it)) },
        help = "Silences the mic below this level: removes hiss and breaths between lines."),
    ControlSpec("Low cut", p.lowCutHz, 20f..400f, 90f, 5f, { "${it.roundToInt()} Hz" }, { set(p.copy(lowCutHz = it)) },
        help = "Removes rumble and handling noise below this frequency."),
    ControlSpec("Body", p.lowDb, -9f..9f, 0f, 0.5f, dbFmt, { set(p.copy(lowDb = it)) }, bipolar = true,
        help = "Warmth and weight around 150 Hz."),
    ControlSpec("Presence", p.presenceDb, -9f..9f, 2f, 0.5f, dbFmt, { set(p.copy(presenceDb = it)) }, bipolar = true,
        help = "Clarity of words around 3 kHz."),
    ControlSpec("Air", p.airDb, -9f..9f, 2f, 0.5f, dbFmt, { set(p.copy(airDb = it)) }, bipolar = true,
        help = "Sparkle and breathiness above 10 kHz."),
    ControlSpec("Compress", p.compress, 0f..1f, 0.45f, 0.05f, pctFmt, { set(p.copy(compress = it)) },
        help = "Evens out loud and soft lines so your voice stays steady in the mix."),
    ControlSpec("De-ess", p.deEss, 0f..1f, 0.3f, 0.05f, pctFmt, { set(p.copy(deEss = it)) },
        help = "Softens sharp S and SH sounds."),
    ControlSpec("Warmth", p.warmth, 0f..1f, 0.15f, 0.05f, pctFmt, { set(p.copy(warmth = it)) },
        help = "Gentle tape-style saturation for a fuller, richer tone."),
    ControlSpec("Echo time", p.echoMs, 60f..800f, 280f, 10f, { "${it.roundToInt()} ms" }, { set(p.copy(echoMs = it)) },
        help = "Gap between your voice and its echo."),
    ControlSpec("Repeats", p.echoFeedback, 0f..0.85f, 0.3f, 0.05f, pctFmt, { set(p.copy(echoFeedback = it)) },
        help = "How many times the echo repeats."),
    ControlSpec("Echo", p.echoMix, 0f..0.5f, 0.08f, 0.01f, pctFmt, { set(p.copy(echoMix = it)) },
        help = "How loud the echo is."),
    ControlSpec("Room size", p.reverbSize, 0f..1f, 0.55f, 0.05f, pctFmt, { set(p.copy(reverbSize = it)) },
        help = "From a small booth to a large hall."),
    ControlSpec("Reverb", p.reverbMix, 0f..0.6f, 0.18f, 0.01f, pctFmt, { set(p.copy(reverbMix = it)) },
        help = "How much room sound surrounds your voice."),
).associateBy { it.label }

@Composable
private fun MixDesk(engine: StudioEngine, phase: StudioPhase, params: MixParams, onParams: (MixParams) -> Unit, onReRecord: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val c = Lumen.colors
    val scope = rememberCoroutineScope()
    val position by engine.positionMs.collectAsState()
    val peaks by engine.waveform.collectAsState()
    val playing = phase is StudioPhase.Playing
    val duration = engine.mixDurationMs.coerceAtLeast(1)
    var preset by remember { mutableStateOf<String?>(null) }
    var fine by remember { mutableStateOf<String?>(null) }
    val specs = mixSpecs(params) { preset = null; onParams(it) }
    fun knob(label: String) = @Composable { Knob(specs.getValue(label), onFineTune = { fine = label }) }

    fine?.let { label -> FineTuneSheet(specs.getValue(label)) { fine = null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Transport
        item {
            Column(Modifier.padding(16.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp)) {
                Waveform(peaks, (position.toFloat() / duration).coerceIn(0f, 1f), { engine.seek((it * duration).toLong()) }, Modifier.fillMaxWidth().height(64.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(position), style = Lumen.type.label, color = c.textDim)
                    Spacer(Modifier.weight(1f))
                    LIconButton(LIcons.Previous, { engine.seek(0) }, contentDescription = "To start")
                    Spacer(Modifier.width(8.dp))
                    PlayButton(playing, { if (playing) engine.pause() else engine.play() }, size = 56.dp)
                    Spacer(Modifier.width(8.dp))
                    LIconButton(LIcons.Mic, onReRecord, contentDescription = "Record again")
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(duration), style = Lumen.type.label, color = c.textDim)
                }
                Text(
                    "Every change is heard live while it plays. Tap a knob for exact steps, double-tap to reset.",
                    style = Lumen.type.small, color = c.textDim, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // Presets
        item {
            Text("PRESETS", style = Lumen.type.micro, color = c.textDim, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Presets) { (name, p) ->
                    // Presets shape the sound but keep your levels and sync.
                    Chip(name, preset == name, {
                        preset = name
                        onParams(p.copy(voiceDb = params.voiceDb, musicDb = params.musicDb, syncMs = params.syncMs, pan = params.pan))
                    })
                }
            }
        }
        item {
            Panel("Levels", "You decide how loud the music is against your voice") {
                Fader(specs.getValue("Voice"), onFineTune = { fine = "Voice" })
                Fader(specs.getValue("Music"), onFineTune = { fine = "Music" })
                KnobRow { knob("Pan")(); knob("Sync")(); knob("Duck music")() }
            }
        }
        item { Panel("Clean-up", "Noise gate and low cut remove hum, breath and room noise") { KnobRow { knob("Gate")(); knob("Low cut")() } } }
        item { Panel("Tone", "Three-band vocal EQ") { KnobRow { knob("Body")(); knob("Presence")(); knob("Air")() } } }
        item {
            Panel("Dynamics", "Compressor evens out loud and soft lines; de-esser softens sharp S sounds") {
                KnobRow { knob("Compress")(); knob("De-ess")(); knob("Warmth")() }
            }
        }
        item {
            Panel("Space", "Echo and reverb place your voice in a room") {
                KnobRow { knob("Echo time")(); knob("Repeats")(); knob("Echo")() }
                Spacer(Modifier.height(8.dp))
                KnobRow { knob("Room size")(); knob("Reverb")() }
            }
        }
        item {
            val exporting = phase as? StudioPhase.Exporting
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (exporting != null) {
                    Text("Mixing down… ${(exporting.progress * 100).roundToInt()}%", style = Lumen.type.label, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator({ exporting.progress }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.text, trackColor = c.line)
                } else {
                    LButton("Export song", {
                        scope.launch {
                            engine.params = params
                            val uri = engine.export()
                            if (uri == null) app.toast("Couldn't save the song") else {
                                app.toast("Saved to Music / Lumen Studio")
                                val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, "Share your song"))
                            }
                        }
                    }, icon = LIcons.Export, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Your voice and the music in one file: high-quality AAC (256 kbps) in Music / Lumen Studio.", style = Lumen.type.small, color = c.textDim)
                }
            }
        }
    }
}

@Composable
private fun KnobRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
