// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.lumen.engine.Guardian
import java.io.File

/**
 * Custom cover pictures for the user's lists (Liked Songs, Downloads, a playlist…). The picture the
 * user picks is copied, cropped to a square, into the app's own storage: the original can be deleted
 * from the gallery afterwards and the cover stays. Nothing is uploaded anywhere.
 */
object Covers {
    /** Side of the saved square, in pixels: sharp on any phone, about 100 KB on disk. */
    const val SIZE = 900
    private const val MAX_DECODE = 2400

    fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * Reads a picked picture, upright (camera photos store their rotation separately) and scaled
     * down to a size that's safe to hold in memory however big the original is. Null if unreadable.
     */
    fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val upright = if (rotation != 0f) rotate(raw, rotation) else raw
        val longest = maxOf(upright.width, upright.height)
        if (longest > MAX_DECODE) {
            val f = MAX_DECODE.toFloat() / longest
            Bitmap.createScaledBitmap(upright, (upright.width * f).toInt().coerceAtLeast(1), (upright.height * f).toInt().coerceAtLeast(1), true)
                .also { if (it !== upright) upright.recycle() }
        } else upright
    }.onFailure { Guardian.record("cover load", it) }.getOrNull()

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Saves the finished square and returns its path. A new file name each time, so images refresh at once. */
    fun save(context: Context, key: String, square: Bitmap): String? = runCatching {
        val safeKey = key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
        val file = File(dir(context), "$safeKey-${System.currentTimeMillis()}.jpg")
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        file.absolutePath
    }.onFailure { Guardian.record("cover save", it) }.getOrNull()

    /** Deletes a cover file, but only one inside the covers folder. */
    fun delete(context: Context, path: String?) {
        path ?: return
        runCatching {
            val f = File(path).canonicalFile
            if (f.parentFile == dir(context).canonicalFile) f.delete()
        }
    }

    /**
     * Housekeeping at startup: forgets covers of playlists that no longer exist or files that are
     * gone, and deletes picture files nothing points to.
     */
    fun prune(context: Context, store: LibraryStore) = runCatching {
        val state = store.current
        val playlistIds = state.playlists.map { it.id }.toHashSet()
        state.covers.forEach { (key, path) ->
            val orphan = key.startsWith("pl:") && key.removePrefix("pl:") !in playlistIds
            if (orphan || !File(path).exists()) store.removeCover(key)
        }
        val used = store.current.covers.values.map { File(it).name }.toHashSet()
        dir(context).listFiles()?.forEach { f ->
            // Leave files from the last few minutes alone: a cover may be saving right now.
            if (f.name !in used && System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) f.delete()
        }
    }
}
