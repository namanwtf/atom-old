package io.github.namanwtf.atom.agent.voice

/**
 * Long text is synthesized per segment and then played back seamlessly. The last samples of one segment and the first samples of the next are not necessarily near zero,
 * and a waveform step is heard as a "buzz". Each segment gets a few milliseconds of linear fade-in and fade-out: the tail can only be finalized once the segment ends,
 * so the last [fadeSamples] samples are always retained and written out faded at [end]. Input is 16-bit little-endian mono PCM,
 * and every write is whole-sample.
 */
internal class SpeechSegmentFade(private val fadeSamples: Int = FADE_SAMPLES) {
    private var tail = ByteArray(0)
    private var emittedSamples = 0L

    /** Returns the part that can be played immediately, which may be empty. */
    fun write(bytes: ByteArray): ByteArray {
        val joined = tail + bytes
        val keep = minOf(joined.size, fadeSamples * BYTES_PER_SAMPLE)
        tail = joined.copyOfRange(joined.size - keep, joined.size)
        return joined.copyOfRange(0, joined.size - keep).also(::fadeIn)
    }

    /** Ends the current segment and returns the faded-out tail; the next segment can then begin. */
    fun end(): ByteArray {
        val last = tail
        tail = ByteArray(0)
        fadeIn(last)
        val samples = last.size / BYTES_PER_SAMPLE
        val ramp = minOf(samples, fadeSamples)
        for (index in samples - ramp until samples) {
            scale(last, index, (samples - 1 - index).toFloat() / ramp)
        }
        emittedSamples = 0
        return last
    }

    private fun fadeIn(bytes: ByteArray) {
        val samples = bytes.size / BYTES_PER_SAMPLE
        var index = 0
        while (index < samples && emittedSamples + index < fadeSamples) {
            scale(bytes, index, (emittedSamples + index).toFloat() / fadeSamples)
            index++
        }
        emittedSamples += samples
    }

    private fun scale(bytes: ByteArray, sample: Int, gain: Float) {
        val offset = sample * BYTES_PER_SAMPLE
        val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        val scaled = (value.toShort() * gain).toInt()
        bytes[offset] = scaled.toByte()
        bytes[offset + 1] = (scaled shr 8).toByte()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        /** 5 ms at 24 kHz, short enough that the volume change is inaudible, yet enough to remove the step. */
        const val FADE_SAMPLES = 120
    }
}
