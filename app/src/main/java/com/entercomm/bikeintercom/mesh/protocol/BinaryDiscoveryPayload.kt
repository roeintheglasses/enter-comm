package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.util.logW
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary serialization for discovery message payloads.
 *
 * Binary format:
 * ```
 * Offset  Size  Field
 * ------  ----  -----
 * 0       1     Version (0x01)
 * 1       1     Node ID length (N1)
 * 2       N1    Node ID (UTF-8)
 * 2+N1    1     Device name length (N2)
 * 3+N1    N2    Device name (UTF-8)
 * 3+N1+N2 1     Group code length (N3)
 * 4+N1+N2 N3    Group code (UTF-8)
 * 4+N1+N2+N3  1     Nickname length (N4)
 * 5+N1+N2+N3  N4    Nickname (UTF-8)
 * ```
 *
 * The full node ID string is included (not hashed) so receivers can
 * register it in the NodeIdEncoder cache for decoding other messages.
 *
 * Size comparison (typical discovery payload):
 * - Text format: "node-A1B2C3D4|MyDevice|ABCD1234|John" = ~45 bytes
 * - Binary format: 1 + 1 + 14 + 1 + 8 + 1 + 8 + 1 + 4 = ~39 bytes
 * - Savings: ~13%
 */
object BinaryDiscoveryPayload {

    /** Binary discovery payload format version. */
    const val VERSION: Byte = 1

    /** Maximum length for any string field. */
    const val MAX_STRING_LENGTH = 255

    /** Minimum payload size: version + 4 length bytes. */
    private const val MIN_PAYLOAD_SIZE = 5

    /**
     * Discovery payload data.
     */
    data class Payload(
        val nodeId: String,
        val deviceName: String,
        val groupCode: String,
        val nickname: String,
    )

    /**
     * Serialize a discovery payload to binary format.
     *
     * @param nodeId The node ID string
     * @param deviceName The device name
     * @param groupCode The group code (or "OPEN")
     * @param nickname The user's nickname
     * @return Binary representation
     */
    fun serialize(nodeId: String, deviceName: String, groupCode: String, nickname: String): ByteArray {
        val nodeIdBytes = nodeId.toByteArray(Charsets.UTF_8).take(MAX_STRING_LENGTH).toByteArray()
        val deviceNameBytes = deviceName.toByteArray(Charsets.UTF_8).take(MAX_STRING_LENGTH).toByteArray()
        val groupCodeBytes = groupCode.toByteArray(Charsets.UTF_8).take(MAX_STRING_LENGTH).toByteArray()
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8).take(MAX_STRING_LENGTH).toByteArray()

        val totalSize = 1 + // version
            1 + nodeIdBytes.size +
            1 + deviceNameBytes.size +
            1 + groupCodeBytes.size +
            1 + nicknameBytes.size

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(VERSION)
        buffer.put(nodeIdBytes.size.toByte())
        buffer.put(nodeIdBytes)
        buffer.put(deviceNameBytes.size.toByte())
        buffer.put(deviceNameBytes)
        buffer.put(groupCodeBytes.size.toByte())
        buffer.put(groupCodeBytes)
        buffer.put(nicknameBytes.size.toByte())
        buffer.put(nicknameBytes)

        return buffer.array()
    }

    /**
     * Deserialize binary data to a discovery payload.
     *
     * @param data Raw binary data
     * @return Parsed Payload, or null if parsing failed
     */
    fun deserialize(data: ByteArray): Payload? {
        return deserialize(data, data.size)
    }

    /**
     * Deserialize binary data to a discovery payload.
     *
     * @param data Raw binary data
     * @param length Number of valid bytes in the data array
     * @return Parsed Payload, or null if parsing failed
     */
    fun deserialize(data: ByteArray, length: Int): Payload? {
        val buffer = validateAndWrapBuffer(data, length) ?: return null
        val fields = parseFields(buffer) ?: return null

        // Register the node ID for decoding (important for binary protocol)
        NodeIdEncoder.register(fields.nodeId)

        return fields
    }

    /**
     * Validate minimum length and version, return wrapped buffer if valid.
     */
    private fun validateAndWrapBuffer(data: ByteArray, length: Int): ByteBuffer? {
        if (length < MIN_PAYLOAD_SIZE) {
            logW { "Discovery payload too short: $length bytes" }
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        val version = buffer.get()
        if (version != VERSION) {
            logW { "Unsupported discovery payload version: $version" }
            return null
        }

        return buffer
    }

    /**
     * Parse all string fields from the buffer.
     */
    private fun parseFields(buffer: ByteBuffer): Payload? {
        val nodeId = readString(buffer, "nodeId", remainingAfter = 3) ?: return null
        val deviceName = readString(buffer, "deviceName", remainingAfter = 2) ?: return null
        val groupCode = readString(buffer, "groupCode", remainingAfter = 1) ?: return null
        val nickname = readString(buffer, "nickname", remainingAfter = 0) ?: return null

        return Payload(
            nodeId = nodeId,
            deviceName = deviceName,
            groupCode = groupCode,
            nickname = nickname,
        )
    }

    /**
     * Read a length-prefixed string from the buffer.
     *
     * @param buffer The buffer to read from
     * @param fieldName Name of the field for error logging
     * @param remainingAfter Expected minimum bytes remaining after this field
     * @return The parsed string, or null if truncated
     */
    private fun readString(buffer: ByteBuffer, fieldName: String, remainingAfter: Int): String? {
        val strLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < strLen + remainingAfter) {
            logW { "Discovery payload truncated at $fieldName" }
            return null
        }
        val bytes = ByteArray(strLen)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
