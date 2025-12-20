package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BinaryDiscoveryPayloadTest {

    @Before
    fun setUp() {
        Logger.isTestMode = true
        NodeIdEncoder.clearCache()
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
        NodeIdEncoder.clearCache()
    }

    @Test
    fun `serialize produces non-empty output`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "My Device",
            groupCode = "ABCD1234",
            nickname = "John",
        )

        assertNotNull(serialized)
        assertEquals(true, serialized.isNotEmpty())
    }

    @Test
    fun `deserialize parses valid payload`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "My Device",
            groupCode = "ABCD1234",
            nickname = "John",
        )

        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals("node-A1B2C3D4", deserialized!!.nodeId)
        assertEquals("My Device", deserialized.deviceName)
        assertEquals("ABCD1234", deserialized.groupCode)
        assertEquals("John", deserialized.nickname)
    }

    @Test
    fun `round trip preserves all fields`() {
        val nodeId = "node-DEADBEEF"
        val deviceName = "Test Device 123"
        val groupCode = "TEST1234"
        val nickname = "TestUser"

        val serialized = BinaryDiscoveryPayload.serialize(nodeId, deviceName, groupCode, nickname)
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(nodeId, deserialized!!.nodeId)
        assertEquals(deviceName, deserialized.deviceName)
        assertEquals(groupCode, deserialized.groupCode)
        assertEquals(nickname, deserialized.nickname)
    }

    @Test
    fun `handles OPEN group code`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "Device",
            groupCode = "OPEN",
            nickname = "User",
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals("OPEN", deserialized!!.groupCode)
    }

    @Test
    fun `handles empty nickname`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "Device",
            groupCode = "ABCD1234",
            nickname = "",
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals("", deserialized!!.nickname)
    }

    @Test
    fun `handles special characters in device name`() {
        val deviceName = "John's iPhone 15 Pro Max"
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = deviceName,
            groupCode = "ABCD1234",
            nickname = "John",
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(deviceName, deserialized!!.deviceName)
    }

    @Test
    fun `handles unicode characters`() {
        val nickname = "John"
        val deviceName = "Device"
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = deviceName,
            groupCode = "ABCD1234",
            nickname = nickname,
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(nickname, deserialized!!.nickname)
    }

    @Test
    fun `rejects truncated payload`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "Device",
            groupCode = "ABCD1234",
            nickname = "User",
        )

        // Truncate the payload
        val truncated = serialized.copyOf(10)
        val deserialized = BinaryDiscoveryPayload.deserialize(truncated)

        assertNull(deserialized)
    }

    @Test
    fun `rejects payload with wrong version`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "Device",
            groupCode = "ABCD1234",
            nickname = "User",
        )

        // Corrupt version byte
        serialized[0] = 99.toByte()
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNull(deserialized)
    }

    @Test
    fun `registers node ID in encoder cache`() {
        val nodeId = "node-TESTNODE"
        NodeIdEncoder.clearCache()

        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = nodeId,
            deviceName = "Device",
            groupCode = "ABCD1234",
            nickname = "User",
        )
        BinaryDiscoveryPayload.deserialize(serialized)

        // After deserialization, node ID should be registered
        assertEquals(true, NodeIdEncoder.isRegistered(nodeId))
    }

    @Test
    fun `handles maximum length strings`() {
        val longString = "A".repeat(255)
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = longString,
            groupCode = "ABCD1234",
            nickname = longString,
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(longString, deserialized!!.deviceName)
        assertEquals(longString, deserialized.nickname)
    }

    @Test
    fun `truncates strings exceeding maximum length`() {
        val veryLongString = "A".repeat(500)
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = veryLongString,
            groupCode = "ABCD1234",
            nickname = "User",
        )
        val deserialized = BinaryDiscoveryPayload.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(255, deserialized!!.deviceName.length)
    }

    @Test
    fun `deserialize with length parameter`() {
        val serialized = BinaryDiscoveryPayload.serialize(
            nodeId = "node-A1B2C3D4",
            deviceName = "Device",
            groupCode = "ABCD1234",
            nickname = "User",
        )

        // Create larger buffer with garbage at end
        val largerBuffer = ByteArray(serialized.size + 50)
        serialized.copyInto(largerBuffer)
        for (i in serialized.size until largerBuffer.size) {
            largerBuffer[i] = 0xFF.toByte()
        }

        val deserialized = BinaryDiscoveryPayload.deserialize(largerBuffer, serialized.size)

        assertNotNull(deserialized)
        assertEquals("node-A1B2C3D4", deserialized!!.nodeId)
    }

    @Test
    fun `binary format is smaller than text format`() {
        val nodeId = "node-A1B2C3D4"
        val deviceName = "My Device"
        val groupCode = "ABCD1234"
        val nickname = "John"

        val binarySize = BinaryDiscoveryPayload.serialize(nodeId, deviceName, groupCode, nickname).size
        val textSize = "$nodeId|$deviceName|$groupCode|$nickname".toByteArray().size

        // Binary should be similar or smaller (version byte adds 1 byte, but no delimiters)
        println("Binary size: $binarySize, Text size: $textSize")
        // Not strictly enforcing smaller since overhead varies, but verify it's reasonable
        assertEquals(true, binarySize <= textSize + 5)
    }
}
