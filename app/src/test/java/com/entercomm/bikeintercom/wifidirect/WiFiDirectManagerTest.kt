package com.entercomm.bikeintercom.wifidirect

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WiFiDirectManager service discovery logic.
 * These tests verify group code validation, normalization, and service matching
 * work correctly for WiFi Direct service discovery.
 *
 * These tests would have caught:
 * - Group code format validation issues
 * - Case-sensitivity issues in group code comparison
 * - Matching logic errors when group mode is enabled/disabled
 */
class WiFiDirectManagerTest {

    @Before
    fun setUp() {
        Logger.isTestMode = true
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
    }

    // === Group Code Validation Tests ===
    // These tests verify isValidGroupCode() works correctly

    @Test
    fun `isValidGroupCode accepts valid 4-8 character alphanumeric codes`() {
        val validCodes = listOf(
            "ABCD",
            "ABCDE",
            "ABCDEF",
            "ABCDEFG",
            "ABCDEFGH",
            "1234",
            "AB12CD34",
            "A1B2C3D4",
        )

        for (code in validCodes) {
            assertTrue(
                "Should accept valid code: $code",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    @Test
    fun `isValidGroupCode accepts lowercase codes after normalization`() {
        // Lowercase codes should be valid because validation uses uppercase()
        val lowercaseCodes = listOf(
            "abcd",
            "abcdef",
            "abc123",
        )

        for (code in lowercaseCodes) {
            assertTrue(
                "Should accept lowercase code: $code",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    @Test
    fun `isValidGroupCode accepts OPEN keyword`() {
        assertTrue("Should accept OPEN", WiFiDirectManager.isValidGroupCode("OPEN"))
        assertTrue("Should accept lowercase open", WiFiDirectManager.isValidGroupCode("open"))
        assertTrue("Should accept mixed case Open", WiFiDirectManager.isValidGroupCode("Open"))
    }

    @Test
    fun `isValidGroupCode accepts null for open mode`() {
        assertTrue("Should accept null", WiFiDirectManager.isValidGroupCode(null))
    }

    @Test
    fun `isValidGroupCode rejects codes with invalid length`() {
        val invalidLengthCodes = listOf(
            "", // empty
            "A", // 1 char
            "AB", // 2 chars
            "ABC", // 3 chars (too short)
            "ABCDEFGHI", // 9 chars (too long)
            "ABCDEFGHIJ", // 10 chars
        )

        for (code in invalidLengthCodes) {
            assertFalse(
                "Should reject invalid length code: '$code' (length ${code.length})",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    @Test
    fun `isValidGroupCode rejects codes with special characters`() {
        val invalidCharCodes = listOf(
            "ABC-EF", // hyphen
            "ABC EF", // space
            "ABC_EF", // underscore
            "ABC.EF", // period
            "ABC@EF", // at symbol
            "ABC#DEF", // hash
        )

        for (code in invalidCharCodes) {
            assertFalse(
                "Should reject code with special chars: $code",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    // === Group Code Normalization Tests ===
    // These tests verify normalizeGroupCode() works correctly

    @Test
    fun `normalizeGroupCode converts to uppercase`() {
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("abcdef"))
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("AbCdEf"))
        assertEquals("A1B2C3", WiFiDirectManager.normalizeGroupCode("a1b2c3"))
    }

    @Test
    fun `normalizeGroupCode removes hyphens`() {
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("ABC-DEF"))
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("ABCD-EF"))
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("A-B-C-D-E-F"))
    }

    @Test
    fun `normalizeGroupCode returns null for null input`() {
        assertNull(WiFiDirectManager.normalizeGroupCode(null))
    }

    @Test
    fun `normalizeGroupCode returns null for OPEN keyword`() {
        assertNull(WiFiDirectManager.normalizeGroupCode("OPEN"))
        assertNull(WiFiDirectManager.normalizeGroupCode("open"))
        assertNull(WiFiDirectManager.normalizeGroupCode("Open"))
    }

    @Test
    fun `normalizeGroupCode combined operations`() {
        // Uppercase + remove hyphens
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("abc-def"))
        assertEquals("ABCDEF", WiFiDirectManager.normalizeGroupCode("Abc-Def"))
    }

    // === DiscoveredService Data Class Tests ===
    // These tests verify DiscoveredService creation and equality

    @Test
    fun `DiscoveredService stores correct values`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "ABC123",
            device = null,
        )

        assertEquals("aa:bb:cc:dd:ee:ff", service.deviceAddress)
        assertEquals("EnterComm", service.instanceName)
        assertEquals("_entercomm._tcp", service.serviceType)
        assertEquals("ABC123", service.groupCode)
        assertNull(service.device)
    }

    @Test
    fun `DiscoveredService with null groupCode represents open mode`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = null,
            device = null,
        )

        assertNull(service.groupCode)
    }

    @Test
    fun `DiscoveredService equality works correctly`() {
        val service1 = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "ABC123",
            device = null,
        )

        val service2 = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "ABC123",
            device = null,
        )

        val service3 = DiscoveredService(
            deviceAddress = "11:22:33:44:55:66",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "ABC123",
            device = null,
        )

        assertEquals("Same values should be equal", service1, service2)
        assertNotEquals("Different device addresses should not be equal", service1, service3)
    }

