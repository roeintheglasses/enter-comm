package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end integration tests for the audio pipeline.
 *
 * These tests validate the complete audio processing flow:
 * 1. Audio capture (simulated with test data)
 * 2. WebRTC/Opus encoding (or ADPCM fallback)
 * 3. Network transmission (simulated with byte array transfer)
 * 4. Decoding back to PCM
 * 5. Audio playback verification
 *
 * This covers subtask-3-1: End-to-end audio pipeline integration test
 *
 * Note: Full device testing requires an actual Android device. These tests
 * validate the codec pipeline logic that can run in a JUnit environment.
 *
 * For on-device E2E testing, see the manual testing steps in build-progress.txt:
 * 1. Build debug APK: make build
 * 2. Install on device: make install
 * 3. Launch app: make run
 * 4. Check logs: make logs | grep -E "WebRTC|Opus|AudioManager"
 * 5. Verify WebRTC initialization: Look for "WebRTC audio processor initialized"
 * 6. Verify codec status: Look for "Using WebRTC/Opus codec" or "Using ADPCM codec"
 */
class AudioPipelineIntegrationTest {

    private lateinit var adpcmCodec: AdpcmCodec
    private lateinit var webRtcProcessor: WebRTCAudioProcessor

    @Before
    fun setUp() {
        Logger.isTestMode = true
        adpcmCodec = AdpcmCodec()
        webRtcProcessor = WebRTCAudioProcessor()
        adpcmCodec.initialize()
    }

    @After
    fun tearDown() {
        adpcmCodec.cleanup()
        webRtcProcessor.cleanup()
        Logger.isTestMode = false
    }

    // === Audio Pipeline Flow Tests (ADPCM Path) ===

    @Test
    fun `ADPCM pipeline - encode decode round trip preserves data integrity`() {
        // Generate test audio signal (sine wave simulating voice)
        val originalSamples = generateTestAudioSignal(960)

        // Step 1: Encode (simulates capture + encode)
        val encoded = adpcmCodec.encode(originalSamples)
        assertNotNull("Encoding should succeed", encoded)
        assertTrue("Encoded data should be smaller than raw PCM", encoded!!.size < originalSamples.size * 2)

        // Step 2: Transmit (simulated by copying the byte array)
        val transmitted = encoded.copyOf()

        // Step 3: Decode (simulates receive + decode + playback)
        val decoded = adpcmCodec.decode(transmitted)
        assertEquals("Decoded sample count should match original", originalSamples.size, decoded.size)

        // Step 4: Verify signal integrity (allow for lossy compression artifacts)
        val correlation = calculateCorrelation(originalSamples, decoded)
        assertTrue(
            "Decoded signal should correlate with original (correlation: $correlation)",
            correlation > 0.9,
        )
    }

    @Test
    fun `ADPCM pipeline - multiple consecutive frames maintain state`() {
        val frames = listOf(
            generateTestAudioSignal(960, frequency = 440.0), // A4 note
            generateTestAudioSignal(960, frequency = 523.25), // C5 note
            generateTestAudioSignal(960, frequency = 659.25), // E5 note
        )

        val decodedFrames = mutableListOf<ShortArray>()

        for (frame in frames) {
            val encoded = adpcmCodec.encode(frame)
            assertNotNull(encoded)
            val decoded = adpcmCodec.decode(encoded!!)
            decodedFrames.add(decoded)
        }

        // Verify all frames decoded correctly
        assertEquals(3, decodedFrames.size)
        decodedFrames.forEachIndexed { index, decoded ->
            assertEquals(
                "Frame $index should have correct sample count",
                960,
                decoded.size,
            )
        }
    }

    @Test
    fun `ADPCM pipeline - handles silence correctly`() {
        // Silent frame (all zeros)
        val silentFrame = ShortArray(960) { 0 }

        val encoded = adpcmCodec.encode(silentFrame)
        assertNotNull(encoded)

        val decoded = adpcmCodec.decode(encoded!!)
        assertEquals(960, decoded.size)

        // Decoded silence should still be very quiet
        val rms = calculateRms(decoded)
        assertTrue(
            "RMS of decoded silence should be very low: $rms",
            rms < 100.0,
        )
    }

