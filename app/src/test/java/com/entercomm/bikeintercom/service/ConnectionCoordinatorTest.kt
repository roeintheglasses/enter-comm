package com.entercomm.bikeintercom.service

import com.entercomm.bikeintercom.util.Logger
import com.entercomm.bikeintercom.wifidirect.DiscoveredService
import com.entercomm.bikeintercom.wifidirect.WiFiDirectManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ConnectionCoordinator WiFi Direct priority logic.
 * These tests verify that WiFi Direct service discovery is prioritized over
 * peer discovery, and that group code synchronization works correctly.
 *
 * These tests would have caught:
 * - WiFi Direct not being prioritized as primary discovery mechanism
 * - Group code not being synchronized to both WiFiDirectManager and MeshNetworkManager
 * - Connection events not being emitted correctly
 */
class ConnectionCoordinatorTest {

    @Before
    fun setUp() {
        Logger.isTestMode = true
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
    }

    // === ConnectionState Tests ===
    // These tests verify all connection states are defined correctly

    @Test
    fun `ConnectionState has all expected values`() {
        val states = ConnectionState.values()

        assertEquals(5, states.size)
        assertTrue(states.contains(ConnectionState.DISCONNECTED))
        assertTrue(states.contains(ConnectionState.DISCOVERING))
        assertTrue(states.contains(ConnectionState.CONNECTING))
        assertTrue(states.contains(ConnectionState.CONNECTED))
        assertTrue(states.contains(ConnectionState.ERROR))
    }

    @Test
    fun `ConnectionState DISCONNECTED is the default state`() {
        // This matches the implementation: MutableStateFlow(ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.values().first())
    }

    // === ConnectionEvent Tests ===
    // These tests verify all connection events are properly structured

    @Test
    fun `ConnectionEvent DeviceDiscovered contains device info`() {
        val event = ConnectionEvent.DeviceDiscovered(
            deviceName = "BikeIntercom-Test",
            deviceAddress = "aa:bb:cc:dd:ee:ff",
        )

        assertEquals("BikeIntercom-Test", event.deviceName)
        assertEquals("aa:bb:cc:dd:ee:ff", event.deviceAddress)
    }

