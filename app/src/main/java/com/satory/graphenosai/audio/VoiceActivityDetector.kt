package com.satory.graphenosai.audio

import kotlin.math.sqrt

/**
 * Detects the end of an utterance in 16-bit, mono PCM audio.
 *
 * Vosk transcribes a completed recording, so without this detector an offline
 * recording can only end when the user presses the stop button.  Keeping the
 * detector independent of Android also makes its behaviour deterministic in
 * unit tests.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16_000,
    private val speechThresholdDb: Double = -42.0,
    private val requiredSilenceMs: Long = 1_500
) {
    private var speechDetected = false
    private var silenceDurationMs = 0L

    /** Returns true once speech has been followed by enough silence. */
    fun addPcmChunk(chunk: ByteArray): Boolean {
        if (chunk.size < 2) return false

        var sumSquares = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < chunk.size) {
            val sample = ((chunk[index + 1].toInt() shl 8) or (chunk[index].toInt() and 0xff)).toShort()
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            sampleCount++
            index += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val db = if (rms > 0.0) 20.0 * kotlin.math.log10(rms) else -160.0
        val chunkDurationMs = sampleCount * 1_000L / sampleRate

        if (db >= speechThresholdDb) {
            speechDetected = true
            silenceDurationMs = 0
            return false
        }

        if (speechDetected) {
            silenceDurationMs += chunkDurationMs
        }
        return speechDetected && silenceDurationMs >= requiredSilenceMs
    }
}
