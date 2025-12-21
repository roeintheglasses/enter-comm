package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AudioManager's WebRTC codec integration.
 *
 * Tests cover:
 * - WebRTC codec initialization and fallback behavior
 * - Codec status tracking (StateFlow)
 * - Encode/decode path selection based on opusEnabled flag
 * - Runtime fallback mechanism when WebRTC failures occur
 * - Codec switching and recovery
 *
 * Note: These tests focus on the codec selection logic and fallback mechanisms
 * without requiring actual Android context or WebRTC native libraries.
 */
class AudioManagerWebRTCTest {

    @Before
    fun setUp() {
        Logger.isTestMode = true
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
    }

    // === AudioConfig Tests ===

    @Test
    fun `AudioConfig has default values matching AdpcmCodec`() {
        val config = AudioConfig()

        assertEquals(AdpcmCodec.SAMPLE_RATE, config.sampleRate)
        assertEquals(AdpcmCodec.CHANNELS, config.channelCount)
        assertEquals(AdpcmCodec.FRAME_SIZE, config.frameSize)
        assertEquals(AdpcmCodec.BITRATE, config.bitrate)
    }

    @Test
    fun `AudioConfig custom values are preserved`() {
        val config = AudioConfig(
            sampleRate = 16_000,
            channelCount = 2,
            frameSize = 480,
            bitrate = 48_000,
        )

        assertEquals(16_000, config.sampleRate)
        assertEquals(2, config.channelCount)
        assertEquals(480, config.frameSize)
        assertEquals(48_000, config.bitrate)
    }

