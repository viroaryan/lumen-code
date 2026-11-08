// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.studio

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Every knob in the studio. Values are in musician units (dB, ms, %), and the whole set is saved with
 * a take, so reopening a recording brings back its sound.
 */
@Serializable
data class MixParams(
    // Levels
    val voiceDb: Float = 0f,
    val musicDb: Float = -4f,
    val pan: Float = 0f,                // -1 left .. 1 right
    val syncMs: Float = 0f,             // shifts the voice earlier (+) or later (-) against the music
    // Clean-up
    val gateDb: Float = -55f,           // below this the voice is muted (-80 = off)
    val lowCutHz: Float = 90f,
    // Tone
    val lowDb: Float = 0f,              // body, 150 Hz shelf
    val presenceDb: Float = 2f,         // clarity, 3 kHz bell
    val airDb: Float = 2f,              // sparkle, 10 kHz shelf
    // Dynamics
    val compress: Float = 0.45f,        // 0..1 amount: lower threshold, higher ratio
    val deEss: Float = 0.3f,            // 0..1 how hard harsh "s" sounds are tamed
    // Colour
    val warmth: Float = 0.15f,          // 0..1 tape-style saturation
    // Space
    val echoMs: Float = 280f,
    val echoFeedback: Float = 0.3f,
    val echoMix: Float = 0.08f,
    val reverbSize: Float = 0.55f,
    val reverbMix: Float = 0.18f,
    // Music
    val duck: Float = 0.2f,             // how much the music dips while you sing
)

/** Starting points, each a full set of knob positions. */
val Presets: List<Pair<String, MixParams>> = listOf(
    "Natural" to MixParams(),
    "Studio pop" to MixParams(presenceDb = 4f, airDb = 4f, compress = 0.65f, deEss = 0.45f, reverbMix = 0.16f, echoMix = 0.1f, warmth = 0.2f),
    "Warm ballad" to MixParams(lowDb = 2.5f, presenceDb = 1f, airDb = 1f, compress = 0.5f, warmth = 0.35f, reverbSize = 0.75f, reverbMix = 0.28f, echoMix = 0.06f),
    "Big hall" to MixParams(reverbSize = 0.92f, reverbMix = 0.38f, echoMix = 0.12f, echoMs = 360f, compress = 0.5f),
    "Radio voice" to MixParams(lowCutHz = 180f, lowDb = -2f, presenceDb = 6f, airDb = 0f, compress = 0.85f, warmth = 0.45f, reverbMix = 0.04f, echoMix = 0f),
    "Lo-fi" to MixParams(lowCutHz = 250f, airDb = -6f, presenceDb = 1f, warmth = 0.7f, compress = 0.6f, reverbMix = 0.2f, echoMix = 0.15f, echoMs = 420f),
    "Dry" to MixParams(reverbMix = 0f, echoMix = 0f, warmth = 0f, compress = 0.3f, presenceDb = 0f, airDb = 0f),
)

fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
fun gainToDb(g: Float): Float = if (g <= 1e-9f) -120f else 20f * log10(g)

/** RBJ cookbook biquad. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun highPass(sr: Int, f: Float, q: Double = 0.707) {
        val w = 2 * PI * f / sr; val c = cos(w); val a = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a)
    }

    fun lowShelf(sr: Int, f: Float, db: Float) {
        val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val s = sin(w)
        val alpha = s / 2 * sqrt(2.0); val sa = 2 * sqrt(A) * alpha
        set(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
            (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa)
    }

    fun highShelf(sr: Int, f: Float, db: Float) {
        val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val s = sin(w)
        val alpha = s / 2 * sqrt(2.0); val sa = 2 * sqrt(A) * alpha
        set(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
            (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa)
    }

    fun peak(sr: Int, f: Float, db: Float, q: Double = 0.9) {
        val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val a = sin(w) / (2 * q)
        set(1 + a * A, -2 * c, 1 - a * A, 1 + a / A, -2 * c, 1 - a / A)
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** One-pole envelope follower with separate attack and release. */
class Envelope(sr: Int, attackMs: Float, releaseMs: Float) {
    private val att = exp(-1.0 / (sr * attackMs / 1000.0)).toFloat()
    private val rel = exp(-1.0 / (sr * releaseMs / 1000.0)).toFloat()
    var value = 0f
        private set

