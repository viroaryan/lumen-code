// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Crash-safe file writes for everything the user can't get back (library, taste, recordings list). */
object SafeFile {
    /**
     * Writes through a temp file that is flushed to the disk (fsync) before it replaces the real one, so
     * a power cut or a killed process leaves either the old file or the new one, never a half-written file.
     */
    fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't replace ${target.name}")
        }
    }
}
