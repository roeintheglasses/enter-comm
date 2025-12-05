package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class OpusCodecTest {

    private lateinit var codec: OpusCodec

    @Before
    fun setUp() {
        Logger.isTestMode = true
        codec = OpusCodec()
        codec.initialize()
    }

    @After
    fun tearDown() {
        codec.cleanup()
        Logger.isTestMode = false
    }

    // === Initialization Tests ===

    @Test
    fun `initialize returns true`() {
        val newCodec = OpusCodec()
        assertTrue(newCodec.initialize())
        newCodec.cleanup()
    }

    // === Encoding Tests ===

    @Test
    fun `encode returns null for empty input`() {
        val result = codec.encode(ShortArray(0))
        assertNull(result)
    }

    @Test
    fun `encode produces smaller output than input`() {
        val pcmData = ShortArray(1000) { (it * 100).toShort() }
        val encoded = codec.encode(pcmData)

        assertNotNull(encoded)
        // ADPCM should produce roughly 1/4 the size (4 bits per 16-bit sample)
        // Plus 4 bytes header
        val expectedMaxSize = 4 + (pcmData.size / 2) + 1
        assertTrue(
            "Encoded size should be much smaller than input",
            encoded!!.size <= expectedMaxSize
        )
    }

    @Test
    fun `encode handles single sample`() {
        val pcmData = shortArrayOf(1000)
        val encoded = codec.encode(pcmData)

        assertNotNull(encoded)
        assertTrue(encoded!!.size > 4) // At least header + 1 byte
    }

    @Test
    fun `encode handles odd number of samples`() {
        val pcmData = ShortArray(101) { it.toShort() }
        val encoded = codec.encode(pcmData)

        assertNotNull(encoded)
    }

    // === Decoding Tests ===

    @Test
    fun `decode returns empty array for too-small input`() {
        val result = codec.decode(ByteArray(2)) // Less than header size
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode returns empty array for empty input`() {
        val result = codec.decode(ByteArray(0))
        assertTrue(result.isEmpty())
    }

    // === Round-trip Tests ===

    @Test
    fun `encode then decode produces similar output`() {
        // Create a simple waveform
        val pcmData = ShortArray(200) { i ->
            (10000 * kotlin.math.sin(2 * Math.PI * i / 20)).toInt().toShort()
        }

        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)
        assertEquals(pcmData.size, decoded.size)

        // Check that decoded is reasonably close to original
        // ADPCM is lossy but should maintain general shape
        var maxError = 0
        for (i in pcmData.indices) {
            val error = abs(pcmData[i].toInt() - decoded[i].toInt())
            maxError = maxOf(maxError, error)
        }

        // Allow some error due to lossy compression
        assertTrue(
            "Maximum error $maxError should be reasonable for ADPCM",
            maxError < 5000
        )
    }

    @Test
    fun `multiple encode-decode cycles work correctly`() {
        val pcmData = ShortArray(100) { (it * 50).toShort() }

        // First cycle
        val encoded1 = codec.encode(pcmData)
        assertNotNull(encoded1)
        val decoded1 = codec.decode(encoded1!!)
        assertEquals(pcmData.size, decoded1.size)

        // Second cycle (reset codec state)
        codec.resetEncoder()
        codec.resetDecoder()

        val encoded2 = codec.encode(pcmData)
        assertNotNull(encoded2)
        val decoded2 = codec.decode(encoded2!!)
        assertEquals(pcmData.size, decoded2.size)
    }

    @Test
    fun `encode-decode preserves silence`() {
        val silence = ShortArray(100) { 0 }
        val encoded = codec.encode(silence)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)

        // All values should be very close to zero
        for (sample in decoded) {
            assertTrue(
                "Silent input should decode to near-silence",
                abs(sample.toInt()) < 100
            )
        }
    }

    @Test
    fun `encode-decode handles extreme values`() {
        val pcmData = shortArrayOf(
            Short.MAX_VALUE,
            Short.MIN_VALUE,
            0,
            Short.MAX_VALUE,
            Short.MIN_VALUE
        )

        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val decoded = codec.decode(encoded!!)
        assertEquals(pcmData.size, decoded.size)

        // Values should still be within valid range
        for (sample in decoded) {
            assertTrue(sample >= Short.MIN_VALUE)
            assertTrue(sample <= Short.MAX_VALUE)
        }
    }

    // === Compression Ratio Tests ===

    @Test
    fun `getCompressionRatio calculates correctly`() {
        val pcmSamples = 1000
        val encodedBytes = 500

        val ratio = codec.getCompressionRatio(pcmSamples, encodedBytes)

        // 1000 samples * 2 bytes = 2000 bytes PCM
        // 2000 / 500 = 4x compression
        assertEquals(4.0f, ratio, 0.001f)
    }

    @Test
    fun `getCompressionRatio handles zero encoded bytes`() {
        val ratio = codec.getCompressionRatio(1000, 0)
        assertEquals(0f, ratio, 0.001f)
    }

    @Test
    fun `actual compression ratio is approximately 4x`() {
        val pcmData = ShortArray(1000) { (it % 256).toShort() }
        val encoded = codec.encode(pcmData)
        assertNotNull(encoded)

        val ratio = codec.getCompressionRatio(pcmData.size, encoded!!.size)

        // ADPCM should achieve ~4x compression (with header overhead slightly less)
        assertTrue(
            "Compression ratio $ratio should be close to 4x",
            ratio in 3.5f..4.5f
        )
    }

    // === Reset Tests ===

    @Test
    fun `resetEncoder resets state`() {
        // Encode something to change state
        codec.encode(ShortArray(100) { 1000 })

        codec.resetEncoder()

        // Encode same data - should produce consistent result
        val data = ShortArray(50) { 500 }
        val encoded1 = codec.encode(data)
        codec.resetEncoder()
        val encoded2 = codec.encode(data)

        assertArrayEquals(encoded1, encoded2)
    }

    @Test
    fun `resetDecoder resets state`() {
        val encoded = codec.encode(ShortArray(100) { 1000 })!!

        // Decode once
        codec.decode(encoded)

        // Reset and decode again - should produce same result
        codec.resetDecoder()
        val decoded1 = codec.decode(encoded)

        codec.resetDecoder()
        val decoded2 = codec.decode(encoded)

        assertArrayEquals(decoded1, decoded2)
    }

    // === Packet Loss Concealment Tests ===

    @Test
    fun `decodePLC returns frame-sized output`() {
        val plc = codec.decodePLC()
        assertEquals(OpusCodec.FRAME_SIZE, plc.size)
    }

    @Test
    fun `decodePLC generates fading output`() {
        // First decode some audio to set predictor
        val encoded = codec.encode(ShortArray(100) { 10000 })!!
        codec.decode(encoded)

        // Now generate PLC
        val plc = codec.decodePLC()

        // Check that it fades towards zero
        val firstAbs = abs(plc[0].toInt())
        val lastAbs = abs(plc[plc.size - 1].toInt())
        assertTrue("PLC should fade towards zero", lastAbs <= firstAbs)
    }

    // === Thread Safety Tests ===

    @Test
    fun `concurrent encode operations are thread-safe`() {
        val threads = mutableListOf<Thread>()
        val results = mutableListOf<ByteArray?>()

        repeat(10) {
            val thread = Thread {
                val data = ShortArray(100) { (Math.random() * 1000).toInt().toShort() }
                val encoded = codec.encode(data)
                synchronized(results) {
                    results.add(encoded)
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // All operations should succeed
        assertEquals(10, results.size)
        results.forEach { assertNotNull(it) }
    }

    @Test
    fun `concurrent decode operations are thread-safe`() {
        val encoded = codec.encode(ShortArray(100) { 500 })!!

        val threads = mutableListOf<Thread>()
        val results = mutableListOf<ShortArray>()

        repeat(10) {
            val thread = Thread {
                val decoded = codec.decode(encoded)
                synchronized(results) {
                    results.add(decoded)
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // All operations should succeed
        assertEquals(10, results.size)
        results.forEach { assertTrue(it.isNotEmpty()) }
    }

    // === Edge Cases ===

    @Test
    fun `encode handles large input`() {
        val largeInput = ShortArray(48000) { (it % Short.MAX_VALUE).toShort() } // 1 second at 48kHz
        val encoded = codec.encode(largeInput)

        assertNotNull(encoded)
        assertTrue(encoded!!.size > 0)
    }

    @Test
    fun `cleanup prevents further operations`() {
        codec.cleanup()

        val result = codec.encode(ShortArray(10) { 100 })
        assertNull(result)
    }

    @Test
    fun `re-initialization after cleanup works`() {
        codec.cleanup()
        assertTrue(codec.initialize())

        val result = codec.encode(ShortArray(10) { 100 })
        assertNotNull(result)
    }
}
