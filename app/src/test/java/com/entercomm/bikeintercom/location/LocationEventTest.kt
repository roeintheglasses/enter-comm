package com.entercomm.bikeintercom.location

import org.junit.Assert.*
import org.junit.Test

class LocationEventTest {

    // === LocationEvent Sealed Class Structure Tests ===

    @Test
    fun `all expected LocationEvent types can be instantiated`() {
        // Create test data
        val testLocation = PeerLocation(
            nodeId = "node-123",
            nickname = "TestRider",
            latitude = 37.7749,
            longitude = -122.4194,
        )

        // Verify all expected LocationEvent types exist and can be created
        val allEventTypes: List<LocationEvent> = listOf(
            LocationEvent.ProviderUnavailable("gps"),
            LocationEvent.PermissionDenied,
            LocationEvent.TrackingStarted("gps"),
            LocationEvent.TrackingStopped,
            LocationEvent.LocationUpdated(testLocation),
        )

        assertEquals("Should have 5 LocationEvent types", 5, allEventTypes.size)

        // Verify all are instances of LocationEvent
        for (event in allEventTypes) {
            assertTrue(
                "All types should be instances of LocationEvent",
                event is LocationEvent,
            )
        }
    }

    // === ProviderUnavailable Event Tests ===

    @Test
    fun `ProviderUnavailable stores provider correctly`() {
        val provider = "gps"
        val event = LocationEvent.ProviderUnavailable(provider)

        assertEquals(provider, event.provider)
    }

    @Test
    fun `ProviderUnavailable is instance of LocationEvent`() {
        val event = LocationEvent.ProviderUnavailable("network")

        assertTrue(event is LocationEvent)
    }

    @Test
    fun `ProviderUnavailable with empty provider is valid`() {
        val event = LocationEvent.ProviderUnavailable("")

        assertEquals("", event.provider)
    }

    @Test
    fun `ProviderUnavailable with various providers is valid`() {
        val providers = listOf("gps", "network", "fused", "passive", "none", "system", "error")

        for (provider in providers) {
            val event = LocationEvent.ProviderUnavailable(provider)
            assertEquals(provider, event.provider)
        }
    }

    // === PermissionDenied Event Tests ===

    @Test
    fun `PermissionDenied is data object`() {
        val event1 = LocationEvent.PermissionDenied
        val event2 = LocationEvent.PermissionDenied

        assertSame("PermissionDenied should be singleton", event1, event2)
    }

    @Test
    fun `PermissionDenied is instance of LocationEvent`() {
        val event = LocationEvent.PermissionDenied

        assertTrue(event is LocationEvent)
    }

    // === TrackingStarted Event Tests ===

    @Test
    fun `TrackingStarted stores provider correctly`() {
        val provider = "gps"
        val event = LocationEvent.TrackingStarted(provider)

        assertEquals(provider, event.provider)
    }

    @Test
    fun `TrackingStarted is instance of LocationEvent`() {
        val event = LocationEvent.TrackingStarted("network")

        assertTrue(event is LocationEvent)
    }

    @Test
    fun `TrackingStarted with empty provider is valid`() {
        val event = LocationEvent.TrackingStarted("")

        assertEquals("", event.provider)
    }

    @Test
    fun `TrackingStarted with various providers is valid`() {
        val providers = listOf("gps", "network", "fused", "passive")

        for (provider in providers) {
            val event = LocationEvent.TrackingStarted(provider)
            assertEquals(provider, event.provider)
        }
    }

    // === TrackingStopped Event Tests ===

    @Test
    fun `TrackingStopped is data object`() {
        val event1 = LocationEvent.TrackingStopped
        val event2 = LocationEvent.TrackingStopped

        assertSame("TrackingStopped should be singleton", event1, event2)
    }

    @Test
    fun `TrackingStopped is instance of LocationEvent`() {
        val event = LocationEvent.TrackingStopped

        assertTrue(event is LocationEvent)
    }

    // === LocationUpdated Event Tests ===