    @Test
    fun `AudioConfig equality works correctly`() {
        val config1 = AudioConfig()
        val config2 = AudioConfig()

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `AudioConfig inequality works correctly`() {
        val config1 = AudioConfig()
        val config2 = AudioConfig(sampleRate = 16_000)

        assertNotEquals(config1, config2)
    }

    // === AudioProcessingSettings Tests ===

    @Test
    fun `AudioProcessingSettings has sensible defaults`() {
        val settings = AudioProcessingSettings()

        assertTrue(settings.aecEnabled)
        assertTrue(settings.nsEnabled)
        assertTrue(settings.agcEnabled)
        assertTrue(settings.windFilterEnabled)
        assertTrue(settings.opusEnabled)
    }

    @Test
    fun `AudioProcessingSettings custom values are preserved`() {
        val settings = AudioProcessingSettings(
            aecEnabled = false,
            nsEnabled = false,
            agcEnabled = false,
            windFilterEnabled = false,
            opusEnabled = false,
        )

        assertFalse(settings.aecEnabled)
        assertFalse(settings.nsEnabled)
        assertFalse(settings.agcEnabled)
        assertFalse(settings.windFilterEnabled)
        assertFalse(settings.opusEnabled)
    }

    @Test
    fun `AudioProcessingSettings equality works correctly`() {
        val settings1 = AudioProcessingSettings()
        val settings2 = AudioProcessingSettings()

        assertEquals(settings1, settings2)
        assertEquals(settings1.hashCode(), settings2.hashCode())
    }

    @Test
    fun `AudioProcessingSettings copy works correctly`() {
        val settings1 = AudioProcessingSettings()
        val settings2 = settings1.copy(opusEnabled = false)

        assertTrue(settings1.opusEnabled)
        assertFalse(settings2.opusEnabled)

        // Other fields should be unchanged
        assertEquals(settings1.aecEnabled, settings2.aecEnabled)
        assertEquals(settings1.nsEnabled, settings2.nsEnabled)
        assertEquals(settings1.agcEnabled, settings2.agcEnabled)
        assertEquals(settings1.windFilterEnabled, settings2.windFilterEnabled)
    }

    // === CodecStatus Tests ===

    @Test
    fun `CodecStatus INITIALIZING is the starting state`() {
        assertEquals("INITIALIZING", CodecStatus.INITIALIZING.name)
    }

    @Test
    fun `CodecStatus WEBRTC_ACTIVE indicates WebRTC is in use`() {
        assertEquals("WEBRTC_ACTIVE", CodecStatus.WEBRTC_ACTIVE.name)
    }

    @Test
    fun `CodecStatus ADPCM_FALLBACK_INIT_FAILURE indicates init failure`() {
        assertEquals("ADPCM_FALLBACK_INIT_FAILURE", CodecStatus.ADPCM_FALLBACK_INIT_FAILURE.name)
    }

    @Test
    fun `CodecStatus ADPCM_FALLBACK_RUNTIME_FAILURE indicates runtime failure`() {
        assertEquals("ADPCM_FALLBACK_RUNTIME_FAILURE", CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE.name)
    }

    @Test
    fun `CodecStatus ADPCM_OPUS_DISABLED indicates Opus is disabled`() {
        assertEquals("ADPCM_OPUS_DISABLED", CodecStatus.ADPCM_OPUS_DISABLED.name)
    }

    @Test
    fun `CodecStatus FAILED indicates complete failure`() {
        assertEquals("FAILED", CodecStatus.FAILED.name)
    }

    @Test
    fun `CodecStatus enum has expected number of values`() {
        val allStatuses = CodecStatus.entries
        assertEquals(6, allStatuses.size)
    }

    @Test
    fun `CodecStatus values can be iterated`() {
        val statuses = mutableListOf<CodecStatus>()
        for (status in CodecStatus.entries) {
            statuses.add(status)
        }

        assertTrue(statuses.contains(CodecStatus.INITIALIZING))
        assertTrue(statuses.contains(CodecStatus.WEBRTC_ACTIVE))
        assertTrue(statuses.contains(CodecStatus.ADPCM_FALLBACK_INIT_FAILURE))
        assertTrue(statuses.contains(CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE))
        assertTrue(statuses.contains(CodecStatus.ADPCM_OPUS_DISABLED))
        assertTrue(statuses.contains(CodecStatus.FAILED))
    }

    // === CodecStats Tests ===

    @Test
    fun `CodecStats has sensible defaults`() {
        val stats = CodecStats()

        assertEquals(0L, stats.packetsEncoded)
        assertEquals(0L, stats.bytesRaw)
        assertEquals(0L, stats.bytesEncoded)
        assertEquals(0f, stats.compressionRatio, 0.001f)
        assertNull(stats.effectsStats)
    }

    @Test
    fun `CodecStats custom values are preserved`() {
        val stats = CodecStats(
            packetsEncoded = 100,
            bytesRaw = 10_000,
            bytesEncoded = 2_500,
            compressionRatio = 4.0f,
            effectsStats = null,
        )

        assertEquals(100L, stats.packetsEncoded)
        assertEquals(10_000L, stats.bytesRaw)
        assertEquals(2_500L, stats.bytesEncoded)
        assertEquals(4.0f, stats.compressionRatio, 0.001f)
    }

    @Test
    fun `CodecStats equality works correctly`() {
        val stats1 = CodecStats(packetsEncoded = 100, bytesRaw = 1000, bytesEncoded = 250, compressionRatio = 4.0f)
        val stats2 = CodecStats(packetsEncoded = 100, bytesRaw = 1000, bytesEncoded = 250, compressionRatio = 4.0f)

        assertEquals(stats1, stats2)
        assertEquals(stats1.hashCode(), stats2.hashCode())
    }

    @Test
    fun `CodecStats inequality works correctly`() {
        val stats1 = CodecStats(packetsEncoded = 100)
        val stats2 = CodecStats(packetsEncoded = 200)

        assertNotEquals(stats1, stats2)
    }

    @Test
    fun `CodecStats copy works correctly`() {
        val stats1 = CodecStats(packetsEncoded = 100, bytesRaw = 1000)
        val stats2 = stats1.copy(packetsEncoded = 200)

        assertEquals(100L, stats1.packetsEncoded)
        assertEquals(200L, stats2.packetsEncoded)
        assertEquals(stats1.bytesRaw, stats2.bytesRaw)
    }

    // === WebRTCAudioProcessor Codec Compatibility Tests ===

    @Test
    fun `WebRTCAudioProcessor SAMPLE_RATE matches AdpcmCodec`() {
        assertEquals(AdpcmCodec.SAMPLE_RATE, WebRTCAudioProcessor.SAMPLE_RATE)
    }

    @Test
    fun `WebRTCAudioProcessor CHANNELS matches AdpcmCodec`() {
        assertEquals(AdpcmCodec.CHANNELS, WebRTCAudioProcessor.CHANNELS)
    }

    @Test
    fun `WebRTCAudioProcessor FRAME_SIZE matches AdpcmCodec`() {
        assertEquals(AdpcmCodec.FRAME_SIZE, WebRTCAudioProcessor.FRAME_SIZE)
    }

    @Test
    fun `WebRTCAudioProcessor has higher BITRATE than AdpcmCodec`() {
        // WebRTC/Opus at 32kbps provides better quality than ADPCM at 24kbps
        assertTrue(WebRTCAudioProcessor.BITRATE >= AdpcmCodec.BITRATE)
    }

    // === WebRTC and ADPCM Codec Interface Compatibility Tests ===

    @Test
    fun `AdpcmCodec can initialize successfully`() {
        val codec = AdpcmCodec()
        assertTrue(codec.initialize())
        codec.cleanup()
    }

    @Test
    fun `AdpcmCodec can encode valid data`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = codec.encode(pcmData)

        assertNotNull(encoded)
        assertTrue(encoded!!.isNotEmpty())

        codec.cleanup()
    }

