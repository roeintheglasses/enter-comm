package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW

/**
 * Pipe-delimited text-based mesh protocol implementation.
 *
 * Message format:
 * ```
 * messageId|sourceId|destinationId|messageType|ttl|timestamp|<payload bytes>
 * ```
 *
 * This protocol is:
 * - Human-readable for debugging
 * - Simple to implement
 * - Compatible with existing mesh network nodes
 *
 * Limitations:
 * - Larger than binary format (~30% overhead)
 * - Requires sanitization of pipe characters in fields
 * - Not suitable for binary payloads containing pipe characters in header position
 *
 * For higher performance or binary payloads, consider BinaryMeshProtocol.
 */
class PipeDelimitedMeshProtocol : MeshProtocol {

    companion object {
        private const val VERSION = "1.0"
        private const val FIELD_DELIMITER = '|'
        private const val HEADER_FIELD_COUNT = 6
    }

    override val protocolVersion: String = VERSION

    override val protocolDescription: String =
        "Pipe-delimited text protocol v$VERSION"

    /**
     * Serialize a MeshMessage to pipe-delimited format.
     *
     * Format: messageId|sourceId|destinationId|messageType|ttl|timestamp|<payload>
     */
    override fun serialize(message: MeshMessage): ByteArray {
        val header = buildString {
            append(message.messageId)
            append(FIELD_DELIMITER)
            append(message.sourceId)
            append(FIELD_DELIMITER)
            append(message.destinationId)
            append(FIELD_DELIMITER)
            append(message.messageType.name)
            append(FIELD_DELIMITER)
            append(message.ttl)
            append(FIELD_DELIMITER)
            append(message.timestamp)
            append(FIELD_DELIMITER)
        }

        // Combine header bytes with payload
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val result = ByteArray(headerBytes.size + message.payload.size)
        headerBytes.copyInto(result)
        message.payload.copyInto(result, headerBytes.size)

        return result
    }

    /**
     * Deserialize pipe-delimited bytes into a MeshMessage.
     *
     * @param data Raw bytes received
     * @param length Number of valid bytes in the data array
     * @return Parsed MeshMessage, or null if parsing failed
     */
    override fun deserialize(data: ByteArray, length: Int): MeshMessage? {
        if (length < HEADER_FIELD_COUNT + 1) {
            logW { "Message too short: $length bytes" }
            return null
        }

        try {
            val dataString = String(data, 0, length, Charsets.UTF_8)
            logD { "Deserializing message: ${dataString.take(100)}..." }

            // Find the position of the 6th pipe character (after timestamp)
            val headerEnd = findHeaderEnd(dataString)
            if (headerEnd == -1) {
                logW { "Could not find $HEADER_FIELD_COUNT pipe characters in message header" }
                return null
            }

            // Parse header fields
            val headerParts = dataString.substring(0, headerEnd).split(FIELD_DELIMITER)
            if (headerParts.size != HEADER_FIELD_COUNT) {
                logW { "Expected $HEADER_FIELD_COUNT header parts, got ${headerParts.size}" }
                return null
            }

            // Extract payload (everything after the 6th pipe)
            val payload = data.copyOfRange(headerEnd + 1, length)

            val message = MeshMessage(
                messageId = headerParts[0],
                sourceId = headerParts[1],
                destinationId = headerParts[2],
                messageType = parseMessageType(headerParts[3]) ?: return null,
                ttl = headerParts[4].toIntOrNull() ?: return null,
                timestamp = headerParts[5].toLongOrNull() ?: return null,
                payload = payload,
            )

            logD { "Successfully deserialized: ${message.messageType} from ${message.sourceId}" }
            return message
        } catch (e: Exception) {
            logE({ "Failed to deserialize message" }, e)
            return null
        }
    }

    /**
     * Find the position of the last header delimiter (6th pipe).
     */
    private fun findHeaderEnd(data: String): Int {
        var pipeCount = 0
        for (i in data.indices) {
            if (data[i] == FIELD_DELIMITER) {
                pipeCount++
                if (pipeCount == HEADER_FIELD_COUNT) {
                    return i
                }
            }
        }
        return -1
    }

    /**
     * Parse message type from string, with error handling.
     */
    private fun parseMessageType(typeStr: String): MeshMessage.MessageType? {
        return try {
            MeshMessage.MessageType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            logW { "Unknown message type: $typeStr" }
            null
        }
    }
}
