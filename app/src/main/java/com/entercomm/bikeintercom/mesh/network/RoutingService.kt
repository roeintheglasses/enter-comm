package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.mesh.DistanceVectorRouter
import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.mesh.MeshNode
import com.entercomm.bikeintercom.mesh.MeshRoute
import com.entercomm.bikeintercom.mesh.MeshTopology
import com.entercomm.bikeintercom.mesh.PathInfo
import com.entercomm.bikeintercom.mesh.TopologyBuilder
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logW
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages mesh network routing, including route table maintenance,
 * route advertisements, and integration with the DistanceVectorRouter.
 */
@Suppress("TooManyFunctions", "ClassOrdering")
class RoutingService(
    private val nodeId: String,
    private val deviceName: String,
    private val router: DistanceVectorRouter,
    private val nodeRegistry: NodeRegistry,
    private val messageDispatcher: MessageDispatcher,
) {
    private companion object {
        const val MAX_ROUTE_AGE = 30_000L
    }

    // Legacy routing table for compatibility
    private val routingTable = ConcurrentHashMap<String, MeshRoute>()

    /**
     * Initialize the router.
     */
    fun initialize() {
        router.initialize()
        logD { "Distance vector router initialized" }
    }

    /**
     * Handle an incoming route update message.
     */
    fun handleRouteUpdate(message: MeshMessage) {
        val advertisement = router.deserializeAdvertisement(message.payload)
        if (advertisement == null) {
            logW { "Failed to parse route advertisement from ${message.sourceId}" }
            return
        }

        logD { "Processing route update from ${message.sourceId} with ${advertisement.routes.size} routes" }

        val senderNode = nodeRegistry.getNode(message.sourceId)
        if (senderNode == null) {
            logW { "Received route update from unknown node: ${message.sourceId}" }
            return
        }

        // Process the route advertisement (Bellman-Ford update)
        val changed = router.processRouteAdvertisement(advertisement, senderNode.ipAddress)

        if (changed) {
            syncRoutingTableFromRouter()
            updateRoutingStats()
            logD { "Routing table updated from ${message.sourceId}" }
            logD { router.dumpRoutingTable() }
        }
    }

    /**
     * Send route advertisements to all direct neighbors.
     */
    fun sendRouteAdvertisements() {
        val neighbors = router.getNeighbors()
        if (neighbors.isEmpty()) {
            return
        }

        logD { "Sending route advertisements to ${neighbors.size} neighbors" }

        for (neighbor in neighbors) {
            // Generate advertisement with split-horizon poison-reverse
            val advertisement = router.generateRouteAdvertisement(neighbor.nodeId)
            val payload = router.serializeAdvertisement(advertisement)

            val message = MeshMessage(
                sourceId = nodeId,
                destinationId = neighbor.nodeId,
                messageType = MeshMessage.MessageType.ROUTE_UPDATE,
                payload = payload,
                ttl = 1, // Route updates are single-hop only
            )

            val targetNode = MeshNode(
                nodeId = neighbor.nodeId,
                deviceName = neighbor.nodeId,
                ipAddress = neighbor.ipAddress,
                port = neighbor.port,
                isDirectConnection = true,
                hopCount = 1,
            )

            messageDispatcher.sendToNode(message, targetNode)
        }

        messageDispatcher.incrementRouteAdvertisementsSent(neighbors.size)
    }

    /**
     * Perform routing table maintenance.
     * @return List of removed destination IDs
     */
    fun performMaintenance(): List<String> {
        // Let the router expire routes and remove dead neighbors
        val removedDestinations = router.performMaintenance()

        // Check for triggered updates
        if (router.hasPendingUpdate()) {
            sendRouteAdvertisements()
            router.clearPendingUpdate()
        }

        // Clean up legacy routing table
        val currentTime = System.currentTimeMillis()
        val expiredRoutes = routingTable.filter { (_, route) ->
            currentTime - route.lastUpdated > MAX_ROUTE_AGE
        }.keys.toList()

        expiredRoutes.forEach { destinationId ->
            routingTable.remove(destinationId)
        }

        // Sync routing table
        syncRoutingTableFromRouter()
        updateRoutingStats()

        return removedDestinations
    }

    /**
     * Sync the legacy routing table with the distance vector router.
     */
    fun syncRoutingTableFromRouter() {
        routingTable.clear()

        router.getAllRoutes().forEach { routeEntry ->
            routingTable[routeEntry.destination] = MeshRoute(
                destinationId = routeEntry.destination,
                nextHop = routeEntry.nextHop,
                hopCount = routeEntry.hopCount,
                lastUpdated = routeEntry.lastUpdated,
            )
        }
    }

    /**
     * Update routing statistics in the message dispatcher.
     */
    fun updateRoutingStats() {
        val routes = router.getAllRoutes()
        val neighbors = router.getNeighbors()

        messageDispatcher.updateRoutingStats(
            totalRoutes = routes.size,
            directNeighbors = neighbors.size,
            multiHopRoutes = routes.count { !it.isDirectNeighbor },
            maxHopCount = routes.maxOfOrNull { it.hopCount } ?: 0,
        )
    }

    /**
     * Get routing table debug information.
     */
    fun getRoutingTableDump(): String = router.dumpRoutingTable()

    /**
     * Get path information to a destination.
     */
    fun getPathInfo(destinationId: String): PathInfo? = router.getPathInfo(destinationId)

    /**
     * Check if a destination is reachable via multi-hop routing.
     */
    fun isReachable(destinationId: String): Boolean = router.isReachable(destinationId)

    /**
     * Get all reachable destinations.
     */
    fun getReachableDestinations(): Set<String> = router.getReachableDestinations()

    /**
     * Add a route entry to the legacy routing table.
     */
    fun addRoute(route: MeshRoute) {
        routingTable[route.destinationId] = route
    }

    /**
     * Remove a route from the legacy routing table.
     */
    fun removeRoute(destinationId: String) {
        routingTable.remove(destinationId)
    }

    /**
     * Generate current mesh topology for visualization.
     */
    fun getMeshTopology(): MeshTopology {
        val builder = TopologyBuilder(nodeId, deviceName)

        // Add all nodes from the registry
        nodeRegistry.getAllNodes().forEach { (id, node) ->
            val routeEntry = router.getRoute(id)
            builder.addNode(
                nodeId = id,
                displayName = node.deviceName,
                isDirectNeighbor = routeEntry?.isDirectNeighbor ?: node.isDirectConnection,
                hopCount = routeEntry?.hopCount ?: node.hopCount,
                linkQuality = routeEntry?.linkQuality ?: node.linkQuality,
                lastSeen = node.lastSeen,
            )
        }

        // Add connections from router
        router.getNeighbors().forEach { neighbor ->
            builder.addConnection(
                fromNodeId = nodeId,
                toNodeId = neighbor.nodeId,
                linkQuality = neighbor.linkQuality,
                isDirect = true,
            )
        }

        // Add route paths
        router.getAllRoutes().forEach { route ->
            if (!route.isDirectNeighbor) {
                builder.addRoutePath(route.destination, listOf(route.nextHop, route.destination))
            }
        }

        return builder.build()
    }

    /**
     * Clear all routing data.
     */
    fun clear() {
        routingTable.clear()
        router.clear()
    }
}