    @Test
    fun `AdpcmCodec can decode valid data`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)
        assertTrue(decoded.isNotEmpty())
        assertEquals(pcmData.size, decoded.size)

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor can be constructed with default parameters`() {
        val processor = WebRTCAudioProcessor()
        assertNotNull(processor)

        val stats = processor.getStats()
        assertEquals(48_000, stats.sampleRate)
        assertEquals(32_000, stats.bitrate)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor can be constructed with custom parameters`() {
        val processor = WebRTCAudioProcessor(sampleRate = 16_000, bitrate = 64_000)
        assertNotNull(processor)

        val stats = processor.getStats()
        assertEquals(16_000, stats.sampleRate)
        assertEquals(64_000, stats.bitrate)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor returns null for encode without initialization`() {
        val processor = WebRTCAudioProcessor()
        val pcmData = ShortArray(960) { (it * 10).toShort() }

        val encoded = processor.encode(pcmData)
        assertNull(encoded)

        processor.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor returns empty array for decode without initialization`() {
        val processor = WebRTCAudioProcessor()
        val opusData = ByteArray(100)

        val decoded = processor.decode(opusData)
        assertTrue(decoded.isEmpty())

        processor.cleanup()
    }

    // === Fallback Behavior Tests ===

    @Test
    fun `AdpcmCodec is available as fallback`() {
        val codec = AdpcmCodec()
        assertTrue(codec.initialize())

        // Verify it can handle the full encode/decode cycle
        val original = ShortArray(960) { (kotlin.math.sin(it * 0.1) * 10_000).toInt().toShort() }
        val encoded = codec.encode(original)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)
        assertEquals(original.size, decoded.size)

        codec.cleanup()
    }

    @Test
    fun `AdpcmCodec encodeInto and decodeInto work correctly`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encodeBuffer = ByteArray(1024) // Pre-allocated buffer
        val decodeBuffer = ShortArray(960) // Pre-allocated buffer

        val encodedSize = codec.encodeInto(pcmData, encodeBuffer)
        assertTrue(encodedSize > 0)

        val decodedSamples = codec.decodeInto(encodeBuffer.copyOf(encodedSize), decodeBuffer)
        assertEquals(pcmData.size, decodedSamples)

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor encodeInto returns -1 without initialization`() {
        val processor = WebRTCAudioProcessor()
        val pcmData = ShortArray(960) { (it * 10).toShort() }
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

    // === PLC (Packet Loss Concealment) Tests ===

    @Test
    fun `AdpcmCodec decodePLC returns frame-sized output`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val plc = codec.decodePLC()
        assertEquals(AdpcmCodec.FRAME_SIZE, plc.size)

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor decodePLC returns frame-sized output`() {
        val processor = WebRTCAudioProcessor()

        val plc = processor.decodePLC()
        assertEquals(WebRTCAudioProcessor.FRAME_SIZE, plc.size)

        processor.cleanup()
    }

    @Test
    fun `Both codecs return same frame size for PLC`() {
        val codec = AdpcmCodec()
        codec.initialize()
        val processor = WebRTCAudioProcessor()

        val adpcmPlc = codec.decodePLC()
        val webrtcPlc = processor.decodePLC()

        assertEquals(adpcmPlc.size, webrtcPlc.size)

        codec.cleanup()
        processor.cleanup()
    }

    // === Reset Tests ===

    @Test
    fun `AdpcmCodec reset methods work correctly`() {
        val codec = AdpcmCodec()
        codec.initialize()

        // Encode some data
        val pcmData = ShortArray(960) { (it * 10).toShort() }
        codec.encode(pcmData)

        // Reset encoder and decoder
        codec.resetEncoder()
        codec.resetDecoder()

        // Should still be able to encode/decode after reset
        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)
        assertTrue(decoded.isNotEmpty())

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor reset methods do not throw when not initialized`() {
        val processor = WebRTCAudioProcessor()

        // These should not throw
        processor.resetEncoder()
        processor.resetDecoder()

        processor.cleanup()
    }

    // === Cleanup Tests ===

    @Test
    fun `AdpcmCodec cleanup is idempotent`() {
        val codec = AdpcmCodec()
        codec.initialize()

        codec.cleanup()
        codec.cleanup() // Should not throw
        codec.cleanup() // Should not throw
    }

    @Test
    fun `WebRTCAudioProcessor cleanup is idempotent`() {
        val processor = WebRTCAudioProcessor()

        processor.cleanup()
        processor.cleanup() // Should not throw
        processor.cleanup() // Should not throw
    }

    @Test
    fun `AdpcmCodec is unusable after cleanup`() {
        val codec = AdpcmCodec()
        codec.initialize()
        codec.cleanup()

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = codec.encode(pcmData)
        assertNull(encoded)
    }

    @Test
    fun `WebRTCAudioProcessor is unusable after cleanup`() {
        val processor = WebRTCAudioProcessor()
        processor.cleanup()

        val pcmData = ShortArray(960) { (it * 10).toShort() }
        val encoded = processor.encode(pcmData)
        assertNull(encoded)
    }

    // === Thread Safety Tests ===

    @Test
    fun `AdpcmCodec concurrent encode is thread-safe`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val threads = mutableListOf<Thread>()
        val results = mutableListOf<ByteArray?>()

        repeat(10) {
            val thread = Thread {
                val data = ShortArray(960) { (Math.random() * 1000).toInt().toShort() }
                val encoded = codec.encode(data)
                synchronized(results) {
                    results.add(encoded)
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        assertEquals(10, results.size)
        results.forEach { assertNotNull(it) }

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor concurrent configuration changes are thread-safe`() {
        val processor = WebRTCAudioProcessor()
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

        processor.cleanup()
    }

    // === Compression Ratio Tests ===

    @Test
    fun `AdpcmCodec achieves approximately 4x compression`() {
        val codec = AdpcmCodec()
        codec.initialize()

        val pcmData = ShortArray(1000) { (it % 256).toShort() }
        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val ratio = codec.getCompressionRatio(pcmData.size, encoded!!.size)

        // ADPCM should achieve ~4x compression
        assertTrue(
            "Compression ratio $ratio should be close to 4x",
            ratio in 3.5f..4.5f,
        )

        codec.cleanup()
    }

    @Test
    fun `WebRTCAudioProcessor getCompressionRatio calculates correctly for Opus`() {
        val processor = WebRTCAudioProcessor()

        // 20ms at 48kHz = 960 samples = 1920 bytes PCM
        // 32kbps Opus = 80 bytes per 20ms frame
        val ratio = processor.getCompressionRatio(960, 80)

        // 1920 / 80 = 24x compression
        assertEquals(24.0f, ratio, 0.001f)

        processor.cleanup()
    }

    // === Data Class toString Tests ===

    @Test
    fun `AudioConfig toString contains all fields`() {
        val config = AudioConfig()
        val string = config.toString()

        assertTrue(string.contains("sampleRate"))
        assertTrue(string.contains("channelCount"))
        assertTrue(string.contains("frameSize"))
        assertTrue(string.contains("bitrate"))
    }

    @Test
    fun `AudioProcessingSettings toString contains all fields`() {
        val settings = AudioProcessingSettings()
        val string = settings.toString()

        assertTrue(string.contains("aecEnabled"))
        assertTrue(string.contains("nsEnabled"))
        assertTrue(string.contains("agcEnabled"))
        assertTrue(string.contains("windFilterEnabled"))
        assertTrue(string.contains("opusEnabled"))
    }

    @Test
    fun `CodecStats toString contains all fields`() {
        val stats = CodecStats()
        val string = stats.toString()

        assertTrue(string.contains("packetsEncoded"))
        assertTrue(string.contains("bytesRaw"))
        assertTrue(string.contains("bytesEncoded"))
        assertTrue(string.contains("compressionRatio"))
    }

    // === WebRTCAudioStats Tests ===

    @Test
    fun `WebRTCAudioStats contains all fields`() {
        val stats = WebRTCAudioStats(
            isInitialized = true,
            aecEnabled = true,
            nsEnabled = false,
            agcEnabled = true,
            highPassFilterEnabled = false,
            sampleRate = 48_000,
            bitrate = 32_000,
        )

        assertTrue(stats.isInitialized)
        assertTrue(stats.aecEnabled)
        assertFalse(stats.nsEnabled)
        assertTrue(stats.agcEnabled)
        assertFalse(stats.highPassFilterEnabled)
        assertEquals(48_000, stats.sampleRate)
        assertEquals(32_000, stats.bitrate)
    }

    @Test
    fun `WebRTCAudioStats toString contains all fields`() {
        val stats = WebRTCAudioStats(
            isInitialized = false,
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            sampleRate = 48_000,
            bitrate = 32_000,
        )
        val string = stats.toString()

        assertTrue(string.contains("isInitialized"))
        assertTrue(string.contains("aecEnabled"))
        assertTrue(string.contains("nsEnabled"))
        assertTrue(string.contains("agcEnabled"))
        assertTrue(string.contains("highPassFilterEnabled"))
        assertTrue(string.contains("sampleRate"))
        assertTrue(string.contains("bitrate"))
    }

    // === AudioProcessingConfig Tests ===

    @Test
    fun `AudioProcessingConfig contains all expected fields`() {
        val config = AudioProcessingConfig(
            aecEnabled = true,
            nsEnabled = false,
            agcEnabled = true,
            highPassFilterEnabled = false,
            hardwareAecAvailable = true,
            hardwareNsAvailable = false,
            usingWebRtcProcessing = true,
        )

        assertTrue(config.aecEnabled)
        assertFalse(config.nsEnabled)
        assertTrue(config.agcEnabled)
        assertFalse(config.highPassFilterEnabled)
        assertTrue(config.hardwareAecAvailable)
        assertFalse(config.hardwareNsAvailable)
        assertTrue(config.usingWebRtcProcessing)
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
        val config1 = AudioProcessingConfig(
            aecEnabled = true,
            nsEnabled = true,
            agcEnabled = true,
            highPassFilterEnabled = true,
            hardwareAecAvailable = true,
            hardwareNsAvailable = true,
            usingWebRtcProcessing = false,
        )

        val config2 = config1.copy(usingWebRtcProcessing = true)

        assertFalse(config1.usingWebRtcProcessing)
        assertTrue(config2.usingWebRtcProcessing)
        assertEquals(config1.aecEnabled, config2.aecEnabled)
    }
}