    @Test
    fun `LocationUpdated stores location correctly`() {
        val location = PeerLocation(
            nodeId = "node-abc",
            nickname = "RiderOne",
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0,
            accuracy = 5.0f,
            bearing = 90.0f,
            speed = 10.0f,
        )
        val event = LocationEvent.LocationUpdated(location)

        assertEquals(location, event.location)
        assertEquals("node-abc", event.location.nodeId)
        assertEquals("RiderOne", event.location.nickname)
        assertEquals(40.7128, event.location.latitude, 0.0001)
        assertEquals(-74.0060, event.location.longitude, 0.0001)
    }

    @Test
    fun `LocationUpdated is instance of LocationEvent`() {
        val location = PeerLocation(
            nodeId = "node-123",
            nickname = "Rider",
            latitude = 0.0,
            longitude = 0.0,
        )
        val event = LocationEvent.LocationUpdated(location)

        assertTrue(event is LocationEvent)
    }

    @Test
    fun `LocationUpdated preserves all PeerLocation properties`() {
        val timestamp = System.currentTimeMillis()
        val location = PeerLocation(
            nodeId = "node-xyz",
            nickname = "SpeedRider",
            latitude = 51.5074,
            longitude = -0.1278,
            altitude = 15.5,
            accuracy = 3.0f,
            bearing = 180.0f,
            speed = 25.5f,
            timestamp = timestamp,
        )
        val event = LocationEvent.LocationUpdated(location)

        assertEquals("node-xyz", event.location.nodeId)
        assertEquals("SpeedRider", event.location.nickname)
        assertEquals(51.5074, event.location.latitude, 0.0001)
        assertEquals(-0.1278, event.location.longitude, 0.0001)
        assertEquals(15.5, event.location.altitude, 0.001)
        assertEquals(3.0f, event.location.accuracy, 0.001f)
        assertEquals(180.0f, event.location.bearing, 0.001f)
        assertEquals(25.5f, event.location.speed, 0.001f)
        assertEquals(timestamp, event.location.timestamp)
    }

    // === Pattern Matching Tests ===

    @Test
    fun `LocationEvent types can be matched with when expression`() {
        val testLocation = PeerLocation(
            nodeId = "node-123",
            nickname = "Rider",
            latitude = 0.0,
            longitude = 0.0,
        )

        val events: List<LocationEvent> = listOf(
            LocationEvent.ProviderUnavailable("gps"),
            LocationEvent.PermissionDenied,
            LocationEvent.TrackingStarted("gps"),
            LocationEvent.TrackingStopped,
            LocationEvent.LocationUpdated(testLocation),
        )

        for (event in events) {
            val result = when (event) {
                is LocationEvent.ProviderUnavailable -> "ProviderUnavailable"
                is LocationEvent.PermissionDenied -> "PermissionDenied"
                is LocationEvent.TrackingStarted -> "TrackingStarted"
                is LocationEvent.TrackingStopped -> "TrackingStopped"
                is LocationEvent.LocationUpdated -> "LocationUpdated"
            }

            assertNotNull("Pattern matching should work for all types", result)
        }
    }

    @Test
    fun `error events can be distinguished from lifecycle events`() {
        val testLocation = PeerLocation(
            nodeId = "node-123",
            nickname = "Rider",
            latitude = 0.0,
            longitude = 0.0,
        )

        // Error events
        val errorEvents: List<LocationEvent> = listOf(
            LocationEvent.ProviderUnavailable("gps"),
            LocationEvent.PermissionDenied,
        )

        // Lifecycle events
        val lifecycleEvents: List<LocationEvent> = listOf(
            LocationEvent.TrackingStarted("gps"),
            LocationEvent.TrackingStopped,
            LocationEvent.LocationUpdated(testLocation),
        )

        // Verify we can distinguish them
        fun isErrorEvent(event: LocationEvent): Boolean = when (event) {
            is LocationEvent.ProviderUnavailable -> true
            is LocationEvent.PermissionDenied -> true
            else -> false
        }

        for (event in errorEvents) {
            assertTrue("Error event should be classified as error: $event", isErrorEvent(event))
        }

        for (event in lifecycleEvents) {
            assertFalse("Lifecycle event should not be classified as error: $event", isErrorEvent(event))
        }
    }

