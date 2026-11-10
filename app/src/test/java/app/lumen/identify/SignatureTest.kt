// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SignatureTest {

    @Test
    fun everyLanguageRowIsComplete() = assertTrue(app.lumen.ui.Lang.complete())

    @Test
    fun readsWordSyncedLyrics() {
        val yaml = """version: '1.0'
metadata:
  title: X
lines:
- text: I've been
  start_ms: 1000
  end_ms: 2000
  words:
  - text: 'I''ve '
    start_ms: 1000
  - text: been
    start_ms: 1500
- text: Yeah
  start_ms: 3000
  end_ms: 3500
  words:
  - text: Yeah
    start_ms: 3000
"""
        val lines = app.lumen.data.source.LyricsSource.parseLyricsFile(yaml)
        assertEquals(2, lines.size)
        assertEquals("I've ", lines[0].words[0].text)
        assertEquals(1500L, lines[0].words[1].timeMs)
        assertEquals("हिन्दी", app.lumen.data.source.LyricsSource.script("केसरिया तेरा इश्क़ है पिया"))
    }

    @Test
    fun fftOfASineHasOnePeak() {
        val re = FloatArray(2048) { kotlin.math.sin(2 * Math.PI * 64 * it / 2048).toFloat() }
        val im = FloatArray(2048)
        Fft.transform(re, im)
        val mags = (0..1024).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun encodesAWellFormedSignature() {
        // A chirp: plenty of peaks across the bands.
        val pcm = ShortArray(16000 * 6) { i ->
            val t = i / 16000.0
            (12000 * kotlin.math.sin(2 * Math.PI * (300 + 400 * t) * t)).toInt().toShort()
        }
        val gen = SignatureGenerator().apply { feed(pcm) }
        assertTrue("found peaks", gen.peakCount > 0)
        val (uri, ms) = gen.encode()
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals(6000L, ms)
        val bytes = java.util.Base64.getDecoder().decode(uri.substringAfter(","))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), b.getInt(0))
        assertEquals(bytes.size - 48, b.getInt(8))
        assertEquals(0x94119c00.toInt(), b.getInt(12))
        val crc = java.util.zip.CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        assertEquals(crc, b.getInt(4))
    }

    /**
     * End to end against the real service, with a recording given in LUMEN_TEST_WAV (16 kHz mono
     * 16-bit WAV). Skipped when the variable isn't set, so normal builds never touch the network.
     */
    @Test
    fun recognisesARealSong() {
        val path = System.getenv("LUMEN_TEST_WAV")
        assumeTrue(path != null && File(path).exists())
        val data = File(path!!).readBytes()
        val pcmBytes = data.copyOfRange(44, data.size)
        val pcm = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val end = minOf(pcm.size, 16000 * 12)
        val (uri, ms) = SignatureGenerator.of(pcm, 0, end)
        val song = Shazam.recognize(uri, ms)
        println("RESULT: $song")
        assertTrue("recognised", song != null)
    }
}