    @Test
    fun `ADPCM pipeline - handles loud signal without clipping`() {
        // Maximum amplitude signal
        val loudFrame = ShortArray(960) { i ->
            if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }

        val encoded = adpcmCodec.encode(loudFrame)
        assertNotNull(encoded)

        val decoded = adpcmCodec.decode(encoded!!)
        assertEquals(960, decoded.size)

        // Should not have any zero samples from clipping errors
        var hasNonZero = false
        for (sample in decoded) {
            if (sample != 0.toShort()) {
                hasNonZero = true
                break
            }
        }
        assertTrue("Decoded loud signal should have non-zero samples", hasNonZero)
    }

    // === Zero-Copy Pipeline Tests ===

    @Test
    fun `ADPCM pipeline - encodeInto and decodeInto work with pre-allocated buffers`() {
        val originalSamples = generateTestAudioSignal(960)

        // Pre-allocate buffers (simulating AudioBufferPool)
        val encodeBuffer = ByteArray(2048)
        val decodeBuffer = ShortArray(960)

        // Encode into pre-allocated buffer
        val encodedSize = adpcmCodec.encodeInto(originalSamples, encodeBuffer)
        assertTrue("Encoded size should be positive", encodedSize > 0)
        assertTrue("Encoded size should be less than raw PCM", encodedSize < 1920)

        // Create slice for transmission
        val transmitBuffer = encodeBuffer.copyOf(encodedSize)

        // Decode into pre-allocated buffer
        val decodedCount = adpcmCodec.decodeInto(transmitBuffer, decodeBuffer)
        assertEquals("Decoded sample count should match frame size", 960, decodedCount)

        // Verify integrity
        val correlation = calculateCorrelation(originalSamples, decodeBuffer)
        assertTrue("Correlation should be high: $correlation", correlation > 0.9)
    }

    // === Pipeline Stress Tests ===

    @Test
    fun `ADPCM pipeline - handles rapid sequential encoding`() {
        val frameCount = 100
        var totalEncodedBytes = 0L
        var totalRawBytes = 0L

        for (i in 0 until frameCount) {
            val frame = generateTestAudioSignal(960, frequency = 300.0 + i * 5)
            val encoded = adpcmCodec.encode(frame)
            assertNotNull("Frame $i encoding should succeed", encoded)
            totalEncodedBytes += encoded!!.size
            totalRawBytes += frame.size * 2
        }

        // Verify compression ratio
        val compressionRatio = totalRawBytes.toFloat() / totalEncodedBytes
        assertTrue(
            "Compression ratio should be approximately 4x: $compressionRatio",
            compressionRatio in 3.5f..4.5f,
        )
    }

    @Test
    fun `ADPCM pipeline - handles rapid sequential decoding`() {
        // Pre-encode some frames
        val encodedFrames = mutableListOf<ByteArray>()
        for (i in 0 until 50) {
            val frame = generateTestAudioSignal(960, frequency = 400.0 + i * 10)
            val encoded = adpcmCodec.encode(frame)
            assertNotNull(encoded)
            encodedFrames.add(encoded!!)
        }

        // Decode all frames rapidly
        val decodedFrames = mutableListOf<ShortArray>()
        for (encoded in encodedFrames) {
            val decoded = adpcmCodec.decode(encoded)
            assertTrue("Decoded frame should not be empty", decoded.isNotEmpty())
            decodedFrames.add(decoded)
        }

        assertEquals(50, decodedFrames.size)
    }

    // === WebRTC Processor Pipeline Tests (without Android initialization) ===

    @Test
    fun `WebRTC processor - configuration is pipeline-ready`() {
        // Verify WebRTC processor has correct audio parameters for pipeline
        val stats = webRtcProcessor.getStats()

        assertEquals("Sample rate should match ADPCM for compatibility", 48_000, stats.sampleRate)
        assertEquals("Bitrate should be 32kbps for Opus", 32_000, stats.bitrate)
        assertTrue("AEC should be enabled by default", stats.aecEnabled)
        assertTrue("NS should be enabled by default", stats.nsEnabled)
        assertTrue("AGC should be enabled by default", stats.agcEnabled)
    }

