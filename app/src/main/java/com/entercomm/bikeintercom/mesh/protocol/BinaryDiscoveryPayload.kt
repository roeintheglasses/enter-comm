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
    @Suppress("ReturnCount")
    fun deserialize(data: ByteArray, length: Int): Payload? {
        if (length < 5) { // Minimum: version + 4 length bytes
            logW { "Discovery payload too short: $length bytes" }
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        // Check version
        val version = buffer.get()
        if (version != VERSION) {
            logW { "Unsupported discovery payload version: $version" }
            return null
        }

        // Read nodeId
        val nodeIdLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < nodeIdLen + 3) {
            logW { "Discovery payload truncated at nodeId" }
            return null
        }
        val nodeIdBytes = ByteArray(nodeIdLen)
        buffer.get(nodeIdBytes)
        val nodeId = String(nodeIdBytes, Charsets.UTF_8)

        // Read deviceName
        val deviceNameLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < deviceNameLen + 2) {
            logW { "Discovery payload truncated at deviceName" }
            return null
        }
        val deviceNameBytes = ByteArray(deviceNameLen)
        buffer.get(deviceNameBytes)
        val deviceName = String(deviceNameBytes, Charsets.UTF_8)

        // Read groupCode
        val groupCodeLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < groupCodeLen + 1) {
            logW { "Discovery payload truncated at groupCode" }
            return null
        }
        val groupCodeBytes = ByteArray(groupCodeLen)
        buffer.get(groupCodeBytes)
        val groupCode = String(groupCodeBytes, Charsets.UTF_8)

        // Read nickname
        val nicknameLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < nicknameLen) {
            logW { "Discovery payload truncated at nickname" }
            return null
        }
        val nicknameBytes = ByteArray(nicknameLen)
        buffer.get(nicknameBytes)
        val nickname = String(nicknameBytes, Charsets.UTF_8)

        // Register the node ID for decoding (important for binary protocol)
        NodeIdEncoder.register(nodeId)

        return Payload(
            nodeId = nodeId,
            deviceName = deviceName,
            groupCode = groupCode,
            nickname = nickname,
        )
    }
}
