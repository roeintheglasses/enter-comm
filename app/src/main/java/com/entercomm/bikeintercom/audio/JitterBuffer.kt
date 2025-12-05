package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logW
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio jitter buffer for smoothing network timing variations.
 *
 * The jitter buffer queues incoming audio frames and releases them at a steady rate,
 * compensating for network latency variations. This prevents audio glitches caused
 * by packets arriving out of order or with variable delays.
 *
 * Features:
 * - Ordered frame extraction by sequence number
 * - Configurable buffer depth (default 80ms)
 * - Automatic buffering before playback starts
 * - Statistics tracking for monitoring
 * - Thread-safe operations
 *
 * @param bufferSizeMs Target buffer size in milliseconds (default 80ms)
 * @param sampleRate Audio sample rate (default 48000Hz)
 * @param frameSizeMs Frame duration in milliseconds (default 20ms)
 */
class JitterBuffer(
    private val bufferSizeMs: Int = DEFAULT_BUFFER_SIZE_MS,
    private val sampleRate: Int = AdpcmCodec.SAMPLE_RATE,
    private val frameSizeMs: Int = DEFAULT_FRAME_SIZE_MS,
) {
    companion object {
        const val DEFAULT_BUFFER_SIZE_MS = 80 // 80ms buffer for bike intercom use case
        const val DEFAULT_FRAME_SIZE_MS = 20 // 20ms frames (standard for VoIP)
        const val MAX_BUFFER_FRAMES = 20 // Prevent unbounded growth
        const val LATE_FRAME_THRESHOLD_MS = 200 // Frames more than 200ms late are dropped
    }

    /**
     * Audio frame with metadata.
     */
    data class AudioFrame(
        val samples: ShortArray,
        val sequenceNumber: Long,
        val timestamp: Long,
        val receivedAt: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioFrame) return false
            return sequenceNumber == other.sequenceNumber
        }

        override fun hashCode(): Int = sequenceNumber.hashCode()
    }

    /**
     * Jitter buffer statistics.
     */
    data class JitterBufferStats(
        val currentBufferSize: Int = 0,
        val framesReceived: Long = 0,
        val framesPlayed: Long = 0,
        val framesDropped: Long = 0,
        val lateFrames: Long = 0,
        val outOfOrderFrames: Long = 0,
        val underruns: Long = 0,
        val averageJitterMs: Float = 0f,
    )

    // Frame buffer ordered by sequence number
    private val frameBuffer = TreeMap<Long, AudioFrame>()
    private val bufferLock = Any()

    // Sequence tracking
    private val nextExpectedSeq = AtomicLong(0)
    private var lastPlayedSeq = -1L
    private var isFirstFrame = true

    // Buffer state
    @Volatile
    private var isBuffering = true

    // Target number of frames to buffer before playback starts
    private val targetFrameCount = bufferSizeMs / frameSizeMs
    private val minFrameCount = (targetFrameCount / 2).coerceAtLeast(1)

    // Statistics
    private var framesReceived = 0L
    private var framesPlayed = 0L
    private var framesDropped = 0L
    private var lateFrames = 0L
    private var outOfOrderFrames = 0L
    private var underruns = 0L

    // Jitter tracking
    private var lastFrameArrivalTime = 0L
    private var jitterSum = 0.0
    private var jitterCount = 0

    /**
     * Add an audio frame to the buffer.
     *
     * @param samples Decoded audio samples
     * @param sequenceNumber Frame sequence number for ordering
     * @param timestamp Original timestamp from sender (for jitter calculation)
     * @return true if frame was accepted, false if dropped
     */
    fun addFrame(samples: ShortArray, sequenceNumber: Long, timestamp: Long): Boolean {
        synchronized(bufferLock) {
            framesReceived++

            // Calculate jitter from inter-arrival time
            val now = System.currentTimeMillis()
            if (lastFrameArrivalTime > 0) {
                val expectedInterval = frameSizeMs.toLong()
                val actualInterval = now - lastFrameArrivalTime
                val jitter = kotlin.math.abs(actualInterval - expectedInterval)
                jitterSum += jitter
                jitterCount++
            }
            lastFrameArrivalTime = now

            // Handle first frame - initialize sequence
            if (isFirstFrame) {
                nextExpectedSeq.set(sequenceNumber)
                isFirstFrame = false
                logD { "JitterBuffer: First frame received, seq=$sequenceNumber" }
            }

            // Check if frame is too late (already played past this sequence)
            if (sequenceNumber <= lastPlayedSeq) {
                lateFrames++
                logD { "JitterBuffer: Dropping late frame seq=$sequenceNumber (last played=$lastPlayedSeq)" }
                return false
            }

            // Check if frame is out of order
            if (sequenceNumber < nextExpectedSeq.get()) {
                outOfOrderFrames++
            }

            // Prevent buffer overflow
            if (frameBuffer.size >= MAX_BUFFER_FRAMES) {
                // Drop oldest frame
                val oldest = frameBuffer.pollFirstEntry()
                if (oldest != null) {
                    framesDropped++
                    logW { "JitterBuffer: Buffer overflow, dropped frame seq=${oldest.key}" }
                }
            }

            // Add frame to buffer
            val frame = AudioFrame(samples, sequenceNumber, timestamp)
            frameBuffer[sequenceNumber] = frame

            // Check if we have enough frames to start playback
            if (isBuffering && frameBuffer.size >= minFrameCount) {
                isBuffering = false
                logD { "JitterBuffer: Buffering complete, starting playback (${frameBuffer.size} frames)" }
            }

            return true
        }
    }

    /**
     * Get the next frame for playback.
     *
     * @return Audio samples for the next frame, or null if still buffering
     */
    fun getFrame(): ShortArray? {
        synchronized(bufferLock) {
            // Still buffering - don't return frames yet
            if (isBuffering) {
                return null
            }

            // Check for underrun
            if (frameBuffer.isEmpty()) {
                underruns++
                // Re-enter buffering mode if buffer is empty
                if (underruns > 3) {
                    isBuffering = true
                    logW { "JitterBuffer: Underrun, re-entering buffering mode" }
                }
                return null
            }

            // Get the next frame in sequence order
            val entry = frameBuffer.pollFirstEntry()
            if (entry != null) {
                val frame = entry.value
                lastPlayedSeq = frame.sequenceNumber
                nextExpectedSeq.set(frame.sequenceNumber + 1)
                framesPlayed++
                underruns = 0 // Reset underrun counter on successful playback
                return frame.samples
            }

            return null
        }
    }

    /**
     * Check if the buffer is ready for playback (not in buffering state).
     */
    fun isReady(): Boolean = !isBuffering

    /**
     * Get current buffer depth in frames.
     */
    fun getBufferDepth(): Int {
        synchronized(bufferLock) {
            return frameBuffer.size
        }
    }

    /**
     * Get current buffer depth in milliseconds.
     */
    fun getBufferDepthMs(): Int {
        return getBufferDepth() * frameSizeMs
    }

    /**
     * Reset the buffer state.
     */
    fun reset() {
        synchronized(bufferLock) {
            frameBuffer.clear()
            nextExpectedSeq.set(0)
            lastPlayedSeq = -1
            isFirstFrame = true
            isBuffering = true
            underruns = 0
            lastFrameArrivalTime = 0
            logD { "JitterBuffer: Reset" }
        }
    }

    /**
     * Get buffer statistics.
     */
    fun getStats(): JitterBufferStats {
        synchronized(bufferLock) {
            val avgJitter = if (jitterCount > 0) {
                (jitterSum / jitterCount).toFloat()
            } else {
                0f
            }

            return JitterBufferStats(
                currentBufferSize = frameBuffer.size,
                framesReceived = framesReceived,
                framesPlayed = framesPlayed,
                framesDropped = framesDropped,
                lateFrames = lateFrames,
                outOfOrderFrames = outOfOrderFrames,
                underruns = underruns,
                averageJitterMs = avgJitter,
            )
        }
    }

    /**
     * Clear statistics (but keep buffer state).
     */
    fun clearStats() {
        synchronized(bufferLock) {
            framesReceived = 0
            framesPlayed = 0
            framesDropped = 0
            lateFrames = 0
            outOfOrderFrames = 0
            jitterSum = 0.0
            jitterCount = 0
        }
    }
}
