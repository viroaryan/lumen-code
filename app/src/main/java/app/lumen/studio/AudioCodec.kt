// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.studio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio file Android can read into raw 16-bit stereo PCM. Returns the sample rate. */
fun decodeToPcm(input: File, output: File, onProgress: (Float) -> Unit = {}): Int {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    // Native decoders are a limited, shared resource: they're released on every path, including failures.
    try {
        extractor.setDataSource(input.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw java.io.IOException("No audio track in ${input.name}")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = c
        return decodeWith(extractor, c, format, output, onProgress)
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun decodeWith(extractor: MediaExtractor, codec: MediaCodec, format: MediaFormat, output: File, onProgress: (Float) -> Unit): Int {
    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    codec.configure(format, null, null, 0)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    val tmp = ByteArray(16384 * 4)

    BufferedOutputStream(FileOutputStream(output), 1 shl 16).use { out ->
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (channels == 2) {
                        // Already the format we store: copy straight through.
                        while (buffer.hasRemaining()) {
                            val n = minOf(tmp.size, buffer.remaining())
                            buffer.get(tmp, 0, n)
                            out.write(tmp, 0, n)
                        }
                    } else {
                        val shorts = buffer.asShortBuffer()
                        val frames = shorts.remaining() / channels
                        var n = 0
                        for (f in 0 until frames) {
                            val s = shorts.get(f * channels).toInt()
                            val lo = s.toByte(); val hi = (s shr 8).toByte()
                            tmp[n++] = lo; tmp[n++] = hi; tmp[n++] = lo; tmp[n++] = hi
                            if (n >= tmp.size) { out.write(tmp, 0, n); n = 0 }
                        }
                        if (n > 0) out.write(tmp, 0, n)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    }
    onProgress(1f)
    return sampleRate
}

/** Encodes interleaved 16-bit stereo PCM, pulled block by block from [next], into an AAC .m4a file. */
fun encodeAac(output: File, sampleRate: Int, totalFrames: Long, next: (ShortArray) -> Int, onProgress: (Float) -> Unit) {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * 4)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    var muxer: MediaMuxer? = null
    var started = false
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        encodeWith(codec, m, sampleRate, totalFrames, next, onProgress) { started = true }
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        // stop() on a muxer that never started throws; only a started one has a file to finish.
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
}

private fun encodeWith(
    codec: MediaCodec, muxer: MediaMuxer, sampleRate: Int, totalFrames: Long,
    next: (ShortArray) -> Int, onProgress: (Float) -> Unit, onStarted: () -> Unit,
) {
    var track = -1
    val info = MediaCodec.BufferInfo()
    // 1024 frames (4 KB) per input buffer: comfortably below every AAC encoder's input size.
    val block = ShortArray(1024 * 2)
    var framesIn = 0L
    var inputDone = false
    var done = false
    while (!done) {
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                val frames = next(block)
                if (frames <= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, framesIn * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    buffer.asShortBuffer().put(block, 0, frames * 2)
                    codec.queueInputBuffer(inIndex, 0, frames * 4, framesIn * 1_000_000 / sampleRate, 0)
                    framesIn += frames
                    onProgress((framesIn.toFloat() / totalFrames).coerceIn(0f, 1f))
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
        when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                onStarted()
            }
            outIndex >= 0 -> {
                val data: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) {
                    data.position(info.offset); data.limit(info.offset + info.size)
                    muxer.writeSampleData(track, data, info)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
    }
    if (track < 0) throw java.io.IOException("Encoder produced no audio")
}
