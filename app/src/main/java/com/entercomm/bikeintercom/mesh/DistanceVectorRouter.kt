package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Distance Vector Routing implementation using Bellman-Ford algorithm.
 *
 * Features:
 * - Multi-hop routing with automatic path discovery
 * - Split-horizon with poison-reverse for loop prevention
 * - Route aging and expiration
 * - Link quality metrics support
 * - Triggered updates on topology changes
 *
 * Protocol:
 * - Each node maintains a routing table with (destination, nextHop, metric, age)
 * - Nodes periodically advertise their routing tables to neighbors
 * - Routes are selected based on lowest metric (hop count + quality adjustment)
 * - Unreachable routes are poisoned with INFINITY metric
 */
class DistanceVectorRouter(
    private val localNodeId: String,
) {
    companion object {
        // Routing constants
        const val INFINITY = 16 // Maximum hop count (unreachable)
        const val MAX_HOP_COUNT = 15 // Maximum valid hop count
        const val ROUTE_TIMEOUT_MS = 30000L // Route expires after 30s without update
        const val ROUTE_FLUSH_MS = 60000L // Route removed after 60s
        const val UPDATE_INTERVAL_MS = 10000L // Periodic update interval

        // Route advertisement format version
        const val ROUTE_AD_VERSION = 1
    }

    /**
     * Routing table entry with extended metrics.
     */
    data class RouteEntry(
        val destination: String, // Destination node ID
        val nextHop: String, // Next hop node ID (direct neighbor)
        val metric: Int, // Total cost (hop count + adjustments)
        val hopCount: Int, // Number of hops to destination
        val lastUpdated: Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + ROUTE_TIMEOUT_MS,
        val isDirectNeighbor: Boolean = false,
        val linkQuality: Float = 1.0f, // 0.0-1.0 link quality estimate
        val sequenceNumber: Int = 0, // For loop detection
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() > expiresAt

        val isStale: Boolean
            get() = System.currentTimeMillis() > lastUpdated + ROUTE_TIMEOUT_MS

        val shouldFlush: Boolean
            get() = System.currentTimeMillis() > lastUpdated + ROUTE_FLUSH_MS
    }

    /**
     * Neighbor information for direct connections.
     */
    data class Neighbor(
        val nodeId: String,
        val ipAddress: String,
        val port: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val linkQuality: Float = 1.0f,
        val rtt: Long = 0L, // Round-trip time in ms
    ) {
        val isAlive: Boolean
            get() = System.currentTimeMillis() - lastSeen < ROUTE_TIMEOUT_MS
    }

    /**
     * Route advertisement packet structure.
     */
    data class RouteAdvertisement(
        val version: Int = ROUTE_AD_VERSION,
        val sourceNodeId: String,
        val sequenceNumber: Int,
        val routes: List<AdvertisedRoute>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Individual route in an advertisement.
     */
    data class AdvertisedRoute(
        val destination: String,
        val metric: Int,
        val hopCount: Int,
    )

    // Routing table: destination -> RouteEntry
    private val routingTable = ConcurrentHashMap<String, RouteEntry>()

    // Direct neighbors: nodeId -> Neighbor
    private val neighbors = ConcurrentHashMap<String, Neighbor>()

    // Track last seen sequence number per source node for stale advertisement rejection
    private val lastSeenSequenceNumbers = ConcurrentHashMap<String, Int>()

    // Sequence number for route advertisements
    private var localSequenceNumber = 0

    // State flows for observing changes
    private val _routeTableChanged = MutableStateFlow(0L)
    val routeTableChanged: StateFlow<Long> = _routeTableChanged.asStateFlow()

    // Pending triggered updates
    @Volatile
    private var pendingTriggeredUpdate = false

    /**
     * Initialize the router.
     */
    fun initialize() {
        logD { "Distance Vector Router initialized for node: $localNodeId" }
        routingTable.clear()
        neighbors.clear()
        lastSeenSequenceNumbers.clear()
        localSequenceNumber = 0
    }

    /**
     * Add or update a direct neighbor.
     * Called when a node is discovered via WiFi Direct or broadcast.
     */
    fun addNeighbor(nodeId: String, ipAddress: String, port: Int, linkQuality: Float = 1.0f) {
        if (nodeId == localNodeId) return

        val existingNeighbor = neighbors[nodeId]
        val now = System.currentTimeMillis()

        val neighbor = Neighbor(
            nodeId = nodeId,
            ipAddress = ipAddress,
            port = port,
            lastSeen = now,
            linkQuality = linkQuality,
            rtt = existingNeighbor?.rtt ?: 0L,
        )

        neighbors[nodeId] = neighbor

        // Add direct route to this neighbor
        val metric = calculateMetric(1, linkQuality)
        val existingRoute = routingTable[nodeId]

        // Update route if: new route, or better metric, or same but fresher
        if (existingRoute == null ||
            metric < existingRoute.metric ||
            (existingRoute.nextHop == nodeId && existingRoute.isStale)
        ) {
            routingTable[nodeId] = RouteEntry(
                destination = nodeId,
                nextHop = nodeId,
                metric = metric,
                hopCount = 1,
                isDirectNeighbor = true,
                linkQuality = linkQuality,
                sequenceNumber = ++localSequenceNumber,
            )

            logD { "Added/updated neighbor route: $nodeId via direct, metric=$metric" }
            notifyRouteChange()
        } else {
            // Just refresh the existing route
            routingTable[nodeId] = existingRoute.copy(
                lastUpdated = now,
                expiresAt = now + ROUTE_TIMEOUT_MS,
            )
        }
    }

    /**
     * Remove a neighbor (disconnected).
     */
    fun removeNeighbor(nodeId: String) {
        neighbors.remove(nodeId)
        // Clear sequence number tracking so we accept fresh advertisements if they reconnect
        lastSeenSequenceNumbers.remove(nodeId)

        // Poison routes through this neighbor
        routingTable.forEach { (dest, route) ->
            if (route.nextHop == nodeId) {
                routingTable[dest] = route.copy(
                    metric = INFINITY,
                    lastUpdated = System.currentTimeMillis(),
                )
                logD { "Poisoned route to $dest (neighbor $nodeId disconnected)" }
            }
        }

        pendingTriggeredUpdate = true
        notifyRouteChange()
    }

    /**
     * Update neighbor's last seen time (from heartbeat).
     */
    fun updateNeighborHeartbeat(nodeId: String) {
        neighbors[nodeId]?.let { neighbor ->
            neighbors[nodeId] = neighbor.copy(lastSeen = System.currentTimeMillis())
        }

        routingTable[nodeId]?.let { route ->
            if (route.isDirectNeighbor) {
                val now = System.currentTimeMillis()
                routingTable[nodeId] = route.copy(
                    lastUpdated = now,
                    expiresAt = now + ROUTE_TIMEOUT_MS,
                )
            }
        }
    }

    /**
     * Process received route advertisement from a neighbor.
     * Implements Bellman-Ford with split-horizon poison-reverse.
     */
    @Suppress("UNUSED_PARAMETER")
    fun processRouteAdvertisement(advertisement: RouteAdvertisement, receivedFromIp: String): Boolean {
        val senderId = advertisement.sourceNodeId
        if (senderId == localNodeId) return false

        // Verify sender is a known neighbor
        val neighbor = neighbors[senderId]
        if (neighbor == null) {
            logW { "Received route ad from unknown neighbor: $senderId" }
            return false
        }

        // Validate sequence number to reject stale advertisements
        // This prevents processing old/replayed route updates that could cause routing loops
        val lastSeenSeq = lastSeenSequenceNumbers[senderId] ?: -1
        if (advertisement.sequenceNumber <= lastSeenSeq) {
            logD { "Ignoring stale route advertisement from $senderId (seq=${advertisement.sequenceNumber} <= $lastSeenSeq)" }
            return false
        }
        lastSeenSequenceNumbers[senderId] = advertisement.sequenceNumber

        logD { "Processing route advertisement from $senderId with ${advertisement.routes.size} routes (seq=${advertisement.sequenceNumber})" }

        var tableChanged = false

        for (advertisedRoute in advertisement.routes) {
            // Skip routes to ourselves
            if (advertisedRoute.destination == localNodeId) continue

            // Calculate new metric through this neighbor
            val neighborLinkCost = calculateMetric(1, neighbor.linkQuality)
            val newMetric = if (advertisedRoute.metric >= INFINITY) {
                INFINITY
            } else {
                minOf(advertisedRoute.metric + neighborLinkCost, INFINITY)
            }
            val newHopCount = advertisedRoute.hopCount + 1

            // Skip if too many hops
            if (newHopCount > MAX_HOP_COUNT) continue

            val existingRoute = routingTable[advertisedRoute.destination]

            // Bellman-Ford relaxation: accept if better path or refresh existing path
            val shouldUpdate = when {
                // No existing route
                existingRoute == null -> newMetric < INFINITY

                // Same next hop - always update (could be worse or better)
                existingRoute.nextHop == senderId -> true

                // Better metric through different path
                newMetric < existingRoute.metric -> true

                // Same metric but existing is stale
                newMetric == existingRoute.metric && existingRoute.isStale -> true

                else -> false
            }

            if (shouldUpdate) {
                val now = System.currentTimeMillis()
                routingTable[advertisedRoute.destination] = RouteEntry(
                    destination = advertisedRoute.destination,
                    nextHop = senderId,
                    metric = newMetric,
                    hopCount = newHopCount,
                    lastUpdated = now,
                    expiresAt = now + ROUTE_TIMEOUT_MS,
                    isDirectNeighbor = false,
                    sequenceNumber = advertisement.sequenceNumber,
                )

                logD {
                    "Updated route: ${advertisedRoute.destination} via $senderId, " +
                        "metric=$newMetric, hops=$newHopCount"
                }
                tableChanged = true
            }
        }

        if (tableChanged) {
            pendingTriggeredUpdate = true
            notifyRouteChange()
        }

        return tableChanged
    }

    /**
     * Generate route advertisement to send to neighbors.
     * Implements split-horizon with poison-reverse.
     */
    fun generateRouteAdvertisement(forNeighborId: String): RouteAdvertisement {
        val routes = mutableListOf<AdvertisedRoute>()

        // Add route to ourselves (metric 0)
        routes.add(
            AdvertisedRoute(
                destination = localNodeId,
                metric = 0,
                hopCount = 0,
            ),
        )

        // Add all known routes with split-horizon poison-reverse
        routingTable.forEach { (destination, route) ->
            val advertisedMetric = if (route.nextHop == forNeighborId) {
                // Poison-reverse: advertise infinity for routes learned from this neighbor
                INFINITY
            } else if (route.metric >= INFINITY || route.isExpired) {
                // Poison expired/unreachable routes
                INFINITY
            } else {
                route.metric
            }

            routes.add(
                AdvertisedRoute(
                    destination = destination,
                    metric = advertisedMetric,
                    hopCount = if (advertisedMetric >= INFINITY) INFINITY else route.hopCount,
                ),
            )
        }

        return RouteAdvertisement(
            sourceNodeId = localNodeId,
            sequenceNumber = ++localSequenceNumber,
            routes = routes,
        )
    }

    /**
     * Get the next hop for a destination.
     * Returns null if no route exists.
     */
    fun getNextHop(destinationId: String): String? {
        // Direct destination
        if (destinationId == localNodeId) return null

        val route = routingTable[destinationId]
        if (route == null || route.metric >= INFINITY || route.isExpired) {
            return null
        }

        return route.nextHop
    }

    /**
     * Get route information for a destination.
     */
    fun getRoute(destinationId: String): RouteEntry? {
        return routingTable[destinationId]?.takeIf {
            it.metric < INFINITY && !it.isExpired
        }
    }

    /**
     * Get all valid routes.
     */
    fun getAllRoutes(): List<RouteEntry> {
        return routingTable.values.filter { it.metric < INFINITY && !it.isExpired }
    }

    /**
     * Get all direct neighbors.
     */
    fun getNeighbors(): List<Neighbor> {
        return neighbors.values.filter { it.isAlive }
    }

    /**
     * Get neighbor by node ID.
     */
    fun getNeighbor(nodeId: String): Neighbor? {
        return neighbors[nodeId]?.takeIf { it.isAlive }
    }

    /**
     * Check if there's a pending triggered update.
     */
    fun hasPendingUpdate(): Boolean {
        return pendingTriggeredUpdate
    }

    /**
     * Clear the pending triggered update flag.
     */
    fun clearPendingUpdate() {
        pendingTriggeredUpdate = false
    }

    /**
     * Periodic maintenance: expire old routes, remove dead neighbors.
     */
    fun performMaintenance(): List<String> {
        val removedDestinations = mutableListOf<String>()
        val now = System.currentTimeMillis()

        // Remove dead neighbors
        val deadNeighbors = neighbors.filter { !it.value.isAlive }.keys
        deadNeighbors.forEach { nodeId ->
            neighbors.remove(nodeId)
            logD { "Removed dead neighbor: $nodeId" }
        }

        // Process routes
        routingTable.forEach { (destination, route) ->
            when {
                // Flush very old routes
                route.shouldFlush -> {
                    routingTable.remove(destination)
                    removedDestinations.add(destination)
                    logD { "Flushed route to $destination" }
                }

                // Poison expired routes
                route.isExpired && route.metric < INFINITY -> {
                    routingTable[destination] = route.copy(
                        metric = INFINITY,
                        lastUpdated = now,
                    )
                    logD { "Poisoned expired route to $destination" }
                    pendingTriggeredUpdate = true
                }

                // Poison routes through dead neighbors
                deadNeighbors.contains(route.nextHop) && route.metric < INFINITY -> {
                    routingTable[destination] = route.copy(
                        metric = INFINITY,
                        lastUpdated = now,
                    )
                    logD { "Poisoned route to $destination (dead next hop)" }
                    pendingTriggeredUpdate = true
                }
            }
        }

        if (removedDestinations.isNotEmpty() || deadNeighbors.isNotEmpty()) {
            notifyRouteChange()
        }

        return removedDestinations
    }

    /**
     * Get reachable destinations (nodes we can route to).
     */
    fun getReachableDestinations(): Set<String> {
        return routingTable.filter { (_, route) ->
            route.metric < INFINITY && !route.isExpired
        }.keys
    }

    /**
     * Check if a destination is reachable.
     */
    fun isReachable(destinationId: String): Boolean {
        if (destinationId == localNodeId) return true
        val route = routingTable[destinationId]
        return route != null && route.metric < INFINITY && !route.isExpired
    }

    /**
     * Get path to destination as list of node IDs.
     * Note: This only shows the next hop - full path requires hop-by-hop queries.
     */
    fun getPathInfo(destinationId: String): PathInfo? {
        val route = getRoute(destinationId) ?: return null
        return PathInfo(
            destination = destinationId,
            nextHop = route.nextHop,
            hopCount = route.hopCount,
            metric = route.metric,
            isDirectNeighbor = route.isDirectNeighbor,
        )
    }

    /**
     * Calculate metric from hop count and link quality.
     *
     * Uses logarithmic scaling for link quality penalty:
     * - linkQuality=1.0  → penalty=0  (perfect link)
     * - linkQuality=0.5  → penalty=3  (50% packet loss = ~3 extra hops)
     * - linkQuality=0.1  → penalty=10 (90% packet loss = 10 extra hops)
     * - linkQuality=0.01 → penalty=20 (99% packet loss = nearly unreachable)
     *
     * This properly penalizes poor links - a route through a 50% loss link
     * is equivalent to 3 additional hops through perfect links.
     */
    private fun calculateMetric(hopCount: Int, linkQuality: Float): Int {
        // Clamp link quality to avoid log(0) and ensure reasonable bounds
        val clampedQuality = linkQuality.coerceIn(0.01f, 1.0f)

        // Logarithmic penalty: -log10(quality) * 10
        // This scales naturally with packet loss severity
        val qualityPenalty = (-kotlin.math.log10(clampedQuality) * 10).toInt()

        return hopCount + qualityPenalty
    }

    private fun notifyRouteChange() {
        _routeTableChanged.value = System.currentTimeMillis()
    }

    /**
     * Serialize route advertisement to bytes for transmission.
     */
    fun serializeAdvertisement(advertisement: RouteAdvertisement): ByteArray {
        val sb = StringBuilder()
        sb.append("${advertisement.version}|")
        sb.append("${advertisement.sourceNodeId}|")
        sb.append("${advertisement.sequenceNumber}|")
        sb.append("${advertisement.timestamp}|")
        sb.append("${advertisement.routes.size}|")

        for (route in advertisement.routes) {
            sb.append("${route.destination}:${route.metric}:${route.hopCount};")
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Deserialize route advertisement from bytes.
     */
    fun deserializeAdvertisement(data: ByteArray): RouteAdvertisement? {
        return try {
            val str = String(data, Charsets.UTF_8)
            val parts = str.split("|")

            if (parts.size < 5) return null

            val version = parts[0].toInt()
            val sourceNodeId = parts[1]
            val sequenceNumber = parts[2].toInt()
            val timestamp = parts[3].toLong()
            // parts[4] is route count - used for validation but not stored

            if (parts.size < 6) {
                // No routes
                return RouteAdvertisement(
                    version = version,
                    sourceNodeId = sourceNodeId,
                    sequenceNumber = sequenceNumber,
                    routes = emptyList(),
                    timestamp = timestamp,
                )
            }

            val routesStr = parts[5]
            val routes = routesStr.split(";")
                .filter { it.isNotEmpty() }
                .mapNotNull { routeStr ->
                    val routeParts = routeStr.split(":")
                    if (routeParts.size == 3) {
                        AdvertisedRoute(
                            destination = routeParts[0],
                            metric = routeParts[1].toInt(),
                            hopCount = routeParts[2].toInt(),
                        )
                    } else {
                        null
                    }
                }

            RouteAdvertisement(
                version = version,
                sourceNodeId = sourceNodeId,
                sequenceNumber = sequenceNumber,
                routes = routes,
                timestamp = timestamp,
            )
        } catch (e: Exception) {
            logE({ "Failed to deserialize route advertisement" }, e)
            null
        }
    }

    /**
     * Debug: dump routing table.
     */
    fun dumpRoutingTable(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Routing Table for $localNodeId ===")
        sb.appendLine("Neighbors: ${neighbors.keys.joinToString(", ")}")
        sb.appendLine("Routes:")

        routingTable.values.sortedBy { it.hopCount }.forEach { route ->
            val status = when {
                route.metric >= INFINITY -> "UNREACHABLE"
                route.isExpired -> "EXPIRED"
                route.isStale -> "STALE"
                else -> "ACTIVE"
            }
            sb.appendLine(
                "  ${route.destination}: via ${route.nextHop}, " +
                    "metric=${route.metric}, hops=${route.hopCount}, " +
                    "direct=${route.isDirectNeighbor}, status=$status",
            )
        }

        return sb.toString()
    }

    /**
     * Clear all routing state.
     */
    fun clear() {
        routingTable.clear()
        neighbors.clear()
        lastSeenSequenceNumbers.clear()
        localSequenceNumber = 0
        pendingTriggeredUpdate = false
        notifyRouteChange()
    }
}

/**
 * Path information for a destination.
 */
data class PathInfo(
    val destination: String,
    val nextHop: String,
    val hopCount: Int,
    val metric: Int,
    val isDirectNeighbor: Boolean,
)
