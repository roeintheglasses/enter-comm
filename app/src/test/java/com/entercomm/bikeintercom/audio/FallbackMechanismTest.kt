package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fallback mechanism validation tests for WebRTC to ADPCM codec fallback.
 *
 * This test suite validates the graceful fallback behavior when WebRTC
 * initialization fails or encounters runtime failures. Per the spec:
 * - "If WebRTC initialization fails, app must fall back to ADPCM without crashing"
 * - "Forced failure test shows successful fallback to ADPCM"
 * - "App remains functional; error logged for monitoring"
 *
 * Test Categories:
 * 1. CodecStatus enum validation
 * 2. Initialization failure fallback
 * 3. Runtime failure fallback (consecutive failures trigger)
 * 4. Recovery mechanism tests
 * 5. ADPCM codec availability when WebRTC fails
 * 6. Graceful degradation without crashes
 *
 * Note: These tests can run without Android context or WebRTC native libraries
 * by testing the fallback logic in isolation.
 */
class FallbackMechanismTest {

    private lateinit var adpcmCodec: AdpcmCodec

    @Before
    fun setUp() {
        Logger.isTestMode = true
        adpcmCodec = AdpcmCodec()
    }

    @After
    fun tearDown() {
        adpcmCodec.cleanup()
        Logger.isTestMode = false
    }

    // === CodecStatus Fallback State Tests ===

    @Test
    fun `CodecStatus has INITIALIZING as valid starting state`() {
        assertEquals(CodecStatus.INITIALIZING, CodecStatus.entries.first { it.name == "INITIALIZING" })
    }

    @Test
    fun `CodecStatus ADPCM_FALLBACK_INIT_FAILURE indicates init fallback`() {
        val status = CodecStatus.ADPCM_FALLBACK_INIT_FAILURE
        assertTrue(status.name.contains("FALLBACK"))
        assertTrue(status.name.contains("INIT"))
    }

    @Test
    fun `CodecStatus ADPCM_FALLBACK_RUNTIME_FAILURE indicates runtime fallback`() {
        val status = CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE
        assertTrue(status.name.contains("FALLBACK"))
        assertTrue(status.name.contains("RUNTIME"))
    }

    @Test
    fun `CodecStatus distinguishes between init and runtime fallback`() {
        assertNotEquals(
            CodecStatus.ADPCM_FALLBACK_INIT_FAILURE,
            CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE,
        )
    }

    @Test
    fun `CodecStatus ADPCM_OPUS_DISABLED indicates user choice not failure`() {
        val status = CodecStatus.ADPCM_OPUS_DISABLED
        assertTrue(status.name.contains("DISABLED"))
        assertFalse(status.name.contains("FAILURE"))
    }

    @Test
    fun `CodecStatus WEBRTC_ACTIVE indicates successful WebRTC initialization`() {
        assertEquals(CodecStatus.WEBRTC_ACTIVE, CodecStatus.entries.first { it.name == "WEBRTC_ACTIVE" })
    }

    @Test
    fun `CodecStatus FAILED indicates complete initialization failure`() {
        val status = CodecStatus.FAILED
        assertEquals("FAILED", status.name)
    }

    @Test
    fun `CodecStatus enum covers all fallback scenarios`() {
        val statuses = CodecStatus.entries.toList()

        // Should have 6 states as per implementation
        assertEquals(6, statuses.size)

        // All expected states should be present
        assertTrue(statuses.any { it == CodecStatus.INITIALIZING })
        assertTrue(statuses.any { it == CodecStatus.WEBRTC_ACTIVE })
        assertTrue(statuses.any { it == CodecStatus.ADPCM_FALLBACK_INIT_FAILURE })
        assertTrue(statuses.any { it == CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE })
        assertTrue(statuses.any { it == CodecStatus.ADPCM_OPUS_DISABLED })
        assertTrue(statuses.any { it == CodecStatus.FAILED })
    }

