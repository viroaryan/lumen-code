// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-FileCopyrightText: Marin Moulinier and SongRec contributors (algorithm)
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.identify


import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Turns a few seconds of sound into an audio fingerprint: the loudest peaks of the spectrum over
 * time, in four frequency bands. This is the format used by the open-source SongRec project
 * (GPL-3.0, github.com/marin-m/SongRec), ported to Kotlin. Only this small fingerprint ever leaves
 * the phone, never the recording itself.
 *
 * Input: 16 kHz mono 16-bit samples.
 */
class SignatureGenerator {
    private val samples = ShortArray(2048)
    private var samplePos = 0

    private val fftOut = Array(256) { FloatArray(1025) }
    private var fftPos = 0
    private val spreadOut = Array(256) { FloatArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0

    private var numberSamples = 0
    private val peaks = Array(4) { ArrayList<Peak>() }

    private val re = FloatArray(2048)
    private val im = FloatArray(2048)

    private class Peak(val fftPass: Int, val magnitude: Int, val bin: Int)

    val peakCount: Int get() = peaks.sumOf { it.size }

    /** Feeds samples; any length, processed in chunks of 128. */
    fun feed(pcm: ShortArray, from: Int = 0, to: Int = pcm.size) {
        var i = from
        while (i + 128 <= to) {
            doFft(pcm, i)
            doPeakSpreading()
            if (spreadWritten >= 46) doPeakRecognition()
            i += 128
            numberSamples += 128
        }
    }

    private fun doFft(pcm: ShortArray, offset: Int) {
        System.arraycopy(pcm, offset, samples, samplePos, 128)
        samplePos = (samplePos + 128) and 2047
        for (i in 0 until 2048) {
            re[i] = samples[(i + samplePos) and 2047] * HANNING[i]
            im[i] = 0f
        }
        Fft.transform(re, im)
        val out = fftOut[fftPos]
        for (i in 0..1024) {
            out[i] = max((re[i] * re[i] + im[i] * im[i]) / (1 shl 17).toFloat(), 1e-10f)
        }
        fftPos = (fftPos + 1) and 255
    }

    private fun doPeakSpreading() {
        val last = fftOut[(fftPos - 1) and 255]
        val spread = spreadOut[spreadPos]
        System.arraycopy(last, 0, spread, 0, 1025)
        for (p in 0 until 1025) {
            // Frequency-domain spreading of peak values.
            if (p < 1023) spread[p] = max(spread[p], max(spread[p + 1], spread[p + 2]))
            // Time-domain spreading into the earlier frames.
            var m = spread[p]
            for (former in FORMER) {
                val f = spreadOut[(spreadPos + former) and 255]
                m = max(f[p], m)
                f[p] = m
            }
        }
        spreadPos = (spreadPos + 1) and 255
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val f46 = fftOut[(fftPos - 46) and 255]
        val f49 = spreadOut[(spreadPos - 49) and 255]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1f / 64f || v < f49[bin - 1]) continue
            var maxNeighbor = 0f
            for (o in NEIGHBORS) maxNeighbor = max(f49[bin + o], maxNeighbor)
            if (v <= maxNeighbor) continue
            var maxOther = maxNeighbor
            for (o in OTHER_FRAMES) maxOther = max(spreadOut[(spreadPos + o) and 255][bin - 1], maxOther)
            if (v <= maxOther) continue

            val fftNumber = spreadWritten - 46
            val mag = magnitude(v)
            val before = magnitude(f46[bin - 1])
            val after = magnitude(f46[bin + 1])
            val variation1 = mag * 2 - before - after
            if (variation1 <= 0) continue
            val variation2 = (after - before) * 32 / variation1
            val corrected = (bin * 64 + variation2).toInt()
            val hz = corrected * (16000.0 / 2 / 1024 / 64)
            val band = when {
                hz > 250 && hz < 520 -> 0
                hz > 520 && hz < 1450 -> 1
                hz > 1450 && hz < 3500 -> 2
                hz > 3500 && hz <= 5500 -> 3
                else -> continue
            }
            peaks[band].add(Peak(fftNumber, mag.toInt(), corrected))
        }
    }

    private fun magnitude(x: Float): Double = ln(max(1.0 / 64, x.toDouble())) * 1477.3 + 6144

    /** The fingerprint as a data URI, and its length in milliseconds. */
    fun encode(): Pair<String, Long> {
        val contents = ByteArrayOutputStream()
        for ((band, list) in peaks.withIndex()) {
            if (list.isEmpty()) continue
            val buf = ByteArrayOutputStream()
            var pass = 0
            for (p in list) {
                if (p.fftPass - pass >= 255) {
                    buf.write(0xff)
                    buf.write(le32(p.fftPass))
                    pass = p.fftPass
                }
                buf.write(p.fftPass - pass)
                buf.write(le16(p.magnitude))
                buf.write(le16(p.bin))
                pass = p.fftPass
            }
            val bytes = buf.toByteArray()
            contents.write(le32(0x60030040 + band))
            contents.write(le32(bytes.size))
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val out = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xcafe2580.toInt())
        out.putInt(0) // crc32, filled below
        out.putInt(body.size + 8)
        out.putInt(0x94119c00.toInt())
        out.putInt(0); out.putInt(0); out.putInt(0)
        out.putInt(3 shl 27) // 16 kHz
        out.putInt(0); out.putInt(0)
        out.putInt((numberSamples + 16000 * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(body.size + 8)
        out.put(body)
        val all = out.array()
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value
        ByteBuffer.wrap(all, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        val uri = "data:audio/vnd.shazam.sig;base64," + java.util.Base64.getEncoder().encodeToString(all)
        return uri to (numberSamples * 1000L / 16000)
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    companion object {
        private val FORMER = intArrayOf(-1, -3, -6)
        private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_FRAMES = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
        private val HANNING = FloatArray(2048) { i -> (0.5 - 0.5 * cos(2 * PI * (i + 1) / 2049)).toFloat() }

        /** Fingerprint of a whole recording (16 kHz mono). */
        fun of(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Pair<String, Long> =
            SignatureGenerator().apply { feed(pcm, from, to) }.encode()
    }
}

/** In-place radix-2 FFT for 2048 points. */
internal object Fft {
    private const val N = 2048
    private val cosT = FloatArray(N / 2) { cos(-2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(-2 * PI * it / N).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = 11
        for (i in 0 until N) {
            var x = i; var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
