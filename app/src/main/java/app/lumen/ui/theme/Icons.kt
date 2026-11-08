// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Lumen's own icon set: soft 1.8px strokes with round caps, drawn on a 24px grid. */
object LIcons {
    private fun icon(name: String, stroke: List<String> = emptyList(), fill: List<String> = emptyList(), width: Float = 1.8f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroke.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            fill.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private const val HOME = "M4 10.4 12 4l8 6.4V19a1.5 1.5 0 0 1-1.5 1.5H15v-5.5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5.5H5.5A1.5 1.5 0 0 1 4 19z"
    private const val SEARCH = "M10.8 4.2a6.6 6.6 0 1 0 0 13.2 6.6 6.6 0 0 0 0-13.2z"
    private const val HEART = "M12 19.6s-7.6-4.5-7.6-10.1A4.2 4.2 0 0 1 12 7a4.2 4.2 0 0 1 7.6 2.5c0 5.6-7.6 10.1-7.6 10.1z"
    private const val PLAY = "M8.2 5.3v13.4a1 1 0 0 0 1.5.86l10.4-6.7a1 1 0 0 0 0-1.72L9.7 4.44a1 1 0 0 0-1.5.86z"
    private const val PAUSE =
        "M7.6 4.6h1.9a1.1 1.1 0 0 1 1.1 1.1v12.6a1.1 1.1 0 0 1-1.1 1.1H7.6a1.1 1.1 0 0 1-1.1-1.1V5.7a1.1 1.1 0 0 1 1.1-1.1zM14.5 4.6h1.9a1.1 1.1 0 0 1 1.1 1.1v12.6a1.1 1.1 0 0 1-1.1 1.1h-1.9a1.1 1.1 0 0 1-1.1-1.1V5.7a1.1 1.1 0 0 1 1.1-1.1z"
    private const val CIRCLE = "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z"

    val Home = icon("home", stroke = listOf(HOME))
    val HomeFilled = icon("home_filled", stroke = listOf(HOME), fill = listOf(HOME))
    val Search = icon("search", stroke = listOf(SEARCH, "M15.6 15.6 20 20"))
    val SearchBold = icon("search_bold", stroke = listOf(SEARCH, "M15.6 15.6 20 20"), width = 2.4f)
    val Library = icon("library", stroke = listOf("M5 4.5v15", "M9.5 4.5v15", "M14 5.2l5 14.3"))
    val LibraryBold = icon("library_bold", stroke = listOf("M5 4.5v15", "M9.5 4.5v15", "M14 5.2l5 14.3"), width = 2.4f)
    val Play = icon("play", fill = listOf(PLAY))
    val Pause = icon("pause", fill = listOf(PAUSE))
    val Next = icon("next", stroke = listOf("M18 5.5v13"), fill = listOf("M5.6 6.3v11.4a.9.9 0 0 0 1.4.74l8.2-5.7a.9.9 0 0 0 0-1.48L7 5.56a.9.9 0 0 0-1.4.74z"), width = 2.2f)
    val Previous = icon("previous", stroke = listOf("M6 5.5v13"), fill = listOf("M18.4 6.3v11.4a.9.9 0 0 1-1.4.74l-8.2-5.7a.9.9 0 0 1 0-1.48l8.2-5.7a.9.9 0 0 1 1.4.74z"), width = 2.2f)
    val Shuffle = icon(
        "shuffle",
        stroke = listOf(
            "M3.8 7h2.4c4.2 0 7.2 10 11.4 10h2.2", "M17.8 14.5 20.3 17l-2.5 2.5",
            "M3.8 17h2.4c1.5 0 2.7-1.4 3.8-3.2", "M13.6 10c1.1-1.7 2.4-3 4-3h2.2", "M17.8 4.5 20.3 7l-2.5 2.5",
        ),
    )
    private val repeat = listOf("M4.2 11.2V9.5A2.5 2.5 0 0 1 6.7 7h12.6", "M16.8 4.5 19.3 7l-2.5 2.5", "M19.8 12.8v1.7a2.5 2.5 0 0 1-2.5 2.5H4.7", "M7.2 19.5 4.7 17l2.5-2.5")
    val Repeat = icon("repeat", stroke = repeat)
    val RepeatOne = icon("repeat_one", stroke = repeat + "M11.3 10.6 12.4 10v4.2")
    val Heart = icon("heart", stroke = listOf(HEART))
    val HeartFilled = icon("heart_filled", stroke = listOf(HEART), fill = listOf(HEART))
    val Plus = icon("plus", stroke = listOf("M12 5v14", "M5 12h14"))
    val PlusCircle = icon("plus_circle", stroke = listOf(CIRCLE, "M12 8.5v7", "M8.5 12h7"))
    val Check = icon("check", stroke = listOf("M5 12.5l4.5 4.5L19 7.5"))
    val CheckCircle = icon("check_circle", stroke = listOf(CIRCLE, "M8.5 12.2l2.4 2.3 4.6-4.8"), width = 2.2f)
    val More = icon("more", fill = listOf("M4.5 12a1.6 1.6 0 1 0 3.2 0 1.6 1.6 0 1 0-3.2 0z", "M10.4 12a1.6 1.6 0 1 0 3.2 0 1.6 1.6 0 1 0-3.2 0z", "M16.3 12a1.6 1.6 0 1 0 3.2 0 1.6 1.6 0 1 0-3.2 0z"))
    val Queue = icon("queue", stroke = listOf("M4 6.5h16", "M4 11.5h16", "M4 16.5h8.5"), fill = listOf("M15.5 14.2v5.6a.6.6 0 0 0 .9.5l4.3-2.8a.6.6 0 0 0 0-1L16.4 13.7a.6.6 0 0 0-.9.5z"))
    val AddToQueue = icon("add_queue", stroke = listOf("M4 6.5h12", "M4 11.5h12", "M4 16.5h7", "M17.5 13.5v7", "M14 17h7"))
    val PlayNext = icon("play_next", stroke = listOf("M4 6.5h9", "M4 11.5h9", "M4 16.5h16"), fill = listOf("M16 4.2v5.6a.6.6 0 0 0 .9.5l4.3-2.8a.6.6 0 0 0 0-1L16.9 3.7a.6.6 0 0 0-.9.5z"))
    val Lyrics = icon("lyrics", stroke = listOf("M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17h-7.5l-4.5 3.4V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5z", "M7.5 9.6h9", "M7.5 13h5.5"))
    val Download = icon("download", stroke = listOf(CIRCLE, "M12 7.8v8", "M8.8 12.8 12 16l3.2-3.2"))
    val Downloaded = icon("downloaded", stroke = listOf(CIRCLE, "M8.5 12.2l2.4 2.3 4.6-4.8"), width = 2.2f)
    val Moon = icon("moon", stroke = listOf("M19.6 14.6A8 8 0 0 1 9.4 4.4a8 8 0 1 0 10.2 10.2z"))
    val Share = icon("share", stroke = listOf("M12 14V4", "M8.5 7.5 12 4l3.5 3.5", "M7.5 10.5h-1A1.5 1.5 0 0 0 5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5h-1"))
    val ChevronDown = icon("chevron_down", stroke = listOf("M6 9.5l6 6 6-6"), width = 2f)
    val ChevronRight = icon("chevron_right", stroke = listOf("M9.5 6l6 6-6 6"), width = 2f)
    val Back = icon("back", stroke = listOf("M14.5 5.5 8 12l6.5 6.5"), width = 2f)
    val Close = icon("close", stroke = listOf("M6.5 6.5l11 11", "M17.5 6.5l-11 11"))
    val Settings = icon("settings", stroke = listOf("M4 7.5h8.5", "M16.5 7.5H20", "M4 16.5h3.5", "M11.5 16.5H20", "M14.5 5v5", "M9.5 14v5"))
    val Trash = icon("trash", stroke = listOf("M5 7h14", "M9.5 7V5.5A1.5 1.5 0 0 1 11 4h2a1.5 1.5 0 0 1 1.5 1.5V7", "M7 7l.8 11.6A1.5 1.5 0 0 0 9.3 20h5.4a1.5 1.5 0 0 0 1.5-1.4L17 7"))
    val Edit = icon("edit", stroke = listOf("M14.2 5.8l4 4", "M4.5 19.5l1-4.3L15.6 5.1a1.4 1.4 0 0 1 2 0l1.3 1.3a1.4 1.4 0 0 1 0 2L8.8 18.5z"))
    val Pip = icon("pip", stroke = listOf("M3.5 6.5A1.5 1.5 0 0 1 5 5h14a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"), fill = listOf("M12.5 12h5.2a.8.8 0 0 1 .8.8v3.4a.8.8 0 0 1-.8.8h-5.2a.8.8 0 0 1-.8-.8v-3.4a.8.8 0 0 1 .8-.8z"))
    val Note = icon("note", stroke = listOf("M9 17.5V6.6l10-2.1v10.6", "M9 17.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z", "M19 15.1a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"))
    val Artist = icon("artist", stroke = listOf("M12 4a3.7 3.7 0 1 0 0 7.4A3.7 3.7 0 0 0 12 4z", "M5 20c.6-3.6 3.4-5.8 7-5.8s6.4 2.2 7 5.8"))
    val Album = icon("album", stroke = listOf(CIRCLE, "M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"))
    val History = icon("history", stroke = listOf(CIRCLE, "M12 7.5V12l3 2"))
    val Phone = icon("phone", stroke = listOf("M8 3.5h8A1.5 1.5 0 0 1 17.5 5v14a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z", "M11 17.5h2"))
    val Speed = icon("speed", stroke = listOf("M4.5 16.5a7.5 7.5 0 1 1 15 0", "M12 16.5l3.4-4.6"))
    val Equalizer = icon("equalizer", stroke = listOf("M6 20v-6", "M6 10V4", "M12 20v-9", "M12 7V4", "M18 20v-4", "M18 12V4", "M4 14h4", "M10 7h4", "M16 16h4"))
    val Radio = icon("radio", stroke = listOf("M5.6 18.4a9 9 0 0 1 0-12.8", "M18.4 5.6a9 9 0 0 1 0 12.8", "M8.5 15.5a5 5 0 0 1 0-7", "M15.5 8.5a5 5 0 0 1 0 7"), fill = listOf("M12 10.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z"))
    val Playlist = icon("playlist", stroke = listOf("M4 6.5h11", "M4 11.5h11", "M4 16.5h6", "M19 6v8.5", "M19 14.5a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 0 1 4.4 0z"))
    val Sun = icon("sun", stroke = listOf("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", "M12 3v1.5", "M12 19.5V21", "M3 12h1.5", "M19.5 12H21", "M5.6 5.6l1.1 1.1", "M17.3 17.3l1.1 1.1", "M5.6 18.4l1.1-1.1", "M17.3 6.7l1.1-1.1"))
    val Up = icon("up", stroke = listOf("M12 19V5", "M6.5 10.5 12 5l5.5 5.5"))
    val DownArrow = icon("down", stroke = listOf("M12 5v14", "M6.5 13.5 12 19l5.5-5.5"))
    private const val MIC = "M12 3.5a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0v-5a3 3 0 0 0-3-3z"
    val Mic = icon("mic", stroke = listOf(MIC, "M6 11a6 6 0 0 0 12 0", "M12 17v3.5", "M9 20.5h6"))
    val MicBold = icon("mic_bold", stroke = listOf(MIC, "M6 11a6 6 0 0 0 12 0", "M12 17v3.5", "M9 20.5h6"), fill = listOf(MIC), width = 2.2f)
    val Record = icon("record", fill = listOf("M12 5a7 7 0 1 0 0 14 7 7 0 0 0 0-14z"))
    val Stop = icon("stop", fill = listOf("M7.5 6h9A1.5 1.5 0 0 1 18 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6z"))
    val Waveform = icon("waveform", stroke = listOf("M3.5 12h1", "M7 9v6", "M10.5 5v14", "M14 8v8", "M17.5 10.5v3", "M20.5 12h0"))
    val Sliders = icon("sliders", stroke = listOf("M6 4v16", "M12 4v16", "M18 4v16"), fill = listOf("M4 13.5h4v3H4z", "M10 7.5h4v3h-4z", "M16 11h4v3h-4z"))
    val Export = icon("export", stroke = listOf("M12 15V4", "M8 8l4-4 4 4", "M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13"))
    val Undo = icon("undo", stroke = listOf("M9 7 5 11l4 4", "M5 11h9a5 5 0 0 1 0 10h-2"))
    val TextSize = icon("text_size", stroke = listOf("M4 18 9 6l5 12", "M5.8 14h6.4", "M15 18l3-7 3 7", "M15.9 16h4.2"))
    val Headphones = icon("headphones", stroke = listOf("M4 15v-3a8 8 0 0 1 16 0v3", "M4 15a2 2 0 0 1 2-2h1v7H6a2 2 0 0 1-2-2z", "M20 15a2 2 0 0 0-2-2h-1v7h1a2 2 0 0 0 2-2z"))
    val Video = icon(
        "video",
        stroke = listOf("M4 6.5A1.5 1.5 0 0 1 5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z"),
        fill = listOf("M10 9.2v5.6a.6.6 0 0 0 .9.5l4.3-2.8a.6.6 0 0 0 0-1l-4.3-2.8a.6.6 0 0 0-.9.5z"),
    )
    val Link = icon("link", stroke = listOf("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"))
    val Bell = icon("bell", stroke = listOf("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", "M10 20.5h4"))
    val Storage = icon("storage", stroke = listOf("M4 7.5c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z", "M4 7.5v9c0 1.7 3.6 3 8 3s8-1.3 8-3v-9", "M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"))
    val Wifi = icon("offline", stroke = listOf("M3.5 9.5a12 12 0 0 1 17 0", "M6.5 12.8a7.7 7.7 0 0 1 11 0", "M9.5 16a3.4 3.4 0 0 1 5 0", "M4 4l16 16"))
}