    // === PeerDevice Data Class Tests ===

    @Test
    fun `PeerDevice stores correct values`() {
        val peer = PeerDevice(
            deviceName = "BikeIntercom-Test",
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            isGroupOwner = true,
            ipAddress = "192.168.49.1",
            isConnected = true,
        )

        assertEquals("BikeIntercom-Test", peer.deviceName)
        assertEquals("aa:bb:cc:dd:ee:ff", peer.deviceAddress)
        assertTrue(peer.isGroupOwner)
        assertEquals("192.168.49.1", peer.ipAddress)
        assertTrue(peer.isConnected)
    }

    @Test
    fun `PeerDevice default values are correct`() {
        val peer = PeerDevice(
            deviceName = "Test",
            deviceAddress = "00:00:00:00:00:00",
        )

        assertFalse("Default isGroupOwner should be false", peer.isGroupOwner)
        assertNull("Default ipAddress should be null", peer.ipAddress)
        assertFalse("Default isConnected should be false", peer.isConnected)
    }

    // === WiFiDirectEvent Tests ===

    @Test
    fun `WiFiDirectEvent ServiceDiscovered contains correct service`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "TEST12",
            device = null,
        )

        val event = WiFiDirectEvent.ServiceDiscovered(service)

        assertEquals(service, event.service)
        assertEquals("TEST12", event.service.groupCode)
    }

    @Test
    fun `WiFiDirectEvent MatchingServiceDiscovered contains correct service`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "MATCH1",
            device = null,
        )

        val event = WiFiDirectEvent.MatchingServiceDiscovered(service)

        assertEquals(service, event.service)
        assertEquals("MATCH1", event.service.groupCode)
    }

    @Test
    fun `WiFiDirectEvent GroupCodeChanged contains previous and new codes`() {
        val event = WiFiDirectEvent.GroupCodeChanged(
            previousCode = "OLD123",
            newCode = "NEW456",
        )

        assertEquals("OLD123", event.previousCode)
        assertEquals("NEW456", event.newCode)
    }

    @Test
    fun `WiFiDirectEvent AutoConnectionStarted contains correct service`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "AUTO12",
            device = null,
        )

        val event = WiFiDirectEvent.AutoConnectionStarted(service)

        assertEquals(service, event.service)
    }

    @Test
    fun `WiFiDirectEvent AutoConnectionFailed contains reason`() {
        val event = WiFiDirectEvent.AutoConnectionFailed("Group code mismatch")

        assertEquals("Group code mismatch", event.reason)
    }

    @Test
    fun `WiFiDirectEvent Error contains message`() {
        val event = WiFiDirectEvent.Error("Connection timeout")

        assertEquals("Connection timeout", event.message)
    }

    // === Group Code Comparison Tests ===
    // These tests verify that group codes from different sources can be correctly compared

    @Test
    fun `group code comparison is case-insensitive after normalization`() {
        val code1 = WiFiDirectManager.normalizeGroupCode("ABC123")
        val code2 = WiFiDirectManager.normalizeGroupCode("abc123")
        val code3 = WiFiDirectManager.normalizeGroupCode("AbC123")

        assertEquals("Uppercase and lowercase should normalize to same", code1, code2)
        assertEquals("Mixed case should normalize to same", code1, code3)
    }

    @Test
    fun `group code comparison handles hyphens after normalization`() {
        val withHyphen = WiFiDirectManager.normalizeGroupCode("ABC-123")
        val withoutHyphen = WiFiDirectManager.normalizeGroupCode("ABC123")

        assertEquals("Hyphenated code should equal non-hyphenated", withHyphen, withoutHyphen)
    }

    @Test
    fun `different group codes are detected correctly`() {
        val code1 = WiFiDirectManager.normalizeGroupCode("ABC123")
        val code2 = WiFiDirectManager.normalizeGroupCode("XYZ789")

        assertNotEquals("Different codes should not match", code1, code2)
    }

    // === Edge Cases ===

    @Test
    fun `group code boundary lengths are valid`() {
        // Minimum valid length: 4
        assertTrue(WiFiDirectManager.isValidGroupCode("ABCD"))

        // Maximum valid length: 8
        assertTrue(WiFiDirectManager.isValidGroupCode("ABCD1234"))

        // Just below minimum: 3
        assertFalse(WiFiDirectManager.isValidGroupCode("ABC"))

        // Just above maximum: 9
        assertFalse(WiFiDirectManager.isValidGroupCode("ABCDEFGHI"))
    }

    @Test
    fun `group code all numbers is valid`() {
        assertTrue(WiFiDirectManager.isValidGroupCode("1234"))
        assertTrue(WiFiDirectManager.isValidGroupCode("12345678"))
    }

    @Test
    fun `group code all letters is valid`() {
        assertTrue(WiFiDirectManager.isValidGroupCode("ABCD"))
        assertTrue(WiFiDirectManager.isValidGroupCode("ABCDEFGH"))
    }

    @Test
    fun `empty string is invalid group code`() {
        assertFalse(WiFiDirectManager.isValidGroupCode(""))
    }

    @Test
    fun `whitespace only is invalid group code`() {
        assertFalse(WiFiDirectManager.isValidGroupCode("    "))
        assertFalse(WiFiDirectManager.isValidGroupCode("\t"))
        assertFalse(WiFiDirectManager.isValidGroupCode("\n"))
    }

    // === Service Discovery Scenarios ===

    @Test
    fun `services from same device can be updated`() {
        val service1 = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "OLD123",
            device = null,
        )

        val service2 = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "NEW456",
            device = null,
        )

        // Same device address but different group code
        assertEquals(
            "Device addresses should match",
            service1.deviceAddress,
            service2.deviceAddress,
        )
        assertNotEquals(
            "Services with different group codes should not be equal",
            service1,
            service2,
        )
    }

    @Test
    fun `multiple services with different devices`() {
        val services = listOf(
            DiscoveredService(
                deviceAddress = "aa:bb:cc:dd:ee:01",
                instanceName = "Device1",
                serviceType = "_entercomm._tcp",
                groupCode = "GROUP1",
                device = null,
            ),
            DiscoveredService(
                deviceAddress = "aa:bb:cc:dd:ee:02",
                instanceName = "Device2",
                serviceType = "_entercomm._tcp",
                groupCode = "GROUP1",
                device = null,
            ),
            DiscoveredService(
                deviceAddress = "aa:bb:cc:dd:ee:03",
                instanceName = "Device3",
                serviceType = "_entercomm._tcp",
                groupCode = "GROUP2",
                device = null,
            ),
        )

        // All unique device addresses
        val uniqueAddresses = services.map { it.deviceAddress }.toSet()
        assertEquals(3, uniqueAddresses.size)

        // Services in same group
        val group1Services = services.filter {
            WiFiDirectManager.normalizeGroupCode(it.groupCode) ==
                WiFiDirectManager.normalizeGroupCode("GROUP1")
        }
        assertEquals(2, group1Services.size)
    }
}
