package com.entercomm.bikeintercom.mesh

import org.junit.Assert.*
import org.junit.Test

class NetworkStatsTest {

    // === Packet Loss Calculation Tests ===

    @Test
    fun `packetLossPercent returns 0 when no packets sent`() {
        val stats = NetworkStats(packetsSent = 0, packetsReceived = 0)
        assertEquals(0f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent returns 0 when all packets received`() {
        val stats = NetworkStats(packetsSent = 100, packetsReceived = 100)
        assertEquals(0f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent returns 0 when more received than sent`() {
        // This can happen with broadcast messages
        val stats = NetworkStats(packetsSent = 100, packetsReceived = 150)
        assertEquals(0f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent calculates 50 percent correctly`() {
        val stats = NetworkStats(packetsSent = 100, packetsReceived = 50)
        assertEquals(50f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent calculates 25 percent correctly`() {
        val stats = NetworkStats(packetsSent = 100, packetsReceived = 75)
        assertEquals(25f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent calculates 100 percent correctly`() {
        val stats = NetworkStats(packetsSent = 100, packetsReceived = 0)
        assertEquals(100f, stats.packetLossPercent, 0.001f)
    }

    @Test
    fun `packetLossPercent is clamped to 100`() {
        // Edge case that shouldn't happen, but test clamping
        val stats = NetworkStats(packetsSent = 50, packetsReceived = -10)
        assertTrue(stats.packetLossPercent <= 100f)
    }

    @Test
    fun `packetLossPercent is clamped to 0`() {
        val stats = NetworkStats(packetsSent = 50, packetsReceived = 100)
        assertTrue(stats.packetLossPercent >= 0f)
    }

    // === Uptime String Formatting Tests ===

    @Test
    fun `getUptimeString formats seconds correctly`() {
        val startTime = System.currentTimeMillis() - 30_000L // 30 seconds ago
        val stats = NetworkStats()

        val uptime = stats.getUptimeString(startTime)

        assertTrue(
            "Should show seconds format, got: $uptime",
            uptime.endsWith("s"),
        )
        assertTrue(
            "Should show ~30s, got: $uptime",
            uptime.contains("30") || uptime.contains("29") || uptime.contains("31"),
        )
    }

    @Test
    fun `getUptimeString formats minutes correctly`() {
        val startTime = System.currentTimeMillis() - (5 * 60 * 1000L) // 5 minutes ago
        val stats = NetworkStats()

        val uptime = stats.getUptimeString(startTime)

        assertTrue(
            "Should contain 'm' for minutes, got: $uptime",
            uptime.contains("m"),
        )
        assertTrue(
            "Should show ~5m, got: $uptime",
            uptime.contains("5m") || uptime.contains("4m"),
        )
    }

    @Test
    fun `getUptimeString formats hours correctly`() {
        val startTime = System.currentTimeMillis() - (2 * 60 * 60 * 1000L) // 2 hours ago
        val stats = NetworkStats()

        val uptime = stats.getUptimeString(startTime)

        assertTrue(
            "Should contain 'h' for hours, got: $uptime",
            uptime.contains("h"),
        )
        assertTrue(
            "Should show ~2h, got: $uptime",
            uptime.startsWith("2h") || uptime.startsWith("1h"),
        )
    }

    @Test
    fun `getUptimeString shows 0s for just started`() {
        val startTime = System.currentTimeMillis()
        val stats = NetworkStats()

        val uptime = stats.getUptimeString(startTime)

        assertTrue(
            "Should show 0s for just started, got: $uptime",
            uptime == "0s" || uptime == "1s",
        )
    }

    @Test
    fun `getUptimeString handles large uptimes`() {
        val startTime = System.currentTimeMillis() - (100 * 60 * 60 * 1000L) // 100 hours ago
        val stats = NetworkStats()

        val uptime = stats.getUptimeString(startTime)

        assertTrue(
            "Should contain 'h' for hours, got: $uptime",
            uptime.contains("h"),
        )
        assertTrue(
            "Should show 100h, got: $uptime",
            uptime.startsWith("100h"),
        )
    }

    // === Data Class Default Values Tests ===

    @Test
    fun `default values are zero`() {
        val stats = NetworkStats()

        assertEquals(0L, stats.packetsSent)
        assertEquals(0L, stats.packetsReceived)
        assertEquals(0L, stats.bytesSent)
        assertEquals(0L, stats.bytesReceived)
        assertEquals(0L, stats.discoveryRequestsSent)
        assertEquals(0L, stats.discoveryResponsesReceived)
        assertEquals(0L, stats.audioPacketsSent)
        assertEquals(0L, stats.audioPacketsReceived)
        assertEquals(0L, stats.heartbeatsSent)
        assertEquals(0L, stats.heartbeatsReceived)
    }

    @Test
    fun `lastUpdateTime defaults to current time`() {
        val before = System.currentTimeMillis()
        val stats = NetworkStats()
        val after = System.currentTimeMillis()

        assertTrue(stats.lastUpdateTime >= before)
        assertTrue(stats.lastUpdateTime <= after)
    }

    // === Copy and Equality Tests ===

    @Test
    fun `copy preserves all fields`() {
        val original = NetworkStats(
            packetsSent = 100,
            packetsReceived = 90,
            bytesSent = 5000,
            bytesReceived = 4500,
            discoveryRequestsSent = 10,
            discoveryResponsesReceived = 8,
            audioPacketsSent = 50,
            audioPacketsReceived = 45,
            heartbeatsSent = 20,
            heartbeatsReceived = 18,
            lastUpdateTime = 12345L,
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `copy allows modification`() {
        val original = NetworkStats(packetsSent = 100)
        val modified = original.copy(packetsSent = 200)

        assertEquals(100L, original.packetsSent)
        assertEquals(200L, modified.packetsSent)
    }

    @Test
    fun `two stats with same values are equal`() {
        val stats1 = NetworkStats(packetsSent = 100, packetsReceived = 90)
        val stats2 = NetworkStats(packetsSent = 100, packetsReceived = 90)

        assertEquals(stats1, stats2)
    }

    @Test
    fun `two stats with different values are not equal`() {
        val stats1 = NetworkStats(packetsSent = 100)
        val stats2 = NetworkStats(packetsSent = 200)

        assertNotEquals(stats1, stats2)
    }

    // === Integration Tests ===

    @Test
    fun `comprehensive stats example`() {
        val stats = NetworkStats(
            packetsSent = 1000,
            packetsReceived = 950,
            bytesSent = 50000,
            bytesReceived = 47500,
            discoveryRequestsSent = 50,
            discoveryResponsesReceived = 48,
            audioPacketsSent = 800,
            audioPacketsReceived = 780,
            heartbeatsSent = 100,
            heartbeatsReceived = 95,
        )

        // 5% packet loss
        assertEquals(5f, stats.packetLossPercent, 0.1f)

        // Verify ratios
        val bytesPerPacketSent = stats.bytesSent.toFloat() / stats.packetsSent
        assertEquals(50f, bytesPerPacketSent, 0.1f) // 50 bytes per packet average
    }
}
