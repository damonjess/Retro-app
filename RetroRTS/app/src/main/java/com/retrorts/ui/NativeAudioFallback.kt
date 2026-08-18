package com.retrorts.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Managed PCM output used by DOSBox-Pure on devices where AAudio is silent or
 * unreliable. Native code supplies a direct ByteBuffer, avoiding a per-audio-
 * callback ShortArray copy that can cause stutter on lower-powered devices.
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

        // Keep enough queued audio for busy frames, but avoid the old 333 ms
        // queue: it delayed feedback and made a missed callback audible longer.
        // 160 ms is stable on mid-range Android devices while remaining responsive.
        val bufferSize = max(minBuffer * 4, sampleRate / 6 * 4)
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

    /** Returns the number of stereo PCM frames accepted by Android. */
    @JvmStatic
    fun writeBuffer(buffer: ByteBuffer, byteCount: Int): Int {
        val activeTrack = track ?: return 0
        val safeByteCount = min(buffer.capacity(), byteCount.coerceAtLeast(0))
        if (safeByteCount < 4 || activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) return 0

        return try {
            buffer.position(0)
            buffer.limit(safeByteCount)
            val writtenBytes = activeTrack.write(buffer, safeByteCount, AudioTrack.WRITE_BLOCKING)
            if (writtenBytes > 0) writtenBytes / 4 else 0
        } catch (_: IllegalArgumentException) {
            0
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