    @Test
    fun `WebRTC processor - audio processing config is correct`() {
        val config = webRtcProcessor.getAudioProcessingConfig()

        assertTrue("AEC should be enabled", config.aecEnabled)
        assertTrue("NS should be enabled", config.nsEnabled)
        assertTrue("AGC should be enabled", config.agcEnabled)
        assertTrue("High-pass filter should be enabled", config.highPassFilterEnabled)
    }

    @Test
    fun `WebRTC processor - gracefully returns null when not initialized`() {
        val samples = generateTestAudioSignal(960)

        // Without Android context, WebRTC cannot fully initialize
        // It should gracefully return null/empty instead of crashing
        val encoded = webRtcProcessor.encode(samples)
        assertNull("Encode should return null when not initialized", encoded)

        val decoded = webRtcProcessor.decode(ByteArray(100))
        assertTrue("Decode should return empty array when not initialized", decoded.isEmpty())
    }

    // === Codec Interoperability Tests ===

    @Test
    fun `Both codecs use same frame size`() {
        assertEquals(
            "Frame sizes should match for seamless fallback",
            AdpcmCodec.FRAME_SIZE,
            WebRTCAudioProcessor.FRAME_SIZE,
        )
    }

    @Test
    fun `Both codecs use same sample rate`() {
        assertEquals(
            "Sample rates should match for seamless fallback",
            AdpcmCodec.SAMPLE_RATE,
            WebRTCAudioProcessor.SAMPLE_RATE,
        )
    }

    @Test
    fun `Both codecs use same channel count`() {
        assertEquals(
            "Channel counts should match for seamless fallback",
            AdpcmCodec.CHANNELS,
            WebRTCAudioProcessor.CHANNELS,
        )
    }

    @Test
    fun `PLC produces compatible frame sizes`() {
        val adpcmPlc = adpcmCodec.decodePLC()
        val webrtcPlc = webRtcProcessor.decodePLC()

        assertEquals(
            "PLC frame sizes should match",
            adpcmPlc.size,
            webrtcPlc.size,
        )
        assertEquals(
            "PLC should produce standard frame size",
            AdpcmCodec.FRAME_SIZE,
            adpcmPlc.size,
        )
    }

    // === Fallback Simulation Tests ===

    @Test
    fun `Fallback scenario - can switch from WebRTC to ADPCM mid-stream`() {
        // Simulate scenario where WebRTC fails and we fall back to ADPCM
        val frames = mutableListOf<ShortArray>()
        for (i in 0 until 5) {
            frames.add(generateTestAudioSignal(960, frequency = 400.0 + i * 50))
        }

        val decodedFrames = mutableListOf<ShortArray>()

        // First 2 frames would use WebRTC (simulated - returns null)
        for (i in 0 until 2) {
            val encoded = webRtcProcessor.encode(frames[i])
            if (encoded == null) {
                // Fallback to ADPCM
                val adpcmEncoded = adpcmCodec.encode(frames[i])
                assertNotNull(adpcmEncoded)
                val decoded = adpcmCodec.decode(adpcmEncoded!!)
                decodedFrames.add(decoded)
            }
        }

        // Next 3 frames use ADPCM directly (after fallback)
        for (i in 2 until 5) {
            val encoded = adpcmCodec.encode(frames[i])
            assertNotNull(encoded)
            val decoded = adpcmCodec.decode(encoded!!)
            decodedFrames.add(decoded)
        }

        // All frames should be decoded
        assertEquals(5, decodedFrames.size)
        decodedFrames.forEach { frame ->
            assertEquals(960, frame.size)
        }
    }

    // === Network Simulation Tests ===

