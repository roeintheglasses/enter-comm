package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio codec using IMA ADPCM (Adaptive Differential PCM).
 *
 * Provides ~4x compression ratio (16-bit PCM -> 4-bit ADPCM) with good voice quality.
 * This is a self-contained implementation with no external dependencies.
 *
 * Benefits:
 * - 4x bandwidth reduction (576KB/s -> 144KB/s per stream at 48kHz)
 * - Very low latency
 * - Simple, fast encoding/decoding
 * - No external dependencies
 * - Works on all Android devices
 *
 * For even better compression (10-20x), consider WebRTC/Opus when available.
 */
class AdpcmCodec(
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = CHANNELS,
    private val frameSize: Int = FRAME_SIZE,
    private val bitrate: Int = BITRATE,
) {
    companion object {
        // Audio configuration
        const val SAMPLE_RATE = 48000 // 48kHz
        const val CHANNELS = 1 // Mono
        const val FRAME_SIZE = 960 // 20ms at 48kHz
        const val BITRATE = 24000 // Target bitrate (actual varies with ADPCM)

        // IMA ADPCM step table
        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )

        // Index adjustment table
        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8,
        )

        // Header size: predictor (2 bytes) + step index (1 byte) + reserved (1 byte)
        private const val HEADER_SIZE = 4
    }

    // Encoder state
    private var encoderPredictor = 0
    private var encoderStepIndex = 0

    // Decoder state
    private var decoderPredictor = 0
    private var decoderStepIndex = 0

    private val encodeLock = Any()
    private val decodeLock = Any()

    @Volatile
    private var isInitialized = false

    /**
     * Initialize the codec.
     */
    fun initialize(): Boolean {
        return try {
            synchronized(encodeLock) {
                encoderPredictor = 0
                encoderStepIndex = 0
            }
            synchronized(decodeLock) {
                decoderPredictor = 0
                decoderStepIndex = 0
            }
            isInitialized = true
            logD { "ADPCM codec initialized: ${sampleRate}Hz, ~4x compression" }
            true
        } catch (e: Exception) {
            logE({ "Failed to initialize codec" }, e)
            false
        }
    }

    /**
     * Encode PCM audio samples to ADPCM format.
     *
     * @param pcmData PCM samples (16-bit signed)
     * @return ADPCM-encoded data with header, or null on failure
     */
    fun encode(pcmData: ShortArray): ByteArray? {
        if (!isInitialized || pcmData.isEmpty()) {
            return null
        }

        return synchronized(encodeLock) {
            try {
                // Calculate output size: header + (samples / 2) bytes (4 bits per sample)
                val adpcmSize = HEADER_SIZE + (pcmData.size + 1) / 2
                val output = ByteArray(adpcmSize)
                val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

                // Write header: predictor and step index
                buffer.putShort(encoderPredictor.toShort())
                buffer.put(encoderStepIndex.toByte())
                buffer.put(0) // Reserved

                // Encode samples (2 samples per byte)
                var byteIndex = HEADER_SIZE
                var i = 0

                while (i < pcmData.size) {
                    val sample1 = pcmData[i]
                    val nibble1 = encodeADPCMSample(sample1.toInt())

                    val nibble2 = if (i + 1 < pcmData.size) {
                        encodeADPCMSample(pcmData[i + 1].toInt())
                    } else {
                        0
                    }

                    // Pack two 4-bit nibbles into one byte (low nibble first)
                    output[byteIndex++] = ((nibble2 shl 4) or nibble1).toByte()
                    i += 2
                }

                output
            } catch (e: Exception) {
                logE({ "Encoding failed" }, e)
                null
            }
        }
    }

    /**
     * Encode PCM audio samples to ADPCM format into a pre-allocated buffer.
     * Zero-copy variant for hot path to eliminate per-frame allocations.
     *
     * @param pcmData PCM samples (16-bit signed)
     * @param output Pre-allocated output buffer (must be at least [HEADER_SIZE] + (samples+1)/2 bytes)
     * @return Number of bytes written, or -1 on failure
     */
    fun encodeInto(pcmData: ShortArray, output: ByteArray): Int {
        if (!isInitialized || pcmData.isEmpty()) {
            return -1
        }

        return synchronized(encodeLock) {
            try {
                val adpcmSize = HEADER_SIZE + (pcmData.size + 1) / 2
                if (output.size < adpcmSize) {
                    logE { "Output buffer too small: ${output.size} < $adpcmSize" }
                    return@synchronized -1
                }

                val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

                // Write header: predictor and step index
                buffer.putShort(encoderPredictor.toShort())
                buffer.put(encoderStepIndex.toByte())
                buffer.put(0) // Reserved

                // Encode samples (2 samples per byte)
                var byteIndex = HEADER_SIZE
                var i = 0

                while (i < pcmData.size) {
                    val sample1 = pcmData[i]
                    val nibble1 = encodeADPCMSample(sample1.toInt())

                    val nibble2 = if (i + 1 < pcmData.size) {
                        encodeADPCMSample(pcmData[i + 1].toInt())
                    } else {
                        0
                    }

                    // Pack two 4-bit nibbles into one byte (low nibble first)
                    output[byteIndex++] = ((nibble2 shl 4) or nibble1).toByte()
                    i += 2
                }

                adpcmSize
            } catch (e: Exception) {
                logE({ "Encoding failed" }, e)
                -1
            }
        }
    }

    /**
     * Decode ADPCM data back to PCM samples.
     *
     * @param adpcmData ADPCM-encoded data with header
     * @return Decoded PCM samples (16-bit signed), or empty array on failure
     */
    fun decode(adpcmData: ByteArray): ShortArray {
        if (!isInitialized || adpcmData.size < HEADER_SIZE) {
            return ShortArray(0)
        }

        return synchronized(decodeLock) {
            try {
                val buffer = ByteBuffer.wrap(adpcmData).order(ByteOrder.LITTLE_ENDIAN)

                // Read header
                decoderPredictor = buffer.short.toInt()
                decoderStepIndex = buffer.get().toInt().coerceIn(0, STEP_TABLE.size - 1)
                buffer.get() // Skip reserved byte

                // Decode samples (2 samples per byte)
                val sampleCount = (adpcmData.size - HEADER_SIZE) * 2
                val output = ShortArray(sampleCount)
                var outIndex = 0

                for (i in HEADER_SIZE until adpcmData.size) {
                    val packedByte = adpcmData[i].toInt() and 0xFF

                    // Low nibble first
                    val nibble1 = packedByte and 0x0F
                    output[outIndex++] = decodeADPCMSample(nibble1).toShort()

                    // High nibble second
                    if (outIndex < sampleCount) {
                        val nibble2 = (packedByte shr 4) and 0x0F
                        output[outIndex++] = decodeADPCMSample(nibble2).toShort()
                    }
                }

                output
            } catch (e: Exception) {
                logE({ "Decoding failed" }, e)
                ShortArray(0)
            }
        }
    }

    /**
     * Decode ADPCM data into a pre-allocated buffer.
     * Zero-copy variant for hot path to eliminate per-frame allocations.
     *
     * @param adpcmData ADPCM-encoded data with header
     * @param output Pre-allocated output buffer (must be at least (adpcmData.size - HEADER_SIZE) * 2 samples)
     * @return Number of samples written, or -1 on failure
     */
    fun decodeInto(adpcmData: ByteArray, output: ShortArray): Int {
        if (!isInitialized || adpcmData.size < HEADER_SIZE) {
            return -1
        }

        return synchronized(decodeLock) {
            try {
                val buffer = ByteBuffer.wrap(adpcmData).order(ByteOrder.LITTLE_ENDIAN)

                // Read header
                decoderPredictor = buffer.short.toInt()
                decoderStepIndex = buffer.get().toInt().coerceIn(0, STEP_TABLE.size - 1)
                buffer.get() // Skip reserved byte

                // Decode samples (2 samples per byte)
                val sampleCount = (adpcmData.size - HEADER_SIZE) * 2
                if (output.size < sampleCount) {
                    logE { "Output buffer too small: ${output.size} < $sampleCount" }
                    return@synchronized -1
                }

                var outIndex = 0

                for (i in HEADER_SIZE until adpcmData.size) {
                    val packedByte = adpcmData[i].toInt() and 0xFF

                    // Low nibble first
                    val nibble1 = packedByte and 0x0F
                    output[outIndex++] = decodeADPCMSample(nibble1).toShort()

                    // High nibble second
                    if (outIndex < sampleCount) {
                        val nibble2 = (packedByte shr 4) and 0x0F
                        output[outIndex++] = decodeADPCMSample(nibble2).toShort()
                    }
                }

                outIndex
            } catch (e: Exception) {
                logE({ "Decoding failed" }, e)
                -1
            }
        }
    }

    /**
     * Decode with packet loss concealment (PLC).
     * Generates comfort noise/silence when packets are lost.
     */
    fun decodePLC(): ShortArray {
        return synchronized(decodeLock) {
            // Generate a frame of silence with gradual fade
            val output = ShortArray(frameSize)
            for (i in output.indices) {
                // Fade the predictor to zero
                decoderPredictor = (decoderPredictor * 0.95).toInt()
                output[i] = decoderPredictor.toShort()
            }
            output
        }
    }

    /**
     * Get the compression ratio achieved.
     */
    fun getCompressionRatio(pcmSamples: Int, encodedBytes: Int): Float {
        val pcmBytes = pcmSamples * 2 // 16-bit samples
        return if (encodedBytes > 0) pcmBytes.toFloat() / encodedBytes else 0f
    }

    /**
     * Reset encoder state (call when starting a new stream).
     */
    fun resetEncoder() {
        synchronized(encodeLock) {
            encoderPredictor = 0
            encoderStepIndex = 0
        }
    }

    /**
     * Reset decoder state.
     */
    fun resetDecoder() {
        synchronized(decodeLock) {
            decoderPredictor = 0
            decoderStepIndex = 0
        }
    }

    /**
     * Release resources.
     */
    fun cleanup() {
        isInitialized = false
        logD { "Codec cleaned up" }
    }

    // IMA ADPCM encoding algorithm
    private fun encodeADPCMSample(sample: Int): Int {
        val step = STEP_TABLE[encoderStepIndex]

        // Calculate difference from predictor
        var diff = sample - encoderPredictor
        var nibble = 0

        // Sign bit
        if (diff < 0) {
            nibble = 8
            diff = -diff
        }

        // Quantize
        var mask = 4
        var tempStep = step
        for (bit in 2 downTo 0) {
            if (diff >= tempStep) {
                nibble = nibble or mask
                diff -= tempStep
            }
            tempStep = tempStep shr 1
            mask = mask shr 1
        }

        // Update predictor
        updatePredictor(nibble, step, true)

        // Update step index
        encoderStepIndex = (encoderStepIndex + INDEX_TABLE[nibble]).coerceIn(0, STEP_TABLE.size - 1)

        return nibble
    }

    // IMA ADPCM decoding algorithm
    private fun decodeADPCMSample(nibble: Int): Int {
        val step = STEP_TABLE[decoderStepIndex]

        // Update predictor
        updatePredictor(nibble, step, false)

        // Update step index
        decoderStepIndex = (decoderStepIndex + INDEX_TABLE[nibble]).coerceIn(0, STEP_TABLE.size - 1)

        return decoderPredictor
    }

    private fun updatePredictor(nibble: Int, step: Int, isEncoder: Boolean) {
        // Calculate difference
        var diff = step shr 3

        if (nibble and 4 != 0) diff += step
        if (nibble and 2 != 0) diff += step shr 1
        if (nibble and 1 != 0) diff += step shr 2

        // Apply sign and update predictor
        val predictor = if (isEncoder) encoderPredictor else decoderPredictor

        val newPredictor = if (nibble and 8 != 0) {
            predictor - diff
        } else {
            predictor + diff
        }

        // Clamp to 16-bit range
        val clampedPredictor = newPredictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

        if (isEncoder) {
            encoderPredictor = clampedPredictor
        } else {
            decoderPredictor = clampedPredictor
        }
    }
}