    fun process(x: Float): Float {
        val a = abs(x)
        value = if (a > value) att * value + (1 - att) * a else rel * value + (1 - rel) * a
        return value
    }
}

/** Freeverb-style plate: parallel damped combs into series all-passes, decorrelated per side. */
class Reverb(sr: Int) {
    private class Comb(size: Int) {
        val buf = FloatArray(size); var i = 0; var store = 0f
        fun process(x: Float, feedback: Float, damp: Float): Float {
            val out = buf[i]
            store = out * (1 - damp) + store * damp
            buf[i] = x + store * feedback
            if (++i >= buf.size) i = 0
            return out
        }
    }

    private class AllPass(size: Int) {
        val buf = FloatArray(size); var i = 0
        fun process(x: Float): Float {
            val b = buf[i]; val out = -x + b
            buf[i] = x + b * 0.5f
            if (++i >= buf.size) i = 0
            return out
        }
    }

    private val scale = sr / 44100f
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allTunings = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private val combsL = combTunings.map { Comb((it * scale).toInt()) }
    private val combsR = combTunings.map { Comb(((it + spread) * scale).toInt()) }
    private val allL = allTunings.map { AllPass((it * scale).toInt()) }
    private val allR = allTunings.map { AllPass(((it + spread) * scale).toInt()) }

    /** Writes the wet stereo signal into [out] (left, right). */
    fun process(x: Float, size: Float, out: FloatArray) {
        val feedback = 0.7f + size * 0.28f
        val damp = 0.2f + (1 - size) * 0.3f
        val input = x * 0.015f
        var l = 0f; var r = 0f
        for (c in combsL) l += c.process(input, feedback, damp)
        for (c in combsR) r += c.process(input, feedback, damp)
        for (a in allL) l = a.process(l)
        for (a in allR) r = a.process(r)
        out[0] = l; out[1] = r
    }
}

/**
 * The vocal chain, in studio order: low cut, gate, tone EQ, de-esser, compressor, warmth, then echo and
 * reverb sends. Mono voice in, stereo out. Parameters can change between blocks while audio plays.
 */
class VocalChain(private val sr: Int) {
    private val hp = Biquad()
    private val low = Biquad()
    private val presence = Biquad()
    private val air = Biquad()
    private val sibilance = Biquad()
    private val gateEnv = Envelope(sr, 1f, 120f)
    private var gateGain = 0f
    private val compEnv = Envelope(sr, 5f, 90f)
    private val essEnv = Envelope(sr, 1f, 60f)
    private val echo = FloatArray(sr * 2)
    private var echoPos = 0
    private val reverb = Reverb(sr)
    private val wet = FloatArray(2)
    private var applied: MixParams? = null

    // Derived per-parameter-set values.
    private var gateThresh = 0f
    private var compThreshDb = 0f
    private var compRatio = 1f
    private var makeup = 1f
    private var voiceGain = 1f
    private var panL = 1f
    private var panR = 1f
    private var echoSamples = 1

    private fun configure(p: MixParams) {
        if (p == applied) return
        applied = p
        hp.highPass(sr, p.lowCutHz.coerceIn(20f, 400f))
        low.lowShelf(sr, 150f, p.lowDb)
        presence.peak(sr, 3000f, p.presenceDb)
        air.highShelf(sr, 10000f, p.airDb)
        sibilance.highPass(sr, 5500f)
        gateThresh = if (p.gateDb <= -79f) 0f else dbToGain(p.gateDb)
        compThreshDb = -6f - p.compress * 22f
        compRatio = 1f + p.compress * 5f
        makeup = dbToGain(-compThreshDb * (1 - 1 / compRatio) * 0.5f)
        voiceGain = dbToGain(p.voiceDb)
        val angle = (p.pan.coerceIn(-1f, 1f) + 1) * (PI / 4)
        panL = (cos(angle) * sqrt(2.0)).toFloat()
        panR = (sin(angle) * sqrt(2.0)).toFloat()
        echoSamples = (p.echoMs / 1000f * sr).toInt().coerceIn(1, echo.size - 1)
    }

