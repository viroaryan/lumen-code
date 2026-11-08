// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.studio

import android.content.Context
import app.lumen.data.Track
import app.lumen.data.source.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** How lyrics sit on the recording screen. */
@Serializable
data class LyricsLayout(val offsetMs: Long = 0, val scale: Float = 1f, val centered: Boolean = true)

/** One recording: the backing track it was sung over, the raw voice, and the mix settings. */
@Serializable
data class Take(
    val id: String,
    val track: Track,
    val sampleRate: Int,
    /** Voice frames to skip so the voice lines up with the music (measured from the audio hardware). */
    val alignFrames: Int,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val params: MixParams = MixParams(),
    val layout: LyricsLayout = LyricsLayout(),
    val exportedUri: String? = null,
)

/** Recordings on disk: filesDir/studio holds decoded backing tracks and each take's voice. */
class StudioStore(context: Context) {
    val root = File(context.filesDir, "studio").apply { mkdirs() }
    private val backingDir = File(root, "backing").apply { mkdirs() }
    private val takesDir = File(root, "takes").apply { mkdirs() }
    private val index = File(root, "takes.json")
    private val serializer = ListSerializer(Take.serializer())

    private val _takes = MutableStateFlow(load())
    val takes: StateFlow<List<Take>> = _takes

    private fun load(): List<Take> = runCatching { Http.json.decodeFromString(serializer, index.readText()) }.getOrDefault(emptyList())

    private fun save() = synchronized(this) {
        runCatching { app.lumen.data.SafeFile.write(index, Http.json.encodeToString(serializer, _takes.value)) }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun backingAudio(track: Track) = File(backingDir, safe(track.id) + ".audio")
    fun backingPcm(track: Track) = File(backingDir, safe(track.id) + ".pcm")
    fun backingRate(track: Track) = File(backingDir, safe(track.id) + ".rate")
    fun voicePcm(takeId: String) = File(takesDir, "$takeId.pcm")

    fun put(take: Take) {
        _takes.update { list -> listOf(take) + list.filterNot { it.id == take.id } }
        save()
    }

    fun update(id: String, block: (Take) -> Take) {
        _takes.update { list -> list.map { if (it.id == id) block(it) else it } }
        save()
    }

    fun get(id: String) = _takes.value.firstOrNull { it.id == id }

    fun delete(id: String) {
        voicePcm(id).delete()
        _takes.update { list -> list.filterNot { it.id == id } }
        save()
    }
}
