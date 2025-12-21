package com.entercomm.bikeintercom.mesh

import java.util.UUID

/**
 * Represents a peer node in the mesh network.
 */
data class MeshNode(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val isDirectConnection: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
    val hopCount: Int = 1,
    val linkQuality: Float = 1.0f,
)

/**
 * Represents a route to a destination in the mesh network.
 */
data class MeshRoute(
    val destinationId: String,
    val nextHop: String,
    val hopCount: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)

/**
 * A message sent over the mesh network.
 */
data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String,
    val messageType: MessageType,
    val payload: ByteArray,
    val ttl: Int = 10,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class MessageType {
        DISCOVERY,
        ROUTE_UPDATE,
        AUDIO_DATA,
        CONTROL,
        HEARTBEAT,
        GROUP,
        LOCATION,
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshMessage

        if (messageId != other.messageId) return false
        if (sourceId != other.sourceId) return false
        if (destinationId != other.destinationId) return false
        if (messageType != other.messageType) return false
        if (!payload.contentEquals(other.payload)) return false
        if (ttl != other.ttl) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Routing statistics for monitoring and debugging.
 */
data class RoutingStats(
    val totalRoutes: Int = 0,
    val directNeighbors: Int = 0,
    val multiHopRoutes: Int = 0,
    val maxHopCount: Int = 0,
    val messagesRouted: Long = 0,
    val messagesForwarded: Long = 0,
    val messagesDropped: Long = 0,
    val routeAdvertisementsSent: Long = 0,
)

/**
 * Comprehensive network statistics for diagnostics UI.
 */
data class NetworkStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val discoveryRequestsSent: Long = 0,
    val discoveryResponsesReceived: Long = 0,
    val audioPacketsSent: Long = 0,
    val audioPacketsReceived: Long = 0,
    val heartbeatsSent: Long = 0,
    val heartbeatsReceived: Long = 0,
    val lastUpdateTime: Long = System.currentTimeMillis(),
) {
    /** Calculate packet loss percentage (0-100) */
    val packetLossPercent: Float
        get() {
            if (packetsSent == 0L) return 0f
            val expectedResponses = packetsSent
            val actualResponses = packetsReceived
            if (actualResponses >= expectedResponses) return 0f
            return ((expectedResponses - actualResponses) * 100f / expectedResponses).coerceIn(0f, 100f)
        }

    /** Get formatted uptime string */
    @Suppress("MagicNumber")
    fun getUptimeString(startTime: Long): String {
        val uptimeMs = System.currentTimeMillis() - startTime
        val seconds = uptimeMs / 1000 % 60
        val minutes = uptimeMs / (1000 * 60) % 60
        val hours = uptimeMs / (1000 * 60 * 60)
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}

/**
 * Validated discovery message payload.
 */
data class DiscoveryPayload(
    val nodeId: String,
    val deviceName: String,
    val groupCode: String,
    val nickname: String,
)
