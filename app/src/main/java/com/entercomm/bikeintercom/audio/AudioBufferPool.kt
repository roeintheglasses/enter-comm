package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.config.AppConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Object pool for audio buffers to eliminate per-frame allocations.
 *
 * Two pool types are provided:
 * - ThreadLocal pools for single-thread paths (encoding, effects)
 * - Shared ring buffer for multi-thread paths (decoding from multiple peers)
 *
 * Memory budget: ~27KB fixed vs ~900KB/sec allocations eliminated.
 *
 * Usage patterns:
 * - Encode path (single thread): Use getEncodeBuffer()
 * - Effects path (single thread): Use getEffectsBuffer(0) and getEffectsBuffer(1)
 * - Decode path (multi-thread): Use acquireDecodeBuffer()
 *
 * Note: Buffers are reused, so callers must complete processing before the
 * next frame. For decode buffers, the ring cycles every DECODE_POOL_SIZE frames
 * across all peers, giving ~240ms window at 50fps with 12 buffers.
 */
object AudioBufferPool {

    /** Frame size: 960 samples (20ms @ 48kHz). */
    private const val FRAME_SIZE = AdpcmCodec.FRAME_SIZE

    /** ADPCM encoded size: 4-byte header + (960+1)/2 bytes = 484 bytes. */
    private const val ENCODED_SIZE = 4 + (FRAME_SIZE + 1) / 2

    /** Decode pool size: max peers + spare capacity for timing variations. */
    private const val DECODE_POOL_SIZE = AppConfig.Audio.MAX_AUDIO_PROCESSORS + 2

    // ==================== ThreadLocal Pools (Encode Path) ====================

    /**
     * ThreadLocal buffer for ADPCM encoding output.
     * Single buffer per thread since encoding is sequential.
     */
    private val encodeBuffer = ThreadLocal.withInitial {
        ByteArray(ENCODED_SIZE)
    }

    /**
     * ThreadLocal buffers for effects processing intermediate results.
     * Two buffers needed: one for wind filter output, one for AGC if needed.
     */
    private val effectsBuffer1 = ThreadLocal.withInitial {
        ShortArray(FRAME_SIZE)
    }

    private val effectsBuffer2 = ThreadLocal.withInitial {
        ShortArray(FRAME_SIZE)
    }

    // ==================== Shared Pool (Decode Path) ====================

    /**
     * Ring buffer pool for decode outputs.
     * Fixed size array with atomic index for lock-free allocation.
     */
    private val decodePool = Array(DECODE_POOL_SIZE) { ShortArray(FRAME_SIZE) }
    private val decodePoolIndex = AtomicInteger(0)

    // ==================== Public API ====================

    /**
     * Get a ByteArray buffer for encoding output.
     * MUST be called from encoding thread only.
     *
     * @return Reusable ByteArray of size [ENCODED_SIZE] (484 bytes)
     */
    fun getEncodeBuffer(): ByteArray = encodeBuffer.get()!!

    /**
     * Get the size of the encode buffer.
     */
    fun getEncodeBufferSize(): Int = ENCODED_SIZE

    /**
     * Get a ShortArray buffer for effects processing.
     * MUST be called from the audio processing thread only.
     *
     * @param slot 0 or 1 to select which buffer
     * @return Reusable ShortArray of size [FRAME_SIZE] (960 samples)
     */
    fun getEffectsBuffer(slot: Int): ShortArray = if (slot == 0) effectsBuffer1.get()!! else effectsBuffer2.get()!!

    /**
     * Acquire a ShortArray buffer from the decode pool.
     * Thread-safe, uses atomic ring buffer allocation.
     *
     * Note: The caller must use the buffer immediately and complete
     * processing before the pool cycles through all buffers.
     * With 12 buffers and 10 peers at 50fps, this gives ~240ms window.
     *
     * @return Reusable ShortArray of size [FRAME_SIZE] (960 samples)
     */
    fun acquireDecodeBuffer(): ShortArray {
        val index = decodePoolIndex.getAndUpdate { (it + 1) % DECODE_POOL_SIZE }
        return decodePool[index]
    }

    /**
     * Get pool statistics for debugging and monitoring.
     */
    fun getStats(): PoolStats = PoolStats(
        encodeBufferSize = ENCODED_SIZE,
        effectsBufferSize = FRAME_SIZE * 2, // 2 bytes per short
        decodePoolSize = DECODE_POOL_SIZE,
        decodeBufferSize = FRAME_SIZE * 2,
        frameSize = FRAME_SIZE,
        totalMemoryBytes = ENCODED_SIZE +
            FRAME_SIZE * 2 * 2 + // 2 effects buffers per thread
            DECODE_POOL_SIZE * FRAME_SIZE * 2, // decode pool
    )

    /**
     * Pool statistics for monitoring.
     */
    data class PoolStats(
        val encodeBufferSize: Int,
        val effectsBufferSize: Int,
        val decodePoolSize: Int,
        val decodeBufferSize: Int,
        val frameSize: Int,
        val totalMemoryBytes: Int,
    )
}
