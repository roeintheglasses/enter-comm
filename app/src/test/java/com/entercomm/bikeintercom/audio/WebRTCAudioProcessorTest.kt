package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebRTCAudioProcessorTest {

    private lateinit var processor: WebRTCAudioProcessor

    @Before
    fun setUp() {
        Logger.isTestMode = true
        processor = WebRTCAudioProcessor()
    }

    @After
    fun tearDown() {
        processor.cleanup()
        Logger.isTestMode = false
    }

    // === Default Configuration Tests ===

    @Test
    fun `default sample rate is 48kHz`() {
        assertEquals(48_000, WebRTCAudioProcessor.SAMPLE_RATE)
    }

    @Test
    fun `default bitrate is 32kbps`() {
        assertEquals(32_000, WebRTCAudioProcessor.BITRATE)
    }

    @Test
    fun `default frame size is 960 samples (20ms at 48kHz)`() {
        assertEquals(960, WebRTCAudioProcessor.FRAME_SIZE)
    }

    @Test
    fun `default channels is mono`() {
        assertEquals(1, WebRTCAudioProcessor.CHANNELS)
    }

    @Test
    fun `opus max packet size is calculated correctly`() {
        // Header size (8 bytes) + worst case (frame size * 2)
        val expectedMax = 8 + WebRTCAudioProcessor.FRAME_SIZE * 2
        assertEquals(expectedMax, WebRTCAudioProcessor.OPUS_MAX_PACKET_SIZE)
    }

    // === Initial State Tests ===

    @Test
    fun `processor is not ready before initialization`() {
        assertFalse(processor.isReady())
    }

    @Test
    fun `AEC is enabled by default`() {
        assertTrue(processor.isAecEnabled)
    }

    @Test
    fun `NS is enabled by default`() {
        assertTrue(processor.isNsEnabled)
    }

    @Test
    fun `AGC is enabled by default`() {
        assertTrue(processor.isAgcEnabled)
    }

    @Test
    fun `high-pass filter is enabled by default`() {
        assertTrue(processor.isHighPassFilterEnabled)
    }

    // === Pre-Initialization Configuration Tests ===

    @Test
    fun `setAecEnabled stores value before initialization`() {
        assertTrue(processor.setAecEnabled(false))
        assertFalse(processor.isAecEnabled)
    }

    @Test
    fun `setNsEnabled stores value before initialization`() {
        assertTrue(processor.setNsEnabled(false))
        assertFalse(processor.isNsEnabled)
    }

    @Test
    fun `setAgcEnabled stores value before initialization`() {
        assertTrue(processor.setAgcEnabled(false))
        assertFalse(processor.isAgcEnabled)
    }

    @Test
    fun `setHighPassFilterEnabled stores value before initialization`() {
        assertTrue(processor.setHighPassFilterEnabled(false))
        assertFalse(processor.isHighPassFilterEnabled)
    }

    @Test
    fun `setAecEnabled returns true when value unchanged`() {
        assertTrue(processor.setAecEnabled(true)) // Already true by default
        assertTrue(processor.isAecEnabled)
    }

    @Test
    fun `setNsEnabled returns true when value unchanged`() {
        assertTrue(processor.setNsEnabled(true)) // Already true by default
        assertTrue(processor.isNsEnabled)
    }

    // === Encoding/Decoding Without Initialization ===

    @Test
    fun `encode returns null when not initialized`() {
        val pcmData = ShortArray(100) { it.toShort() }
        val result = processor.encode(pcmData)
        assertNull(result)
    }

    @Test
    fun `encode returns null for empty input when not initialized`() {
        val result = processor.encode(ShortArray(0))
        assertNull(result)
    }

    @Test
    fun `decode returns empty array when not initialized`() {
        val opusData = ByteArray(100)
        val result = processor.decode(opusData)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode returns empty array for small input when not initialized`() {
        val result = processor.decode(ByteArray(4)) // Less than header size
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode returns empty array for empty input when not initialized`() {
        val result = processor.decode(ByteArray(0))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `encodeInto returns -1 when not initialized`() {
        val pcmData = ShortArray(100) { it.toShort() }
        val output = ByteArray(200)
        val result = processor.encodeInto(pcmData, output)
        assertEquals(-1, result)
    }

    @Test
    fun `encodeInto returns -1 for empty input when not initialized`() {
        val output = ByteArray(200)
        val result = processor.encodeInto(ShortArray(0), output)
        assertEquals(-1, result)
    }

    @Test
    fun `decodeInto returns -1 when not initialized`() {
        val opusData = ByteArray(100)
        val output = ShortArray(100)
        val result = processor.decodeInto(opusData, output)
        assertEquals(-1, result)
    }

    @Test
    fun `decodeInto returns -1 for small input when not initialized`() {
        val output = ShortArray(100)
        val result = processor.decodeInto(ByteArray(4), output)
        assertEquals(-1, result)
    }

    // === Compression Ratio Tests ===

    @Test
    fun `getCompressionRatio calculates correctly`() {
        val pcmSamples = 1000
        val encodedBytes = 125

        val ratio = processor.getCompressionRatio(pcmSamples, encodedBytes)

        // 1000 samples * 2 bytes = 2000 bytes PCM
        // 2000 / 125 = 16x compression
        assertEquals(16.0f, ratio, 0.001f)
    }

    @Test
    fun `getCompressionRatio handles zero encoded bytes`() {
        val ratio = processor.getCompressionRatio(1000, 0)
        assertEquals(0f, ratio, 0.001f)
    }

    @Test
    fun `getCompressionRatio handles zero PCM samples`() {
        val ratio = processor.getCompressionRatio(0, 100)
        assertEquals(0f, ratio, 0.001f)
    }

    @Test
    fun `getCompressionRatio for typical Opus compression`() {
        // 20ms at 48kHz = 960 samples = 1920 bytes PCM
        // 32kbps Opus = 80 bytes per 20ms frame
        val ratio = processor.getCompressionRatio(960, 80)
        // 1920 / 80 = 24x compression
        assertEquals(24.0f, ratio, 0.001f)
    }

    // === Codec State Tests ===

    @Test
    fun `isCodecReady returns false when not initialized`() {
        assertFalse(processor.isCodecReady())
    }

    @Test
    fun `decodePLC returns frame-sized output when not initialized`() {
        val plc = processor.decodePLC()
        assertEquals(WebRTCAudioProcessor.FRAME_SIZE, plc.size)
    }

    @Test
    fun `decodePLC returns silence when not initialized`() {
        val plc = processor.decodePLC()
        for (sample in plc) {
            assertEquals(0, sample.toInt())
        }
    }

    // === Reset Tests ===

    @Test
    fun `resetEncoder does not throw when not initialized`() {
        // Should not throw even when not initialized
        processor.resetEncoder()
    }

    @Test
    fun `resetDecoder does not throw when not initialized`() {
        // Should not throw even when not initialized
        processor.resetDecoder()
    }

    @Test
    fun `setFecEnabled does not throw when not initialized`() {
        // Should not throw even when not initialized
        processor.setFecEnabled(false)
        processor.setFecEnabled(true)
    }

    // === Cleanup Tests ===

    @Test
    fun `cleanup does not throw when not initialized`() {
        processor.cleanup()
        assertFalse(processor.isReady())
    }

    @Test
    fun `double cleanup does not throw`() {
        processor.cleanup()
        processor.cleanup()
        assertFalse(processor.isReady())
    }

    @Test
    fun `shutdownFactory handles native library not loaded gracefully`() {
        // shutdownFactory calls PeerConnectionFactory.shutdownInternalTracer() which
        // requires native WebRTC libraries. In unit tests without native libs loaded,
        // this will throw UnsatisfiedLinkError which is expected behavior.
        // The method should still be callable without crashing the JVM.
        try {
            processor.shutdownFactory()
        } catch (e: UnsatisfiedLinkError) {
            // Expected when native WebRTC library is not loaded
            // The error message should reference WebRTC/PeerConnectionFactory
            assertNotNull(e.message)
        }
    }

    // === Stats Tests ===

    @Test
    fun `getStats returns correct values when not initialized`() {
        val stats = processor.getStats()

        assertFalse(stats.isInitialized)
        assertTrue(stats.aecEnabled)
        assertTrue(stats.nsEnabled)
        assertTrue(stats.agcEnabled)
        assertTrue(stats.highPassFilterEnabled)
        assertEquals(48_000, stats.sampleRate)
        assertEquals(32_000, stats.bitrate)
    }

    @Test
    fun `getStats reflects configuration changes`() {
        processor.setAecEnabled(false)
        processor.setNsEnabled(false)
        processor.setAgcEnabled(false)
        processor.setHighPassFilterEnabled(false)

        val stats = processor.getStats()

        assertFalse(stats.aecEnabled)
        assertFalse(stats.nsEnabled)
        assertFalse(stats.agcEnabled)
        assertFalse(stats.highPassFilterEnabled)
    }

    @Test
    fun `getAudioProcessingConfig returns correct values when not initialized`() {
        val config = processor.getAudioProcessingConfig()

        assertTrue(config.aecEnabled)
        assertTrue(config.nsEnabled)
        assertTrue(config.agcEnabled)
        assertTrue(config.highPassFilterEnabled)
        assertTrue(config.hardwareAecAvailable)
        assertTrue(config.hardwareNsAvailable)
        assertFalse(config.usingWebRtcProcessing) // Not initialized
    }

    @Test
    fun `getAudioProcessingConfig reflects configuration changes`() {
        processor.setAecEnabled(false)
        processor.setNsEnabled(false)

        val config = processor.getAudioProcessingConfig()

        assertFalse(config.aecEnabled)
        assertFalse(config.nsEnabled)
        assertTrue(config.agcEnabled) // Unchanged
        assertTrue(config.highPassFilterEnabled) // Unchanged
    }

    // === Update Audio Processing Tests ===

    @Test
    fun `updateAudioProcessing returns false when not initialized`() {
        val result = processor.updateAudioProcessing(
            aecEnabled = false,
            nsEnabled = false,
            agcEnabled = false,
            highPassEnabled = false,
        )
        assertFalse(result)
    }

    // === WebRTC Component Access Tests ===

    @Test
    fun `getAudioTrack returns null when not initialized`() {
        assertNull(processor.getAudioTrack())
    }

    @Test
    fun `getAudioSource returns null when not initialized`() {
        assertNull(processor.getAudioSource())
    }

    @Test
    fun `getPeerConnectionFactory returns null when not initialized`() {
        assertNull(processor.getPeerConnectionFactory())
    }

    // === Custom Sample Rate Tests ===

    @Test
    fun `custom sample rate is preserved`() {
        val customProcessor = WebRTCAudioProcessor(sampleRate = 16_000)
        val stats = customProcessor.getStats()
        assertEquals(16_000, stats.sampleRate)
        customProcessor.cleanup()
    }

    @Test
    fun `custom bitrate is preserved`() {
        val customProcessor = WebRTCAudioProcessor(bitrate = 64_000)
        val stats = customProcessor.getStats()
        assertEquals(64_000, stats.bitrate)
        customProcessor.cleanup()
    }

    // === Data Class Tests ===

    @Test
    fun `WebRTCAudioStats equality works correctly`() {
        val stats1 = WebRTCAudioStats(
            isInitialized = true,
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            sampleRate = 48_000,
            bitrate = 32_000,
        )

        val stats2 = WebRTCAudioStats(
            isInitialized = true,
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            sampleRate = 48_000,
            bitrate = 32_000,
        )

        assertEquals(stats1, stats2)
        assertEquals(stats1.hashCode(), stats2.hashCode())
    }

    @Test
    fun `WebRTCAudioStats inequality works correctly`() {
        val stats1 = processor.getStats()
        val stats2 = WebRTCAudioStats(
            isInitialized = true, // Different from stats1
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            sampleRate = 48_000,
            bitrate = 32_000,
        )

        assertNotEquals(stats1, stats2)
    }

    @Test
    fun `AudioProcessingConfig equality works correctly`() {
        val config1 = AudioProcessingConfig(
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            hardwareAecAvailable = true,
            hardwareNsAvailable = true,
            usingWebRtcProcessing = false,
        )

        val config2 = AudioProcessingConfig(
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            hardwareAecAvailable = true,
            hardwareNsAvailable = true,
            usingWebRtcProcessing = false,
        )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `AudioProcessingConfig copy works correctly`() {
        val config1 = processor.getAudioProcessingConfig()
        val config2 = config1.copy(aecEnabled = false)

        assertNotEquals(config1.aecEnabled, config2.aecEnabled)
        assertEquals(config1.nsEnabled, config2.nsEnabled)
    }

    // === Thread Safety Tests ===

    @Test
    fun `concurrent configuration changes are thread-safe`() {
        val threads = mutableListOf<Thread>()

        repeat(10) { i ->
            val thread = Thread {
                val enable = i % 2 == 0
                processor.setAecEnabled(enable)
                processor.setNsEnabled(!enable)
                processor.setAgcEnabled(enable)
                processor.setHighPassFilterEnabled(!enable)
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // Should not throw or cause inconsistent state
        val stats = processor.getStats()
        assertNotNull(stats)
    }

    @Test
    fun `concurrent cleanup is thread-safe`() {
        val threads = mutableListOf<Thread>()

        repeat(10) {
            val thread = Thread {
                processor.cleanup()
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // Should not throw
        assertFalse(processor.isReady())
    }

    @Test
    fun `concurrent encode calls are thread-safe`() {
        val threads = mutableListOf<Thread>()
        val results = mutableListOf<ByteArray?>()

        repeat(10) {
            val thread = Thread {
                val data = ShortArray(100) { (Math.random() * 1000).toInt().toShort() }
                val encoded = processor.encode(data)
                synchronized(results) {
                    results.add(encoded)
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // All should return null (not initialized), but no crashes
        assertEquals(10, results.size)
        results.forEach { assertNull(it) }
    }

    @Test
    fun `concurrent decode calls are thread-safe`() {
        val threads = mutableListOf<Thread>()
        val results = mutableListOf<ShortArray>()

        repeat(10) {
            val thread = Thread {
                val data = ByteArray(100)
                val decoded = processor.decode(data)
                synchronized(results) {
                    results.add(decoded)
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // All should return empty array (not initialized), but no crashes
        assertEquals(10, results.size)
        results.forEach { assertTrue(it.isEmpty()) }
    }

    // === Edge Cases ===

    @Test
    fun `multiple processors can coexist`() {
        val processor1 = WebRTCAudioProcessor(sampleRate = 48_000)
        val processor2 = WebRTCAudioProcessor(sampleRate = 16_000)

        assertEquals(48_000, processor1.getStats().sampleRate)
        assertEquals(16_000, processor2.getStats().sampleRate)

        processor1.cleanup()
        processor2.cleanup()
    }

    @Test
    fun `configuration changes preserve other settings`() {
        processor.setAecEnabled(false)
        assertTrue(processor.isNsEnabled) // Should be unchanged

        processor.setNsEnabled(false)
        assertFalse(processor.isAecEnabled) // Should still be false

        processor.setAgcEnabled(false)
        assertTrue(processor.isHighPassFilterEnabled) // Should be unchanged
    }

    @Test
    fun `configuration toggle multiple times works`() {
        for (i in 0 until 10) {
            val enable = i % 2 == 0
            processor.setAecEnabled(enable)
            assertEquals(enable, processor.isAecEnabled)
        }
    }
}
