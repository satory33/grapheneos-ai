package com.satory.graphenosai.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    @Test
    fun `stops after speech is followed by configured silence`() {
        val detector = VoiceActivityDetector(requiredSilenceMs = 100)
        val speech = pcmChunk(value = 8_000, sampleCount = 800) // 50 ms
        val silence = pcmChunk(value = 0, sampleCount = 800) // 50 ms

        assertFalse(detector.addPcmChunk(speech))
        assertFalse(detector.addPcmChunk(silence))
        assertTrue(detector.addPcmChunk(silence))
    }

    @Test
    fun `does not stop before speech starts`() {
        val detector = VoiceActivityDetector(requiredSilenceMs = 100)
        val silence = pcmChunk(value = 0, sampleCount = 2_400)

        assertFalse(detector.addPcmChunk(silence))
    }

    private fun pcmChunk(value: Short, sampleCount: Int): ByteArray {
        return ByteArray(sampleCount * 2).also { bytes ->
            repeat(sampleCount) { index ->
                bytes[index * 2] = (value.toInt() and 0xff).toByte()
                bytes[index * 2 + 1] = (value.toInt() shr 8).toByte()
            }
        }
    }
}
