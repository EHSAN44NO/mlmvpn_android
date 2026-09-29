package com.mlmvpn.scanner.ui.arena

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The arena's sounds: short, soft tones synthesised on the spot -- no audio files shipped -- in
 * the spirit of the system's own ticks and chimes, at a low volume, on the media stream so the
 * phone's own volume and silent handling apply. [enabled] is the user's switch, remembered.
 */
object ArenaSounds {

    /**
     * LIGHT and START are the start-line beeps of a motor race: one flat, electronic beep per red
     * light, and a higher, long one when they go out -- the timing-system sound, not a chime.
     */
    enum class Cue { TICK, LIGHT, START, GO, OUT, FINISH, WIN }

    private const val RATE = 44_100
    private const val PREFS = "arena_prefs"
    private const val KEY = "sounds_on"
    private val cache = HashMap<Cue, ShortArray>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    fun play(context: Context, cue: Cue) {
        if (!enabled(context)) return
        val pcm = synchronized(cache) { cache.getOrPut(cue) { synth(cue) } }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.35f)
            track.play()
            // Released once played; a static track holds its buffer until then.
            Thread({ Thread.sleep(pcm.size * 1000L / RATE + 120); runCatching { track.release() } }, "arena-sound").apply { isDaemon = true }.start()
        }
    }

    /** Notes as (frequency, start ms, length ms), each with a soft attack and an exponential fade. */
    private fun synth(cue: Cue): ShortArray {
        if (cue == Cue.LIGHT) return beep(660.0, 230)       // E5, one per light
        if (cue == Cue.START) return beep(1320.0, 750)      // E6, an octave up and three times as long
        val notes: List<Triple<Double, Int, Int>> = when (cue) {
            Cue.TICK -> listOf(Triple(1318.5, 0, 45))                                   // E6, a light tick
            Cue.GO -> listOf(Triple(784.0, 0, 160), Triple(1174.7, 70, 260))            // G5 → D6
            Cue.OUT -> listOf(Triple(392.0, 0, 140), Triple(311.1, 90, 200))            // G4 → Eb4, soft and low
            Cue.FINISH -> listOf(Triple(1046.5, 0, 120), Triple(1318.5, 90, 180))       // C6 → E6
            Cue.LIGHT, Cue.START -> emptyList()                                          // see beep()
            Cue.WIN -> listOf(Triple(523.3, 0, 180), Triple(659.3, 110, 180), Triple(784.0, 220, 200), Triple(1046.5, 330, 420)) // C major arpeggio
        }
        val total = notes.maxOf { it.second + it.third } + 40
        val out = DoubleArray(RATE * total / 1000)
        for ((f, start, len) in notes) {
            val s0 = RATE * start / 1000
            val n = RATE * len / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = (i / (RATE * 0.004)).coerceAtMost(1.0)           // 4 ms, no click
                val decay = exp(-t * 7.0 / (len / 1000.0))
                // A touch of the octave makes it a chime rather than a beep.
                val v = sin(2 * PI * f * t) + 0.25 * sin(4 * PI * f * t)
                val idx = s0 + i
                if (idx < out.size) out[idx] += v * attack * decay
            }
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(out.size) { (out[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }

    /**
     * A start-line beep: held at full level for its whole length (no fade -- that is what made the
     * lights sound like the countdown tick), a soft square wave from odd harmonics, 5 ms edges.
     */
    private fun beep(f: Double, lenMs: Int): ShortArray {
        val n = RATE * lenMs / 1000
        val edge = RATE * 0.005
        val out = DoubleArray(n + RATE * 30 / 1000)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = minOf(1.0, i / edge, (n - i) / edge)
            out[i] = (sin(2 * PI * f * t) + sin(6 * PI * f * t) / 3 + sin(10 * PI * f * t) / 5) * env
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(out.size) { (out[it] / peak * 0.7 * Short.MAX_VALUE).toInt().toShort() }
    }
}