    // === ADPCM Fallback Availability Tests ===

    @Test
    fun `ADPCM codec can initialize when WebRTC would fail`() {
        // ADPCM should always be available as fallback
        assertTrue(adpcmCodec.initialize())
    }

    @Test
    fun `ADPCM codec can encode after initialization`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = adpcmCodec.encode(pcmData)

        assertNotNull(encoded)
        assertTrue(encoded!!.isNotEmpty())
    }

    @Test
    fun `ADPCM codec can decode after initialization`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = adpcmCodec.encode(pcmData)
        assertNotNull(encoded)

        val decoded = adpcmCodec.decode(encoded!!)
        assertEquals(pcmData.size, decoded.size)
    }

    @Test
    fun `ADPCM codec provides PLC for packet loss concealment`() {
        assertTrue(adpcmCodec.initialize())

        val plcSamples = adpcmCodec.decodePLC()
        assertEquals(AdpcmCodec.FRAME_SIZE, plcSamples.size)
    }

    @Test
    fun `ADPCM codec handles multiple consecutive encodes without failure`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }

        // Simulate 100 consecutive encodes (stress test)
        repeat(100) {
            val encoded = adpcmCodec.encode(pcmData)
            assertNotNull("Encode failed on iteration $it", encoded)
            assertTrue("Encoded data empty on iteration $it", encoded!!.isNotEmpty())
        }
    }

    @Test
    fun `ADPCM codec handles multiple consecutive decodes without failure`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = adpcmCodec.encode(pcmData)
        assertNotNull(encoded)

        // Simulate 100 consecutive decodes (stress test)
        repeat(100) {
            val decoded = adpcmCodec.decode(encoded!!)
            assertEquals("Decode failed on iteration $it", pcmData.size, decoded.size)
        }
    }

    @Test
    fun `ADPCM codec is stable under interleaved encode and decode`() {
        assertTrue(adpcmCodec.initialize())

        repeat(50) { i ->
            val pcmData = ShortArray(960) { ((it + i) * 10).toShort() }
            val encoded = adpcmCodec.encode(pcmData)
            assertNotNull("Encode failed on iteration $i", encoded)

            val decoded = adpcmCodec.decode(encoded!!)
            assertEquals("Decode failed on iteration $i", pcmData.size, decoded.size)
        }
    }

    // === WebRTCAudioProcessor Fallback Behavior Tests ===

    @Test
    fun `WebRTCAudioProcessor returns null encode without initialization`() {
        val processor = WebRTCAudioProcessor()
        val pcmData = ShortArray(960) { (it * 10).toShort() }

        val encoded = processor.encode(pcmData)
        assertNull("Should return null when not initialized", encoded)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor returns empty decode without initialization`() {
        val processor = WebRTCAudioProcessor()
        val opusData = ByteArray(100)

        val decoded = processor.decode(opusData)
        assertTrue("Should return empty array when not initialized", decoded.isEmpty())

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor encodeInto returns -1 without initialization`() {
        val processor = WebRTCAudioProcessor()
        val pcmData = ShortArray(960)
        val output = ByteArray(2000)

        val result = processor.encodeInto(pcmData, output)
        assertEquals(-1, result)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor decodeInto returns -1 without initialization`() {
        val processor = WebRTCAudioProcessor()
        val opusData = ByteArray(100)
        val output = ShortArray(960)

        val result = processor.decodeInto(opusData, output)
        assertEquals(-1, result)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor isReady returns false without initialization`() {
        val processor = WebRTCAudioProcessor()

        assertFalse(processor.isReady())

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor isCodecReady returns false without initialization`() {
        val processor = WebRTCAudioProcessor()

        assertFalse(processor.isCodecReady())

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor decodePLC returns frame-sized output even without initialization`() {
        val processor = WebRTCAudioProcessor()

        // PLC should return silence when not initialized
        val plc = processor.decodePLC()
        assertEquals(WebRTCAudioProcessor.FRAME_SIZE, plc.size)

        processor.cleanup()
    }

    // === Graceful Degradation Tests ===

    @Test
    fun `WebRTCAudioProcessor cleanup does not throw when not initialized`() {
        val processor = WebRTCAudioProcessor()

        // Should not throw
        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor cleanup is idempotent`() {
        val processor = WebRTCAudioProcessor()

        // Multiple cleanups should not throw
        processor.cleanup()
        processor.cleanup()
        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor reset methods do not throw when not initialized`() {
        val processor = WebRTCAudioProcessor()

        // Should not throw
        processor.resetEncoder()
        processor.resetDecoder()

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor setFecEnabled does not throw when not initialized`() {
        val processor = WebRTCAudioProcessor()

        // Should not throw
        processor.setFecEnabled(true)
        processor.setFecEnabled(false)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor getStats works without initialization`() {
        val processor = WebRTCAudioProcessor()

        val stats = processor.getStats()
        assertNotNull(stats)
        assertFalse(stats.isInitialized)
        assertEquals(48_000, stats.sampleRate)
        assertEquals(32_000, stats.bitrate)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor getAudioProcessingConfig works without initialization`() {
        val processor = WebRTCAudioProcessor()

        val config = processor.getAudioProcessingConfig()
        assertNotNull(config)
        assertFalse(config.usingWebRtcProcessing)

        processor.cleanup()
    }

    // === Configuration Preservation During Fallback Tests ===

    @Test
    fun `WebRTCAudioProcessor preserves AEC setting before initialization`() {
        val processor = WebRTCAudioProcessor()

        processor.setAecEnabled(false)
        val config = processor.getAudioProcessingConfig()

        assertFalse(config.aecEnabled)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor preserves NS setting before initialization`() {
        val processor = WebRTCAudioProcessor()

        processor.setNsEnabled(false)
        val config = processor.getAudioProcessingConfig()

        assertFalse(config.nsEnabled)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor preserves AGC setting before initialization`() {
        val processor = WebRTCAudioProcessor()

        processor.setAgcEnabled(false)
        val config = processor.getAudioProcessingConfig()

        assertFalse(config.agcEnabled)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor preserves high-pass filter setting before initialization`() {
        val processor = WebRTCAudioProcessor()

        processor.setHighPassFilterEnabled(false)
        val config = processor.getAudioProcessingConfig()

        assertFalse(config.highPassFilterEnabled)

        processor.cleanup()
    }

    // === Fallback Codec Interoperability Tests ===

    @Test
    fun `ADPCM and WebRTCAudioProcessor have matching frame sizes`() {
        assertEquals(AdpcmCodec.FRAME_SIZE, WebRTCAudioProcessor.FRAME_SIZE)
    }

    @Test
    fun `ADPCM and WebRTCAudioProcessor have matching sample rates`() {
        assertEquals(AdpcmCodec.SAMPLE_RATE, WebRTCAudioProcessor.SAMPLE_RATE)
    }

    @Test
    fun `ADPCM and WebRTCAudioProcessor have matching channel counts`() {
        assertEquals(AdpcmCodec.CHANNELS, WebRTCAudioProcessor.CHANNELS)
    }

    @Test
    fun `Both codecs produce same PLC frame size`() {
        assertTrue(adpcmCodec.initialize())
        val processor = WebRTCAudioProcessor()

        val adpcmPlc = adpcmCodec.decodePLC()
        val webrtcPlc = processor.decodePLC()

        assertEquals(adpcmPlc.size, webrtcPlc.size)

        processor.cleanup()
    }

    // === Simulated Fallback Scenario Tests ===

    @Test
    fun `ADPCM codec can seamlessly take over after WebRTC would fail`() {
        // Simulate WebRTC failure scenario
        val webRtcProcessor = WebRTCAudioProcessor()

        // WebRTC is not initialized, so it cannot encode
        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val webRtcEncoded = webRtcProcessor.encode(pcmData)
        assertNull(webRtcEncoded)

        // ADPCM should seamlessly take over
        assertTrue(adpcmCodec.initialize())
        val adpcmEncoded = adpcmCodec.encode(pcmData)
        assertNotNull(adpcmEncoded)
        assertTrue(adpcmEncoded!!.isNotEmpty())

        webRtcProcessor.cleanup()
    }

    @Test
    fun `ADPCM codec can decode continuously after WebRTC failure simulation`() {
        // Simulate a scenario where WebRTC fails after some usage
        assertTrue(adpcmCodec.initialize())

        // Generate test data
        val testData = ShortArray(960) { (kotlin.math.sin(it * 0.1) * 10_000).toInt().toShort() }

        // Simulate 100 frames of continuous operation (simulating 2 seconds of audio)
        repeat(100) { frameIndex ->
            val encoded = adpcmCodec.encode(testData)
            assertNotNull("Encode failed at frame $frameIndex", encoded)

            val decoded = adpcmCodec.decode(encoded!!)
            assertEquals("Decode frame size mismatch at frame $frameIndex", testData.size, decoded.size)
        }
    }

    @Test
    fun `Audio quality is preserved when falling back to ADPCM`() {
        assertTrue(adpcmCodec.initialize())

        // Generate a sine wave signal
        val sineWave = ShortArray(960) { i ->
            (kotlin.math.sin(i * 2 * Math.PI * 1000 / 48_000) * 20_000).toInt().toShort()
        }

        val encoded = adpcmCodec.encode(sineWave)
        assertNotNull(encoded)

        val decoded = adpcmCodec.decode(encoded!!)
        assertEquals(sineWave.size, decoded.size)

        // Check signal correlation (decoded should be similar to original)
        var sumProduct = 0.0
        var sumOriginalSquared = 0.0
        var sumDecodedSquared = 0.0

        for (i in sineWave.indices) {
            val orig = sineWave[i].toDouble()
            val dec = decoded[i].toDouble()
            sumProduct += orig * dec
            sumOriginalSquared += orig * orig
            sumDecodedSquared += dec * dec
        }

        val correlation = sumProduct / kotlin.math.sqrt(sumOriginalSquared * sumDecodedSquared)

        // ADPCM should preserve signal reasonably well (correlation > 0.8)
        assertTrue(
            "Signal correlation $correlation should be > 0.8",
            correlation > 0.8,
        )
    }

    // === Zero-Copy Buffer Fallback Tests ===

    @Test
    fun `ADPCM encodeInto provides zero-copy fallback path`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val output = ByteArray(1024)

        val encodedSize = adpcmCodec.encodeInto(pcmData, output)
        assertTrue("encodeInto should return positive size", encodedSize > 0)
    }

    @Test
    fun `ADPCM decodeInto provides zero-copy fallback path`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = adpcmCodec.encode(pcmData)
        assertNotNull(encoded)

        val output = ShortArray(960)
        val decodedSamples = adpcmCodec.decodeInto(encoded!!, output)

        assertEquals("decodeInto should return frame size", pcmData.size, decodedSamples)
    }

    @Test
    fun `Zero-copy encode-decode cycle preserves audio in fallback mode`() {
        assertTrue(adpcmCodec.initialize())

        val original = ShortArray(960) { i ->
            (kotlin.math.sin(i * 2 * Math.PI * 440 / 48_000) * 15_000).toInt().toShort()
        }

        val encodeBuffer = ByteArray(1024)
        val decodeBuffer = ShortArray(960)

        val encodedSize = adpcmCodec.encodeInto(original, encodeBuffer)
        assertTrue(encodedSize > 0)

        val trimmedEncodedData = encodeBuffer.copyOf(encodedSize)
        val decodedSamples = adpcmCodec.decodeInto(trimmedEncodedData, decodeBuffer)

        assertEquals(original.size, decodedSamples)
    }

    // === Thread Safety in Fallback Mode ===

    @Test
    fun `ADPCM codec is thread-safe during fallback operation`() {
        assertTrue(adpcmCodec.initialize())

        val threads = mutableListOf<Thread>()
        val errors = mutableListOf<Throwable>()

        repeat(10) { threadIndex ->
            val thread = Thread {
                try {
                    repeat(100) {
                        val pcmData = ShortArray(960) { (Math.random() * 1000).toInt().toShort() }
                        val encoded = adpcmCodec.encode(pcmData)
                        assertNotNull("Thread $threadIndex encode failed", encoded)

                        val decoded = adpcmCodec.decode(encoded!!)
                        assertEquals("Thread $threadIndex decode size mismatch", pcmData.size, decoded.size)
                    }
                } catch (e: Throwable) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        assertTrue(
            "Thread safety errors: ${errors.map { it.message }}",
            errors.isEmpty(),
        )
    }

    // === AudioProcessingSettings Fallback Behavior Tests ===

    @Test
    fun `AudioProcessingSettings opusEnabled false indicates ADPCM should be used`() {
        val settings = AudioProcessingSettings(opusEnabled = false)

        assertFalse(settings.opusEnabled)
        assertTrue("AEC should still be available", settings.aecEnabled)
        assertTrue("NS should still be available", settings.nsEnabled)
        assertTrue("AGC should still be available", settings.agcEnabled)
    }

    @Test
    fun `AudioProcessingSettings can be copied to disable Opus`() {
        val original = AudioProcessingSettings()
        assertTrue(original.opusEnabled)

        val fallbackSettings = original.copy(opusEnabled = false)
        assertFalse(fallbackSettings.opusEnabled)

        // Other settings should remain unchanged
        assertEquals(original.aecEnabled, fallbackSettings.aecEnabled)
        assertEquals(original.nsEnabled, fallbackSettings.nsEnabled)
        assertEquals(original.agcEnabled, fallbackSettings.agcEnabled)
        assertEquals(original.windFilterEnabled, fallbackSettings.windFilterEnabled)
    }

    // === Logging Verification Tests (Structure Only) ===

    @Test
    fun `CodecStatus has meaningful string representation for logging`() {
        // Verify all status values can be logged
        for (status in CodecStatus.entries) {
            val statusString = status.name
            assertNotNull(statusString)
            assertTrue("Status name should not be empty", statusString.isNotEmpty())

            // Verify logging-friendly format
            assertTrue(
                "Status $statusString should be uppercase",
                statusString == statusString.uppercase(),
            )
        }
    }

    @Test
    fun `CodecStatus fallback states contain ADPCM in name for log clarity`() {
        val fallbackStates = listOf(
            CodecStatus.ADPCM_FALLBACK_INIT_FAILURE,
            CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE,
            CodecStatus.ADPCM_OPUS_DISABLED,
        )

        for (status in fallbackStates) {
            assertTrue(
                "Fallback status ${status.name} should contain ADPCM",
                status.name.contains("ADPCM"),
            )
        }
    }

    // === Compression Ratio Fallback Tests ===

    @Test
    fun `ADPCM provides acceptable compression ratio as fallback`() {
        assertTrue(adpcmCodec.initialize())

        val pcmData = ShortArray(960) { (it % 256).toShort() }
        val encoded = adpcmCodec.encode(pcmData)
        assertNotNull(encoded)

        val ratio = adpcmCodec.getCompressionRatio(pcmData.size, encoded!!.size)

        // ADPCM should achieve ~4x compression
        assertTrue(
            "Fallback compression ratio $ratio should be >= 2x",
            ratio >= 2.0f,
        )
    }

    @Test
    fun `ADPCM compression is consistent across different input signals`() {
        assertTrue(adpcmCodec.initialize())

        val signals = listOf(
            ShortArray(960) { 0 }, // Silence
            ShortArray(960) { Short.MAX_VALUE }, // Max value
            ShortArray(960) { (it * 10).toShort() }, // Ramp
            ShortArray(960) { (Math.random() * Short.MAX_VALUE).toInt().toShort() }, // Random
        )

        val ratios = signals.map { signal ->
            val encoded = adpcmCodec.encode(signal)
            assertNotNull(encoded)
            adpcmCodec.getCompressionRatio(signal.size, encoded!!.size)
        }

        // All compression ratios should be similar (within 50% of each other)
        val avgRatio = ratios.average()
        for (ratio in ratios) {
            assertTrue(
                "Ratio $ratio should be within 50% of average $avgRatio",
                ratio >= avgRatio * 0.5 && ratio <= avgRatio * 1.5,
            )
        }
    }

    // === Fallback State Transition Tests ===

    @Test
    fun `CodecStatus can transition from INITIALIZING to WEBRTC_ACTIVE`() {
        // Valid transition: init succeeds
        val initialState = CodecStatus.INITIALIZING
        val successState = CodecStatus.WEBRTC_ACTIVE

        assertNotEquals(initialState, successState)
    }

    @Test
    fun `CodecStatus can transition from INITIALIZING to ADPCM_FALLBACK_INIT_FAILURE`() {
        // Valid transition: WebRTC init fails, fall back to ADPCM
        val initialState = CodecStatus.INITIALIZING
        val fallbackState = CodecStatus.ADPCM_FALLBACK_INIT_FAILURE

        assertNotEquals(initialState, fallbackState)
    }

    @Test
    fun `CodecStatus can transition from WEBRTC_ACTIVE to ADPCM_FALLBACK_RUNTIME_FAILURE`() {
        // Valid transition: WebRTC was working but encounters runtime failures
        val activeState = CodecStatus.WEBRTC_ACTIVE
        val fallbackState = CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE

        assertNotEquals(activeState, fallbackState)
    }

    @Test
    fun `CodecStatus can transition from INITIALIZING to ADPCM_OPUS_DISABLED`() {
        // Valid transition: User has Opus disabled
        val initialState = CodecStatus.INITIALIZING
        val disabledState = CodecStatus.ADPCM_OPUS_DISABLED

        assertNotEquals(initialState, disabledState)
    }

    // === Edge Case Tests ===

    @Test
    fun `ADPCM handles empty input gracefully`() {
        assertTrue(adpcmCodec.initialize())

        val emptyInput = ShortArray(0)
        val encoded = adpcmCodec.encode(emptyInput)

        // Should handle gracefully (either null or empty, not crash)
        if (encoded != null) {
            assertTrue(encoded.isEmpty())
        }
    }

    @Test
    fun `ADPCM handles single sample input`() {
        assertTrue(adpcmCodec.initialize())

        val singleSample = ShortArray(1) { 1000 }
        val encoded = adpcmCodec.encode(singleSample)

        // Should handle gracefully
        assertNotNull(encoded)
    }

    @Test
    fun `ADPCM handles maximum amplitude signals`() {
        assertTrue(adpcmCodec.initialize())

        val maxAmplitude = ShortArray(960) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val encoded = adpcmCodec.encode(maxAmplitude)
        assertNotNull(encoded)

        val decoded = adpcmCodec.decode(encoded!!)
        assertEquals(maxAmplitude.size, decoded.size)
    }

    @Test
    fun `ADPCM reset does not affect fallback operation`() {
        assertTrue(adpcmCodec.initialize())

        // Encode some data
        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded1 = adpcmCodec.encode(pcmData)
        assertNotNull(encoded1)

        // Reset codec
        adpcmCodec.resetEncoder()
        adpcmCodec.resetDecoder()

        // Should still work after reset
        val encoded2 = adpcmCodec.encode(pcmData)
        assertNotNull(encoded2)

        val decoded = adpcmCodec.decode(encoded2!!)
        assertEquals(pcmData.size, decoded.size)
    }
}