    /** Processes one block of mono voice into interleaved stereo [outL]/[outR] (added to existing). */
    fun process(input: FloatArray, n: Int, p: MixParams, outL: FloatArray, outR: FloatArray) {
        configure(p)
        val gateRelease = exp(-1.0 / (sr * 0.08)).toFloat()
        for (i in 0 until n) {
            var x = hp.process(input[i])

            // Gate: fade out breaths and room noise between phrases.
            if (gateThresh > 0f) {
                val target = if (gateEnv.process(x) > gateThresh) 1f else 0f
                gateGain = if (target > gateGain) target else gateGain * gateRelease
                x *= gateGain
            }

            x = air.process(presence.process(low.process(x)))

            // De-esser: when the sibilant band is loud, pull it back.
            if (p.deEss > 0f) {
                val s = sibilance.process(x)
                val level = essEnv.process(s)
                val limit = 0.08f * (1.2f - p.deEss)
                if (level > limit) x -= s * (1 - limit / level) * p.deEss
            }

            // Compressor with soft makeup.
            if (p.compress > 0f) {
                val levelDb = gainToDb(compEnv.process(x))
                if (levelDb > compThreshDb) {
                    val reduce = (levelDb - compThreshDb) * (1 - 1 / compRatio)
                    x *= dbToGain(-reduce)
                }
                x *= makeup
            }

            // Warmth: gentle tape-like saturation, blended.
            if (p.warmth > 0f) {
                val drive = 1f + p.warmth * 4f
                x = x * (1 - p.warmth) + (tanh((x * drive).toDouble()).toFloat() / drive * 1.4f) * p.warmth
            }

            x *= voiceGain

            // Echo send.
            var echoOut = 0f
            if (p.echoMix > 0f) {
                val read = (echoPos - echoSamples + echo.size) % echo.size
                echoOut = echo[read]
                echo[echoPos] = x + echoOut * p.echoFeedback.coerceIn(0f, 0.85f)
                echoPos = (echoPos + 1) % echo.size
            }

            // Reverb send.
            var rl = 0f; var rr = 0f
            if (p.reverbMix > 0f) {
                reverb.process(x, p.reverbSize, wet)
                rl = wet[0] * p.reverbMix * 3f; rr = wet[1] * p.reverbMix * 3f
            }

            val dryL = x * panL; val dryR = x * panR
            outL[i] += dryL + echoOut * p.echoMix * 1.5f + rl
            outR[i] += dryR + echoOut * p.echoMix * 1.5f + rr
        }
    }
}

/** Master stage: music level with ducking under the voice, then a brickwall-style peak limiter. */
class MasterBus(sr: Int) {
    private val voiceEnv = Envelope(sr, 10f, 250f)
    private var limiterGain = 1f
    private val limiterRelease = exp(-1.0 / (sr * 0.08)).toFloat()

    fun duckGain(voiceSample: Float, p: MixParams): Float {
        if (p.duck <= 0f) return 1f
        val env = voiceEnv.process(voiceSample)
        val amount = (env * 8f).coerceAtMost(1f) * p.duck * 0.6f
        return 1f - amount
    }

    /** Limits a stereo frame to just under full scale, in place. */
    fun limit(frame: FloatArray) {
        val peak = max(abs(frame[0]), abs(frame[1]))
        val needed = if (peak > 0.97f) 0.97f / peak else 1f
        // Clamp instantly, recover smoothly, never above what this frame allows.
        limiterGain = minOf(needed, limiterGain + (1 - limiterGain) * (1 - limiterRelease))
        frame[0] *= limiterGain; frame[1] *= limiterGain
    }
}
