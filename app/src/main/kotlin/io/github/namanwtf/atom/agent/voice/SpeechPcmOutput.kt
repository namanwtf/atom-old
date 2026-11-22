package io.github.namanwtf.atom.agent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import kotlinx.coroutines.CancellationException

internal class SpeechPcmOutput : SpeechAudioOutput {
    private var track: AudioTrack? = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(SAMPLE_RATE, AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private var frames = 0L
    private var started = false
    private var finished = false

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = synchronized(this) {
                val audio = track ?: throw CancellationException("Speech playback stopped")
                check(!finished) { "Speech audio input already finished" }
                val remaining = bytes.size - offset
                val count = audio.write(bytes, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) {
                    throw SpeechFailure(
                        SpeechErrorCode.AUDIO,
                        AppStrings.get(R.string.voice_i18n_audio_playback_failed, "Audio playback failed"),
                    )
                }
                frames += count / BYTES_PER_FRAME
                // Starting an empty track early would time out and deactivate while waiting for the first cloud packet; start only after it's full, where a short write also means the writable limit is reached.
                if (!started && frames > 0 && (frames >= audio.bufferSizeInFrames || count < remaining)) {
                    audio.play()
                    started = true
                }
                count
            }
            offset += written
            if (written == 0) Thread.sleep(5)
        }
    }

    @Synchronized override fun finish() {
        val audio = track ?: throw CancellationException("Speech playback stopped")
        if (finished) return
        // Short audio may never fill the start threshold; on end-of-input, start with the actual frame count, without padding silence or truncating the content.
        if (!started && frames > 0) {
            audio.setStartThresholdInFrames(frames.toInt())
            audio.play()
            started = true
        }
        finished = true
    }

    @Synchronized override fun drained(): Boolean = track?.let {
        finished && (it.playbackHeadPosition.toLong() and 0xffffffffL) >= frames
    } ?: true

    @Synchronized override fun close() {
        track?.let { it.pause(); it.flush(); it.release() }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
    }
}
