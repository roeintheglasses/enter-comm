package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.mesh.NetworkStats
import com.entercomm.bikeintercom.util.logD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects and manages network statistics for the mesh network.
 * Tracks packets, bytes, and message type-specific counters.
 */
class NetworkStatsCollector {

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    // Network statistics counters
    private var statsPacketsSent = 0L
    private var statsPacketsReceived = 0L
    private var statsBytesSent = 0L
    private var statsBytesReceived = 0L
    private var statsDiscoveryRequestsSent = 0L
    private var statsDiscoveryResponsesReceived = 0L
    private var statsAudioPacketsSent = 0L
    private var statsAudioPacketsReceived = 0L
    private var statsHeartbeatsSent = 0L
    private var statsHeartbeatsReceived = 0L
    private var networkStartTime = 0L

    /**
     * Start tracking statistics. Records the start time for uptime calculation.
     */
    fun start() {
        networkStartTime = System.currentTimeMillis()
        reset()
    }

    /**
     * Record a packet sent with its size and message type.
     */
    fun recordPacketSent(bytes: Int, messageType: MeshMessage.MessageType) {
        statsPacketsSent++
        statsBytesSent += bytes
        when (messageType) {
            MeshMessage.MessageType.DISCOVERY -> statsDiscoveryRequestsSent++
            MeshMessage.MessageType.AUDIO_DATA -> statsAudioPacketsSent++
            MeshMessage.MessageType.HEARTBEAT -> statsHeartbeatsSent++
            else -> { /* Other types not tracked separately */ }
        }
    }

    /**
     * Record a packet received with its size and message type.
     */
    fun recordPacketReceived(bytes: Int, messageType: MeshMessage.MessageType) {
        statsPacketsReceived++
        statsBytesReceived += bytes
        when (messageType) {
            MeshMessage.MessageType.DISCOVERY -> statsDiscoveryResponsesReceived++
            MeshMessage.MessageType.AUDIO_DATA -> statsAudioPacketsReceived++
            MeshMessage.MessageType.HEARTBEAT -> statsHeartbeatsReceived++
            else -> { /* Other types not tracked separately */ }
        }
    }

    /**
     * Update the network statistics snapshot exposed via StateFlow.
     * Should be called periodically (e.g., during heartbeat service).
     */
    fun updateStats() {
        _networkStats.value = NetworkStats(
            packetsSent = statsPacketsSent,
            packetsReceived = statsPacketsReceived,
            bytesSent = statsBytesSent,
            bytesReceived = statsBytesReceived,
            discoveryRequestsSent = statsDiscoveryRequestsSent,
            discoveryResponsesReceived = statsDiscoveryResponsesReceived,
            audioPacketsSent = statsAudioPacketsSent,
            audioPacketsReceived = statsAudioPacketsReceived,
            heartbeatsSent = statsHeartbeatsSent,
            heartbeatsReceived = statsHeartbeatsReceived,
            lastUpdateTime = System.currentTimeMillis(),
        )
    }

    /**
     * Reset all network statistics counters.
     */
    fun reset() {
        statsPacketsSent = 0
        statsPacketsReceived = 0
        statsBytesSent = 0
        statsBytesReceived = 0
        statsDiscoveryRequestsSent = 0
        statsDiscoveryResponsesReceived = 0
        statsAudioPacketsSent = 0
        statsAudioPacketsReceived = 0
        statsHeartbeatsSent = 0
        statsHeartbeatsReceived = 0
        networkStartTime = System.currentTimeMillis()
        updateStats()
        logD { "Network statistics reset" }
    }

    /**
     * Get network uptime in milliseconds.
     */
    fun getUptime(): Long {
        return if (networkStartTime > 0) {
            System.currentTimeMillis() - networkStartTime
        } else {
            0
        }
    }

    /**
     * Get the network start time timestamp.
     */
    fun getStartTime(): Long = networkStartTime
}
