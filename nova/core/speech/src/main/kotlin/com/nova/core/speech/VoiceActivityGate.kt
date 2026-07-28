package com.nova.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Emits when someone starts speaking.
 *
 * This exists so the wake word does not need the speech recogniser running all day. Reading raw
 * PCM and computing a root-mean-square per frame is arithmetic on a small buffer; running the
 * platform recogniser is an entire ASR pipeline, and the difference in battery over hours is
 * the whole point.
 *
 * It only answers "is that a voice", never "what was said". The recogniser still decides
 * whether the wake phrase was actually spoken.
 */
class VoiceActivityGate(
    private val sampleRate: Int = SAMPLE_RATE,
    /**
     * RMS above which a frame counts as speech, on a 0..1 scale.
     *
     * Calibrated by ear rather than theory: low enough to catch a normal speaking voice at
     * arm's length, high enough that room tone and a laptop fan do not constantly wake the
     * recogniser — which would give back exactly the battery this is here to save.
     */
    private val threshold: Float = 0.035f,
    /** Consecutive speech frames before firing, so a door slam is not a wake word. */
    private val framesToTrigger: Int = 3,
) {

    /**
     * Cold flow. Each emission means "speech just started"; the caller should stop collecting
     * while it uses the microphone for something else, then collect again.
     */
    @SuppressLint("MissingPermission")
    fun speechStarts(): Flow<Unit> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord reports no usable buffer size; gate cannot run")
            return@flow
        }

        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            // Usually the microphone already being held by something else, which is silent
            // and indistinguishable from "nobody is speaking" without this.
            Log.w(TAG, "AudioRecord failed to initialise; another app may hold the microphone")
            recorder.release()
            return@flow
        }

        // Cancels Raza's own output at the hardware level, where it can be subtracted from the
        // signal rather than inferred from the transcript afterwards. [EchoGuard] stays
        // regardless: this is absent on some devices, weak on others, and does nothing for
        // sound arriving off a wall a beat late.
        val canceller = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            } else {
                Log.i(TAG, "no hardware echo canceller on this device")
                null
            }
        }.getOrNull()

        val frame = ShortArray(FRAME_SAMPLES)
        var loudFrames = 0

        try {
            recorder.startRecording()
            Log.i(TAG, "listening for speech onset")

            var peak = 0f
            var frames = 0

            while (true) {
                coroutineContext.ensureActive()

                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue

                val level = rms(frame, read)
                peak = maxOf(peak, level)

                // Periodic, because the threshold is the single most likely thing to be wrong
                // on an unfamiliar microphone, and it is invisible otherwise.
                if (++frames % LEVEL_LOG_FRAMES == 0) {
                    Log.i(TAG, "peak level %.4f over last %d frames (threshold %.4f)".format(peak, LEVEL_LOG_FRAMES, threshold))
                    peak = 0f
                }

                if (level >= threshold) {
                    loudFrames++
                    if (loudFrames >= framesToTrigger) {
                        loudFrames = 0
                        Log.i(TAG, "speech detected at level %.4f".format(level))
                        emit(Unit)
                    }
                } else {
                    loudFrames = 0
                }
            }
        } finally {
            // Before the recorder: the effect is attached to its audio session, and outliving
            // that session leaks a hardware resource the next open may not get back.
            runCatching { canceller?.release() }
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    /** Root mean square of the frame, normalised to 0..1. */
    private fun rms(frame: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = frame[i] / Short.MAX_VALUE.toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count).toFloat()
    }

    private companion object {
        const val TAG = "NovaWake"

        /** About every two seconds at 32 ms a frame — often enough to tune by, rare enough not to flood. */
        const val LEVEL_LOG_FRAMES = 60

        const val SAMPLE_RATE = 16_000

        /** 32 ms at 16 kHz — long enough for a stable RMS, short enough to react quickly. */
        const val FRAME_SAMPLES = 512
    }
}
