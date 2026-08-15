package com.retrorts.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import kotlin.math.max
import kotlin.math.min

/**
 * Fallback PCM output for devices where the native AAudio stream cannot be
 * opened or remains unavailable. Called only from the native emulator bridge.
 */
object NativeAudioFallback {
    @Volatile
    private var track: AudioTrack? = null

    @JvmStatic
    fun start(sampleRate: Int): Boolean {
        stop()
        if (sampleRate <= 0) return false

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val bufferSize = max(minBuffer * 4, sampleRate / 5 * 4)
        return runCatching {
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
            check(newTrack.state == AudioTrack.STATE_INITIALIZED)
            newTrack.play()
            track = newTrack
            true
        }.getOrElse {
            stop()
            false
        }
    }

    /** Returns the number of stereo frames accepted by Android. */
    @JvmStatic
    fun write(samples: ShortArray, frames: Int): Int {
        val activeTrack = track ?: return 0
        val sampleCount = min(samples.size, frames.coerceAtLeast(0) * 2)
        if (sampleCount == 0 || activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) return 0
        return try {
            val writtenSamples = activeTrack.write(
                samples,
                0,
                sampleCount,
                AudioTrack.WRITE_BLOCKING
            )
            if (writtenSamples > 0) writtenSamples / 2 else 0
        } catch (_: IllegalStateException) {
            0
        }
    }

    @JvmStatic
    fun stop() {
        val oldTrack = track
        track = null
        if (oldTrack != null) {
            runCatching { oldTrack.pause() }
            runCatching { oldTrack.flush() }
            runCatching { oldTrack.stop() }
            oldTrack.release()
        }
    }
}
