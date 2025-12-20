package com.entercomm.bikeintercom.ui.components

import androidx.compose.ui.graphics.Color
import com.entercomm.bikeintercom.ui.theme.TechGreen
import com.entercomm.bikeintercom.ui.theme.TechRed
import org.junit.Assert.*
import org.junit.Test

class TechnicalComponentsTest {

    // Expected colors from TechnicalComponents.kt (private vals duplicated for testing)
    private val techYellow = Color(0xFFFFD54F)
    private val techOrange = Color(0xFFFF9800)

    // ============================================================================
    // formatBytes Tests
    // ============================================================================

    @Test
    fun `formatBytes returns 0 B for zero bytes`() {
        val result = formatBytes(0)
        assertEquals("0 B", result)
    }

    @Test
    fun `formatBytes returns 0 B for negative bytes`() {
        val result = formatBytes(-100)
        assertEquals("0 B", result)
    }

    @Test
    fun `formatBytes returns 0 B for large negative bytes`() {
        val result = formatBytes(-1_000_000)
        assertEquals("0 B", result)
    }

    @Test
    fun `formatBytes formats bytes correctly`() {
        assertEquals("1 B", formatBytes(1))
        assertEquals("100 B", formatBytes(100))
        assertEquals("500 B", formatBytes(500))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `formatBytes formats kilobytes correctly`() {
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("10 KB", formatBytes(10 * 1024))
        assertEquals("100 KB", formatBytes(100 * 1024))
        assertEquals("512 KB", formatBytes(512 * 1024))
        assertEquals("1023 KB", formatBytes(1023 * 1024))
    }

    @Test
    fun `formatBytes formats megabytes correctly`() {
        // 1 MB
        val oneMB = 1024L * 1024L
        assertEquals("1.0 MB", formatBytes(oneMB))

        // 1.5 MB
        val onePointFiveMB = (1.5 * 1024 * 1024).toLong()
        assertEquals("1.5 MB", formatBytes(onePointFiveMB))

        // 10 MB
        val tenMB = 10L * 1024 * 1024
        assertEquals("10.0 MB", formatBytes(tenMB))

        // 512 MB
        val fiveHundredTwelveMB = 512L * 1024 * 1024
        assertEquals("512.0 MB", formatBytes(fiveHundredTwelveMB))
    }

    @Test
    fun `formatBytes formats gigabytes correctly`() {
        // 1 GB
        val oneGB = 1024L * 1024 * 1024
        assertEquals("1.00 GB", formatBytes(oneGB))

        // 1.5 GB
        val onePointFiveGB = (1.5 * 1024 * 1024 * 1024).toLong()
        assertEquals("1.50 GB", formatBytes(onePointFiveGB))

        // 10 GB
        val tenGB = 10L * 1024 * 1024 * 1024
        assertEquals("10.00 GB", formatBytes(tenGB))
    }

    @Test
    fun `formatBytes handles very large values`() {
        // 100 GB
        val hundredGB = 100L * 1024 * 1024 * 1024
        assertEquals("100.00 GB", formatBytes(hundredGB))

        // 1 TB (should still format as GB)
        val oneTB = 1024L * 1024 * 1024 * 1024
        assertEquals("1024.00 GB", formatBytes(oneTB))
    }

    @Test
    fun `formatBytes handles boundary values between units`() {
        // Just under 1 KB
        assertEquals("1023 B", formatBytes(1023))

        // Exactly 1 KB
        assertEquals("1 KB", formatBytes(1024))

        // Just under 1 MB
        assertEquals("1023 KB", formatBytes(1024 * 1024 - 1024))

        // Exactly 1 MB
        assertEquals("1.0 MB", formatBytes(1024 * 1024))

        // Just under 1 GB
        val justUnderGB = 1024L * 1024 * 1024 - 1024 * 1024
        assertTrue(formatBytes(justUnderGB).endsWith("MB"))

        // Exactly 1 GB
        assertEquals("1.00 GB", formatBytes(1024L * 1024 * 1024))
    }

    // ============================================================================
    // getPacketLossColor Tests
    // ============================================================================

    @Test
    fun `getPacketLossColor returns green for 0 percent`() {
        val result = getPacketLossColor(0f)
        assertEquals(TechGreen, result)
    }

    @Test
    fun `getPacketLossColor returns green for 5 percent`() {
        val result = getPacketLossColor(5f)
        assertEquals(TechGreen, result)
    }

    @Test
    fun `getPacketLossColor returns green for values under 5 percent`() {
        assertEquals(TechGreen, getPacketLossColor(0.5f))
        assertEquals(TechGreen, getPacketLossColor(1f))
        assertEquals(TechGreen, getPacketLossColor(2.5f))
        assertEquals(TechGreen, getPacketLossColor(4.99f))
    }

    @Test
    fun `getPacketLossColor returns yellow for values between 5 and 15 percent`() {
        assertEquals(techYellow, getPacketLossColor(5.01f))
        assertEquals(techYellow, getPacketLossColor(10f))
        assertEquals(techYellow, getPacketLossColor(15f))
    }

    @Test
    fun `getPacketLossColor returns orange for values between 15 and 30 percent`() {
        assertEquals(techOrange, getPacketLossColor(15.01f))
        assertEquals(techOrange, getPacketLossColor(20f))
        assertEquals(techOrange, getPacketLossColor(25f))
        assertEquals(techOrange, getPacketLossColor(30f))
    }

    @Test
    fun `getPacketLossColor returns red for values above 30 percent`() {
        assertEquals(TechRed, getPacketLossColor(30.01f))
        assertEquals(TechRed, getPacketLossColor(40f))
        assertEquals(TechRed, getPacketLossColor(50f))
        assertEquals(TechRed, getPacketLossColor(75f))
        assertEquals(TechRed, getPacketLossColor(100f))
    }

    @Test
    fun `getPacketLossColor returns red for extreme values`() {
        // Extreme high value
        assertEquals(TechRed, getPacketLossColor(150f))

        // Should still work with very high values
        assertEquals(TechRed, getPacketLossColor(1000f))
    }

    @Test
    fun `getPacketLossColor handles exact threshold values correctly`() {
        // At 5% boundary - should be green (<=5 is green)
        assertEquals(TechGreen, getPacketLossColor(5f))

        // At 15% boundary - should be yellow (<=15 is yellow)
        assertEquals(techYellow, getPacketLossColor(15f))

        // At 30% boundary - should be orange (<=30 is orange)
        assertEquals(techOrange, getPacketLossColor(30f))
    }

    @Test
    fun `getPacketLossColor handles negative values gracefully`() {
        // Negative packet loss should still return green (best quality)
        val result = getPacketLossColor(-5f)
        assertEquals(TechGreen, result)
    }

    @Test
    fun `getPacketLossColor handles zero exactly`() {
        val result = getPacketLossColor(0.0f)
        assertEquals(TechGreen, result)
    }

    // ============================================================================
    // Integration Tests
    // ============================================================================

    @Test
    fun `formatBytes and getPacketLossColor work together in typical scenario`() {
        // Simulate typical network stats
        val bytesSent = 512_000L // 500 KB
        val bytesReceived = 1_024_000L // 1000 KB

        val formattedSent = formatBytes(bytesSent)
        val formattedReceived = formatBytes(bytesReceived)

        assertEquals("500 KB", formattedSent)
        assertEquals("1000 KB", formattedReceived)

        // Low packet loss - green
        val lowLossColor = getPacketLossColor(2.5f)
        assertEquals(TechGreen, lowLossColor)

        // High packet loss - red
        val highLossColor = getPacketLossColor(45f)
        assertEquals(TechRed, highLossColor)
    }
}