    // === Edge Cases ===

    @Test
    fun `LocationEvent with special characters in provider is valid`() {
        val specialChars = "!@#$%^&*()_+-={}|[]\\:\";'<>?,./`~"

        val providerUnavailable = LocationEvent.ProviderUnavailable(specialChars)
        val trackingStarted = LocationEvent.TrackingStarted(specialChars)

        assertEquals(specialChars, providerUnavailable.provider)
        assertEquals(specialChars, trackingStarted.provider)
    }

    @Test
    fun `LocationEvent with unicode in provider is valid`() {
        val unicodeProvider = "Provider with unicode: 中文 евро"

        val providerUnavailable = LocationEvent.ProviderUnavailable(unicodeProvider)
        val trackingStarted = LocationEvent.TrackingStarted(unicodeProvider)

        assertEquals(unicodeProvider, providerUnavailable.provider)
        assertEquals(unicodeProvider, trackingStarted.provider)
    }

    @Test
    fun `LocationUpdated with location at extreme coordinates is valid`() {
        // Test with extreme valid coordinates
        val northPole = PeerLocation(
            nodeId = "north",
            nickname = "NorthPole",
            latitude = 90.0,
            longitude = 0.0,
        )
        val southPole = PeerLocation(
            nodeId = "south",
            nickname = "SouthPole",
            latitude = -90.0,
            longitude = 0.0,
        )
        val dateLine = PeerLocation(
            nodeId = "dateline",
            nickname = "DateLine",
            latitude = 0.0,
            longitude = 180.0,
        )

        val eventNorth = LocationEvent.LocationUpdated(northPole)
        val eventSouth = LocationEvent.LocationUpdated(southPole)
        val eventDateLine = LocationEvent.LocationUpdated(dateLine)

        assertEquals(90.0, eventNorth.location.latitude, 0.001)
        assertEquals(-90.0, eventSouth.location.latitude, 0.001)
        assertEquals(180.0, eventDateLine.location.longitude, 0.001)
    }

    @Test
    fun `LocationUpdated with location containing unicode nickname is valid`() {
        val location = PeerLocation(
            nodeId = "node-123",
            nickname = "Rider 中文 евро",
            latitude = 37.7749,
            longitude = -122.4194,
        )
        val event = LocationEvent.LocationUpdated(location)

        assertEquals("Rider 中文 евро", event.location.nickname)
    }

    @Test
    fun `TrackingStarted and ProviderUnavailable are distinct events with same provider`() {
        val provider = "gps"

        val trackingEvent = LocationEvent.TrackingStarted(provider)
        val unavailableEvent = LocationEvent.ProviderUnavailable(provider)

        // Both have same provider but are different types
        assertEquals(trackingEvent.provider, unavailableEvent.provider)
        assertNotEquals(trackingEvent::class, unavailableEvent::class)
    }

    @Test
    fun `PermissionDenied and TrackingStopped are distinct data objects`() {
        val permissionEvent = LocationEvent.PermissionDenied
        val stoppedEvent = LocationEvent.TrackingStopped

        // Both are data objects but different types
        assertNotEquals(permissionEvent::class, stoppedEvent::class)
    }

    @Test
    fun `multiple LocationUpdated events with different locations are independent`() {
        val location1 = PeerLocation(
            nodeId = "node-1",
            nickname = "Rider1",
            latitude = 37.7749,
            longitude = -122.4194,
        )
        val location2 = PeerLocation(
            nodeId = "node-2",
            nickname = "Rider2",
            latitude = 40.7128,
            longitude = -74.0060,
        )

        val event1 = LocationEvent.LocationUpdated(location1)
        val event2 = LocationEvent.LocationUpdated(location2)

        assertNotEquals(event1.location, event2.location)
        assertNotEquals(event1.location.nodeId, event2.location.nodeId)
        assertNotEquals(event1.location.latitude, event2.location.latitude, 0.001)
    }
}
