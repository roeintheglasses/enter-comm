package com.entercomm.bikeintercom.util

import org.junit.Assert.*
import org.junit.Test

class BatteryManagerTest {

    // === getDiscoveryIntervalForBattery Tests ===

    @Test
    fun `getDiscoveryIntervalForBattery returns 120_000ms for battery level 0`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(0)

        assertEquals("Battery 0% should return 120,000ms (critical)", 120_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 120_000ms for battery level 20`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(20)

        assertEquals("Battery 20% should return 120,000ms (critical upper boundary)", 120_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 60_000ms for battery level 21`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(21)

        assertEquals("Battery 21% should return 60,000ms (low lower boundary)", 60_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 60_000ms for battery level 50`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(50)

        assertEquals("Battery 50% should return 60,000ms (low upper boundary)", 60_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 30_000ms for battery level 51`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(51)

        assertEquals("Battery 51% should return 30,000ms (normal lower boundary)", 30_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 30_000ms for battery level 100`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(100)

        assertEquals("Battery 100% should return 30,000ms (normal)", 30_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 120_000ms for mid-critical range`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(10)

        assertEquals("Battery 10% should return 120,000ms (mid-critical)", 120_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 60_000ms for mid-low range`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(35)

        assertEquals("Battery 35% should return 60,000ms (mid-low)", 60_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery returns 30_000ms for mid-normal range`() {
        val result = BatteryManager.getDiscoveryIntervalForBattery(75)

        assertEquals("Battery 75% should return 30,000ms (mid-normal)", 30_000L, result)
    }

    // === getUpdateIntervalForBattery Tests ===

    @Test
    fun `getUpdateIntervalForBattery returns 30_000ms for battery level 0`() {
        val result = BatteryManager.getUpdateIntervalForBattery(0)

        assertEquals("Battery 0% should return 30,000ms (critical)", 30_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 30_000ms for battery level 20`() {
        val result = BatteryManager.getUpdateIntervalForBattery(20)

        assertEquals("Battery 20% should return 30,000ms (critical upper boundary)", 30_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 15_000ms for battery level 21`() {
        val result = BatteryManager.getUpdateIntervalForBattery(21)

        assertEquals("Battery 21% should return 15,000ms (low lower boundary)", 15_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 15_000ms for battery level 50`() {
        val result = BatteryManager.getUpdateIntervalForBattery(50)

        assertEquals("Battery 50% should return 15,000ms (low upper boundary)", 15_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 5_000ms for battery level 51`() {
        val result = BatteryManager.getUpdateIntervalForBattery(51)

        assertEquals("Battery 51% should return 5,000ms (normal lower boundary)", 5_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 5_000ms for battery level 100`() {
        val result = BatteryManager.getUpdateIntervalForBattery(100)

        assertEquals("Battery 100% should return 5,000ms (normal)", 5_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 30_000ms for mid-critical range`() {
        val result = BatteryManager.getUpdateIntervalForBattery(10)

        assertEquals("Battery 10% should return 30,000ms (mid-critical)", 30_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 15_000ms for mid-low range`() {
        val result = BatteryManager.getUpdateIntervalForBattery(35)

        assertEquals("Battery 35% should return 15,000ms (mid-low)", 15_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery returns 5_000ms for mid-normal range`() {
        val result = BatteryManager.getUpdateIntervalForBattery(75)

        assertEquals("Battery 75% should return 5,000ms (mid-normal)", 5_000L, result)
    }

    // === Edge Cases ===

    @Test
    fun `getDiscoveryIntervalForBattery handles negative values`() {
        // Negative values fall into "else" case (30,000ms normal)
        val result = BatteryManager.getDiscoveryIntervalForBattery(-1)

        assertEquals("Negative battery should return 30,000ms (else case)", 30_000L, result)
    }

    @Test
    fun `getDiscoveryIntervalForBattery handles values above 100`() {
        // Values above 100 fall into "else" case (30,000ms normal)
        val result = BatteryManager.getDiscoveryIntervalForBattery(150)

        assertEquals("Battery above 100% should return 30,000ms (else case)", 30_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery handles negative values`() {
        // Negative values fall into "else" case (30,000ms critical)
        val result = BatteryManager.getUpdateIntervalForBattery(-1)

        assertEquals("Negative battery should return 30,000ms (else case)", 30_000L, result)
    }

    @Test
    fun `getUpdateIntervalForBattery handles values above 100`() {
        // Values above 100 are > 50, so return 5,000ms (normal)
        val result = BatteryManager.getUpdateIntervalForBattery(150)

        assertEquals("Battery above 100% should return 5,000ms (normal)", 5_000L, result)
    }

    // === Consistency Tests ===

    @Test
    fun `discovery interval increases as battery decreases`() {
        val normalInterval = BatteryManager.getDiscoveryIntervalForBattery(75)
        val lowInterval = BatteryManager.getDiscoveryIntervalForBattery(35)
        val criticalInterval = BatteryManager.getDiscoveryIntervalForBattery(10)

        assertTrue(
            "Low interval should be greater than normal",
            lowInterval > normalInterval,
        )
        assertTrue(
            "Critical interval should be greater than low",
            criticalInterval > lowInterval,
        )
    }

    @Test
    fun `GPS update interval increases as battery decreases`() {
        val normalInterval = BatteryManager.getUpdateIntervalForBattery(75)
        val lowInterval = BatteryManager.getUpdateIntervalForBattery(35)
        val criticalInterval = BatteryManager.getUpdateIntervalForBattery(10)

        assertTrue(
            "Low interval should be greater than normal",
            lowInterval > normalInterval,
        )
        assertTrue(
            "Critical interval should be greater than low",
            criticalInterval > lowInterval,
        )
    }
}
