package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage

/**
 * Interface for mesh network message serialization and deserialization.
 *
 * This abstraction layer allows for different protocol implementations:
 * - PipeDelimitedMeshProtocol: Current text-based format (human-readable, debuggable)
 * - BinaryMeshProtocol: Future binary format (smaller, faster)
 * - EncryptedMeshProtocol: Future encrypted wrapper
 *
 * All implementations must handle the complete MeshMessage structure including:
 * - Message ID (UUID)
 * - Source and destination node IDs
 * - Message type
 * - TTL (time-to-live)
 * - Timestamp
 * - Variable-length payload
 */
interface MeshProtocol {
    /**
     * Serialize a MeshMessage to bytes for network transmission.
     *
     * @param message The message to serialize
     * @return Byte array ready for transmission
     */
    fun serialize(message: MeshMessage): ByteArray

    /**
     * Deserialize bytes received from the network into a MeshMessage.
     *
     * @param data Raw bytes received
     * @param length Number of valid bytes in the data array
     * @return Parsed MeshMessage, or null if parsing failed
     */
    fun deserialize(data: ByteArray, length: Int): MeshMessage?

    /**
     * Get the protocol version identifier.
     * Used for protocol negotiation and debugging.
     */
    val protocolVersion: String

    /**
     * Get a human-readable description of the protocol.
     */
    val protocolDescription: String

    companion object {
        /**
         * Create the default protocol implementation.
         * Uses binary protocol for efficient serialization.
         */
        fun default(): MeshProtocol = BinaryMeshProtocol()
    }
}
