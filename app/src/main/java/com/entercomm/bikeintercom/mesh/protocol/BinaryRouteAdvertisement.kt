package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.DistanceVectorRouter.AdvertisedRoute
import com.entercomm.bikeintercom.mesh.DistanceVectorRouter.RouteAdvertisement
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logW
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary serialization for route advertisements.
 *
 * Binary format:
 * ```
 * Offset  Size  Field
 * ------  ----  -----
 * 0       1     Version
 * 1       8     Source node ID (hash)
 * 9       4     Sequence number
 * 13      8     Timestamp
 * 21      2     Route count
 * 23      N     Routes (10 bytes each)
 * ```
 *
 * Each route (10 bytes):
 * ```
 * Offset  Size  Field
 * ------  ----  -----
 * 0       8     Destination node ID (hash)
 * 8       1     Metric
 * 9       1     Hop count
 * ```
 *
 * Size comparison:
 * - Text format with 10 routes: ~250 bytes
 * - Binary format with 10 routes: 23 + (10 * 10) = 123 bytes
 * - Savings: ~50%
 */
object BinaryRouteAdvertisement {

    /** Binary route advertisement format version. */
    const val VERSION: Byte = 1

    /** Fixed header size (before routes). */
    const val HEADER_SIZE = 23

    /** Size of each route entry. */
    const val ROUTE_ENTRY_SIZE = 10

    /** Maximum number of routes in a single advertisement. */
    const val MAX_ROUTES = 255

    /**
     * Serialize a RouteAdvertisement to binary format.
     *
     * @param advertisement The route advertisement to serialize
     * @return Binary representation
     */
    fun serialize(advertisement: RouteAdvertisement): ByteArray {
        val routeCount = minOf(advertisement.routes.size, MAX_ROUTES)
        val totalSize = HEADER_SIZE + routeCount * ROUTE_ENTRY_SIZE

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Write header
        buffer.put(VERSION)
        buffer.putLong(NodeIdEncoder.encode(advertisement.sourceNodeId))
        buffer.putInt(advertisement.sequenceNumber)
        buffer.putLong(advertisement.timestamp)
        buffer.putShort(routeCount.toShort())

        // Write routes
        for (i in 0 until routeCount) {
            val route = advertisement.routes[i]
            buffer.putLong(NodeIdEncoder.encode(route.destination))
            buffer.put(route.metric.coerceIn(0, 255).toByte())
            buffer.put(route.hopCount.coerceIn(0, 255).toByte())
        }

        logD { "Serialized route advertisement: $totalSize bytes, $routeCount routes" }
        return buffer.array()
    }

    /**
     * Deserialize binary data to a RouteAdvertisement.
     *
     * @param data Raw binary data
     * @return Parsed RouteAdvertisement, or null if parsing failed
     */
    fun deserialize(data: ByteArray): RouteAdvertisement? {
        return deserialize(data, data.size)
    }

    /**
     * Deserialize binary data to a RouteAdvertisement.
     *
     * @param data Raw binary data
     * @param length Number of valid bytes in the data array
     * @return Parsed RouteAdvertisement, or null if parsing failed
     */
    fun deserialize(data: ByteArray, length: Int): RouteAdvertisement? {
        // Validate minimum length
        if (length < HEADER_SIZE) {
            logW { "Route advertisement too short: $length bytes (minimum: $HEADER_SIZE)" }
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        // Read and validate version
        val version = buffer.get()
        if (version != VERSION) {
            logW { "Unsupported route advertisement version: $version" }
            return null
        }

        // Read header fields
        val sourceIdEncoded = buffer.long
        val sequenceNumber = buffer.int
        val timestamp = buffer.long
        val routeCount = buffer.short.toInt() and 0xFFFF

        // Validate that we have enough data for all routes
        val expectedSize = HEADER_SIZE + routeCount * ROUTE_ENTRY_SIZE
        if (length < expectedSize) {
            logW { "Route advertisement truncated: got $length bytes, expected $expectedSize" }
            return null
        }

        // Decode source node ID
        val sourceNodeId = NodeIdEncoder.decode(sourceIdEncoded)
        if (sourceNodeId == null) {
            logW { "Unknown source node ID in route advertisement: $sourceIdEncoded" }
            return null
        }

        // Read routes
        val routes = mutableListOf<AdvertisedRoute>()
        for (i in 0 until routeCount) {
            val destIdEncoded = buffer.long
            val metric = buffer.get().toInt() and 0xFF
            val hopCount = buffer.get().toInt() and 0xFF

            val destination = NodeIdEncoder.decode(destIdEncoded)
            if (destination == null) {
                logW { "Unknown destination node ID in route: $destIdEncoded" }
                continue // Skip unknown destinations but continue parsing
            }

            routes.add(
                AdvertisedRoute(
                    destination = destination,
                    metric = metric,
                    hopCount = hopCount,
                ),
            )
        }

        logD { "Deserialized route advertisement from $sourceNodeId: ${routes.size} routes" }

        return RouteAdvertisement(
            version = version.toInt(),
            sourceNodeId = sourceNodeId,
            sequenceNumber = sequenceNumber,
            routes = routes,
            timestamp = timestamp,
        )
    }

    /**
     * Calculate the serialized size of a route advertisement.
     *
     * @param routeCount Number of routes
     * @return Size in bytes
     */
    fun calculateSize(routeCount: Int): Int {
        return HEADER_SIZE + minOf(routeCount, MAX_ROUTES) * ROUTE_ENTRY_SIZE
    }
}
