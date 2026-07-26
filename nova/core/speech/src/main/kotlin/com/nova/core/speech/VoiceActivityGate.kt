package com.nova.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        if (minBuffer <= 0) return@flow

        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@flow
        }

        val frame = ShortArray(FRAME_SAMPLES)
        var loudFrames = 0

        try {
            recorder.startRecording()
            while (true) {
                coroutineContext.ensureActive()

                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue

                if (rms(frame, read) >= threshold) {
                    loudFrames++
                    if (loudFrames >= framesToTrigger) {
                        loudFrames = 0
                        emit(Unit)
                    }
                } else {
                    loudFrames = 0
                }
            }
        } finally {
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
        const val SAMPLE_RATE = 16_000

        /** 32 ms at 16 kHz — long enough for a stable RMS, short enough to react quickly. */
        const val FRAME_SAMPLES = 512
    }
}
