@file:OptIn(UnstableApi::class)

package com.polefm.radio.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqPreset(val label: String, val gainsDb: FloatArray) {
    OFF("Выкл", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    ROCK("Рок", floatArrayOf(5f, 3f, -2f, 2f, 4f)),
    POP("Поп", floatArrayOf(-1f, 2f, 4f, 2f, -1f)),
    CLASSIC("Класс", floatArrayOf(3f, 0f, -1f, 2f, 3f)),
    JAZZ("Джаз", floatArrayOf(4f, 1f, -1f, 2f, 4f));

    companion object {
        fun fromName(name: String?): EqPreset = entries.find { it.name == name } ?: OFF
    }
}

/** Live spectrum and current EQ preset shared between the player and the UI. */
object AudioLevels {
    @Volatile
    var bands: FloatArray = FloatArray(BAND_COUNT)

    @Volatile
    var preset: EqPreset = EqPreset.OFF

    const val BAND_COUNT = 15
    const val EQ_BAND_COUNT = 5
    val EQ_FREQUENCIES = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
}

/** RBJ peaking-EQ biquad filter. */
private class BiquadEq {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(sampleRate: Double, frequency: Double, q: Double, gainDb: Double) {
        val w0 = 2.0 * PI * frequency / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val a = 10.0.pow(gainDb / 40.0)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha / a) / a0
        reset()
    }

    fun process(x: Float): Float {
        val result = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2).toFloat()
        x2 = x1
        x1 = x.toDouble()
        y2 = y1
        y1 = result.toDouble()
        return result
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

/**
 * Pass-through PCM processor: applies a 5-band EQ and performs a real
 * (Goertzel) spectral analysis of the audio the user actually hears.
 */
class RmsAudioProcessor : AudioProcessor {
    private var pending: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var inputEnded = false
    private var sampleRate = 44_100
    private var channelCount = 2
    private var filters: Array<BiquadEq> = emptyArray()
    private var appliedPreset: EqPreset? = null

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        appliedPreset = null
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (AudioLevels.preset == EqPreset.OFF) {
            measure(inputBuffer.duplicate())
            pending = inputBuffer
            return
        }
        ensureFilters()
        val size = inputBuffer.remaining()
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        outputBuffer.clear()
        applyEq(inputBuffer, outputBuffer)
        outputBuffer.limit(size)
        measure(outputBuffer.duplicate())
        // The pipeline only moves on when the input buffer's position advances.
        inputBuffer.position(inputBuffer.limit())
        pending = outputBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = pending
        pending = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && pending === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        pending = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        AudioLevels.bands = FloatArray(AudioLevels.BAND_COUNT)
        appliedPreset = null
    }

    override fun reset() {
        flush()
    }

    private fun ensureFilters() {
        val preset = AudioLevels.preset
        if (appliedPreset == preset && filters.isNotEmpty()) return
        appliedPreset = preset
        filters = Array(channelCount * AudioLevels.EQ_BAND_COUNT) { BiquadEq() }
        for (ch in 0 until channelCount) {
            for (band in 0 until AudioLevels.EQ_BAND_COUNT) {
                filters[ch * AudioLevels.EQ_BAND_COUNT + band].configure(
                    sampleRate.toDouble(),
                    AudioLevels.EQ_FREQUENCIES[band],
                    1.0,
                    preset.gainsDb[band].toDouble()
                )
            }
        }
    }

    private fun applyEq(input: ByteBuffer, output: ByteBuffer) {
        val frames = input.remaining() / (2 * channelCount)
        for (frame in 0 until frames) {
            for (ch in 0 until channelCount) {
                val index = (frame * channelCount + ch) * 2
                var sample = input.getShort(index) / 32768f
                for (band in 0 until AudioLevels.EQ_BAND_COUNT) {
                    sample = filters[ch * AudioLevels.EQ_BAND_COUNT + band].process(sample)
                }
                if (!sample.isFinite()) {
                    filters.forEach { it.reset() }
                    sample = 0f
                }
                // Small headroom prevents clipping when several bands boost at once.
                output.putShort(index, ((sample * 0.85f).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
    }

    private fun measure(buffer: ByteBuffer) {
        if (buffer.remaining() < WINDOW * 2) return
        val samples = ShortArray(WINDOW)
        var i = 0
        while (i < WINDOW && buffer.remaining() >= 2) {
            samples[i++] = buffer.short
        }
        if (i < WINDOW) return

        val out = FloatArray(AudioLevels.BAND_COUNT)
        val nyquist = sampleRate / 2f
        for (band in 0 until AudioLevels.BAND_COUNT) {
            val frequency = minOf(BAND_FREQUENCIES[band], nyquist - 100f)
            val k = frequency * WINDOW / sampleRate
            val coefficient = 2.0 * cos(2.0 * PI * k / WINDOW)
            var s1 = 0.0
            var s2 = 0.0
            for (n in 0 until WINDOW) {
                val s0 = samples[n].toDouble() + coefficient * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = max(0.0, s1 * s1 + s2 * s2 - coefficient * s1 * s2)
            out[band] = (sqrt(power) / WINDOW / 3500.0).toFloat().coerceIn(0f, 1f)
        }
        AudioLevels.bands = out
    }

    private companion object {
        const val WINDOW = 512
        val BAND_FREQUENCIES = floatArrayOf(
            50f, 80f, 130f, 200f, 310f, 480f, 740f, 1100f,
            1600f, 2300f, 3300f, 4700f, 6500f, 9000f, 13000f
        )
    }
}
