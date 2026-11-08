// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lumen.Graph

/** Handles the buttons shown in the picture-in-picture window. */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> Graph.player.toggle()
            ACTION_NEXT -> Graph.player.next()
            ACTION_PREVIOUS -> Graph.player.previous()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "app.lumen.pip.TOGGLE"
        const val ACTION_NEXT = "app.lumen.pip.NEXT"
        const val ACTION_PREVIOUS = "app.lumen.pip.PREVIOUS"
    }
}