    @Test
    fun `Pipeline - handles simulated network packet with header`() {
        // Create audio frame
        val samples = generateTestAudioSignal(960)

        // Encode
        val encodedAudio = adpcmCodec.encode(samples)
        assertNotNull(encodedAudio)

        // Simulate network packet with header (source ID, sequence, timestamp)
        val packetHeader = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(12_345_678L) // Source ID (8 bytes)
            .putInt(1) // Sequence number (4 bytes)
            .putInt(System.currentTimeMillis().toInt()) // Timestamp (4 bytes)
            .array()

        // Create full packet
        val packet = ByteArray(packetHeader.size + encodedAudio!!.size)
        System.arraycopy(packetHeader, 0, packet, 0, packetHeader.size)
        System.arraycopy(encodedAudio, 0, packet, packetHeader.size, encodedAudio.size)

        // Extract audio from packet (simulates receive side)
        val receivedAudio = ByteArray(packet.size - packetHeader.size)
        System.arraycopy(packet, packetHeader.size, receivedAudio, 0, receivedAudio.size)

        // Decode
        val decoded = adpcmCodec.decode(receivedAudio)
        assertEquals(960, decoded.size)

        // Verify
        val correlation = calculateCorrelation(samples, decoded)
        assertTrue("Correlation after network simulation: $correlation", correlation > 0.9)
    }

    @Test
    fun `Pipeline - handles packet loss concealment`() {
        // Encode 3 frames
        val frame1 = generateTestAudioSignal(960, frequency = 440.0)
        val frame2 = generateTestAudioSignal(960, frequency = 440.0)
        val frame3 = generateTestAudioSignal(960, frequency = 440.0)

        val encoded1 = adpcmCodec.encode(frame1)
        adpcmCodec.encode(frame2) // This packet is "lost"
        val encoded3 = adpcmCodec.encode(frame3)

        assertNotNull(encoded1)
        assertNotNull(encoded3)

        // Decode frame 1
        val decoded1 = adpcmCodec.decode(encoded1!!)
        assertEquals(960, decoded1.size)

        // Frame 2 is lost - use PLC
        val plcFrame = adpcmCodec.decodePLC()
        assertEquals(960, plcFrame.size)

        // Decode frame 3
        val decoded3 = adpcmCodec.decode(encoded3!!)
        assertEquals(960, decoded3.size)

        // All frames should have valid sample counts
        assertEquals(960, decoded1.size)
        assertEquals(960, plcFrame.size)
        assertEquals(960, decoded3.size)
    }

    // === Helper Methods ===

    /**
     * Generate a test audio signal (sine wave) for testing.
     *
     * @param sampleCount Number of samples to generate
     * @param frequency Frequency in Hz (default 440 Hz = A4 note)
     * @param amplitude Amplitude as fraction of max (default 0.5)
     * @return ShortArray of PCM samples
     */
    private fun generateTestAudioSignal(sampleCount: Int, frequency: Double = 440.0, amplitude: Double = 0.5): ShortArray {
        val sampleRate = AdpcmCodec.SAMPLE_RATE.toDouble()
        return ShortArray(sampleCount) { i ->
            val t = i / sampleRate
            val sample = kotlin.math.sin(2.0 * Math.PI * frequency * t) * amplitude * Short.MAX_VALUE
            sample.toInt().toShort()
        }
    }

    /**
     * Calculate Pearson correlation coefficient between two arrays.
     * Returns value between -1 and 1, where 1 is perfect correlation.
     */
    private fun calculateCorrelation(a: ShortArray, b: ShortArray): Double {
        require(a.size == b.size) { "Arrays must have same size" }
        val n = a.size

        var sumA = 0.0
        var sumB = 0.0
        var sumAB = 0.0
        var sumA2 = 0.0
        var sumB2 = 0.0

        for (i in 0 until n) {
            val ai = a[i].toDouble()
            val bi = b[i].toDouble()
            sumA += ai
            sumB += bi
            sumAB += ai * bi
            sumA2 += ai * ai
            sumB2 += bi * bi
        }

        val numerator = n * sumAB - sumA * sumB
        val denominator = kotlin.math.sqrt((n * sumA2 - sumA * sumA) * (n * sumB2 - sumB * sumB))

        return if (denominator > 0) numerator / denominator else 0.0
    }

    /**
     * Calculate RMS (Root Mean Square) of audio samples.
     */
    private fun calculateRms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return kotlin.math.sqrt(sum / samples.size)
    }
}
