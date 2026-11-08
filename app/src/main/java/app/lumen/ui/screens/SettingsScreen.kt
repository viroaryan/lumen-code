// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.lumen.engine.StorageGuard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lumen.Graph
import app.lumen.data.LibraryBackup
import app.lumen.data.Source
import app.lumen.data.ThemeMode
import app.lumen.data.source.Quality
import app.lumen.ui.LocalApp
import app.lumen.ui.components.LIconButton
import app.lumen.ui.components.LumenLogo
import app.lumen.ui.components.pressable
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen
import app.lumen.ui.theme.Serif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val app = LocalApp.current
    val c = Lumen.colors
    val settings = Graph.store.state.collectAsState().value.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var usage by remember { mutableStateOf<StorageGuard.Usage?>(null) }
    LaunchedEffect(Unit) { usage = StorageGuard.usage(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) app.toast("Notifications are off for Lumen in system settings")
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = LibraryBackup.export(Graph.store.current)
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            app.toast(if (ok) "Backup saved. Keep this file somewhere safe." else "Couldn't save the backup")
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        LibraryBackup.readLimited(input)?.let(LibraryBackup::read)
                    }
                }.getOrNull()
            }
            if (backup == null) {
                app.toast("That isn't a Lumen backup, or the file is damaged")
                return@launch
            }
            val added = Graph.store.restore(backup)
            app.toast("Restored ${added.playlists} playlists, ${added.liked} liked songs, ${added.saved} saved items")
        }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LIconButton(LIcons.Back, { app.nav.popBackStack() }, contentDescription = "Back")
                Text("Settings", style = Lumen.type.title, color = c.text)
            }
        }

        item { Group("Appearance") }
        item {
            Segmented(
                listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK),
                settings.theme,
            ) { mode -> Graph.store.updateSettings { it.copy(theme = mode) } }
        }

        item { Group("Playback") }
        item {
            Label("Streaming quality")
            Segmented(listOf("Low" to Quality.LOW, "Normal" to Quality.NORMAL, "High" to Quality.HIGH), settings.quality) { q ->
                Graph.store.updateSettings { it.copy(quality = q) }
            }
        }
        item {
            Label("Download quality")
            Segmented(listOf("Low" to Quality.LOW, "Normal" to Quality.NORMAL, "High" to Quality.HIGH), settings.downloadQuality) { q ->
                Graph.store.updateSettings { it.copy(downloadQuality = q) }
            }
        }
        item {
            Toggle("Download liked songs", "Every song you like is saved for offline listening too", settings.autoDownloadLiked) { v ->
                Graph.store.updateSettings { it.copy(autoDownloadLiked = v) }
                if (v) {
                    val n = Graph.downloads.download(Graph.store.current.liked)
                    if (n > 0) app.toast("Downloading $n liked songs")
                }
            }
        }
        item { Toggle("Autoplay","Keep playing similar songs when your music ends", settings.autoplay) { v -> Graph.store.updateSettings { it.copy(autoplay = v) } } }
        item { Toggle("Skip silence", "Trim quiet gaps between and within songs", settings.skipSilence) { v -> Graph.store.updateSettings { it.copy(skipSilence = v) } } }
        item { Toggle("Picture-in-picture", "Show a floating player when you leave the app", settings.autoPip) { v -> Graph.store.updateSettings { it.copy(autoPip = v) } } }

        item { Group("Music sources") }
        listOf(
            Source.YOUTUBE to "The largest catalogue, from global hits to rare uploads",
            Source.SAAVN to "Bollywood, regional and Indian music in 320 kbps",
            Source.SOUNDCLOUD to "Remixes, independent artists and DJ sets",
        ).forEach { (source, desc) ->
            item {
                Toggle(source.label, desc, source in settings.sources) { on ->
                    Graph.store.updateSettings { s ->
                        val next = if (on) s.sources + source else s.sources - source
                        s.copy(sources = next.ifEmpty { setOf(Source.YOUTUBE) })
                    }
                }
            }
        }

        item { Group("Notifications") }
        item {
            Toggle(
                "Picks & new releases",
                "New songs from your artists, mood picks and talks you'll like, with a bit of fun. At most once a day, never at night.",
                settings.notifications,
            ) { on ->
                Graph.store.updateSettings { it.copy(notifications = on) }
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        item { Group("Storage") }
        item { StorageBar(usage) }
        item {
            Label("Song cache limit")
            Segmented(
                listOf("Auto" to 0, "128 MB" to 128, "512 MB" to 512, "1 GB" to 1024),
                settings.cacheLimitMb,
            ) { mb ->
                Graph.store.updateSettings { it.copy(cacheLimitMb = mb) }
                scope.launch(Dispatchers.IO) {
                    // Shrinking takes effect now; a bigger limit applies the next time the app starts.
                    StorageGuard.trimAudioCache(StorageGuard.audioCacheLimit(context, mb))
                    usage = StorageGuard.usage(context)
                }
            }
            Text(
                if (settings.cacheLimitMb == 0) "Auto keeps the cache to a few percent of your free space and shrinks it when the phone runs low."
                else "Songs you play are kept up to this size for instant replays and offline listening.",
                style = Lumen.type.small, color = c.textDim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        item {
            Action("Free up space", "Clears the song cache, artwork cache, unused Studio files and leftovers. Downloads and recordings stay.") {
                scope.launch {
                    val freed = StorageGuard.maintain(context, aggressive = true)
                    StorageGuard.clearImages(context)
                    usage = StorageGuard.usage(context)
                    app.toast(if (freed > 1024 * 1024) "Freed ${freed / (1024 * 1024)} MB" else "All tidy already")
                }
            }
        }

        item { Group("Backup") }
        item {
            Text(
                "Lumen has no account, so your library lives on this phone. Keep a backup file somewhere safe (Google Drive, " +
                    "or send it to yourself on WhatsApp) and restore it after a reset or on a new phone. Single playlists " +
                    "can also be shared as a code from the playlist's share button.",
                style = Lumen.type.small, color = c.textDim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        item {
            Action("Back up library to a file", "Liked songs, playlists, saved albums and artists, and settings") {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                runCatching { backupLauncher.launch("Lumen backup $date.json") }.onFailure { app.toast("No app available to save files") }
            }
        }
        item {
            Action("Restore from a backup file", "Adds everything from the backup to your library. Nothing is deleted.") {
                runCatching { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    .onFailure { app.toast("No app available to open files") }
            }
        }

        item { Group("Privacy") }
        item { Toggle("Save listening history", "Used for Jump back in and recommendations", settings.saveHistory) { v -> Graph.store.updateSettings { it.copy(saveHistory = v) } } }
        item { Action("Clear search history", "Remove your recent searches") { Graph.store.clearSearches(); app.toast("Search history cleared") } }
        item { Action("Clear listening history", "Remove recently played songs") { Graph.store.clearHistory(); app.toast("Listening history cleared") } }

        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LumenLogo(Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Lumen", style = Lumen.type.title, color = c.text)
                Text("Version $version · No ads · No account · No tracking", style = Lumen.type.small, color = c.textDim)
                Spacer(Modifier.height(16.dp))
                Text("Made by", style = Lumen.type.micro, color = c.textFaint)
                Spacer(Modifier.height(2.dp))
                Text("Abhiraj Aryan", style = Lumen.type.heading.copy(fontFamily = Serif), color = c.text)
                Spacer(Modifier.height(16.dp))
                // The GPL's "appropriate legal notices": who owns it, the license, and where the code is.
                Text("© 2026 Nirvana Team · Free software under GPL-3.0", style = Lumen.type.small, color = c.textDim)
                Text(
                    "Source code on GitHub",
                    style = Lumen.type.label, color = c.text,
                    modifier = Modifier.pressable({
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/viroaryan/lumen-code")),
                            )
                        }.onFailure { app.toast("github.com/viroaryan/lumen-code") }
                    }).padding(8.dp),
                )
            }
        }
    }
}

/** What Lumen keeps on the phone, as one bar with a legend. */
@Composable
private fun StorageBar(usage: StorageGuard.Usage?) {
    val c = Lumen.colors
    val mb = { b: Long -> if (b >= 1024L * 1024 * 1024) "%.1f GB".format(b / (1024.0 * 1024 * 1024)) else "${b / (1024 * 1024)} MB" }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (usage == null) {
            Text("Measuring…", style = Lumen.type.small, color = c.textDim)
            return@Column
        }
        val parts = listOf(
            Triple("Downloads", usage.downloads, c.text),
            Triple("Song cache", usage.songCache, c.textDim),
            Triple("Studio", usage.studio, c.textFaint),
            Triple("Artwork", usage.images, c.line),
        )
        val total = parts.sumOf { it.second }.coerceAtLeast(1)
        Text("Lumen uses ${mb(parts.sumOf { it.second })} · ${mb(usage.free)} free on phone", style = Lumen.type.bodyStrong, color = c.text)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surface)) {
            parts.forEach { (_, bytes, color) ->
                val w = bytes.toFloat() / total
                if (w > 0.005f) Box(Modifier.weight(w).fillMaxHeight().background(color))
            }
        }
        Spacer(Modifier.height(10.dp))
        parts.forEach { (label, bytes, color) ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(8.dp))
                Text(label, style = Lumen.type.small, color = c.textDim, modifier = Modifier.weight(1f))
                Text(mb(bytes), style = Lumen.type.small, color = c.text)
            }
        }
    }
}

@Composable
private fun Group(title: String) =
    Text(title.uppercase(), style = Lumen.type.micro, color = Lumen.colors.textDim, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp))

@Composable
private fun Label(text: String) =
    Text(text, style = Lumen.type.body, color = Lumen.colors.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp))

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val c = Lumen.colors
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).background(if (on) c.text else c.surface).pressable({ onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Lumen.type.label, color = if (on) c.bg else c.textDim)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lumen.colors
    Row(Modifier.fillMaxWidth().pressable({ onChange(!checked) }).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.body, color = c.text)
            Text(subtitle, style = Lumen.type.small, color = c.textDim)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.bg, checkedTrackColor = c.text, uncheckedThumbColor = c.textDim,
                uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.line, checkedBorderColor = c.text,
            ),
        )
    }
}

@Composable
private fun Action(title: String, subtitle: String, onClick: () -> Unit) {
    val c = Lumen.colors
    Column(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = Lumen.type.body, color = c.text)
        Text(subtitle, style = Lumen.type.small, color = c.textDim)
    }
}