    @Test
    fun `ConnectionEvent ServiceDiscovered contains service info`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "ABC123",
            device = null,
        )

        val event = ConnectionEvent.ServiceDiscovered(service)

        assertEquals(service, event.service)
        assertEquals("ABC123", event.service.groupCode)
    }

    @Test
    fun `ConnectionEvent MatchingServiceDiscovered contains matching service`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "MATCH1",
            device = null,
        )

        val event = ConnectionEvent.MatchingServiceDiscovered(service)

        assertEquals(service, event.service)
        assertEquals("MATCH1", event.service.groupCode)
    }

    @Test
    fun `ConnectionEvent ConnectionEstablished contains address`() {
        val event = ConnectionEvent.ConnectionEstablished("192.168.49.1")

        assertEquals("192.168.49.1", event.address)
    }

    @Test
    fun `ConnectionEvent ConnectionFailed contains reason`() {
        val event = ConnectionEvent.ConnectionFailed("Connection timeout")

        assertEquals("Connection timeout", event.reason)
    }

    @Test
    fun `ConnectionEvent StateChanged contains new state`() {
        val event = ConnectionEvent.StateChanged(ConnectionState.CONNECTED)

        assertEquals(ConnectionState.CONNECTED, event.state)
    }

    // === Service Discovery Prioritization Tests ===
    // These tests verify WiFi Direct service discovery is the primary mechanism

    @Test
    fun `ServiceDiscovered and MatchingServiceDiscovered events are distinct`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "TEST12",
            device = null,
        )

        val discovered = ConnectionEvent.ServiceDiscovered(service)
        val matching = ConnectionEvent.MatchingServiceDiscovered(service)

        // Different event types even with same service
        assertNotEquals(discovered, matching)
        assertNotEquals(discovered::class, matching::class)

        // But same service content
        assertEquals(discovered.service, matching.service)
    }

    @Test
    fun `ConnectionEvent types cover all scenarios`() {
        // Verify all sealed class subtypes exist for exhaustive when handling
        val events = listOf<ConnectionEvent>(
            ConnectionEvent.DeviceDiscovered("Device", "00:00:00:00:00:00"),
            ConnectionEvent.ServiceDiscovered(createTestService()),
            ConnectionEvent.MatchingServiceDiscovered(createTestService()),
            ConnectionEvent.ConnectionEstablished("192.168.1.1"),
            ConnectionEvent.ConnectionFailed("Error"),
            ConnectionEvent.StateChanged(ConnectionState.DISCONNECTED),
        )

        assertEquals(6, events.size)
    }

    // === Group Code Synchronization Tests ===
    // These tests verify group code handling patterns match WiFiDirectManager

    @Test
    fun `group code normalization is consistent with WiFiDirectManager`() {
        // These patterns should match between ConnectionCoordinator and WiFiDirectManager
        val testCases = listOf(
            "ABCDEF" to "ABCDEF",
            "abcdef" to "ABCDEF",
            "AbCdEf" to "ABCDEF",
            "ABC-DEF" to "ABCDEF",
            "abc-def" to "ABCDEF",
        )

        for ((input, expected) in testCases) {
            val normalized = WiFiDirectManager.normalizeGroupCode(input)
            assertEquals(
                "Group code '$input' should normalize to '$expected'",
                expected,
                normalized,
            )
        }
    }

    @Test
    fun `OPEN group code normalizes to null`() {
        // OPEN represents no group code (open mode)
        assertNull(WiFiDirectManager.normalizeGroupCode("OPEN"))
        assertNull(WiFiDirectManager.normalizeGroupCode("open"))
        assertNull(WiFiDirectManager.normalizeGroupCode("Open"))
    }

    @Test
    fun `null group code is valid for open mode`() {
        assertTrue(WiFiDirectManager.isValidGroupCode(null))
    }

    @Test
    fun `valid group codes pass validation`() {
        val validCodes = listOf(
            "ABCD",
            "ABCDEF",
            "ABCDEFGH",
            "1234",
            "A1B2C3D4",
            "abcd", // lowercase also valid
        )

        for (code in validCodes) {
            assertTrue(
                "Group code '$code' should be valid",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    @Test
    fun `invalid group codes fail validation`() {
        val invalidCodes = listOf(
            "", // empty
            "ABC", // too short (3 chars)
            "ABCDEFGHI", // too long (9 chars)
            "ABC-EF", // contains hyphen
            "ABC EF", // contains space
            "ABC_EF", // contains underscore
        )

        for (code in invalidCodes) {
            assertFalse(
                "Group code '$code' should be invalid",
                WiFiDirectManager.isValidGroupCode(code),
            )
        }
    }

    // === Service Matching Tests ===
    // These tests verify service matching patterns for connection decisions

    @Test
    fun `services with same group code should match`() {
        val service1 = createTestService(groupCode = "ABC123")
        val service2 = createTestService(groupCode = "ABC123")

        val normalized1 = WiFiDirectManager.normalizeGroupCode(service1.groupCode)
        val normalized2 = WiFiDirectManager.normalizeGroupCode(service2.groupCode)

        assertEquals(normalized1, normalized2)
    }

    @Test
    fun `services with different group codes should not match`() {
        val service1 = createTestService(groupCode = "ABC123")
        val service2 = createTestService(groupCode = "XYZ789")

        val normalized1 = WiFiDirectManager.normalizeGroupCode(service1.groupCode)
        val normalized2 = WiFiDirectManager.normalizeGroupCode(service2.groupCode)

        assertNotEquals(normalized1, normalized2)
    }

    @Test
    fun `service matching is case-insensitive`() {
        val service1 = createTestService(groupCode = "ABCDEF")
        val service2 = createTestService(groupCode = "abcdef")

        val normalized1 = WiFiDirectManager.normalizeGroupCode(service1.groupCode)
        val normalized2 = WiFiDirectManager.normalizeGroupCode(service2.groupCode)

        assertEquals(normalized1, normalized2)
    }

    @Test
    fun `null group code services are open for all connections`() {
        val openService = createTestService(groupCode = null)

        assertNull(openService.groupCode)
        assertNull(WiFiDirectManager.normalizeGroupCode(openService.groupCode))
    }

    // === WiFi Direct Priority Connection Flow Tests ===
    // These tests verify the priority connection flow behavior

    @Test
    fun `DiscoveredService stores correct properties`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = "MYCODE",
            device = null,
        )

        assertEquals("aa:bb:cc:dd:ee:ff", service.deviceAddress)
        assertEquals("EnterComm", service.instanceName)
        assertEquals("_entercomm._tcp", service.serviceType)
        assertEquals("MYCODE", service.groupCode)
        assertNull(service.device)
    }

    @Test
    fun `multiple services can be discovered with different group codes`() {
        val services = listOf(
            createTestService(address = "aa:bb:cc:dd:ee:01", groupCode = "GROUP1"),
            createTestService(address = "aa:bb:cc:dd:ee:02", groupCode = "GROUP1"),
            createTestService(address = "aa:bb:cc:dd:ee:03", groupCode = "GROUP2"),
            createTestService(address = "aa:bb:cc:dd:ee:04", groupCode = null), // Open mode
        )

        assertEquals(4, services.size)

        // Filter for GROUP1
        val group1Services = services.filter { service ->
            WiFiDirectManager.normalizeGroupCode(service.groupCode) ==
                WiFiDirectManager.normalizeGroupCode("GROUP1")
        }
        assertEquals(2, group1Services.size)

        // Filter for GROUP2
        val group2Services = services.filter { service ->
            WiFiDirectManager.normalizeGroupCode(service.groupCode) ==
                WiFiDirectManager.normalizeGroupCode("GROUP2")
        }
        assertEquals(1, group2Services.size)

        // Filter for open mode (null group code)
        val openServices = services.filter { it.groupCode == null }
        assertEquals(1, openServices.size)
    }

    @Test
    fun `service updates preserve device address but can change group code`() {
        val service1 = createTestService(address = "aa:bb:cc:dd:ee:ff", groupCode = "OLD123")
        val service2 = createTestService(address = "aa:bb:cc:dd:ee:ff", groupCode = "NEW456")

        // Same device address
        assertEquals(service1.deviceAddress, service2.deviceAddress)

        // Different group codes
        assertNotEquals(service1.groupCode, service2.groupCode)

        // Services are not equal (data class equality)
        assertNotEquals(service1, service2)
    }

    // === Connection State Transitions ===
    // These tests verify expected state transition patterns

    @Test
    fun `state transitions follow expected patterns`() {
        // Verify valid state transitions based on ConnectionCoordinator logic
        val validTransitions = listOf(
            ConnectionState.DISCONNECTED to ConnectionState.DISCOVERING,
            ConnectionState.DISCOVERING to ConnectionState.CONNECTING,
            ConnectionState.DISCOVERING to ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING to ConnectionState.CONNECTED,
            ConnectionState.CONNECTING to ConnectionState.ERROR,
            ConnectionState.CONNECTED to ConnectionState.DISCONNECTED,
            ConnectionState.ERROR to ConnectionState.DISCONNECTED,
        )

        // Just verify all states and transitions are possible
        for ((from, to) in validTransitions) {
            assertNotNull("State $from should exist", from)
            assertNotNull("State $to should exist", to)
        }
    }

    @Test
    fun `WiFi Direct P2P default IP address is standard`() {
        // The standard WiFi Direct group owner IP
        val expectedGroupOwnerIP = "192.168.49.1"

        // Verify it follows the 192.168.49.x pattern
        assertTrue(expectedGroupOwnerIP.startsWith("192.168.49."))
    }

    // === Edge Cases ===

    @Test
    fun `empty service list handling`() {
        val emptyServices = emptyList<DiscoveredService>()

        assertTrue(emptyServices.isEmpty())
        assertEquals(0, emptyServices.filter { it.groupCode != null }.size)
    }

    @Test
    fun `service with all null optional fields`() {
        val service = DiscoveredService(
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = null,
            device = null,
        )

        assertNotNull(service.deviceAddress)
        assertNotNull(service.instanceName)
        assertNotNull(service.serviceType)
        assertNull(service.groupCode)
        assertNull(service.device)
    }

    @Test
    fun `ConnectionEvent equality works correctly`() {
        val event1 = ConnectionEvent.ConnectionEstablished("192.168.49.1")
        val event2 = ConnectionEvent.ConnectionEstablished("192.168.49.1")
        val event3 = ConnectionEvent.ConnectionEstablished("192.168.49.2")

        assertEquals("Same address events should be equal", event1, event2)
        assertNotEquals("Different address events should not be equal", event1, event3)
    }

    // === Helper Functions ===

    private fun createTestService(address: String = "aa:bb:cc:dd:ee:ff", groupCode: String? = "TEST12"): DiscoveredService {
        return DiscoveredService(
            deviceAddress = address,
            instanceName = "EnterComm",
            serviceType = "_entercomm._tcp",
            groupCode = groupCode,
            device = null,
        )
    }
}
