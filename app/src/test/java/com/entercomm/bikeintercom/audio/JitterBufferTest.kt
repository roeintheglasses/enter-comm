package com.entercomm.bikeintercom.audio

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JitterBufferTest {

    private lateinit var buffer: JitterBuffer

    @Before
    fun setUp() {
        Logger.isTestMode = true
        // Use smaller buffer for faster tests
        buffer = JitterBuffer(bufferSizeMs = 40, frameSizeMs = 20)
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
    }

    // === Initial State Tests ===

    @Test
    fun `new buffer is in buffering state`() {
        assertFalse("New buffer should not be ready", buffer.isReady())
    }

    @Test
    fun `new buffer has zero depth`() {
        assertEquals(0, buffer.getBufferDepth())
        assertEquals(0, buffer.getBufferDepthMs())
    }

    @Test
    fun `getFrame returns null when buffering`() {
        assertNull("Should return null when buffering", buffer.getFrame())
    }

    // === Adding Frames Tests ===

    @Test
    fun `addFrame stores frame correctly`() {
        val samples = shortArrayOf(100, 200, 300)
        val result = buffer.addFrame(samples, sequenceNumber = 0, timestamp = 1000L)

        assertTrue("addFrame should return true", result)
        assertEquals(1, buffer.getBufferDepth())
    }

    @Test
    fun `addFrame increments framesReceived stat`() {
        buffer.addFrame(shortArrayOf(1, 2, 3), 0, 0)
        buffer.addFrame(shortArrayOf(4, 5, 6), 1, 0)

        val stats = buffer.getStats()
        assertEquals(2, stats.framesReceived)
    }

    @Test
    fun `buffer becomes ready after minimum frames added`() {
        // With 40ms buffer and 20ms frames, min frames = 2/2 = 1 (coerced to 1)
        assertFalse("Should not be ready initially", buffer.isReady())

        buffer.addFrame(shortArrayOf(1, 2, 3), 0, 0)

        assertTrue("Should be ready after minimum frames", buffer.isReady())
    }

    // === Frame Ordering Tests ===

    @Test
    fun `getFrame returns frames in sequence order`() {
        // Add frames out of order
        buffer.addFrame(shortArrayOf(30), 2, 0)
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)

        // Should return in sequence order
        val frame0 = buffer.getFrame()
        val frame1 = buffer.getFrame()
        val frame2 = buffer.getFrame()

        assertNotNull(frame0)
        assertNotNull(frame1)
        assertNotNull(frame2)
        assertEquals(10, frame0!![0].toInt())
        assertEquals(20, frame1!![0].toInt())
        assertEquals(30, frame2!![0].toInt())
    }

    @Test
    fun `handles out of order frames`() {
        // Add frame with seq 5 first
        buffer.addFrame(shortArrayOf(50), 5, 0)
        // Then add frame with seq 3 (out of order)
        buffer.addFrame(shortArrayOf(30), 3, 0)

        val stats = buffer.getStats()
        assertEquals("Should track out of order frames", 1, stats.outOfOrderFrames)
    }

    // === Late Frame Tests ===

    @Test
    fun `rejects late frames after they have been played`() {
        // Add and play frame 0
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.getFrame() // plays frame 0

        // Try to add frame 0 again (late)
        val result = buffer.addFrame(shortArrayOf(10), 0, 0)

        assertFalse("Should reject late frame", result)

        val stats = buffer.getStats()
        assertEquals(1, stats.lateFrames)
    }

    @Test
    fun `rejects frames with sequence less than last played`() {
        // Add frames 0, 1, 2
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)
        buffer.addFrame(shortArrayOf(30), 2, 0)

        // Play all frames
        buffer.getFrame() // seq 0
        buffer.getFrame() // seq 1
        buffer.getFrame() // seq 2

        // Try to add frame with seq 1 (already played)
        val result = buffer.addFrame(shortArrayOf(15), 1, 0)

        assertFalse("Should reject frame with sequence <= lastPlayedSeq", result)
    }

    // === Buffer Overflow Tests ===

    @Test
    fun `buffer overflow drops oldest frames`() {
        // Fill buffer beyond MAX_BUFFER_FRAMES
        for (i in 0 until JitterBuffer.MAX_BUFFER_FRAMES + 5) {
            buffer.addFrame(shortArrayOf(i.toShort()), i.toLong(), 0)
        }

        // Buffer should be at max size
        assertEquals(JitterBuffer.MAX_BUFFER_FRAMES, buffer.getBufferDepth())

        // Stats should show dropped frames
        val stats = buffer.getStats()
        assertEquals(5, stats.framesDropped)
    }

    // === Underrun Tests ===

    @Test
    fun `empty buffer causes underrun`() {
        // Add one frame to exit buffering state
        buffer.addFrame(shortArrayOf(10), 0, 0)

        // Consume the frame
        buffer.getFrame()

        // Try to get another frame - should cause underrun
        val result = buffer.getFrame()

        assertNull("Should return null on underrun", result)

        val stats = buffer.getStats()
        assertTrue("Should track underruns", stats.underruns > 0)
    }

    @Test
    fun `multiple underruns re-enter buffering mode`() {
        // Add and consume one frame to exit buffering
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.getFrame()

        assertTrue("Should be ready after first frame", buffer.isReady())

        // Cause multiple underruns
        for (i in 0 until 5) {
            buffer.getFrame()
        }

        // Should re-enter buffering mode after multiple underruns
        assertFalse("Should re-enter buffering after multiple underruns", buffer.isReady())
    }

    // === Reset Tests ===

    @Test
    fun `reset clears all frames`() {
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)

        buffer.reset()

        assertEquals(0, buffer.getBufferDepth())
        assertFalse("Should be in buffering state after reset", buffer.isReady())
    }

    @Test
    fun `reset allows reuse with new sequence numbers`() {
        // Use buffer with some frames
        buffer.addFrame(shortArrayOf(10), 100, 0)
        buffer.getFrame()

        // Reset
        buffer.reset()

        // Should accept new sequence starting from 0
        val result = buffer.addFrame(shortArrayOf(20), 0, 0)
        assertTrue("Should accept new frames after reset", result)
    }

    // === Statistics Tests ===

    @Test
    fun `getStats returns accurate frame counts`() {
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)
        buffer.getFrame()

        val stats = buffer.getStats()

        assertEquals(2, stats.framesReceived)
        assertEquals(1, stats.framesPlayed)
        assertEquals(1, stats.currentBufferSize)
    }

    @Test
    fun `clearStats resets statistics`() {
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)
        buffer.getFrame()

        buffer.clearStats()

        val stats = buffer.getStats()
        assertEquals(0, stats.framesReceived)
        assertEquals(0, stats.framesPlayed)
        // Buffer should still have frames
        assertEquals(1, buffer.getBufferDepth())
    }

    @Test
    fun `stats track buffer depth correctly`() {
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)
        buffer.addFrame(shortArrayOf(30), 2, 0)

        assertEquals(3, buffer.getStats().currentBufferSize)
        assertEquals(60, buffer.getBufferDepthMs()) // 3 frames * 20ms
    }

    // === Edge Cases ===

    @Test
    fun `handles empty sample array`() {
        val result = buffer.addFrame(ShortArray(0), 0, 0)
        assertTrue("Should accept empty samples", result)
    }

    @Test
    fun `handles large sequence numbers`() {
        val largeSeq = Long.MAX_VALUE - 100
        val result = buffer.addFrame(shortArrayOf(10), largeSeq, 0)

        assertTrue("Should accept large sequence numbers", result)

        val frame = buffer.getFrame()
        assertNotNull(frame)
        assertEquals(10, frame!![0].toInt())
    }

    @Test
    fun `handles negative timestamps`() {
        val result = buffer.addFrame(shortArrayOf(10), 0, -1000L)
        assertTrue("Should accept negative timestamps", result)
    }

    // === Buffer Depth Tests ===

    @Test
    fun `buffer depth reflects actual frame count`() {
        assertEquals(0, buffer.getBufferDepth())

        buffer.addFrame(shortArrayOf(10), 0, 0)
        assertEquals(1, buffer.getBufferDepth())

        buffer.addFrame(shortArrayOf(20), 1, 0)
        assertEquals(2, buffer.getBufferDepth())

        buffer.getFrame()
        assertEquals(1, buffer.getBufferDepth())

        buffer.getFrame()
        assertEquals(0, buffer.getBufferDepth())
    }

    @Test
    fun `buffer depth in ms calculated correctly`() {
        // frameSizeMs is 20 in our test setup
        buffer.addFrame(shortArrayOf(10), 0, 0)
        buffer.addFrame(shortArrayOf(20), 1, 0)
        buffer.addFrame(shortArrayOf(30), 2, 0)

        assertEquals(60, buffer.getBufferDepthMs()) // 3 * 20ms
    }

    // === Companion Object Constants ===

    @Test
    fun `default buffer size is reasonable`() {
        assertTrue(
            "Default buffer should be between 40-200ms",
            JitterBuffer.DEFAULT_BUFFER_SIZE_MS in 40..200,
        )
    }

    @Test
    fun `default frame size is reasonable`() {
        assertTrue(
            "Default frame size should be between 10-40ms",
            JitterBuffer.DEFAULT_FRAME_SIZE_MS in 10..40,
        )
    }

    @Test
    fun `max buffer frames prevents unbounded growth`() {
        assertTrue(
            "Max buffer should be reasonable",
            JitterBuffer.MAX_BUFFER_FRAMES in 10..100,
        )
    }
}
