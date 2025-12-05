package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DistanceVectorRouterTest {

    private lateinit var router: DistanceVectorRouter

    @Before
    fun setUp() {
        Logger.isTestMode = true
        router = DistanceVectorRouter("node-local")
        router.initialize()
    }

    @After
    fun tearDown() {
        router.clear()
        Logger.isTestMode = false
    }

    // === Neighbor Management Tests ===

    @Test
    fun `addNeighbor creates direct route`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val route = router.getRoute("node-a")
        assertNotNull("Route should exist", route)
        assertEquals("node-a", route!!.destination)
        assertEquals("node-a", route.nextHop)
        assertEquals(1, route.hopCount)
        assertTrue(route.isDirectNeighbor)
    }

    @Test
    fun `addNeighbor ignores self`() {
        router.addNeighbor("node-local", "192.168.1.1", 8888)

        val route = router.getRoute("node-local")
        assertNull("Should not create route to self", route)
    }

    @Test
    fun `removeNeighbor poisons routes through that neighbor`() {
        // Add neighbors
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        router.addNeighbor("node-b", "192.168.1.3", 8888)

        // Simulate learning route to node-c through node-a
        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(
                DistanceVectorRouter.AdvertisedRoute("node-c", 1, 1),
            ),
        )
        router.processRouteAdvertisement(advertisement, "192.168.1.2")

        // Verify route exists
        assertTrue(router.isReachable("node-c"))

        // Remove node-a
        router.removeNeighbor("node-a")

        // Route through node-a should be poisoned
        assertFalse("Route through removed neighbor should be poisoned", router.isReachable("node-c"))
    }

    @Test
    fun `getNeighbors returns only alive neighbors`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        router.addNeighbor("node-b", "192.168.1.3", 8888)

        val neighbors = router.getNeighbors()
        assertEquals(2, neighbors.size)
        assertTrue(neighbors.any { it.nodeId == "node-a" })
        assertTrue(neighbors.any { it.nodeId == "node-b" })
    }

    // === Routing Tests ===

    @Test
    fun `getNextHop returns correct neighbor for direct route`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val nextHop = router.getNextHop("node-a")
        assertEquals("node-a", nextHop)
    }

    @Test
    fun `getNextHop returns null for unknown destination`() {
        val nextHop = router.getNextHop("unknown-node")
        assertNull(nextHop)
    }

    @Test
    fun `getNextHop returns null for self`() {
        val nextHop = router.getNextHop("node-local")
        assertNull(nextHop)
    }

    @Test
    fun `isReachable returns true for self`() {
        assertTrue(router.isReachable("node-local"))
    }

    @Test
    fun `isReachable returns true for direct neighbors`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        assertTrue(router.isReachable("node-a"))
    }

    @Test
    fun `isReachable returns false for unknown nodes`() {
        assertFalse(router.isReachable("unknown-node"))
    }

    // === Route Advertisement Tests ===

    @Test
    fun `processRouteAdvertisement adds new routes`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(
                DistanceVectorRouter.AdvertisedRoute("node-b", 1, 1),
                DistanceVectorRouter.AdvertisedRoute("node-c", 2, 2),
            ),
        )

        val changed = router.processRouteAdvertisement(advertisement, "192.168.1.2")
        assertTrue("Table should have changed", changed)

        assertTrue(router.isReachable("node-b"))
        assertTrue(router.isReachable("node-c"))

        val routeToB = router.getRoute("node-b")
        assertEquals("node-a", routeToB?.nextHop)
        assertEquals(2, routeToB?.hopCount) // 1 hop to node-a + 1 hop advertised
    }

    @Test
    fun `processRouteAdvertisement ignores routes from unknown neighbors`() {
        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "unknown-node",
            sequenceNumber = 1,
            routes = listOf(
                DistanceVectorRouter.AdvertisedRoute("node-b", 1, 1),
            ),
        )

        val changed = router.processRouteAdvertisement(advertisement, "192.168.1.99")
        assertFalse("Table should not change from unknown neighbor", changed)
        assertFalse(router.isReachable("node-b"))
    }

    @Test
    fun `processRouteAdvertisement ignores routes to self`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(
                // Route back to us
                DistanceVectorRouter.AdvertisedRoute("node-local", 1, 1),
            ),
        )

        val changed = router.processRouteAdvertisement(advertisement, "192.168.1.2")
        assertFalse("Should not add route to self", changed)
    }

    @Test
    fun `processRouteAdvertisement respects max hop count`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val advertisement = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(
                // Almost at max
                DistanceVectorRouter.AdvertisedRoute("node-far", 14, 14),
            ),
        )

        val changed = router.processRouteAdvertisement(advertisement, "192.168.1.2")
        assertTrue(changed)
        assertTrue(router.isReachable("node-far"))

        // Route with too many hops should be ignored
        val advertisement2 = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 2,
            routes = listOf(
                DistanceVectorRouter.AdvertisedRoute("node-too-far", 15, 15),
            ),
        )
        router.processRouteAdvertisement(advertisement2, "192.168.1.2")
        assertFalse("Route with too many hops should be ignored", router.isReachable("node-too-far"))
    }

    @Test
    fun `processRouteAdvertisement handles infinity metric as unreachable`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        // First add a route
        val ad1 = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-b", 1, 1)),
        )
        router.processRouteAdvertisement(ad1, "192.168.1.2")
        assertTrue(router.isReachable("node-b"))

        // Now advertise infinity (unreachable)
        // Use hopCount=14 so newHopCount (14+1=15) doesn't exceed MAX_HOP_COUNT
        val ad2 = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 2,
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-b", DistanceVectorRouter.INFINITY, 14)),
        )
        router.processRouteAdvertisement(ad2, "192.168.1.2")
        assertFalse("Node should be unreachable after infinity advertisement", router.isReachable("node-b"))
    }

    // === Route Advertisement Generation Tests ===

    @Test
    fun `generateRouteAdvertisement includes self with zero metric`() {
        val advertisement = router.generateRouteAdvertisement("node-a")

        val selfRoute = advertisement.routes.find { it.destination == "node-local" }
        assertNotNull("Should include route to self", selfRoute)
        assertEquals(0, selfRoute!!.metric)
        assertEquals(0, selfRoute.hopCount)
    }

    @Test
    fun `generateRouteAdvertisement implements split-horizon poison-reverse`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        router.addNeighbor("node-b", "192.168.1.3", 8888)

        // Learn route to node-c through node-a
        val ad = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-c", 1, 1)),
        )
        router.processRouteAdvertisement(ad, "192.168.1.2")

        // When advertising to node-a, route to node-c should be poisoned (infinity)
        val adForA = router.generateRouteAdvertisement("node-a")
        val routeToC = adForA.routes.find { it.destination == "node-c" }
        assertEquals("Route learned from neighbor should be poisoned", DistanceVectorRouter.INFINITY, routeToC?.metric)

        // When advertising to node-b, route to node-c should have normal metric
        val adForB = router.generateRouteAdvertisement("node-b")
        val routeToCForB = adForB.routes.find { it.destination == "node-c" }
        assertTrue("Route to node-c should be valid for other neighbors", routeToCForB!!.metric < DistanceVectorRouter.INFINITY)
    }

    // === Serialization Tests ===

    @Test
    fun `serializeAdvertisement and deserializeAdvertisement are reversible`() {
        val original = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 42,
            routes = listOf(
                DistanceVectorRouter.AdvertisedRoute("node-b", 2, 2),
                DistanceVectorRouter.AdvertisedRoute("node-c", 3, 3),
                // Infinity
                DistanceVectorRouter.AdvertisedRoute("node-d", 16, 16),
            ),
            timestamp = 1234567890L,
        )

        val serialized = router.serializeAdvertisement(original)
        val deserialized = router.deserializeAdvertisement(serialized)

        assertNotNull(deserialized)
        assertEquals(original.sourceNodeId, deserialized!!.sourceNodeId)
        assertEquals(original.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(original.routes.size, deserialized.routes.size)

        for (i in original.routes.indices) {
            assertEquals(original.routes[i].destination, deserialized.routes[i].destination)
            assertEquals(original.routes[i].metric, deserialized.routes[i].metric)
            assertEquals(original.routes[i].hopCount, deserialized.routes[i].hopCount)
        }
    }

    @Test
    fun `deserializeAdvertisement handles empty routes`() {
        val original = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = emptyList(),
        )

        val serialized = router.serializeAdvertisement(original)
        val deserialized = router.deserializeAdvertisement(serialized)

        assertNotNull(deserialized)
        assertTrue(deserialized!!.routes.isEmpty())
    }

    @Test
    fun `deserializeAdvertisement returns null for invalid data`() {
        val invalidData = "invalid|data".toByteArray()
        val result = router.deserializeAdvertisement(invalidData)
        assertNull(result)
    }

    // === Maintenance Tests ===

    @Test
    fun `clear removes all routes and neighbors`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        router.addNeighbor("node-b", "192.168.1.3", 8888)

        assertTrue(router.getNeighbors().isNotEmpty())
        assertTrue(router.getAllRoutes().isNotEmpty())

        router.clear()

        assertTrue(router.getNeighbors().isEmpty())
        assertTrue(router.getAllRoutes().isEmpty())
    }

    @Test
    fun `getReachableDestinations returns only valid routes`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)
        router.addNeighbor("node-b", "192.168.1.3", 8888)

        val reachable = router.getReachableDestinations()
        assertEquals(2, reachable.size)
        assertTrue(reachable.contains("node-a"))
        assertTrue(reachable.contains("node-b"))
    }

    @Test
    fun `getPathInfo returns correct information`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888)

        val pathInfo = router.getPathInfo("node-a")
        assertNotNull(pathInfo)
        assertEquals("node-a", pathInfo!!.destination)
        assertEquals("node-a", pathInfo.nextHop)
        assertEquals(1, pathInfo.hopCount)
        assertTrue(pathInfo.isDirectNeighbor)
    }

    @Test
    fun `getPathInfo returns null for unreachable destination`() {
        val pathInfo = router.getPathInfo("unknown-node")
        assertNull(pathInfo)
    }

    // === Multi-hop Routing Tests ===

    @Test
    fun `selects better route when multiple paths exist`() {
        router.addNeighbor("node-a", "192.168.1.2", 8888, linkQuality = 1.0f)
        router.addNeighbor("node-b", "192.168.1.3", 8888, linkQuality = 1.0f)

        // Learn route to node-c through node-a (2 hops)
        val ad1 = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-a",
            sequenceNumber = 1,
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-c", 1, 1)),
        )
        router.processRouteAdvertisement(ad1, "192.168.1.2")

        assertEquals("node-a", router.getNextHop("node-c"))

        // Learn better route through node-b (direct neighbor)
        val ad2 = DistanceVectorRouter.RouteAdvertisement(
            sourceNodeId = "node-b",
            sequenceNumber = 1,
            // Direct connection
            routes = listOf(DistanceVectorRouter.AdvertisedRoute("node-c", 0, 0)),
        )
        router.processRouteAdvertisement(ad2, "192.168.1.3")

        // Should now route through node-b (shorter path)
        assertEquals("node-b", router.getNextHop("node-c"))
    }
}
