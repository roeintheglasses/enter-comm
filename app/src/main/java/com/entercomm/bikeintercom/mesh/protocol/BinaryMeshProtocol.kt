package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logW
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary mesh protocol implementation for efficient message serialization.
 *
 * Wire format (39 bytes header + N bytes payload):
 * ```
 * Offset  Size  Field
 * ------  ----  -----
 * 0       1     Magic byte (0xEC)
 * 1       1     Version (0x10 = v1.0)
 * 2       1     Flags (reserved)
 * 3       1     Message type (0-6)
 * 4       1     TTL
 * 5       8     Source ID (SHA-256 hash)
 * 13      8     Destination ID (hash, 0xFF..FF = broadcast)
 * 21      8     Message ID (first 8 bytes of UUID)
 * 29      8     Timestamp (milliseconds)
 * 37      2     Payload length
 * 39      N     Payload bytes
 * ```
 *
 * Benefits over pipe-delimited format:
 * - ~60% smaller header (39 bytes vs ~100 bytes)
 * - Faster parsing (no string splitting)
 * - Fixed-size fields for predictable memory access
 */
class BinaryMeshProtocol : MeshProtocol {

    override val protocolVersion: String = VERSION_STRING

    override val protocolDescription: String =
        "Binary protocol v$VERSION_STRING"

    // Thread-local buffer for serialization to avoid allocation on hot path
    private val serializeBuffer = ThreadLocal.withInitial {
        ByteBuffer.allocate(MAX_MESSAGE_SIZE).order(ByteOrder.BIG_ENDIAN)
    }

    /**
     * Serialize a MeshMessage to binary format.
     *
     * @param message The message to serialize
     * @return Byte array in binary wire format
     */
    override fun serialize(message: MeshMessage): ByteArray {
        val payloadSize = message.payload.size
        if (payloadSize > MAX_PAYLOAD_SIZE) {
            logW { "Payload size $payloadSize exceeds maximum $MAX_PAYLOAD_SIZE, truncating" }
        }

        val effectivePayloadSize = minOf(payloadSize, MAX_PAYLOAD_SIZE)
        val totalSize = HEADER_SIZE + effectivePayloadSize

        val buffer = serializeBuffer.get()
        buffer.clear()

        // Write header
        buffer.put(MAGIC_BYTE)
        buffer.put(VERSION)
        buffer.put(0) // Flags (reserved)
        buffer.put(message.messageType.ordinal.toByte())
        buffer.put(message.ttl.toByte())
        buffer.putLong(NodeIdEncoder.encode(message.sourceId))
        buffer.putLong(NodeIdEncoder.encode(message.destinationId))
        buffer.putLong(encodeMessageId(message.messageId))
        buffer.putLong(message.timestamp)
        buffer.putShort(effectivePayloadSize.toShort())

        // Write payload
        buffer.put(message.payload, 0, effectivePayloadSize)

        // Extract result
        val result = ByteArray(totalSize)
        buffer.flip()
        buffer.get(result)

        logD { "Serialized ${message.messageType} message: $totalSize bytes" }
        return result
    }

    /**
     * Deserialize binary bytes into a MeshMessage.
     *
     * @param data Raw bytes received
     * @param length Number of valid bytes in the data array
     * @return Parsed MeshMessage, or null if parsing failed
     */
    @Suppress("ReturnCount")
    override fun deserialize(data: ByteArray, length: Int): MeshMessage? {
        // Validate minimum length
        if (length < HEADER_SIZE) {
            logW { "Message too short: $length bytes (minimum: $HEADER_SIZE)" }
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        // Validate magic byte
        val magic = buffer.get()
        if (magic != MAGIC_BYTE) {
            logW { "Invalid magic byte: ${magic.toInt() and 0xFF} (expected: ${MAGIC_BYTE.toInt() and 0xFF})" }
            return null
        }

        // Validate version (accept same major version)
        val version = buffer.get()
        val majorVersion = version.toInt() and 0xF0 shr 4
        if (majorVersion != 1) {
            logW { "Unsupported protocol version: $majorVersion" }
            return null
        }

        // Skip flags
        buffer.get()

        // Parse message type
        val typeOrdinal = buffer.get().toInt() and 0xFF
        val messageType = MeshMessage.MessageType.entries.getOrNull(typeOrdinal)
        if (messageType == null) {
            logW { "Unknown message type: $typeOrdinal" }
            return null
        }

        // Parse remaining header fields
        val ttl = buffer.get().toInt() and 0xFF
        val sourceIdEncoded = buffer.long
        val destIdEncoded = buffer.long
        val messageIdEncoded = buffer.long
        val timestamp = buffer.long
        val payloadLength = buffer.short.toInt() and 0xFFFF

        // Validate payload length
        val expectedTotal = HEADER_SIZE + payloadLength
        if (length < expectedTotal) {
            logW { "Truncated message: got $length bytes, expected $expectedTotal" }
            return null
        }

        // Extract payload
        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        // Decode node IDs
        val sourceId = NodeIdEncoder.decode(sourceIdEncoded)
        val destinationId = NodeIdEncoder.decode(destIdEncoded)

        if (sourceId == null) {
            logW { "Unknown source node ID: $sourceIdEncoded" }
            return null
        }

        if (destinationId == null) {
            logW { "Unknown destination node ID: $destIdEncoded" }
            return null
        }

        val message = MeshMessage(
            messageId = decodeMessageId(messageIdEncoded),
            sourceId = sourceId,
            destinationId = destinationId,
            messageType = messageType,
            ttl = ttl,
            timestamp = timestamp,
            payload = payload,
        )

        logD { "Deserialized ${message.messageType} from ${message.sourceId}" }
        return message
    }

    /**
     * Encode a UUID string to 8-byte long.
     * Uses first 8 bytes of the UUID (most significant bits).
     */
    private fun encodeMessageId(messageId: String): Long {
        return try {
            val uuid = UUID.fromString(messageId)
            uuid.mostSignificantBits
        } catch (e: IllegalArgumentException) {
            // Not a valid UUID, hash the string instead
            logD { "MessageId '$messageId' is not a UUID, using hash: ${e.message}" }
            messageId.hashCode().toLong()
        }
    }

    /**
     * Decode 8-byte long back to UUID string representation.
     * Creates a UUID with the encoded value as most significant bits and zeros for least significant.
     */
    private fun decodeMessageId(encoded: Long): String {
        return UUID(encoded, 0L).toString()
    }

    companion object {
        private const val VERSION_STRING = "2.0"

        /** Magic byte to identify binary protocol messages. */
        const val MAGIC_BYTE: Byte = 0xEC.toByte()

        /** Protocol version: major in high nibble, minor in low nibble. */
        const val VERSION: Byte = 0x10 // v1.0

        /** Fixed header size in bytes. */
        const val HEADER_SIZE = 39

        /** Maximum supported payload size. */
        const val MAX_PAYLOAD_SIZE = 65_535

        /** Maximum total message size. */
        const val MAX_MESSAGE_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE
    }
}
