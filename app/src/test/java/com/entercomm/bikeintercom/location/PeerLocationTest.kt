package com.entercomm.bikeintercom.location

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PeerLocationTest {

    // === Companion Object Validation Tests ===

    @Test
    fun `isValid returns true for valid coordinates`() {
        assertTrue(PeerLocation.isValid(0.0, 0.0)) // Equator, Prime Meridian
        assertTrue(PeerLocation.isValid(90.0, 180.0)) // North Pole, Date Line
        assertTrue(PeerLocation.isValid(-90.0, -180.0)) // South Pole, Date Line
        assertTrue(PeerLocation.isValid(51.5074, -0.1278)) // London
        assertTrue(PeerLocation.isValid(-33.8688, 151.2093)) // Sydney
        assertTrue(PeerLocation.isValid(35.6762, 139.6503)) // Tokyo
    }

    @Test
    fun `isValid returns false for latitude greater than 90`() {
        assertFalse(PeerLocation.isValid(90.1, 0.0))
        assertFalse(PeerLocation.isValid(91.0, 0.0))
        assertFalse(PeerLocation.isValid(180.0, 0.0))
    }

    @Test
    fun `isValid returns false for latitude less than -90`() {
        assertFalse(PeerLocation.isValid(-90.1, 0.0))
        assertFalse(PeerLocation.isValid(-91.0, 0.0))
        assertFalse(PeerLocation.isValid(-180.0, 0.0))
    }

    @Test
    fun `isValid returns false for longitude greater than 180`() {
        assertFalse(PeerLocation.isValid(0.0, 180.1))
        assertFalse(PeerLocation.isValid(0.0, 181.0))
        assertFalse(PeerLocation.isValid(0.0, 360.0))
    }

    @Test
    fun `isValid returns false for longitude less than -180`() {
        assertFalse(PeerLocation.isValid(0.0, -180.1))
        assertFalse(PeerLocation.isValid(0.0, -181.0))
        assertFalse(PeerLocation.isValid(0.0, -360.0))
    }

    @Test
    fun `isValid returns false for NaN coordinates`() {
        assertFalse(PeerLocation.isValid(Double.NaN, 0.0))
        assertFalse(PeerLocation.isValid(0.0, Double.NaN))
        assertFalse(PeerLocation.isValid(Double.NaN, Double.NaN))
    }

    @Test
    fun `isValid returns false for infinite coordinates`() {
        assertFalse(PeerLocation.isValid(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(PeerLocation.isValid(0.0, Double.NEGATIVE_INFINITY))
    }

    // === Accuracy Validation Tests ===

    @Test
    fun `hasAcceptableAccuracy returns true for good accuracy`() {
        assertTrue(PeerLocation.hasAcceptableAccuracy(0f))
        assertTrue(PeerLocation.hasAcceptableAccuracy(5f))
        assertTrue(PeerLocation.hasAcceptableAccuracy(25f))
        assertTrue(PeerLocation.hasAcceptableAccuracy(50f))
    }

    @Test
    fun `hasAcceptableAccuracy returns false for poor accuracy`() {
        assertFalse(PeerLocation.hasAcceptableAccuracy(50.1f))
        assertFalse(PeerLocation.hasAcceptableAccuracy(100f))
        assertFalse(PeerLocation.hasAcceptableAccuracy(1000f))
    }

    @Test
    fun `hasAcceptableAccuracy returns false for negative accuracy`() {
        assertFalse(PeerLocation.hasAcceptableAccuracy(-1f))
        assertFalse(PeerLocation.hasAcceptableAccuracy(-10f))
    }

    @Test
    fun `MAX_ACCURACY_METERS is reasonable value`() {
        assertTrue(
            "Max accuracy should be between 10 and 100 meters",
            PeerLocation.MAX_ACCURACY_METERS in 10f..100f,
        )
    }

    // === Instance Property Tests ===

    @Test
    fun `hasValidCoordinates property returns true for valid location`() {
        val location = createLocation(latitude = 40.7128, longitude = -74.0060)
        assertTrue(location.hasValidCoordinates)
    }

    @Test
    fun `hasValidCoordinates property returns false for invalid location`() {
        val location = createLocation(latitude = 91.0, longitude = 0.0)
        assertFalse(location.hasValidCoordinates)
    }

    @Test
    fun `hasAcceptableAccuracy property returns true for good accuracy`() {
        val location = createLocation(accuracy = 10f)
        assertTrue(location.hasAcceptableAccuracy)
    }

    @Test
    fun `hasAcceptableAccuracy property returns false for poor accuracy`() {
        val location = createLocation(accuracy = 100f)
        assertFalse(location.hasAcceptableAccuracy)
    }

    // === Distance Calculation Tests ===

    @Test
    fun `distanceTo returns zero for same location`() {
        val location = createLocation(latitude = 40.7128, longitude = -74.0060)
        assertEquals(0f, location.distanceTo(location), 0.1f)
    }

    @Test
    fun `distanceTo calculates correct distance for known locations`() {
        // New York to Los Angeles is approximately 3,940 km
        val newYork = createLocation(latitude = 40.7128, longitude = -74.0060)
        val losAngeles = createLocation(latitude = 34.0522, longitude = -118.2437)

        val distance = newYork.distanceTo(losAngeles)

        // Allow 5% tolerance for Haversine approximation
        val expectedDistance = 3940000f // meters
        assertTrue(
            "Distance should be approximately 3940km, got ${distance / 1000}km",
            abs(distance - expectedDistance) < expectedDistance * 0.05f,
        )
    }

    @Test
    fun `distanceTo calculates short distances accurately`() {
        // Two points about 1km apart in Manhattan
        val point1 = createLocation(latitude = 40.7580, longitude = -73.9855) // Times Square
        val point2 = createLocation(latitude = 40.7484, longitude = -73.9857) // Empire State

        val distance = point1.distanceTo(point2)

        // These are about 1km apart
        assertTrue(
            "Distance should be approximately 1km, got ${distance}m",
            distance in 900f..1200f,
        )
    }

    @Test
    fun `distanceTo is symmetric`() {
        val location1 = createLocation(latitude = 51.5074, longitude = -0.1278) // London
        val location2 = createLocation(latitude = 48.8566, longitude = 2.3522) // Paris

        val distance1to2 = location1.distanceTo(location2)
        val distance2to1 = location2.distanceTo(location1)

        assertEquals(
            "Distance should be symmetric",
            distance1to2,
            distance2to1,
            1f, // Allow 1 meter tolerance
        )
    }

    // === Bearing Calculation Tests ===

    @Test
    fun `bearingTo returns 0 for point due north`() {
        val origin = createLocation(latitude = 40.0, longitude = -74.0)
        val north = createLocation(latitude = 41.0, longitude = -74.0)

        val bearing = origin.bearingTo(north)

        // Due north should be close to 0 degrees
        assertTrue(
            "Bearing due north should be close to 0, got $bearing",
            bearing < 5f || bearing > 355f,
        )
    }

    @Test
    fun `bearingTo returns 90 for point due east`() {
        val origin = createLocation(latitude = 40.0, longitude = -74.0)
        val east = createLocation(latitude = 40.0, longitude = -73.0)

        val bearing = origin.bearingTo(east)

        // Due east should be close to 90 degrees
        assertTrue(
            "Bearing due east should be close to 90, got $bearing",
            bearing in 85f..95f,
        )
    }

    @Test
    fun `bearingTo returns 180 for point due south`() {
        val origin = createLocation(latitude = 40.0, longitude = -74.0)
        val south = createLocation(latitude = 39.0, longitude = -74.0)

        val bearing = origin.bearingTo(south)

        // Due south should be close to 180 degrees
        assertTrue(
            "Bearing due south should be close to 180, got $bearing",
            bearing in 175f..185f,
        )
    }

    @Test
    fun `bearingTo returns 270 for point due west`() {
        val origin = createLocation(latitude = 40.0, longitude = -74.0)
        val west = createLocation(latitude = 40.0, longitude = -75.0)

        val bearing = origin.bearingTo(west)

        // Due west should be close to 270 degrees
        assertTrue(
            "Bearing due west should be close to 270, got $bearing",
            bearing in 265f..275f,
        )
    }

    @Test
    fun `bearingTo returns value in 0-360 range`() {
        val locations = listOf(
            createLocation(latitude = 0.0, longitude = 0.0) to createLocation(latitude = 10.0, longitude = 10.0),
            createLocation(latitude = 45.0, longitude = 90.0) to createLocation(latitude = -45.0, longitude = -90.0),
            createLocation(latitude = -30.0, longitude = 150.0) to createLocation(latitude = 60.0, longitude = -120.0),
        )

        for ((from, to) in locations) {
            val bearing = from.bearingTo(to)
            assertTrue(
                "Bearing should be in [0, 360), got $bearing",
                bearing in 0f..360f,
            )
        }
    }

    // === Data Class Behavior Tests ===

    @Test
    fun `two locations with same data are equal`() {
        val location1 = createLocation(latitude = 40.0, longitude = -74.0, nodeId = "node1")
        val location2 = createLocation(latitude = 40.0, longitude = -74.0, nodeId = "node1")

        assertEquals(location1, location2)
    }

    @Test
    fun `two locations with different data are not equal`() {
        val location1 = createLocation(latitude = 40.0, longitude = -74.0)
        val location2 = createLocation(latitude = 41.0, longitude = -74.0)

        assertNotEquals(location1, location2)
    }

    @Test
    fun `copy preserves data correctly`() {
        val original = createLocation(
            latitude = 40.0,
            longitude = -74.0,
            accuracy = 10f,
            speed = 5f,
        )

        val copy = original.copy(speed = 10f)

        assertEquals(original.latitude, copy.latitude, 0.0001)
        assertEquals(original.longitude, copy.longitude, 0.0001)
        assertEquals(original.accuracy, copy.accuracy, 0.0001f)
        assertEquals(10f, copy.speed, 0.0001f)
    }

    // === Helper Functions ===

    private fun createLocation(
        nodeId: String = "test-node",
        nickname: String = "Test",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        altitude: Double = 0.0,
        accuracy: Float = 10f,
        bearing: Float = 0f,
        speed: Float = 0f,
        timestamp: Long = System.currentTimeMillis(),
    ) = PeerLocation(
        nodeId = nodeId,
        nickname = nickname,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        bearing = bearing,
        speed = speed,
        timestamp = timestamp,
    )
}
