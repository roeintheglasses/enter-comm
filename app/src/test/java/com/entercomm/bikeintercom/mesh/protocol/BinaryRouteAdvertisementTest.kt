package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.DistanceVectorRouter.AdvertisedRoute
import com.entercomm.bikeintercom.mesh.DistanceVectorRouter.RouteAdvertisement
import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BinaryRouteAdvertisementTest {

    @Before
    fun setUp() {
        Logger.isTestMode = true
        NodeIdEncoder.clearCache()

        // Register test node IDs
        NodeIdEncoder.register("node-source")
        NodeIdEncoder.register("node-A")
        NodeIdEncoder.register("node-B")
        NodeIdEncoder.register("node-C")
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
        NodeIdEncoder.clearCache()
    }

    @Test
    fun `serialize produces correct header size`() {
        val advertisement = createAdvertisement(routes = emptyList())

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        assertEquals(BinaryRouteAdvertisement.HEADER_SIZE, serialized.size)
    }

    @Test
    fun `serialize includes route entries`() {
        val routes = listOf(
            AdvertisedRoute("node-A", metric = 1, hopCount = 1),
            AdvertisedRoute("node-B", metric = 2, hopCount = 2),
        )
        val advertisement = createAdvertisement(routes = routes)

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        val expectedSize = BinaryRouteAdvertisement.HEADER_SIZE +
            routes.size * BinaryRouteAdvertisement.ROUTE_ENTRY_SIZE
        assertEquals(expectedSize, serialized.size)
    }

    @Test
    fun `deserialize parses valid advertisement`() {
        val routes = listOf(
            AdvertisedRoute("node-A", metric = 1, hopCount = 1),
        )
        val advertisement = createAdvertisement(routes = routes)

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(advertisement.sourceNodeId, deserialized!!.sourceNodeId)
        assertEquals(advertisement.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(advertisement.timestamp, deserialized.timestamp)
    }

    @Test
    fun `round trip preserves all routes`() {
        val routes = listOf(
            AdvertisedRoute("node-A", metric = 1, hopCount = 1),
            AdvertisedRoute("node-B", metric = 5, hopCount = 3),
            AdvertisedRoute("node-C", metric = 10, hopCount = 5),
        )
        val advertisement = createAdvertisement(routes = routes)

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(routes.size, deserialized!!.routes.size)

        for (i in routes.indices) {
            assertEquals(routes[i].destination, deserialized.routes[i].destination)
            assertEquals(routes[i].metric, deserialized.routes[i].metric)
            assertEquals(routes[i].hopCount, deserialized.routes[i].hopCount)
        }
    }

    @Test
    fun `handles empty route list`() {
        val advertisement = createAdvertisement(routes = emptyList())

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(0, deserialized!!.routes.size)
    }

    @Test
    fun `handles maximum metric value`() {
        val routes = listOf(
            AdvertisedRoute("node-A", metric = 255, hopCount = 15),
        )
        val advertisement = createAdvertisement(routes = routes)

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(255, deserialized!!.routes[0].metric)
        assertEquals(15, deserialized.routes[0].hopCount)
    }

    @Test
    fun `rejects truncated header`() {
        val advertisement = createAdvertisement()
        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        // Try to deserialize truncated data
        val deserialized = BinaryRouteAdvertisement.deserialize(
            serialized,
            BinaryRouteAdvertisement.HEADER_SIZE - 5,
        )

        assertNull(deserialized)
    }

    @Test
    fun `rejects truncated route data`() {
        val routes = listOf(
            AdvertisedRoute("node-A", metric = 1, hopCount = 1),
            AdvertisedRoute("node-B", metric = 2, hopCount = 2),
        )
        val advertisement = createAdvertisement(routes = routes)
        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        // Truncate in the middle of routes
        val truncatedLength = BinaryRouteAdvertisement.HEADER_SIZE + 5
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized, truncatedLength)

        assertNull(deserialized)
    }

    @Test
    fun `rejects invalid version`() {
        val advertisement = createAdvertisement()
        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        // Corrupt version byte
        serialized[0] = 99.toByte()

        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNull(deserialized)
    }

    @Test
    fun `calculateSize returns correct value`() {
        val size0 = BinaryRouteAdvertisement.calculateSize(0)
        val size5 = BinaryRouteAdvertisement.calculateSize(5)
        val size10 = BinaryRouteAdvertisement.calculateSize(10)

        assertEquals(BinaryRouteAdvertisement.HEADER_SIZE, size0)
        assertEquals(
            BinaryRouteAdvertisement.HEADER_SIZE + 5 * BinaryRouteAdvertisement.ROUTE_ENTRY_SIZE,
            size5,
        )
        assertEquals(
            BinaryRouteAdvertisement.HEADER_SIZE + 10 * BinaryRouteAdvertisement.ROUTE_ENTRY_SIZE,
            size10,
        )
    }

    @Test
    fun `preserves sequence number`() {
        val advertisement = RouteAdvertisement(
            sourceNodeId = "node-source",
            sequenceNumber = 42,
            routes = emptyList(),
            timestamp = System.currentTimeMillis(),
        )

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(42, deserialized!!.sequenceNumber)
    }

    @Test
    fun `preserves timestamp`() {
        val timestamp = 1_703_176_800_000L
        val advertisement = RouteAdvertisement(
            sourceNodeId = "node-source",
            sequenceNumber = 1,
            routes = emptyList(),
            timestamp = timestamp,
        )

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)
        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(timestamp, deserialized!!.timestamp)
    }

    @Test
    fun `skips unknown destination but continues parsing`() {
        // Register only some nodes
        NodeIdEncoder.register("node-known")

        val routes = listOf(
            AdvertisedRoute("node-known", metric = 1, hopCount = 1),
            AdvertisedRoute("node-unknown", metric = 2, hopCount = 2), // Won't be decodeable
        )
        val advertisement = createAdvertisement(routes = routes)

        val serialized = BinaryRouteAdvertisement.serialize(advertisement)

        // Clear cache to simulate unknown nodes
        NodeIdEncoder.clearCache()
        NodeIdEncoder.register("node-source")
        NodeIdEncoder.register("node-known")

        val deserialized = BinaryRouteAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        // Should have only the known route
        assertEquals(1, deserialized!!.routes.size)
        assertEquals("node-known", deserialized.routes[0].destination)
    }

    private fun createAdvertisement(sourceNodeId: String = "node-source", sequenceNumber: Int = 1, routes: List<AdvertisedRoute> = listOf(AdvertisedRoute("node-A", 1, 1))): RouteAdvertisement {
        return RouteAdvertisement(
            sourceNodeId = sourceNodeId,
            sequenceNumber = sequenceNumber,
            routes = routes,
            timestamp = System.currentTimeMillis(),
        )
    }
}
